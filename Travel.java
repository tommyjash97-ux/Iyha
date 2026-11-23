package net.iyha.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Rank-driven limits read from permission nodes such as iyha.limit.homes.5 (set per group in LuckPerms). */
final class Limits {
    int get(Player player, String name, int fallback) {
        String prefix = "iyha.limit." + name + ".";
        int best = -1;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info.getValue() && info.getPermission().startsWith(prefix)) {
                try {
                    best = Math.max(best, Integer.parseInt(info.getPermission().substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // not a number
                }
            }
        }
        return best >= 0 ? best : fallback;
    }

    /** For cooldown and warm-up values: the lowest value any of the player's permissions grants. */
    int lowest(Player player, String name, int fallback) {
        String prefix = "iyha.limit." + name + ".";
        int best = Integer.MAX_VALUE;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info.getValue() && info.getPermission().startsWith(prefix)) {
                try {
                    best = Math.min(best, Integer.parseInt(info.getPermission().substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // not a number
                }
            }
        }
        return best == Integer.MAX_VALUE ? fallback : best;
    }
}

/** Warm-up, cooldown, combat block and safety checks for every kind of teleport. */
final class TeleportService implements Listener {
    private record Warmup(Location start, BukkitTask task) {}

    private static final Set<String> BAD_FLOOR = Set.of("LAVA", "MAGMA_BLOCK", "CACTUS", "FIRE", "SOUL_FIRE", "CAMPFIRE",
            "SOUL_CAMPFIRE", "SWEET_BERRY_BUSH", "WITHER_ROSE", "POWDER_SNOW", "WATER", "POINTED_DRIPSTONE");

    private final IyhaCore plugin;
    private final Map<UUID, Warmup> warmups = new ConcurrentHashMap<>();

    TeleportService(IyhaCore plugin) {
        this.plugin = plugin;
    }

