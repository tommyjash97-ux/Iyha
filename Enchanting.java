package net.iyha.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * IYHA custom 3x3 enchanting. The vanilla enchanting table screen is never shown. Two interchangeable models,
 * chosen in enchantments.yml (model: B or A). Both are IYHA-original designs: the exact reference behaviour
 * could not be verified.
 * <ul>
 * <li>Model B "Selection Grid": put an item in the item slot; the 3x3 grid shows enchantment tiles. Select
 * tiles, then confirm.</li>
 * <li>Model A "Grid Recipe": the item goes in the centre of the 3x3 grid, reagents in the eight surrounding
 * slots. A matching recipe from enchantments.yml is applied on confirm.</li>
 * </ul>
 */
final class EnchantService {
    record Recipe(String enchant, int level, Map<String, Integer> reagents) {}

    private final IyhaCore plugin;
    private final Random random = new Random();
    private String model = "B";
    private int maxPerItem = 5;
    private double failChance = 0;
    private double coinBase = 400;
    private double xpBase = 2;
    private double bookApplyFee = 250;
    private final Map<String, Double> rarity = new HashMap<>();
    private final List<Recipe> recipes = new ArrayList<>();

    final NamespacedKey telepathy;
    final NamespacedKey lifesteal;

    EnchantService(IyhaCore plugin) {
        this.plugin = plugin;
        this.telepathy = new NamespacedKey(plugin, "ench_telepathy");
        this.lifesteal = new NamespacedKey(plugin, "ench_lifesteal");
        reload();
    }

    void reload() {
        File file = new File(plugin.getDataFolder(), "enchantments.yml");
        if (!file.exists()) {
            plugin.saveResource("enchantments.yml", false);
        }
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        model = yaml.getString("model", "B").toUpperCase(Locale.ROOT);
        maxPerItem = yaml.getInt("max-enchants-per-item", 5);
        failChance = yaml.getDouble("fail-chance", 0.0);
        coinBase = yaml.getDouble("costs.coins-base", 400);
        xpBase = yaml.getDouble("costs.xp-levels-base", 2);
        bookApplyFee = yaml.getDouble("costs.book-apply-fee", 250);
        rarity.clear();
        var rs = yaml.getConfigurationSection("rarity");
        if (rs != null) {
            for (String k : rs.getKeys(false)) {
                rarity.put(k.toLowerCase(Locale.ROOT), rs.getDouble(k));
            }
        }
        recipes.clear();
        var recs = yaml.getMapList("recipes");
        for (Map<?, ?> m : recs) {
            Object e = m.get("enchant");
            Object l = m.get("level");
            Object r = m.get("reagents");
            if (e == null || l == null || !(r instanceof Map<?, ?> rm)) {
                continue;
            }
            Map<String, Integer> reagents = new LinkedHashMap<>();
            for (Map.Entry<?, ?> re : rm.entrySet()) {
                reagents.put(String.valueOf(re.getKey()).toUpperCase(Locale.ROOT), Integer.parseInt(String.valueOf(re.getValue())));
            }
            recipes.add(new Recipe(String.valueOf(e).toLowerCase(Locale.ROOT), Integer.parseInt(String.valueOf(l)), reagents));
        }
    }

    String model() { return model; }
    int maxPerItem() { return maxPerItem; }
    double bookFee() { return bookApplyFee; }
    List<Recipe> recipes() { return recipes; }

