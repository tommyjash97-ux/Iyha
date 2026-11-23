package net.iyha.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Write-ahead journal on disk for operations where an item leaves a player's inventory before the
 * database knows about it. States: STARTED (nothing removed yet), SAVED (item removed and player saved),
 * DONE / ABORTED (finished).
 */
final class Journal {
    record Entry(String id, String state, String op, String uuid, long ts, String payload) {}

    private final IyhaCore plugin;
    private final Path file;
    private final Object lock = new Object();

    Journal(IyhaCore plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("journal.log");
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Cannot create journal folder", ex);
        }
    }

    static String encode(List<ItemStack> items) {
        return items.stream().filter(Objects::nonNull).filter(i -> !i.getType().isAir())
                .map(i -> Base64.getEncoder().encodeToString(i.serializeAsBytes()))
                .collect(Collectors.joining(";"));
    }

    static List<ItemStack> decode(String payload) {
        List<ItemStack> list = new ArrayList<>();
        if (payload == null || payload.isEmpty()) {
            return list;
        }
        for (String part : payload.split(";")) {
            try {
                list.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(part)));
            } catch (Exception ignored) {
                // corrupted entry: skip
            }
        }
        return list;
    }

    String begin(String op, UUID who, String payload) {
        String id = UUID.randomUUID().toString();
        write(id, "STARTED", op, who, payload);
        return id;
    }

    void write(String id, String state, String op, UUID who, String payload) {
        String line = String.join("|", id, state, op, who.toString(), String.valueOf(System.currentTimeMillis()),
                payload == null ? "" : payload) + "\n";
        synchronized (lock) {
            try {
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "JOURNAL WRITE FAILED for " + id, ex);
            }
        }
    }

    void finish(String id, String state, String op, UUID who) {
        write(id, state, op, who, "");
    }

    /** Latest line per id, keeping only entries that never reached DONE or ABORTED. */
    List<Entry> unresolved() {
        Map<String, Entry> latest = new LinkedHashMap<>();
        Map<String, String> lastPayload = new LinkedHashMap<>();
        synchronized (lock) {
            if (!Files.exists(file)) {
                return List.of();
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] p = line.split("\\|", 6);
                    if (p.length < 5) {
                        continue;
                    }
                    String payload = p.length == 6 ? p[5] : "";
                    if (!payload.isEmpty()) {
                        lastPayload.put(p[0], payload);
                    }
                    latest.put(p[0], new Entry(p[0], p[1], p[2], p[3], Long.parseLong(p[4]),
                            lastPayload.getOrDefault(p[0], "")));
                }
            } catch (IOException | NumberFormatException ex) {
                plugin.getLogger().log(Level.SEVERE, "Cannot read journal", ex);
            }
        }
        return latest.values().stream()
                .filter(e -> !e.state().equals("DONE") && !e.state().equals("ABORTED"))
                .toList();
    }

    /** Rewrites the file with only unresolved entries. */
    void compact() {
        List<Entry> keep = unresolved();
        synchronized (lock) {
            try {
                List<String> lines = keep.stream().map(e -> String.join("|", e.id(), e.state(), e.op(), e.uuid(),
                        String.valueOf(e.ts()), e.payload())).toList();
                Files.write(file, lines, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Cannot compact journal", ex);
            }
        }
    }
}

/** Pending item deliveries. An item is delivered exactly once: PENDING -> DELIVERED is a compare-and-set. */
final class ClaimService {
    record Claim(long id, ItemStack item, String source) {}

    private final IyhaCore plugin;
    private final Database db;

    ClaimService(IyhaCore plugin) {
        this.plugin = plugin;
        this.db = plugin.database();
    }

    static void insertClaim(Connection c, String owner, String source, String sourceId, byte[] bytes, int amount,
                            String idem, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO claims(owner, source, source_id, item_bytes, "
                + "amount, status, idem_key, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, owner);
            ps.setString(2, source);
            ps.setString(3, sourceId);
            ps.setBytes(4, bytes);
            ps.setInt(5, amount);
            ps.setString(6, "PENDING");
            ps.setString(7, idem);
            ps.setLong(8, now);
            ps.executeUpdate();
        }
    }

    static void insertClaim(Connection c, String owner, String source, String sourceId, ItemStack item,
                            String idem, long now) throws SQLException {
        insertClaim(c, owner, source, sourceId, item.serializeAsBytes(), item.getAmount(), idem, now);
    }

