package net.iyha.core;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A server event. Only one runs at a time. All three types are IYHA-original designs. */
interface GameEvent {
    String id();

    String title();

    /** Starts the event. Returns false if it cannot start (for example missing configuration). */
    boolean start();

    void stop();

    boolean join(Player player);

    void leave(Player player);

    String status();

    List<String> rules();

    boolean joinable();
}

/** Event framework: scheduling, announcements, one active event, commands. */
final class EventManager implements Listener {
    private final IyhaCore plugin;
    private final Map<String, GameEvent> types = new LinkedHashMap<>();
    private volatile GameEvent active;
    private BukkitTask scheduler;
    private long minuteCounter = 0;

    EventManager(IyhaCore plugin) {
        this.plugin = plugin;
        types.put("reward", new RewardEvent(plugin, this));
        types.put("koth", new KothEvent(plugin, this));
        types.put("lms", new LmsEvent(plugin, this));
        scheduler = Bukkit.getScheduler().runTaskTimer(plugin, this::scheduleTick, 1200L, 1200L);
    }

    Map<String, GameEvent> types() {
        return types;
    }

    GameEvent active() {
        return active;
    }

    void ended(GameEvent event) {
        if (active == event) {
            active = null;
        }
    }

    boolean startEvent(String type) {
        GameEvent e = types.get(type.toLowerCase(Locale.ROOT));
        if (e == null || active != null) {
            return false;
        }
        active = e;
        if (!e.start()) {
            active = null;
            return false;
        }
        return true;
    }

    void stopActive() {
        GameEvent e = active;
        if (e != null) {
            e.stop();
            active = null;
        }
    }

