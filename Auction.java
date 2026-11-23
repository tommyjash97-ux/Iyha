package net.iyha.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Auction House. Items are escrowed in the database. A sale is one transaction: payment, fee, listing state
 * and the buyer's claim row are committed together, so an item can only ever be sold once.
 */
final class AuctionService {
    enum Sort { PRICE_ASC, PRICE_DESC, NEWEST, ENDING, ALPHA, UNIT_PRICE }

    record Query(String category, String search, UUID seller, Sort sort) {}

    record Listing(long id, UUID seller, String sellerName, ItemStack item, String itemKey, int quantity,
                   long price, long expiresAt, int version) {}

    record Page(List<Listing> rows, int total) {}

    enum ListStatus { OK, CAP, ERROR }

    enum BuyStatus { OK, GONE, SELF, INSUFFICIENT, FROZEN, ERROR }

    private record BuyOutcome(BuyStatus status, List<EconomyService.Posting> postings, UUID seller, int quantity,
                              String itemKey, long price) {}

    private final IyhaCore plugin;
    private final Database db;

    AuctionService(IyhaCore plugin) {
        this.plugin = plugin;
        this.db = plugin.database();
    }

    // ------------------------------------------------------------------ listing

    void list(Player player, long price, int requestedAmount) {
        var cfg = plugin.getConfig();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.itemRules().allowed(hand)) {
            plugin.lang().send(player, "ah-bad-item");
            return;
        }
        long min = Math.max(1, Money.fromDouble(cfg.getDouble("auction.min-price", 1)));
        long max = Money.fromDouble(cfg.getDouble("auction.max-price", 1_000_000_000));
        if (price < min || price > max) {
            plugin.lang().send(player, "ah-bad-price", "min", plugin.economy().format(min), "max", plugin.economy().format(max));
            return;
        }
        int amount = Math.max(1, Math.min(requestedAmount, hand.getAmount()));
        int cap = plugin.limits().get(player, "ah-listings", cfg.getInt("auction.default-cap", 25));
        ItemStack listing = hand.clone();
        listing.setAmount(amount);
        String itemKey = plugin.itemKeys().keyOf(listing);
        String category = Categories.of(listing, plugin.itemKeys());
        byte[] bytes = listing.serializeAsBytes();
        UUID id = player.getUniqueId();
        String payload = Journal.encode(List.of(listing));
        int feeBp = (int) Math.round(cfg.getDouble("auction.fee-percent", 5.0) * 100);
        long now = System.currentTimeMillis();
        long expires = now + cfg.getLong("auction.expiry-hours", 48) * 3_600_000L;

