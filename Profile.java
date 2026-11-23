package net.iyha.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Per-player toggles stored in player_settings. */
final class SettingsService implements Listener {
    static final class Settings {
        volatile boolean pm = true;
        volatile String tpaMode = "ASK";
        volatile boolean ahConfirm = true;
        volatile boolean scoreboard = true;
        volatile boolean sounds = true;
    }

    private final IyhaCore plugin;
    private final Map<UUID, Settings> cache = new ConcurrentHashMap<>();

    SettingsService(IyhaCore plugin) {
        this.plugin = plugin;
    }

    private Settings of(UUID id) {
        return cache.computeIfAbsent(id, k -> new Settings());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        plugin.database().supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT pm_toggle, tpa_mode, ah_confirm, scoreboard, sounds FROM player_settings WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    Settings s = new Settings();
                    if (rs.next()) {
                        s.pm = rs.getInt(1) != 0;
                        s.tpaMode = rs.getString(2);
                        s.ahConfirm = rs.getInt(3) != 0;
                        s.scoreboard = rs.getInt(4) != 0;
                        s.sounds = rs.getInt(5) != 0;
                    }
                    return s;
                }
            }
        }).thenAccept(s -> cache.put(id, s)).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Could not load settings", ex);
            return null;
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cache.remove(event.getPlayer().getUniqueId());
    }

    boolean pmEnabled(UUID id) { return of(id).pm; }
    String tpaMode(UUID id) { return of(id).tpaMode; }
    boolean ahConfirmOff(Player p) { return !of(p.getUniqueId()).ahConfirm && p.hasPermission("iyha.ah.skipconfirm"); }
    boolean ahConfirm(UUID id) { return of(id).ahConfirm; }
    boolean scoreboard(UUID id) { return of(id).scoreboard; }
    boolean sounds(UUID id) { return of(id).sounds; }

    String cycleTpaMode(UUID id) {
        Settings s = of(id);
        s.tpaMode = switch (s.tpaMode) {
            case "ASK" -> "DENY";
            case "DENY" -> "ACCEPT";
            default -> "ASK";
        };
        save(id, "tpa_mode", s.tpaMode);
        return s.tpaMode;
    }

    boolean toggle(UUID id, String field) {
        Settings s = of(id);
        boolean now;
        switch (field) {
            case "pm_toggle" -> now = s.pm = !s.pm;
            case "ah_confirm" -> now = s.ahConfirm = !s.ahConfirm;
            case "scoreboard" -> now = s.scoreboard = !s.scoreboard;
            default -> now = s.sounds = !s.sounds;
        }
        save(id, field, now ? 1 : 0);
        return now;
    }

    /** {@code column} always comes from the fixed set above, never from user input. */
    private void save(UUID id, String column, Object value) {
        plugin.database().supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE player_settings SET " + column + " = ? WHERE uuid = ?")) {
                ps.setObject(1, value);
                ps.setString(2, id.toString());
                ps.executeUpdate();
            }
            return (Void) null;
        });
    }
}

/** Kills, deaths, playtime and block counters. Held in memory and flushed every 30 seconds and on quit. */
final class StatsService implements Listener {
    static final class Stats {
        volatile long playtime;
        volatile int kills;
        volatile int deaths;
        volatile int streak;
        volatile int best;
        volatile long mobs;
        volatile long placed;
        volatile long broken;
        volatile long lastMove = System.currentTimeMillis();
        volatile boolean dirty;
    }

    record Snapshot(String name, long playtime, int kills, int deaths, int streak, int best, long mobs, long placed, long broken) {
        double kdr() {
            return deaths == 0 ? kills : Math.round(kills * 100.0 / deaths) / 100.0;
        }
    }

    private final IyhaCore plugin;
    private final Map<UUID, Stats> online = new ConcurrentHashMap<>();

