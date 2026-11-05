package top.dext.bvc.protocol.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Per-direction AES-128-CTR keys for one stream connection. CTR is a stream cipher, so it
 * adds no per-packet overhead and works on arbitrary chunk boundaries.
 */
public final class SessionKeys {

    private final byte[] clientToServerKey;
    private final byte[] clientToServerIv;
    private final byte[] serverToClientKey;
    private final byte[] serverToClientIv;

    SessionKeys(byte[] clientToServerKey, byte[] clientToServerIv, byte[] serverToClientKey, byte[] serverToClientIv) {
        this.clientToServerKey = clientToServerKey;
        this.clientToServerIv = clientToServerIv;
        this.serverToClientKey = serverToClientKey;
        this.serverToClientIv = serverToClientIv;
    }

    /** Client side: encrypts what the client sends. */
    public Cipher clientOutbound() {
        return ctr(Cipher.ENCRYPT_MODE, clientToServerKey, clientToServerIv);
    }

    /** Client side: decrypts what the proxy sends. */
    public Cipher clientInbound() {
        return ctr(Cipher.DECRYPT_MODE, serverToClientKey, serverToClientIv);
    }

    /** Proxy side: encrypts what the proxy sends. */
    public Cipher serverOutbound() {
        return ctr(Cipher.ENCRYPT_MODE, serverToClientKey, serverToClientIv);
    }

    /** Proxy side: decrypts what the client sends. */
    public Cipher serverInbound() {
        return ctr(Cipher.DECRYPT_MODE, clientToServerKey, clientToServerIv);
    }

    private static Cipher ctr(int mode, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/CTR is required by every Java runtime", e);
        }
    }
}
