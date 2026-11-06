package top.dext.bvc.proxy.storage;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One JDBC connection used from a single dedicated thread, so callers never block network
 * threads and statements never race. Plenty for one proxy's mute and report traffic.
 * <p>
 * H2 is built in. MySQL / MariaDB use a JDBC driver jar the admin puts in the plugin's
 * {@code lib/} folder; nothing is downloaded or bundled. A connection that went stale (server
 * restart, {@code wait_timeout}) is replaced before the next statement.
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Opens a fresh connection; called again when the current one is no longer valid. */
    @FunctionalInterface
    private interface Connector {
        Connection connect() throws SQLException;
    }

    private final Connector connector;
    private final boolean mysql;
    private Connection connection;
    private final ExecutorService executor;

    private Database(Connector connector, boolean mysql) throws SQLException {
        this.connector = connector;
        this.mysql = mysql;
        this.connection = connector.connect();
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
        Database database = new Database(() -> new org.h2.Driver().connect(url, new Properties()), false);
        database.sync(database::migrate);
        return database;
    }

    /**
     * MySQL or MariaDB. {@code libDirectory} holds the JDBC driver jar (mysql-connector-j or
     * mariadb-java-client); a driver already on the classpath (e.g. BungeeCord's) works too.
     */
    public static Database openMySql(Path libDirectory, String host, int port, String database, String user, String password,
                                     String extraParameters) throws SQLException {
        Driver driver = findDriver(libDirectory);
        boolean maria = driver.getClass().getName().startsWith("org.mariadb");
        String url = "jdbc:" + (maria ? "mariadb" : "mysql") + "://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8" + (extraParameters.isEmpty() ? "" : "&" + extraParameters);
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        Database db = new Database(() -> {
            Connection connection = driver.connect(url, properties);
            if (connection == null) {
                throw new SQLException("The driver " + driver.getClass().getName() + " does not accept " + url);
            }
            return connection;
        }, true);
        db.sync(db::migrate);
        return db;
    }

    private static Driver findDriver(Path libDirectory) throws SQLException {
        List<URL> jars = new ArrayList<>();
        if (Files.isDirectory(libDirectory)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(libDirectory, "*.jar")) {
                for (Path jar : files) {
                    jars.add(jar.toUri().toURL());
                }
            } catch (IOException e) {
                throw new SQLException("Cannot read " + libDirectory, e);
            }
        }
        ClassLoader loader = jars.isEmpty() ? Database.class.getClassLoader()
                : new URLClassLoader(jars.toArray(new URL[0]), Database.class.getClassLoader());
        for (String name : List.of("org.mariadb.jdbc.Driver", "com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver")) {
            try {
                return (Driver) Class.forName(name, true, loader).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // try the next one
            }
        }
        for (Driver driver : ServiceLoader.load(Driver.class, loader)) {
            if (!driver.getClass().getName().startsWith("org.h2")) {
                return driver;
            }
        }
        throw new SQLException("No MySQL / MariaDB JDBC driver found. Put mysql-connector-j or mariadb-java-client"
                + " (.jar) into " + libDirectory.toAbsolutePath());
    }

    /** In-memory H2, for tests. */
    public static Database openInMemory(String name) throws SQLException {
        String url = "jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Database database = new Database(() -> new org.h2.Driver().connect(url, new Properties()), false);
        database.sync(database::migrate);
        return database;
    }

    public <T> CompletableFuture<T> async(SqlFunction<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(work.apply(validConnection()));
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    /** The open connection, replaced first if it went stale. Database thread only. */
    private Connection validConnection() throws SQLException {
        if (mysql && !connection.isValid(2)) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // already broken
            }
            connection = connector.connect();
        }
        return connection;
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
            ),
            // 2: recordings, reports, web panel.
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS bvc_utterances (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        segment VARCHAR(255) NOT NULL,
                        server VARCHAR(64) NOT NULL,
                        speaker_uuid VARCHAR(36) NOT NULL,
                        speaker_name VARCHAR(32) NOT NULL,
                        started_at BIGINT NOT NULL,
                        ended_at BIGINT NOT NULL,
                        frames INT NOT NULL,
                        local_id INT NOT NULL,
                        offset_from BIGINT NOT NULL,
                        offset_to BIGINT NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS bvc_utterances_time ON bvc_utterances(started_at)",
                    "CREATE INDEX IF NOT EXISTS bvc_utterances_speaker ON bvc_utterances(speaker_uuid, started_at)",
                    "CREATE INDEX IF NOT EXISTS bvc_utterances_segment ON bvc_utterances(segment)",
                    """
                    CREATE TABLE IF NOT EXISTS bvc_reports (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        reporter_uuid VARCHAR(36) NOT NULL,
                        reporter_name VARCHAR(32) NOT NULL,
                        target_uuid VARCHAR(36) NOT NULL,
                        target_name VARCHAR(32) NOT NULL,
                        server VARCHAR(64) NOT NULL,
                        reason VARCHAR(512) NOT NULL,
                        created_at BIGINT NOT NULL,
                        window_from BIGINT NOT NULL,
                        window_to BIGINT NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        handled_by VARCHAR(64),
                        handled_at BIGINT,
                        note VARCHAR(1024)
                    )""",
                    "CREATE INDEX IF NOT EXISTS bvc_reports_status ON bvc_reports(status, created_at)",
                    """
                    CREATE TABLE IF NOT EXISTS bvc_web_users (
                        username VARCHAR(32) PRIMARY KEY,
                        uuid VARCHAR(36),
                        password_hash VARCHAR(256),
                        permissions VARCHAR(256) NOT NULL,
                        created_at BIGINT NOT NULL
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS bvc_audit (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        at BIGINT NOT NULL,
                        username VARCHAR(32) NOT NULL,
                        action VARCHAR(64) NOT NULL,
                        detail VARCHAR(1024) NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS bvc_audit_at ON bvc_audit(at)"
            ),
            // 3: panel sessions survive a proxy restart (only a SHA-256 of the cookie is stored).
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS bvc_web_sessions (
                        id_hash VARCHAR(64) PRIMARY KEY,
                        username VARCHAR(32) NOT NULL,
                        expires_at BIGINT NOT NULL
                    )"""
            ));

    private Void migrate(Connection connection) throws SQLException {
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
                    // MySQL has no CREATE INDEX IF NOT EXISTS; each migration runs only once anyway.
                    statement.execute(mysql ? sql.replace("CREATE INDEX IF NOT EXISTS", "CREATE INDEX") : sql);
                }
                statement.execute("INSERT INTO bvc_schema (version) VALUES (" + (version + 1) + ")");
            }
        }
        return null;
    }
}