    StatsService(IyhaCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::playtimeTick, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll, 600L, 600L);
    }

    Stats of(UUID id) {
        return online.get(id);
    }

    long playtimeSeconds(UUID id) {
        Stats s = online.get(id);
        return s == null ? 0 : s.playtime;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        plugin.database().supplySerial(c -> {
            Stats s = new Stats();
            try (PreparedStatement ps = c.prepareStatement("SELECT playtime_sec, kills, deaths, killstreak, best_killstreak, "
                    + "mobs_killed, blocks_placed, blocks_broken FROM player_stats WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        s.playtime = rs.getLong(1);
                        s.kills = rs.getInt(2);
                        s.deaths = rs.getInt(3);
                        s.streak = rs.getInt(4);
                        s.best = rs.getInt(5);
                        s.mobs = rs.getLong(6);
                        s.placed = rs.getLong(7);
                        s.broken = rs.getLong(8);
                    }
                }
            }
            return s;
        }).thenAccept(s -> online.put(id, s)).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Could not load stats", ex);
            return null;
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Stats s = online.remove(id);
        if (s != null) {
            flush(id, s);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock()) {
            Stats s = online.get(event.getPlayer().getUniqueId());
            if (s != null) {
                s.lastMove = System.currentTimeMillis();
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Stats s = online.get(event.getPlayer().getUniqueId());
        if (s != null) {
            s.placed++;
            s.dirty = true;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Stats s = online.get(event.getPlayer().getUniqueId());
        if (s != null) {
            s.broken++;
            s.dirty = true;
        }
    }

    @EventHandler
    public void onMobKill(EntityDeathEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof Player) && event.getEntity().getKiller() != null) {
            Stats s = online.get(event.getEntity().getKiller().getUniqueId());
            if (s != null) {
                s.mobs++;
                s.dirty = true;
            }
        }
    }

    void addKill(Player killer) {
        Stats s = online.get(killer.getUniqueId());
        if (s != null) {
            s.kills++;
            s.streak++;
            s.best = Math.max(s.best, s.streak);
            s.dirty = true;
        }
    }

    void addDeath(Player victim) {
        Stats s = online.get(victim.getUniqueId());
        if (s != null) {
            s.deaths++;
            s.streak = 0;
            s.dirty = true;
        }
    }

    /** Every minute: players who moved in the last few minutes earn playtime (AFK time is not counted). */
    private void playtimeTick() {
        long afkMs = plugin.getConfig().getLong("stats.afk-seconds", 300) * 1000L;
        long now = System.currentTimeMillis();
        for (Stats s : online.values()) {
            if (now - s.lastMove < afkMs) {
                s.playtime += 60;
                s.dirty = true;
            }
        }
    }

    void flushAll() {
        for (Map.Entry<UUID, Stats> e : online.entrySet()) {
            if (e.getValue().dirty) {
                flush(e.getKey(), e.getValue());
            }
        }
    }

    private void flush(UUID id, Stats s) {
        s.dirty = false;
        long playtime = s.playtime;
        int kills = s.kills;
        int deaths = s.deaths;
        int streak = s.streak;
        int best = s.best;
        long mobs = s.mobs;
        long placed = s.placed;
        long broken = s.broken;
        plugin.database().supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE player_stats SET playtime_sec = ?, kills = ?, deaths = ?, "
                    + "killstreak = ?, best_killstreak = ?, mobs_killed = ?, blocks_placed = ?, blocks_broken = ?, "
                    + "updated_at = ? WHERE uuid = ?")) {
                ps.setLong(1, playtime);
                ps.setInt(2, kills);
                ps.setInt(3, deaths);
                ps.setInt(4, streak);
                ps.setInt(5, best);
                ps.setLong(6, mobs);
                ps.setLong(7, placed);
                ps.setLong(8, broken);
                ps.setLong(9, System.currentTimeMillis());
                ps.setString(10, id.toString());
                ps.executeUpdate();
            }
            return (Void) null;
        }).exceptionally(ex -> {
            s.dirty = true;
            plugin.getLogger().log(Level.WARNING, "Could not save stats", ex);
            return null;
        });
    }

    /** Online players come from memory, everyone else from the database. */
    CompletableFuture<Snapshot> snapshot(UUID id, String name) {
        Stats s = online.get(id);
        if (s != null) {
            return CompletableFuture.completedFuture(new Snapshot(name, s.playtime, s.kills, s.deaths, s.streak, s.best,
                    s.mobs, s.placed, s.broken));
        }
        return plugin.database().supplyIo(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT playtime_sec, kills, deaths, killstreak, best_killstreak, "
                    + "mobs_killed, blocks_placed, blocks_broken FROM player_stats WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Snapshot(name, rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                                rs.getLong(6), rs.getLong(7), rs.getLong(8));
                    }
                    return new Snapshot(name, 0, 0, 0, 0, 0, 0, 0, 0);
                }
            }
        });
    }

    static String duration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h + "h " + m + "m";
    }
}

