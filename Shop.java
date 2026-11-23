package net.iyha.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Classic server shop (shop.yml). On load every item is checked against the sell prices: an item that can be
 * sold back for more than it costs would create infinite money, so it is disabled and reported.
 */
final class ShopService {
    record Item(String key, Material material, long buy, String category) {}

    record Category(String id, String name, Material icon, String permission, List<Item> items) {}

    private final IyhaCore plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();

    ShopService(IyhaCore plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        categories.clear();
        File file = new File(plugin.getDataFolder(), "shop.yml");
        if (!file.exists()) {
            plugin.saveResource("shop.yml", false);
        }
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("categories");
        if (root == null) {
            return;
        }
        double maxMult = 1.0 + plugin.getConfig().getDouble("sell.multipliers.max-bonus", 0.5);
        int disabled = 0;
        for (String id : root.getKeys(false)) {
            ConfigurationSection cs = root.getConfigurationSection(id);
            if (cs == null) {
                continue;
            }
            Material icon = Material.matchMaterial(cs.getString("icon", "CHEST"));
            List<Item> items = new ArrayList<>();
            ConfigurationSection is = cs.getConfigurationSection("items");
            if (is != null) {
                for (String key : is.getKeys(false)) {
                    Material mat = Material.matchMaterial(key);
                    if (mat == null || mat.isAir()) {
                        plugin.getLogger().warning("shop.yml: unknown item " + key);
                        continue;
                    }
                    long buy = Math.max(1, Money.fromDouble(is.getDouble(key)));
                    WorthService.Price sell = plugin.worth().price(mat.getKey().getKey());
                    if (sell != null && buy < Math.ceil(sell.unit() * maxMult)) {
                        plugin.getLogger().severe("shop.yml: " + key + " costs less than it can be sold for. Item DISABLED.");
                        disabled++;
                        continue;
                    }
                    items.add(new Item(mat.getKey().getKey(), mat, buy, id));
                }
            }
            categories.put(id, new Category(id, cs.getString("name", id), icon == null ? Material.CHEST : icon,
                    cs.getString("permission", ""), items));
        }
        plugin.getLogger().info("Shop loaded: " + categories.size() + " categories, " + disabled + " items disabled.");
    }

    Map<String, Category> categories() {
        return categories;
    }

    List<Item> search(String text) {
        String q = text.toLowerCase(Locale.ROOT).replace(' ', '_');
        List<Item> list = new ArrayList<>();
        for (Category c : categories.values()) {
            for (Item i : c.items()) {
                if (i.key().contains(q)) {
                    list.add(i);
                }
            }
        }
        return list;
    }

    /** Charges the money, then hands over the items. Main thread. */
    void buy(Player player, Item item, int amount) {
        if (Give.capacity(player.getInventory(), new ItemStack(item.material())) < amount) {
            plugin.lang().send(player, "shop-full");
            return;
        }
        long total;
        try {
            total = Math.multiplyExact(item.buy(), (long) amount);
        } catch (ArithmeticException ex) {
            plugin.lang().send(player, "internal-error");
            return;
        }
        long cost = total;
        plugin.economy().sink(player.getUniqueId(), cost, "SHOP_BUY", item.key(), player.getName())
                .thenAccept(result -> plugin.sync(() -> {
                    switch (result.status()) {
                        case OK -> {
                            for (ItemStack stack : Give.stacksOf(item.material(), amount)) {
                                Give.giveOrClaim(plugin, player, stack, "SHOP");
                            }
                            player.saveData();
                            plugin.lang().send(player, "shop-bought", "qty", String.valueOf(amount),
                                    "item", item.key(), "amount", plugin.economy().format(cost));
                            log(player, item, amount);
                        }
                        case INSUFFICIENT -> plugin.lang().send(player, "pay-insufficient", "balance",
                                plugin.economy().format(result.fromBalance()));
                        case FROZEN -> plugin.lang().send(player, "economy-frozen");
                        default -> plugin.lang().send(player, "internal-error");
                    }
                }));
    }

    private void log(Player player, Item item, int amount) {
        long now = System.currentTimeMillis();
        plugin.database().supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO shop_log(uuid, item_key, quantity, unit_price, "
                    + "mode, created_at) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, item.key());
                ps.setInt(3, amount);
                ps.setLong(4, item.buy());
                ps.setString(5, "BUY");
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            return (Void) null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Could not write shop log", ex);
            return null;
        });
    }
}

/** Main shop menu: categories plus Sell, Quick Buy and Search. */
final class ShopMenu extends Menu {
    ShopMenu(IyhaCore plugin) {
        super(plugin);
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>IYHA Shop");
    }

    @Override
    protected int size() {
        return 54;
    }

