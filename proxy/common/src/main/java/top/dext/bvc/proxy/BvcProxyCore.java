package top.dext.bvc.proxy;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.Channel;
import top.dext.bvc.api.Mute;
import top.dext.bvc.api.MuteListener;
import top.dext.bvc.api.MuteScope;
import top.dext.bvc.api.MuteService;
import top.dext.bvc.api.proxy.BvcProxy;
import top.dext.bvc.api.proxy.BvcProxyApi;
import top.dext.bvc.api.proxy.ReportService;
import top.dext.bvc.api.proxy.VoiceListener;
import top.dext.bvc.api.proxy.VoicePlayer;
import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.ProtocolException;
import top.dext.bvc.protocol.crypto.BvcCrypto;
import top.dext.bvc.protocol.data.Transport;
import top.dext.bvc.protocol.packet.ClientToProxy;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.protocol.packet.ProxyToClient;
import top.dext.bvc.proxy.backend.BackendBridge;
import top.dext.bvc.proxy.command.BvcCommand;
import top.dext.bvc.proxy.command.Durations;
import top.dext.bvc.proxy.config.BvcConfig;
import top.dext.bvc.proxy.config.Messages;
import top.dext.bvc.proxy.mute.MuteManager;
import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.platform.PlatformPlayer;
import top.dext.bvc.proxy.platform.ProxyPlatform;
import top.dext.bvc.proxy.recording.HeardLog;
import top.dext.bvc.proxy.recording.Recorder;
import top.dext.bvc.proxy.recording.RecordingIndex;
import top.dext.bvc.proxy.report.ReportCommand;
import top.dext.bvc.proxy.report.ReportManager;
import top.dext.bvc.proxy.report.ReportStore;
import top.dext.bvc.proxy.routing.RouteRegistry;
import top.dext.bvc.proxy.routing.ServerRoutes;
import top.dext.bvc.proxy.routing.VoiceRouter;
import top.dext.bvc.proxy.session.SessionManager;
import top.dext.bvc.proxy.session.StreamConnection;
import top.dext.bvc.proxy.session.VoiceSession;
import top.dext.bvc.proxy.storage.Database;
import top.dext.bvc.proxy.storage.Stores;
import top.dext.bvc.proxy.transport.StreamDetector;
import top.dext.bvc.proxy.transport.StreamGateway;
import top.dext.bvc.proxy.web.WebAuth;
import top.dext.bvc.proxy.web.WebServer;

/**
 * Platform-independent heart of the proxy plugin. The Velocity and BungeeCord modules forward
 * their events here and implement {@link ProxyPlatform}.
 */
public final class BvcProxyCore implements BvcProxyApi, StreamGateway {

    private static final long TOKEN_LIFETIME_MILLIS = 30_000;

    private final ProxyPlatform platform;
    private final BvcLogger logger;
    private final Clock clock;
    private final SessionManager sessions = new SessionManager();
    private final RouteRegistry routes = new RouteRegistry();
    private final AtomicInteger pendingStreams = new AtomicInteger();
    private final List<ProxyPlatform.Cancellable> tasks = new ArrayList<>();
    private final List<VoiceListener> voiceListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile BvcConfig config;
    private volatile Messages messages;
    private Database database;
    private Stores stores;
    private MuteManager mutes;
    private VoiceRouter router;
    private BackendBridge backend;
    private BvcCommand command;
    private Recorder recorder;
    private ReportManager reports;
    private ReportCommand reportCommand;
    private WebAuth webAuth;
    private WebServer web;

    public BvcProxyCore(ProxyPlatform platform) {
        this(platform, Clock.systemUTC());
    }

    BvcProxyCore(ProxyPlatform platform, Clock clock) {
        this.platform = platform;
        this.logger = platform.logger();
        this.clock = clock;
    }

    // ------------------------------------------------------------------ lifecycle

