package top.dext.bvc.protocol.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import top.dext.bvc.protocol.BvcProtocol;

/**
 * Key exchange and key derivation for the stream transport.
 * <p>
 * Flow: the proxy hands the client a one-time token over the Minecraft connection. On the
 * stream connection the client sends its ECDH public key plus {@code HMAC(token, publicKey)},
 * so the token itself never travels on the stream. Both sides then derive per-direction
 * AES-CTR keys from the ECDH secret salted with the token (HKDF-SHA256).
 * <p>
 * secp256r1 is used because Java 8 ships it (X25519 needs Java 11).
 */
public final class BvcCrypto {

    private static final String CURVE = "secp256r1";
    private static final byte[] AUTH_LABEL = "bvc-auth-v1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private BvcCrypto() {
    }

    /** False on the rare JREs without EC support; such clients use the plugin-message transport. */
    public static boolean isKeyExchangeSupported() {
        try {
            KeyPair pair = generateKeyPair();
            agree(pair.getPrivate(), pair.getPublic());
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(CURVE), RANDOM);
        return generator.generateKeyPair();
    }

    public static byte[] encodePublicKey(PublicKey key) {
        return key.getEncoded();
    }

    public static PublicKey decodePublicKey(byte[] encoded) throws GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
        if (!(key instanceof ECPublicKey) || ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize() != 256) {
            throw new GeneralSecurityException("Unexpected key type or curve");
        }
        return key;
    }

    public static byte[] agree(PrivateKey privateKey, PublicKey peerKey) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(peerKey, true);
        return agreement.generateSecret();
    }

    public static byte[] randomToken() {
        byte[] token = new byte[BvcProtocol.TOKEN_LENGTH];
        RANDOM.nextBytes(token);
        return token;
    }

    public static byte[] authMac(byte[] token, byte[] clientPublicKey) {
        byte[] data = new byte[AUTH_LABEL.length + clientPublicKey.length];
        System.arraycopy(AUTH_LABEL, 0, data, 0, AUTH_LABEL.length);
        System.arraycopy(clientPublicKey, 0, data, AUTH_LABEL.length, clientPublicKey.length);
        return hmac(token, data);
    }

    /** Constant-time check of {@link #authMac}. */
    public static boolean verifyAuthMac(byte[] token, byte[] clientPublicKey, byte[] mac) {
        return MessageDigest.isEqual(authMac(token, clientPublicKey), mac);
    }

    public static SessionKeys deriveSessionKeys(byte[] sharedSecret, byte[] token) {
        byte[] prk = hmac(token, sharedSecret); // HKDF-Extract(salt = token, ikm = secret)
        return new SessionKeys(
                expand(prk, "bvc c2s key", 16),
                expand(prk, "bvc c2s iv", 16),
                expand(prk, "bvc s2c key", 16),
                expand(prk, "bvc s2c iv", 16));
    }

    /** Single-block HKDF-Expand; enough for outputs up to 32 bytes. */
    private static byte[] expand(byte[] prk, String info, int length) {
        byte[] infoBytes = info.getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[infoBytes.length + 1];
        System.arraycopy(infoBytes, 0, input, 0, infoBytes.length);
        input[infoBytes.length] = 1;
        byte[] block = hmac(prk, input);
        byte[] out = new byte[length];
        System.arraycopy(block, 0, out, 0, length);
        return out;
    }

    /**
     * Proof that the sender of a {@code ProxyHello} / {@code BackendHello} knows the shared
     * secret configured on the proxy and its sub-servers. {@code role} is "proxy" or "backend";
     * binding it to the instance ids stops a captured proof from being reused elsewhere.
     */
    public static byte[] linkProof(String secret, String role, long proxyInstance, long backendInstance) {
        byte[] key = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] data = (role + "|" + proxyInstance + "|" + backendInstance).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return hmac(key, data);
    }

    public static boolean verifyLinkProof(String secret, String role, long proxyInstance, long backendInstance, byte[] proof) {
        return java.security.MessageDigest.isEqual(linkProof(secret, role, proxyInstance, backendInstance), proof);
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by every Java runtime", e);
        }
    }
}
