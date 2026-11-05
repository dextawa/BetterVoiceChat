package top.dext.bvc.protocol.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Random;

import javax.crypto.Cipher;

import org.junit.jupiter.api.Test;

class BvcCryptoTest {

    @Test
    void handshakeDerivesMatchingKeys() throws Exception {
        assertTrue(BvcCrypto.isKeyExchangeSupported());
        byte[] token = BvcCrypto.randomToken();

        KeyPair client = BvcCrypto.generateKeyPair();
        byte[] clientPublic = BvcCrypto.encodePublicKey(client.getPublic());
        byte[] mac = BvcCrypto.authMac(token, clientPublic);

        // Proxy side.
        assertTrue(BvcCrypto.verifyAuthMac(token, clientPublic, mac));
        KeyPair server = BvcCrypto.generateKeyPair();
        PublicKey clientKeyOnServer = BvcCrypto.decodePublicKey(clientPublic);
        SessionKeys serverKeys = BvcCrypto.deriveSessionKeys(BvcCrypto.agree(server.getPrivate(), clientKeyOnServer), token);

        // Client side.
        PublicKey serverKeyOnClient = BvcCrypto.decodePublicKey(BvcCrypto.encodePublicKey(server.getPublic()));
        SessionKeys clientKeys = BvcCrypto.deriveSessionKeys(BvcCrypto.agree(client.getPrivate(), serverKeyOnClient), token);

        assertStreamWorks(clientKeys.clientOutbound(), serverKeys.serverInbound());
        assertStreamWorks(serverKeys.serverOutbound(), clientKeys.clientInbound());
    }

    @Test
    void macRejectsWrongTokenOrKey() throws Exception {
        byte[] token = BvcCrypto.randomToken();
        byte[] key = BvcCrypto.encodePublicKey(BvcCrypto.generateKeyPair().getPublic());
        byte[] otherKey = BvcCrypto.encodePublicKey(BvcCrypto.generateKeyPair().getPublic());
        byte[] mac = BvcCrypto.authMac(token, key);

        assertFalse(BvcCrypto.verifyAuthMac(BvcCrypto.randomToken(), key, mac));
        assertFalse(BvcCrypto.verifyAuthMac(token, otherKey, mac));
        assertFalse(BvcCrypto.verifyAuthMac(token, key, new byte[3]));
    }

    @Test
    void rejectsGarbageKeys() {
        assertThrows(GeneralSecurityException.class, () -> BvcCrypto.decodePublicKey(new byte[] {1, 2, 3}));
    }

    /** Encrypt in one chunking, decrypt in another: CTR must not care about boundaries. */
    private static void assertStreamWorks(Cipher encryptor, Cipher decryptor) throws GeneralSecurityException {
        Random random = new Random(42);
        byte[] plain = new byte[10_000];
        random.nextBytes(plain);

        byte[] cipherText = new byte[plain.length];
        int offset = 0;
        while (offset < plain.length) {
            int chunk = Math.min(1 + random.nextInt(700), plain.length - offset);
            encryptor.update(plain, offset, chunk, cipherText, offset);
            offset += chunk;
        }
        assertFalse(java.util.Arrays.equals(plain, cipherText));

        byte[] decrypted = new byte[plain.length];
        offset = 0;
        while (offset < plain.length) {
            int chunk = Math.min(1 + random.nextInt(31), plain.length - offset);
            decryptor.update(cipherText, offset, chunk, decrypted, offset);
            offset += chunk;
        }
        assertArrayEquals(plain, decrypted);
    }
}
