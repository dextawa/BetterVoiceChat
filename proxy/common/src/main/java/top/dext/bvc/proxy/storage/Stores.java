package top.dext.bvc.proxy.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import top.dext.bvc.api.Mute;
import top.dext.bvc.api.MuteScope;

/**
 * Persistence for players (name lookups), mutes and small settings.
 */
public final class Stores {

    public record KnownPlayer(UUID uuid, String name, long lastSeen) {
    }

    private final Database database;

    public Stores(Database database) {
        this.database = database;
    }

    // ------------------------------------------------------------------ players

    public CompletableFuture<Void> touchPlayer(UUID uuid, String name, long now) {
        return database.async(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE bvc_players SET name = ?, name_lower = ?, last_seen = ? WHERE uuid = ?")) {
                update.setString(1, name);
                update.setString(2, name.toLowerCase(Locale.ROOT));
                update.setLong(3, now);
                update.setString(4, uuid.toString());
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO bvc_players (uuid, name, name_lower, last_seen) VALUES (?, ?, ?, ?)")) {
                        insert.setString(1, uuid.toString());
                        insert.setString(2, name);
                        insert.setString(3, name.toLowerCase(Locale.ROOT));
                        insert.setLong(4, now);
                        insert.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    public CompletableFuture<Optional<KnownPlayer>> findPlayer(String name) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, name, last_seen FROM bvc_players WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1")) {
                statement.setString(1, name.toLowerCase(Locale.ROOT));
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new KnownPlayer(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    // ------------------------------------------------------------------ mutes

    public CompletableFuture<Mute> insertMute(Mute mute) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO bvc_mutes (target_uuid, target_name, scope, reason, operator, created_at, expires_at, active)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)", PreparedStatement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, mute.target().toString());
                statement.setString(2, truncate(mute.targetName(), 32));
                statement.setString(3, mute.scope().key());
                statement.setString(4, truncate(mute.reason(), 512));
                statement.setString(5, truncate(mute.operator(), 64));
                statement.setLong(6, mute.createdAt());
                statement.setLong(7, mute.expiresAt());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    return keys.next() ? mute.withId(keys.getLong(1)) : mute;
                }
            }
        });
    }

    /** Marks active mutes of the player in this scope as removed. */
    public CompletableFuture<Integer> deactivateMutes(UUID target, MuteScope scope, String removedBy, long now) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE bvc_mutes SET active = FALSE, removed_by = ?, removed_at = ? WHERE target_uuid = ? AND scope = ? AND active = TRUE")) {
                statement.setString(1, truncate(removedBy, 64));
                statement.setLong(2, now);
                statement.setString(3, target.toString());
                statement.setString(4, scope.key());
                return statement.executeUpdate();
            }
        });
    }

    public List<Mute> loadActiveMutesSync(long now) throws SQLException {
        return database.sync(connection -> {
            List<Mute> mutes = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, target_uuid, target_name, scope, reason, operator, created_at, expires_at FROM bvc_mutes"
                            + " WHERE active = TRUE AND (expires_at = 0 OR expires_at > ?)")) {
                statement.setLong(1, now);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        mutes.add(readMute(rs));
                    }
                }
            }
            return mutes;
        });
    }

    public CompletableFuture<List<Mute>> muteHistory(UUID target, int limit) {
        return database.async(connection -> {
            List<Mute> mutes = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, target_uuid, target_name, scope, reason, operator, created_at, expires_at FROM bvc_mutes"
                            + " WHERE target_uuid = ? ORDER BY created_at DESC LIMIT ?")) {
                statement.setString(1, target.toString());
                statement.setInt(2, limit);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        mutes.add(readMute(rs));
                    }
                }
            }
            return mutes;
        });
    }

    private static Mute readMute(ResultSet rs) throws SQLException {
        return new Mute(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), MuteScope.parse(rs.getString(4)),
                rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8));
    }

    // ------------------------------------------------------------------ settings

    public CompletableFuture<Void> setSetting(String key, String value) {
        return database.async(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM bvc_settings WHERE k = ?")) {
                delete.setString(1, key);
                delete.executeUpdate();
            }
            if (value != null) {
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO bvc_settings (k, v) VALUES (?, ?)")) {
                    insert.setString(1, key);
                    insert.setString(2, value);
                    insert.executeUpdate();
                }
            }
            return null;
        });
    }

    public Optional<String> getSettingSync(String key) throws SQLException {
        return database.sync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT v FROM bvc_settings WHERE k = ?")) {
                statement.setString(1, key);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
