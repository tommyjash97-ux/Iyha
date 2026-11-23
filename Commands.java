package net.iyha.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Base class for every IYHA command: permission check, cooldown, error handling and tab completion
 * helpers. Every message comes from messages.yml.
 */
abstract class Cmd implements TabExecutor {
    protected final IyhaCore plugin;
    private final String name;
    private final String permission;
    private final long cooldownMs;

    protected Cmd(IyhaCore plugin, String name, String permission, long cooldownMs) {
        this.plugin = plugin;
        this.name = name;
        this.permission = permission;
        this.cooldownMs = cooldownMs;
    }

    @Override
    public final boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (permission != null && !sender.hasPermission(permission)) {
            plugin.lang().send(sender, "no-permission");
            return true;
        }
        if (sender instanceof Player player && cooldownMs > 0 && !player.hasPermission("iyha.bypass.cooldown")) {
            long left = plugin.cooldowns().tryUse(player.getUniqueId(), name, cooldownMs);
            if (left > 0) {
                plugin.lang().send(sender, "cooldown", "seconds", String.valueOf((left + 999) / 1000));
                return true;
            }
        }
        try {
            run(sender, label, args);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Command /" + name + " failed", ex);
            plugin.lang().send(sender, "internal-error");
        }
        return true;
    }

    @Override
    public final List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (permission != null && !sender.hasPermission(permission)) {
            return List.of();
        }
        try {
            return tab(sender, args);
        } catch (Exception ex) {
            return List.of();
        }
    }

    protected abstract void run(CommandSender sender, String label, String[] args) throws Exception;

    protected List<String> tab(CommandSender sender, String[] args) {
        return List.of();
    }

    /** Reports any exception from an async chain to the sender and the console. */
    protected <T> CompletableFuture<T> guard(CompletableFuture<T> future, CommandSender sender) {
        return future.exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Async error in /" + name, ex);
            plugin.sync(() -> plugin.lang().send(sender, "internal-error"));
            return null;
        });
    }

    protected static List<String> filter(Collection<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p))
                .sorted()
                .collect(Collectors.toList());
    }

    protected static List<String> onlineNames(String prefix) {
        return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), prefix);
    }
}

/** /balance [player] */
final class BalanceCmd extends Cmd {
    BalanceCmd(IyhaCore plugin) {
        super(plugin, "balance", "iyha.economy.balance", 1000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        EconomyService eco = plugin.economy();
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.lang().send(sender, "players-only");
                return;
            }
            guard(eco.balance(player.getUniqueId()).thenAccept(balance -> plugin.sync(() ->
                    plugin.lang().send(sender, "bal-self", "balance", eco.format(balance)))), sender);
            return;
        }
        if (!sender.hasPermission("iyha.economy.balance.others")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        guard(plugin.players().resolve(args[0]).thenCompose(found -> {
            if (found.isEmpty()) {
                plugin.sync(() -> plugin.lang().send(sender, "unknown-player", "name", args[0]));
                return CompletableFuture.<Void>completedFuture(null);
            }
            PlayerDataService.PlayerRef ref = found.get();
            return eco.balance(ref.uuid()).thenAccept(balance -> plugin.sync(() ->
                    plugin.lang().send(sender, "bal-other", "player", ref.name(), "balance", eco.format(balance))));
        }), sender);
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        return args.length == 1 ? onlineNames(args[0]) : List.of();
    }
}

/** /pay <player> <amount> */
final class PayCmd extends Cmd {
    private record Pending(UUID target, long amount, long expiresAt) {}

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    PayCmd(IyhaCore plugin) {
        super(plugin, "pay", "iyha.economy.pay", 2000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player me)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        if (args.length != 2) {
            plugin.lang().send(sender, "usage", "usage", "/" + label + " <player> <amount>");
            return;
        }
        OptionalLong parsed = Money.parse(args[1]);
        if (parsed.isEmpty()) {
            plugin.lang().send(sender, "invalid-amount");
            return;
        }
        long amount = parsed.getAsLong();
        EconomyService eco = plugin.economy();
        if (amount < eco.minPay()) {
            plugin.lang().send(sender, "pay-too-small", "amount", eco.format(eco.minPay()));
            return;
        }
        guard(plugin.players().resolve(args[0]).thenAccept(found ->
                plugin.sync(() -> proceed(me, found, amount))), me);
    }

