package net.iyha.core;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/** IYHA SMP core plugin. Every system is created here and reaches the others through these accessors. */
public final class IyhaCore extends JavaPlugin {

    private Lang lang;
    private Cooldowns cooldowns;
    private Database database;
    private AuditService audit;
    private EconomyService economy;
    private PlayerDataService players;
    private ItemKeys itemKeys;
    private ItemRules itemRules;
    private Journal journal;
    private ClaimService claims;
    private WorthService worth;
    private OrdersService orders;
    private SellService sell;
    private ShopService shop;
    private AuctionService auctions;
    private CustomItems customItems;
    private EnchantService enchants;
    private Limits limits;
    private TeleportService teleports;
    private TravelService travel;
    private CombatService combat;
    private BountyService bounties;
    private StatsService stats;
    private SettingsService settings;
    private LeaderboardService leaderboards;
    private RankService ranks;
    private EventManager events;
    private SocialService social;
    private Prompts prompts;
    private final List<Hook> hooks = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lang = new Lang(this);
        cooldowns = new Cooldowns();
        try {
            database = new Database(this);
            database.migrate();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Database failed to start. Disabling IYHACore.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            audit = new AuditService(this);
            economy = new EconomyService(this);
            players = new PlayerDataService(this);
            itemKeys = new ItemKeys(this);
            itemRules = new ItemRules(this);
            journal = new Journal(this);
            claims = new ClaimService(this);
            worth = new WorthService(this);
            orders = new OrdersService(this);
            sell = new SellService(this);
            shop = new ShopService(this);
            auctions = new AuctionService(this);
            customItems = new CustomItems(this);
            enchants = new EnchantService(this);
            limits = new Limits();
            teleports = new TeleportService(this);
            travel = new TravelService(this);
            combat = new CombatService(this);
            bounties = new BountyService(this);
            stats = new StatsService(this);
            settings = new SettingsService(this);
            leaderboards = new LeaderboardService(this);
            ranks = new RankService(this);
            events = new EventManager(this);
            social = new SocialService(this);
            prompts = new Prompts(this);
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "A system failed to start. Disabling IYHACore.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        var pm = getServer().getPluginManager();
        // Order matters: PlayerDataService must see joins first so player rows exist before other systems read them.
        pm.registerEvents(players, this);
        pm.registerEvents(stats, this);
        pm.registerEvents(settings, this);
        pm.registerEvents(travel, this);
        pm.registerEvents(teleports, this);
        pm.registerEvents(social, this);
        pm.registerEvents(combat, this);
        pm.registerEvents(events, this);
        pm.registerEvents(customItems, this);
        pm.registerEvents(new EnchantListener(this), this);
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(prompts, this);
        pm.registerEvents(new SpawnProtection(this), this);

        registerCommands();

        // Background jobs (every minute): expire auctions and orders. Duplicate scan every five minutes.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            auctions.sweepExpired();
            orders.sweepExpired();
        }, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, customItems::scanForDuplicates, 6000L, 6000L);

        economy.startupAudit();
        auctions.recoverJournal();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.track(player);
        }

        loadHook("VaultHook", "Vault");
        loadHook("PapiHook", "PlaceholderAPI");
        getLogger().info("IYHACore enabled. Database: " + database.dialect() + ". Modules: " + moduleList());
    }

    @Override
    public void onDisable() {
        for (Hook hook : hooks) {
            try {
                hook.disable();
            } catch (Throwable ignored) {
                // shutting down anyway
            }
        }
        if (events != null) {
            events.shutdown();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) {
                player.closeInventory();
            }
        }
        if (stats != null) {
            stats.flushAll();
        }
        if (journal != null) {
            journal.compact();
        }
        if (database != null) {
            database.close();
        }
    }

    private void registerCommands() {
        // economy
        register("balance", new BalanceCmd(this));
        register("pay", new PayCmd(this));
        register("paytoggle", new PayToggleCmd(this));
        register("baltop", new BaltopCmd(this));
        register("eco", new EcoCmd(this));
        register("transactions", new TransactionsCmd(this));
        register("iyha", new IyhaCmd(this));
        // markets
        register("sell", new SellCmd(this));
        register("worth", new WorthCmd(this));
        register("sellhistory", new SellHistoryCmd(this));
        register("sellmulti", new SellMultiCmd(this));
        register("shop", new ShopCmd(this));
        register("ah", new AhCmd(this));
        register("claim", new ClaimCmd(this));
        register("orders", new OrdersCmd(this));
        // enchanting and items
        register("enchants", new EnchantsCmd(this));
        register("ienchant", new IEnchantCmd(this));
        register("iitem", new IItemCmd(this));
        // travel
        register("tpa", new TpaCmd(this, "tpa", false));
        register("tpahere", new TpaCmd(this, "tpahere", true));
        register("tpaccept", new TpaReplyCmd(this, "tpaccept", "accept"));
        register("tpadeny", new TpaReplyCmd(this, "tpadeny", "deny"));
        register("tpacancel", new TpaReplyCmd(this, "tpacancel", "cancel"));
        register("tpatoggle", new TpaReplyCmd(this, "tpatoggle", "toggle"));
        register("sethome", new HomeCmd(this, "sethome", "set"));
        register("home", new HomeCmd(this, "home", "go"));
        register("homes", new HomeCmd(this, "homes", "list"));
        register("delhome", new HomeCmd(this, "delhome", "delete"));
        register("renamehome", new HomeCmd(this, "renamehome", "rename"));
        register("warp", new WarpCmd(this, "warp", "go"));
        register("warps", new WarpCmd(this, "warps", "list"));
        register("setwarp", new WarpCmd(this, "setwarp", "set"));
        register("delwarp", new WarpCmd(this, "delwarp", "delete"));
        register("spawn", new SpawnCmd(this, "spawn", false));
        register("setspawn", new SpawnCmd(this, "setspawn", true));
        register("rtp", new RtpCmd(this));
        // combat, ranks, profile
        register("bounty", new BountyCmd(this));
        register("suicide", new KillCmd(this));
        register("ranks", new RanksCmd(this));
        register("profile", new ProfileCmd(this, "profile", "iyha.profile.view", "profile"));
        register("stats", new ProfileCmd(this, "stats", "iyha.profile.stats", "stats"));
        register("playtime", new ProfileCmd(this, "playtime", "iyha.profile.playtime", "playtime"));
        register("leaderboard", new ProfileCmd(this, "leaderboard", "iyha.leaderboard.use", "leaderboard"));
        register("settings", new ProfileCmd(this, "settings", "iyha.profile.settings", "settings"));
        // social and info
        register("msg", new SocialCmd(this, "msg", "iyha.social.msg", "msg"));
        register("reply", new SocialCmd(this, "reply", "iyha.social.msg", "reply"));
        register("msgtoggle", new SocialCmd(this, "msgtoggle", "iyha.social.msgtoggle", "msgtoggle"));
        register("ignore", new SocialCmd(this, "ignore", "iyha.social.ignore", "ignore"));
        register("unignore", new SocialCmd(this, "unignore", "iyha.social.ignore", "unignore"));
        register("nightvision", new SocialCmd(this, "nightvision", "iyha.utility.nv", "nv"));
        register("ping", new SocialCmd(this, "ping", "iyha.utility.ping", "ping"));
        register("menu", new MenuCmd(this));
        register("help", new InfoCmd(this, "help"));
        register("rules", new InfoCmd(this, "rules"));
        register("discord", new InfoCmd(this, "discord"));
        register("store", new InfoCmd(this, "store"));
        register("media", new InfoCmd(this, "media"));
        register("event", new EventCmd(this));
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command missing from plugin.yml: " + name);
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void loadHook(String className, String pluginName) {
        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) {
            return;
        }
        try {
            var ctor = Class.forName("net.iyha.core." + className).getDeclaredConstructor(IyhaCore.class);
            ctor.setAccessible(true);
            Hook hook = (Hook) ctor.newInstance(this);
            hook.enable();
            hooks.add(hook);
            getLogger().info("Connected to " + pluginName);
        } catch (ClassNotFoundException ex) {
            getLogger().info(pluginName + " found, but this build has no " + pluginName + " support compiled in.");
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Could not connect to " + pluginName, t);
        }
    }

    String moduleList() {
        return "economy, sell, shop, auction, orders, enchanting, travel, combat, ranks, profile, leaderboards, items, events, social, spawn"
                + (hooks.isEmpty() ? "" : ", integrations: " + hooks.size());
    }

    /** Reloads configuration files without restarting. */
    void reloadAll() {
        reloadConfig();
        lang.reload();
        economy.reloadSettings();
        itemRules.reload();
        worth.reload();
        shop.reload();
        customItems.reload();
        enchants.reload();
        ranks.reload();
    }

    /** Runs the task on the main server thread (immediately if already there). */
    void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    Lang lang() { return lang; }
    Cooldowns cooldowns() { return cooldowns; }
    Database database() { return database; }
    AuditService audit() { return audit; }
    EconomyService economy() { return economy; }
    PlayerDataService players() { return players; }
    ItemKeys itemKeys() { return itemKeys; }
    ItemRules itemRules() { return itemRules; }
    Journal journal() { return journal; }
    ClaimService claims() { return claims; }
    WorthService worth() { return worth; }
    OrdersService orders() { return orders; }
    SellService sell() { return sell; }
    ShopService shop() { return shop; }
    AuctionService auctions() { return auctions; }
    CustomItems customItems() { return customItems; }
    EnchantService enchants() { return enchants; }
    Limits limits() { return limits; }
    TeleportService teleports() { return teleports; }
    TravelService travel() { return travel; }
    CombatService combat() { return combat; }
    BountyService bounties() { return bounties; }
    StatsService stats() { return stats; }
    SettingsService settings() { return settings; }
    LeaderboardService leaderboards() { return leaderboards; }
    RankService ranks() { return ranks; }
    EventManager events() { return events; }
    SocialService social() { return social; }
    Prompts prompts() { return prompts; }
}
