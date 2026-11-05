package top.dext.bvc.client;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import top.dext.bvc.protocol.data.AudioCategory;
import top.dext.bvc.protocol.data.Transport;
import top.dext.bvc.protocol.packet.ProxyToClient;

/**
 * Immutable snapshot of the voice state for the HUD and settings screens.
 */
public final class ClientStatus {

    public enum Phase {
        /** Not on a server. */
        IDLE,
        /** Announced the mod, waiting for the server. */
        CONNECTING,
        CONNECTED,
        /** The server does not run BVC (no answer). */
        UNAVAILABLE,
        /** The server runs another BVC protocol version. */
        INCOMPATIBLE,
        /** Lost the connection; retrying. */
        RECONNECTING
    }

    public static final class Speaking {
        public final UUID uuid;
        public final String name;
        public final AudioCategory category;
        public final int priority;

        public Speaking(UUID uuid, String name, AudioCategory category, int priority) {
            this.uuid = uuid;
            this.name = name;
            this.category = category;
            this.priority = priority;
        }
    }

    public final Phase phase;
    public final Transport transport;
    /** Server-decided rules for where we are; {@code null} until known. */
    public final ProxyToClient.VoiceState voice;
    public final ProxyToClient.MuteStatus mute;
    public final ActivationMode activation;
    public final boolean talking;
    public final boolean microphoneMuted;
    public final boolean deafened;
    public final boolean inputAvailable;
    public final boolean outputAvailable;
    public final float microphoneLevelDb;
    public final int pingMillis;
    public final List<Speaking> speaking;
    public final String notice;

    public ClientStatus(Phase phase, Transport transport, ProxyToClient.VoiceState voice, ProxyToClient.MuteStatus mute,
                        ActivationMode activation, boolean talking, boolean microphoneMuted, boolean deafened,
                        boolean inputAvailable, boolean outputAvailable, float microphoneLevelDb, int pingMillis,
                        List<Speaking> speaking, String notice) {
        this.phase = phase;
        this.transport = transport;
        this.voice = voice;
        this.mute = mute;
        this.activation = activation;
        this.talking = talking;
        this.microphoneMuted = microphoneMuted;
        this.deafened = deafened;
        this.inputAvailable = inputAvailable;
        this.outputAvailable = outputAvailable;
        this.microphoneLevelDb = microphoneLevelDb;
        this.pingMillis = pingMillis;
        this.speaking = Collections.unmodifiableList(speaking);
        this.notice = notice;
    }

    public boolean isConnected() {
        return phase == Phase.CONNECTED;
    }
}
