package top.dext.bvc.proxy.recording;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import top.dext.bvc.proxy.storage.Database;

/**
 * Database index of utterances (one push-to-talk press or one voice-activation run), so
 * recordings can be found by player, server and time without scanning files.
 */
public final class RecordingIndex {

    /**
     * Where one utterance lives: frames with {@code localId} between the two byte offsets of
     * {@code segment} (relative to the recordings directory).
     */
    public record Utterance(long id, String segment, String server, UUID speaker, String speakerName, long startedAt,
                            long endedAt, int frames, int localId, long offsetFrom, long offsetTo) {
    }

    private static final String COLUMNS =
            "id, segment, server, speaker_uuid, speaker_name, started_at, ended_at, frames, local_id, offset_from, offset_to";

    private final Database database;

    public RecordingIndex(Database database) {
        this.database = database;
    }

    CompletableFuture<Void> insert(Utterance u) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO bvc_utterances (segment, server, speaker_uuid, speaker_name, started_at, ended_at, frames,"
                            + " local_id, offset_from, offset_to) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, u.segment());
                statement.setString(2, truncate(u.server(), 64));
                statement.setString(3, u.speaker().toString());
                statement.setString(4, truncate(u.speakerName(), 32));
                statement.setLong(5, u.startedAt());
                statement.setLong(6, u.endedAt());
                statement.setInt(7, u.frames());
                statement.setInt(8, u.localId());
                statement.setLong(9, u.offsetFrom());
                statement.setLong(10, u.offsetTo());
                statement.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Integer> deleteSegment(String segment) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM bvc_utterances WHERE segment = ?")) {
                statement.setString(1, segment);
                return statement.executeUpdate();
            }
        });
    }

    /** Utterances overlapping {@code [from, to]}, by any of {@code speakers} ({@code null} = everyone). */
    public CompletableFuture<List<Utterance>> overlapping(long from, long to, Collection<UUID> speakers) {
        return database.async(connection -> {
            StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM bvc_utterances WHERE started_at <= ? AND ended_at >= ?");
            if (speakers != null) {
                if (speakers.isEmpty()) {
                    return List.of();
                }
                sql.append(" AND speaker_uuid IN (");
                for (int i = 0; i < speakers.size(); i++) {
                    sql.append(i == 0 ? "?" : ", ?");
                }
                sql.append(')');
            }
            sql.append(" ORDER BY started_at");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                statement.setLong(1, to);
                statement.setLong(2, from);
                int index = 3;
                if (speakers != null) {
                    for (UUID speaker : speakers) {
                        statement.setString(index++, speaker.toString());
                    }
                }
                return read(statement);
            }
        });
    }

    /** Search for the panel; {@code speaker} and {@code server} may be {@code null}. Newest first. */
    public CompletableFuture<List<Utterance>> search(UUID speaker, String server, long from, long to, int limit) {
        return database.async(connection -> {
            StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM bvc_utterances WHERE started_at <= ? AND ended_at >= ?");
            if (speaker != null) {
                sql.append(" AND speaker_uuid = ?");
            }
            if (server != null) {
                sql.append(" AND server = ?");
            }
            sql.append(" ORDER BY started_at DESC LIMIT ?");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                statement.setLong(index++, to);
                statement.setLong(index++, from);
                if (speaker != null) {
                    statement.setString(index++, speaker.toString());
                }
                if (server != null) {
                    statement.setString(index++, server);
                }
                statement.setInt(index, limit);
                return read(statement);
            }
        });
    }

    public CompletableFuture<Utterance> get(long id) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM bvc_utterances WHERE id = ?")) {
                statement.setLong(1, id);
                List<Utterance> found = read(statement);
                return found.isEmpty() ? null : found.get(0);
            }
        });
    }

    private static List<Utterance> read(PreparedStatement statement) throws SQLException {
        List<Utterance> out = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.add(new Utterance(rs.getLong(1), rs.getString(2), rs.getString(3), UUID.fromString(rs.getString(4)),
                        rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getLong(10), rs.getLong(11)));
            }
        }
        return out;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