    boolean isSafe(Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return false;
        }
        int y = loc.getBlockY();
        if (y < w.getMinHeight() + 1 || y > w.getMaxHeight() - 2) {
            return false;
        }
        Block feet = loc.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block below = feet.getRelative(BlockFace.DOWN);
        if (!feet.isPassable() || !head.isPassable() || feet.isLiquid() || head.isLiquid()) {
            return false;
        }
        Material floor = below.getType();
        return !floor.isAir() && !BAD_FLOOR.contains(floor.name());
    }

    /** Starts a teleport of {@code player} to wherever {@code destination} points when the warm-up ends. */
    void request(Player player, Supplier<Location> destination, String kind, Runnable onDone) {
        UUID id = player.getUniqueId();
        boolean bypassCombat = player.hasPermission("iyha.bypass.combat");
        if (!bypassCombat && plugin.combat().isTagged(player)) {
            plugin.lang().send(player, "combat-blocked");
            return;
        }
        boolean bypassCooldown = player.hasPermission("iyha.bypass.cooldown");
        int cdSeconds = plugin.limits().lowest(player, "cooldown-" + kind,
                plugin.getConfig().getInt("travel.cooldowns." + kind, 5));
        if (!bypassCooldown) {
            long left = plugin.cooldowns().remaining(id, "tp-" + kind, cdSeconds * 1000L);
            if (left > 0) {
                plugin.lang().send(player, "cooldown", "seconds", String.valueOf((left + 999) / 1000));
                return;
            }
        }
        if (warmups.containsKey(id)) {
            plugin.lang().send(player, "tp-already");
            return;
        }
        int warm = bypassCooldown ? 0 : plugin.limits().lowest(player, "warmup",
                plugin.getConfig().getInt("travel.warmup-seconds", 5));
        if (warm <= 0) {
            complete(player, destination, kind, onDone);
            return;
        }
        int[] left = {warm};
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                cancel(id, false);
                return;
            }
            if (left[0] <= 0) {
                warmups.remove(id);
                holder[0].cancel();
                complete(player, destination, kind, onDone);
                return;
            }
            plugin.lang().send(player, "tp-warmup", "seconds", String.valueOf(left[0]));
            left[0]--;
        }, 0L, 20L);
        warmups.put(id, new Warmup(player.getLocation().clone(), holder[0]));
    }

    private void complete(Player player, Supplier<Location> destination, String kind, Runnable onDone) {
        Location loc = destination.get();
        if (loc == null || loc.getWorld() == null) {
            plugin.lang().send(player, "tp-no-destination");
            return;
        }
        if (!player.hasPermission("iyha.tp.unsafe") && !isSafe(loc)) {
            plugin.lang().send(player, "tp-unsafe");
            return;
        }
        player.teleportAsync(loc).thenAccept(ok -> plugin.sync(() -> {
            if (ok) {
                plugin.cooldowns().start(player.getUniqueId(), "tp-" + kind);
                plugin.lang().send(player, "tp-done");
                if (onDone != null) {
                    onDone.run();
                }
            } else {
                plugin.lang().send(player, "tp-failed");
            }
        }));
    }

    void cancel(UUID id, boolean notify) {
        Warmup w = warmups.remove(id);
        if (w != null) {
            w.task().cancel();
            Player p = Bukkit.getPlayer(id);
            if (notify && p != null) {
                plugin.lang().send(p, "tp-cancelled");
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Warmup w = warmups.get(event.getPlayer().getUniqueId());
        if (w != null && event.getTo() != null && (w.start().getBlockX() != event.getTo().getBlockX()
                || w.start().getBlockY() != event.getTo().getBlockY() || w.start().getBlockZ() != event.getTo().getBlockZ())) {
            cancel(event.getPlayer().getUniqueId(), true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            cancel(p.getUniqueId(), true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), false);
    }
}

/** Homes, warps, spawn, TPA requests and random teleport. */
final class TravelService implements Listener {
    record Home(String name, String world, double x, double y, double z, float yaw, float pitch) {
        Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    record Warp(String name, String world, double x, double y, double z, float yaw, float pitch, String permission) {
        Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    record Request(UUID from, UUID to, boolean here, long expiresAt) {}

    private final IyhaCore plugin;
    private final Database db;
    private final Map<UUID, Map<String, Home>> homes = new ConcurrentHashMap<>();
    private final Map<String, Warp> warps = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Request>> requests = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private volatile Location spawn;

    TravelService(IyhaCore plugin) {
        this.plugin = plugin;
        this.db = plugin.database();
        loadWarps();
        loadSpawn();
    }

    // ------------------------------------------------------------------ loading

    private void loadWarps() {
        try {
            db.supplyIo(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT name, world, x, y, z, yaw, pitch, permission FROM warps");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        warps.put(rs.getString(1).toLowerCase(Locale.ROOT), new Warp(rs.getString(1), rs.getString(2),
                                rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getFloat(6), rs.getFloat(7), rs.getString(8)));
                    }
                }
                return (Void) null;
            }).join();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not load warps", ex);
        }
    }

    private void loadSpawn() {
        try {
            String raw = db.supplyIo(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT value FROM system_flags WHERE flag = 'spawn'");
                     ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }).join();
            if (raw != null) {
                String[] p = raw.split(";");
                World w = Bukkit.getWorld(p[0]);
                if (w != null) {
                    spawn = new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                            Float.parseFloat(p[4]), Float.parseFloat(p[5]));
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Could not load spawn", ex);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        db.supplyIo(c -> {
            Map<String, Home> map = new ConcurrentHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, world, x, y, z, yaw, pitch FROM homes WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1).toLowerCase(Locale.ROOT), new Home(rs.getString(1), rs.getString(2),
                                rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getFloat(6), rs.getFloat(7)));
                    }
                }
            }
            return map;
        }).thenAccept(map -> homes.put(id, map)).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Could not load homes", ex);
            return null;
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        homes.remove(id);
        requests.remove(id);
        requests.values().forEach(m -> m.remove(id));
    }

    // ------------------------------------------------------------------ spawn

    Location spawn(World fallbackWorld) {
        if (spawn != null) {
            return spawn.clone();
        }
        World w = fallbackWorld != null ? fallbackWorld : Bukkit.getWorlds().get(0);
        return w.getSpawnLocation().clone().add(0.5, 0, 0.5);
    }

    void setSpawn(Location loc) {
        spawn = loc.clone();
        String raw = loc.getWorld().getName() + ";" + loc.getX() + ";" + loc.getY() + ";" + loc.getZ() + ";" + loc.getYaw() + ";" + loc.getPitch();
        db.supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("REPLACE INTO system_flags(flag, value) VALUES ('spawn', ?)")) {
                ps.setString(1, raw);
                ps.executeUpdate();
            }
            return (Void) null;
        });
    }

    // ------------------------------------------------------------------ homes

    Map<String, Home> homesOf(UUID id) {
        return homes.getOrDefault(id, Map.of());
    }

    void setHome(Player player, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!name.matches("[A-Za-z0-9_\\-]{1,24}")) {
            plugin.lang().send(player, "home-bad-name");
            return;
        }
        if (plugin.getConfig().getStringList("travel.home-denied-worlds").contains(player.getWorld().getName())) {
            plugin.lang().send(player, "home-denied-world");
            return;
        }
        Map<String, Home> map = homes.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        int limit = plugin.limits().get(player, "homes", plugin.getConfig().getInt("travel.default-homes", 2));
        if (!map.containsKey(key) && map.size() >= limit) {
            plugin.lang().send(player, "home-limit", "limit", String.valueOf(limit));
            return;
        }
        Location l = player.getLocation();
        Home home = new Home(name, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        map.put(key, home);
        long now = System.currentTimeMillis();
        String uuid = player.getUniqueId().toString();
        db.supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("REPLACE INTO homes(uuid, name, world, x, y, z, yaw, pitch, created_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setString(3, home.world());
                ps.setDouble(4, home.x());
                ps.setDouble(5, home.y());
                ps.setDouble(6, home.z());
                ps.setFloat(7, home.yaw());
                ps.setFloat(8, home.pitch());
                ps.setLong(9, now);
                ps.executeUpdate();
            }
            return (Void) null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Could not save home", ex);
            return null;
        });
        plugin.lang().send(player, "home-set", "name", name);
    }

    boolean deleteHome(Player player, String name) {
        Map<String, Home> map = homes.get(player.getUniqueId());
        if (map == null || map.remove(name.toLowerCase(Locale.ROOT)) == null) {
            return false;
        }
        String uuid = player.getUniqueId().toString();
        db.supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM homes WHERE uuid = ? AND name = ?")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.executeUpdate();
            }
            return (Void) null;
        });
        return true;
    }

    boolean renameHome(Player player, String from, String to) {
        Map<String, Home> map = homes.get(player.getUniqueId());
        if (map == null || !to.matches("[A-Za-z0-9_\\-]{1,24}") || map.containsKey(to.toLowerCase(Locale.ROOT))) {
            return false;
        }
        Home old = map.remove(from.toLowerCase(Locale.ROOT));
        if (old == null) {
            return false;
        }
        Home renamed = new Home(to, old.world(), old.x(), old.y(), old.z(), old.yaw(), old.pitch());
        map.put(to.toLowerCase(Locale.ROOT), renamed);
        String uuid = player.getUniqueId().toString();
        db.supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE homes SET name = ? WHERE uuid = ? AND name = ?")) {
                ps.setString(1, to);
                ps.setString(2, uuid);
                ps.setString(3, old.name());
                ps.executeUpdate();
            }
            return (Void) null;
        });
        return true;
    }

    // ------------------------------------------------------------------ warps

    Map<String, Warp> warps() {
        return warps;
    }

    void setWarp(Player admin, String name, String permission) {
        Location l = admin.getLocation();
        Warp warp = new Warp(name, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(),
                permission == null ? "" : permission);
        warps.put(name.toLowerCase(Locale.ROOT), warp);
        db.supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("REPLACE INTO warps(name, world, x, y, z, yaw, pitch, permission, "
                    + "created_by) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, name);
                ps.setString(2, warp.world());
                ps.setDouble(3, warp.x());
                ps.setDouble(4, warp.y());
                ps.setDouble(5, warp.z());
                ps.setFloat(6, warp.yaw());
                ps.setFloat(7, warp.pitch());
                ps.setString(8, warp.permission());
                ps.setString(9, admin.getUniqueId().toString());
                ps.executeUpdate();
            }
            return (Void) null;
        });
    }

    boolean deleteWarp(String name) {
        if (warps.remove(name.toLowerCase(Locale.ROOT)) == null) {
            return false;
        }
        db.supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM warps WHERE name = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
            return (Void) null;
        });
        return true;
    }

    // ------------------------------------------------------------------ TPA

    void sendRequest(Player from, Player to, boolean here) {
        String mode = plugin.settings().tpaMode(to.getUniqueId());
        if (mode.equals("DENY")) {
            plugin.lang().send(from, "tpa-denied-mode", "player", to.getName());
            return;
        }
        Request req = new Request(from.getUniqueId(), to.getUniqueId(), here,
                System.currentTimeMillis() + plugin.getConfig().getLong("travel.tpa-expire-seconds", 60) * 1000L);
        requests.computeIfAbsent(to.getUniqueId(), k -> new ConcurrentHashMap<>()).put(from.getUniqueId(), req);
        plugin.lang().send(from, "tpa-sent", "player", to.getName());
        if (mode.equals("ACCEPT")) {
            accept(to, from.getName());
            return;
        }
        Component accept = Component.text(" [Accept]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/tpaccept " + from.getName()));
        Component deny = Component.text(" [Deny]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/tpadeny " + from.getName()));
        to.sendMessage(plugin.lang().component(here ? "tpahere-request" : "tpa-request", "player", from.getName())
                .append(accept).append(deny));
    }

    private Request pick(Player target, String fromName) {
        Map<UUID, Request> map = requests.get(target.getUniqueId());
        if (map == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        map.values().removeIf(r -> r.expiresAt() < now);
        if (fromName != null) {
            Player from = Bukkit.getPlayerExact(fromName);
            return from == null ? null : map.get(from.getUniqueId());
        }
        return map.values().stream().max(Comparator.comparingLong(Request::expiresAt)).orElse(null);
    }

    void accept(Player target, String fromName) {
        Request req = pick(target, fromName);
        if (req == null) {
            plugin.lang().send(target, "tpa-none");
            return;
        }
        requests.get(target.getUniqueId()).remove(req.from());
        Player from = Bukkit.getPlayer(req.from());
        if (from == null) {
            plugin.lang().send(target, "tpa-none");
            return;
        }
        plugin.lang().send(target, "tpa-accepted", "player", from.getName());
        plugin.lang().send(from, "tpa-accepted-by", "player", target.getName());
        if (req.here()) {
            plugin.teleports().request(target, () -> from.isOnline() ? from.getLocation() : null, "tpa", null);
        } else {
            plugin.teleports().request(from, () -> target.isOnline() ? target.getLocation() : null, "tpa", null);
        }
    }

    void deny(Player target, String fromName) {
        Request req = pick(target, fromName);
        if (req == null) {
            plugin.lang().send(target, "tpa-none");
            return;
        }
        requests.get(target.getUniqueId()).remove(req.from());
        plugin.lang().send(target, "tpa-denied");
        Player from = Bukkit.getPlayer(req.from());
        if (from != null) {
            plugin.lang().send(from, "tpa-denied-by", "player", target.getName());
        }
    }

    void cancelOutgoing(Player from) {
        boolean any = false;
        for (Map<UUID, Request> map : requests.values()) {
            any |= map.remove(from.getUniqueId()) != null;
        }
        plugin.lang().send(from, any ? "tpa-cancelled" : "tpa-none");
    }

    // ------------------------------------------------------------------ random teleport

    void rtp(Player player, World world) {
        var cfg = plugin.getConfig();
        if (!cfg.getStringList("travel.rtp.worlds").contains(world.getName())) {
            plugin.lang().send(player, "rtp-world");
            return;
        }
        if (plugin.combat().isTagged(player) && !player.hasPermission("iyha.bypass.combat")) {
            plugin.lang().send(player, "combat-blocked");
            return;
        }
        plugin.lang().send(player, "rtp-searching");
        search(player, world, cfg.getInt("travel.rtp.attempts", 10));
    }

    private void search(Player player, World world, int tries) {
        var cfg = plugin.getConfig();
        if (tries <= 0 || !player.isOnline()) {
            plugin.lang().send(player, "rtp-failed");
            return;
        }
        int min = cfg.getInt("travel.rtp.min-radius", 500);
        int max = Math.max(min + 1, cfg.getInt("travel.rtp.max-radius", 5000));
        double angle = random.nextDouble() * Math.PI * 2;
        double radius = min + random.nextDouble() * (max - min);
        Location center = world.getSpawnLocation();
        int x = (int) Math.round(center.getX() + Math.cos(angle) * radius);
        int z = (int) Math.round(center.getZ() + Math.sin(angle) * radius);
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> plugin.sync(() -> {
            if (!player.isOnline()) {
                return;
            }
            int y = world.getHighestBlockYAt(x, z);
            Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
            Block ground = world.getBlockAt(x, y, z);
            String biome = world.getBiome(x, y, z).getKey().getKey();
            boolean badBiome = cfg.getStringList("travel.rtp.denied-biomes").contains(biome);
            if (badBiome || ground.isLiquid() || !world.getWorldBorder().isInside(loc) || !plugin.teleports().isSafe(loc)) {
                search(player, world, tries - 1);
                return;
            }
            plugin.teleports().request(player, () -> loc, "rtp", () -> plugin.lang().send(player, "rtp-done",
                    "x", String.valueOf(x), "z", String.valueOf(z)));
        }));
    }
}

