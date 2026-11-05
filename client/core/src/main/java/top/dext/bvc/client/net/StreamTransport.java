package top.dext.bvc.client.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPair;

import javax.crypto.Cipher;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.ProtocolException;
import top.dext.bvc.protocol.crypto.BvcCrypto;
import top.dext.bvc.protocol.crypto.SessionKeys;
import top.dext.bvc.protocol.io.Buf;
import top.dext.bvc.protocol.packet.ClientToProxy;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.protocol.packet.ProxyToClient;
import top.dext.bvc.protocol.stream.StreamHandshake;

/**
 * Client side of the stream ("A") transport: a second TCP connection to the Minecraft port,
 * opened with a fake Minecraft handshake, authenticated with the session token and encrypted
 * with AES-CTR. Plain blocking sockets keep it independent of the game's Netty version.
 */
public final class StreamTransport {

    public interface Listener {
        void onPacket(StreamTransport transport, Packet<ProxyToClient.Handler> packet);

        /** Called once when the connection ends for any reason after a successful connect. */
        void onClosed(StreamTransport transport, Throwable cause);
    }

    private final Socket socket = new Socket();
    private volatile boolean closed;
    private Cipher encryptor;
    private OutputStream out;

    /**
     * Connects and authenticates; blocks up to about {@code timeoutMillis}. On success a reader
     * thread delivers packets to {@code listener}.
     *
     * @param handshakeHost host name for the fake handshake; use the one the game itself sent,
     *                      since some DDoS-protection proxies route connections by it
     */
    public void connect(String host, int port, String handshakeHost, int minecraftProtocol, int sessionId, byte[] token,
                        int timeoutMillis, final Listener listener) throws IOException, GeneralSecurityException {
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            InputStream rawIn = new BufferedInputStream(socket.getInputStream());
            OutputStream rawOut = socket.getOutputStream();

            KeyPair keyPair = BvcCrypto.generateKeyPair();
            byte[] publicKey = BvcCrypto.encodePublicKey(keyPair.getPublic());
            Buf opening = new Buf(256);
            opening.writeBytes(StreamHandshake.encode(handshakeHost == null || handshakeHost.isEmpty() ? host : handshakeHost,
                    port, minecraftProtocol));
            frame(opening, ClientToProxy.REGISTRY.encode(new ClientToProxy.Auth(sessionId, publicKey,
                    BvcCrypto.authMac(token, publicKey))));
            rawOut.write(opening.array(), 0, opening.writerIndex());
            rawOut.flush();

            // The answer is plain text; everything after it is encrypted.
            Packet<ProxyToClient.Handler> answer = ProxyToClient.REGISTRY.decode(readFrame(rawIn));
            if (!(answer instanceof ProxyToClient.AuthResult)) {
                throw new IOException("Unexpected first packet " + answer.getClass().getSimpleName());
            }
            ProxyToClient.AuthResult result = (ProxyToClient.AuthResult) answer;
            if (!result.success) {
                throw new IOException("Proxy rejected the stream: " + result.message);
            }
            SessionKeys keys = BvcCrypto.deriveSessionKeys(
                    BvcCrypto.agree(keyPair.getPrivate(), BvcCrypto.decodePublicKey(result.publicKey)), token);
            encryptor = keys.clientOutbound();
            out = new BufferedOutputStream(rawOut, 4096);
            final InputStream in = new DecryptingInputStream(rawIn, keys.clientInbound());
            socket.setSoTimeout(0);

            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop(in, listener);
                }
            }, "BVC-Stream");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException | GeneralSecurityException | ProtocolException e) {
            closeQuietly();
            if (e instanceof ProtocolException) {
                throw new IOException(e.getMessage(), e);
            }
            throw e;
        }
    }

    private void readLoop(InputStream in, Listener listener) {
        Throwable cause = null;
        try {
            while (!closed) {
                listener.onPacket(this, ProxyToClient.REGISTRY.decode(readFrame(in)));
            }
        } catch (IOException | ProtocolException e) {
            cause = e;
        } catch (RuntimeException e) {
            cause = e;
        } finally {
            boolean wasOpen = !closed;
            closeQuietly();
            if (wasOpen) {
                listener.onClosed(this, cause);
            }
        }
    }

    /** Thread-safe. Failures close the connection; the reader thread then reports it. */
    public void send(byte[] packet) {
        if (closed) {
            return;
        }
        Buf framed = new Buf(packet.length + 3);
        frame(framed, packet);
        synchronized (this) {
            try {
                out.write(encryptor.update(framed.array(), 0, framed.writerIndex()));
                out.flush();
            } catch (IOException e) {
                closeQuietly();
            }
        }
    }

    public boolean isOpen() {
        return !closed && socket.isConnected();
    }

    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    private static void frame(Buf buf, byte[] packet) {
        buf.writeVarInt(packet.length);
        buf.writeBytes(packet);
    }

    static byte[] readFrame(InputStream in) throws IOException {
        int length = 0;
        for (int i = 0; ; i++) {
            if (i == 3) {
                throw new IOException("Frame length VarInt too long");
            }
            int b = in.read();
            if (b < 0) {
                throw new EOFException();
            }
            length |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                break;
            }
        }
        if (length <= 0 || length > BvcProtocol.MAX_STREAM_FRAME) {
            throw new IOException("Bad frame length " + length);
        }
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(data, read, length - read);
            if (n < 0) {
                throw new EOFException();
            }
            read += n;
        }
        return data;
    }

    /** CTR is a stream cipher, so decrypting in place chunk by chunk is exact. */
    private static final class DecryptingInputStream extends FilterInputStream {
        private final Cipher cipher;
        private final byte[] single = new byte[1];

        DecryptingInputStream(InputStream in, Cipher cipher) {
            super(in);
            this.cipher = cipher;
        }

        @Override
        public int read() throws IOException {
            int n = read(single, 0, 1);
            return n < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                try {
                    cipher.update(b, off, n, b, off);
                } catch (javax.crypto.ShortBufferException e) {
                    throw new IOException(e);
                }
            }
            return n;
        }
    }
}
