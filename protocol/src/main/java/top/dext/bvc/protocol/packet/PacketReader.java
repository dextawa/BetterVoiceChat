package top.dext.bvc.protocol.packet;

import top.dext.bvc.protocol.io.Buf;

@FunctionalInterface
public interface PacketReader<T> {

    T read(Buf buf);
}
