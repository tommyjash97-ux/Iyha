package net.iyha.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Small UI helpers (MiniMessage text without italics, icons, lore). */
final class Ui {
    static final MiniMessage MM = MiniMessage.miniMessage();

    private Ui() {}

    static Component c(String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    static ItemStack button(Material material, String name, String... lore) {
        ItemBuilder b = new ItemBuilder(material).name(c(name));
        if (lore.length > 0) {
            b.lore(Arrays.stream(lore).map(Ui::c).toList());
        }
        return b.build();
    }

    static ItemStack filler() {
        return button(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    /** Copy of {@code base} with extra lore lines appended. */
    static ItemStack withLore(ItemStack base, List<String> extra) {
        ItemStack copy = base.clone();
        copy.editMeta(meta -> {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            for (String line : extra) {
                lore.add(c(line));
            }
            meta.lore(lore);
        });
        return copy;
    }
}

/** Inventory helpers shared by every system that hands items to players. */
final class Give {
    private Give() {}

    /** How many more items like {@code proto} fit into the inventory's storage slots. */
    static int capacity(Inventory inv, ItemStack proto) {
        int max = proto.getMaxStackSize();
        int cap = 0;
        for (ItemStack slot : inv.getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                cap += max;
            } else if (slot.isSimilar(proto)) {
                cap += Math.max(0, max - slot.getAmount());
            }
        }
        return cap;
    }

    static boolean canFit(Inventory inv, ItemStack item) {
        return capacity(inv, item) >= item.getAmount();
    }

    /** Gives the item; whatever does not fit becomes a pending claim. Main thread only. */
    static void giveOrClaim(IyhaCore plugin, Player player, ItemStack item, String source) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(item.clone());
        for (ItemStack rest : left.values()) {
            plugin.claims().createAsync(player.getUniqueId(), rest, source, null);
        }
        if (!left.isEmpty()) {
            plugin.lang().send(player, "claim-pending");
        }
    }

    static List<ItemStack> stacksOf(Material material, int amount) {
        List<ItemStack> list = new ArrayList<>();
        int max = material.getMaxStackSize();
        int left = amount;
        while (left > 0) {
            int n = Math.min(max, left);
            list.add(new ItemStack(material, n));
            left -= n;
        }
        return list;
    }
}

/** A read-only 54-slot menu with pages. Subclasses supply entries and optional extra buttons (slots 46-48, 50-52). */
abstract class PagedMenu extends Menu {
    record Entry(ItemStack icon, Consumer<InventoryClickEvent> action) {}

    private final List<Entry> entries = new ArrayList<>();
    protected int page = 0;

    protected PagedMenu(IyhaCore plugin) {
        super(plugin);
    }

    @Override
    protected int size() {
        return 54;
    }

    protected final void setEntries(List<Entry> list) {
        entries.clear();
        entries.addAll(list);
        page = Math.min(page, maxPage());
        render();
    }

    protected int maxPage() {
        return Math.max(0, (entries.size() - 1) / 45);
    }

    @Override
    protected void build() {
        render();
    }

    /** Subclass hook: place extra buttons in slots 46-48 and 50-52. */
    protected void extras(Inventory inv) {}

    /** Subclass hook: handle a click on an extra button slot. */
    protected void extraClick(int slot, InventoryClickEvent event) {}

    protected void onBack(Player player) {
        player.closeInventory();
    }

    protected final void render() {
        Inventory inv = getInventory();
        if (inv == null) {
            return;
        }
        inv.clear();
        int start = page * 45;
        for (int i = 0; i < 45 && start + i < entries.size(); i++) {
            inv.setItem(i, entries.get(start + i).icon());
        }
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, Ui.filler());
        }
        if (page > 0) {
            inv.setItem(45, Ui.button(Material.ARROW, "<yellow>Previous page"));
        }
        if (page < maxPage()) {
            inv.setItem(53, Ui.button(Material.ARROW, "<yellow>Next page"));
        }
        inv.setItem(49, Ui.button(Material.BARRIER, "<red>Close"));
        extras(inv);
    }

    @Override
    void onClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= 54) {
            return;
        }
        if (raw < 45) {
            int index = page * 45 + raw;
            if (index < entries.size()) {
                entries.get(index).action().accept(event);
            }
            return;
        }
        Player player = (Player) event.getWhoClicked();
        switch (raw) {
            case 45 -> {
                if (page > 0) {
                    page--;
                    render();
                }
            }
            case 53 -> {
                if (page < maxPage()) {
                    page++;
                    render();
                }
            }
            case 49 -> onBack(player);
            default -> extraClick(raw, event);
        }
    }
}

/** Yes/No screen used before any purchase. */
final class ConfirmMenu extends Menu {
    private final String titleText;
    private final ItemStack shown;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private boolean done;

    ConfirmMenu(IyhaCore plugin, String title, ItemStack shown, Runnable onConfirm, Runnable onCancel) {
        super(plugin);
        this.titleText = title;
        this.shown = shown;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    protected Component title() {
        return Ui.c(titleText);
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
        set(13, shown);
        set(11, Ui.button(Material.LIME_CONCRETE, "<green><bold>Confirm"));
        set(15, Ui.button(Material.RED_CONCRETE, "<red><bold>Cancel"));
    }

    @Override
    void onClick(InventoryClickEvent event) {
        if (done) {
            return;
        }
        if (event.getRawSlot() == 11) {
            done = true;
            onConfirm.run();
        } else if (event.getRawSlot() == 15) {
            done = true;
            onCancel.run();
        }
    }
}

/** Captures the next chat message of a player (used for search boxes). Type "cancel" to abort. */
final class Prompts implements Listener {
    private final IyhaCore plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    Prompts(IyhaCore plugin) {
        this.plugin = plugin;
    }

    void ask(Player player, String messageKey, Consumer<String> callback) {
        pending.put(player.getUniqueId(), callback);
        plugin.lang().send(player, messageKey);
        player.closeInventory();
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Consumer<String> callback = pending.remove(event.getPlayer().getUniqueId());
        if (callback == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.sync(() -> callback.accept(text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
