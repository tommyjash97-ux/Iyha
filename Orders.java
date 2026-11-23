package net.iyha.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Buy orders. The buyer's money is escrowed when the order is created. Sellers fill orders from a menu or
 * automatically when they /sell. Items fulfilled go to the buyer as claims.
 */
final class OrdersService {
    record Order(long id, UUID buyer, String buyerName, String itemKey, int total, int filled, long unit, long expiresAt) {
        int remaining() {
            return total - filled;
        }
    }

    record FillResult(int filled, long earned) {}

    enum CreateStatus { OK, CAP, INSUFFICIENT, FROZEN, INVALID, ERROR }

    private record Row(long id, String buyer, int total, int filled, long unit) {}

    private final IyhaCore plugin;
    private final Database db;

    OrdersService(IyhaCore plugin) {
        this.plugin = plugin;
        this.db = plugin.database();
    }

    // ------------------------------------------------------------------ create

    CompletableFuture<CreateStatus> create(Player player, Material material, int qty, long unit) {
        var cfg = plugin.getConfig();
        long minUnit = Math.max(1, Money.fromDouble(cfg.getDouble("orders.min-unit-price", 0.01)));
        long maxUnit = Money.fromDouble(cfg.getDouble("orders.max-unit-price", 1_000_000));
        int maxQty = cfg.getInt("orders.max-quantity", 10_000);
        if (qty < 1 || qty > maxQty || unit < minUnit || unit > maxUnit || material.isAir() || !material.isItem()
                || plugin.getConfig().getStringList("market.banned-materials").contains(material.name())) {
            return CompletableFuture.completedFuture(CreateStatus.INVALID);
        }
        long total;
        try {
            total = Math.multiplyExact(unit, (long) qty);
        } catch (ArithmeticException ex) {
            return CompletableFuture.completedFuture(CreateStatus.INVALID);
        }
        int cap = plugin.limits().get(player, "orders", cfg.getInt("orders.default-cap", 10));
        long expires = System.currentTimeMillis() + cfg.getLong("orders.expiry-hours", 72) * 3_600_000L;
        String owner = player.getUniqueId().toString();
        String key = material.getKey().getKey();
        String name = player.getName();
        List<EconomyService.Posting> posts = new ArrayList<>();
        long totalCents = total;
        return db.supplySerial(c -> Database.tx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM orders WHERE buyer = ? AND status = 'ACTIVE'")) {
                ps.setString(1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= cap) {
                        return CreateStatus.CAP;
                    }
                }
            }
            EconomyService.Applied applied = plugin.economy().applyLedger(conn, owner,
                    Map.of(EconomyService.ESCROW, totalCents), "ORDER_CREATE", key, name, null, false);
            if (applied.status() == EconomyService.Status.INSUFFICIENT) {
                return CreateStatus.INSUFFICIENT;
            }
            if (applied.status() == EconomyService.Status.FROZEN) {
                return CreateStatus.FROZEN;
            }
            if (applied.status() != EconomyService.Status.OK) {
                return CreateStatus.ERROR;
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO orders(buyer, item_key, qty_total, qty_filled, "
                    + "unit_price, status, version, created_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, owner);
                ps.setString(2, key);
                ps.setInt(3, qty);
                ps.setInt(4, 0);
                ps.setLong(5, unit);
                ps.setString(6, "ACTIVE");
                ps.setInt(7, 0);
                ps.setLong(8, System.currentTimeMillis());
                ps.setLong(9, expires);
                ps.executeUpdate();
            }
            posts.addAll(applied.postings());
            return CreateStatus.OK;
        }, s -> s == CreateStatus.OK)).thenApply(status -> {
            if (status == CreateStatus.OK) {
                plugin.economy().publish(posts);
            }
            return status;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Order create failed", ex);
            return CreateStatus.ERROR;
        });
    }

    // ------------------------------------------------------------------ filling (inside caller's transaction)

    private List<Row> loadRows(Connection conn, String sql, Object... params) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getLong(5)));
                }
            }
        }
        return rows;
    }

    private FillResult applyFills(Connection conn, UUID seller, String sellerName, Material material, List<Row> rows,
                                  int qty, List<EconomyService.Posting> posts) throws SQLException {
        int left = qty;
        int filled = 0;
        long earned = 0;
        long now = System.currentTimeMillis();
        for (Row row : rows) {
            if (left <= 0) {
                break;
            }
            int take = Math.min(left, row.total() - row.filled());
            if (take <= 0) {
                continue;
            }
            long pay = Math.multiplyExact(row.unit(), (long) take);
            EconomyService.Applied applied = plugin.economy().applyLedger(conn, EconomyService.ESCROW,
                    Map.of(seller.toString(), pay), "ORDER_FILL", "O" + row.id(), sellerName, null, false);
            if (applied.status() != EconomyService.Status.OK) {
                break;
            }
            int newFilled = row.filled() + take;
            try (PreparedStatement ps = conn.prepareStatement("UPDATE orders SET qty_filled = ?, status = ?, "
                    + "version = version + 1 WHERE order_id = ?")) {
                ps.setInt(1, newFilled);
                ps.setString(2, newFilled >= row.total() ? "COMPLETED" : "ACTIVE");
                ps.setLong(3, row.id());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO order_fills(order_id, seller, quantity, "
                    + "unit_price, tx_id, created_at) VALUES (?,?,?,?,?,?)")) {
                ps.setLong(1, row.id());
                ps.setString(2, seller.toString());
                ps.setInt(3, take);
                ps.setLong(4, row.unit());
                ps.setLong(5, applied.txId());
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            for (ItemStack stack : Give.stacksOf(material, take)) {
                ClaimService.insertClaim(conn, row.buyer(), "ORDER_FILL", "O" + row.id(), stack, null, now);
            }
            posts.addAll(applied.postings());
            earned += pay;
            filled += take;
            left -= take;
        }
        return new FillResult(filled, earned);
    }

    /** Fills the best-paying matching orders (used by /sell). Caller owns the transaction. */
    FillResult fillInTx(Connection conn, UUID seller, String sellerName, String itemKey, int qty,
                        List<EconomyService.Posting> posts) throws SQLException {
        Material material = Material.matchMaterial(itemKey);
        if (material == null || qty <= 0) {
            return new FillResult(0, 0);
        }
        List<Row> rows = loadRows(conn, "SELECT order_id, buyer, qty_total, qty_filled, unit_price FROM orders "
                + "WHERE status = 'ACTIVE' AND item_key = ? AND expires_at > ? AND buyer <> ? "
                + "ORDER BY unit_price DESC, order_id ASC LIMIT 20" + db.forUpdate(),
                itemKey, System.currentTimeMillis(), seller.toString());
        return applyFills(conn, seller, sellerName, material, rows, qty, posts);
    }

    /** Menu path: the player sells matching plain items from their inventory into one specific order. */
    void fillOrder(Player seller, Order order) {
        Material material = Material.matchMaterial(order.itemKey());
        if (material == null) {
            return;
        }
        int have = 0;
        for (ItemStack it : seller.getInventory().getStorageContents()) {
            if (it != null && it.getType() == material && !it.hasItemMeta()) {
                have += it.getAmount();
            }
        }
        int take = Math.min(have, order.remaining());
        if (take <= 0) {
            plugin.lang().send(seller, "orders-no-items", "item", order.itemKey());
            return;
        }
        int toRemove = take;
        ItemStack[] contents = seller.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && toRemove > 0; i++) {
            ItemStack it = contents[i];
            if (it != null && it.getType() == material && !it.hasItemMeta()) {
                int n = Math.min(toRemove, it.getAmount());
                if (n >= it.getAmount()) {
                    seller.getInventory().setItem(i, null);
                } else {
                    it.setAmount(it.getAmount() - n);
                }
                toRemove -= n;
            }
        }
        seller.saveData();
        List<EconomyService.Posting> posts = new ArrayList<>();
        UUID id = seller.getUniqueId();
        String name = seller.getName();
        int amount = take;
        db.supplySerial(c -> Database.tx(c, conn -> {
            List<Row> rows = loadRows(conn, "SELECT order_id, buyer, qty_total, qty_filled, unit_price FROM orders "
                    + "WHERE order_id = ? AND status = 'ACTIVE' AND expires_at > ? AND buyer <> ?" + db.forUpdate(),
                    order.id(), System.currentTimeMillis(), id.toString());
            return applyFills(conn, id, name, material, rows, amount, posts);
        }, r -> r.filled() > 0)).thenAccept(result -> plugin.sync(() -> {
            plugin.economy().publish(posts);
            int back = amount - result.filled();
            if (back > 0) {
                for (ItemStack stack : Give.stacksOf(material, back)) {
                    Give.giveOrClaim(plugin, seller, stack, "ORDER_RETURN");
                }
            }
            if (result.filled() > 0) {
                plugin.lang().send(seller, "orders-filled", "qty", String.valueOf(result.filled()),
                        "amount", plugin.economy().format(result.earned()));
            } else {
                plugin.lang().send(seller, "orders-gone");
            }
        })).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Order fill failed", ex);
            plugin.sync(() -> {
                for (ItemStack stack : Give.stacksOf(material, amount)) {
                    Give.giveOrClaim(plugin, seller, stack, "ORDER_RETURN");
                }
                plugin.lang().send(seller, "internal-error");
            });
            return null;
        });
    }

    // ------------------------------------------------------------------ cancel and expiry

    /** Refunds the unfilled escrow and closes the order. {@code newStatus} is CANCELLED or EXPIRED. */
    private boolean closeInTx(Connection conn, long orderId, String requiredBuyer, String newStatus,
                              List<EconomyService.Posting> posts, List<UUID> notify) throws SQLException {
        String sql = "SELECT order_id, buyer, qty_total, qty_filled, unit_price FROM orders WHERE order_id = ? "
                + "AND status = 'ACTIVE'" + (requiredBuyer != null ? " AND buyer = ?" : "") + db.forUpdate();
        List<Row> rows = requiredBuyer != null ? loadRows(conn, sql, orderId, requiredBuyer) : loadRows(conn, sql, orderId);
        if (rows.isEmpty()) {
            return false;
        }
        Row row = rows.get(0);
        long refund = Math.multiplyExact(row.unit(), (long) (row.total() - row.filled()));
        if (refund > 0) {
            EconomyService.Applied applied = plugin.economy().applyLedger(conn, EconomyService.ESCROW,
                    Map.of(row.buyer(), refund), "ORDER_REFUND", "O" + orderId, "system", null, true);
            if (applied.status() != EconomyService.Status.OK) {
                return false;
            }
            posts.addAll(applied.postings());
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE orders SET status = ?, version = version + 1 WHERE order_id = ? AND status = 'ACTIVE'")) {
            ps.setString(1, newStatus);
            ps.setLong(2, orderId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Order changed during close");
            }
        }
        notify.add(UUID.fromString(row.buyer()));
        return true;
    }

    void cancel(Player player, long orderId, boolean staff) {
        List<EconomyService.Posting> posts = new ArrayList<>();
        List<UUID> notify = new ArrayList<>();
        String requiredBuyer = staff ? null : player.getUniqueId().toString();
        db.supplySerial(c -> Database.tx(c, conn -> closeInTx(conn, orderId, requiredBuyer, "CANCELLED", posts, notify),
                ok -> ok)).thenAccept(ok -> plugin.sync(() -> {
            if (ok) {
                plugin.economy().publish(posts);
                plugin.lang().send(player, "orders-cancelled");
            } else {
                plugin.lang().send(player, "orders-gone");
            }
        })).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Order cancel failed", ex);
            plugin.sync(() -> plugin.lang().send(player, "internal-error"));
            return null;
        });
    }

    /** Called every minute: closes expired orders and refunds buyers. */
    void sweepExpired() {
        List<EconomyService.Posting> posts = new ArrayList<>();
        List<UUID> notify = new ArrayList<>();
        db.supplySerial(c -> Database.tx(c, conn -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT order_id FROM orders WHERE status = 'ACTIVE' AND expires_at <= ? LIMIT 50")) {
                ps.setLong(1, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            for (long id : ids) {
                closeInTx(conn, id, null, "EXPIRED", posts, notify);
            }
            return ids.size();
        }, n -> n > 0)).thenAccept(n -> {
            if (n > 0) {
                plugin.sync(() -> {
                    plugin.economy().publish(posts);
                    Set<UUID> seen = new HashSet<>(notify);
                    for (UUID id : seen) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) {
                            plugin.lang().send(p, "orders-expired");
                        }
                    }
                });
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Order sweep failed", ex);
            return null;
        });
    }

    // ------------------------------------------------------------------ queries

    CompletableFuture<List<Order>> active(String search, UUID buyerFilter) {
        return db.supplyIo(c -> {
            StringBuilder sql = new StringBuilder("SELECT o.order_id, o.buyer, p.name, o.item_key, o.qty_total, "
                    + "o.qty_filled, o.unit_price, o.expires_at FROM orders o LEFT JOIN players p ON p.uuid = o.buyer "
                    + "WHERE o.status = 'ACTIVE' AND o.expires_at > ?");
            List<Object> params = new ArrayList<>();
            params.add(System.currentTimeMillis());
            if (search != null) {
                sql.append(" AND o.item_key LIKE ?");
                params.add("%" + search.toLowerCase(Locale.ROOT).replace(' ', '_').replace("%", "") + "%");
            }
            if (buyerFilter != null) {
                sql.append(" AND o.buyer = ?");
                params.add(buyerFilter.toString());
            }
            sql.append(" ORDER BY o.unit_price DESC, o.order_id ASC LIMIT 300");
            List<Order> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(3);
                        list.add(new Order(rs.getLong(1), UUID.fromString(rs.getString(2)), name == null ? "?" : name,
                                rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getLong(7), rs.getLong(8)));
                    }
                }
            }
            return list;
        });
    }
}