    public void enable() throws Exception {
        Path data = platform.dataDirectory();
        config = BvcConfig.load(data.resolve("config.yml"));
        messages = Messages.load(data.resolve("messages.yml"));
        database = Database.openH2(data.resolve("data"));
        stores = new Stores(database);
        mutes = new MuteManager(stores, clock, logger, this::onMutesChanged, this::onMuteSwitchChanged);
        mutes.load();
        mutes.addListener(new MuteNotifier());
        router = new VoiceRouter(sessions, routes, mutes, () -> config, clock);
        backend = new BackendBridge(sessions, routes, router, mutes, logger, platform.pluginVersion());

        recorder = new Recorder(data.resolve("recordings"), new RecordingIndex(database), new HeardLog(heardRetention()),
                () -> config, logger);
        recorder.start();
        router.setSink(recorder);
        reports = new ReportManager(new ReportStore(database), recorder, data.resolve("reports"), () -> config, logger,
                this::onReportReady);
        reports.resumePending();
        reportCommand = new ReportCommand(this);
        webAuth = new WebAuth(database);
        if (config.web().enabled()) {
            web = new WebServer(webAuth, reports, recorder, mutes, stores, () -> config, logger);
            try {
                web.start();
            } catch (java.io.IOException e) {
                logger.error("Could not start the web panel on " + config.web().bind() + ":" + config.web().port(), e);
                web = null;
            }
        }
        command = new BvcCommand(this);

        tasks.add(platform.scheduleRepeating(mutes::purgeExpired, 5, 5, TimeUnit.SECONDS));
        tasks.add(platform.scheduleRepeating(this::refreshAllPermissions, 30, 30, TimeUnit.SECONDS));
        tasks.add(platform.scheduleRepeating(() -> {
            long now = clock.millis();
            webAuth.purge(now);
            reports.purgeExpired(now);
        }, 1, 60, TimeUnit.MINUTES));

        if (!BvcCrypto.isKeyExchangeSupported()) {
            logger.warn("This Java runtime lacks EC key exchange; stream transport disabled, clients will use plugin messages");
        }
        // Players already online (plugin loaded late, or /reload of the proxy plugin).
        for (PlatformPlayer player : platform.players()) {
            onPlayerJoin(player);
            if (player.currentServer() != null) {
                onServerConnected(player, player.currentServer());
            }
        }
        BvcProxy.register(this);
        logger.info("BetterVoiceChat " + platform.pluginVersion() + " enabled on " + platform.platformName()
                + " (transport " + config.transportPolicy() + ")");
    }

    public void disable() {
        BvcProxy.register(null);
        for (ProxyPlatform.Cancellable task : tasks) {
            task.cancel();
        }
        tasks.clear();
        for (VoiceSession session : new ArrayList<>(sessions.all())) {
            sessions.remove(session.uuid());
        }
        if (web != null) {
            web.stop();
            web = null;
        }
        if (router != null) {
            router.setSink(null);
        }
        if (recorder != null) {
            recorder.close();
        }
        if (reports != null) {
            reports.close();
        }
        if (database != null) {
            database.close();
        }
    }

    public void reload() throws Exception {
        Path data = platform.dataDirectory();
        config = BvcConfig.load(data.resolve("config.yml"));
        messages = Messages.load(data.resolve("messages.yml"));
        recorder.heard().setRetention(heardRetention());
        refreshAllPermissions();
        for (VoiceSession session : sessions.all()) {
            router.pushVoiceState(session);
        }
    }

    // ------------------------------------------------------------------ platform events

    /** Installs the stream detector on a newly accepted client connection. */
    public void initChannel(Channel channel) {
        StreamDetector.install(channel, this, pendingStreams);
    }

    public void onPlayerJoin(PlatformPlayer player) {
        VoiceSession session = sessions.create(player, clock.millis());
        refreshPermissions(session);
        stores.touchPlayer(player.uuid(), player.name(), clock.millis());
        platform.scheduleLater(() -> checkModRequirement(session), config.modDetectTimeoutSeconds(), TimeUnit.SECONDS);
    }

