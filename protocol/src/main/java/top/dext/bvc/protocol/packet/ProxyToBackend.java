package top.dext.bvc.protocol.packet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import top.dext.bvc.protocol.io.Buf;

/**
 * Packets sent by the proxy to the sub-server plugin over {@code bvc:proxy}.
 */
public final class ProxyToBackend {

    public interface Handler {

        void handle(ProxyHello packet);

        void handle(BindPlayers packet);

        void handle(UnbindPlayers packet);

        void handle(ResyncRequest packet);
    }

    public static final PacketRegistry<Handler> REGISTRY = new PacketRegistry<Handler>("proxy->backend")
            .register(0, ProxyHello.class, ProxyHello::read)
            .register(1, BindPlayers.class, BindPlayers::read)
            .register(2, UnbindPlayers.class, UnbindPlayers::read)
            .register(3, ResyncRequest.class, ResyncRequest::read);

    private ProxyToBackend() {
    }

    /**
     * Sent before every binding. {@code instanceId} is random per proxy start: when it changes the
     * proxy lost its tables (restart), and the backend answers with {@link BackendToProxy.BackendHello}
     * and a full resend.
     */
    public static final class ProxyHello implements Packet<Handler> {
        public final int protocolVersion;
        public final String pluginVersion;
        public final long instanceId;

        public ProxyHello(int protocolVersion, String pluginVersion, long instanceId) {
            this.protocolVersion = protocolVersion;
            this.pluginVersion = pluginVersion;
            this.instanceId = instanceId;
        }

        static ProxyHello read(Buf buf) {
            return new ProxyHello(buf.readVarInt(), buf.readString(64), buf.readLong());
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarInt(protocolVersion).writeString(pluginVersion).writeLong(instanceId);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** Assigns compact ids to players on this sub-server and says whether they have voice. */
    public static final class BindPlayers implements Packet<Handler> {
        public final List<Entry> entries;

        public BindPlayers(List<Entry> entries) {
            this.entries = Collections.unmodifiableList(entries);
        }

        static BindPlayers read(Buf buf) {
            int count = buf.readCount(BackendToProxy.MAX_ENTRIES);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new Entry(buf.readUuid(), buf.readVarInt(), buf.readBoolean()));
            }
            return new BindPlayers(entries);
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarInt(entries.size());
            for (Entry entry : entries) {
                buf.writeUuid(entry.uuid).writeVarInt(entry.playerId).writeBoolean(entry.voiceEnabled);
            }
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }

        public static final class Entry {
            public final UUID uuid;
            public final int playerId;
            public final boolean voiceEnabled;

            public Entry(UUID uuid, int playerId, boolean voiceEnabled) {
                this.uuid = uuid;
                this.playerId = playerId;
                this.voiceEnabled = voiceEnabled;
            }
        }
    }

    public static final class UnbindPlayers implements Packet<Handler> {
        public final int[] playerIds;

        public UnbindPlayers(int[] playerIds) {
            this.playerIds = playerIds;
        }

        static UnbindPlayers read(Buf buf) {
            return new UnbindPlayers(buf.readVarIntArray(BackendToProxy.MAX_ENTRIES));
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarIntArray(playerIds);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** Asks the sub-server to resend its complete state (after a proxy reload, for example). */
    public static final class ResyncRequest implements Packet<Handler> {

        public ResyncRequest() {
        }

        static ResyncRequest read(Buf buf) {
            return new ResyncRequest();
        }

        @Override
        public void write(Buf buf) {
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }
}
