package top.dext.bvc.protocol.packet;

import top.dext.bvc.protocol.io.Buf;

/**
 * A BVC packet travelling in one direction. {@code H} is the handler interface of the
 * receiving side, so dispatch is a plain virtual call instead of an instanceof chain.
 */
public interface Packet<H> {

    void write(Buf buf);

    void handle(H handler);
}
