package top.dext.bvc.proxy.routing;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.AudioCategory;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.packet.ClientToProxy;
import top.dext.bvc.protocol.packet.ProxyToClient;
import top.dext.bvc.proxy.config.BvcConfig;
import top.dext.bvc.proxy.mute.MuteManager;
import top.dext.bvc.proxy.session.SessionManager;
import top.dext.bvc.proxy.session.VoiceSession;

/**
 * Forwards audio by table lookup. The sub-server already decided who can hear whom; the
 * proxy only checks mutes, enforces "never across sub-servers" and fans the frame out.
 */
public final class VoiceRouter {

    /** How often a muted player who keeps sending is reminded of the mute. */
    private static final long MUTE_REMINDER_MILLIS = 5_000;

    @FunctionalInterface
    private interface ListenerVisitor {
        void visit(VoiceSession listener, AudioCategory category);
    }

    private final SessionManager sessions;
    private final RouteRegistry routes;
    private final MuteManager mutes;
    private final Supplier<BvcConfig> config;
    private final Clock clock;
    private volatile AudioSink sink = AudioSink.NONE;

    public VoiceRouter(SessionManager sessions, RouteRegistry routes, MuteManager mutes, Supplier<BvcConfig> config, Clock clock) {
        this.sessions = sessions;
        this.routes = routes;
        this.mutes = mutes;
        this.config = config;
        this.clock = clock;
    }

    public void setSink(AudioSink sink) {
        this.sink = sink == null ? AudioSink.NONE : sink;
    }

    public void onMicAudio(VoiceSession speaker, ClientToProxy.MicAudio packet) {
        String server = speaker.server();
        if (server == null || speaker.state() != VoiceSession.State.CONNECTED) {
            return;
        }
        long now = clock.millis();
        ProxyToClient.MuteStatus muted = mutes.check(speaker, server, now);
        if (muted != null) {
            // New or changed status goes out at once; an unchanged one is repeated now and then
            // in case the client missed it.
            if (!speaker.sendMuteStatus(muted) && speaker.shouldRemindMute(now, MUTE_REMINDER_MILLIS)) {
                speaker.sendMuteStatus(muted);
            }
            return;
        }

        int speakerId = speaker.id();
        int priority = priorityOf(speaker, server);
        int cap = config.get().maxStreamsPerListener();
        byte[][] encoded = new byte[AudioCategory.values().length][];
        AudioSink currentSink = sink;

        boolean allowed = forEachListener(speaker, server, packet.isBroadcast(), (listener, category) -> {
            if (!listener.admitStream(speakerId, priority, now, cap)) {
                return;
            }
            if (listener.learnSpeaker(speakerId)) {
                listener.send(new ProxyToClient.PlayerInfo(List.of(
                        new ProxyToClient.PlayerInfo.Entry(speakerId, speaker.uuid(), speaker.name()))));
            }
            byte[] bytes = encoded[category.id()];
            if (bytes == null) {
                bytes = ProxyToClient.REGISTRY.encode(
                        new ProxyToClient.VoiceAudio(speakerId, packet.sequence, category, priority, packet.opus));
                encoded[category.id()] = bytes;
            }
            listener.sendEncoded(bytes);
            currentSink.onHeard(listener, speaker, now);
        });
        if (allowed) {
            speaker.markAudio(now);
            currentSink.onAudio(server, speaker, packet.sequence, packet.opus, now);
        }
    }

    public void onMicEnd(VoiceSession speaker, ClientToProxy.MicEnd packet) {
        String server = speaker.server();
        if (server == null || speaker.state() != VoiceSession.State.CONNECTED) {
            return;
        }
        int speakerId = speaker.id();
        byte[] end = ProxyToClient.REGISTRY.encode(new ProxyToClient.VoiceEnd(speakerId, packet.sequence));
        forEachListener(speaker, server, true, (listener, category) -> {
            if (listener.isStreamActive(speakerId)) {
                listener.releaseStream(speakerId);
                listener.sendEncoded(end);
            }
        });
        sink.onAudioEnd(server, speaker, clock.millis());
    }