/** Menu of homes (left-click teleport, right-click delete). */
final class HomesMenu extends Menu {
    private final Player player;
    private final List<TravelService.Home> shown = new ArrayList<>();

    HomesMenu(IyhaCore plugin, Player player) {
        super(plugin);
        this.player = player;
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>Your homes");
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
        TreeMap<String, TravelService.Home> sorted = new TreeMap<>(plugin.travel().homesOf(player.getUniqueId()));
        int slot = 0;
        for (TravelService.Home h : sorted.values()) {
            if (slot >= 18) {
                break;
            }
            shown.add(h);
            set(slot++, Ui.button(Material.RED_BED, "<white>" + h.name(), "<gray>" + h.world(),
                    "<yellow>Left-click: teleport", "<red>Right-click: delete"));
        }
        set(22, Ui.button(Material.BARRIER, "<red>Close"));
    }

    @Override
    void onClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw == 22) {
            player.closeInventory();
            return;
        }
        if (raw < 0 || raw >= shown.size()) {
            return;
        }
        TravelService.Home h = shown.get(raw);
        player.closeInventory();
        if (event.isRightClick()) {
            plugin.travel().deleteHome(player, h.name());
            plugin.lang().send(player, "home-deleted", "name", h.name());
        } else {
            plugin.teleports().request(player, h::toLocation, "home", null);
        }
    }
}

