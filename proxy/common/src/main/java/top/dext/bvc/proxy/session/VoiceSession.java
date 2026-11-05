package top.dext.bvc.proxy.session;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import top.dext.bvc.api.proxy.VoicePlayer;
import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.data.Transport;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.protocol.packet.ProxyToClient;
import top.dext.bvc.proxy.platform.PlatformPlayer;

/**
 * Voice state of one online player. Fields are written from platform and Netty threads, so
 * everything mutable is volatile or internally synchronized.
 */
public final class VoiceSession implements VoicePlayer {

    public enum State {
        /** No {@code Hello} from the client mod yet. */
        WAITING_FOR_MOD,
        /** Mod announced itself and got a token; choosing a transport. */
        HANDSHAKING,
        /** Audio can flow. */
        CONNECTED,
        /** Mod speaks another protocol version. */
        INCOMPATIBLE
    }

    private static final long SPEAKING_WINDOW_MILLIS = 400;

    private final int id;
    private final PlatformPlayer player;
    private final long createdAt;
    private final Set<Integer> knownSpeakers = ConcurrentHashMap.newKeySet();
    private final ListenerStreams streams = new ListenerStreams();

    private volatile State state = State.WAITING_FOR_MOD;
    private volatile Transport transport;
    private volatile StreamConnection stream;
    private volatile byte[] token;
    private volatile long tokenExpiresAt;
    private volatile String server;
    private volatile String modVersion = "";
    private volatile String minecraftVersion = "";
    private volatile String loader = "";
    private volatile int supportedTransports;
    private volatile boolean deafened;
    private volatile boolean microphoneMuted;
    private volatile long lastAudioAt;

    // Permission results, refreshed on join, server switch and periodically: hasPermission is
    // too slow to call for every audio frame.
    private volatile int proxyPriority;
    private volatile boolean proxyBroadcast;
    private volatile boolean bypassGlobalMute;
    private volatile boolean bypassServerMute;

    private volatile byte[] lastVoiceState;
    private volatile byte[] lastMuteStatus;
    private volatile long lastMuteReminderAt;

    VoiceSession(int id, PlatformPlayer player, long createdAt) {
        this.id = id;
        this.player = player;
        this.createdAt = createdAt;
    }

    public int id() {
        return id;
    }

    public long createdAt() {
        return createdAt;
    }

    public PlatformPlayer player() {
        return player;
    }

    @Override
    public UUID uuid() {
        return player.uuid();
    }

    @Override
    public String name() {
        return player.name();
    }

    @Override
    public boolean isVoiceConnected() {
        return state == State.CONNECTED;
    }

    @Override
    public String server() {
        return server;
    }

    @Override
    public boolean isSpeaking() {
        return System.currentTimeMillis() - lastAudioAt < SPEAKING_WINDOW_MILLIS;
    }