    /**
     * Visits everyone who should hear {@code speaker} right now.
     *
     * @return false if the speaker may not talk at all here
     */
    private boolean forEachListener(VoiceSession speaker, String server, boolean broadcastRequested, ListenerVisitor visitor) {
        ServerRoutes table = routes.get(server);
        if (table == null || !table.backendPresent()) {
            if (config.get().serversWithoutPlugin() != BvcConfig.FallbackMode.FREE) {
                return false;
            }
            for (VoiceSession listener : sessions.onServer(server)) {
                visitChecked(speaker, server, null, listener, AudioCategory.GROUP, visitor);
            }
            return true;
        }

        PlayerRoute route = table.route(speaker.id());
        if (route == null || !route.canSpeak() || route.mode() == VoiceMode.DISABLED) {
            return false;
        }
        if (broadcastRequested && (speaker.proxyBroadcast() || route.canBroadcast())) {
            for (int id : table.roomMembers(route.roomId())) {
                visitChecked(speaker, server, table, sessions.get(id), AudioCategory.BROADCAST, visitor);
            }
            return true;
        }
        int group = route.groupId();
        if (group != 0) {
            for (int id : table.groupMembers(group)) {
                visitChecked(speaker, server, table, sessions.get(id), AudioCategory.GROUP, visitor);
            }
        }
        for (int id : route.neighbors()) {
            if (group != 0) {
                PlayerRoute listenerRoute = table.route(id);
                if (listenerRoute != null && listenerRoute.groupId() == group) {
                    continue; // already got it as a group member
                }
            }
            visitChecked(speaker, server, table, sessions.get(id), AudioCategory.PROXIMITY, visitor);
        }
        return true;
    }

    private static void visitChecked(VoiceSession speaker, String server, ServerRoutes table, VoiceSession listener,
                                     AudioCategory category, ListenerVisitor visitor) {
        if (listener == null || listener == speaker || listener.state() != VoiceSession.State.CONNECTED || listener.isDeafened()) {
            return;
        }
        // Voice never crosses sub-servers, whatever a backend table says.
        if (!server.equals(listener.server())) {
            return;
        }
        if (table != null) {
            PlayerRoute listenerRoute = table.route(listener.id());
            if (listenerRoute == null || !listenerRoute.canHear()) {
                return;
            }
        }
        visitor.visit(listener, category);
    }

    private int priorityOf(VoiceSession speaker, String server) {
        ServerRoutes table = routes.get(server);
        PlayerRoute route = table == null ? null : table.route(speaker.id());
        return Math.max(speaker.proxyPriority(), route == null ? 0 : route.priority());
    }

    // ------------------------------------------------------------------ client-visible state

    /** What the client should display and how it should encode, for where the player is now. */
    public ProxyToClient.VoiceState voiceStateFor(VoiceSession session) {
        BvcConfig cfg = config.get();
        String server = session.server();
        if (server == null) {
            return new ProxyToClient.VoiceState("", "", VoiceMode.DISABLED, Activation.ANY, 0f, cfg.bitrate(),
                    cfg.duckingDecibels(), session.proxyPriority(), false, false);
        }
        ServerRoutes table = routes.get(server);
        if (table == null || !table.backendPresent()) {
            boolean free = cfg.serversWithoutPlugin() == BvcConfig.FallbackMode.FREE;
            return new ProxyToClient.VoiceState(server, "", free ? VoiceMode.FREE : VoiceMode.DISABLED, Activation.ANY, 0f,
                    cfg.bitrate(), cfg.duckingDecibels(), session.proxyPriority(), free, false);
        }
        PlayerRoute route = table.route(session.id());
        if (route == null) {
            route = PlayerRoute.INITIAL;
        }
        boolean canSpeak = route.canSpeak() && route.mode() != VoiceMode.DISABLED;
        return new ProxyToClient.VoiceState(server, route.room(), route.mode(), route.activation(), route.radius(),
                cfg.bitrate(), cfg.duckingDecibels(), Math.max(session.proxyPriority(), route.priority()), canSpeak,
                canSpeak && (session.proxyBroadcast() || route.canBroadcast()));
    }

    public void pushVoiceState(VoiceSession session) {
        if (session.state() == VoiceSession.State.CONNECTED) {
            session.sendVoiceState(voiceStateFor(session));
        }
    }

    /** Re-evaluates and sends the mute status (only if it changed). */
    public void pushMuteStatus(VoiceSession session) {
        if (session.state() != VoiceSession.State.CONNECTED) {
            return;
        }
        String server = session.server();
        ProxyToClient.MuteStatus status = server == null ? null : mutes.check(session, server, clock.millis());
        session.sendMuteStatus(status == null ? ProxyToClient.MuteStatus.unmuted() : status);
    }
}
