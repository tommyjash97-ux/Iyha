package net.iyha.core;

import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The single money authority for IYHA SMP.
 * Every change is a double-entry ledger transaction: the entries of one transaction always sum to zero
 * (money created or destroyed is booked against the MINT and SINK system accounts).
 */
final class EconomyService {

    static final String CURRENCY = "MONEY";
    static final String MINT = "system:mint";
    static final String SINK = "system:sink";
    static final String FEES = "system:fees";
    static final String ESCROW = "system:escrow";

    enum Status { OK, INSUFFICIENT, FROZEN, INVALID, CAP, DUPLICATE, ERROR }

    record TxResult(Status status, long fromBalance, long toBalance, long txId) {
        static TxResult of(Status status) {
            return new TxResult(status, 0, 0, 0);
        }
    }

    record HistoryEntry(long amount, long balanceAfter, String kind, String ref, long createdAt) {}

    record TopEntry(String uuid, String name, long balance) {}

    record AuditReport(long unbalancedTx, long mismatchedAccounts, long negativeAccounts) {
        boolean clean() {
            return unbalancedTx == 0 && mismatchedAccounts == 0 && negativeAccounts == 0;
        }
    }

    private final IyhaCore plugin;
    private final Database db;
    private final Map<String, Long> cache = new ConcurrentHashMap<>();

    private volatile boolean frozen;
    private volatile long maxBalance;
    private volatile long startingBalance;
    private volatile long minPay;
    private volatile long confirmThreshold;
    private volatile long auditThreshold;
    private volatile String symbol = "$";

    private volatile List<TopEntry> topCache = List.of();
    private volatile long topCachedAt = 0L;

    EconomyService(IyhaCore plugin) {
        this.plugin = plugin;
        this.db = plugin.database();
        reloadSettings();
        try {
            frozen = db.supplyIo(c -> readFlag(c, "economy_frozen")).join();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not read economy flags. Economy starts FROZEN.", ex);
            frozen = true;
        }
    }

    void reloadSettings() {
        FileConfiguration cfg = plugin.getConfig();
        symbol = cfg.getString("economy.symbol", "$");
        startingBalance = Money.fromDouble(cfg.getDouble("economy.starting-balance", 0));
        maxBalance = Money.fromDouble(cfg.getDouble("economy.max-balance", 1.0E12));
        minPay = Math.max(1, Money.fromDouble(cfg.getDouble("economy.min-pay", 1)));
        confirmThreshold = Money.fromDouble(cfg.getDouble("economy.pay-confirm-threshold", 1_000_000));
        auditThreshold = Money.fromDouble(cfg.getDouble("economy.audit-threshold", 100_000));
    }

    // ---------------------------------------------------------------- settings access

    long minPay() { return minPay; }
    long confirmThreshold() { return confirmThreshold; }
    long auditThreshold() { return auditThreshold; }
    boolean isFrozen() { return frozen; }

    static boolean isSystem(String owner) {
        return owner.startsWith("system:");
    }

    String format(long cents) {
        return cents < 0 ? "-" + symbol + Money.plain(-cents) : symbol + Money.plain(cents);
    }

    // ---------------------------------------------------------------- balances

    CompletableFuture<Long> balance(UUID id) {
        Long cached = cache.get(id.toString());
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return db.supplyIo(c -> readBalance(c, id.toString(), false));
    }

    /** Balance of an online player, from memory. Returns 0 if not loaded. */
    long cachedBalance(UUID id) {
        return cache.getOrDefault(id.toString(), 0L);
    }

    void evict(UUID id) {
        cache.remove(id.toString());
    }

    /** Creates the account if needed, grants the starting balance once, and caches the balance. */
    CompletableFuture<Void> loadAccount(UUID id) {
        String owner = id.toString();
        return db.supplySerial(c -> {
            ensureAccount(c, owner);
            if (startingBalance > 0) {
                doTransfer(c, MINT, owner, startingBalance, "START", null, "system", "START:" + owner, false);
            }
            cache.put(owner, readBalance(c, owner, false));
            return null;
        });
    }

    // ---------------------------------------------------------------- transfers

