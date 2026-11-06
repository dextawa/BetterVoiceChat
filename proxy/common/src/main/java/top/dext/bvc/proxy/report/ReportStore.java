package top.dext.bvc.proxy.report;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import top.dext.bvc.proxy.storage.Database;

/**
 * Report rows in the database.
 */
public final class ReportStore {

    public enum Status {
        /** Waiting for the "after" part of the evidence window. */
        COLLECTING,
        OPEN,
        ACCEPTED,
        REJECTED
    }

    public record Report(long id, UUID reporter, String reporterName, UUID target, String targetName, String server,
                         String reason, long createdAt, long windowFrom, long windowTo, Status status, String handledBy,
                         long handledAt, String note) {
    }

    private static final String COLUMNS = "id, reporter_uuid, reporter_name, target_uuid, target_name, server, reason,"
            + " created_at, window_from, window_to, status, handled_by, handled_at, note";

    private final Database database;

    public ReportStore(Database database) {
        this.database = database;
    }

    public CompletableFuture<Report> insert(Report report) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO bvc_reports (reporter_uuid, reporter_name, target_uuid, target_name, server, reason, created_at,"
                            + " window_from, window_to, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, report.reporter().toString());
                statement.setString(2, truncate(report.reporterName(), 32));
                statement.setString(3, report.target().toString());
                statement.setString(4, truncate(report.targetName(), 32));
                statement.setString(5, truncate(report.server(), 64));
                statement.setString(6, truncate(report.reason(), 512));
                statement.setLong(7, report.createdAt());
                statement.setLong(8, report.windowFrom());
                statement.setLong(9, report.windowTo());
                statement.setString(10, report.status().name());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    long id = keys.getLong(1);
                    return new Report(id, report.reporter(), report.reporterName(), report.target(), report.targetName(),
                            report.server(), report.reason(), report.createdAt(), report.windowFrom(), report.windowTo(),
                            report.status(), null, 0, null);
                }
            }
        });
    }

    public CompletableFuture<Void> setStatus(long id, Status status, String handledBy, long handledAt, String note) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE bvc_reports SET status = ?, handled_by = ?, handled_at = ?, note = ? WHERE id = ?")) {
                statement.setString(1, status.name());
                statement.setString(2, handledBy == null ? null : truncate(handledBy, 64));
                if (handledBy == null) {
                    statement.setNull(3, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(3, handledAt);
                }
                statement.setString(4, note == null ? null : truncate(note, 1024));
                statement.setLong(5, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Report> get(long id) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM bvc_reports WHERE id = ?")) {
                statement.setLong(1, id);
                List<Report> found = read(statement);
                return found.isEmpty() ? null : found.get(0);
            }
        });
    }

    /** Newest first; {@code status == null} = all. */
    public CompletableFuture<List<Report>> list(Status status, int limit, int offset) {
        return database.async(connection -> {
            String sql = "SELECT " + COLUMNS + " FROM bvc_reports" + (status == null ? "" : " WHERE status = ?")
                    + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                if (status != null) {
                    statement.setString(index++, status.name());
                }
                statement.setInt(index++, limit);
                statement.setInt(index, offset);
                return read(statement);
            }
        });
    }

    /** Reports still collecting (e.g. the proxy restarted before their evidence was built). */
    public List<Report> collectingSync() throws SQLException {
        return database.sync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM bvc_reports WHERE status = 'COLLECTING'")) {
                return read(statement);
            }
        });
    }

    /** Handled reports created before {@code cutoff}; their evidence may be deleted. */
    public CompletableFuture<List<Long>> expired(long cutoff) {
        return database.async(connection -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM bvc_reports WHERE created_at < ? AND status IN ('ACCEPTED', 'REJECTED')")) {
                statement.setLong(1, cutoff);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            return ids;
        });
    }

    public CompletableFuture<Void> delete(long id) {
        return database.async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM bvc_reports WHERE id = ?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static List<Report> read(PreparedStatement statement) throws SQLException {
        List<Report> out = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.add(new Report(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), UUID.fromString(rs.getString(4)),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9), rs.getLong(10),
                        Status.valueOf(rs.getString(11)), rs.getString(12), rs.getLong(13), rs.getString(14)));
            }
        }
        return out;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
