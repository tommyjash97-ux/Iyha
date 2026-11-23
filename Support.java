package net.iyha.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** All player-facing text lives in messages.yml (MiniMessage format). */
final class Lang {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final IyhaCore plugin;
    private YamlConfiguration messages;

    Lang(IyhaCore plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
        InputStream defaults = plugin.getResource("messages.yml");
        if (defaults != null) {
            messages.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
    }

    /** Sends message {@code key}. Extra arguments are name/value pairs for placeholders. */
    void send(CommandSender to, String key, Object... pairs) {
        to.sendMessage(component(key, pairs));
    }

    Component component(String key, Object... pairs) {
        String raw = messages.getString(key, "<red>Missing message: " + key);
        String full = raw.startsWith("!") ? raw.substring(1) : messages.getString("prefix", "") + raw;
        TagResolver.Builder builder = TagResolver.builder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            builder.resolver(Placeholder.unparsed(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1])));
        }
        return MM.deserialize(full, builder.build());
    }
}

/** Simple per-player, per-command cooldowns. */
final class Cooldowns {
    private final ConcurrentHashMap<String, Long> last = new ConcurrentHashMap<>();

    /** Remaining milliseconds without starting the cooldown. */
    long remaining(UUID id, String key, long cooldownMs) {
        Long previous = last.get(id + ":" + key);
        if (previous == null) {
            return 0L;
        }
        return Math.max(0L, cooldownMs - (System.currentTimeMillis() - previous));
    }

    void start(UUID id, String key) {
        last.put(id + ":" + key, System.currentTimeMillis());
    }

    /** Returns 0 and starts the cooldown if the action is free, otherwise the remaining milliseconds. */
    long tryUse(UUID id, String key, long cooldownMs) {
        long now = System.currentTimeMillis();
        long[] remaining = {0L};
        last.compute(id + ":" + key, (k, previous) -> {
            if (previous != null && now - previous < cooldownMs) {
                remaining[0] = cooldownMs - (now - previous);
                return previous;
            }
            return now;
        });
        return remaining[0];
    }
}

/** Writes admin and security relevant actions to the audit_log table. */
final class AuditService {
    private final IyhaCore plugin;

    AuditService(IyhaCore plugin) {
        this.plugin = plugin;
    }

    void log(String actor, String action, String target, String details) {
        long now = System.currentTimeMillis();
        plugin.database().supplySerial(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit_log(actor, action, target, details, created_at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, actor);
                ps.setString(2, action);
                ps.setString(3, target);
                ps.setString(4, details);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Could not write audit log", ex);
            return null;
        });
    }
}