/** Browse all orders (sell into one) or your own (cancel one). */
final class OrdersMenu extends PagedMenu {
    private final boolean mine;
    private final String titleText;

    private OrdersMenu(IyhaCore plugin, boolean mine, String titleText) {
        super(plugin);
        this.mine = mine;
        this.titleText = titleText;
    }

    static void open(IyhaCore plugin, Player player, String search, boolean mine) {
        OrdersMenu menu = new OrdersMenu(plugin, mine, mine ? "<gold><bold>My orders" : "<gold><bold>Buy orders");
        plugin.orders().active(search, mine ? player.getUniqueId() : null).thenAccept(list -> plugin.sync(() -> {
            List<Entry> entries = new ArrayList<>();
            for (OrdersService.Order o : list) {
                Material mat = Material.matchMaterial(o.itemKey());
                ItemStack icon = Ui.button(mat == null ? Material.BARRIER : mat, "<white>" + o.itemKey().replace('_', ' '),
                        "<gray>Buyer: <white>" + o.buyerName(),
                        "<gray>Pays: <green>" + plugin.economy().format(o.unit()) + "<gray> each",
                        "<gray>Wanted: <white>" + o.remaining() + "<gray> of " + o.total(),
                        "<yellow>" + (mine ? "Click to cancel" : "Click to sell your items into this order"));
                entries.add(new Entry(icon, ev -> menu.clicked(player, o)));
            }
            menu.setEntries(entries);
            menu.open(player);
        })).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Orders menu failed", ex);
            return null;
        });
    }

    private void clicked(Player player, OrdersService.Order order) {
        Material mat = Material.matchMaterial(order.itemKey());
        ItemStack shown = Ui.button(mat == null ? Material.BARRIER : mat,
                (mine ? "<red>Cancel order" : "<green>Fill order") + " <gray>#" + order.id(),
                "<gray>" + order.remaining() + " x " + order.itemKey() + " at " + plugin.economy().format(order.unit()));
        new ConfirmMenu(plugin, mine ? "<red>Cancel this order?" : "<green>Sell into this order?", shown, () -> {
            player.closeInventory();
            if (mine) {
                plugin.orders().cancel(player, order.id(), false);
            } else {
                plugin.orders().fillOrder(player, order);
            }
        }, () -> player.closeInventory()).open(player);
    }

    @Override
    protected Component title() {
        return Ui.c(titleText);
    }
}