    public void onServerConnected(PlatformPlayer player, String server) {
        VoiceSession session = sessions.get(player.uuid());
        if (session == null) {
            onPlayerJoin(player);
            session = sessions.get(player.uuid());
        }
        String previous = sessions.moveToServer(session, server);
        if (previous != null && !previous.equals(server)) {
            ServerRoutes old = routes.get(previous);
            if (old != null) {
                old.remove(session.id());
            }
        }
        routes.getOrCreate(server);
        refreshPermissions(session);
        backend.onPlayerConnected(session, server);
        router.pushVoiceState(session);
        router.pushMuteStatus(session);
        if (clock.millis() - session.createdAt() > config.modDetectTimeoutSeconds() * 1000L) {
            checkModRequirement(session);
        }
    }

    public void onPlayerQuit(PlatformPlayer player) {
        VoiceSession current = sessions.get(player.uuid());
        boolean hadVoice = current != null && current.isVoiceConnected();
        VoiceSession session = sessions.remove(player.uuid());
        if (session == null) {
            return;
        }
        if (hadVoice) {
            fire(listener -> listener.onVoiceDisconnected(session));
        }
        routes.removeEverywhere(session.id(), null);
        byte[] remove = ProxyToClient.REGISTRY.encode(new ProxyToClient.PlayerRemove(new int[] {session.id()}));
        for (VoiceSession other : sessions.all()) {
            if (other.forgetSpeaker(session.id())) {
                other.sendEncoded(remove);
            }
        }
    }

    /** {@code bvc:voice} message from a client. Never forward it to the backend. */
    public void onClientMessage(PlatformPlayer player, byte[] data) {
        VoiceSession session = sessions.get(player.uuid());
        if (session == null) {
            return;
        }
        Packet<ClientToProxy.Handler> packet;
        try {
            packet = ClientToProxy.REGISTRY.decode(data);
        } catch (ProtocolException e) {
            logger.debug("Bad bvc:voice message from " + player.name() + ": " + e.getMessage());
            return;
        }
        packet.handle(new ClientHandler(session, Transport.PLUGIN_MESSAGE));
    }

    /** {@code bvc:server} message from a sub-server. Never forward it to the client. */
    public void onBackendMessage(PlatformPlayer via, String server, byte[] data) {
        backend.onBackendMessage(server, via, data);
    }

    // ------------------------------------------------------------------ StreamGateway

    @Override
    public BvcConfig config() {
        return config;
    }

    @Override
    public BvcLogger logger() {
        return logger;
    }

    @Override
    public VoiceSession sessionForAuth(int sessionId) {
        VoiceSession session = sessions.get(sessionId);
        if (session == null || session.state() == VoiceSession.State.WAITING_FOR_MOD
                || session.state() == VoiceSession.State.INCOMPATIBLE) {
            return null;
        }
        return session;
    }

    @Override
    public void onStreamAuthenticated(VoiceSession session, StreamConnection connection) {
        if (sessions.get(session.id()) != session) {
            connection.close(); // player left meanwhile
            return;
        }
        session.attachStream(connection);
        session.send(new ProxyToClient.TransportAck(Transport.STREAM));
        onVoiceConnected(session);
    }

    @Override
    public void onStreamPacket(VoiceSession session, Packet<ClientToProxy.Handler> packet) {
        packet.handle(new ClientHandler(session, Transport.STREAM));
    }

    @Override
    public void onStreamClosed(VoiceSession session, StreamConnection connection) {
        if (session.detachStream(connection)) {
            logger.debug(session.name() + " lost the voice stream");
            backend.onVoiceChanged(session);
            fire(listener -> listener.onVoiceDisconnected(session));
        }
    }

    private void onVoiceConnected(VoiceSession session) {
        logger.debug(session.name() + " voice connected via " + session.transport());
        router.pushVoiceState(session);
        router.pushMuteStatus(session);
        backend.onVoiceChanged(session);
        fire(listener -> listener.onVoiceConnected(session));
    }

    // ------------------------------------------------------------------ client packets

    private final class ClientHandler implements ClientToProxy.Handler {

        private final VoiceSession session;
        private final Transport source;

        ClientHandler(VoiceSession session, Transport source) {
            this.session = session;
            this.source = source;
        }

