package top.dext.bvc.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import top.dext.bvc.client.audio.AudioDevices;
import top.dext.bvc.client.audio.OpusCodec;
import top.dext.bvc.client.audio.Positions;
import top.dext.bvc.client.audio.MicrophoneTest;
import top.dext.bvc.client.audio.RemoteSpeaker;
import top.dext.bvc.client.audio.VoiceActivity;
import top.dext.bvc.client.audio.VoiceMixer;
import top.dext.bvc.client.net.StreamTransport;
import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.ProtocolException;
import top.dext.bvc.protocol.crypto.BvcCrypto;
import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.Transport;
import top.dext.bvc.protocol.data.TransportPolicy;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.packet.ClientToProxy;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.protocol.packet.ProxyToClient;

/**
 * The whole client-side voice system, independent of Minecraft. One instance lives for the
 * whole game; adapters forward server joins, plugin messages, key presses and positions.
 * <p>
 * Nothing here decides who hears whom: mode, team, room and radius all come from the server.
 */
public final class VoiceClient {

    /** No Welcome within this time after joining = the server does not run BVC. */
    static final long WELCOME_TIMEOUT_MILLIS = 10_000;
    private static final long PING_INTERVAL_MILLIS = 5_000;
    private static final long DEVICE_RETRY_MILLIS = 5_000;

    private final ClientPlatform platform;
    private final ClientSettings settings;
    private final AudioDevices devices;
    private final ClientLogger logger;
    private final Positions positions = new Positions();
    private final Map<Integer, RemoteSpeaker> speakers = new ConcurrentHashMap<Integer, RemoteSpeaker>();
    private final ScheduledExecutorService scheduler;

    // Connection state; guarded by this.
    private InetSocketAddress serverAddress;
    private String handshakeHost = "";
    private int generation;
    private ProxyToClient.Welcome welcome;
    private StreamTransport stream;
    private ScheduledFuture<?> welcomeTimeout;
    private int reconnectDelaySeconds = 1;

    private volatile ClientStatus.Phase phase = ClientStatus.Phase.IDLE;
    private volatile Transport transport;
    private volatile ProxyToClient.VoiceState voice;
    private volatile ProxyToClient.MuteStatus mute = ProxyToClient.MuteStatus.unmuted();
    private volatile String notice;
    private volatile int pingMillis = -1;

    // Input state.
    private volatile boolean talkKeyHeld;
    private volatile boolean toggledOn;
    private volatile boolean broadcastHeld;
    private volatile boolean microphoneMuted;
    private volatile boolean deafened;
    private volatile boolean talking;
    private volatile float microphoneLevel = -90f;
    private volatile boolean inputAvailable = true;
    private volatile boolean outputAvailable = true;

    private Thread captureThread;
    private Thread playbackThread;
    private volatile boolean running;

    private MicrophoneTest microphoneTest;

    /**
     * The settings screen's microphone test. It is fed by the normal capture below, never opens
     * the microphone itself, so voice keeps working while it runs.
     */
    public synchronized MicrophoneTest microphoneTest() {
        if (microphoneTest == null) {
            microphoneTest = new MicrophoneTest(devices);
        }
        return microphoneTest;
    }

    /** Starts the test; capture also runs without a server while it is on (title screen, singleplayer). */
    public void startMicrophoneTest(boolean loopback) {
        microphoneTest().start(settings.outputDevice(), loopback);
        startAudio();
    }

    public void stopMicrophoneTest() {
        if (microphoneTest != null) {
            microphoneTest.stop();
        }
    }

    private boolean testing() {
        MicrophoneTest test = microphoneTest;
        return test != null && test.isRunning();
    }