    private void proceed(Player me, Optional<PlayerDataService.PlayerRef> found, long amount) {
        EconomyService eco = plugin.economy();
        if (found.isEmpty()) {
            plugin.lang().send(me, "unknown-player", "name", "?");
            return;
        }
        PlayerDataService.PlayerRef target = found.get();
        if (plugin.getConfig().getBoolean("combat.block-pay", true) && plugin.combat().isTagged(me)
                && !me.hasPermission("iyha.bypass.combat")) {
            plugin.lang().send(me, "combat-blocked");
            return;
        }
        if (target.uuid().equals(me.getUniqueId())) {
            plugin.lang().send(me, "pay-self");
            return;
        }
        if (!plugin.players().isPayEnabled(target.uuid())) {
            plugin.lang().send(me, "pay-disabled", "player", target.name());
            return;
        }
        if (amount >= eco.confirmThreshold()) {
            long now = System.currentTimeMillis();
            Pending existing = pending.get(me.getUniqueId());
            boolean confirmed = existing != null && existing.target().equals(target.uuid())
                    && existing.amount() == amount && existing.expiresAt() > now;
            if (!confirmed) {
                pending.put(me.getUniqueId(), new Pending(target.uuid(), amount, now + 15_000L));
                plugin.lang().send(me, "pay-confirm", "amount", eco.format(amount), "player", target.name());
                return;
            }
            pending.remove(me.getUniqueId());
        }
        guard(eco.transfer(me.getUniqueId().toString(), target.uuid().toString(), amount, "PAY", null,
                me.getName(), null).thenAccept(result -> plugin.sync(() -> finish(me, target, amount, result))), me);
    }

    private void finish(Player me, PlayerDataService.PlayerRef target, long amount, EconomyService.TxResult result) {
        EconomyService eco = plugin.economy();
        switch (result.status()) {
            case OK -> {
                plugin.lang().send(me, "pay-sent", "amount", eco.format(amount), "player", target.name());
                Player receiver = Bukkit.getPlayer(target.uuid());
                if (receiver != null) {
                    plugin.lang().send(receiver, "pay-received", "amount", eco.format(amount), "player", me.getName());
                }
                if (amount >= eco.auditThreshold()) {
                    plugin.audit().log(me.getName(), "PAY", target.name(), eco.format(amount));
                }
            }
            case INSUFFICIENT -> plugin.lang().send(me, "pay-insufficient", "balance", eco.format(result.fromBalance()));
            case CAP -> plugin.lang().send(me, "pay-cap");
            case FROZEN -> plugin.lang().send(me, "economy-frozen");
            default -> plugin.lang().send(me, "internal-error");
        }
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        return args.length == 1 ? onlineNames(args[0]) : List.of();
    }
}

/** /paytoggle */
final class PayToggleCmd extends Cmd {
    PayToggleCmd(IyhaCore plugin) {
        super(plugin, "paytoggle", "iyha.economy.paytoggle", 2000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "players-only");
            return;
        }
        boolean enable = !plugin.players().isPayEnabled(player.getUniqueId());
        guard(plugin.players().setPayEnabled(player.getUniqueId(), enable).thenRun(() -> plugin.sync(() ->
                plugin.lang().send(player, enable ? "paytoggle-on" : "paytoggle-off"))), player);
    }
}

/** /baltop */
final class BaltopCmd extends Cmd {
    BaltopCmd(IyhaCore plugin) {
        super(plugin, "baltop", "iyha.leaderboard.use", 3000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        EconomyService eco = plugin.economy();
        guard(eco.top(10).thenAccept(list -> plugin.sync(() -> {
            plugin.lang().send(sender, "baltop-header");
            if (list.isEmpty()) {
                plugin.lang().send(sender, "baltop-empty");
                return;
            }
            int rank = 1;
            for (EconomyService.TopEntry entry : list) {
                plugin.lang().send(sender, "baltop-line", "rank", String.valueOf(rank++),
                        "player", entry.name(), "balance", eco.format(entry.balance()));
            }
        })), sender);
    }
}

/** /transactions [player] */
final class TransactionsCmd extends Cmd {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    TransactionsCmd(IyhaCore plugin) {
        super(plugin, "transactions", "iyha.economy.history", 3000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        EconomyService eco = plugin.economy();
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.lang().send(sender, "players-only");
                return;
            }
            show(sender, new PlayerDataService.PlayerRef(player.getUniqueId(), player.getName()));
            return;
        }
        if (!sender.hasPermission("iyha.economy.history.others")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        guard(plugin.players().resolve(args[0]).thenAccept(found -> plugin.sync(() -> {
            if (found.isEmpty()) {
                plugin.lang().send(sender, "unknown-player", "name", args[0]);
            } else {
                show(sender, found.get());
            }
        })), sender);
    }