/** Menu of public warps. */
final class WarpsMenu extends PagedMenu {
    private WarpsMenu(IyhaCore plugin) {
        super(plugin);
    }

    static void open(IyhaCore plugin, Player player) {
        WarpsMenu menu = new WarpsMenu(plugin);
        List<Entry> entries = new ArrayList<>();
        new TreeMap<>(plugin.travel().warps()).values().forEach(w -> {
            if (!w.permission().isEmpty() && !player.hasPermission(w.permission())) {
                return;
            }
            entries.add(new Entry(Ui.button(Material.ENDER_PEARL, "<white>" + w.name(), "<gray>" + w.world(),
                    "<yellow>Click to teleport"), e -> {
                player.closeInventory();
                plugin.teleports().request(player, w::toLocation, "warp", null);
            }));
        });
        menu.setEntries(entries);
        menu.open(player);
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>Warps");
    }
}

final class TpaCmd extends Cmd {
    private final boolean here;

    TpaCmd(IyhaCore plugin, String name, boolean here) {
        super(plugin, name, here ? "iyha.tp.tpahere" : "iyha.tp.tpa", 3000);
        this.here = here;
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        if (args.length != 1) {
            plugin.lang().send(player, "usage", "usage", "/" + label + " <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            plugin.lang().send(player, "unknown-player", "name", args[0]);
            return;
        }
        if (target.equals(player)) {
            plugin.lang().send(player, "pay-self");
            return;
        }
        plugin.travel().sendRequest(player, target, here);
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        return args.length == 1 ? onlineNames(args[0]) : List.of();
    }
}

/** /tpaccept, /tpadeny, /tpacancel, /tpatoggle */
final class TpaReplyCmd extends Cmd {
    private final String action;

