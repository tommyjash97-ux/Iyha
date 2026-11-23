package net.iyha.core;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * PlaceholderAPI expansion "iyha": %iyha_balance%, %iyha_kills%, %iyha_deaths%, %iyha_kdr%, %iyha_killstreak%,
 * %iyha_playtime%, %iyha_combat%, %iyha_rank%, %iyha_prefix% and %iyha_top_<type>_<n>_name|value% for holograms.
 */
final class PapiHook extends PlaceholderExpansion implements Hook {
    private final IyhaCore plugin;

    PapiHook(IyhaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        register();
    }

    @Override
    public void disable() {
        unregister();
    }

    @Override
    public String getIdentifier() {
        return "iyha";
    }

    @Override
    public String getAuthor() {
        return "IYHA";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.startsWith("top_")) {
            return top(p);
        }
        if (player == null) {
            return "";
        }
        UUID id = player.getUniqueId();
        Player online = player.getPlayer();
        StatsService.Stats s = plugin.stats().of(id);
        switch (p) {
            case "balance":
                return plugin.economy().format(plugin.economy().cachedBalance(id));
            case "balance_raw":
                return String.valueOf(plugin.economy().cachedBalance(id) / (double) Money.SCALE);
            case "kills":
                return s == null ? "0" : String.valueOf(s.kills);
            case "deaths":
                return s == null ? "0" : String.valueOf(s.deaths);
            case "killstreak":
                return s == null ? "0" : String.valueOf(s.streak);
            case "kdr":
                return s == null ? "0" : String.valueOf(s.deaths == 0 ? s.kills : Math.round(s.kills * 100.0 / s.deaths) / 100.0);
            case "playtime":
                return s == null ? "0h 0m" : StatsService.duration(s.playtime);
            case "combat":
                return online != null && plugin.combat().isTagged(online) ? "true" : "false";
            case "combat_seconds":
                return online == null ? "0" : String.valueOf(plugin.combat().secondsLeft(online));
            case "rank":
                return online == null ? "" : plugin.ranks().rankOf(online).name();
            case "prefix":
                return online == null ? "" : plugin.ranks().rankOf(online).prefix();
            default:
                return null;
        }
    }

    /** top_<type>_<position>_<name|value> where type may contain underscores. */
    private String top(String p) {
        String[] parts = p.split("_");
        if (parts.length < 4) {
            return "";
        }
        String field = parts[parts.length - 1];
        int position;
        try {
            position = Integer.parseInt(parts[parts.length - 2]);
        } catch (NumberFormatException ex) {
            return "";
        }
        String type = String.join("_", List.of(parts).subList(1, parts.length - 2));
        LeaderboardService.Type t = LeaderboardService.Type.of(type);
        if (t == null) {
            return "";
        }
        List<LeaderboardService.Row> rows = plugin.leaderboards().cached(t);
        if (position < 1 || position > rows.size()) {
            return field.equals("name") ? "-" : "";
        }
        LeaderboardService.Row row = rows.get(position - 1);
        return field.equals("name") ? row.name() : plugin.leaderboards().format(t, row.value());
    }
}