    public VoiceClient(ClientPlatform platform, ClientSettings settings, AudioDevices devices) {
        this.platform = platform;
        this.settings = settings;
        this.devices = devices;
        this.logger = platform.logger();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemon("BVC-Client"));
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                ping();
            }
        }, PING_INTERVAL_MILLIS, PING_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ adapter entry points

    /**
     * The player finished joining a server (play state).
     *
     * @param remote        address of the server connection (after SRV resolution)
     * @param handshakeHost host name the game put into its own handshake
     */
    public synchronized void onJoinServer(InetSocketAddress remote, String handshakeHost) {
        resetConnection();
        this.serverAddress = remote;
        this.handshakeHost = handshakeHost == null ? "" : handshakeHost;
        reconnectDelaySeconds = 1;
        sendHello();
    }

    /** The player left the server. */
    public synchronized void onDisconnect() {
        resetConnection();
        serverAddress = null;
        phase = ClientStatus.Phase.IDLE;
    }

    /** A {@code bvc:voice} plugin message arrived. Any thread. */
    public void onPluginMessage(byte[] data) {
        Packet<ProxyToClient.Handler> packet;
        try {
            packet = ProxyToClient.REGISTRY.decode(data);
        } catch (ProtocolException e) {
            logger.debug("Bad bvc:voice message: " + e.getMessage());
            return;
        }
        packet.handle(new Handler(Transport.PLUGIN_MESSAGE, null));
    }

    /** Push-to-talk key (also toggles in {@link ActivationMode#TOGGLE}). */
    public void setTalkKey(boolean down) {
        if (down && !talkKeyHeld && settings.activation() == ActivationMode.TOGGLE && !serverForcesPushToTalk()) {
            toggledOn = !toggledOn;
        }
        talkKeyHeld = down;
    }

    /** Broadcast key; only does something when the server allows this player to broadcast. */
    public void setBroadcastKey(boolean down) {
        broadcastHeld = down;
    }

    public void toggleMicrophoneMuted() {
        microphoneMuted = !microphoneMuted;
        sendClientStatus();
    }

    public void toggleDeafened() {
        deafened = !deafened;
        sendClientStatus();
    }

    /** Positions for 3D audio; call once per client tick. */
    public Positions positions() {
        return positions;
    }

    public ClientSettings settings() {
        return settings;
    }

    public AudioDevices devices() {
        return devices;
    }

    /** Re-open audio devices after the player picked others in the settings. */
    public void reopenDevices() {
        Thread capture = captureThread;
        Thread playback = playbackThread;
        if (capture != null) {
            capture.interrupt();
        }
        if (playback != null) {
            playback.interrupt();
        }
    }

    public void shutdown() {
        stopMicrophoneTest();
        synchronized (this) {
            resetConnection();
        }
        stopAudio();
        scheduler.shutdownNow();
    }

    /** Everyone heard since joining who has a name and UUID, for per-player volume. */
    public List<RemoteSpeaker> knownSpeakers() {
        List<RemoteSpeaker> list = new ArrayList<RemoteSpeaker>();
        for (RemoteSpeaker speaker : speakers.values()) {
            if (speaker.uuid() != null) {
                list.add(speaker);
            }
        }
        Collections.sort(list, new Comparator<RemoteSpeaker>() {
            @Override
            public int compare(RemoteSpeaker a, RemoteSpeaker b) {
                return String.valueOf(a.name()).compareToIgnoreCase(String.valueOf(b.name()));
            }
        });
        return list;
    }

    public ClientStatus status() {
        long now = System.currentTimeMillis();
        List<ClientStatus.Speaking> list = new ArrayList<ClientStatus.Speaking>();
        for (RemoteSpeaker speaker : speakers.values()) {
            if (speaker.isSpeaking(now)) {
                list.add(new ClientStatus.Speaking(speaker.uuid(), speaker.name() == null ? "#" + speaker.id() : speaker.name(),
                        speaker.category(), speaker.priority()));
            }
        }
        Collections.sort(list, new Comparator<ClientStatus.Speaking>() {
            @Override
            public int compare(ClientStatus.Speaking a, ClientStatus.Speaking b) {
                return b.priority - a.priority;
            }
        });
        return new ClientStatus(phase, transport, voice, mute, effectiveActivation(), talking, microphoneMuted, deafened,
                inputAvailable, outputAvailable, microphoneLevel, pingMillis, list, notice);
    }

    // ------------------------------------------------------------------ connection

    private void sendHello() {
        if (serverAddress == null) {
            return;
        }
        final int current = ++generation;
        phase = phase == ClientStatus.Phase.RECONNECTING ? phase : ClientStatus.Phase.CONNECTING;
        int transports = Transport.PLUGIN_MESSAGE.bit() | (BvcCrypto.isKeyExchangeSupported() ? Transport.STREAM.bit() : 0);
        platform.sendPluginMessage(ClientToProxy.REGISTRY.encode(new ClientToProxy.Hello(BvcProtocol.VERSION,
                platform.modVersion(), platform.minecraftVersion(), platform.loader(), transports)));
        welcomeTimeout = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (VoiceClient.this) {
                    if (generation == current && welcome == null) {
                        phase = ClientStatus.Phase.UNAVAILABLE;
                    }
                }
            }
        }, WELCOME_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private synchronized void onWelcome(ProxyToClient.Welcome packet) {
        if (serverAddress == null) {
            return;
        }
        if (welcomeTimeout != null) {
            welcomeTimeout.cancel(false);
        }
        welcome = packet;
        notice = null;
        final int current = generation;
        final TransportPolicy policy = packet.transportPolicy;
        if (policy.allows(Transport.STREAM) && BvcCrypto.isKeyExchangeSupported()) {
            final InetSocketAddress target = packet.streamHost.isEmpty()
                    ? new InetSocketAddress(serverAddress.getAddress(), packet.streamPort > 0 ? packet.streamPort : serverAddress.getPort())
                    : new InetSocketAddress(packet.streamHost, packet.streamPort > 0 ? packet.streamPort : serverAddress.getPort());
            final String host = packet.streamHost.isEmpty() ? handshakeHost : packet.streamHost;
            Thread connector = new Thread(new Runnable() {
                @Override
                public void run() {
                    connectStream(current, target, host, packet, policy);
                }
            }, "BVC-Connect");
            connector.setDaemon(true);
            connector.start();
        } else if (policy.allows(Transport.PLUGIN_MESSAGE)) {
            selectPluginMessages();
        }
    }

    private void connectStream(int current, InetSocketAddress target, String host, ProxyToClient.Welcome packet,
                               TransportPolicy policy) {
        final StreamTransport candidate = new StreamTransport();
        try {
            candidate.connect(target.getHostString(), target.getPort(), host, platform.minecraftProtocol(), packet.sessionId,
                    packet.token, packet.connectTimeoutMillis, new StreamTransport.Listener() {
                        @Override
                        public void onPacket(StreamTransport transport, Packet<ProxyToClient.Handler> received) {
                            received.handle(new Handler(Transport.STREAM, transport));
                        }

                        @Override
                        public void onClosed(StreamTransport transport, Throwable cause) {
                            onStreamClosed(transport, cause);
                        }
                    });
        } catch (Exception e) {
            logger.info("Voice stream connection failed (" + e.getMessage() + ")"
                    + (policy.allows(Transport.PLUGIN_MESSAGE) ? ", using plugin messages instead" : ""));
            synchronized (this) {
                if (generation != current) {
                    return;
                }
                if (policy.allows(Transport.PLUGIN_MESSAGE)) {
                    selectPluginMessages();
                } else {
                    scheduleReconnect();
                }
            }
            return;
        }
        synchronized (this) {
            if (generation != current) {
                candidate.close();
                return;
            }
            stream = candidate;
        }
    }

    private void selectPluginMessages() {
        platform.sendPluginMessage(ClientToProxy.REGISTRY.encode(new ClientToProxy.SelectTransport(Transport.PLUGIN_MESSAGE)));
    }

    private synchronized void onTransportAck(Transport acked, StreamTransport via) {
        if (serverAddress == null || welcome == null || (acked == Transport.STREAM && (via == null || via != stream))) {
            return; // stale
        }
        transport = acked;
        phase = ClientStatus.Phase.CONNECTED;
        reconnectDelaySeconds = 1;
        startAudio();
        sendClientStatus();
        logger.info("Voice connected (" + (acked == Transport.STREAM ? "stream" : "plugin messages") + ")");
    }

    private void onStreamClosed(StreamTransport closed, Throwable cause) {
        synchronized (this) {
            if (closed != stream) {
                return;
            }
            stream = null;
            transport = null;
            logger.info("Voice stream lost" + (cause == null ? "" : " (" + cause.getMessage() + ")") + ", reconnecting");
            scheduleReconnect();
        }
    }

    /** Called with the lock held. */
    private void scheduleReconnect() {
        if (serverAddress == null) {
            return;
        }
        welcome = null;
        phase = ClientStatus.Phase.RECONNECTING;
        speakers.clear();
        final int current = ++generation;
        int delay = reconnectDelaySeconds;
        reconnectDelaySeconds = Math.min(30, reconnectDelaySeconds * 2);
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (VoiceClient.this) {
                    if (generation == current) {
                        sendHello();
                    }
                }
            }
        }, delay, TimeUnit.SECONDS);
    }

    /** Called with the lock held. */
    private void resetConnection() {
        generation++;
        if (welcomeTimeout != null) {
            welcomeTimeout.cancel(false);
            welcomeTimeout = null;
        }
        if (stream != null) {
            stream.close();
            stream = null;
        }
        welcome = null;
        transport = null;
        voice = null;
        mute = ProxyToClient.MuteStatus.unmuted();
        notice = null;
        pingMillis = -1;
        speakers.clear();
        positions.clear();
        toggledOn = false;
        if (phase != ClientStatus.Phase.IDLE) {
            phase = ClientStatus.Phase.IDLE;
        }
    }

    /** Sends over the active transport. */
    private void send(Packet<ClientToProxy.Handler> packet) {
        byte[] data = ClientToProxy.REGISTRY.encode(packet);
        StreamTransport current = stream;
        if (transport == Transport.STREAM && current != null) {
            current.send(data);
        } else if (transport == Transport.PLUGIN_MESSAGE) {
            platform.sendPluginMessage(data);
        }
    }

    private void sendClientStatus() {
        if (phase == ClientStatus.Phase.CONNECTED) {
            send(new ClientToProxy.ClientStatus(microphoneMuted, deafened));
        }
    }

    private void ping() {
        if (phase == ClientStatus.Phase.CONNECTED) {
            send(new ClientToProxy.Ping(System.nanoTime()));
        }
    }

    private final class Handler implements ProxyToClient.Handler {
        private final Transport source;
        private final StreamTransport via;

        Handler(Transport source, StreamTransport via) {
            this.source = source;
            this.via = via;
        }

        @Override
        public void handle(ProxyToClient.Welcome packet) {
            if (source == Transport.PLUGIN_MESSAGE) {
                if (packet.protocolVersion != BvcProtocol.VERSION) {
                    phase = ClientStatus.Phase.INCOMPATIBLE;
                    return;
                }
                onWelcome(packet);
            }
        }

        @Override
        public void handle(ProxyToClient.AuthResult packet) {
            // Consumed by StreamTransport during connect.
        }

        @Override
        public void handle(ProxyToClient.TransportAck packet) {
            onTransportAck(packet.transport, via);
        }

        @Override
        public void handle(ProxyToClient.VoiceState packet) {
            voice = packet;
        }

        @Override
        public void handle(ProxyToClient.VoiceAudio packet) {
            RemoteSpeaker speaker = speakers.get(packet.speakerId);
            if (speaker == null) {
                speaker = new RemoteSpeaker(packet.speakerId);
                RemoteSpeaker raced = speakers.putIfAbsent(packet.speakerId, speaker);
                if (raced != null) {
                    speaker = raced;
                }
            }
            speaker.offer(packet.opus, packet.category, packet.priority, System.currentTimeMillis());
        }

        @Override
        public void handle(ProxyToClient.VoiceEnd packet) {
            RemoteSpeaker speaker = speakers.get(packet.speakerId);
            if (speaker != null) {
                speaker.end();
            }
        }

        @Override
        public void handle(ProxyToClient.PlayerInfo packet) {
            for (ProxyToClient.PlayerInfo.Entry entry : packet.entries) {
                RemoteSpeaker speaker = speakers.get(entry.speakerId);
                if (speaker == null) {
                    speaker = new RemoteSpeaker(entry.speakerId);
                    RemoteSpeaker raced = speakers.putIfAbsent(entry.speakerId, speaker);
                    if (raced != null) {
                        speaker = raced;
                    }
                }
                speaker.identify(entry.uuid, entry.name);
            }
        }

        @Override
        public void handle(ProxyToClient.PlayerRemove packet) {
            for (int id : packet.speakerIds) {
                speakers.remove(id);
            }
        }

        @Override
        public void handle(ProxyToClient.MuteStatus packet) {
            mute = packet;
        }

        @Override
        public void handle(ProxyToClient.Pong packet) {
            pingMillis = (int) Math.max(0, (System.nanoTime() - packet.id) / 1_000_000L);
        }

        @Override
        public void handle(ProxyToClient.Notice packet) {
            notice = packet.message;
            platform.showMessage(packet.message);
        }
    }

    // ------------------------------------------------------------------ talking rules

    private boolean serverForcesPushToTalk() {
        ProxyToClient.VoiceState state = voice;
        return state != null && state.activation == Activation.PTT_ONLY;
    }

    /** The player's choice, narrowed by the server: PTT-only zones turn voice/toggle into push-to-talk. */
    ActivationMode effectiveActivation() {
        return serverForcesPushToTalk() ? ActivationMode.PUSH_TO_TALK : settings.activation();
    }

    /**
     * Whether to transmit this frame.
     *
     * @return 0 = silent, 1 = talk, 2 = talk as broadcast
     */
    static int decide(ProxyToClient.VoiceState state, ProxyToClient.MuteStatus mute, boolean microphoneMuted,
                      ActivationMode activation, boolean talkKeyHeld, boolean toggledOn, boolean voiceDetected,
                      boolean broadcastHeld) {
        if (state == null || !state.canSpeak || state.mode == VoiceMode.DISABLED || mute.muted || microphoneMuted) {
            return 0;
        }
        if (broadcastHeld && state.canBroadcast) {
            return 2;
        }
        switch (activation) {
            case VOICE:
                return voiceDetected ? 1 : 0;
            case TOGGLE:
                return toggledOn ? 1 : 0;
            default:
                return talkKeyHeld ? 1 : 0;
        }
    }

    // ------------------------------------------------------------------ audio threads

    private synchronized void startAudio() {
        if (running) {
            return;
        }
        running = true;
        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                captureLoop();
            }
        }, "BVC-Capture");
        captureThread.setDaemon(true);
        captureThread.start();
        playbackThread = new Thread(new Runnable() {
            @Override
            public void run() {
                playbackLoop();
            }
        }, "BVC-Playback");
        playbackThread.setDaemon(true);
        playbackThread.setPriority(Thread.MAX_PRIORITY - 1);
        playbackThread.start();
    }

    private void stopAudio() {
        running = false;
        reopenDevices();
    }

    private void captureLoop() {
        short[] frame = new short[BvcProtocol.FRAME_SAMPLES];
        VoiceActivity activity = new VoiceActivity();
        OpusCodec.Encoder encoder = null;
        int sequence = 0;
        boolean wasTalking = false;
        while (running) {
            // The microphone is open only while voice is connected or the player is testing it.
            if (phase != ClientStatus.Phase.CONNECTED && !testing()) {
                if (wasTalking) {
                    wasTalking = false;
                    talking = false;
                }
                sleep(100);
                continue;
            }
            AudioDevices.AudioInput input;
            try {
                input = devices.openInput(settings.inputDevice());
                inputAvailable = true;
            } catch (IOException e) {
                if (inputAvailable) {
                    logger.warn("Cannot open the microphone: " + e.getMessage());
                }
                inputAvailable = false;
                MicrophoneTest test = microphoneTest;
                if (test != null) {
                    test.inputFailed(e.getMessage());
                }
                sleep(testing() ? 1000 : DEVICE_RETRY_MILLIS);
                continue;
            }
            try {
                while (running && (phase == ClientStatus.Phase.CONNECTED || testing()) && !Thread.currentThread().isInterrupted()) {
                    input.read(frame);
                    applyGain(frame, settings.inputGain());
                    float level = VoiceActivity.levelDb(frame);
                    microphoneLevel = level;
                    MicrophoneTest test = microphoneTest;
                    if (test != null) {
                        test.feed(frame, level);
                    }
                    if (phase != ClientStatus.Phase.CONNECTED) {
                        continue; // testing without a server: nothing to send
                    }
                    boolean detected = activity.update(level, settings.voiceThresholdDb());
                    ProxyToClient.VoiceState state = voice;
                    int decision = decide(state, mute, microphoneMuted, effectiveActivation(), talkKeyHeld, toggledOn,
                            detected, broadcastHeld);
                    if (decision > 0) {
                        if (encoder == null) {
                            encoder = new OpusCodec.Encoder(state.bitrate);
                        }
                        encoder.setBitrate(state.bitrate);
                        send(new ClientToProxy.MicAudio(sequence++, decision == 2 ? ClientToProxy.MicAudio.FLAG_BROADCAST : 0,
                                encoder.encode(frame)));
                        wasTalking = true;
                    } else if (wasTalking) {
                        send(new ClientToProxy.MicEnd(sequence));
                        wasTalking = false;
                    }
                    talking = wasTalking;
                }
            } catch (IOException e) {
                logger.warn("Microphone stopped: " + e.getMessage());
                sleep(1000);
            } finally {
                input.close();
                Thread.interrupted(); // clear a device-reopen request
            }
            if (wasTalking) {
                send(new ClientToProxy.MicEnd(sequence));
                wasTalking = false;
                talking = false;
            }
        }
    }

    private void playbackLoop() {
        short[] out = new short[BvcProtocol.FRAME_SAMPLES * 2];
        VoiceMixer mixer = new VoiceMixer();
        VoiceMixer.VolumeSource volumes = new VoiceMixer.VolumeSource() {
            @Override
            public float volume(UUID player) {
                return settings.playerVolume(player);
            }
        };
        while (running) {
            if (phase != ClientStatus.Phase.CONNECTED) {
                sleep(100);
                continue;
            }
            AudioDevices.AudioOutput output;
            try {
                output = devices.openOutput(settings.outputDevice());
                outputAvailable = true;
            } catch (IOException e) {
                if (outputAvailable) {
                    logger.warn("Cannot open the speakers: " + e.getMessage());
                }
                outputAvailable = false;
                // Keep draining so buffers do not pile up while there is no device.
                long until = System.currentTimeMillis() + DEVICE_RETRY_MILLIS;
                while (running && System.currentTimeMillis() < until) {
                    mixOnce(mixer, volumes, out);
                    sleep(BvcProtocol.FRAME_MILLIS);
                }
                continue;
            }
            try {
                while (running && phase == ClientStatus.Phase.CONNECTED && !Thread.currentThread().isInterrupted()) {
                    mixOnce(mixer, volumes, out);
                    output.write(out);
                }
            } catch (IOException e) {
                logger.warn("Speakers stopped: " + e.getMessage());
                sleep(1000);
            } finally {
                output.close();
                Thread.interrupted();
            }
        }
    }

    private void mixOnce(VoiceMixer mixer, VoiceMixer.VolumeSource volumes, short[] out) {
        ProxyToClient.VoiceState state = voice;
        float radius = state == null ? 0f : state.radius;
        float ducking = state == null ? 0f : state.duckingDecibels;
        mixer.mix(speakers.values(), positions, radius, ducking, deafened ? 0f : settings.outputVolume(), volumes, out);
    }

    private static void applyGain(short[] frame, float gain) {
        if (gain == 1f) {
            return;
        }
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(frame[i] * gain)));
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // interrupt = reopen devices; just continue
        }
    }

    private static ThreadFactory daemon(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