    CompletableFuture<Void> createAsync(UUID owner, ItemStack item, String source, String sourceId) {
        long now = System.currentTimeMillis();
        return db.supplySerial(c -> {
            insertClaim(c, owner.toString(), source, sourceId, item, null, now);
            return (Void) null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "FAILED to create claim for " + owner + ". Item bytes (base64): "
                    + Journal.encode(List.of(item)), ex);
            return null;
        });
    }

    CompletableFuture<List<Claim>> pending(UUID owner, String sourceFilter) {
        return db.supplyIo(c -> {
            List<Claim> list = new ArrayList<>();
            String sql = "SELECT claim_id, item_bytes, source FROM claims WHERE owner = ? AND status = 'PENDING'"
                    + (sourceFilter != null ? " AND source = ?" : "") + " ORDER BY claim_id LIMIT 200";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, owner.toString());
                if (sourceFilter != null) {
                    ps.setString(2, sourceFilter);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            list.add(new Claim(rs.getLong(1), ItemStack.deserializeBytes(rs.getBytes(2)), rs.getString(3)));
                        } catch (Exception ex) {
                            plugin.getLogger().warning("Skipping unreadable claim " + rs.getLong(1));
                        }
                    }
                }
            }
            return list;
        });
    }

    /** Delivers every pending claim that fits. Main thread. */
    void deliverAll(Player player) {
        pending(player.getUniqueId(), null).thenAccept(list -> plugin.sync(() -> {
            if (!player.isOnline()) {
                return;
            }
            for (Claim claim : list) {
                deliverOne(player, claim, null);
            }
        })).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Could not load claims", ex);
            return null;
        });
    }

    /** Tells the player how many claims are waiting. */
    void notifyPending(Player player) {
        db.supplyIo(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM claims WHERE owner = ? AND status = 'PENDING'")) {
                ps.setString(1, player.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }).thenAccept(count -> {
            if (count > 0) {
                plugin.sync(() -> plugin.lang().send(player, "claim-waiting", "count", String.valueOf(count)));
            }
        });
    }

    /** Main thread. Marks the claim delivered first, then hands over the item (no-dupe-first ordering). */
    void deliverOne(Player player, Claim claim, Runnable afterwards) {
        if (!Give.canFit(player.getInventory(), claim.item())) {
            plugin.lang().send(player, "claim-full");
            return;
        }
        long now = System.currentTimeMillis();
        db.supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE claims SET status = 'DELIVERED', delivered_at = ? WHERE claim_id = ? AND status = 'PENDING'")) {
                ps.setLong(1, now);
                ps.setLong(2, claim.id());
                return ps.executeUpdate() == 1;
            }
        }).thenAccept(won -> plugin.sync(() -> {
            if (!won) {
                return;
            }
            if (!player.isOnline()) {
                createAsync(player.getUniqueId(), claim.item(), "LEFTOVER", String.valueOf(claim.id()));
                return;
            }
            Map<Integer, ItemStack> left = player.getInventory().addItem(claim.item().clone());
            for (ItemStack rest : left.values()) {
                createAsync(player.getUniqueId(), rest, "LEFTOVER", String.valueOf(claim.id()));
            }
            player.saveData();
            plugin.lang().send(player, "claim-delivered");
            if (afterwards != null) {
                afterwards.run();
            }
        })).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Claim delivery failed for claim " + claim.id(), ex);
            return null;
        });
    }
}

/** Paged list of pending claims; click one to receive it. */
final class ClaimsMenu extends PagedMenu {
    private final Player owner;
    private final String sourceFilter;
    private final String titleText;

    private ClaimsMenu(IyhaCore plugin, Player owner, String sourceFilter, String titleText) {
        super(plugin);
        this.owner = owner;
        this.sourceFilter = sourceFilter;
        this.titleText = titleText;
    }

    static void open(IyhaCore plugin, Player player, String sourceFilter, String title) {
        ClaimsMenu menu = new ClaimsMenu(plugin, player, sourceFilter, title);
        menu.reload(true);
    }

    @Override
    protected Component title() {
        return Ui.c(titleText);
    }

    private void reload(boolean open) {
        plugin.claims().pending(owner.getUniqueId(), sourceFilter).thenAccept(list -> plugin.sync(() -> {
            List<Entry> entries = new ArrayList<>();
            for (ClaimService.Claim claim : list) {
                ItemStack icon = Ui.withLore(claim.item(), List.of("", "<yellow>Click to claim"));
                entries.add(new Entry(icon, e -> plugin.claims().deliverOne(owner, claim,
                        () -> Bukkit.getScheduler().runTaskLater(plugin, () -> reload(false), 5L))));
            }
            setEntries(entries);
            if (open) {
                open(owner);
            }
        }));
    }
}

/**
 * Base for menus that temporarily hold a player's items (sell, enchanting). Items are snapshotted to the
 * journal whenever they change and returned if the menu closes for any reason.
 */
abstract class EscrowMenu extends Menu {
    private final String op;
    private String journalId;
    protected Player viewer;

    protected EscrowMenu(IyhaCore plugin, String op) {
        super(plugin);
        this.op = op;
    }

    /** Items currently held inside the menu. */
    protected abstract List<ItemStack> heldItems();

    /** Clears every held item from the inventory (items are then owned by the caller). */
    protected abstract void clearHeld();

    protected final void snapshot() {
        if (viewer == null) {
            return;
        }
        List<ItemStack> held = heldItems();
        String payload = Journal.encode(held);
        if (payload.isEmpty()) {
            settle();
            return;
        }
        if (journalId == null) {
            journalId = plugin.journal().begin(op, viewer.getUniqueId(), payload);
        }
        plugin.journal().write(journalId, "SAVED", op, viewer.getUniqueId(), payload);
    }

    protected final void settle() {
        if (journalId != null && viewer != null) {
            plugin.journal().finish(journalId, "DONE", op, viewer.getUniqueId());
        }
        journalId = null;
    }

    @Override
    void onClose(Player player) {
        List<ItemStack> held = new ArrayList<>(heldItems());
        clearHeld();
        for (ItemStack item : held) {
            if (item != null && !item.getType().isAir()) {
                if (player.isOnline()) {
                    Give.giveOrClaim(plugin, player, item, op + "_RETURN");
                } else {
                    plugin.claims().createAsync(player.getUniqueId(), item, op + "_RETURN", null);
                }
            }
        }
        settle();
        viewer = null;
    }

    @Override
    void onOpen(Player player) {
        this.viewer = player;
    }

    protected final void afterClick() {
        Bukkit.getScheduler().runTask(plugin, this::onItemsChanged);
    }

    protected abstract void onItemsChanged();

    @Override
    void onClick(InventoryClickEvent event) {
        if (viewer == null && event.getWhoClicked() instanceof Player p) {
            viewer = p;
        }
        handleClick(event);
    }

    protected abstract void handleClick(InventoryClickEvent event);
}