/** Top-10 tables, cached for 60 seconds. Money comes from the economy; the rest from player_stats. */
final class LeaderboardService {
    enum Type {
        MONEY("money", null, "Richest"),
        KILLS("kills", "kills", "Most kills"),
        DEATHS("deaths", "deaths", "Most deaths"),
        KILLSTREAK("killstreak", "best_killstreak", "Best killstreak"),
        PLAYTIME("playtime", "playtime_sec", "Most playtime"),
        PLACED("blocks_placed", "blocks_placed", "Blocks placed"),
        BROKEN("blocks_broken", "blocks_broken", "Blocks broken"),
        MOBS("mobs_killed", "mobs_killed", "Mobs killed");

        final String key;
        final String column;
        final String label;

        Type(String key, String column, String label) {
            this.key = key;
            this.column = column;
            this.label = label;
        }

        static Type of(String key) {
            for (Type t : values()) {
                if (t.key.equalsIgnoreCase(key)) {
                    return t;
                }
            }
            return null;
        }
    }

    record Row(UUID uuid, String name, long value) {}

    private final IyhaCore plugin;
    private final Map<Type, List<Row>> snapshot = new ConcurrentHashMap<>();
    private final Map<Type, Long> fetchedAt = new ConcurrentHashMap<>();

    LeaderboardService(IyhaCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (Type t : Type.values()) {
                fetch(t, true);
            }
        }, 200L, 1200L);
    }

    /** Last cached rows for placeholders. Never blocks. */
    List<Row> cached(Type type) {
        return snapshot.getOrDefault(type, List.of());
    }

    CompletableFuture<List<Row>> top(Type type) {
        Long at = fetchedAt.get(type);
        if (at != null && System.currentTimeMillis() - at < 60_000L) {
            return CompletableFuture.completedFuture(cached(type));
        }
        return fetch(type, false);
    }

    String format(Type type, long value) {
        return switch (type) {
            case MONEY -> plugin.economy().format(value);
            case PLAYTIME -> StatsService.duration(value);
            default -> String.valueOf(value);
        };
    }

    private CompletableFuture<List<Row>> fetch(Type type, boolean force) {
        CompletableFuture<List<Row>> future;
        if (type == Type.MONEY) {
            future = plugin.economy().top(10).thenApply(list -> {
                List<Row> rows = new ArrayList<>();
                for (EconomyService.TopEntry e : list) {
                    rows.add(new Row(UUID.fromString(e.uuid()), e.name(), e.balance()));
                }
                return rows;
            });
        } else {
            future = plugin.database().supplyIo(c -> {
                List<Row> rows = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT s.uuid, p.name, s." + type.column + " FROM player_stats s "
                        + "JOIN players p ON p.uuid = s.uuid ORDER BY s." + type.column + " DESC LIMIT 10")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                        }
                    }
                }
                return rows;
            });
        }
        return future.thenApply(rows -> {
            snapshot.put(type, rows);
            fetchedAt.put(type, System.currentTimeMillis());
            return rows;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Leaderboard fetch failed", ex);
            return cached(type);
        });
    }
}

