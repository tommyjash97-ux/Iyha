package net.iyha.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ranks. LuckPerms owns the groups and permissions; this reads them through standard permission checks
 * ("group.<id>") so no LuckPerms API is needed. Rank definitions live in config.yml under ranks.
 */
final class RankService {
    record Rank(String id, String name, String prefix, String group, Material icon, List<String> perks, String obtain,
                boolean shown, boolean isDefault) {}

    private final IyhaCore plugin;
    private final List<Rank> ranks = new ArrayList<>();

    RankService(IyhaCore plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        ranks.clear();
        for (Map<?, ?> m : plugin.getConfig().getMapList("ranks")) {
            String id = String.valueOf(m.get("id"));
            Object icon = m.get("icon");
            Material mat = Material.matchMaterial(icon == null ? "PAPER" : String.valueOf(icon));
            List<String> perks = new ArrayList<>();
            if (m.get("perks") instanceof List<?> list) {
                for (Object o : list) {
                    perks.add(String.valueOf(o));
                }
            }
            ranks.add(new Rank(id, String.valueOf(m.getOrDefault("name", id)), String.valueOf(m.getOrDefault("prefix", "")),
                    String.valueOf(m.getOrDefault("group", id)), mat == null ? Material.PAPER : mat, perks,
                    String.valueOf(m.getOrDefault("obtain", "")), Boolean.parseBoolean(String.valueOf(m.getOrDefault("shown", "true"))),
                    Boolean.parseBoolean(String.valueOf(m.getOrDefault("default", "false")))));
        }
    }

    List<Rank> all() {
        return ranks;
    }

    /** Highest configured rank whose permission group the player is in. */
    Rank rankOf(Player player) {
        Rank fallback = null;
        for (Rank r : ranks) {
            if (r.isDefault()) {
                fallback = r;
            }
            if (!r.isDefault() && player.hasPermission("group." + r.group())) {
                return r;
            }
        }
        return fallback != null ? fallback : new Rank("member", "Member", "", "default", Material.PAPER, List.of(), "", true, true);
    }
}

/** /ranks */
final class RanksMenu extends Menu {
    private final Player player;

    RanksMenu(IyhaCore plugin, Player player) {
        super(plugin);
        this.player = player;
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>Ranks");
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Ui.filler());
        }
        RankService.Rank mine = plugin.ranks().rankOf(player);
        int slot = 10;
        for (RankService.Rank r : plugin.ranks().all()) {
            if (!r.shown() || slot > 43) {
                continue;
            }
            List<String> lore = new ArrayList<>();
            lore.add(r.id().equals(mine.id()) ? "<green>Your current rank" : " ");
            for (String perk : r.perks()) {
                lore.add("<gray>- <white>" + perk);
            }
            if (!r.obtain().isEmpty()) {
                lore.add(" ");
                lore.add("<yellow>" + r.obtain());
            }
            set(slot, Ui.button(r.icon(), r.name(), lore.toArray(new String[0])));
            slot += (slot % 9 == 7) ? 3 : 1;
        }
        set(49, Ui.button(Material.BARRIER, "<red>Close"));
    }

    @Override
    void onClick(InventoryClickEvent event) {
        if (event.getRawSlot() == 49) {
            player.closeInventory();
        }
    }
}

final class RanksCmd extends Cmd {
    RanksCmd(IyhaCore plugin) {
        super(plugin, "ranks", "iyha.ranks.view", 2000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (sender instanceof Player player) {
            new RanksMenu(plugin, player).open(player);
        } else {
            plugin.lang().send(sender, "players-only");
        }
    }
}