        String jid = plugin.journal().begin("AH_LIST", id, payload);
        if (amount >= hand.getAmount()) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - amount);
        }
        player.saveData();
        plugin.journal().write(jid, "SAVED", "AH_LIST", id, payload);

        db.supplySerial(c -> Database.tx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM ah_listings WHERE seller = ? AND status = 'ACTIVE'")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= cap) {
                        return ListStatus.CAP;
                    }
                }
            }
            long listingId;
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ah_listings(seller, item_bytes, item_key, "
                    + "category, quantity, price, fee_bp, status, version, created_at, expires_at, jref) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, id.toString());
                ps.setBytes(2, bytes);
                ps.setString(3, itemKey);
                ps.setString(4, category);
                ps.setInt(5, amount);
                ps.setLong(6, price);
                ps.setInt(7, feeBp);
                ps.setString(8, "ACTIVE");
                ps.setInt(9, 0);
                ps.setLong(10, now);
                ps.setLong(11, expires);
                ps.setString(12, jid);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    listingId = keys.next() ? keys.getLong(1) : 0;
                }
            }
            history(conn, listingId, "LISTED", id.toString(), null, itemKey, amount, price, now);
            return ListStatus.OK;
        }, s -> s == ListStatus.OK)).thenAccept(status -> plugin.sync(() -> {
            if (status == ListStatus.OK) {
                plugin.journal().finish(jid, "DONE", "AH_LIST", id);
                plugin.lang().send(player, "ah-listed", "qty", String.valueOf(amount), "item", itemKey,
                        "price", plugin.economy().format(price));
            } else {
                plugin.journal().finish(jid, "ABORTED", "AH_LIST", id);
                Give.giveOrClaim(plugin, player, listing, "AH_RETURN");
                plugin.lang().send(player, status == ListStatus.CAP ? "ah-cap" : "internal-error");
            }
        })).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Listing failed", ex);
            plugin.sync(() -> {
                plugin.journal().finish(jid, "ABORTED", "AH_LIST", id);
                Give.giveOrClaim(plugin, player, listing, "AH_RETURN");
                plugin.lang().send(player, "internal-error");
            });
            return null;
        });
    }

    private void history(Connection c, long listingId, String event, String actor, String other, String itemKey,
                         int qty, long amount, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO ah_history(listing_id, event, actor, other, "
                + "item_key, quantity, amount, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, listingId);
            ps.setString(2, event);
            ps.setString(3, actor);
            ps.setString(4, other);
            ps.setString(5, itemKey);
            ps.setInt(6, qty);
            ps.setLong(7, amount);
            ps.setLong(8, now);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ browsing

    CompletableFuture<Page> page(Query q, int page, int pageSize) {
        return db.supplyIo(c -> {
            StringBuilder where = new StringBuilder(" WHERE l.status = 'ACTIVE' AND l.expires_at > ?");
            List<Object> params = new ArrayList<>();
            params.add(System.currentTimeMillis());
            if (q.category() != null) {
                where.append(" AND l.category = ?");
                params.add(q.category());
            }
            if (q.search() != null) {
                where.append(" AND l.item_key LIKE ?");
                params.add("%" + q.search().toLowerCase(Locale.ROOT).replace(' ', '_').replace("%", "") + "%");
            }
            if (q.seller() != null) {
                where.append(" AND l.seller = ?");
                params.add(q.seller().toString());
            }
            int total;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ah_listings l" + where)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getInt(1) : 0;
                }
            }
            String order = switch (q.sort()) {
                case PRICE_ASC -> "l.price ASC";
                case PRICE_DESC -> "l.price DESC";
                case NEWEST -> "l.created_at DESC";
                case ENDING -> "l.expires_at ASC";
                case ALPHA -> "l.item_key ASC, l.price ASC";
                case UNIT_PRICE -> "(l.price * 1.0 / l.quantity) ASC";
            };
            List<Listing> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT l.listing_id, l.seller, p.name, l.item_bytes, "
                    + "l.item_key, l.quantity, l.price, l.expires_at, l.version FROM ah_listings l "
                    + "LEFT JOIN players p ON p.uuid = l.seller" + where + " ORDER BY " + order + " LIMIT ? OFFSET ?")) {
                int i = 1;
                for (Object param : params) {
                    ps.setObject(i++, param);
                }
                ps.setInt(i++, pageSize);
                ps.setInt(i, page * pageSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            String name = rs.getString(3);
                            rows.add(new Listing(rs.getLong(1), UUID.fromString(rs.getString(2)), name == null ? "?" : name,
                                    ItemStack.deserializeBytes(rs.getBytes(4)), rs.getString(5), rs.getInt(6),
                                    rs.getLong(7), rs.getLong(8), rs.getInt(9)));
                        } catch (Exception ex) {
                            plugin.getLogger().warning("Unreadable listing " + rs.getLong(1));
                        }
                    }
                }
            }
            return new Page(rows, total);
        });
    }

    // ------------------------------------------------------------------ buying

    void buy(Player buyer, Listing listing, Runnable afterwards) {
        UUID buyerId = buyer.getUniqueId();
        String buyerKey = buyerId.toString();
        String buyerName = buyer.getName();
        List<EconomyService.Posting> posts = new ArrayList<>();
        db.supplySerial(c -> Database.tx(c, conn -> {
            long price;
            int qty;
            int feeBp;
            String sellerKey;
            String itemKey;
            byte[] bytes;
            try (PreparedStatement ps = conn.prepareStatement("SELECT seller, price, quantity, fee_bp, item_key, item_bytes, "
                    + "status, version, expires_at FROM ah_listings WHERE listing_id = ?" + db.forUpdate())) {
                ps.setLong(1, listing.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !"ACTIVE".equals(rs.getString(7)) || rs.getInt(8) != listing.version()
                            || rs.getLong(9) <= System.currentTimeMillis()) {
                        return new BuyOutcome(BuyStatus.GONE, List.of(), null, 0, null, 0);
                    }
                    sellerKey = rs.getString(1);
                    price = rs.getLong(2);
                    qty = rs.getInt(3);
                    feeBp = rs.getInt(4);
                    itemKey = rs.getString(5);
                    bytes = rs.getBytes(6);
                }
            }
            if (sellerKey.equals(buyerKey)) {
                return new BuyOutcome(BuyStatus.SELF, List.of(), null, 0, null, 0);
            }
            long fee = price * feeBp / 10_000L;
            Map<String, Long> credits = new LinkedHashMap<>();
            credits.put(sellerKey, price - fee);
            if (fee > 0) {
                credits.put(EconomyService.FEES, fee);
            }
            EconomyService.Applied applied = plugin.economy().applyLedger(conn, buyerKey, credits, "AH_BUY",
                    "L" + listing.id(), buyerName, "AH:" + listing.id(), false);
            if (applied.status() != EconomyService.Status.OK) {
                BuyStatus s = switch (applied.status()) {
                    case INSUFFICIENT -> BuyStatus.INSUFFICIENT;
                    case FROZEN -> BuyStatus.FROZEN;
                    case DUPLICATE -> BuyStatus.GONE;
                    default -> BuyStatus.ERROR;
                };
                return new BuyOutcome(s, List.of(), null, 0, null, 0);
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement("UPDATE ah_listings SET status = 'SOLD', buyer = ?, "
                    + "sold_at = ?, tx_id = ?, version = version + 1 WHERE listing_id = ? AND status = 'ACTIVE'")) {
                ps.setString(1, buyerKey);
                ps.setLong(2, now);
                ps.setLong(3, applied.txId());
                ps.setLong(4, listing.id());
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("Listing changed during purchase");
                }
            }
            ClaimService.insertClaim(conn, buyerKey, "AH_BUY", String.valueOf(listing.id()), bytes, qty,
                    "CLAIM:AH:" + listing.id(), now);
            history(conn, listing.id(), "SOLD", sellerKey, buyerKey, itemKey, qty, price, now);
            posts.addAll(applied.postings());
            return new BuyOutcome(BuyStatus.OK, applied.postings(), UUID.fromString(sellerKey), qty, itemKey, price);
        }, o -> o.status() == BuyStatus.OK)).thenAccept(outcome -> plugin.sync(() -> {
            switch (outcome.status()) {
                case OK -> {
                    plugin.economy().publish(posts);
                    plugin.lang().send(buyer, "ah-bought", "qty", String.valueOf(outcome.quantity()),
                            "item", outcome.itemKey(), "price", plugin.economy().format(outcome.price()));
                    Player seller = Bukkit.getPlayer(outcome.seller());
                    if (seller != null) {
                        plugin.lang().send(seller, "ah-sold-notice", "item", outcome.itemKey(),
                                "buyer", buyer.getName(), "price", plugin.economy().format(outcome.price()));
                    }
                    plugin.claims().deliverAll(buyer);
                }
                case GONE -> plugin.lang().send(buyer, "ah-gone");
                case SELF -> plugin.lang().send(buyer, "ah-own");
                case INSUFFICIENT -> plugin.lang().send(buyer, "pay-insufficient", "balance",
                        plugin.economy().format(plugin.economy().cachedBalance(buyerId)));
                case FROZEN -> plugin.lang().send(buyer, "economy-frozen");
                default -> plugin.lang().send(buyer, "internal-error");
            }
            if (afterwards != null) {
                afterwards.run();
            }
        })).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Purchase failed", ex);
            plugin.sync(() -> plugin.lang().send(buyer, "internal-error"));
            return null;
        });
    }

    // ------------------------------------------------------------------ cancel, expiry, admin remove

    /** Closes an ACTIVE listing and gives the item to its seller as a claim. Returns false if it was no longer active. */
    private boolean closeInTx(Connection conn, long listingId, String requiredSeller, String newStatus, String claimSource)
            throws SQLException {
        String seller;
        byte[] bytes;
        int qty;
        String itemKey;
        try (PreparedStatement ps = conn.prepareStatement("SELECT seller, item_bytes, quantity, item_key FROM ah_listings "
                + "WHERE listing_id = ? AND status = 'ACTIVE'" + (requiredSeller != null ? " AND seller = ?" : "")
                + db.forUpdate())) {
            ps.setLong(1, listingId);
            if (requiredSeller != null) {
                ps.setString(2, requiredSeller);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                seller = rs.getString(1);
                bytes = rs.getBytes(2);
                qty = rs.getInt(3);
                itemKey = rs.getString(4);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ah_listings SET status = ?, version = version + 1 WHERE listing_id = ? AND status = 'ACTIVE'")) {
            ps.setString(1, newStatus);
            ps.setLong(2, listingId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Listing changed while closing");
            }
        }
        long now = System.currentTimeMillis();
        ClaimService.insertClaim(conn, seller, claimSource, String.valueOf(listingId), bytes, qty,
                "CLAIM:AH:" + listingId, now);
        history(conn, listingId, newStatus, seller, null, itemKey, qty, 0, now);
        return true;
    }

    void cancel(Player player, long listingId, boolean staff, String reason) {
        String required = staff ? null : player.getUniqueId().toString();
        db.supplySerial(c -> Database.tx(c, conn -> closeInTx(conn, listingId, required, "CANCELLED", "AH_EXPIRED"),
                ok -> ok)).thenAccept(ok -> plugin.sync(() -> {
            if (ok) {
                plugin.lang().send(player, "ah-cancelled");
                if (staff) {
                    plugin.audit().log(player.getName(), "AH_REMOVE", String.valueOf(listingId), reason);
                } else {
                    plugin.claims().deliverAll(player);
                }
            } else {
                plugin.lang().send(player, "ah-gone");
            }
        })).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Cancel failed", ex);
            plugin.sync(() -> plugin.lang().send(player, "internal-error"));
            return null;
        });
    }

    /** Every minute: expired listings become claims (/ah expired). */
    void sweepExpired() {
        Set<UUID> sellers = new HashSet<>();
        db.supplySerial(c -> Database.tx(c, conn -> {
            List<long[]> ids = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT listing_id FROM ah_listings WHERE status = 'ACTIVE' AND expires_at <= ? LIMIT 50")) {
                ps.setLong(1, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(new long[]{rs.getLong(1)});
                    }
                }
            }
            for (long[] id : ids) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT seller FROM ah_listings WHERE listing_id = ?")) {
                    ps.setLong(1, id[0]);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            sellers.add(UUID.fromString(rs.getString(1)));
                        }
                    }
                }
                closeInTx(conn, id[0], null, "EXPIRED", "AH_EXPIRED");
            }
            return ids.size();
        }, n -> n > 0)).thenAccept(n -> {
            if (n > 0) {
                plugin.sync(() -> {
                    for (UUID id : sellers) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) {
                            plugin.lang().send(p, "ah-expired-notice");
                        }
                    }
                });
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Auction sweep failed", ex);
            return null;
        });
    }

    CompletableFuture<List<String[]>> soldHistory(UUID seller) {
        return db.supplyIo(c -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT h.item_key, h.quantity, h.amount, p.name FROM ah_history h "
                    + "LEFT JOIN players p ON p.uuid = h.other WHERE h.actor = ? AND h.event = 'SOLD' "
                    + "ORDER BY h.id DESC LIMIT 10")) {
                ps.setString(1, seller.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(4);
                        rows.add(new String[]{rs.getString(1), String.valueOf(rs.getInt(2)),
                                plugin.economy().format(rs.getLong(3)), name == null ? "?" : name});
                    }
                }
            }
            return rows;
        });
    }

    // ------------------------------------------------------------------ crash recovery

    /** Startup: listings whose journal entry reached SAVED but never got a database row are returned to the seller. */
    void recoverJournal() {
        for (Journal.Entry e : plugin.journal().unresolved()) {
            if (!e.op().equals("AH_LIST")) {
                continue;
            }
            UUID who = UUID.fromString(e.uuid());
            if (e.state().equals("STARTED")) {
                plugin.getLogger().warning("Journal " + e.id() + ": listing never started removing the item. Closed.");
                plugin.journal().finish(e.id(), "ABORTED", e.op(), who);
                continue;
            }
            List<ItemStack> items = Journal.decode(e.payload());
            db.supplySerial(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM ah_listings WHERE jref = ?")) {
                    ps.setString(1, e.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return true;
                        }
                    }
                }
                long now = System.currentTimeMillis();
                for (ItemStack item : items) {
                    ClaimService.insertClaim(c, e.uuid(), "AH_RESTORE", e.id(), item, "RESTORE:" + e.id(), now);
                }
                return false;
            }).thenAccept(existed -> {
                plugin.journal().finish(e.id(), "DONE", e.op(), who);
                plugin.getLogger().warning("Journal " + e.id() + ": " + (existed ? "listing exists, nothing to restore."
                        : "item restored to " + e.uuid() + " as a claim."));
            }).exceptionally(ex -> {
                plugin.getLogger().log(Level.SEVERE, "Journal recovery failed for " + e.id(), ex);
                return null;
            });
        }
    }
}

