package net.iyha.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Server buy prices from worth.yml, grouped by category. All prices are IYHA-original and tunable. */
final class WorthService {
    record Price(long unit, String category) {}

    private final IyhaCore plugin;
    private final Map<String, Price> prices = new LinkedHashMap<>();

    WorthService(IyhaCore plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        prices.clear();
        File file = new File(plugin.getDataFolder(), "worth.yml");
        if (!file.exists()) {
            plugin.saveResource("worth.yml", false);
        }
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        var root = yaml.getConfigurationSection("categories");
        if (root == null) {
            return;
        }
        for (String category : root.getKeys(false)) {
            var section = root.getConfigurationSection(category);
            if (section == null) {
                continue;
            }
            for (String key : section.getKeys(false)) {
                long cents = Math.max(1, Money.fromDouble(section.getDouble(key)));
                prices.put(key.toLowerCase(Locale.ROOT), new Price(cents, category.toLowerCase(Locale.ROOT)));
            }
        }
        plugin.getLogger().info("Loaded " + prices.size() + " sell prices.");
    }

    Price price(String key) {
        return prices.get(key);
    }

    Map<String, Price> all() {
        return prices;
    }

    double multiplier(long totalSoldInCategory) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("sell.multipliers.enabled", true)) {
            return 1.0;
        }
        long perStep = Math.max(1, cfg.getLong("sell.multipliers.items-per-step", 1000));
        double bonus = cfg.getDouble("sell.multipliers.bonus-per-step", 0.02);
        double max = cfg.getDouble("sell.multipliers.max-bonus", 0.5);
        return 1.0 + Math.min(max, (totalSoldInCategory / perStep) * bonus);
    }
}

/** Selling to the server. Orders are filled first, the rest sells at the server price. One transaction. */
final class SellService {
    record SellResult(EconomyService.Status status, long serverTotal, long orderTotal, int sold,
                      List<EconomyService.Posting> postings) {}

    private final IyhaCore plugin;

    SellService(IyhaCore plugin) {
        this.plugin = plugin;
    }

    /** Plain, unmodified items with a server price. Everything else must go through the Auction House. */
    boolean isSellable(ItemStack item) {
        return item != null && !item.getType().isAir() && !item.hasItemMeta()
                && plugin.worth().price(item.getType().getKey().getKey()) != null && plugin.itemRules().allowed(item);
    }

    CompletableFuture<SellResult> sell(UUID id, String name, List<ItemStack> stacks, String source) {
        Map<String, Integer> byKey = new LinkedHashMap<>();
        for (ItemStack s : stacks) {
            byKey.merge(s.getType().getKey().getKey(), s.getAmount(), Integer::sum);
        }
        String owner = id.toString();
        EconomyService eco = plugin.economy();
        return plugin.database().supplySerial(c -> Database.tx(c, conn -> {
            List<EconomyService.Posting> posts = new ArrayList<>();
            long serverTotal = 0;
            long orderTotal = 0;
            int sold = 0;
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Integer> e : byKey.entrySet()) {
                String key = e.getKey();
                int qty = e.getValue();
                WorthService.Price price = plugin.worth().price(key);
                if (price == null) {
                    continue;
                }
                OrdersService.FillResult fill = plugin.orders().fillInTx(conn, id, name, key, qty, posts);
                orderTotal += fill.earned();
                int remaining = qty - fill.filled();
                long progress = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT total FROM sell_progress WHERE uuid = ? AND category = ?")) {
                    ps.setString(1, owner);
                    ps.setString(2, price.category());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            progress = rs.getLong(1);
                        }
                    }
                }
                if (remaining > 0) {
                    double mult = plugin.worth().multiplier(progress);
                    long total = Math.round(price.unit() * (double) remaining * mult);
                    serverTotal += total;
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO sell_log(uuid, item_key, quantity, "
                            + "unit_price, multiplier_pct, total, source, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                        ps.setString(1, owner);
                        ps.setString(2, key);
                        ps.setInt(3, remaining);
                        ps.setLong(4, price.unit());
                        ps.setInt(5, (int) Math.round(mult * 100));
                        ps.setLong(6, total);
                        ps.setString(7, source);
                        ps.setLong(8, now);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("REPLACE INTO sell_progress(uuid, category, total) VALUES (?,?,?)")) {
                    ps.setString(1, owner);
                    ps.setString(2, price.category());
                    ps.setLong(3, progress + qty);
                    ps.executeUpdate();
                }
                sold += qty;
            }
            if (serverTotal > 0) {
                EconomyService.Applied applied = eco.applyLedger(conn, EconomyService.MINT, Map.of(owner, serverTotal),
                        "SELL", source, name, null, false);
                if (applied.status() != EconomyService.Status.OK) {
                    return new SellResult(applied.status(), 0, 0, 0, List.of());
                }
                posts.addAll(applied.postings());
            }
            return new SellResult(EconomyService.Status.OK, serverTotal, orderTotal, sold, posts);
        }, r -> r.status() == EconomyService.Status.OK)).thenApply(result -> {
            if (result.status() == EconomyService.Status.OK) {
                eco.publish(result.postings());
            }
            return result;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Sell failed", ex);
            return new SellResult(EconomyService.Status.ERROR, 0, 0, 0, List.of());
        });
    }

    void announce(Player player, SellResult result) {
        EconomyService eco = plugin.economy();
        plugin.lang().send(player, "sell-done", "count", String.valueOf(result.sold()),
                "server", eco.format(result.serverTotal()), "orders", eco.format(result.orderTotal()));
    }

    void failed(Player player, SellResult result) {
        plugin.lang().send(player, result.status() == EconomyService.Status.FROZEN ? "economy-frozen" : "internal-error");
    }
}

