package net.iyha.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Private messages and ignore lists. */
final class SocialService implements Listener {
    private final IyhaCore plugin;
    private final Map<UUID, Set<UUID>> ignores = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastMessaged = new ConcurrentHashMap<>();

    SocialService(IyhaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        plugin.database().supplyIo(c -> {
            Set<UUID> set = ConcurrentHashMap.newKeySet();
            try (PreparedStatement ps = c.prepareStatement("SELECT ignored FROM ignores WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        set.add(UUID.fromString(rs.getString(1)));
                    }
                }
            }
            return set;
        }).thenAccept(set -> ignores.put(id, set)).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Could not load ignore list", ex);
            return null;
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        ignores.remove(id);
        lastMessaged.remove(id);
    }

    /** Chat from ignored players is hidden from the ignoring player. */
    @EventHandler
    public void onChat(AsyncChatEvent event) {
        UUID sender = event.getPlayer().getUniqueId();
        event.viewers().removeIf(audience -> audience instanceof Player viewer && isIgnoring(viewer.getUniqueId(), sender));
    }

    boolean isIgnoring(UUID who, UUID target) {
        Set<UUID> set = ignores.get(who);
        return set != null && set.contains(target);
    }

    Set<UUID> ignoreList(UUID who) {
        return ignores.getOrDefault(who, Set.of());
    }

    void setIgnore(UUID who, UUID target, boolean ignore) {
        Set<UUID> set = ignores.computeIfAbsent(who, k -> ConcurrentHashMap.newKeySet());
        if (ignore) {
            set.add(target);
        } else {
            set.remove(target);
        }
        plugin.database().supplySerial(c -> {
            String sql = ignore ? plugin.database().insertIgnore() + "ignores(uuid, ignored) VALUES (?, ?)"
                    : "DELETE FROM ignores WHERE uuid = ? AND ignored = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, who.toString());
                ps.setString(2, target.toString());
                ps.executeUpdate();
            }
            return (Void) null;
        });
    }

    void message(Player from, Player to, String text) {
        if (isIgnoring(to.getUniqueId(), from.getUniqueId()) && !from.hasPermission("iyha.social.bypass")) {
            plugin.lang().send(from, "msg-ignored", "player", to.getName());
            return;
        }
        if (!plugin.settings().pmEnabled(to.getUniqueId()) && !from.hasPermission("iyha.social.bypass")) {
            plugin.lang().send(from, "msg-disabled", "player", to.getName());
            return;
        }
        plugin.lang().send(from, "msg-sent", "player", to.getName(), "text", text);
        plugin.lang().send(to, "msg-received", "player", from.getName(), "text", text);
        lastMessaged.put(from.getUniqueId(), to.getUniqueId());
        lastMessaged.put(to.getUniqueId(), from.getUniqueId());
    }

    Player lastOf(Player p) {
        UUID id = lastMessaged.get(p.getUniqueId());
        return id == null ? null : Bukkit.getPlayer(id);
    }
}

/** /msg /reply /msgtoggle /ignore /unignore /nightvision /ping */
final class SocialCmd extends Cmd {
    private final String action;

    SocialCmd(IyhaCore plugin, String name, String permission, String action) {
        super(plugin, name, permission, 1000);
        this.action = action;
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        switch (action) {
            case "msg" -> {
                if (args.length < 2) {
                    plugin.lang().send(player, "usage", "usage", "/" + label + " <player> <message>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null || target.equals(player)) {
                    plugin.lang().send(player, "unknown-player", "name", args[0]);
                    return;
                }
                plugin.social().message(player, target, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            }
            case "reply" -> {
                Player target = plugin.social().lastOf(player);
                if (args.length < 1 || target == null) {
                    plugin.lang().send(player, "msg-nobody");
                    return;
                }
                plugin.social().message(player, target, String.join(" ", args));
            }
            case "msgtoggle" -> {
                boolean on = plugin.settings().toggle(player.getUniqueId(), "pm_toggle");
                plugin.lang().send(player, on ? "msgtoggle-on" : "msgtoggle-off");
            }
            case "ignore" -> ignore(player, args, true);
            case "unignore" -> ignore(player, args, false);
            case "nv" -> {
                if (player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                    player.removePotionEffect(PotionEffectType.NIGHT_VISION);
                    plugin.lang().send(player, "nv-off");
                } else {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false));
                    plugin.lang().send(player, "nv-on");
                }
            }
            default -> {
                Player target = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : player;
                if (target == null) {
                    plugin.lang().send(player, "unknown-player", "name", args[0]);
                    return;
                }
                plugin.lang().send(player, "ping", "player", target.getName(), "ping", String.valueOf(target.getPing()));
            }
        }
    }

