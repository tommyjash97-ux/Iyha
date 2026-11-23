package net.iyha.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Player records keyed by UUID, plus a few cached settings for online players. */
final class PlayerDataService implements Listener {

    record PlayerRef(UUID uuid, String name) {}

    private final IyhaCore plugin;
    private final Database db;
    private final Map<UUID, Boolean> payEnabled = new ConcurrentHashMap<>();

    PlayerDataService(IyhaCore plugin) {
        this.plugin = plugin;
        this.db = plugin.database();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        track(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        payEnabled.remove(id);
        plugin.economy().evict(id);
        db.supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET last_seen = ? WHERE uuid = ?")) {
                ps.setLong(1, now);
                ps.setString(2, id.toString());
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Could not update last_seen", ex);
            return null;
        });
    }

    /** Creates or updates the player's rows and loads their account. */
    void track(Player player) {
        UUID id = player.getUniqueId();
        String uuid = id.toString();
        String name = player.getName();
        long now = System.currentTimeMillis();
        db.supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement(db.insertIgnore()
                    + "players(uuid, name, name_lower, first_join, last_seen) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setString(3, name.toLowerCase(Locale.ROOT));
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET name = ?, name_lower = ?, last_seen = ? WHERE uuid = ?")) {
                ps.setString(1, name);
                ps.setString(2, name.toLowerCase(Locale.ROOT));
                ps.setLong(3, now);
                ps.setString(4, uuid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(db.insertIgnore() + "player_settings(uuid) VALUES (?)")) {
                ps.setString(1, uuid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    db.insertIgnore() + "player_stats(uuid, updated_at) VALUES (?, ?)")) {
                ps.setString(1, uuid);
                ps.setLong(2, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT pay_toggle FROM player_settings WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    return !rs.next() || rs.getInt(1) != 0;
                }
            }
        }).thenCompose(payOn -> {
            payEnabled.put(id, payOn);
            return plugin.economy().loadAccount(id);
        }).thenAccept(v -> plugin.sync(() -> {
            if (player.isOnline()) {
                plugin.claims().notifyPending(player);
            }
        })).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Could not load player data for " + name, ex);
            return null;
        });
    }

    /** Finds a player by exact name: online players first, then the players table. */
    CompletableFuture<Optional<PlayerRef>> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(new PlayerRef(online.getUniqueId(), online.getName())));
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return db.supplyIo(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, name FROM players WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1")) {
                ps.setString(1, lower);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2)));
                    }
                    return Optional.<PlayerRef>empty();
                }
            }
        });
    }

    /** Payments setting. Offline players are treated as accepting payments. */
    boolean isPayEnabled(UUID id) {
        return payEnabled.getOrDefault(id, true);
    }

    CompletableFuture<Void> setPayEnabled(UUID id, boolean value) {
        payEnabled.put(id, value);
        return db.supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE player_settings SET pay_toggle = ? WHERE uuid = ?")) {
                ps.setInt(1, value ? 1 : 0);
                ps.setString(2, id.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