/** /sell menu: deposit items, review the total, confirm. Closing without confirming returns everything. */
final class SellMenu extends EscrowMenu {
    private boolean busy;

    SellMenu(IyhaCore plugin) {
        super(plugin, "SELL");
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>Sell items");
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, Ui.filler());
        }
        set(47, Ui.button(Material.LIME_CONCRETE, "<green><bold>Confirm sale", "<gray>Sells everything sellable above"));
        set(49, Ui.button(Material.BARRIER, "<red>Close and return items"));
        set(51, Ui.button(Material.BOOK, "<yellow>Price list"));
        onItemsChanged();
    }

    @Override
    boolean acceptsItemInSlot(int slot) {
        return slot < 45;
    }

    @Override
    boolean allowsBottomInteraction() {
        return true;
    }

    @Override
    protected List<ItemStack> heldItems() {
        List<ItemStack> list = new ArrayList<>();
        Inventory inv = getInventory();
        if (inv == null) {
            return list;
        }
        for (int i = 0; i < 45; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) {
                list.add(it);
            }
        }
        return list;
    }

    @Override
    protected void clearHeld() {
        Inventory inv = getInventory();
        if (inv != null) {
            for (int i = 0; i < 45; i++) {
                inv.setItem(i, null);
            }
        }
    }

    @Override
    protected void onItemsChanged() {
        long base = 0;
        int unsellable = 0;
        for (ItemStack it : heldItems()) {
            if (plugin.sell().isSellable(it)) {
                base += plugin.worth().price(it.getType().getKey().getKey()).unit() * it.getAmount();
            } else {
                unsellable++;
            }
        }
        if (getInventory() != null) {
            set(45, Ui.button(Material.GOLD_INGOT, "<gold>Estimated: <green>" + plugin.economy().format(base),
                    "<gray>Before multipliers and order bonuses",
                    unsellable > 0 ? "<red>" + unsellable + " stack(s) cannot be sold and will be returned" : " "));
        }
        snapshot();
    }

    @Override
    protected void handleClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        Player player = (Player) event.getWhoClicked();
        InventoryAction action = event.getAction();
        if (raw >= 0 && raw < 45) {
            switch (action) {
                case PICKUP_ALL, PICKUP_HALF, PICKUP_SOME, PICKUP_ONE, PLACE_ALL, PLACE_ONE, PLACE_SOME,
                     SWAP_WITH_CURSOR, MOVE_TO_OTHER_INVENTORY -> event.setCancelled(false);
                default -> { }
            }
            afterClick();
        } else if (raw >= 45 && raw < 54) {
            if (raw == 47) {
                confirm(player);
            } else if (raw == 49) {
                player.closeInventory();
            } else if (raw == 51) {
                plugin.sync(() -> WorthMenu.open(plugin, player));
            }
        } else if (raw >= 54 && action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            ItemStack current = event.getCurrentItem();
            if (current != null && !current.getType().isAir()) {
                ItemStack rest = deposit(current.clone());
                event.setCurrentItem(rest == null || rest.getAmount() <= 0 ? null : rest);
                afterClick();
            }
        } else if (raw >= 54) {
            afterClick();
        }
    }

    /** Puts the stack into deposit slots 0-44. Returns what did not fit. */
    private ItemStack deposit(ItemStack stack) {
        Inventory inv = getInventory();
        for (int i = 0; i < 45 && stack.getAmount() > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot != null && slot.isSimilar(stack) && slot.getAmount() < slot.getMaxStackSize()) {
                int move = Math.min(stack.getAmount(), slot.getMaxStackSize() - slot.getAmount());
                slot.setAmount(slot.getAmount() + move);
                stack.setAmount(stack.getAmount() - move);
            }
        }
        for (int i = 0; i < 45 && stack.getAmount() > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot == null || slot.getType().isAir()) {
                inv.setItem(i, stack.clone());
                stack.setAmount(0);
            }
        }
        return stack.getAmount() > 0 ? stack : null;
    }

    private void confirm(Player player) {
        if (busy) {
            return;
        }
        List<ItemStack> held = new ArrayList<>(heldItems());
        if (held.isEmpty()) {
            plugin.lang().send(player, "sell-empty");
            return;
        }
        List<ItemStack> sellable = new ArrayList<>();
        List<ItemStack> rejected = new ArrayList<>();
        for (ItemStack it : held) {
            (plugin.sell().isSellable(it) ? sellable : rejected).add(it);
        }
        if (sellable.isEmpty()) {
            plugin.lang().send(player, "sell-nothing");
            return;
        }
        busy = true;
        clearHeld();
        plugin.sell().sell(player.getUniqueId(), player.getName(), sellable, "MENU").thenAccept(result -> plugin.sync(() -> {
            busy = false;
            if (result.status() == EconomyService.Status.OK) {
                for (ItemStack it : rejected) {
                    Give.giveOrClaim(plugin, player, it, "SELL_RETURN");
                }
                plugin.sell().announce(player, result);
            } else {
                for (ItemStack it : sellable) {
                    Give.giveOrClaim(plugin, player, it, "SELL_RETURN");
                }
                for (ItemStack it : rejected) {
                    Give.giveOrClaim(plugin, player, it, "SELL_RETURN");
                }
                plugin.sell().failed(player, result);
            }
            settle();
            player.closeInventory();
        }));
    }
}