    Enchantment find(String name) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
    }

    /** Vanilla enchantments that fit the item. Books accept every enchantment. */
    List<Enchantment> applicable(ItemStack item) {
        boolean book = item.getType() == Material.BOOK || item.getType() == Material.ENCHANTED_BOOK;
        return Registry.ENCHANTMENT.stream()
                .filter(e -> book || e.canEnchantItem(item))
                .sorted(Comparator.comparing((Enchantment e) -> e.getKey().getKey()))
                .collect(Collectors.toList());
    }

    int currentLevel(ItemStack item, Enchantment e) {
        if (item.getItemMeta() instanceof EnchantmentStorageMeta storage) {
            return storage.getStoredEnchantLevel(e);
        }
        return item.getEnchantmentLevel(e);
    }

    int enchantCount(ItemStack item) {
        if (item.getItemMeta() instanceof EnchantmentStorageMeta storage) {
            return storage.getStoredEnchants().size();
        }
        return item.getEnchantments().size();
    }

    boolean conflicts(ItemStack item, Enchantment add) {
        Map<Enchantment, Integer> existing = item.getItemMeta() instanceof EnchantmentStorageMeta storage
                ? storage.getStoredEnchants() : item.getEnchantments();
        for (Enchantment other : existing.keySet()) {
            if (!other.equals(add) && other.conflictsWith(add)) {
                return true;
            }
        }
        return false;
    }

    long coinCost(Enchantment e, int level) {
        double mult = rarity.getOrDefault(e.getKey().getKey(), 1.0);
        return Money.fromDouble(coinBase * level * mult);
    }

    int xpCost(int level) {
        return (int) Math.ceil(xpBase + level * 1.5);
    }

    /** Returns a new stack with the enchantment applied (a plain BOOK becomes an ENCHANTED_BOOK). */
    ItemStack apply(ItemStack item, Enchantment e, int level) {
        ItemStack out = item.clone();
        if (out.getType() == Material.BOOK) {
            out = new ItemStack(Material.ENCHANTED_BOOK, out.getAmount());
        }
        if (out.getType() == Material.ENCHANTED_BOOK) {
            out.editMeta(EnchantmentStorageMeta.class, meta -> meta.addStoredEnchant(e, level, true));
        } else {
            out.editMeta(meta -> meta.addEnchant(e, level, true));
        }
        return out;
    }

    ItemStack book(Enchantment e, int level) {
        return apply(new ItemStack(Material.ENCHANTED_BOOK), e, level);
    }

    boolean rollFailure() {
        return failChance > 0 && random.nextDouble() < failChance;
    }

    void log(Player player, ItemStack item, String action, String details, long coins, int xp, String result) {
        long now = System.currentTimeMillis();
        String key = plugin.itemKeys().keyOf(item);
        plugin.database().supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO enchant_log(uuid, item_key, action, details, coins, "
                    + "xp_levels, result, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, key);
                ps.setString(3, action);
                ps.setString(4, details);
                ps.setLong(5, coins);
                ps.setInt(6, xp);
                ps.setString(7, result);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            return (Void) null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Could not write enchant log", ex);
            return null;
        });
    }
}

/** The 3x3 enchanting menu (54 slots). */
final class EnchantMenu extends EscrowMenu {
    // Model A: 3x3 grid, centre = item
    private static final int[] GRID = {11, 12, 13, 20, 21, 22, 29, 30, 31};
    private static final int CENTER = 21;
    // Model B: item slot, book slot
    private static final int ITEM_SLOT = 15;
    private static final int BOOK_SLOT = 33;
    private static final int PREVIEW = 24;
    private static final int CONFIRM = 49;

    private final boolean modelB;
    private final List<Enchantment> tiles = new ArrayList<>();
    private final Map<Enchantment, Integer> selected = new LinkedHashMap<>();
    private int tilePage = 0;
    private boolean busy;

    EnchantMenu(IyhaCore plugin) {
        super(plugin, "ENCHANT");
        this.modelB = plugin.enchants().model().equals("B");
    }

    @Override
    protected Component title() {
        return Ui.c("<light_purple><bold>IYHA Enchanting <gray>(" + (modelB ? "Selection" : "Recipe") + ")");
    }

    @Override
    protected int size() {
        return 54;
    }

