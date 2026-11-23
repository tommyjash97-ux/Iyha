package net.iyha.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in spawn protection (radius around /setspawn) and walk-in portals that run a command.
 * Works without WorldGuard. NPCs and holograms are left to Citizens and DecentHolograms (see README).
 */
final class SpawnProtection implements Listener {
    private final IyhaCore plugin;
    private final Map<UUID, String> insidePortal = new ConcurrentHashMap<>();

    SpawnProtection(IyhaCore plugin) {
        this.plugin = plugin;
        applyWorldBorders();
    }

    private void applyWorldBorders() {
        var section = plugin.getConfig().getConfigurationSection("world-border");
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            World w = Bukkit.getWorld(name);
            if (w != null) {
                w.getWorldBorder().setSize(Math.max(100, section.getDouble(name)));
            }
        }
    }

    boolean inSpawn(Location loc) {
        if (!plugin.getConfig().getBoolean("spawn.protection.enabled", true) || loc.getWorld() == null) {
            return false;
        }
        Location spawn = plugin.travel().spawn(null);
        if (spawn.getWorld() == null || !spawn.getWorld().equals(loc.getWorld())) {
            return false;
        }
        double r = plugin.getConfig().getDouble("spawn.protection.radius", 60);
        double dx = loc.getX() - spawn.getX();
        double dz = loc.getZ() - spawn.getZ();
        return dx * dx + dz * dz <= r * r;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (inSpawn(event.getBlock().getLocation()) && !event.getPlayer().hasPermission("iyha.spawn.bypass")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (inSpawn(event.getBlock().getLocation()) && !event.getPlayer().hasPermission("iyha.spawn.bypass")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Player
                && plugin.getConfig().getBoolean("spawn.protection.no-pvp", true) && inSpawn(victim.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (inSpawn(event.getLocation())) {
            event.blockList().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        Entity e = event.getEntity();
        if (plugin.getConfig().getBoolean("spawn.protection.no-hunger", true) && e instanceof Player && inSpawn(e.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Monster && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL
                && inSpawn(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Walk-in portals: portals list in config.yml, each with world, min, max (x,y,z) and a command. */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || event.getTo() == null) {
            return;
        }
        Player player = event.getPlayer();
        Location to = event.getTo();
        String found = null;
        String command = null;
        for (Map<?, ?> m : plugin.getConfig().getMapList("portals")) {
            if (!String.valueOf(m.get("world")).equals(to.getWorld().getName())) {
                continue;
            }
            double[] min = parse(String.valueOf(m.get("min")));
            double[] max = parse(String.valueOf(m.get("max")));
            if (min == null || max == null) {
                continue;
            }
            if (to.getX() >= min[0] && to.getX() <= max[0] + 1 && to.getY() >= min[1] && to.getY() <= max[1] + 1
                    && to.getZ() >= min[2] && to.getZ() <= max[2] + 1) {
                found = String.valueOf(m.get("name"));
                command = String.valueOf(m.get("command"));
                break;
            }
        }
        String previous = insidePortal.get(player.getUniqueId());
        if (found == null) {
            insidePortal.remove(player.getUniqueId());
        } else if (!found.equals(previous)) {
            insidePortal.put(player.getUniqueId(), found);
            String cmd = command;
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand(cmd));
        }
    }

    private double[] parse(String text) {
        try {
            String[] p = text.split(",");
            return new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim())};
        } catch (Exception ex) {
            return null;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        insidePortal.remove(event.getPlayer().getUniqueId());
    }
}