/** Price browser. */
final class WorthMenu extends PagedMenu {
    private WorthMenu(IyhaCore plugin) {
        super(plugin);
    }

    static void open(IyhaCore plugin, Player player) {
        WorthMenu menu = new WorthMenu(plugin);
        List<Entry> entries = new ArrayList<>();
        List<Map.Entry<String, WorthService.Price>> sorted = new ArrayList<>(plugin.worth().all().entrySet());
        sorted.sort((a, b) -> a.getValue().category().equals(b.getValue().category())
                ? a.getKey().compareTo(b.getKey()) : a.getValue().category().compareTo(b.getValue().category()));
        for (Map.Entry<String, WorthService.Price> e : sorted) {
            Material mat = Material.matchMaterial(e.getKey());
            if (mat == null || mat.isAir()) {
                continue;
            }
            ItemStack icon = Ui.button(mat, "<white>" + e.getKey().replace('_', ' '),
                    "<gray>Sells for <green>" + plugin.economy().format(e.getValue().unit()) + "<gray> each",
                    "<dark_gray>Category: " + e.getValue().category());
            entries.add(new Entry(icon, ev -> { }));
        }
        menu.setEntries(entries);
        menu.open(player);
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>Sell prices");
    }
}

/** /sell, /sell hand [amount], /sell all */
final class SellCmd extends Cmd {
    SellCmd(IyhaCore plugin) {
        super(plugin, "sell", "iyha.sell.use", 1000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        if (args.length == 0) {
            new SellMenu(plugin).open(player);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("hand") && player.hasPermission("iyha.sell.hand")) {
            sellHand(player, args);
        } else if (sub.equals("all") && player.hasPermission("iyha.sell.all")) {
            sellAll(player);
        } else {
            plugin.lang().send(player, "usage", "usage", "/sell [hand [amount]|all]");
        }
    }

    private void sellHand(Player player, String[] args) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.sell().isSellable(hand)) {
            plugin.lang().send(player, "sell-nothing");
            return;
        }
        int amount = hand.getAmount();
        if (args.length > 1) {
            try {
                amount = Math.max(1, Math.min(amount, Integer.parseInt(args[1])));
            } catch (NumberFormatException ex) {
                plugin.lang().send(player, "invalid-amount");
                return;
            }
        }
        ItemStack sold = hand.clone();
        sold.setAmount(amount);
        if (amount >= hand.getAmount()) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - amount);
        }
        player.saveData();
        finish(player, List.of(sold));
    }

    private void sellAll(Player player) {
        List<ItemStack> sold = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (plugin.sell().isSellable(contents[i])) {
                sold.add(contents[i].clone());
                player.getInventory().setItem(i, null);
            }
        }
        if (sold.isEmpty()) {
            plugin.lang().send(player, "sell-nothing");
            return;
        }
        player.saveData();
        finish(player, sold);
    }

    private void finish(Player player, List<ItemStack> stacks) {
        guard(plugin.sell().sell(player.getUniqueId(), player.getName(), stacks, "CMD").thenAccept(result -> plugin.sync(() -> {
            if (result.status() == EconomyService.Status.OK) {
                plugin.sell().announce(player, result);
            } else {
                for (ItemStack it : stacks) {
                    Give.giveOrClaim(plugin, player, it, "SELL_RETURN");
                }
                plugin.sell().failed(player, result);
            }
        })), player);
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        return args.length == 1 ? filter(List.of("hand", "all"), args[0]) : List.of();
    }
}