/** Top-10 menu with one tab per ranking. */
final class LeaderboardMenu extends Menu {
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23};
    private final Player player;
    private LeaderboardService.Type type;

    LeaderboardMenu(IyhaCore plugin, Player player, LeaderboardService.Type type) {
        super(plugin);
        this.player = player;
        this.type = type;
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>Leaderboard: " + type.label);
    }

    @Override
    protected int size() {
        return 54;
    }

    static void open(IyhaCore plugin, Player player, LeaderboardService.Type type) {
        plugin.leaderboards().top(type).thenAccept(rows -> plugin.sync(() -> new LeaderboardMenu(plugin, player, type).open(player)));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Ui.filler());
        }
        List<LeaderboardService.Row> rows = plugin.leaderboards().cached(type);
        for (int i = 0; i < SLOTS.length; i++) {
            if (i >= rows.size()) {
                break;
            }
            LeaderboardService.Row r = rows.get(i);
            ItemStack head = Ui.button(Material.PLAYER_HEAD, "<yellow>#" + (i + 1) + " <white>" + r.name(),
                    "<gray>" + type.label + ": <green>" + plugin.leaderboards().format(type, r.value()));
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(r.uuid())));
            set(SLOTS[i], head);
        }
        LeaderboardService.Type[] types = LeaderboardService.Type.values();
        for (int i = 0; i < types.length; i++) {
            set(45 + i, Ui.button(types[i] == type ? Material.LIME_DYE : Material.GRAY_DYE, "<aqua>" + types[i].label,
                    "<yellow>Click to view"));
        }
        set(53, Ui.button(Material.BARRIER, "<red>Close"));
    }

    @Override
    void onClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        LeaderboardService.Type[] types = LeaderboardService.Type.values();
        if (raw >= 45 && raw < 45 + types.length) {
            open(plugin, player, types[raw - 45]);
        } else if (raw == 53) {
            player.closeInventory();
        }
    }
}

/** Profile card. */
final class ProfileMenu extends Menu {
    private final String name;
    private final UUID target;
    private final StatsService.Snapshot stats;
    private final long balance;
    private final String rank;

    private ProfileMenu(IyhaCore plugin, String name, UUID target, StatsService.Snapshot stats, long balance, String rank) {
        super(plugin);
        this.name = name;
        this.target = target;
        this.stats = stats;
        this.balance = balance;
        this.rank = rank;
    }

    static void open(IyhaCore plugin, Player viewer, UUID id, String name) {
        plugin.stats().snapshot(id, name).thenCompose(stats -> plugin.economy().balance(id).thenAccept(bal -> plugin.sync(() -> {
            Player online = Bukkit.getPlayer(id);
            String rank = online == null ? "-" : plugin.ranks().rankOf(online).name();
            new ProfileMenu(plugin, name, id, stats, bal, rank).open(viewer);
        }))).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Profile failed", ex);
            return null;
        });
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>Profile: " + name);
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, Ui.filler());
        }
        ItemStack head = Ui.button(Material.PLAYER_HEAD, "<white><bold>" + name, "<gray>Rank: <white>" + rank,
                "<gray>Balance: <green>" + plugin.economy().format(balance));
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(target)));
        set(4, head);
        set(10, Ui.button(Material.DIAMOND_SWORD, "<red>Kills: <white>" + stats.kills()));
        set(11, Ui.button(Material.SKELETON_SKULL, "<red>Deaths: <white>" + stats.deaths()));
        set(12, Ui.button(Material.GOLDEN_SWORD, "<gold>K/D: <white>" + stats.kdr()));
        set(13, Ui.button(Material.BLAZE_POWDER, "<gold>Killstreak: <white>" + stats.streak(), "<gray>Best: " + stats.best()));
        set(14, Ui.button(Material.CLOCK, "<aqua>Playtime: <white>" + StatsService.duration(stats.playtime())));
        set(15, Ui.button(Material.ZOMBIE_HEAD, "<green>Mobs killed: <white>" + stats.mobs()));
        set(16, Ui.button(Material.STONE, "<gray>Blocks: <white>" + stats.placed() + " placed, " + stats.broken() + " broken"));
        set(22, Ui.button(Material.BARRIER, "<red>Close"));
    }

    @Override
    void onClick(InventoryClickEvent event) {
        if (event.getRawSlot() == 22) {
            event.getWhoClicked().closeInventory();
        }
    }
}

/** Settings toggles. */
final class SettingsMenu extends Menu {
    private final Player player;