    TpaReplyCmd(IyhaCore plugin, String name, String action) {
        super(plugin, name, action.equals("toggle") ? "iyha.tp.toggle" : "iyha.tp.tpa", 1000);
        this.action = action;
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        String name = args.length > 0 ? args[0] : null;
        switch (action) {
            case "accept" -> plugin.travel().accept(player, name);
            case "deny" -> plugin.travel().deny(player, name);
            case "cancel" -> plugin.travel().cancelOutgoing(player);
            default -> {
                String next = plugin.settings().cycleTpaMode(player.getUniqueId());
                plugin.lang().send(player, "tpatoggle", "mode", next.toLowerCase(Locale.ROOT));
            }
        }
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        return args.length == 1 && !action.equals("toggle") && !action.equals("cancel") ? onlineNames(args[0]) : List.of();
    }
}

/** /sethome /home /homes /delhome /renamehome */
final class HomeCmd extends Cmd {
    private final String action;

    HomeCmd(IyhaCore plugin, String name, String action) {
        super(plugin, name, action.equals("set") ? "iyha.tp.home.set" : "iyha.tp.home", 1000);
        this.action = action;
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        Map<String, TravelService.Home> homes = plugin.travel().homesOf(player.getUniqueId());
        switch (action) {
            case "set" -> plugin.travel().setHome(player, args.length > 0 ? args[0] : "home");
            case "go" -> {
                if (args.length == 0) {
                    if (homes.size() == 1) {
                        TravelService.Home only = homes.values().iterator().next();
                        plugin.teleports().request(player, only::toLocation, "home", null);
                    } else if (homes.isEmpty()) {
                        plugin.lang().send(player, "home-none");
                    } else {
                        new HomesMenu(plugin, player).open(player);
                    }
                    return;
                }
                TravelService.Home h = homes.get(args[0].toLowerCase(Locale.ROOT));
                if (h == null) {
                    plugin.lang().send(player, "home-unknown", "name", args[0]);
                } else {
                    plugin.teleports().request(player, h::toLocation, "home", null);
                }
            }
            case "list" -> new HomesMenu(plugin, player).open(player);
            case "delete" -> {
                if (args.length != 1) {
                    plugin.lang().send(player, "usage", "usage", "/delhome <name>");
                } else if (plugin.travel().deleteHome(player, args[0])) {
                    plugin.lang().send(player, "home-deleted", "name", args[0]);
                } else {
                    plugin.lang().send(player, "home-unknown", "name", args[0]);
                }
            }
            default -> {
                if (args.length != 2) {
                    plugin.lang().send(player, "usage", "usage", "/renamehome <old> <new>");
                } else if (plugin.travel().renameHome(player, args[0], args[1])) {
                    plugin.lang().send(player, "home-renamed", "name", args[1]);
                } else {
                    plugin.lang().send(player, "home-bad-name");
                }
            }
        }
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1 && sender instanceof Player p && !action.equals("set") && !action.equals("list")) {
            return filter(plugin.travel().homesOf(p.getUniqueId()).values().stream().map(TravelService.Home::name).toList(), args[0]);
        }
        return List.of();
    }
}

