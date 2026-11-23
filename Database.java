package net.iyha.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Database access. All work runs off the main thread.
 * {@code serial} is a single thread used for every write that must not race (money, escrow).
 * {@code io} is a small pool for reads.
 */
final class Database {

    enum Dialect { SQLITE, MARIADB }

    @FunctionalInterface
    interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final IyhaCore plugin;
    private final HikariDataSource dataSource;
    private final Dialect dialect;
    private final ExecutorService serial;
    private final ExecutorService io;

    Database(IyhaCore plugin) {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getConfig();
        String type = cfg.getString("database.type", "sqlite").toLowerCase(Locale.ROOT);
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("IYHACore");
        if (type.equals("mariadb") || type.equals("mysql")) {
            dialect = Dialect.MARIADB;
            hc.setDriverClassName("org.mariadb.jdbc.Driver");
            hc.setJdbcUrl("jdbc:mariadb://" + cfg.getString("database.host", "localhost") + ":"
                    + cfg.getInt("database.port", 3306) + "/" + cfg.getString("database.name", "iyha"));
            hc.setUsername(cfg.getString("database.user", "iyha"));
            hc.setPassword(cfg.getString("database.password", ""));
            hc.setMaximumPoolSize(Math.max(2, cfg.getInt("database.pool-size", 6)));
        } else {
            dialect = Dialect.SQLITE;
            plugin.getDataFolder().mkdirs();
            File file = new File(plugin.getDataFolder(), cfg.getString("database.sqlite-file", "iyha.db"));
            hc.setDriverClassName("org.sqlite.JDBC");
            hc.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath() + "?journal_mode=WAL&busy_timeout=5000");
            hc.setMaximumPoolSize(1);
        }
        hc.setConnectionTimeout(30_000);
        this.dataSource = new HikariDataSource(hc);
        this.serial = Executors.newSingleThreadExecutor(r -> daemon(r, "iyha-db-serial"));
        this.io = Executors.newFixedThreadPool(dialect == Dialect.SQLITE ? 1 : 4, r -> daemon(r, "iyha-db-io"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    Dialect dialect() { return dialect; }

    /** Prefix for an insert that does nothing if the row exists. Append "table(cols) VALUES (...)". */
    String insertIgnore() {
        return dialect == Dialect.SQLITE ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ";
    }

    /** Row lock suffix for SELECT. SQLite has no row locks (and does not need them here). */
    String forUpdate() {
        return dialect == Dialect.SQLITE ? "" : " FOR UPDATE";
    }

    String autoId() {
        return dialect == Dialect.SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT PRIMARY KEY AUTO_INCREMENT";
    }

    /** Column type for item bytes. */
    String blob() {
        return dialect == Dialect.SQLITE ? "BLOB" : "MEDIUMBLOB";
    }

    /**
     * Runs {@code body} in one transaction. Commits if {@code commitIf} accepts the result, otherwise rolls back.
     * Any exception rolls back and is rethrown.
     */
    static <T> T tx(Connection c, SqlFunction<T> body, java.util.function.Predicate<T> commitIf) throws SQLException {
        boolean previous = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T result = body.apply(c);
            if (commitIf.test(result)) {
                c.commit();
            } else {
                c.rollback();
            }
            return result;
        } catch (SQLException | RuntimeException ex) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // keep the original exception
            }
            throw ex;
        } finally {
            try {
                c.setAutoCommit(previous);
            } catch (SQLException ignored) {
                // connection returns to the pool anyway
            }
        }
    }

    <T> CompletableFuture<T> supplySerial(SqlFunction<T> work) {
        return supply(serial, work);
    }

    <T> CompletableFuture<T> supplyIo(SqlFunction<T> work) {
        return supply(io, work);
    }

    private <T> CompletableFuture<T> supply(ExecutorService executor, SqlFunction<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    future.complete(work.apply(connection));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RuntimeException ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }

    /** Applies any migrations that have not been applied yet. */
    void migrate() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT PRIMARY KEY, applied_at BIGINT NOT NULL)");
            int current = 0;
            try (ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
                if (rs.next()) {
                    current = rs.getInt(1);
                }
            }
            for (Migrations.Step step : Migrations.all(this)) {
                if (step.version() <= current) {
                    continue;
                }
                for (String sql : step.statements()) {
                    st.execute(sql);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO schema_version(version, applied_at) VALUES (?, ?)")) {
                    ps.setInt(1, step.version());
                    ps.setLong(2, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                plugin.getLogger().info("Applied database migration " + step.version());
            }
        }
    }

    void close() {
        serial.shutdown();
        io.shutdown();
        try {
            serial.awaitTermination(10, TimeUnit.SECONDS);
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }
}

/** Versioned schema. Never edit an applied step: add a new one. */
final class Migrations {
    record Step(int version, List<String> statements) {}

    private Migrations() {}

    static List<Step> all(Database db) {
        return List.of(v1(db), MigrationsV2.step(db));
    }

    private static Step v1(Database db) {
        String id = db.autoId();
        return new Step(1, List.of(
                "CREATE TABLE IF NOT EXISTS players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL, "
                        + "name_lower VARCHAR(32) NOT NULL, first_join BIGINT NOT NULL, last_seen BIGINT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_players_name ON players(name_lower)",
                "CREATE TABLE IF NOT EXISTS player_settings (uuid VARCHAR(36) PRIMARY KEY, "
                        + "pay_toggle INT NOT NULL DEFAULT 1, pm_toggle INT NOT NULL DEFAULT 1, "
                        + "tpa_mode VARCHAR(8) NOT NULL DEFAULT 'ASK', ah_confirm INT NOT NULL DEFAULT 1, "
                        + "scoreboard INT NOT NULL DEFAULT 1, sounds INT NOT NULL DEFAULT 1)",
                "CREATE TABLE IF NOT EXISTS player_stats (uuid VARCHAR(36) PRIMARY KEY, "
                        + "playtime_sec BIGINT NOT NULL DEFAULT 0, kills INT NOT NULL DEFAULT 0, "
                        + "deaths INT NOT NULL DEFAULT 0, killstreak INT NOT NULL DEFAULT 0, "
                        + "best_killstreak INT NOT NULL DEFAULT 0, mobs_killed BIGINT NOT NULL DEFAULT 0, "
                        + "blocks_placed BIGINT NOT NULL DEFAULT 0, blocks_broken BIGINT NOT NULL DEFAULT 0, "
                        + "updated_at BIGINT NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS accounts (owner VARCHAR(64) NOT NULL, currency VARCHAR(16) NOT NULL, "
                        + "balance BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (owner, currency))",
                "CREATE TABLE IF NOT EXISTS ledger_tx (tx_id " + id + ", idem_key VARCHAR(80) UNIQUE, "
                        + "kind VARCHAR(32) NOT NULL, ref VARCHAR(80), actor VARCHAR(64), created_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS ledger_entry (entry_id " + id + ", tx_id BIGINT NOT NULL, "
                        + "owner VARCHAR(64) NOT NULL, currency VARCHAR(16) NOT NULL, amount BIGINT NOT NULL, "
                        + "balance_after BIGINT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_ledger_owner ON ledger_entry(owner, currency, entry_id)",
                "CREATE INDEX IF NOT EXISTS idx_ledger_tx ON ledger_entry(tx_id)",
                "CREATE TABLE IF NOT EXISTS system_flags (flag VARCHAR(64) PRIMARY KEY, value VARCHAR(255) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS audit_log (id " + id + ", actor VARCHAR(64), action VARCHAR(64) NOT NULL, "
                        + "target VARCHAR(64), details TEXT, created_at BIGINT NOT NULL)"));
    }
}
