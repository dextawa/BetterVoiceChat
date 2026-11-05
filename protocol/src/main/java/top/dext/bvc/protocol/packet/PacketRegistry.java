package top.dext.bvc.protocol.packet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.dext.bvc.protocol.ProtocolException;
import top.dext.bvc.protocol.io.Buf;

/**
 * Maps packet ids to classes for one direction. Wire format: {@code VarInt id} then the
 * packet body. Trailing bytes after a known body are ignored so newer peers can append
 * fields without breaking older ones.
 */
public final class PacketRegistry<H> {

    private final String name;
    private final Map<Class<?>, Integer> idsByType = new HashMap<>();
    private final List<PacketReader<? extends Packet<H>>> readers = new ArrayList<>();

    public PacketRegistry(String name) {
        this.name = name;
    }

    public <T extends Packet<H>> PacketRegistry<H> register(int id, Class<T> type, PacketReader<T> reader) {
        if (id != readers.size()) {
            throw new IllegalArgumentException(name + ": packet ids must be registered in order, expected " + readers.size());
        }
        if (idsByType.put(type, id) != null) {
            throw new IllegalArgumentException(name + ": " + type.getSimpleName() + " registered twice");
        }
        readers.add(reader);
        return this;
    }

    public int idOf(Packet<H> packet) {
        Integer id = idsByType.get(packet.getClass());
        if (id == null) {
            throw new IllegalArgumentException(packet.getClass().getSimpleName() + " is not a " + name + " packet");
        }
        return id;
    }

    public void encode(Packet<H> packet, Buf out) {
        out.writeVarInt(idOf(packet));
        packet.write(out);
    }

    public byte[] encode(Packet<H> packet) {
        Buf buf = new Buf();
        encode(packet, buf);
        return buf.toByteArray();
    }

    public Packet<H> decode(Buf in) {
        int id = in.readVarInt();
        if (id < 0 || id >= readers.size()) {
            throw new ProtocolException("Unknown " + name + " packet id " + id);
        }
        return readers.get(id).read(in);
    }

    public Packet<H> decode(byte[] data) {
        return decode(Buf.wrap(data));
    }

    public String name() {
        return name;
    }
}