    private void show(CommandSender sender, PlayerDataService.PlayerRef ref) {
        EconomyService eco = plugin.economy();
        guard(eco.history(ref.uuid(), 10).thenAccept(list -> plugin.sync(() -> {
            plugin.lang().send(sender, "tx-header", "player", ref.name());
            if (list.isEmpty()) {
                plugin.lang().send(sender, "tx-empty");
                return;
            }
            for (EconomyService.HistoryEntry e : list) {
                String time = TIME.format(java.time.Instant.ofEpochMilli(e.createdAt()));
                boolean incoming = e.amount() >= 0;
                plugin.lang().send(sender, incoming ? "tx-line-in" : "tx-line-out",
                        "time", time, "kind", e.kind(),
                        "amount", eco.format(Math.abs(e.amount())), "balance", eco.format(e.balanceAfter()));
            }
        })), sender);
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        return args.length == 1 && sender.hasPermission("iyha.economy.history.others")
                ? onlineNames(args[0]) : List.of();
    }
}

/** /eco give|take|set|reset <player> [amount], /eco freeze|unfreeze|audit */
final class EcoCmd extends Cmd {
    EcoCmd(IyhaCore plugin) {
        super(plugin, "eco", "iyha.admin.economy", 0);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            plugin.lang().send(sender, "usage", "usage", "/" + label + " <give|take|set|reset|freeze|unfreeze|audit>");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "freeze", "unfreeze" -> {
                if (!sender.hasPermission("iyha.admin.economy.freeze")) {
                    plugin.lang().send(sender, "no-permission");
                    return;
                }
                boolean freeze = sub.equals("freeze");
                plugin.audit().log(sender.getName(), "ECO_" + sub.toUpperCase(Locale.ROOT), null, null);
                guard(plugin.economy().setFrozen(freeze).thenRun(() -> plugin.sync(() ->
                        plugin.lang().send(sender, freeze ? "eco-frozen" : "eco-unfrozen"))), sender);
            }
            case "audit" -> {
                if (!sender.hasPermission("iyha.admin.economy.audit")) {
                    plugin.lang().send(sender, "no-permission");
                    return;
                }
                guard(plugin.economy().audit().thenAccept(report -> plugin.sync(() -> {
                    if (report.clean()) {
                        plugin.lang().send(sender, "eco-audit-clean");
                    } else {
                        plugin.lang().send(sender, "eco-audit-issues",
                                "tx", String.valueOf(report.unbalancedTx()),
                                "accounts", String.valueOf(report.mismatchedAccounts()),
                                "negative", String.valueOf(report.negativeAccounts()));
                    }
                })), sender);
            }
            case "give", "take", "set", "reset" -> money(sender, sub, args, label);
            default -> plugin.lang().send(sender, "usage", "usage",
                    "/" + label + " <give|take|set|reset|freeze|unfreeze|audit>");
        }
    }

    private void money(CommandSender sender, String sub, String[] args, String label) {
        boolean needsAmount = !sub.equals("reset");
        if (args.length < (needsAmount ? 3 : 2)) {
            plugin.lang().send(sender, "usage", "usage", "/" + label + " " + sub + " <player>" + (needsAmount ? " <amount>" : ""));
            return;
        }
        long amount;
        if (!needsAmount || (sub.equals("set") && args[2].equals("0"))) {
            amount = 0;
        } else {
            OptionalLong parsed = Money.parse(args[2]);
            if (parsed.isEmpty()) {
                plugin.lang().send(sender, "invalid-amount");
                return;
            }
            amount = parsed.getAsLong();
        }
        long amt = amount;
        EconomyService eco = plugin.economy();
        String actor = sender.getName();
        guard(plugin.players().resolve(args[1]).thenCompose(found -> {
            if (found.isEmpty()) {
                plugin.sync(() -> plugin.lang().send(sender, "unknown-player", "name", args[1]));
                return CompletableFuture.<Void>completedFuture(null);
            }
            PlayerDataService.PlayerRef ref = found.get();
            CompletableFuture<EconomyService.TxResult> work = switch (sub) {
                case "give" -> eco.mint(ref.uuid(), amt, "ADMIN_GIVE", null, actor);
                case "take" -> eco.sink(ref.uuid(), amt, "ADMIN_TAKE", null, actor);
                default -> eco.adminSet(ref.uuid(), amt, actor);
            };
            return work.thenAccept(result -> plugin.sync(() -> report(sender, sub, ref, amt, result)));
        }), sender);
    }