        @Override
        public void handle(ClientToProxy.Hello packet) {
            if (source != Transport.PLUGIN_MESSAGE) {
                return;
            }
            session.onHello(packet.modVersion, packet.minecraftVersion, packet.loader, packet.transports);
            if (packet.protocolVersion != BvcProtocol.VERSION) {
                session.setState(VoiceSession.State.INCOMPATIBLE);
                session.sendViaPluginMessage(new ProxyToClient.Notice(messages.raw("mod-incompatible",
                        "server", BvcProtocol.VERSION, "client", packet.protocolVersion)));
                return;
            }
            session.resetVoice();
            session.setState(VoiceSession.State.HANDSHAKING);
            byte[] token = BvcCrypto.randomToken();
            session.issueToken(token, clock.millis() + TOKEN_LIFETIME_MILLIS);
            BvcConfig cfg = config;
            session.sendViaPluginMessage(new ProxyToClient.Welcome(BvcProtocol.VERSION, session.id(), token,
                    cfg.transportPolicy(), cfg.streamHost(), cfg.streamPort(), cfg.connectTimeoutMillis()));
        }

        @Override
        public void handle(ClientToProxy.Auth packet) {
            // Only meaningful as the first packet of a stream; StreamHandler deals with it.
        }

        @Override
        public void handle(ClientToProxy.SelectTransport packet) {
            if (source != Transport.PLUGIN_MESSAGE || packet.transport != Transport.PLUGIN_MESSAGE) {
                return;
            }
            if (!config.transportPolicy().allows(Transport.PLUGIN_MESSAGE)) {
                return;
            }
            VoiceSession.State state = session.state();
            if (state != VoiceSession.State.HANDSHAKING && state != VoiceSession.State.CONNECTED) {
                return;
            }
            session.usePluginMessages();
            session.send(new ProxyToClient.TransportAck(Transport.PLUGIN_MESSAGE));
            onVoiceConnected(session);
        }

        @Override
        public void handle(ClientToProxy.MicAudio packet) {
            if (source == session.transport()) {
                router.onMicAudio(session, packet);
            }
        }

        @Override
        public void handle(ClientToProxy.MicEnd packet) {
            if (source == session.transport()) {
                router.onMicEnd(session, packet);
            }
        }

        @Override
        public void handle(ClientToProxy.ClientStatus packet) {
            session.setClientStatus(packet.microphoneMuted, packet.deafened);
        }

        @Override
        public void handle(ClientToProxy.Ping packet) {
            byte[] pong = ProxyToClient.REGISTRY.encode(new ProxyToClient.Pong(packet.id));
            StreamConnection stream = session.stream();
            if (source == Transport.STREAM && stream != null) {
                stream.send(pong);
            } else {
                session.player().sendPluginMessage(BvcProtocol.CHANNEL_VOICE, pong);
            }
        }
    }

    // ------------------------------------------------------------------ mutes, permissions, mod check

    private void onMutesChanged(UUID target) {
        VoiceSession session = sessions.get(target);
        if (session != null) {
            router.pushMuteStatus(session);
        }
    }

    private void onMuteSwitchChanged() {
        for (VoiceSession session : sessions.all()) {
            router.pushMuteStatus(session);
        }
    }

    /** Tells muted/unmuted players in chat, whoever made the change (command, API, LiteBans...). */
    private final class MuteNotifier implements MuteListener {
        @Override
        public void onMute(Mute mute) {
            platform.player(mute.target()).ifPresent(player -> player.sendMessage(messages.get("notify-muted",
                    "scope", describeScope(mute.scope()),
                    "duration", mute.isPermanent() ? messages.raw("duration-permanent") : Durations.format(mute.expiresAt() - mute.createdAt()),
                    "reason", mute.reason().isEmpty() ? "-" : mute.reason())));
        }

        @Override
        public void onUnmute(Mute mute, String operator) {
            platform.player(mute.target()).ifPresent(player -> player.sendMessage(messages.get("notify-unmuted",
                    "scope", describeScope(mute.scope()))));
        }
    }

    private String describeScope(MuteScope scope) {
        return scope.isGlobal() ? messages.raw("scope-global") : messages.raw("scope-server", "server", scope.server());
    }