    CompletableFuture<TxResult> transfer(String from, String to, long amount, String kind, String ref,
                                         String actor, String idempotencyKey) {
        boolean bypassFreeze = kind.startsWith("ADMIN");
        return db.supplySerial(c -> doTransfer(c, from, to, amount, kind, ref, actor, idempotencyKey, bypassFreeze))
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.SEVERE, "Transfer failed (" + kind + ")", ex);
                    return TxResult.of(Status.ERROR);
                });
    }

    CompletableFuture<TxResult> mint(UUID to, long amount, String kind, String ref, String actor) {
        return transfer(MINT, to.toString(), amount, kind, ref, actor, null);
    }

    CompletableFuture<TxResult> sink(UUID from, long amount, String kind, String ref, String actor) {
        return transfer(from.toString(), SINK, amount, kind, ref, actor, null);
    }

    /** Admin: set a balance by minting or sinking the difference, so the ledger stays balanced. */
    CompletableFuture<TxResult> adminSet(UUID target, long newBalance, String actor) {
        String owner = target.toString();
        return db.supplySerial(c -> {
            ensureAccount(c, owner);
            long current = readBalance(c, owner, true);
            if (newBalance == current) {
                return new TxResult(Status.OK, current, current, 0);
            }
            if (newBalance > current) {
                return doTransfer(c, MINT, owner, newBalance - current, "ADMIN_SET", null, actor, null, true);
            }
            return doTransfer(c, owner, SINK, current - newBalance, "ADMIN_SET", null, actor, null, true);
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "adminSet failed", ex);
            return TxResult.of(Status.ERROR);
        });
    }

    private TxResult doTransfer(Connection c, String from, String to, long amount, String kind, String ref,
                                String actor, String idempotencyKey, boolean bypassFreeze) throws SQLException {
        if (amount <= 0 || from.equals(to)) {
            return TxResult.of(Status.INVALID);
        }
        if (frozen && !bypassFreeze) {
            return TxResult.of(Status.FROZEN);
        }
        boolean previousAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            if (idempotencyKey != null && txExists(c, idempotencyKey)) {
                c.rollback();
                return TxResult.of(Status.DUPLICATE);
            }
            ensureAccount(c, from);
            ensureAccount(c, to);
            long fromBalance = readBalance(c, from, true);
            long toBalance = readBalance(c, to, true);
            if (!isSystem(from) && fromBalance < amount) {
                c.rollback();
                return new TxResult(Status.INSUFFICIENT, fromBalance, toBalance, 0);
            }
            long newFrom = Math.subtractExact(fromBalance, amount);
            long newTo = Math.addExact(toBalance, amount);
            if (!isSystem(to) && newTo > maxBalance) {
                c.rollback();
                return new TxResult(Status.CAP, fromBalance, toBalance, 0);
            }
            long now = System.currentTimeMillis();
            setBalance(c, from, newFrom, now);
            setBalance(c, to, newTo, now);
            long txId = insertTx(c, kind, ref, actor, idempotencyKey, now);
            insertEntry(c, txId, from, -amount, newFrom);
            insertEntry(c, txId, to, amount, newTo);
            c.commit();
            if (!isSystem(from)) {
                cache.computeIfPresent(from, (k, v) -> newFrom);
            }
            if (!isSystem(to)) {
                cache.computeIfPresent(to, (k, v) -> newTo);
            }
            return new TxResult(Status.OK, newFrom, newTo, txId);
        } catch (SQLException | RuntimeException ex) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // original exception is more important
            }
            throw ex;
        } finally {
            try {
                c.setAutoCommit(previousAutoCommit);
            } catch (SQLException ignored) {
                // connection is returned to the pool anyway
            }
        }
    }

    // ---------------------------------------------------------------- multi-party ledger (caller owns the transaction)

    record Posting(String owner, long balance) {}

    record Applied(Status status, long fromBalance, long txId, List<Posting> postings) {
        static Applied fail(Status status, long fromBalance) {
            return new Applied(status, fromBalance, 0, List.of());
        }
    }

    /**
     * Books one ledger transaction: {@code from} pays every account in {@code credits}.
     * Does NOT commit or roll back: the caller wraps it in Database.tx(...) together with its own writes,
     * and calls {@link #publish} with the postings after a successful commit.
     */
    Applied applyLedger(Connection c, String from, Map<String, Long> credits, String kind, String ref,
                        String actor, String idem, boolean bypassFreeze) throws SQLException {
        long total = 0;
        for (long v : credits.values()) {
            if (v <= 0) {
                return Applied.fail(Status.INVALID, 0);
            }
            total = Math.addExact(total, v);
        }
        if (total <= 0 || credits.containsKey(from)) {
            return Applied.fail(Status.INVALID, 0);
        }
        if (frozen && !bypassFreeze) {
            return Applied.fail(Status.FROZEN, 0);
        }
        if (idem != null && txExists(c, idem)) {
            return Applied.fail(Status.DUPLICATE, 0);
        }
        ensureAccount(c, from);
        for (String to : credits.keySet()) {
            ensureAccount(c, to);
        }
        long fromBal = readBalance(c, from, true);
        if (!isSystem(from) && fromBal < total) {
            return Applied.fail(Status.INSUFFICIENT, fromBal);
        }
        Map<String, Long> newBalances = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Long> e : credits.entrySet()) {
            long nb = Math.addExact(readBalance(c, e.getKey(), true), e.getValue());
            if (!isSystem(e.getKey()) && nb > maxBalance) {
                return Applied.fail(Status.CAP, fromBal);
            }
            newBalances.put(e.getKey(), nb);
        }
        long now = System.currentTimeMillis();
        long newFrom = Math.subtractExact(fromBal, total);
        List<Posting> postings = new ArrayList<>();
        setBalance(c, from, newFrom, now);
        postings.add(new Posting(from, newFrom));
        for (Map.Entry<String, Long> e : newBalances.entrySet()) {
            setBalance(c, e.getKey(), e.getValue(), now);
            postings.add(new Posting(e.getKey(), e.getValue()));
        }
        long txId = insertTx(c, kind, ref, actor, idem, now);
        insertEntry(c, txId, from, -total, newFrom);
        for (Map.Entry<String, Long> e : credits.entrySet()) {
            insertEntry(c, txId, e.getKey(), e.getValue(), newBalances.get(e.getKey()));
        }
        return new Applied(Status.OK, newFrom, txId, postings);
    }

    /** Call after the surrounding transaction committed, so online balances stay in sync. */
    void publish(List<Posting> postings) {
        for (Posting p : postings) {
            if (!isSystem(p.owner())) {
                cache.computeIfPresent(p.owner(), (k, v) -> p.balance());
            }
        }
    }

    /** Blocking balance lookup for callers that cannot be asynchronous (Vault). */
    long balanceBlocking(UUID id) {
        try {
            return balance(id).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ex) {
            return 0L;
        }
    }

    // ---------------------------------------------------------------- freeze (kill switch)

    CompletableFuture<Void> setFrozen(boolean value) {
        frozen = value;
        return db.supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("REPLACE INTO system_flags(flag, value) VALUES (?, ?)")) {
                ps.setString(1, "economy_frozen");
                ps.setString(2, value ? "1" : "0");
                ps.executeUpdate();
            }
            return null;
        });
    }

    private boolean readFlag(Connection c, String flag) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM system_flags WHERE flag = ?")) {
            ps.setString(1, flag);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "1".equals(rs.getString(1));
            }
        }
    }

    // ---------------------------------------------------------------- audit

    CompletableFuture<AuditReport> audit() {
        return db.supplyIo(c -> {
            long unbalanced = scalar(c, "SELECT COUNT(*) FROM (SELECT tx_id FROM ledger_entry "
                    + "GROUP BY tx_id HAVING SUM(amount) <> 0) t");
            long mismatched = scalar(c, "SELECT COUNT(*) FROM (SELECT a.owner FROM accounts a "
                    + "LEFT JOIN ledger_entry e ON e.owner = a.owner AND e.currency = a.currency "
                    + "GROUP BY a.owner, a.currency, a.balance "
                    + "HAVING a.balance <> COALESCE(SUM(e.amount), 0)) t");
            long negative = scalar(c, "SELECT COUNT(*) FROM accounts WHERE balance < 0 AND owner NOT LIKE 'system:%'");
            return new AuditReport(unbalanced, mismatched, negative);
        });
    }

    /** Runs on startup. If the ledger is inconsistent the economy freezes itself and tells staff. */
    void startupAudit() {
        audit().thenAccept(report -> {
            if (report.clean()) {
                plugin.getLogger().info("Economy ledger audit passed.");
            } else {
                plugin.getLogger().severe("Economy ledger audit FAILED: " + report + ". Freezing economy.");
                setFrozen(true);
                plugin.audit().log("system", "ECONOMY_AUTO_FREEZE", null, report.toString());
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Economy audit could not run", ex);
            return null;
        });
    }

    // ---------------------------------------------------------------- history and leaderboard

    CompletableFuture<List<HistoryEntry>> history(UUID id, int limit) {
        return db.supplyIo(c -> {
            List<HistoryEntry> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT e.amount, e.balance_after, t.kind, t.ref, t.created_at FROM ledger_entry e "
                            + "JOIN ledger_tx t ON t.tx_id = e.tx_id WHERE e.owner = ? AND e.currency = ? "
                            + "ORDER BY e.entry_id DESC LIMIT ?")) {
                ps.setString(1, id.toString());
                ps.setString(2, CURRENCY);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new HistoryEntry(rs.getLong(1), rs.getLong(2), rs.getString(3),
                                rs.getString(4), rs.getLong(5)));
                    }
                }
            }
            return list;
        });
    }

    /** Top balances, cached for 60 seconds so the database is not hit on every /baltop. */
    CompletableFuture<List<TopEntry>> top(int limit) {
        long now = System.currentTimeMillis();
        if (now - topCachedAt < 60_000L && !topCache.isEmpty()) {
            return CompletableFuture.completedFuture(topCache);
        }
        return db.supplyIo(c -> {
            List<TopEntry> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT a.owner, a.balance, p.name FROM accounts a LEFT JOIN players p ON p.uuid = a.owner "
                            + "WHERE a.currency = ? AND a.owner NOT LIKE 'system:%' "
                            + "ORDER BY a.balance DESC LIMIT ?")) {
                ps.setString(1, CURRENCY);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(3);
                        list.add(new TopEntry(rs.getString(1), name == null ? "Unknown" : name, rs.getLong(2)));
                    }
                }
            }
            topCache = list;
            topCachedAt = System.currentTimeMillis();
            return list;
        });
    }

    // ---------------------------------------------------------------- SQL helpers

    private long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void ensureAccount(Connection c, String owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.insertIgnore()
                + "accounts(owner, currency, balance, updated_at) VALUES (?, ?, 0, ?)")) {
            ps.setString(1, owner);
            ps.setString(2, CURRENCY);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private long readBalance(Connection c, String owner, boolean lock) throws SQLException {
        String sql = "SELECT balance FROM accounts WHERE owner = ? AND currency = ?" + (lock ? db.forUpdate() : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private void setBalance(Connection c, String owner, long balance, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE accounts SET balance = ?, updated_at = ? WHERE owner = ? AND currency = ?")) {
            ps.setLong(1, balance);
            ps.setLong(2, now);
            ps.setString(3, owner);
            ps.setString(4, CURRENCY);
            ps.executeUpdate();
        }
    }

    private boolean txExists(Connection c, String idempotencyKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM ledger_tx WHERE idem_key = ?")) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private long insertTx(Connection c, String kind, String ref, String actor, String idempotencyKey, long now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_tx(idem_key, kind, ref, actor, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, idempotencyKey);
            ps.setString(2, kind);
            ps.setString(3, ref);
            ps.setString(4, actor);
            ps.setLong(5, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No transaction id returned");
                }
                return keys.getLong(1);
            }
        }
    }

    private void insertEntry(Connection c, long txId, String owner, long amount, long balanceAfter)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_entry(tx_id, owner, currency, amount, balance_after) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, txId);
            ps.setString(2, owner);
            ps.setString(3, CURRENCY);
            ps.setLong(4, amount);
            ps.setLong(5, balanceAfter);
            ps.executeUpdate();
        }
    }
}