    void announce(String messageKey, Object... pairs) {
        Component msg = plugin.lang().component(messageKey, pairs);
        Bukkit.broadcast(msg);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(Ui.c("<gold><bold>EVENT"), msg, Title.Times.times(Duration.ofMillis(300),
                    Duration.ofSeconds(3), Duration.ofMillis(500))));
        }
    }

    /** events.auto in config.yml: list of {type, every-minutes}. */
    private void scheduleTick() {
        minuteCounter++;
        if (active != null) {
            return;
        }
        for (Map<?, ?> m : plugin.getConfig().getMapList("events.auto")) {
            try {
                long every = Long.parseLong(String.valueOf(m.get("every-minutes")));
                if (every > 0 && minuteCounter % every == 0) {
                    startEvent(String.valueOf(m.get("type")));
                    return;
                }
            } catch (NumberFormatException ignored) {
                // bad config entry
            }
        }
    }

    void shutdown() {
        if (scheduler != null) {
            scheduler.cancel();
        }
        stopActive();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (active instanceof LmsEvent lms) {
            lms.onDeath(event);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (active instanceof LmsEvent lms) {
            lms.onRespawn(event);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (active instanceof LmsEvent lms) {
            lms.onQuit(event.getPlayer());
        }
    }
}

/** Gives every online player a reward at once (a "key-all" style drop). */
final class RewardEvent implements GameEvent {
    private final IyhaCore plugin;
    private final EventManager manager;

    RewardEvent(IyhaCore plugin, EventManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public String id() { return "reward"; }
    public String title() { return "Reward drop"; }
    public boolean joinable() { return false; }
    public boolean join(Player player) { return false; }
    public void leave(Player player) { }
    public void stop() { }
    public String status() { return "Instant reward for everyone online."; }
    public List<String> rules() { return List.of("No sign-up needed. Everyone online receives the reward."); }

    public boolean start() {
        long coins = Money.fromDouble(plugin.getConfig().getDouble("events.reward.coins", 5000));
        manager.announce("event-reward-announce", "amount", plugin.economy().format(coins));
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.economy().mint(p.getUniqueId(), coins, "EVENT_REWARD", "reward", "system");
        }
        plugin.audit().log("system", "EVENT_REWARD", null, plugin.economy().format(coins) + " x " + Bukkit.getOnlinePlayers().size());
        manager.ended(this);
        return true;
    }
}

/** King of the hill: stand alone in the zone for the capture time. */
final class KothEvent implements GameEvent {
    private final IyhaCore plugin;
    private final EventManager manager;
    private BukkitTask task;
    private BossBar bar;
    private UUID holder;
    private int progress;
    private int elapsed;

    KothEvent(IyhaCore plugin, EventManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public String id() { return "koth"; }
    public String title() { return "King of the Hill"; }
    public boolean joinable() { return false; }
    public boolean join(Player player) { return false; }
    public void leave(Player player) { }

    public String status() {
        var cfg = plugin.getConfig();
        return "Zone at " + cfg.getString("events.koth.world", "world") + " " + cfg.getInt("events.koth.x") + ", "
                + cfg.getInt("events.koth.y") + ", " + cfg.getInt("events.koth.z");
    }

    public List<String> rules() {
        return List.of("Stand alone inside the zone to capture it.", "If two players are inside, the timer pauses.",
                "First to capture wins the reward.");
    }

    public boolean start() {
        var cfg = plugin.getConfig();
        World w = Bukkit.getWorld(cfg.getString("events.koth.world", "world"));
        if (w == null) {
            return false;
        }
        holder = null;
        progress = 0;
        elapsed = 0;
        bar = BossBar.bossBar(Ui.c("<gold>KOTH"), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showBossBar(bar);
        }
        manager.announce("event-koth-start");
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(w), 20L, 20L);
        return true;
    }

    private void tick(World w) {
        var cfg = plugin.getConfig();
        int capture = cfg.getInt("events.koth.capture-seconds", 60);
        int duration = cfg.getInt("events.koth.duration-minutes", 15) * 60;
        double radius = cfg.getDouble("events.koth.radius", 6);
        Location center = new Location(w, cfg.getDouble("events.koth.x"), cfg.getDouble("events.koth.y"), cfg.getDouble("events.koth.z"));
        elapsed++;
        List<Player> inside = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            double dx = l.getX() - center.getX();
            double dz = l.getZ() - center.getZ();
            if (dx * dx + dz * dz <= radius * radius && Math.abs(l.getY() - center.getY()) <= 6 && !p.isDead()) {
                inside.add(p);
            }
        }
        if (inside.size() == 1) {
            Player p = inside.get(0);
            if (!p.getUniqueId().equals(holder)) {
                holder = p.getUniqueId();
                progress = 0;
            }
            progress++;
            if (progress >= capture) {
                win(p);
                return;
            }
        } else if (inside.isEmpty()) {
            progress = Math.max(0, progress - 1);
            if (progress == 0) {
                holder = null;
            }
        }
        Player h = holder == null ? null : Bukkit.getPlayer(holder);
        bar.name(Ui.c(h == null ? "<gold>KOTH: <white>unclaimed" : "<gold>KOTH: <white>" + h.getName() + " <gray>" + progress + "/" + capture
                + (inside.size() > 1 ? " <red>(contested)" : "")));
        bar.progress(Math.min(1f, (float) progress / capture));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showBossBar(bar);
        }
        if (elapsed >= duration) {
            manager.announce("event-koth-nowinner");
            stop();
            manager.ended(this);
        }
    }

    private void win(Player p) {
        long coins = Money.fromDouble(plugin.getConfig().getDouble("events.koth.reward-coins", 10000));
        manager.announce("event-winner", "player", p.getName(), "event", title());
        plugin.economy().mint(p.getUniqueId(), coins, "EVENT_KOTH", "koth", "system");
        plugin.lang().send(p, "event-reward-received", "amount", plugin.economy().format(coins));
        plugin.audit().log("system", "EVENT_KOTH_WIN", p.getName(), plugin.economy().format(coins));
        stop();
        manager.ended(this);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.hideBossBar(bar);
            }
            bar = null;
        }
    }
}

/** Last man standing. Players keep their inventory when they die in the arena. */
final class LmsEvent implements GameEvent {
    private final IyhaCore plugin;
    private final EventManager manager;
    private final Map<UUID, Location> originals = new ConcurrentHashMap<>();
    private final List<UUID> alive = new ArrayList<>();
    private boolean joining;
    private boolean fighting;
    private BukkitTask task;