/** Browse menu. Server-side paging: 45 listings per page. */
final class AuctionMenu extends Menu {
    private static final List<AuctionService.Sort> SORTS = List.of(AuctionService.Sort.values());

    private final Player player;
    private AuctionService.Query query;
    private int page = 0;
    private int total = 0;
    private List<AuctionService.Listing> rows = List.of();

    private AuctionMenu(IyhaCore plugin, Player player, AuctionService.Query query) {
        super(plugin);
        this.player = player;
        this.query = query;
    }

    static void open(IyhaCore plugin, Player player, AuctionService.Query query) {
        AuctionMenu menu = new AuctionMenu(plugin, player, query);
        menu.load(true);
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>Auction House");
    }

    @Override
    protected int size() {
        return 54;
    }

    private void load(boolean open) {
        plugin.auctions().page(query, page, 45).thenAccept(result -> plugin.sync(() -> {
            rows = result.rows();
            total = result.total();
            if (open) {
                open(player);
            } else {
                render();
            }
        })).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Auction menu failed", ex);
            plugin.sync(() -> plugin.lang().send(player, "internal-error"));
            return null;
        });
    }

    @Override
    protected void build() {
        render();
    }

    private int maxPage() {
        return Math.max(0, (total - 1) / 45);
    }

    private void render() {
        Inventory inv = getInventory();
        if (inv == null) {
            return;
        }
        inv.clear();
        long now = System.currentTimeMillis();
        for (int i = 0; i < rows.size() && i < 45; i++) {
            AuctionService.Listing l = rows.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("<gray>Price: <green>" + plugin.economy().format(l.price()));
            lore.add("<gray>Seller: <white>" + l.sellerName());
            lore.add("<gray>Expires in: <white>" + timeLeft(l.expiresAt() - now));
            lore.add(l.seller().equals(player.getUniqueId()) ? "<yellow>Your listing. Click to cancel" : "<yellow>Click to buy");
            inv.setItem(i, Ui.withLore(l.item(), lore));
        }
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, Ui.filler());
        }
        if (page > 0) {
            inv.setItem(45, Ui.button(Material.ARROW, "<yellow>Previous page"));
        }
        inv.setItem(46, Ui.button(Material.HOPPER, "<aqua>Sort: <white>" + query.sort().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                "<gray>Click to change"));
        inv.setItem(47, Ui.button(Material.CHEST, "<aqua>Category: <white>" + (query.category() == null ? "all" : query.category()),
                "<gray>Click to change"));
        inv.setItem(48, Ui.button(Material.COMPASS, "<aqua>Search" + (query.search() == null ? "" : ": <white>" + query.search()),
                "<gray>Click, then type in chat"));
        inv.setItem(49, Ui.button(Material.SUNFLOWER, "<green>Refresh", "<gray>Page " + (page + 1) + " of " + (maxPage() + 1)));
        inv.setItem(50, Ui.button(Material.WRITABLE_BOOK, "<yellow>My listings"));
        inv.setItem(51, Ui.button(Material.BUNDLE, "<yellow>Expired items"));
        inv.setItem(52, Ui.button(Material.GOLD_NUGGET, "<yellow>Recently sold"));
        if (page < maxPage()) {
            inv.setItem(53, Ui.button(Material.ARROW, "<yellow>Next page"));
        }
    }

    static String timeLeft(long ms) {
        if (ms <= 0) {
            return "now";
        }
        long minutes = ms / 60_000L;
        if (minutes >= 60) {
            return (minutes / 60) + "h " + (minutes % 60) + "m";
        }
        return Math.max(1, minutes) + "m";
    }

    @Override
    void onClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= 54) {
            return;
        }
        if (raw < 45) {
            if (raw < rows.size()) {
                clickedListing(rows.get(raw));
            }
            return;
        }
        switch (raw) {
            case 45 -> {
                if (page > 0) {
                    page--;
                    load(false);
                }
            }
            case 53 -> {
                if (page < maxPage()) {
                    page++;
                    load(false);
                }
            }
            case 46 -> {
                int next = (SORTS.indexOf(query.sort()) + 1) % SORTS.size();
                query = new AuctionService.Query(query.category(), query.search(), query.seller(), SORTS.get(next));
                page = 0;
                load(false);
            }
            case 47 -> {
                List<String> cats = new ArrayList<>();
                cats.add(null);
                cats.addAll(Categories.ALL);
                int idx = cats.indexOf(query.category());
                query = new AuctionService.Query(cats.get((idx + 1) % cats.size()), query.search(), query.seller(), query.sort());
                page = 0;
                load(false);
            }
            case 48 -> plugin.prompts().ask(player, "ah-search-prompt", text -> {
                String search = text.equalsIgnoreCase("cancel") || text.isEmpty() ? null : text;
                open(plugin, player, new AuctionService.Query(query.category(), search, query.seller(), query.sort()));
            });
            case 49 -> load(false);
            case 50 -> SellingMenu.open(plugin, player);
            case 51 -> ClaimsMenu.open(plugin, player, "AH_EXPIRED", "<gold><bold>Expired and returned items");
            case 52 -> {
                player.closeInventory();
                AhCmd.showSold(plugin, player);
            }
            default -> { }
        }
    }

    private void clickedListing(AuctionService.Listing l) {
        if (l.seller().equals(player.getUniqueId())) {
            confirmCancel(plugin, player, l, () -> load(true));
            return;
        }
        boolean skip = plugin.settings().ahConfirmOff(player);
        if (skip) {
            plugin.auctions().buy(player, l, () -> player.closeInventory());
            return;
        }
        ItemStack shown = Ui.withLore(l.item(), List.of("", "<gray>Price: <green>" + plugin.economy().format(l.price()),
                "<gray>Seller: <white>" + l.sellerName()));
        new ConfirmMenu(plugin, "<gold>Buy for " + plugin.economy().format(l.price()) + "?", shown, () -> {
            plugin.auctions().buy(player, l, () -> player.closeInventory());
        }, () -> load(true)).open(player);
    }

    static void confirmCancel(IyhaCore plugin, Player player, AuctionService.Listing l, Runnable afterwards) {
        ItemStack shown = Ui.withLore(l.item(), List.of("", "<gray>Price: <green>" + plugin.economy().format(l.price())));
        new ConfirmMenu(plugin, "<red>Cancel this listing?", shown, () -> {
            player.closeInventory();
            plugin.auctions().cancel(player, l.id(), false, null);
        }, afterwards).open(player);
    }
}