    private boolean isInputSlot(int slot) {
        if (modelB) {
            return slot == ITEM_SLOT || slot == BOOK_SLOT;
        }
        for (int g : GRID) {
            if (g == slot) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Ui.filler());
        }
        if (modelB) {
            for (int g : GRID) {
                set(g, Ui.button(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>Place an item"));
            }
            set(ITEM_SLOT, null);
            set(BOOK_SLOT, null);
            set(14, Ui.button(Material.PAPER, "<yellow>Item", "<gray>Put the item to enchant in the slot at the right"));
            set(32, Ui.button(Material.PAPER, "<yellow>Enchanted book (optional)", "<gray>Apply a book's enchantments"));
        } else {
            for (int g : GRID) {
                set(g, null);
            }
            set(10, Ui.button(Material.PAPER, "<yellow>Reagents go around the item", "<gray>The item goes in the centre"));
        }
        set(45, Ui.button(Material.BARRIER, "<red>Close and return items"));
        set(CONFIRM, Ui.button(Material.LIME_CONCRETE, "<green><bold>Enchant"));
        set(53, Ui.button(Material.BOOK, "<yellow>Help", "<gray>/enchants lists every enchantment and its cost"));
        refreshView();
    }

    @Override
    boolean acceptsItemInSlot(int slot) {
        return isInputSlot(slot);
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
        int[] slots = modelB ? new int[]{ITEM_SLOT, BOOK_SLOT} : GRID;
        for (int s : slots) {
            ItemStack it = inv.getItem(s);
            if (it != null && !it.getType().isAir() && !isPlaceholder(it)) {
                list.add(it);
            }
        }
        return list;
    }

    private boolean isPlaceholder(ItemStack it) {
        return it.getType() == Material.BLACK_STAINED_GLASS_PANE || it.getType() == Material.GRAY_STAINED_GLASS_PANE;
    }

    @Override
    protected void clearHeld() {
        Inventory inv = getInventory();
        if (inv == null) {
            return;
        }
        int[] slots = modelB ? new int[]{ITEM_SLOT, BOOK_SLOT} : GRID;
        for (int s : slots) {
            inv.setItem(s, null);
        }
    }

    private ItemStack target() {
        Inventory inv = getInventory();
        ItemStack it = inv.getItem(modelB ? ITEM_SLOT : CENTER);
        return it == null || it.getType().isAir() ? null : it;
    }

    @Override
    protected void onItemsChanged() {
        if (target() == null) {
            selected.clear();
        }
        refreshView();
        snapshot();
    }

    // ------------------------------------------------------------------ rendering

    private void refreshView() {
        Inventory inv = getInventory();
        if (inv == null) {
            return;
        }
        ItemStack item = target();
        if (modelB) {
            renderTiles(item);
        }
        inv.setItem(PREVIEW, previewIcon(item));
    }

    private void renderTiles(ItemStack item) {
        Inventory inv = getInventory();
        tiles.clear();
        if (item != null) {
            tiles.addAll(plugin.enchants().applicable(item));
        }
        int start = tilePage * 9;
        if (start >= tiles.size() && tilePage > 0) {
            tilePage = 0;
            start = 0;
        }
        for (int i = 0; i < GRID.length; i++) {
            int idx = start + i;
            if (item == null || idx >= tiles.size()) {
                inv.setItem(GRID[i], Ui.button(Material.BLACK_STAINED_GLASS_PANE, item == null ? "<dark_gray>Place an item" : "<dark_gray>-"));
                continue;
            }
            Enchantment e = tiles.get(idx);
            int current = plugin.enchants().currentLevel(item, e);
            int next = Math.min(e.getMaxLevel(), current + 1);
            boolean maxed = current >= e.getMaxLevel();
            boolean isSel = selected.containsKey(e);
            boolean clash = !isSel && plugin.enchants().conflicts(item, e);
            long coins = plugin.enchants().coinCost(e, next);
            int xp = plugin.enchants().xpCost(next);
            Material icon = isSel ? Material.ENCHANTED_BOOK : (maxed || clash ? Material.BARRIER : Material.BOOK);
            inv.setItem(GRID[i], Ui.button(icon, (isSel ? "<green>" : "<white>") + prettyName(e) + " " + roman(next),
                    maxed ? "<red>Already at maximum" : clash ? "<red>Conflicts with an enchantment on this item"
                            : "<gray>Cost: <gold>" + plugin.economy().format(coins) + "<gray> + <green>" + xp + " levels",
                    isSel ? "<yellow>Selected. Click to remove" : "<yellow>Click to select"));
        }
        inv.setItem(46, tilePage > 0 ? Ui.button(Material.ARROW, "<yellow>Previous tiles") : Ui.filler());
        inv.setItem(52, start + 9 < tiles.size() ? Ui.button(Material.ARROW, "<yellow>Next tiles") : Ui.filler());
    }

    private ItemStack previewIcon(ItemStack item) {
        if (item == null) {
            return Ui.button(Material.GRAY_STAINED_GLASS_PANE, "<gray>Preview");
        }
        Plan plan = plan();
        if (plan == null) {
            return Ui.button(Material.GRAY_STAINED_GLASS_PANE, "<gray>Nothing to apply yet",
                    modelB ? "<dark_gray>Select tiles or add a book" : "<dark_gray>Add reagents for a recipe");
        }
        return Ui.withLore(plan.result(), List.of("", "<gray>Coins: <gold>" + plugin.economy().format(plan.coins()),
                "<gray>XP levels: <green>" + plan.xp(), "<yellow>Press Enchant to apply"));
    }

    private static String prettyName(Enchantment e) {
        String k = e.getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    // ------------------------------------------------------------------ plan

    private record Plan(ItemStack result, long coins, int xp, String details, Map<Integer, Integer> reagentUse) {}

    /** What would happen on confirm, or null if nothing valid is selected. */
    private Plan plan() {
        ItemStack item = target();
        if (item == null) {
            return null;
        }
        EnchantService svc = plugin.enchants();
        if (modelB) {
            ItemStack result = item.clone();
            long coins = 0;
            int xp = 0;
            StringBuilder details = new StringBuilder();
            ItemStack book = getInventory().getItem(BOOK_SLOT);
            if (book != null && book.getType() == Material.ENCHANTED_BOOK && book.getItemMeta() instanceof EnchantmentStorageMeta sm) {
                for (Map.Entry<Enchantment, Integer> be : sm.getStoredEnchants().entrySet()) {
                    Enchantment e = be.getKey();
                    boolean fits = item.getType() == Material.BOOK || item.getType() == Material.ENCHANTED_BOOK || e.canEnchantItem(item);
                    if (fits && !svc.conflicts(result, e) && be.getValue() > svc.currentLevel(result, e)) {
                        result = svc.apply(result, e, be.getValue());
                        coins += Money.fromDouble(svc.bookFee());
                        details.append("book:").append(e.getKey().getKey()).append(be.getValue()).append(' ');
                    }
                }
            }
            for (Map.Entry<Enchantment, Integer> s : selected.entrySet()) {
                result = svc.apply(result, s.getKey(), s.getValue());
                coins += svc.coinCost(s.getKey(), s.getValue());
                xp += svc.xpCost(s.getValue());
                details.append(s.getKey().getKey().getKey()).append(s.getValue()).append(' ');
            }
            if (details.length() == 0) {
                return null;
            }
            return new Plan(result, coins, xp, details.toString().trim(), Map.of());
        }
        // Model A: count reagents in the ring and pick the best matching recipe
        Map<String, Integer> ring = new HashMap<>();
        for (int g : GRID) {
            if (g == CENTER) {
                continue;
            }
            ItemStack it = getInventory().getItem(g);
            if (it != null && !it.getType().isAir()) {
                ring.merge(it.getType().name(), it.getAmount(), Integer::sum);
            }
        }
        EnchantService.Recipe best = null;
        Enchantment bestEnchant = null;
        for (EnchantService.Recipe r : svc.recipes()) {
            Enchantment e = svc.find(r.enchant());
            if (e == null) {
                continue;
            }
            boolean fits = item.getType() == Material.BOOK || item.getType() == Material.ENCHANTED_BOOK || e.canEnchantItem(item);
            if (!fits || svc.conflicts(item, e) || svc.currentLevel(item, e) >= r.level() || r.level() > e.getMaxLevel()) {
                continue;
            }
            boolean ok = true;
            for (Map.Entry<String, Integer> need : r.reagents().entrySet()) {
                if (ring.getOrDefault(need.getKey(), 0) < need.getValue()) {
                    ok = false;
                    break;
                }
            }
            if (ok && (best == null || r.level() > best.level())) {
                best = r;
                bestEnchant = e;
            }
        }
        if (best == null || svc.enchantCount(item) >= svc.maxPerItem() + (item.getType() == Material.ENCHANTED_BOOK ? 0 : 0)
                && svc.currentLevel(item, bestEnchant) == 0) {
            return null;
        }
        return new Plan(svc.apply(item, bestEnchant, best.level()), svc.coinCost(bestEnchant, best.level()),
                svc.xpCost(best.level()), bestEnchant.getKey().getKey() + best.level(), Map.of());
    }

    // ------------------------------------------------------------------ clicks

    @Override
    protected void handleClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        Player player = (Player) event.getWhoClicked();
        InventoryAction action = event.getAction();
        if (raw >= 0 && raw < 54) {
            if (isInputSlot(raw)) {
                switch (action) {
                    case PICKUP_ALL, PICKUP_HALF, PICKUP_SOME, PICKUP_ONE, PLACE_ALL, PLACE_ONE, PLACE_SOME,
                         SWAP_WITH_CURSOR, MOVE_TO_OTHER_INVENTORY -> event.setCancelled(false);
                    default -> { }
                }
                ItemStack cur = event.getCursor();
                if (action == InventoryAction.PLACE_ALL && cur != null && cur.getAmount() > 1 && raw != BOOK_SLOT) {
                    // only one item per input slot
                    event.setCancelled(true);
                    return;
                }
                afterClick();
                return;
            }
            if (raw == CONFIRM) {
                confirm(player);
            } else if (raw == 45) {
                player.closeInventory();
            } else if (modelB && raw == 46 && tilePage > 0) {
                tilePage--;
                refreshView();
            } else if (modelB && raw == 52 && (tilePage + 1) * 9 < tiles.size()) {
                tilePage++;
                refreshView();
            } else if (modelB) {
                for (int i = 0; i < GRID.length; i++) {
                    if (GRID[i] == raw) {
                        toggleTile(tilePage * 9 + i);
                        return;
                    }
                }
            }
            return;
        }
        if (raw >= 54) {
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                ItemStack cur = event.getCurrentItem();
                if (cur != null && !cur.getType().isAir()) {
                    int slot = firstFreeInput(cur);
                    if (slot >= 0) {
                        ItemStack one = cur.clone();
                        one.setAmount(1);
                        getInventory().setItem(slot, one);
                        if (cur.getAmount() <= 1) {
                            event.setCurrentItem(null);
                        } else {
                            cur.setAmount(cur.getAmount() - 1);
                            event.setCurrentItem(cur);
                        }
                        afterClick();
                    }
                }
            } else {
                afterClick();
            }
        }
    }

