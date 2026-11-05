package top.dext.bvc.proxy.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One JDBC connection used from a single dedicated thread, so callers never block network
 * threads and statements never race. Plenty for one proxy's mute and report traffic.
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final Connection connection;
    private final ExecutorService executor;

    private Database(Connection connection) {
        this.connection = connection;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BVC-Database");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Embedded H2 database in {@code directory}. */
    public static Database openH2(Path directory) throws SQLException {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new SQLException("Cannot create " + directory, e);
        }
        String url = "jdbc:h2:file:" + directory.resolve("bvc").toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        // Use the driver directly: DriverManager does not see drivers inside plugin class loaders.
        Connection connection = new org.h2.Driver().connect(url, new Properties());
        Database database = new Database(connection);
        database.sync(Database::migrate);
        return database;
    }

    /** In-memory H2, for tests. */
    public static Database openInMemory(String name) throws SQLException {
        Connection connection = new org.h2.Driver().connect("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", new Properties());
        Database database = new Database(connection);
        database.sync(Database::migrate);
        return database;
    }

    public <T> CompletableFuture<T> async(SqlFunction<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(work.apply(connection));
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    /** Runs on the database thread and waits; only for startup and tests. */
    public <T> T sync(SqlFunction<T> work) throws SQLException {
        try {
            return async(work).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted", e);
        } catch (TimeoutException e) {
            throw new SQLException("Database did not answer in time", e);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // shutting down anyway
        }
    }

    /** Schema versions in order; each entry runs once. Never edit a released entry, append a new one. */
    private static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS bvc_players (
                        uuid VARCHAR(36) PRIMARY KEY,
                        name VARCHAR(32) NOT NULL,
                        name_lower VARCHAR(32) NOT NULL,
                        last_seen BIGINT NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS bvc_players_name ON bvc_players(name_lower)",
                    """
                    CREATE TABLE IF NOT EXISTS bvc_mutes (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        target_uuid VARCHAR(36) NOT NULL,
                        target_name VARCHAR(32) NOT NULL,
                        scope VARCHAR(64) NOT NULL,
                        reason VARCHAR(512) NOT NULL,
                        operator VARCHAR(64) NOT NULL,
                        created_at BIGINT NOT NULL,
                        expires_at BIGINT NOT NULL,
                        active BOOLEAN NOT NULL,
                        removed_by VARCHAR(64),
                        removed_at BIGINT
                    )""",
                    "CREATE INDEX IF NOT EXISTS bvc_mutes_target ON bvc_mutes(target_uuid)",
                    "CREATE INDEX IF NOT EXISTS bvc_mutes_active ON bvc_mutes(active)",
                    """
                    CREATE TABLE IF NOT EXISTS bvc_settings (
                        k VARCHAR(64) PRIMARY KEY,
                        v VARCHAR(4096) NOT NULL
                    )"""
            ));

    private static Void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS bvc_schema (version INT NOT NULL)");
            int current = 0;
            try (ResultSet rs = statement.executeQuery("SELECT MAX(version) FROM bvc_schema")) {
                if (rs.next()) {
                    current = rs.getInt(1);
                }
            }
            for (int version = current; version < MIGRATIONS.size(); version++) {
                for (String sql : MIGRATIONS.get(version)) {
                    statement.execute(sql);
                }
                statement.execute("INSERT INTO bvc_schema (version) VALUES (" + (version + 1) + ")");
            }
        }
        return null;
    }
}