    private final List<ShopService.Category> shown = new ArrayList<>();
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Ui.filler());
        }
        int n = 0;
        for (ShopService.Category c : plugin.shop().categories().values()) {
            if (n >= SLOTS.length) {
                break;
            }
            shown.add(c);
            set(SLOTS[n++], Ui.button(c.icon(), c.name(), "<gray>" + c.items().size() + " items", "<yellow>Click to open"));
        }
        set(45, Ui.button(Material.GOLD_INGOT, "<green><bold>Sell items", "<gray>Sell to the server"));
        set(47, Ui.button(Material.EMERALD, "<aqua><bold>Quick Buy", "<gray>Cheapest player listings"));
        set(49, Ui.button(Material.BARRIER, "<red>Close"));
        set(51, Ui.button(Material.COMPASS, "<yellow><bold>Search", "<gray>Type an item name in chat"));
    }

    @Override
    void onClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        Player player = (Player) event.getWhoClicked();
        for (int i = 0; i < shown.size(); i++) {
            if (SLOTS[i] == raw) {
                ShopService.Category c = shown.get(i);
                if (!c.permission().isEmpty() && !player.hasPermission(c.permission())) {
                    plugin.lang().send(player, "no-permission");
                    return;
                }
                ShopItemsMenu.open(plugin, player, c.name(), c.items());
                return;
            }
        }
        switch (raw) {
            case 45 -> new SellMenu(plugin).open(player);
            case 47 -> AuctionMenu.open(plugin, player, new AuctionService.Query(null, null, null, AuctionService.Sort.UNIT_PRICE));
            case 49 -> player.closeInventory();
            case 51 -> plugin.prompts().ask(player, "shop-search-prompt", text -> {
                if (!text.equalsIgnoreCase("cancel")) {
                    ShopItemsMenu.open(plugin, player, "Search: " + text, plugin.shop().search(text));
                }
            });
            default -> { }
        }
    }
}

/** Items of one category (or search results). */
final class ShopItemsMenu extends PagedMenu {
    private final String titleText;

    private ShopItemsMenu(IyhaCore plugin, String titleText) {
        super(plugin);
        this.titleText = titleText;
    }

    static void open(IyhaCore plugin, Player player, String title, List<ShopService.Item> items) {
        ShopItemsMenu menu = new ShopItemsMenu(plugin, title);
        List<Entry> entries = new ArrayList<>();
        for (ShopService.Item item : items) {
            ItemStack icon = Ui.button(item.material(), "<white>" + item.key().replace('_', ' '),
                    "<gray>Price: <green>" + plugin.economy().format(item.buy()) + "<gray> each",
                    "<yellow>Click to choose a quantity");
            entries.add(new Entry(icon, e -> new QuantityMenu(plugin, item, () -> open(plugin, player, title, items)).open(player)));
        }
        menu.setEntries(entries);
        menu.open(player);
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>" + titleText);
    }

    @Override
    protected void onBack(Player player) {
        new ShopMenu(plugin).open(player);
    }
}

/** Quantity chooser, then a confirmation screen before any money moves. */
final class QuantityMenu extends Menu {
    private static final int[] AMOUNTS = {1, 8, 16, 32, 64};
    private final ShopService.Item item;
    private final Runnable back;

    QuantityMenu(IyhaCore plugin, ShopService.Item item, Runnable back) {
        super(plugin);
        this.item = item;
        this.back = back;
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>How many?");
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void build() {
        Inventory inv = getInventory();
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, Ui.filler());
        }
        inv.setItem(4, Ui.button(item.material(), "<white>" + item.key().replace('_', ' '),
                "<gray>" + plugin.economy().format(item.buy()) + " each"));
        for (int i = 0; i < AMOUNTS.length; i++) {
            ItemStack icon = Ui.button(Material.LIME_STAINED_GLASS_PANE, "<green>Buy " + AMOUNTS[i],
                    "<gray>Total: <green>" + plugin.economy().format(item.buy() * AMOUNTS[i]));
            icon.setAmount(Math.max(1, Math.min(64, AMOUNTS[i])));
            inv.setItem(10 + i, icon);
        }
        inv.setItem(22, Ui.button(Material.ARROW, "<yellow>Back"));
    }

    @Override
    void onClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        Player player = (Player) event.getWhoClicked();
        if (raw == 22) {
            back.run();
            return;
        }
        if (raw >= 10 && raw < 10 + AMOUNTS.length) {
            int amount = AMOUNTS[raw - 10];
            ItemStack shown = Ui.button(item.material(), "<white>" + amount + " x " + item.key().replace('_', ' '),
                    "<gray>Total: <green>" + plugin.economy().format(item.buy() * amount));
            new ConfirmMenu(plugin, "<gold>Confirm purchase", shown, () -> {
                player.closeInventory();
                plugin.shop().buy(player, item, amount);
            }, back).open(player);
        }
    }
}

/** /shop, /shop search <text>, /shop reload */
final class ShopCmd extends Cmd {
    ShopCmd(IyhaCore plugin) {
        super(plugin, "shop", "iyha.shop.use", 1000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!player.hasPermission("iyha.admin.shop")) {
                plugin.lang().send(player, "no-permission");
                return;
            }
            plugin.shop().reload();
            plugin.audit().log(player.getName(), "SHOP_RELOAD", null, null);
            plugin.lang().send(player, "reload-done");
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("search")) {
            String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            ShopItemsMenu.open(plugin, player, "Search: " + text, plugin.shop().search(text));
            return;
        }
        new ShopMenu(plugin).open(player);
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        return args.length == 1 ? filter(List.of("search", "reload"), args[0]) : List.of();
    }
}