    LmsEvent(IyhaCore plugin, EventManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public String id() { return "lms"; }
    public String title() { return "Last Man Standing"; }
    public boolean joinable() { return joining; }

    public String status() {
        return joining ? "Sign-up open (" + originals.size() + " joined)" : fighting ? alive.size() + " fighters left" : "Idle";
    }

    public List<String> rules() {
        return List.of("Join with /event join while sign-up is open.", "You fight with your own gear and keep it if you die.",
                "Leaving or logging out eliminates you.", "The last player alive wins the reward.");
    }

    public boolean start() {
        var cfg = plugin.getConfig();
        if (Bukkit.getWorld(cfg.getString("events.lms.world", "world")) == null) {
            return false;
        }
        originals.clear();
        alive.clear();
        joining = true;
        fighting = false;
        int seconds = cfg.getInt("events.lms.join-seconds", 60);
        manager.announce("event-lms-open", "seconds", String.valueOf(seconds));
        task = Bukkit.getScheduler().runTaskLater(plugin, this::begin, seconds * 20L);
        return true;
    }

    public boolean join(Player player) {
        if (!joining || originals.containsKey(player.getUniqueId())) {
            return false;
        }
        originals.put(player.getUniqueId(), player.getLocation().clone());
        alive.add(player.getUniqueId());
        return true;
    }

    public void leave(Player player) {
        eliminate(player.getUniqueId(), true);
    }

    private void begin() {
        var cfg = plugin.getConfig();
        joining = false;
        if (alive.size() < cfg.getInt("events.lms.min-players", 2)) {
            manager.announce("event-lms-cancelled");
            stop();
            manager.ended(this);
            return;
        }
        World w = Bukkit.getWorld(cfg.getString("events.lms.world", "world"));
        Location arena = new Location(w, cfg.getDouble("events.lms.x"), cfg.getDouble("events.lms.y"), cfg.getDouble("events.lms.z"));
        fighting = true;
        for (UUID id : new ArrayList<>(alive)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.teleportAsync(arena);
            }
        }
        manager.announce("event-lms-start");
    }

    private void eliminate(UUID id, boolean teleportHome) {
        if (!alive.remove(id) && !originals.containsKey(id)) {
            return;
        }
        Location home = originals.get(id);
        Player p = Bukkit.getPlayer(id);
        if (teleportHome && p != null && home != null && fighting) {
            p.teleportAsync(home);
        }
        if (fighting && alive.size() <= 1) {
            finish();
        }
    }

    private void finish() {
        fighting = false;
        UUID winnerId = alive.isEmpty() ? null : alive.get(0);
        Player winner = winnerId == null ? null : Bukkit.getPlayer(winnerId);
        if (winner != null) {
            long coins = Money.fromDouble(plugin.getConfig().getDouble("events.lms.reward-coins", 5000));
            manager.announce("event-winner", "player", winner.getName(), "event", title());
            plugin.economy().mint(winner.getUniqueId(), coins, "EVENT_LMS", "lms", "system");
            plugin.lang().send(winner, "event-reward-received", "amount", plugin.economy().format(coins));
            Location home = originals.get(winnerId);
            if (home != null) {
                winner.teleportAsync(home);
            }
            plugin.audit().log("system", "EVENT_LMS_WIN", winner.getName(), plugin.economy().format(coins));
        }
        stop();
        manager.ended(this);
    }

    void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (!fighting || !alive.contains(p.getUniqueId())) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        alive.remove(p.getUniqueId());
        if (alive.size() <= 1) {
            Bukkit.getScheduler().runTask(plugin, this::finish);
        }
    }

    void onRespawn(PlayerRespawnEvent event) {
        Location home = originals.get(event.getPlayer().getUniqueId());
        if (home != null && !alive.contains(event.getPlayer().getUniqueId())) {
            event.setRespawnLocation(home);
        }
    }

    void onQuit(Player player) {
        if (originals.containsKey(player.getUniqueId())) {
            eliminate(player.getUniqueId(), false);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        joining = false;
        fighting = false;
        alive.clear();
        originals.clear();
    }
}

