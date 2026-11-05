package top.dext.bvc.protocol.stream;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.io.Buf;

/**
 * Opening bytes of a stream ("A") connection.
 * <p>
 * The client sends what looks like a normal Minecraft handshake packet whose server address
 * carries {@link BvcProtocol#HANDSHAKE_MARKER}. TCP forwarders and Minecraft-aware firewalls
 * see a valid handshake; the proxy's detector recognises the marker and takes the connection
 * over. Everything after the handshake is VarInt-length-prefixed BVC packets.
 */
public final class StreamHandshake {

    /** Minecraft handshake "next state" we claim (login), like a real joining client. */
    private static final int NEXT_STATE_LOGIN = 2;
    /** Our handshake is small; anything longer is certainly a real Minecraft client. */
    private static final int MAX_FRAME = 1024;

    private StreamHandshake() {
    }

    public static byte[] encode(String host, int port, int minecraftProtocol) {
        Buf body = new Buf(128);
        body.writeVarInt(0x00)
                .writeVarInt(minecraftProtocol)
                .writeString(host + BvcProtocol.HANDSHAKE_MARKER + BvcProtocol.VERSION)
                .writeShort(port)
                .writeVarInt(NEXT_STATE_LOGIN);
        Buf framed = new Buf(body.readableBytes() + 3);
        framed.writeVarInt(body.readableBytes());
        framed.writeBytes(body.array(), 0, body.writerIndex());
        return framed.toByteArray();
    }

    /**
     * Inspects the first bytes received on a fresh connection.
     *
     * @return {@link Detection#needMore()} until a decision can be made
     */
    public static Detection detect(byte[] data, int offset, int length) {
        if (length <= 0) {
            return Detection.NEED_MORE;
        }
        int first = data[offset] & 0xFF;
        // 0xFE = legacy server list ping, 0x02 = pre-1.7 login. Never ours.
        if (first == 0xFE || first == 0x02) {
            return Detection.NOT_BVC;
        }

        // Frame length VarInt, at most 3 bytes like Minecraft's frame decoder.
        int frameLength = 0;
        int lengthBytes = 0;
        while (true) {
            if (lengthBytes == 3) {
                return Detection.NOT_BVC;
            }
            if (lengthBytes >= length) {
                return Detection.NEED_MORE;
            }
            byte b = data[offset + lengthBytes];
            frameLength |= (b & 0x7F) << (7 * lengthBytes);
            lengthBytes++;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        if (frameLength <= 0 || frameLength > MAX_FRAME) {
            return Detection.NOT_BVC;
        }
        if (length > lengthBytes && data[offset + lengthBytes] != 0x00) {
            return Detection.NOT_BVC; // not a handshake packet
        }
        if (length < lengthBytes + frameLength) {
            return Detection.NEED_MORE;
        }

        try {
            Buf frame = Buf.wrap(data, offset + lengthBytes, frameLength);
            if (frame.readVarInt() != 0x00) {
                return Detection.NOT_BVC;
            }
            frame.readVarInt(); // Minecraft protocol version, irrelevant to us
            String address = frame.readString(MAX_FRAME);
            frame.readUnsignedShort();
            frame.readVarInt();
            int marker = address.indexOf(BvcProtocol.HANDSHAKE_MARKER);
            if (marker < 0) {
                return Detection.NOT_BVC;
            }
            String host = address.substring(0, marker);
            String versionText = address.substring(marker + BvcProtocol.HANDSHAKE_MARKER.length());
            int version;
            try {
                version = Integer.parseInt(versionText.trim());
            } catch (NumberFormatException e) {
                version = -1;
            }
            return new Detection(Detection.Kind.BVC, lengthBytes + frameLength, version, host);
        } catch (RuntimeException e) {
            return Detection.NOT_BVC;
        }
    }

    /** Result of {@link #detect}. */
    public static final class Detection {

        public enum Kind {
            NEED_MORE,
            NOT_BVC,
            BVC
        }

        static final Detection NEED_MORE = new Detection(Kind.NEED_MORE, 0, -1, "");
        static final Detection NOT_BVC = new Detection(Kind.NOT_BVC, 0, -1, "");

        private final Kind kind;
        private final int consumed;
        private final int protocolVersion;
        private final String host;

        Detection(Kind kind, int consumed, int protocolVersion, String host) {
            this.kind = kind;
            this.consumed = consumed;
            this.protocolVersion = protocolVersion;
            this.host = host;
        }

        public static Detection needMore() {
            return NEED_MORE;
        }

        public Kind kind() {
            return kind;
        }

        /** Bytes taken by the handshake (only for {@link Kind#BVC}). */
        public int consumed() {
            return consumed;
        }

        /** BVC protocol version announced by the client, or -1 if unparsable. */
        public int protocolVersion() {
            return protocolVersion;
        }

        public String host() {
            return host;
        }

        @Override
        public String toString() {
            return kind == Kind.BVC ? "BVC(v" + protocolVersion + ", " + consumed + " bytes, host=" + host + ")" : kind.name();
        }
    }
}