/** Your own active listings. Click one to cancel it. */
final class SellingMenu extends PagedMenu {
    private SellingMenu(IyhaCore plugin) {
        super(plugin);
    }

    static void open(IyhaCore plugin, Player player) {
        SellingMenu menu = new SellingMenu(plugin);
        plugin.auctions().page(new AuctionService.Query(null, null, player.getUniqueId(), AuctionService.Sort.NEWEST), 0, 200)
                .thenAccept(result -> plugin.sync(() -> {
                    List<Entry> entries = new ArrayList<>();
                    long now = System.currentTimeMillis();
                    for (AuctionService.Listing l : result.rows()) {
                        ItemStack icon = Ui.withLore(l.item(), List.of("", "<gray>Price: <green>" + plugin.economy().format(l.price()),
                                "<gray>Expires in: <white>" + AuctionMenu.timeLeft(l.expiresAt() - now), "<yellow>Click to cancel"));
                        entries.add(new Entry(icon, e -> AuctionMenu.confirmCancel(plugin, player, l,
                                () -> SellingMenu.open(plugin, player))));
                    }
                    menu.setEntries(entries);
                    menu.open(player);
                }));
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>My listings");
    }

    @Override
    protected void onBack(Player player) {
        AuctionMenu.open(plugin, player, new AuctionService.Query(null, null, null, AuctionService.Sort.NEWEST));
    }
}