    private void ignore(Player player, String[] args, boolean add) {
        if (args.length != 1) {
            plugin.lang().send(player, "usage", "usage", "/" + (add ? "ignore" : "unignore") + " <player>");
            return;
        }
        guard(plugin.players().resolve(args[0]).thenAccept(found -> plugin.sync(() -> {
            if (found.isEmpty() || found.get().uuid().equals(player.getUniqueId())) {
                plugin.lang().send(player, "unknown-player", "name", args[0]);
                return;
            }
            plugin.social().setIgnore(player.getUniqueId(), found.get().uuid(), add);
            plugin.lang().send(player, add ? "ignore-added" : "ignore-removed", "player", found.get().name());
        })), player);
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        return args.length == 1 && !action.equals("msgtoggle") && !action.equals("nv") ? onlineNames(args[0]) : List.of();
    }
}

/** /menu: shortcuts to every system. */
final class MainMenu extends Menu {
    private record Item(int slot, Material icon, String name, String command) {}

    private static final List<Item> ITEMS = List.of(
            new Item(10, Material.EMERALD, "<green><bold>Shop", "shop"),
            new Item(11, Material.GOLD_INGOT, "<gold><bold>Sell", "sell"),
            new Item(12, Material.CHEST, "<yellow><bold>Auction House", "ah"),
            new Item(13, Material.BOOK, "<aqua><bold>Buy orders", "orders"),
            new Item(14, Material.PLAYER_HEAD, "<white><bold>Profile", "profile"),
            new Item(15, Material.NETHER_STAR, "<light_purple><bold>Ranks", "ranks"),
            new Item(16, Material.ENDER_PEARL, "<aqua><bold>Warps", "warps"),
            new Item(19, Material.RED_BED, "<red><bold>Homes", "homes"),
            new Item(20, Material.GOLDEN_HELMET, "<gold><bold>Leaderboards", "leaderboard"),
            new Item(21, Material.CLOCK, "<yellow><bold>Events", "event list"),
            new Item(22, Material.COMPARATOR, "<gray><bold>Settings", "settings"),
            new Item(23, Material.KNOWLEDGE_BOOK, "<white><bold>Help", "help"));

    private final Player player;

    MainMenu(IyhaCore plugin, Player player) {
        super(plugin);
        this.player = player;
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>IYHA SMP");
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
        for (Item i : ITEMS) {
            set(i.slot(), Ui.button(i.icon(), i.name(), "<yellow>Click to open"));
        }
        set(26, Ui.button(Material.BARRIER, "<red>Close"));
    }

    @Override
    void onClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw == 26) {
            player.closeInventory();
            return;
        }
        for (Item i : ITEMS) {
            if (i.slot() == raw) {
                player.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> player.performCommand(i.command()));
                return;
            }
        }
    }
}

/** /menu */
final class MenuCmd extends Cmd {
    MenuCmd(IyhaCore plugin) {
        super(plugin, "menu", "iyha.menu", 1000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (sender instanceof Player player) {
            new MainMenu(plugin, player).open(player);
        } else {
            plugin.lang().send(sender, "players-only");
        }
    }
}

/** /help /rules /discord /store /media: text from config.yml (info.<name>). */
final class InfoCmd extends Cmd {
    private final String key;

    InfoCmd(IyhaCore plugin, String name) {
        super(plugin, name, "iyha.info", 2000);
        this.key = name;
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        List<String> lines = plugin.getConfig().getStringList("info." + key);
        if (lines.isEmpty()) {
            plugin.lang().send(sender, "info-empty");
            return;
        }
        for (String line : lines) {
            sender.sendMessage(Ui.MM.deserialize(line));
        }
    }
}
