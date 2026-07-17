package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.data.Recipes;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryClickEvent;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.inventory.AbstractInventory;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.utils.inventory.PlayerInventoryUtils;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real crafting against the Mojang recipe set: the player's own 2x2 grid and the
 * crafting table's 3x3. Result-slot takes consume ingredients; shift-click crafts
 * repeatedly; closing the window returns leftover ingredients.
 */
public final class Crafting {
    private Crafting() {}

    /** Open 3x3 crafting windows (slot 0 result, 1-9 grid). */
    private static final Set<Inventory> TABLES = ConcurrentHashMap.newKeySet();

    static void register(GlobalEventHandler events) {
        events.addListener(InventoryPreClickEvent.class, Crafting::preClick);
        events.addListener(InventoryClickEvent.class, Crafting::postClick);
        events.addListener(InventoryCloseEvent.class, Crafting::close);
        events.addListener(PlayerDisconnectEvent.class, e -> {
            AbstractInventory open = e.getPlayer().getOpenInventory();
            if (open instanceof Inventory inv && TABLES.contains(inv)) returnGrid(e.getPlayer(), inv);
        });
    }

    static void open(Player player) {
        Inventory inv = new Inventory(InventoryType.CRAFTING, Component.text("Crafting"));
        TABLES.add(inv);
        player.openInventory(inv);
    }

    // ------------------------------------------------------------------ grid plumbing

    private record Grid(AbstractInventory inv, int resultSlot, int[] gridSlots, int width) {}

    private static Grid gridFor(AbstractInventory inv) {
        if (inv instanceof Inventory window && TABLES.contains(window)) {
            return new Grid(inv, 0, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, 3);
        }
        if (inv instanceof PlayerInventory) {
            return new Grid(inv, PlayerInventoryUtils.CRAFT_RESULT, new int[]{
                    PlayerInventoryUtils.CRAFT_SLOT_1, PlayerInventoryUtils.CRAFT_SLOT_2,
                    PlayerInventoryUtils.CRAFT_SLOT_3, PlayerInventoryUtils.CRAFT_SLOT_4}, 2);
        }
        return null;
    }

    private static ItemStack[] readGrid(Grid grid) {
        ItemStack[] stacks = new ItemStack[grid.gridSlots().length];
        for (int i = 0; i < stacks.length; i++) {
            stacks[i] = grid.inv().getItemStack(grid.gridSlots()[i]);
        }
        return stacks;
    }

    private static void recompute(Grid grid) {
        ItemStack[] cells = readGrid(grid);
        ItemStack result = Banners.matchSpecial(cells);
        if (result == null) result = Recipes.matchCrafting(cells, grid.width());
        grid.inv().setItemStack(grid.resultSlot(), result);
    }

    private static void consumeIngredients(Grid grid) {
        ItemStack[] cells = readGrid(grid);
        // BannerDuplicateRecipe.getRemainingItems: the source (patterned) banner survives at
        // quantity 1 instead of being consumed like every other crafting-grid ingredient in
        // this project — the one asymmetric-consumption recipe, so it needs its own path.
        if (Banners.isDuplicateRecipe(cells)) {
            Banners.consumeDuplicate(cells, (slot, stack) -> grid.inv().setItemStack(grid.gridSlots()[slot], stack));
            return;
        }
        for (int slot : grid.gridSlots()) {
            ItemStack stack = grid.inv().getItemStack(slot);
            if (!stack.isAir()) grid.inv().setItemStack(slot, stack.consume(1));
        }
    }

    // ------------------------------------------------------------------ events

    private static void preClick(InventoryPreClickEvent e) {
        Grid grid = gridFor(e.getInventory());
        if (grid == null || e.getSlot() != grid.resultSlot()) return;
        Click click = e.getClick();
        // block all default handling on the result slot; we implement takes ourselves
        e.setCancelled(true);

        Player player = e.getPlayer();
        ItemStack result = Recipes.matchCrafting(readGrid(grid), grid.width());
        if (result.isAir()) {
            recompute(grid);
            return;
        }

        switch (click) {
            case Click.Left ignored -> take(player, grid, result);
            case Click.Right ignored -> take(player, grid, result);
            case Click.LeftShift ignored -> craftAll(player, grid);
            case Click.RightShift ignored -> craftAll(player, grid);
            case Click.DropSlot ignored -> {
                dev.pointofpressure.minecom.survival.Survival.dropItem(player, result, false);
                consumeIngredients(grid);
                recompute(grid);
            }
            default -> { }
        }
    }

    private static void take(Player player, Grid grid, ItemStack result) {
        ItemStack cursor = cursorOf(player, grid);
        if (cursor.isAir()) {
            setCursor(player, grid, result);
        } else if (cursor.material() == result.material()
                && cursor.amount() + result.amount() <= cursor.material().maxStackSize()) {
            setCursor(player, grid, cursor.withAmount(cursor.amount() + result.amount()));
        } else {
            return; // cursor can't accept the craft
        }
        consumeIngredients(grid);
        recompute(grid);
    }

    private static void craftAll(Player player, Grid grid) {
        for (int i = 0; i < 64; i++) {
            ItemStack result = Recipes.matchCrafting(readGrid(grid), grid.width());
            if (result.isAir()) break;
            if (!player.getInventory().addItemStack(result)) break;
            consumeIngredients(grid);
        }
        recompute(grid);
    }

    private static ItemStack cursorOf(Player player, Grid grid) {
        if (grid.inv() instanceof Inventory window) return window.getCursorItem(player);
        return player.getInventory().getCursorItem();
    }

    private static void setCursor(Player player, Grid grid, ItemStack stack) {
        if (grid.inv() instanceof Inventory window) window.setCursorItem(player, stack);
        else player.getInventory().setCursorItem(stack);
    }

    private static void postClick(InventoryClickEvent e) {
        // after any applied click, refresh the result of whichever grid the player can see
        Grid grid = gridFor(e.getInventory());
        if (grid == null && e.getInventory() instanceof PlayerInventory) {
            grid = gridFor(e.getInventory());
        }
        if (grid != null && e.getSlot() != grid.resultSlot()) {
            recompute(grid);
        }
        // clicking own inventory while a table is open can shift items out of the table grid
        AbstractInventory open = e.getPlayer().getOpenInventory();
        if (open instanceof Inventory window && TABLES.contains(window)) {
            recompute(gridFor(window));
        }
    }

    private static void close(InventoryCloseEvent e) {
        if (e.getInventory() instanceof Inventory window && TABLES.contains(window)) {
            returnGrid(e.getPlayer(), window);
            TABLES.remove(window);
        } else if (e.getInventory() instanceof PlayerInventory playerInv) {
            Grid grid = gridFor(playerInv);
            if (grid == null) return;
            for (int slot : grid.gridSlots()) {
                ItemStack stack = playerInv.getItemStack(slot);
                if (stack.isAir()) continue;
                playerInv.setItemStack(slot, ItemStack.AIR);
                Containers.giveOrDrop(e.getPlayer(), stack);
            }
            playerInv.setItemStack(grid.resultSlot(), ItemStack.AIR);
        }
    }

    private static void returnGrid(Player player, Inventory window) {
        for (int slot = 1; slot <= 9; slot++) {
            ItemStack stack = window.getItemStack(slot);
            if (stack.isAir()) continue;
            window.setItemStack(slot, ItemStack.AIR);
            Containers.giveOrDrop(player, stack);
        }
        window.setItemStack(0, ItemStack.AIR);
    }
}
