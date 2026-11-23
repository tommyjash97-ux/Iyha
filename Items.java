package net.iyha.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Keys stored on custom items. */
final class ItemKeys {
    final NamespacedKey id;
    final NamespacedKey uid;

    ItemKeys(IyhaCore plugin) {
        this.id = new NamespacedKey(plugin, "item_id");
        this.uid = new NamespacedKey(plugin, "item_uid");
    }

    String customId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(id, PersistentDataType.STRING);
    }

    String uidOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(uid, PersistentDataType.STRING);
    }

    /** Search/index key: the material name, or iyha:<id> for custom items. */
    String keyOf(ItemStack item) {
        String custom = customId(item);
        return custom != null ? "iyha:" + custom : item.getType().getKey().getKey();
    }
}

/** Auction categories. Materials are matched by name so unknown constants never break the build. */
final class Categories {
    static final List<String> ALL = List.of("blocks", "tools", "weapons", "armor", "books", "potions", "food",
            "farming", "redstone", "special", "misc");

    private static final Set<String> FARMING = Set.of("WHEAT", "WHEAT_SEEDS", "CARROT", "POTATO", "BEETROOT",
            "BEETROOT_SEEDS", "MELON_SLICE", "PUMPKIN_SEEDS", "SUGAR_CANE", "BONE_MEAL", "NETHER_WART",
            "COCOA_BEANS", "SWEET_BERRIES", "KELP", "BAMBOO", "HAY_BLOCK", "MELON_SEEDS", "SUGAR");
    private static final Set<String> REDSTONE = Set.of("REDSTONE", "REPEATER", "COMPARATOR", "PISTON",
            "STICKY_PISTON", "OBSERVER", "HOPPER", "DISPENSER", "DROPPER", "LEVER", "REDSTONE_TORCH",
            "REDSTONE_BLOCK", "TNT", "TARGET", "DAYLIGHT_DETECTOR", "TRIPWIRE_HOOK", "SLIME_BLOCK", "HONEY_BLOCK");

    private Categories() {}

    static String of(ItemStack item, ItemKeys keys) {
        if (keys.customId(item) != null) {
            return "special";
        }
        Material m = item.getType();
        String n = m.name();
        if (m == Material.ENCHANTED_BOOK) {
            return "books";
        }
        if (n.endsWith("_SWORD") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT")
                || n.equals("MACE") || n.endsWith("_SPEAR")) {
            return "weapons";
        }
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || n.equals("FISHING_ROD") || n.equals("SHEARS") || n.equals("FLINT_AND_STEEL")) {
            return "tools";
        }
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.equals("SHIELD") || n.equals("ELYTRA")) {
            return "armor";
        }
        if (n.contains("POTION")) {
            return "potions";
        }
        if (FARMING.contains(n)) {
            return "farming";
        }
        if (REDSTONE.contains(n)) {
            return "redstone";
        }
        if (m.isEdible()) {
            return "food";
        }
        if (m.isBlock()) {
            return "blocks";
        }
        return "misc";
    }
}

/** Basic sanity checks for items entering the markets. */
final class ItemRules {
    private final IyhaCore plugin;
    private volatile Set<String> banned = Set.of();
    private volatile int maxEnchantLevel = 10;

    ItemRules(IyhaCore plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        Set<String> set = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("market.banned-materials")) {
            set.add(s.toUpperCase(Locale.ROOT));
        }
        banned = set;
        maxEnchantLevel = plugin.getConfig().getInt("market.max-enchant-level", 10);
    }

    boolean allowed(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() < 1 || item.getAmount() > item.getMaxStackSize()) {
            return false;
        }
        if (banned.contains(item.getType().name())) {
            return false;
        }
        for (Map.Entry<Enchantment, Integer> e : item.getEnchantments().entrySet()) {
            if (e.getValue() > maxEnchantLevel) {
                return false;
            }
        }
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof EnchantmentStorageMeta storage) {
                for (int level : storage.getStoredEnchants().values()) {
                    if (level > maxEnchantLevel) {
                        return false;
                    }
                }
            }
            Component name = meta.displayName();
            if (name != null && PlainTextComponentSerializer.plainText().serialize(name).length() > 128) {
                return false;
            }
        }
        return true;
    }
}