/** Event list menu. */
final class EventsMenu extends Menu {
    private final Player player;

    EventsMenu(IyhaCore plugin, Player player) {
        super(plugin);
        this.player = player;
    }

    @Override
    protected Component title() {
        return Ui.c("<gold><bold>Events");
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
        GameEvent active = plugin.events().active();
        int slot = 10;
        for (GameEvent e : plugin.events().types().values()) {
            boolean running = e == active;
            List<String> lore = new ArrayList<>();
            lore.add(running ? "<green>RUNNING: " + e.status() : "<gray>Not running");
            lore.add(" ");
            for (String rule : e.rules()) {
                lore.add("<gray>- " + rule);
            }
            if (running && e.joinable()) {
                lore.add(" ");
                lore.add("<yellow>Click to join");
            }
            set(slot++, Ui.button(running ? Material.LIME_BANNER : Material.GRAY_BANNER, "<gold>" + e.title(), lore.toArray(new String[0])));
        }
        set(22, Ui.button(Material.BARRIER, "<red>Close"));
    }

    @Override
    void onClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw == 22) {
            player.closeInventory();
            return;
        }
        List<GameEvent> list = new ArrayList<>(plugin.events().types().values());
        int idx = raw - 10;
        if (idx >= 0 && idx < list.size()) {
            GameEvent e = list.get(idx);
            if (e == plugin.events().active() && e.joinable()) {
                player.closeInventory();
                plugin.lang().send(player, e.join(player) ? "event-joined" : "event-cannot-join");
            }
        }
    }
}

/** /event list|info|join|leave|rules and admin start|stop|reload */
final class EventCmd extends Cmd {
    EventCmd(IyhaCore plugin) {
        super(plugin, "event", "iyha.events.use", 1000);
    }

    @Override
    protected void run(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        EventManager mgr = plugin.events();
        switch (sub) {
            case "list" -> {
                if (sender instanceof Player p) {
                    new EventsMenu(plugin, p).open(p);
                }
            }
            case "info", "rules" -> {
                GameEvent e = mgr.active();
                if (e == null) {
                    plugin.lang().send(sender, "event-none");
                    return;
                }
                sender.sendMessage(Ui.c("<gold>" + e.title() + " <gray>- " + e.status()));
                for (String rule : e.rules()) {
                    sender.sendMessage(Ui.c("<gray>- " + rule));
                }
            }
            case "join" -> {
                GameEvent e = mgr.active();
                if (!(sender instanceof Player p) || e == null) {
                    plugin.lang().send(sender, "event-none");
                    return;
                }
                plugin.lang().send(p, e.join(p) ? "event-joined" : "event-cannot-join");
            }
            case "leave" -> {
                GameEvent e = mgr.active();
                if (sender instanceof Player p && e != null) {
                    e.leave(p);
                }
            }
            case "start" -> {
                if (!sender.hasPermission("iyha.admin.events") || args.length < 2) {
                    plugin.lang().send(sender, "no-permission");
                    return;
                }
                boolean ok = mgr.startEvent(args[1]);
                plugin.audit().log(sender.getName(), "EVENT_START", args[1], String.valueOf(ok));
                plugin.lang().send(sender, ok ? "event-started" : "event-cannot-start");
            }
            case "stop" -> {
                if (!sender.hasPermission("iyha.admin.events")) {
                    plugin.lang().send(sender, "no-permission");
                    return;
                }
                mgr.stopActive();
                plugin.audit().log(sender.getName(), "EVENT_STOP", null, null);
                plugin.lang().send(sender, "event-stopped");
            }
            default -> plugin.lang().send(sender, "usage", "usage", "/event [list|info|join|leave|rules]");
        }
    }

    @Override
    protected List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("list", "info", "join", "leave", "rules", "start", "stop"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            return filter(new ArrayList<>(plugin.events().types().keySet()), args[1]);
        }
        return List.of();
    }
}