/** /warp /warps /setwarp /delwarp */
final class WarpCmd extends Cmd {
    private final String action;

    WarpCmd(IyhaCore plugin, String name, String action) {
        super(plugin, name, action.equals("go") || action.equals("list") ? "iyha.tp.warp" : "iyha.admin.warp", 1000);
        this.action = action;
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        switch (action) {
            case "list" -> WarpsMenu.open(plugin, player);
            case "go" -> {
                if (args.length == 0) {
                    WarpsMenu.open(plugin, player);
                    return;
                }
                TravelService.Warp w = plugin.travel().warps().get(args[0].toLowerCase(Locale.ROOT));
                if (w == null || (!w.permission().isEmpty() && !player.hasPermission(w.permission()))) {
                    plugin.lang().send(player, "warp-unknown", "name", args[0]);
                } else {
                    plugin.teleports().request(player, w::toLocation, "warp", null);
                }
            }
            case "set" -> {
                if (args.length < 1) {
                    plugin.lang().send(player, "usage", "usage", "/setwarp <name> [permission]");
                    return;
                }
                plugin.travel().setWarp(player, args[0], args.length > 1 ? args[1] : null);
                plugin.audit().log(player.getName(), "WARP_SET", args[0], null);
                plugin.lang().send(player, "warp-set", "name", args[0]);
            }
            default -> {
                if (args.length < 1 || !plugin.travel().deleteWarp(args[0])) {
                    plugin.lang().send(player, "warp-unknown", "name", args.length > 0 ? args[0] : "?");
                } else {
                    plugin.audit().log(player.getName(), "WARP_DELETE", args[0], null);
                    plugin.lang().send(player, "warp-deleted", "name", args[0]);
                }
            }
        }
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1 && !action.equals("list")) {
            return filter(plugin.travel().warps().values().stream().map(TravelService.Warp::name).toList(), args[0]);
        }
        return List.of();
    }
}