/** Custom items defined in items.yml. Every created stack carries an id and a unique instance id. */
final class CustomItems implements Listener {
    record Def(String id, Material material, String name, List<String> lore, String model, String ability,
               double value, String effect, int seconds, int amplifier) {}

    private final IyhaCore plugin;
    private final Map<String, Def> defs = new LinkedHashMap<>();

    CustomItems(IyhaCore plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        defs.clear();
        File file = new File(plugin.getDataFolder(), "items.yml");
        if (!file.exists()) {
            plugin.saveResource("items.yml", false);
        }
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("items");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Material mat = Material.matchMaterial(s.getString("material", "PAPER"));
            if (mat == null) {
                plugin.getLogger().warning("items.yml: unknown material for " + id);
                continue;
            }
            defs.put(id.toLowerCase(Locale.ROOT), new Def(id.toLowerCase(Locale.ROOT), mat, s.getString("name", id),
                    s.getStringList("lore"), s.getString("item-model", ""), s.getString("ability", "none"),
                    s.getDouble("value", 0), s.getString("effect", "SPEED"), s.getInt("seconds", 30),
                    s.getInt("amplifier", 0)));
        }
    }

    Set<String> ids() {
        return defs.keySet();
    }

    boolean exists(String id) {
        return defs.containsKey(id.toLowerCase(Locale.ROOT));
    }

    /** Creates a stack and registers its instance id (for duplicate detection). */
    ItemStack create(String id, int amount, String createdBy) {
        Def def = defs.get(id.toLowerCase(Locale.ROOT));
        if (def == null) {
            return null;
        }
        ItemStack item = new ItemStack(def.material(), Math.max(1, Math.min(amount, def.material().getMaxStackSize())));
        String uid = UUID.randomUUID().toString();
        item.editMeta(meta -> {
            meta.displayName(Ui.c(def.name()));
            List<Component> lore = new ArrayList<>();
            for (String line : def.lore()) {
                lore.add(Ui.c(line));
            }
            meta.lore(lore);
            if (!def.model().isEmpty()) {
                NamespacedKey model = NamespacedKey.fromString(def.model());
                if (model != null) {
                    meta.setItemModel(model);
                }
            }
            meta.getPersistentDataContainer().set(plugin.itemKeys().id, PersistentDataType.STRING, def.id());
            meta.getPersistentDataContainer().set(plugin.itemKeys().uid, PersistentDataType.STRING, uid);
        });
        int count = item.getAmount();
        long now = System.currentTimeMillis();
        plugin.database().supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO item_instances(uid, item_id, amount, created_by, created_at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, uid);
                ps.setString(2, def.id());
                ps.setInt(3, count);
                ps.setString(4, createdBy);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            return (Void) null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Could not register item instance", ex);
            return null;
        });
        return item;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        String id = plugin.itemKeys().customId(hand);
        if (id == null) {
            return;
        }
        Def def = defs.get(id);
        if (def == null || def.ability().equals("none")) {
            return;
        }
        event.setCancelled(true);
        switch (def.ability()) {
            case "voucher" -> redeemVoucher(player, hand, def);
            case "effect" -> {
                PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(def.effect().toLowerCase(Locale.ROOT)));
                if (type == null) {
                    plugin.lang().send(player, "internal-error");
                    return;
                }
                consumeOne(player, hand);
                player.addPotionEffect(new PotionEffect(type, def.seconds() * 20, def.amplifier()));
                plugin.lang().send(player, "item-used");
            }
            default -> { }
        }
    }

    private void consumeOne(Player player, ItemStack hand) {
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
        }
        player.saveData();
    }

    private void redeemVoucher(Player player, ItemStack hand, Def def) {
        long cents = Money.fromDouble(def.value());
        ItemStack single = hand.clone();
        single.setAmount(1);
        consumeOne(player, hand);
        plugin.economy().mint(player.getUniqueId(), cents, "VOUCHER", def.id(), player.getName())
                .thenAccept(result -> plugin.sync(() -> {
                    if (result.status() == EconomyService.Status.OK) {
                        plugin.lang().send(player, "voucher-redeemed", "amount", plugin.economy().format(cents));
                    } else {
                        Give.giveOrClaim(plugin, player, single, "VOUCHER_REFUND");
                        plugin.lang().send(player, "economy-frozen");
                    }
                }));
    }

    /** Scans online inventories; a unique id present in more items than were ever created is a duplicate. */
    void scanForDuplicates() {
        Map<String, Integer> found = new HashMap<>();
        Map<String, String> holder = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<ItemStack> all = new ArrayList<>();
            all.addAll(java.util.Arrays.asList(p.getInventory().getContents()));
            all.addAll(java.util.Arrays.asList(p.getEnderChest().getContents()));
            for (ItemStack it : all) {
                String uid = plugin.itemKeys().uidOf(it);
                if (uid != null) {
                    found.merge(uid, it.getAmount(), Integer::sum);
                    holder.putIfAbsent(uid, p.getName());
                }
            }
        }
        if (found.isEmpty()) {
            return;
        }
        List<String> uids = new ArrayList<>(found.keySet());
        plugin.database().supplyIo(c -> {
            Map<String, Integer> registered = new HashMap<>();
            String marks = String.join(",", java.util.Collections.nCopies(uids.size(), "?"));
            try (PreparedStatement ps = c.prepareStatement("SELECT uid, amount FROM item_instances WHERE uid IN (" + marks + ")")) {
                for (int i = 0; i < uids.size(); i++) {
                    ps.setString(i + 1, uids.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        registered.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
            return registered;
        }).thenAccept(registered -> {
            for (Map.Entry<String, Integer> e : found.entrySet()) {
                int reg = registered.getOrDefault(e.getKey(), 0);
                if (e.getValue() > reg) {
                    String msg = "Possible DUPLICATED item " + e.getKey() + " (found " + e.getValue()
                            + ", created " + reg + ") near " + holder.get(e.getKey());
                    plugin.getLogger().severe(msg);
                    plugin.audit().log("system", "DUPE_SUSPECT", holder.get(e.getKey()), msg);
                    plugin.sync(() -> Bukkit.broadcast(Ui.c("<red>[IYHA] " + msg), "iyha.admin.audit"));
                }
            }
        });
    }
}

/** /iitem give <player> <id> [amount] | list | info */
final class IItemCmd extends Cmd {
    IItemCmd(IyhaCore plugin) {
        super(plugin, "iitem", "iyha.admin.items", 0);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> sender.sendMessage(Ui.c("<gray>Custom items: <white>" + String.join(", ", plugin.customItems().ids())));
            case "info" -> {
                if (sender instanceof Player p) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    String id = plugin.itemKeys().customId(hand);
                    sender.sendMessage(Ui.c("<gray>Item id: <white>" + (id == null ? "(not a custom item)" : id)
                            + " <gray>uid: <white>" + plugin.itemKeys().uidOf(hand)));
                }
            }
            case "give" -> {
                if (args.length < 3) {
                    plugin.lang().send(sender, "usage", "usage", "/iitem give <player> <id> [amount]");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null || !plugin.customItems().exists(args[2])) {
                    plugin.lang().send(sender, "unknown-player", "name", args[1]);
                    return;
                }
                int amount = 1;
                if (args.length > 3) {
                    try {
                        amount = Integer.parseInt(args[3]);
                    } catch (NumberFormatException ex) {
                        plugin.lang().send(sender, "invalid-amount");
                        return;
                    }
                }
                ItemStack item = plugin.customItems().create(args[2], amount, sender.getName());
                Give.giveOrClaim(plugin, target, item, "ADMIN_GIVE");
                plugin.audit().log(sender.getName(), "ITEM_GIVE", target.getName(), args[2] + " x" + amount);
                plugin.lang().send(sender, "item-given");
            }
            case "reload" -> {
                plugin.customItems().reload();
                plugin.lang().send(sender, "reload-done");
            }
            default -> plugin.lang().send(sender, "usage", "usage", "/iitem give|list|info|reload");
        }
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("give", "list", "info", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return onlineNames(args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(new ArrayList<>(plugin.customItems().ids()), args[2]);
        }
        return List.of();
    }
}