    private void report(CommandSender sender, String sub, PlayerDataService.PlayerRef ref, long amount,
                        EconomyService.TxResult result) {
        EconomyService eco = plugin.economy();
        switch (result.status()) {
            case OK -> {
                switch (sub) {
                    case "give" -> plugin.lang().send(sender, "eco-given", "amount", eco.format(amount), "player", ref.name());
                    case "take" -> plugin.lang().send(sender, "eco-taken", "amount", eco.format(amount), "player", ref.name());
                    default -> plugin.lang().send(sender, "eco-set", "amount", eco.format(amount), "player", ref.name());
                }
                plugin.audit().log(sender.getName(), "ECO_" + sub.toUpperCase(Locale.ROOT), ref.name(), eco.format(amount));
            }
            case INSUFFICIENT -> plugin.lang().send(sender, "eco-take-insufficient",
                    "player", ref.name(), "balance", eco.format(result.fromBalance()));
            case CAP -> plugin.lang().send(sender, "pay-cap");
            default -> plugin.lang().send(sender, "internal-error");
        }
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("give", "take", "set", "reset", "freeze", "unfreeze", "audit"), args[0]);
        }
        if (args.length == 2 && List.of("give", "take", "set", "reset").contains(args[0].toLowerCase(Locale.ROOT))) {
            return onlineNames(args[1]);
        }
        return List.of();
    }
}

/** /iyha reload|version|modules */
final class IyhaCmd extends Cmd {
    IyhaCmd(IyhaCore plugin) {
        super(plugin, "iyha", "iyha.admin.core", 0);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "version" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadAll();
                plugin.lang().send(sender, "reload-done");
            }
            case "modules" -> plugin.lang().send(sender, "modules", "list", plugin.moduleList());
            case "journal" -> journal(sender, args);
            default -> plugin.lang().send(sender, "version",
                    "version", plugin.getPluginMeta().getVersion(), "db", plugin.database().dialect().name());
        }
    }

    private void journal(CommandSender sender, String[] args) {
        if (!sender.hasPermission("iyha.admin.restore")) {
            plugin.lang().send(sender, "no-permission");
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        List<Journal.Entry> open = plugin.journal().unresolved();
        if (action.equals("list")) {
            sender.sendMessage(Ui.c("<gold>Unresolved journal entries: <white>" + open.size()));
            for (Journal.Entry e : open) {
                sender.sendMessage(Ui.c("<gray>" + e.id() + " <white>" + e.op() + " <gray>" + e.state() + " owner " + e.uuid()
                        + " items " + Journal.decode(e.payload()).size()));
            }
            return;
        }
        if (args.length < 3) {
            plugin.lang().send(sender, "usage", "usage", "/iyha journal <list|restore|dismiss> [id]");
            return;
        }
        Journal.Entry entry = open.stream().filter(e -> e.id().equals(args[2])).findFirst().orElse(null);
        if (entry == null) {
            plugin.lang().send(sender, "journal-unknown");
            return;
        }
        UUID owner = UUID.fromString(entry.uuid());
        if (action.equals("restore")) {
            for (ItemStack item : Journal.decode(entry.payload())) {
                plugin.claims().createAsync(owner, item, "JOURNAL_RESTORE", entry.id());
            }
            plugin.journal().finish(entry.id(), "DONE", entry.op(), owner);
            plugin.audit().log(sender.getName(), "JOURNAL_RESTORE", entry.uuid(), entry.id());
            plugin.lang().send(sender, "journal-restored");
        } else if (action.equals("dismiss")) {
            plugin.journal().finish(entry.id(), "ABORTED", entry.op(), owner);
            plugin.audit().log(sender.getName(), "JOURNAL_DISMISS", entry.uuid(), entry.id());
            plugin.lang().send(sender, "journal-dismissed");
        }
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("reload", "version", "modules", "journal"), args[0]);
        }
        return args.length == 2 && args[0].equalsIgnoreCase("journal") ? filter(List.of("list", "restore", "dismiss"), args[1]) : List.of();
    }
}