/** /spawn and /setspawn */
final class SpawnCmd extends Cmd {
    private final boolean set;

    SpawnCmd(IyhaCore plugin, String name, boolean set) {
        super(plugin, name, set ? "iyha.admin.spawn" : "iyha.tp.spawn", 1000);
        this.set = set;
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        if (set) {
            plugin.travel().setSpawn(player.getLocation());
            plugin.audit().log(player.getName(), "SETSPAWN", null, null);
            plugin.lang().send(player, "spawn-set");
        } else {
            plugin.teleports().request(player, () -> plugin.travel().spawn(player.getWorld()), "spawn", null);
        }
    }
}

/** /rtp [world] */
final class RtpCmd extends Cmd {
    RtpCmd(IyhaCore plugin) {
        super(plugin, "rtp", "iyha.tp.rtp", 0);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        World world = args.length > 0 ? Bukkit.getWorld(args[0]) : player.getWorld();
        if (world == null) {
            plugin.lang().send(player, "rtp-world");
            return;
        }
        if (!plugin.getConfig().getStringList("travel.rtp.worlds").contains(world.getName())) {
            world = Bukkit.getWorld(plugin.getConfig().getStringList("travel.rtp.worlds").stream().findFirst().orElse(""));
            if (world == null) {
                plugin.lang().send(player, "rtp-world");
                return;
            }
        }
        plugin.travel().rtp(player, world);
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        return args.length == 1 ? filter(plugin.getConfig().getStringList("travel.rtp.worlds"), args[0]) : List.of();
    }
}