    @Override
    public String modVersion() {
        return modVersion;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Transport transport() {
        return transport;
    }

    public StreamConnection stream() {
        return stream;
    }

    // ------------------------------------------------------------------ handshake

    public void onHello(String modVersion, String minecraftVersion, String loader, int transports) {
        this.modVersion = modVersion;
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
        this.supportedTransports = transports;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public String loader() {
        return loader;
    }

    public int supportedTransports() {
        return supportedTransports;
    }

    public void issueToken(byte[] token, long expiresAt) {
        this.token = token;
        this.tokenExpiresAt = expiresAt;
    }

    /** Returns the token if still valid and consumes it (single use). */
    public synchronized byte[] consumeToken(long now) {
        byte[] current = token;
        token = null;
        return current != null && now <= tokenExpiresAt ? current : null;
    }

    /** Switches audio to the stream connection; closes a previous one. */
    public void attachStream(StreamConnection connection) {
        StreamConnection previous;
        synchronized (this) {
            previous = stream;
            stream = connection;
            transport = Transport.STREAM;
            state = State.CONNECTED;
        }
        if (previous != null && previous != connection) {
            previous.close();
        }
    }

    /** @return true if {@code connection} was the active one */
    public synchronized boolean detachStream(StreamConnection connection) {
        if (stream != connection) {
            return false;
        }
        stream = null;
        if (transport == Transport.STREAM) {
            transport = null;
            state = State.HANDSHAKING;
        }
        return true;
    }

    public void usePluginMessages() {
        StreamConnection previous;
        synchronized (this) {
            previous = stream;
            stream = null;
            transport = Transport.PLUGIN_MESSAGE;
            state = State.CONNECTED;
        }
        if (previous != null) {
            previous.close();
        }
    }

    /** Back to "no voice" (for example when the stream drops); the client re-sends Hello to recover. */
    public void resetVoice() {
        StreamConnection previous;
        synchronized (this) {
            previous = stream;
            stream = null;
            transport = null;
            if (state == State.CONNECTED || state == State.HANDSHAKING) {
                state = State.HANDSHAKING;
            }
        }
        if (previous != null) {
            previous.close();
        }
        knownSpeakers.clear();
        lastVoiceState = null;
        lastMuteStatus = null;
    }

    // ------------------------------------------------------------------ sending

    public void send(Packet<ProxyToClient.Handler> packet) {
        sendEncoded(ProxyToClient.REGISTRY.encode(packet));
    }

    /** Sends an already-encoded proxy->client packet over the active transport. */
    public void sendEncoded(byte[] packet) {
        if (state == State.WAITING_FOR_MOD) {
            return; // no mod, nobody would understand it
        }
        StreamConnection connection = stream;
        if (transport == Transport.STREAM && connection != null) {
            connection.send(packet);
        } else {
            player.sendPluginMessage(BvcProtocol.CHANNEL_VOICE, packet);
        }
    }

    /** Sends over the plugin-message channel regardless of transport (handshake packets). */
    public void sendViaPluginMessage(Packet<ProxyToClient.Handler> packet) {
        player.sendPluginMessage(BvcProtocol.CHANNEL_VOICE, ProxyToClient.REGISTRY.encode(packet));
    }

    /** Sends the state only if it differs from what the client already has. */
    public void sendVoiceState(ProxyToClient.VoiceState voiceState) {
        byte[] encoded = ProxyToClient.REGISTRY.encode(voiceState);
        if (!Arrays.equals(encoded, lastVoiceState)) {
            lastVoiceState = encoded;
            sendEncoded(encoded);
        }
    }

    /** Sends the mute status only if it changed. */
    public boolean sendMuteStatus(ProxyToClient.MuteStatus status) {
        byte[] encoded = ProxyToClient.REGISTRY.encode(status);
        if (!Arrays.equals(encoded, lastMuteStatus)) {
            lastMuteStatus = encoded;
            sendEncoded(encoded);
            return true;
        }
        return false;
    }

    /** Forces the next {@link #sendMuteStatus} to go out (client may have missed it). */
    public boolean shouldRemindMute(long now, long intervalMillis) {
        if (now - lastMuteReminderAt < intervalMillis) {
            return false;
        }
        lastMuteReminderAt = now;
        lastMuteStatus = null;
        return true;
    }

    // ------------------------------------------------------------------ routing helpers

    /** @return true the first time this listener hears about {@code speakerId} */
    public boolean learnSpeaker(int speakerId) {
        return knownSpeakers.add(speakerId);
    }

    public boolean forgetSpeaker(int speakerId) {
        return knownSpeakers.remove(speakerId);
    }

    public boolean admitStream(int speakerId, int priority, long now, int cap) {
        return streams.admit(speakerId, priority, now, cap);
    }

    public void releaseStream(int speakerId) {
        streams.release(speakerId);
    }

    public boolean isStreamActive(int speakerId) {
        return streams.isActive(speakerId);
    }

    public void markAudio(long now) {
        lastAudioAt = now;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public boolean isDeafened() {
        return deafened;
    }

    public boolean isMicrophoneMuted() {
        return microphoneMuted;
    }

    public void setClientStatus(boolean microphoneMuted, boolean deafened) {
        this.microphoneMuted = microphoneMuted;
        this.deafened = deafened;
    }

    public int proxyPriority() {
        return proxyPriority;
    }

    public boolean proxyBroadcast() {
        return proxyBroadcast;
    }

    public boolean bypassGlobalMute() {
        return bypassGlobalMute;
    }

    public boolean bypassServerMute() {
        return bypassServerMute;
    }

    public void setPermissions(int priority, boolean broadcast, boolean bypassGlobalMute, boolean bypassServerMute) {
        this.proxyPriority = priority;
        this.proxyBroadcast = broadcast;
        this.bypassGlobalMute = bypassGlobalMute;
        this.bypassServerMute = bypassServerMute;
    }

    @Override
    public String toString() {
        return "VoiceSession{" + id + " " + name() + " " + state + " " + transport + " @" + server + "}";
    }
}
