package net.iyha.core;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Combat tag, logout punishment, kills and deaths, anti-farming and kill messages. Numbers (tag length, blocked
 * commands, cooldowns) are IYHA placeholders in config.yml: the reference's real values could not be verified.
 */
final class CombatService implements Listener {
    private final IyhaCore plugin;
    private final Map<UUID, Long> tagged = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastAttacker = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private final Set<UUID> loggedOut = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> recentKills = new ConcurrentHashMap<>();
    private final Map<UUID, Long> suicideConfirm = new ConcurrentHashMap<>();

    CombatService(IyhaCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    boolean isTagged(Player player) {
        Long until = tagged.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    long secondsLeft(Player player) {
        Long until = tagged.get(player.getUniqueId());
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    private long tagMillis() {
        return plugin.getConfig().getLong("combat.tag-seconds", 15) * 1000L;
    }

    private void tag(Player player, Player other) {
        if (player.hasPermission("iyha.bypass.combat")) {
            return;
        }
        boolean wasTagged = isTagged(player);
        tagged.put(player.getUniqueId(), System.currentTimeMillis() + tagMillis());
        lastAttacker.put(player.getUniqueId(), other.getUniqueId());
        plugin.teleports().cancel(player.getUniqueId(), true);
        if (!wasTagged) {
            plugin.lang().send(player, "combat-tagged", "seconds", String.valueOf(secondsLeft(player)));
            BossBar bar = BossBar.bossBar(Ui.c("<red>In combat"), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            bars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long total = tagMillis();
        for (Map.Entry<UUID, Long> e : new ArrayList<>(tagged.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            long left = e.getValue() - now;
            BossBar bar = bars.get(e.getKey());
            if (p == null || left <= 0) {
                tagged.remove(e.getKey());
                BossBar removed = bars.remove(e.getKey());
                if (p != null) {
                    if (removed != null) {
                        p.hideBossBar(removed);
                    }
                    plugin.lang().send(p, "combat-ended");
                }
                continue;
            }
            if (bar != null) {
                bar.progress(Math.max(0f, Math.min(1f, (float) left / total)));
                bar.name(Ui.c("<red>In combat: <white>" + ((left + 999) / 1000) + "s"));
            }
        }
        recentKills.values().removeIf(t -> t < now);
        suicideConfirm.values().removeIf(t -> t < now);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null || attacker.equals(victim) || attacker.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (plugin.getConfig().getStringList("combat.pvp-disabled-worlds").contains(victim.getWorld().getName())) {
            event.setCancelled(true);
            return;
        }
        tag(attacker, victim);
        tag(victim, attacker);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isTagged(player) || player.hasPermission("iyha.bypass.combat")) {
            return;
        }
        String base = event.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (base.contains(":")) {
            base = base.substring(base.indexOf(':') + 1);
        }
        if (plugin.getConfig().getStringList("combat.blocked-commands").contains(base)) {
            event.setCancelled(true);
            plugin.lang().send(player, "combat-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        BossBar bar = bars.remove(id);
        if (bar != null) {
            player.hideBossBar(bar);
        }
        if (isTagged(player) && !player.hasPermission("iyha.bypass.combat")
                && plugin.getConfig().getString("combat.logout-action", "KILL").equalsIgnoreCase("KILL")) {
            loggedOut.add(id);
            plugin.getLogger().info(player.getName() + " logged out in combat.");
            player.setHealth(0.0);
        }
        tagged.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID vid = victim.getUniqueId();
        Player killer = victim.getKiller();
        if (loggedOut.remove(vid)) {
            UUID last = lastAttacker.get(vid);
            killer = last == null ? null : Bukkit.getPlayer(last);
        }
        tagged.remove(vid);
        BossBar bar = bars.remove(vid);
        if (bar != null) {
            victim.hideBossBar(bar);
        }
        plugin.stats().addDeath(victim);
        if (killer == null || killer.equals(victim)) {
            return;
        }
        boolean counted = validKill(killer, victim);
        String weapon = "fists";
        ItemStack hand = killer.getInventory().getItemInMainHand();
        if (!hand.getType().isAir()) {
            weapon = hand.getType().getKey().getKey().replace('_', ' ');
        }
        String template = plugin.getConfig().getString("combat.kill-message", "<red><victim> <gray>was slain by <red><killer> <gray>using <white><weapon>");
        event.deathMessage(Ui.MM.deserialize(template, Placeholder.unparsed("victim", victim.getName()),
                Placeholder.unparsed("killer", killer.getName()), Placeholder.unparsed("weapon", weapon)));
        logKill(killer, victim, weapon, counted);
        if (counted) {
            plugin.stats().addKill(killer);
            plugin.bounties().claim(killer, victim);
        } else {
            plugin.lang().send(killer, "kill-not-counted");
        }
    }

    private boolean validKill(Player killer, Player victim) {
        var cfg = plugin.getConfig();
        long cooldown = cfg.getLong("combat.anti-farm.same-victim-cooldown-seconds", 600) * 1000L;
        String key = killer.getUniqueId() + ":" + victim.getUniqueId();
        Long until = recentKills.get(key);
        if (until != null && until > System.currentTimeMillis()) {
            return false;
        }
        if (cfg.getBoolean("combat.anti-farm.same-ip-blocked", true) && killer.getAddress() != null && victim.getAddress() != null
                && killer.getAddress().getAddress().equals(victim.getAddress().getAddress())) {
            return false;
        }
        long minMinutes = cfg.getLong("combat.anti-farm.min-victim-playtime-minutes", 10);
        if (plugin.stats().playtimeSeconds(victim.getUniqueId()) < minMinutes * 60) {
            return false;
        }
        recentKills.put(key, System.currentTimeMillis() + cooldown);
        return true;
    }

    private void logKill(Player killer, Player victim, String weapon, boolean counted) {
        long now = System.currentTimeMillis();
        var loc = victim.getLocation();
        plugin.database().supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kill_log(killer, victim, world, x, y, z, weapon, "
                    + "counted, created_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, killer.getUniqueId().toString());
                ps.setString(2, victim.getUniqueId().toString());
                ps.setString(3, loc.getWorld().getName());
                ps.setInt(4, loc.getBlockX());
                ps.setInt(5, loc.getBlockY());
                ps.setInt(6, loc.getBlockZ());
                ps.setString(7, weapon);
                ps.setInt(8, counted ? 1 : 0);
                ps.setLong(9, now);
                ps.executeUpdate();
            }
            return (Void) null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Could not write kill log", ex);
            return null;
        });
    }

    /** /kill with confirmation. */
    void suicide(Player player) {
        if (isTagged(player) && !player.hasPermission("iyha.bypass.combat")) {
            plugin.lang().send(player, "combat-blocked");
            return;
        }
        Long until = suicideConfirm.get(player.getUniqueId());
        if (until == null || until < System.currentTimeMillis()) {
            suicideConfirm.put(player.getUniqueId(), System.currentTimeMillis() + 10_000L);
            plugin.lang().send(player, "kill-confirm");
            return;
        }
        suicideConfirm.remove(player.getUniqueId());
        player.setHealth(0.0);
    }
}

/** Bounties: money is escrowed when placed and paid to the first counted killer. */
final class BountyService {
    record Row(String targetName, UUID target, long total, int count) {}

    private final IyhaCore plugin;
    private final Database db;

    BountyService(IyhaCore plugin) {
        this.plugin = plugin;
        this.db = plugin.database();
    }

    CompletableFuture<EconomyService.Status> add(Player placer, UUID target, long amount) {
        List<EconomyService.Posting> posts = new ArrayList<>();
        String placerKey = placer.getUniqueId().toString();
        return db.supplySerial(c -> Database.tx(c, conn -> {
            EconomyService.Applied applied = plugin.economy().applyLedger(conn, placerKey,
                    Map.of(EconomyService.ESCROW, amount), "BOUNTY_ADD", target.toString(), placer.getName(), null, false);
            if (applied.status() != EconomyService.Status.OK) {
                return applied.status();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO bounties(target, placer, amount, status, created_at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, target.toString());
                ps.setString(2, placerKey);
                ps.setLong(3, amount);
                ps.setString(4, "ACTIVE");
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            posts.addAll(applied.postings());
            return EconomyService.Status.OK;
        }, s -> s == EconomyService.Status.OK)).thenApply(status -> {
            if (status == EconomyService.Status.OK) {
                plugin.economy().publish(posts);
            }
            return status;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Bounty add failed", ex);
            return EconomyService.Status.ERROR;
        });
    }

    /** Pays every active bounty on {@code victim} to {@code killer}. */
    void claim(Player killer, Player victim) {
        String target = victim.getUniqueId().toString();
        String killerKey = killer.getUniqueId().toString();
        List<EconomyService.Posting> posts = new ArrayList<>();
        db.supplySerial(c -> Database.tx(c, conn -> {
            long total = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COALESCE(SUM(amount), 0) FROM bounties WHERE target = ? AND status = 'ACTIVE'" + db.forUpdate())) {
                ps.setString(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            if (total <= 0) {
                return 0L;
            }
            EconomyService.Applied applied = plugin.economy().applyLedger(conn, EconomyService.ESCROW,
                    Map.of(killerKey, total), "BOUNTY_CLAIM", target, killer.getName(), null, false);
            if (applied.status() != EconomyService.Status.OK) {
                return 0L;
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE bounties SET status = 'CLAIMED', claimed_by = ? "
                    + "WHERE target = ? AND status = 'ACTIVE'")) {
                ps.setString(1, killerKey);
                ps.setString(2, target);
                ps.executeUpdate();
            }
            posts.addAll(applied.postings());
            return total;
        }, t -> t > 0)).thenAccept(total -> {
            if (total > 0) {
                plugin.sync(() -> {
                    plugin.economy().publish(posts);
                    plugin.lang().send(killer, "bounty-claimed", "amount", plugin.economy().format(total), "player", victim.getName());
                });
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Bounty claim failed", ex);
            return null;
        });
    }

    CompletableFuture<List<Row>> list(UUID target) {
        return db.supplyIo(c -> {
            List<Row> rows = new ArrayList<>();
            String sql = "SELECT b.target, p.name, SUM(b.amount), COUNT(*) FROM bounties b LEFT JOIN players p ON p.uuid = b.target "
                    + "WHERE b.status = 'ACTIVE'" + (target != null ? " AND b.target = ?" : "")
                    + " GROUP BY b.target, p.name ORDER BY SUM(b.amount) DESC LIMIT 10";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (target != null) {
                    ps.setString(1, target.toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(2);
                        rows.add(new Row(name == null ? "?" : name, UUID.fromString(rs.getString(1)), rs.getLong(3), rs.getInt(4)));
                    }
                }
            }
            return rows;
        });
    }
}

/** /bounty add|list|info */
final class BountyCmd extends Cmd {
    BountyCmd(IyhaCore plugin) {
        super(plugin, "bounty", "iyha.combat.bounty", 3000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add" -> add(player, args);
            case "info" -> {
                if (args.length < 2) {
                    plugin.lang().send(player, "usage", "usage", "/bounty info <player>");
                    return;
                }
                guard(plugin.players().resolve(args[1]).thenCompose(found -> {
                    if (found.isEmpty()) {
                        plugin.sync(() -> plugin.lang().send(player, "unknown-player", "name", args[1]));
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return plugin.bounties().list(found.get().uuid()).thenAccept(rows -> plugin.sync(() -> show(player, rows)));
                }), player);
            }
            default -> guard(plugin.bounties().list(null).thenAccept(rows -> plugin.sync(() -> show(player, rows))), player);
        }
    }

    private void show(Player player, List<BountyService.Row> rows) {
        plugin.lang().send(player, "bounty-header");
        if (rows.isEmpty()) {
            plugin.lang().send(player, "bounty-none");
        }
        for (BountyService.Row r : rows) {
            plugin.lang().send(player, "bounty-line", "player", r.targetName(),
                    "amount", plugin.economy().format(r.total()), "count", String.valueOf(r.count()));
        }
    }

    private void add(Player player, String[] args) {
        if (args.length != 3) {
            plugin.lang().send(player, "usage", "usage", "/bounty add <player> <amount>");
            return;
        }
        var amount = Money.parse(args[2]);
        if (amount.isEmpty()) {
            plugin.lang().send(player, "invalid-amount");
            return;
        }
        long min = Money.fromDouble(plugin.getConfig().getDouble("combat.min-bounty", 1000));
        if (amount.getAsLong() < min) {
            plugin.lang().send(player, "pay-too-small", "amount", plugin.economy().format(min));
            return;
        }
        guard(plugin.players().resolve(args[1]).thenCompose(found -> {
            if (found.isEmpty() || found.get().uuid().equals(player.getUniqueId())) {
                plugin.sync(() -> plugin.lang().send(player, found.isEmpty() ? "unknown-player" : "pay-self", "name", args[1]));
                return CompletableFuture.<Void>completedFuture(null);
            }
            return plugin.bounties().add(player, found.get().uuid(), amount.getAsLong()).thenAccept(status -> plugin.sync(() -> {
                switch (status) {
                    case OK -> {
                        plugin.lang().send(player, "bounty-placed", "amount", plugin.economy().format(amount.getAsLong()),
                                "player", found.get().name());
                        Bukkit.broadcast(plugin.lang().component("bounty-broadcast", "player", found.get().name(),
                                "amount", plugin.economy().format(amount.getAsLong())));
                    }
                    case INSUFFICIENT -> plugin.lang().send(player, "pay-insufficient", "balance",
                            plugin.economy().format(plugin.economy().cachedBalance(player.getUniqueId())));
                    case FROZEN -> plugin.lang().send(player, "economy-frozen");
                    default -> plugin.lang().send(player, "internal-error");
                }
            }));
        }), player);
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("add", "list", "info"), args[0]);
        }
        return args.length == 2 ? onlineNames(args[1]) : List.of();
    }
}

/** /suicide (with confirmation) */
final class KillCmd extends Cmd {
    KillCmd(IyhaCore plugin) {
        super(plugin, "suicide", "iyha.combat.suicide", 0);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (sender instanceof Player player) {
            plugin.combat().suicide(player);
        } else {
            plugin.lang().send(sender, "players-only");
        }
    }
}
