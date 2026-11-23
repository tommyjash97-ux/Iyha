package net.iyha.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Base class for every IYHA menu. By default a menu is read-only: every click is cancelled.
 * A menu that needs items to move (sell, enchanting) overrides the hooks below and opts in slot by slot.
 */
abstract class Menu implements InventoryHolder {
    protected final IyhaCore plugin;
    private Inventory inventory;
    private boolean closed;

    protected Menu(IyhaCore plugin) {
        this.plugin = plugin;
    }

    protected abstract Component title();

    protected abstract int size();

    /** Fill the inventory with items. Called once, the first time the menu is opened. */
    protected void build() {}

    final void open(Player player) {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, size(), title());
            build();
        }
        closed = false;
        player.openInventory(inventory);
        onOpen(player);
    }

    /** Called right after the menu is shown to the player. */
    void onOpen(Player player) {}

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    protected final void set(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    /** True if items may be dropped into this slot by the player. */
    boolean acceptsItemInSlot(int slot) {
        return false;
    }

    /** True if the player may move items inside their own inventory while this menu is open. */
    boolean allowsBottomInteraction() {
        return false;
    }

    /** Called for every click while the menu is open. The click is already cancelled; call setCancelled(false) to allow it. */
    void onClick(org.bukkit.event.inventory.InventoryClickEvent event) {}

    /** Called once when the menu closes for any reason (close, quit, kick, shutdown). Return unsold items here. */
    void onClose(Player player) {}

    final void closeOnce(Player player) {
        if (closed) {
            return;
        }
        closed = true;
        onClose(player);
    }
}

/** Click, drag, close and quit protection shared by every menu. */
final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) {
            return;
        }
        InventoryAction action = event.getAction();
        boolean inTop = event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize();

        event.setCancelled(true);
        // Double-click "collect to cursor" can pull items out of a menu, so it is never allowed.
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            return;
        }
        if (!inTop && menu.allowsBottomInteraction() && action != InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(false);
        }
        menu.onClick(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) {
            return;
        }
        boolean touchesTop = false;
        for (int slot : event.getRawSlots()) {
            if (slot < top.getSize()) {
                touchesTop = true;
                if (!menu.acceptsItemInSlot(slot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (!touchesTop && !menu.allowsBottomInteraction()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof Menu menu && event.getPlayer() instanceof Player player) {
            menu.closeOnce(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu menu) {
            menu.closeOnce(player);
        }
    }
}

/** Small helper for building menu icons. */
final class ItemBuilder {
    private final ItemStack item;

    ItemBuilder(Material material) {
        this.item = new ItemStack(material);
    }

    ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    ItemBuilder name(Component name) {
        item.editMeta(meta -> meta.displayName(name.decoration(TextDecoration.ITALIC, false)));
        return this;
    }

    ItemBuilder lore(List<Component> lines) {
        item.editMeta(meta -> meta.lore(lines.stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false))
                .toList()));
        return this;
    }

    ItemStack build() {
        return item;
    }
}