    SettingsMenu(IyhaCore plugin, Player player) {
        super(plugin);
        this.player = player;
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>Settings");
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void build() {
        render();
    }

    private String state(boolean on) {
        return on ? "<green>ON" : "<red>OFF";
    }

    private void render() {
        Inventory inv = getInventory();
        UUID id = player.getUniqueId();
        SettingsService s = plugin.settings();
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, Ui.filler());
        }
        inv.setItem(10, Ui.button(Material.GOLD_INGOT, "<yellow>Receive payments: " + state(plugin.players().isPayEnabled(id))));
        inv.setItem(11, Ui.button(Material.WRITABLE_BOOK, "<yellow>Private messages: " + state(s.pmEnabled(id))));
        inv.setItem(12, Ui.button(Material.ENDER_PEARL, "<yellow>Teleport requests: <white>" + s.tpaMode(id).toLowerCase(Locale.ROOT),
                "<gray>ask, deny or accept automatically"));
        inv.setItem(13, Ui.button(Material.CHEST, "<yellow>Auction confirmation: " + state(s.ahConfirm(id)),
                "<gray>Turning it off needs the skipconfirm permission"));
        inv.setItem(14, Ui.button(Material.PAINTING, "<yellow>Scoreboard: " + state(s.scoreboard(id))));
        inv.setItem(15, Ui.button(Material.NOTE_BLOCK, "<yellow>Sounds: " + state(s.sounds(id))));
        inv.setItem(22, Ui.button(Material.BARRIER, "<red>Close"));
    }

    @Override
    void onClick(InventoryClickEvent event) {
        UUID id = player.getUniqueId();
        switch (event.getRawSlot()) {
            case 10 -> plugin.players().setPayEnabled(id, !plugin.players().isPayEnabled(id));
            case 11 -> plugin.settings().toggle(id, "pm_toggle");
            case 12 -> plugin.settings().cycleTpaMode(id);
            case 13 -> plugin.settings().toggle(id, "ah_confirm");
            case 14 -> plugin.settings().toggle(id, "scoreboard");
            case 15 -> plugin.settings().toggle(id, "sounds");
            case 22 -> {
                player.closeInventory();
                return;
            }
            default -> {
                return;
            }
        }
        render();
    }
}

/** /profile /stats /playtime /leaderboard /settings */
final class ProfileCmd extends Cmd {
    private final String action;

    ProfileCmd(IyhaCore plugin, String name, String permission, String action) {
        super(plugin, name, permission, 2000);
        this.action = action;
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        switch (action) {
            case "settings" -> new SettingsMenu(plugin, player).open(player);
            case "leaderboard" -> {
                LeaderboardService.Type type = args.length > 0 ? LeaderboardService.Type.of(args[0]) : LeaderboardService.Type.MONEY;
                LeaderboardMenu.open(plugin, player, type == null ? LeaderboardService.Type.MONEY : type);
            }
            default -> {
                if (args.length == 0) {
                    show(player, player.getUniqueId(), player.getName());
                    return;
                }
                if (!player.hasPermission("iyha.profile.others")) {
                    plugin.lang().send(player, "no-permission");
                    return;
                }
                guard(plugin.players().resolve(args[0]).thenAccept(found -> plugin.sync(() -> {
                    if (found.isEmpty()) {
                        plugin.lang().send(player, "unknown-player", "name", args[0]);
                    } else {
                        show(player, found.get().uuid(), found.get().name());
                    }
                })), player);
            }
        }
    }

    private void show(Player viewer, UUID id, String name) {
        switch (action) {
            case "profile" -> ProfileMenu.open(plugin, viewer, id, name);
            case "playtime" -> guard(plugin.stats().snapshot(id, name).thenAccept(s -> plugin.sync(() ->
                    plugin.lang().send(viewer, "playtime", "player", name, "time", StatsService.duration(s.playtime())))), viewer);
            default -> guard(plugin.stats().snapshot(id, name).thenAccept(s -> plugin.sync(() -> {
                plugin.lang().send(viewer, "stats-header", "player", name);
                plugin.lang().send(viewer, "stats-line", "kills", String.valueOf(s.kills()), "deaths", String.valueOf(s.deaths()),
                        "kdr", String.valueOf(s.kdr()), "streak", String.valueOf(s.streak()), "best", String.valueOf(s.best()),
                        "time", StatsService.duration(s.playtime()));
            })), viewer);
        }
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1 && action.equals("leaderboard")) {
            List<String> keys = new ArrayList<>();
            for (LeaderboardService.Type t : LeaderboardService.Type.values()) {
                keys.add(t.key);
            }
            return filter(keys, args[0]);
        }
        return args.length == 1 && !action.equals("settings") ? onlineNames(args[0]) : List.of();
    }
}