/** /orders ... */
final class OrdersCmd extends Cmd {
    OrdersCmd(IyhaCore plugin) {
        super(plugin, "orders", "iyha.orders.use", 1000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        if (args.length == 0) {
            OrdersMenu.open(plugin, player, null, false);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "my" -> OrdersMenu.open(plugin, player, null, true);
            case "collect" -> ClaimsMenu.open(plugin, player, "ORDER_FILL", "<gold><bold>Order deliveries");
            case "search" -> {
                if (args.length < 2) {
                    plugin.lang().send(player, "usage", "usage", "/orders search <text>");
                } else {
                    OrdersMenu.open(plugin, player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)), false);
                }
            }
            case "create" -> create(player, args);
            case "cancel" -> {
                if (args.length < 2) {
                    plugin.lang().send(player, "usage", "usage", "/orders cancel <id>");
                    return;
                }
                try {
                    plugin.orders().cancel(player, Long.parseLong(args[1]), false);
                } catch (NumberFormatException ex) {
                    plugin.lang().send(player, "invalid-amount");
                }
            }
            case "remove" -> {
                if (!player.hasPermission("iyha.admin.orders") || args.length < 2) {
                    plugin.lang().send(player, "no-permission");
                    return;
                }
                try {
                    plugin.orders().cancel(player, Long.parseLong(args[1]), true);
                    plugin.audit().log(player.getName(), "ORDER_REMOVE", args[1], null);
                } catch (NumberFormatException ex) {
                    plugin.lang().send(player, "invalid-amount");
                }
            }
            default -> plugin.lang().send(player, "usage", "usage", "/orders [my|collect|search|create|cancel]");
        }
    }

    private void create(Player player, String[] args) {
        if (!player.hasPermission("iyha.orders.create")) {
            plugin.lang().send(player, "no-permission");
            return;
        }
        if (args.length != 4) {
            plugin.lang().send(player, "usage", "usage", "/orders create <item> <quantity> <price each>");
            return;
        }
        Material mat = Material.matchMaterial(args[1]);
        if (mat == null) {
            plugin.lang().send(player, "orders-bad-item");
            return;
        }
        int qty;
        try {
            qty = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            plugin.lang().send(player, "invalid-amount");
            return;
        }
        var unit = Money.parse(args[3]);
        if (unit.isEmpty()) {
            plugin.lang().send(player, "invalid-amount");
            return;
        }
        guard(plugin.orders().create(player, mat, qty, unit.getAsLong()).thenAccept(status -> plugin.sync(() -> {
            switch (status) {
                case OK -> plugin.lang().send(player, "orders-created", "qty", String.valueOf(qty),
                        "item", mat.getKey().getKey(), "unit", plugin.economy().format(unit.getAsLong()));
                case CAP -> plugin.lang().send(player, "orders-cap");
                case INSUFFICIENT -> plugin.lang().send(player, "pay-insufficient", "balance",
                        plugin.economy().format(plugin.economy().cachedBalance(player.getUniqueId())));
                case FROZEN -> plugin.lang().send(player, "economy-frozen");
                case INVALID -> plugin.lang().send(player, "orders-invalid");
                default -> plugin.lang().send(player, "internal-error");
            }
        })), player);
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("my", "collect", "search", "create", "cancel"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return filter(java.util.Arrays.stream(Material.values()).filter(Material::isItem)
                    .map(m -> m.getKey().getKey()).toList(), args[1]).stream().limit(40).toList();
        }
        return List.of();
    }
}