    private int firstFreeInput(ItemStack item) {
        Inventory inv = getInventory();
        if (modelB) {
            if (item.getType() == Material.ENCHANTED_BOOK && inv.getItem(ITEM_SLOT) != null && inv.getItem(BOOK_SLOT) == null) {
                return BOOK_SLOT;
            }
            return inv.getItem(ITEM_SLOT) == null ? ITEM_SLOT : (inv.getItem(BOOK_SLOT) == null ? BOOK_SLOT : -1);
        }
        if (inv.getItem(CENTER) == null && item.getMaxStackSize() == 1) {
            return CENTER;
        }
        for (int g : GRID) {
            if (g != CENTER && inv.getItem(g) == null) {
                return g;
            }
        }
        return -1;
    }

    private void toggleTile(int index) {
        ItemStack item = target();
        if (item == null || index >= tiles.size()) {
            return;
        }
        Enchantment e = tiles.get(index);
        if (selected.containsKey(e)) {
            selected.remove(e);
        } else {
            int current = plugin.enchants().currentLevel(item, e);
            if (current >= e.getMaxLevel() || plugin.enchants().conflicts(item, e)) {
                return;
            }
            for (Enchantment other : selected.keySet()) {
                if (other.conflictsWith(e)) {
                    return;
                }
            }
            if (plugin.enchants().enchantCount(item) + selected.size() >= plugin.enchants().maxPerItem()) {
                return;
            }
            selected.put(e, current + 1);
        }
        refreshView();
    }

