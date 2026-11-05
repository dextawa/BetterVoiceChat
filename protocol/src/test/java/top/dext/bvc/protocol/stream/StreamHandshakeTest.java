package top.dext.bvc.protocol.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.io.Buf;
import top.dext.bvc.protocol.stream.StreamHandshake.Detection;

class StreamHandshakeTest {

    @Test
    void detectsOwnHandshake() {
        byte[] handshake = StreamHandshake.encode("play.example.com", 25565, 47);
        Detection detection = StreamHandshake.detect(handshake, 0, handshake.length);
        assertEquals(Detection.Kind.BVC, detection.kind());
        assertEquals(handshake.length, detection.consumed());
        assertEquals(BvcProtocol.VERSION, detection.protocolVersion());
        assertEquals("play.example.com", detection.host());
    }

    @Test
    void trailingBytesAreNotConsumed() {
        byte[] handshake = StreamHandshake.encode("1.2.3.4", 25565, 770);
        byte[] withAuth = Arrays.copyOf(handshake, handshake.length + 10);
        Detection detection = StreamHandshake.detect(withAuth, 0, withAuth.length);
        assertEquals(Detection.Kind.BVC, detection.kind());
        assertEquals(handshake.length, detection.consumed());
    }

    @Test
    void partialHandshakeNeedsMore() {
        byte[] handshake = StreamHandshake.encode("play.example.com", 25565, 47);
        for (int length = 0; length < handshake.length; length++) {
            assertEquals(Detection.Kind.NEED_MORE, StreamHandshake.detect(handshake, 0, length).kind(), "prefix " + length);
        }
    }

    @Test
    void realMinecraftHandshakeIsNotOurs() {
        byte[] vanilla = minecraftHandshake("play.example.com", 25565, 770, 2);
        assertEquals(Detection.Kind.NOT_BVC, StreamHandshake.detect(vanilla, 0, vanilla.length).kind());

        byte[] forge = minecraftHandshake("play.example.com\0FML3\0", 25565, 770, 2);
        assertEquals(Detection.Kind.NOT_BVC, StreamHandshake.detect(forge, 0, forge.length).kind());

        byte[] status = minecraftHandshake("localhost", 25565, 47, 1);
        assertEquals(Detection.Kind.NOT_BVC, StreamHandshake.detect(status, 0, status.length).kind());
    }

    @Test
    void decidesEarlyForOtherTraffic() {
        byte[] legacyPing = {(byte) 0xFE, 0x01};
        assertEquals(Detection.Kind.NOT_BVC, StreamHandshake.detect(legacyPing, 0, 1).kind());

        // Length 5, packet id 0x01: not a handshake, decided after two bytes.
        byte[] notHandshake = {0x05, 0x01};
        assertEquals(Detection.Kind.NOT_BVC, StreamHandshake.detect(notHandshake, 0, 2).kind());

        byte[] huge = new Buf().writeVarInt(100_000).toByteArray();
        assertEquals(Detection.Kind.NOT_BVC, StreamHandshake.detect(huge, 0, huge.length).kind());

        byte[] http = "GET / HTTP/1.1\r\n".getBytes();
        assertEquals(Detection.Kind.NOT_BVC, StreamHandshake.detect(http, 0, http.length).kind());
    }

    private static byte[] minecraftHandshake(String host, int port, int protocol, int nextState) {
        Buf body = new Buf().writeVarInt(0).writeVarInt(protocol).writeString(host).writeShort(port).writeVarInt(nextState);
        Buf framed = new Buf().writeVarInt(body.readableBytes());
        framed.writeBytes(body.toByteArray());
        return framed.toByteArray();
    }
}
