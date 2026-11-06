package top.dext.bvc.proxy.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import top.dext.bvc.proxy.storage.Database;

/**
 * Panel accounts, sessions, one-time login links and the audit log.
 * <p>
 * Accounts are created by {@code /bvc web} from in-game (named after the player, permissions
 * copied from {@code bvc.web.*}). A password is optional: set from the panel, it allows logging
 * in without going through the game.
 */
public final class WebAuth {

    public enum Permission {
        /** View reports and recordings, close reports. */
        REVIEW("bvc.web.review"),
        /** Mute and unmute from the panel. */
        MUTE("bvc.web.mute"),
        /** Manage panel accounts, read the audit log. */
        ADMIN("bvc.web.admin");

        public final String node;

        Permission(String node) {
            this.node = node;
        }
    }

    public record User(String username, UUID uuid, Set<Permission> permissions, boolean hasPassword) {
        public boolean can(Permission permission) {
            return permissions.contains(permission) || permissions.contains(Permission.ADMIN);
        }
    }

    public record AuditEntry(long at, String username, String action, String detail) {
    }

    private record Session(String username, long expiresAt) {
    }

    private record LoginToken(String username, long expiresAt) {
    }

    static final long LOGIN_TOKEN_MILLIS = 5 * 60_000;
    private static final int PBKDF2_ITERATIONS = 120_000;

    private final Database database;
    private final SecureRandom random = new SecureRandom();
    /** Cache of {@code bvc_web_sessions}, keyed by the cookie value. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, LoginToken> loginTokens = new ConcurrentHashMap<>();
    /** Failed password logins per username, to slow down guessing. */
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();

    public WebAuth(Database database) {
        this.database = database;
    }

    // ------------------------------------------------------------------ accounts