/** /ah ... */
final class AhCmd extends Cmd {
    AhCmd(IyhaCore plugin) {
        super(plugin, "ah", "iyha.ah.use", 1000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        if (args.length == 0) {
            AuctionMenu.open(plugin, player, new AuctionService.Query(null, null, null, AuctionService.Sort.NEWEST));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "sell" -> sell(player, args);
            case "search" -> {
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                AuctionMenu.open(plugin, player, new AuctionService.Query(null, text.isEmpty() ? null : text, null,
                        AuctionService.Sort.PRICE_ASC));
            }
            case "selling" -> SellingMenu.open(plugin, player);
            case "sold" -> showSold(plugin, player);
            case "expired" -> ClaimsMenu.open(plugin, player, "AH_EXPIRED", "<gold><bold>Expired and returned items");
            case "claim" -> plugin.claims().deliverAll(player);
            case "cancel" -> {
                try {
                    plugin.auctions().cancel(player, Long.parseLong(args[1]), false, null);
                } catch (RuntimeException ex) {
                    plugin.lang().send(player, "usage", "usage", "/ah cancel <id>");
                }
            }
            case "player" -> {
                if (args.length < 2) {
                    plugin.lang().send(player, "usage", "usage", "/ah player <name>");
                    return;
                }
                guard(plugin.players().resolve(args[1]).thenAccept(found -> plugin.sync(() -> {
                    if (found.isEmpty()) {
                        plugin.lang().send(player, "unknown-player", "name", args[1]);
                    } else {
                        AuctionMenu.open(plugin, player, new AuctionService.Query(null, null, found.get().uuid(),
                                AuctionService.Sort.NEWEST));
                    }
                })), player);
            }
            case "remove" -> {
                if (!player.hasPermission("iyha.admin.ah") || args.length < 2) {
                    plugin.lang().send(player, "no-permission");
                    return;
                }
                try {
                    String reason = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
                    plugin.auctions().cancel(player, Long.parseLong(args[1]), true, reason);
                } catch (NumberFormatException ex) {
                    plugin.lang().send(player, "invalid-amount");
                }
            }
            default -> AuctionMenu.open(plugin, player, new AuctionService.Query(null,
                    String.join(" ", args), null, AuctionService.Sort.PRICE_ASC));
        }
    }

    private void sell(Player player, String[] args) {
        if (!player.hasPermission("iyha.ah.sell")) {
            plugin.lang().send(player, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.lang().send(player, "usage", "usage", "/ah sell <price> [amount]");
            return;
        }
        var price = Money.parse(args[1]);
        if (price.isEmpty()) {
            plugin.lang().send(player, "invalid-amount");
            return;
        }
        int amount = Integer.MAX_VALUE;
        if (args.length > 2) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                plugin.lang().send(player, "invalid-amount");
                return;
            }
        }
        plugin.auctions().list(player, price.getAsLong(), amount);
    }

    static void showSold(IyhaCore plugin, Player player) {
        plugin.auctions().soldHistory(player.getUniqueId()).thenAccept(rows -> plugin.sync(() -> {
            plugin.lang().send(player, "ah-sold-header");
            if (rows.isEmpty()) {
                plugin.lang().send(player, "sellhistory-empty");
            }
            for (String[] r : rows) {
                plugin.lang().send(player, "ah-sold-line", "item", r[0], "qty", r[1], "price", r[2], "buyer", r[3]);
            }
        }));
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("sell", "search", "selling", "sold", "expired", "claim", "cancel", "player"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
            return onlineNames(args[1]);
        }
        return List.of();
    }
}

/** /claim: receive every pending item (purchases, returns, deliveries). */
final class ClaimCmd extends Cmd {
    ClaimCmd(IyhaCore plugin) {
        super(plugin, "claim", "iyha.ah.claim", 2000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        ClaimsMenu.open(plugin, player, null, "<gold><bold>Your pending items");
    }
}