/** /worth [hand] */
final class WorthCmd extends Cmd {
    WorthCmd(IyhaCore plugin) {
        super(plugin, "worth", "iyha.sell.worth", 1000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("hand")) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            WorthService.Price price = hand.getType().isAir() ? null : plugin.worth().price(hand.getType().getKey().getKey());
            if (price == null || hand.hasItemMeta()) {
                plugin.lang().send(player, "worth-none");
            } else {
                plugin.lang().send(player, "worth-hand", "unit", plugin.economy().format(price.unit()),
                        "total", plugin.economy().format(price.unit() * hand.getAmount()));
            }
            return;
        }
        WorthMenu.open(plugin, player);
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        return args.length == 1 ? filter(List.of("hand"), args[0]) : List.of();
    }
}

/** /sellhistory */
final class SellHistoryCmd extends Cmd {
    SellHistoryCmd(IyhaCore plugin) {
        super(plugin, "sellhistory", "iyha.sell.history", 2000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        UUID id = player.getUniqueId();
        guard(plugin.database().supplyIo(c -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT item_key, quantity, total, source FROM sell_log "
                    + "WHERE uuid = ? ORDER BY id DESC LIMIT 10")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[]{rs.getString(1), String.valueOf(rs.getInt(2)),
                                plugin.economy().format(rs.getLong(3)), rs.getString(4)});
                    }
                }
            }
            return rows;
        }).thenAccept(rows -> plugin.sync(() -> {
            plugin.lang().send(player, "sellhistory-header");
            if (rows.isEmpty()) {
                plugin.lang().send(player, "sellhistory-empty");
            }
            for (String[] r : rows) {
                plugin.lang().send(player, "sellhistory-line", "item", r[0], "qty", r[1], "total", r[2], "source", r[3]);
            }
        })), player);
    }
}

/** /sellmulti */
final class SellMultiCmd extends Cmd {
    SellMultiCmd(IyhaCore plugin) {
        super(plugin, "sellmulti", "iyha.sell.multi", 2000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        UUID id = player.getUniqueId();
        guard(plugin.database().supplyIo(c -> {
            Map<String, Long> map = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT category, total FROM sell_progress WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            return map;
        }).thenAccept(map -> plugin.sync(() -> {
            plugin.lang().send(player, "sellmulti-header");
            for (Map.Entry<String, Long> e : map.entrySet()) {
                plugin.lang().send(player, "sellmulti-line", "category", e.getKey(), "sold", String.valueOf(e.getValue()),
                        "mult", String.format(Locale.US, "x%.2f", plugin.worth().multiplier(e.getValue())));
            }
            if (map.isEmpty()) {
                plugin.lang().send(player, "sellhistory-empty");
            }
        })), player);
    }
}