    /** Creates or updates the account of an in-game admin and returns a one-time login token. */
    public CompletableFuture<String> issueLoginToken(String username, UUID uuid, Set<Permission> permissions, long now) {
        String name = normalize(username);
        return database.async(connection -> {
            String perms = encode(permissions);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE bvc_web_users SET uuid = ?, permissions = ? WHERE username = ?")) {
                update.setString(1, uuid == null ? null : uuid.toString());
                update.setString(2, perms);
                update.setString(3, name);
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO bvc_web_users (username, uuid, password_hash, permissions, created_at) VALUES (?, ?, NULL, ?, ?)")) {
                        insert.setString(1, name);
                        insert.setString(2, uuid == null ? null : uuid.toString());
                        insert.setString(3, perms);
                        insert.setLong(4, now);
                        insert.executeUpdate();
                    }
                }
            }
            String token = randomToken();
            loginTokens.put(token, new LoginToken(name, now + LOGIN_TOKEN_MILLIS));
            return token;
        });
    }

    public CompletableFuture<Optional<User>> user(String username) {
        String name = normalize(username);
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT username, uuid, permissions, password_hash FROM bvc_web_users WHERE username = ?")) {
                statement.setString(1, name);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<User>empty();
                    }
                    return Optional.of(readUser(rs));
                }
            }
        });
    }

    public CompletableFuture<List<User>> users() {
        return database.async(connection -> {
            List<User> out = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT username, uuid, permissions, password_hash FROM bvc_web_users ORDER BY username");
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.add(readUser(rs));
                }
            }
            return out;
        });
    }

    public CompletableFuture<Boolean> setPermissions(String username, Set<Permission> permissions) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE bvc_web_users SET permissions = ? WHERE username = ?")) {
                statement.setString(1, encode(permissions));
                statement.setString(2, normalize(username));
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Boolean> deleteUser(String username) {
        String name = normalize(username);
        sessions.values().removeIf(session -> session.username().equals(name));
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM bvc_web_sessions WHERE username = ?")) {
                statement.setString(1, name);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM bvc_web_users WHERE username = ?")) {
                statement.setString(1, name);
                return statement.executeUpdate() > 0;
            }
        });
    }

    /** {@code password == null} removes the password (in-game links only). */
    public CompletableFuture<Void> setPassword(String username, String password) {
        String hash = password == null ? null : hash(password);
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE bvc_web_users SET password_hash = ? WHERE username = ?")) {
                statement.setString(1, hash);
                statement.setString(2, normalize(username));
                statement.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ sessions

    /** Exchanges a one-time token for a session id, or empty if it is unknown or expired. */
    public Optional<String> redeem(String token, long now, long sessionMillis) {
        LoginToken login = token == null ? null : loginTokens.remove(token);
        if (login == null || login.expiresAt() < now) {
            return Optional.empty();
        }
        return Optional.of(newSession(login.username(), now, sessionMillis));
    }

    /** Password login; empty on a wrong password, an account without one, or too many failures. */
    public CompletableFuture<Optional<String>> passwordLogin(String username, String password, long now, long sessionMillis) {
        String name = normalize(username);
        long[] fail = failures.get(name);
        if (fail != null && fail[0] >= 5 && now - fail[1] < 5 * 60_000) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT password_hash FROM bvc_web_users WHERE username = ?")) {
                statement.setString(1, name);
                try (ResultSet rs = statement.executeQuery()) {
                    String hash = rs.next() ? rs.getString(1) : null;
                    if (hash == null || password == null || !verify(password, hash)) {
                        failures.compute(name, (key, old) -> old == null || now - old[1] >= 5 * 60_000
                                ? new long[] {1, now} : new long[] {old[0] + 1, now});
                        return Optional.<String>empty();
                    }
                }
            }
            failures.remove(name);
            return Optional.of(newSession(name, now, sessionMillis));
        });
    }

    public Optional<String> sessionUser(String sessionId, long now) {
        if (sessionId == null || sessionId.length() > 64) {
            return Optional.empty();
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            session = loadSession(sessionId);
            if (session == null) {
                return Optional.empty();
            }
            sessions.put(sessionId, session);
        }
        if (session.expiresAt() < now) {
            logout(sessionId);
            return Optional.empty();
        }
        return Optional.of(session.username());
    }

    public void logout(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
            String hash = sha256(sessionId);
            database.async(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM bvc_web_sessions WHERE id_hash = ?")) {
                    statement.setString(1, hash);
                    statement.executeUpdate();
                }
                return null;
            });
        }
    }

    private Session loadSession(String sessionId) {
        try {
            return database.async(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT username, expires_at FROM bvc_web_sessions WHERE id_hash = ?")) {
                    statement.setString(1, sha256(sessionId));
                    try (ResultSet rs = statement.executeQuery()) {
                        return rs.next() ? new Session(rs.getString(1), rs.getLong(2)) : null;
                    }
                }
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void purge(long now) {
        sessions.values().removeIf(session -> session.expiresAt() < now);
        loginTokens.values().removeIf(token -> token.expiresAt() < now);
        database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM bvc_web_sessions WHERE expires_at < ?")) {
                statement.setLong(1, now);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private String newSession(String username, long now, long sessionMillis) {
        String id = randomToken();
        Session session = new Session(username, now + sessionMillis);
        sessions.put(id, session);
        String hash = sha256(id);
        database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO bvc_web_sessions (id_hash, username, expires_at) VALUES (?, ?, ?)")) {
                statement.setString(1, hash);
                statement.setString(2, session.username());
                statement.setLong(3, session.expiresAt());
                statement.executeUpdate();
            }
            return null;
        });
        return id;
    }

    // ------------------------------------------------------------------ audit

    public CompletableFuture<Void> audit(String username, String action, String detail, long now) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO bvc_audit (at, username, action, detail) VALUES (?, ?, ?, ?)")) {
                statement.setLong(1, now);
                statement.setString(2, username);
                statement.setString(3, action);
                statement.setString(4, detail.length() > 1024 ? detail.substring(0, 1024) : detail);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<List<AuditEntry>> auditLog(int limit, int offset) {
        return database.async(connection -> {
            List<AuditEntry> out = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT at, username, action, detail FROM bvc_audit ORDER BY id DESC LIMIT ? OFFSET ?")) {
                statement.setInt(1, limit);
                statement.setInt(2, offset);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        out.add(new AuditEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ helpers

    static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static User readUser(ResultSet rs) throws java.sql.SQLException {
        String uuid = rs.getString(2);
        return new User(rs.getString(1), uuid == null ? null : UUID.fromString(uuid), decode(rs.getString(3)), rs.getString(4) != null);
    }

    static String encode(Set<Permission> permissions) {
        StringBuilder out = new StringBuilder();
        for (Permission permission : permissions) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(permission.name());
        }
        return out.toString();
    }

    static Set<Permission> decode(String text) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        if (text != null) {
            for (String part : text.split(",")) {
                try {
                    out.add(Permission.valueOf(part.trim()));
                } catch (IllegalArgumentException ignored) {
                    // unknown or empty
                }
            }
        }
        return out;
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + HexFormat.of().formatHex(salt) + "$"
                + HexFormat.of().formatHex(pbkdf2(password, salt, PBKDF2_ITERATIONS));
    }

    static boolean verify(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) {
            return false;
        }
        try {
            byte[] expected = HexFormat.of().parseHex(parts[3]);
            byte[] actual = pbkdf2(password, HexFormat.of().parseHex(parts[2]), Integer.parseInt(parts[1]));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** UTF-8 helper for request bodies. */
    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