    private void refreshAllPermissions() {
        for (VoiceSession session : sessions.all()) {
            int before = session.proxyPriority();
            boolean broadcastBefore = session.proxyBroadcast();
            refreshPermissions(session);
            if (before != session.proxyPriority() || broadcastBefore != session.proxyBroadcast()) {
                router.pushVoiceState(session);
            }
        }
    }

    private void refreshPermissions(VoiceSession session) {
        PlatformPlayer player = session.player();
        int priority = 0;
        for (int level : config.priorityLevels()) {
            if (player.hasPermission("bvc.priority." + level)) {
                priority = level;
                break;
            }
        }
        session.setPermissions(priority, player.hasPermission("bvc.broadcast"),
                player.hasPermission("bvc.bypass.globalmute"), player.hasPermission("bvc.bypass.servermute"));
    }

    private void checkModRequirement(VoiceSession session) {
        BvcConfig cfg = config;
        if (!cfg.requireMod() || session.state() != VoiceSession.State.WAITING_FOR_MOD) {
            return;
        }
        PlatformPlayer player = session.player();
        if (!player.isOnline() || sessions.get(player.uuid()) != session) {
            return;
        }
        if (cfg.exemptBedrock() && isBedrock(player.uuid())) {
            return;
        }
        if (player.currentServer() == null || cfg.isRequireModExempt(player.currentServer())) {
            return;
        }
        if (player.hasPermission("bvc.bypass.requiremod")) {
            return;
        }
        player.disconnect(messages.raw("kick-no-mod", "url", cfg.downloadUrl()));
    }

    /** Floodgate gives Bedrock players UUIDs whose upper 64 bits are zero. */
    static boolean isBedrock(UUID uuid) {
        return uuid.getMostSignificantBits() == 0;
    }

    // ------------------------------------------------------------------ reports

    /** The "who heard whom" log must cover the whole report window. */
    private long heardRetention() {
        BvcConfig.Reports cfg = config.reports();
        return (cfg.beforeSeconds() + cfg.afterSeconds()) * 1000L + 60_000L;
    }

    /** Evidence is ready: tell staff online. */
    private void onReportReady(ReportStore.Report report) {
        String text = messages.get("report-staff", "id", report.id(), "reporter", report.reporterName(),
                "target", report.targetName(), "server", report.server(),
                "reason", report.reason().isEmpty() ? "-" : report.reason());
        for (PlatformPlayer player : platform.players()) {
            if (player.hasPermission(ReportCommand.NOTIFY_PERMISSION)) {
                player.sendMessage(text);
            }
        }
        platform.console().sendMessage(text);
        fire(listener -> listener.onReport(ReportApi.view(report)));
    }

    private void fire(java.util.function.Consumer<VoiceListener> event) {
        for (VoiceListener listener : voiceListeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                logger.error("A BetterVoiceChat listener failed", e);
            }
        }
    }

    @Override
    public ReportService reportService() {
        return new ReportApi(reports, clock);
    }

    @Override
    public void addListener(VoiceListener listener) {
        if (listener != null && !voiceListeners.contains(listener)) {
            voiceListeners.add(listener);
        }
    }

    @Override
    public void removeListener(VoiceListener listener) {
        voiceListeners.remove(listener);
    }

    // ------------------------------------------------------------------ API + accessors

    @Override
    public MuteService mutes() {
        return mutes;
    }

    @Override
    public Optional<VoicePlayer> player(UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }

    @Override
    public Collection<VoicePlayer> players() {
        return List.copyOf(sessions.all());
    }

    public ProxyPlatform platform() {
        return platform;
    }

    public Messages messages() {
        return messages;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public RouteRegistry routes() {
        return routes;
    }

    public VoiceRouter router() {
        return router;
    }

    public Stores stores() {
        return stores;
    }

    public BvcCommand command() {
        return command;
    }

    public ReportCommand reportCommand() {
        return reportCommand;
    }

    public ReportManager reports() {
        return reports;
    }

    public Recorder recorder() {
        return recorder;
    }

    public WebAuth webAuth() {
        return webAuth;
    }

    /** {@code null} when the panel is disabled or failed to start. */
    public WebServer web() {
        return web;
    }

    public Clock clock() {
        return clock;
    }
}