    // ------------------------------------------------------------------ confirm

    private void confirm(Player player) {
        if (busy) {
            return;
        }
        Plan plan = plan();
        ItemStack item = target();
        if (plan == null || item == null) {
            plugin.lang().send(player, "enchant-nothing");
            return;
        }
        if (player.getLevel() < plan.xp()) {
            plugin.lang().send(player, "enchant-xp", "levels", String.valueOf(plan.xp()));
            return;
        }
        busy = true;
        long coins = plan.coins();
        plugin.economy().sink(player.getUniqueId(), coins, "ENCHANT", plan.details(), player.getName())
                .thenAccept(result -> plugin.sync(() -> finish(player, item, plan, coins, result)))
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.SEVERE, "Enchant payment failed", ex);
                    busy = false;
                    return null;
                });
    }

    private void finish(Player player, ItemStack original, Plan plan, long coins, EconomyService.TxResult result) {
        busy = false;
        if (result.status() != EconomyService.Status.OK) {
            plugin.lang().send(player, result.status() == EconomyService.Status.INSUFFICIENT ? "pay-insufficient"
                    : result.status() == EconomyService.Status.FROZEN ? "economy-frozen" : "internal-error",
                    "balance", plugin.economy().format(result.fromBalance()));
            return;
        }
        if (!player.isOnline() || getInventory() == null || target() == null || !target().isSimilar(original)) {
            // The menu changed while paying. Refund rather than apply to a different item.
            plugin.economy().mint(player.getUniqueId(), coins, "ENCHANT_REFUND", plan.details(), "system");
            return;
        }
        player.giveExpLevels(-plan.xp());
        if (plugin.enchants().rollFailure()) {
            plugin.lang().send(player, "enchant-failed");
            plugin.enchants().log(player, original, "APPLY", plan.details(), coins, plan.xp(), "FAILED");
        } else {
            getInventory().setItem(modelB ? ITEM_SLOT : CENTER, plan.result());
            if (modelB) {
                ItemStack book = getInventory().getItem(BOOK_SLOT);
                if (book != null && book.getType() == Material.ENCHANTED_BOOK) {
                    getInventory().setItem(BOOK_SLOT, null);
                }
            } else {
                for (int g : GRID) {
                    if (g != CENTER) {
                        getInventory().setItem(g, null);
                    }
                }
            }
            plugin.lang().send(player, "enchant-done");
            plugin.enchants().log(player, original, "APPLY", plan.details(), coins, plan.xp(), "OK");
        }
        selected.clear();
        refreshView();
        snapshot();
    }
}

/** Opens the IYHA menu from enchanting tables and prevents the vanilla screen. Also runs the special enchantments. */
final class EnchantListener implements Listener {
    private final IyhaCore plugin;

    EnchantListener(IyhaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTable(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.ENCHANTING_TABLE || event.getPlayer().isSneaking()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("iyha.enchant.use")) {
            plugin.lang().send(player, "no-permission");
            return;
        }
        new EnchantMenu(plugin).open(player);
    }

    /** Safety net: any other way of opening the vanilla enchanting screen is closed. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENCHANTING) {
            event.setCancelled(true);
            if (event.getPlayer() instanceof Player p) {
                plugin.sync(() -> new EnchantMenu(plugin).open(p));
            }
        }
    }

    // ---- IYHA-original special enchantments (stored on the item, not vanilla enchantments) ----

    @EventHandler(ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent event) {
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        if (tool.getType().isAir() || !tool.hasItemMeta()
                || !tool.getItemMeta().getPersistentDataContainer().has(plugin.enchants().telepathy, PersistentDataType.INTEGER)) {
            return;
        }
        Player player = event.getPlayer();
        event.getItems().removeIf(drop -> {
            Map<Integer, ItemStack> left = player.getInventory().addItem(drop.getItemStack());
            if (left.isEmpty()) {
                return true;
            }
            drop.setItemStack(left.values().iterator().next());
            return false;
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (weapon.getType().isAir() || !weapon.hasItemMeta()) {
            return;
        }
        Integer level = weapon.getItemMeta().getPersistentDataContainer().get(plugin.enchants().lifesteal, PersistentDataType.INTEGER);
        if (level == null || !(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        double max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                ? player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20.0;
        player.setHealth(Math.min(max, player.getHealth() + 0.5 * level));
    }
}

/** /enchants [name] and /ienchant give|reload (admin). */
final class EnchantsCmd extends Cmd {
    EnchantsCmd(IyhaCore plugin) {
        super(plugin, "enchants", "iyha.enchant.info", 2000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        EnchantService svc = plugin.enchants();
        if (args.length == 0) {
            sender.sendMessage(Ui.c("<light_purple>Right-click an enchanting table to open IYHA enchanting."));
            sender.sendMessage(Ui.c("<gray>Use <white>/enchants <name><gray> for costs, for example <white>/enchants sharpness"));
            return;
        }
        Enchantment e = svc.find(args[0]);
        if (e == null) {
            plugin.lang().send(sender, "enchant-unknown", "name", args[0]);
            return;
        }
        sender.sendMessage(Ui.c("<light_purple>" + e.getKey().getKey() + " <gray>(max level " + e.getMaxLevel() + ")"));
        for (int lvl = 1; lvl <= e.getMaxLevel(); lvl++) {
            sender.sendMessage(Ui.c("<gray> Level " + lvl + ": <gold>" + plugin.economy().format(svc.coinCost(e, lvl))
                    + "<gray> + <green>" + svc.xpCost(lvl) + " levels"));
        }
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        return args.length == 1 ? filter(Registry.ENCHANTMENT.stream().map(en -> en.getKey().getKey()).toList(), args[0]) : List.of();
    }
}

final class IEnchantCmd extends Cmd {
    IEnchantCmd(IyhaCore plugin) {
        super(plugin, "ienchant", "iyha.admin.enchant", 0);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.enchants().reload();
            plugin.lang().send(sender, "reload-done");
            return;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            Enchantment e = plugin.enchants().find(args[2]);
            if (target == null || e == null) {
                plugin.lang().send(sender, "unknown-player", "name", args[1]);
                return;
            }
            int level;
            try {
                level = Math.max(1, Math.min(e.getMaxLevel(), Integer.parseInt(args[3])));
            } catch (NumberFormatException ex) {
                plugin.lang().send(sender, "invalid-amount");
                return;
            }
            Give.giveOrClaim(plugin, target, plugin.enchants().book(e, level), "ADMIN_GIVE");
            plugin.audit().log(sender.getName(), "ENCHANT_BOOK_GIVE", target.getName(), e.getKey().getKey() + " " + level);
            plugin.lang().send(sender, "item-given");
            return;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("special") && sender instanceof Player holder) {
            ItemStack hand = holder.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                plugin.lang().send(sender, "sell-nothing");
                return;
            }
            NamespacedKey key = args[1].equalsIgnoreCase("telepathy") ? plugin.enchants().telepathy
                    : args[1].equalsIgnoreCase("lifesteal") ? plugin.enchants().lifesteal : null;
            if (key == null) {
                plugin.lang().send(sender, "enchant-unknown", "name", args[1]);
                return;
            }
            int level;
            try {
                level = Math.max(1, Math.min(5, Integer.parseInt(args[2])));
            } catch (NumberFormatException ex) {
                plugin.lang().send(sender, "invalid-amount");
                return;
            }
            String label2 = args[1].substring(0, 1).toUpperCase(Locale.ROOT) + args[1].substring(1).toLowerCase(Locale.ROOT);
            hand.editMeta(meta -> {
                meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, level);
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(Ui.c("<light_purple>" + label2 + " " + level + " <dark_gray>(IYHA special)"));
                meta.lore(lore);
            });
            plugin.audit().log(sender.getName(), "SPECIAL_ENCHANT", args[1], String.valueOf(level));
            plugin.lang().send(sender, "item-given");
            return;
        }
        plugin.lang().send(sender, "usage", "usage", "/ienchant give <player> <enchant> <level> | special <telepathy|lifesteal> <level> | reload");
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("give", "special", "reload"), args[0]);
        }
        if (args.length == 2) {
            return onlineNames(args[1]);
        }
        if (args.length == 3) {
            return filter(Registry.ENCHANTMENT.stream().map(en -> en.getKey().getKey()).toList(), args[2]);
        }
        return List.of();
    }
}
