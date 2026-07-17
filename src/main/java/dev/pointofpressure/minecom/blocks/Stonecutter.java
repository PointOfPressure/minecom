package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.data.Recipes;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryButtonClickEvent;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryItemChangeEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.recipe.Recipe;
import net.minestom.server.recipe.RecipeBookCategory;
import net.minestom.server.recipe.display.RecipeDisplay;
import net.minestom.server.recipe.display.SlotDisplay;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stonecutter: ported from decompiled StonecutterMenu (26.2, cached under vanilla-src/). Unlike
 * every other station in this codebase, its result depends on a THIRD input the player picks
 * interactively — which of the (possibly several) recipes matching the current input block they
 * want — via a client-side button click ({@link InventoryButtonClickEvent}, button id = list
 * index), not just item placement. That button list only renders at all because
 * {@link #declareRecipes} registers every {@code stonecutting} recipe with Minestom's
 * {@link net.minestom.server.recipe.RecipeManager} at startup: real vanilla's stonecutter
 * button UI is built client-side from ITS OWN known recipe registry filtered by the current
 * input, and Minestom mirrors that exactly via {@code DeclareRecipesPacket.stonecutterRecipes}
 * (sent automatically on join, see {@code Player#549-553}) — without declaring the recipes here
 * first, the client would show zero buttons for every input, no matter what
 * {@link Recipes#stonecuttingFor} says server-side. Declaring them ourselves also sidesteps any
 * risk of our recipe order disagreeing with real vanilla's: the client learns the list FROM us,
 * so whatever order {@link Recipes#stonecuttingFor} returns (see its own doc) is definitionally
 * the order button indices mean on both sides.
 *
 * <ul>
 * <li><b>slot 0 changes material</b> (StonecutterMenu.slotsChanged: {@code !input.is(...)},
 * not just count): reset the selection and clear the result — a same-material count change
 * (e.g. from taking a result) does neither.
 * <li><b>button click</b> selects a recipe index into {@link Recipes#stonecuttingFor}'s list for
 * the current input and previews its exact result (StonecutterMenu.setupResultSlot).
 * <li><b>taking the result</b> consumes exactly 1 from the input and, if any remains (same
 * material), re-populates the SAME selected recipe's result for a repeat take — the input
 * material didn't change, so the slot-0 listener above doesn't fire, matching real vanilla's own
 * redundant re-{@code setupResultSlot} call after every take.
 * </ul>
 */
public final class Stonecutter {
    private Stonecutter() {}

    private static final Set<Inventory> OPEN = ConcurrentHashMap.newKeySet();
    private static final Map<Inventory, Material> LAST_INPUT = new ConcurrentHashMap<>();
    private static final Map<Inventory, Integer> SELECTED = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        declareRecipes();
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().equals("stonecutter")) return;
            e.setBlockingItemUse(true);
            Inventory inv = new Inventory(InventoryType.STONE_CUTTER, Component.text("Stonecutter"));
            OPEN.add(inv);
            e.getPlayer().openInventory(inv);
        });
        events.addListener(InventoryItemChangeEvent.class, e -> {
            if (e.getSlot() != 0) return;
            if (!(e.getInventory() instanceof Inventory inv) || !OPEN.contains(inv)) return;
            Material now = inv.getItemStack(0).isAir() ? null : inv.getItemStack(0).material();
            if (now != LAST_INPUT.get(inv)) {
                LAST_INPUT.put(inv, now);
                SELECTED.put(inv, -1);
                inv.setItemStack(1, ItemStack.AIR);
            }
        });
        events.addListener(InventoryButtonClickEvent.class, e -> {
            if (!(e.getInventory() instanceof Inventory inv) || !OPEN.contains(inv)) return;
            int buttonId = e.getButtonId();
            if (SELECTED.getOrDefault(inv, -1) == buttonId) return;
            List<Recipes.Stonecut> options = Recipes.stonecuttingFor(inv.getItemStack(0).material());
            if (buttonId < 0 || buttonId >= options.size()) return;
            SELECTED.put(inv, buttonId);
            inv.setItemStack(1, options.get(buttonId).result());
        });
        events.addListener(InventoryPreClickEvent.class, e -> {
            if (!(e.getInventory() instanceof Inventory inv) || !OPEN.contains(inv)) return;
            if (e.getSlot() != 1) return;
            e.setCancelled(true);
            ItemStack result = inv.getItemStack(1);
            if (result.isAir()) return;
            Player player = e.getPlayer();
            if (!inv.getCursorItem(player).isAir()) return;
            inv.setCursorItem(player, result);
            ItemStack remaining = inv.getItemStack(0).consume(1);
            inv.setItemStack(0, remaining);
            if (!remaining.isAir()) {
                int selected = SELECTED.getOrDefault(inv, -1);
                List<Recipes.Stonecut> options = Recipes.stonecuttingFor(remaining.material());
                inv.setItemStack(1, selected >= 0 && selected < options.size()
                        ? options.get(selected).result() : ItemStack.AIR);
            }
            // else: the slot-0 listener above already fired on the AIR transition and cleared
            // the selection/result for us.
        });
        events.addListener(InventoryCloseEvent.class, e -> {
            if (e.getInventory() instanceof Inventory inv && OPEN.remove(inv)) {
                LAST_INPUT.remove(inv);
                SELECTED.remove(inv);
                ItemStack stack = inv.getItemStack(0);
                if (!stack.isAir()) Containers.giveOrDrop(e.getPlayer(), stack);
            }
        });
    }

    private record StonecutterRecipe(String id, Material input, ItemStack result) implements Recipe {
        @Override
        public List<RecipeDisplay> createRecipeDisplays() {
            return List.of(new RecipeDisplay.Stonecutter(
                    new SlotDisplay.Item(input), new SlotDisplay.ItemStack(result),
                    new SlotDisplay.Item(Material.STONECUTTER)));
        }

        @Override
        public RecipeBookCategory recipeBookCategory() {
            return RecipeBookCategory.STONECUTTER;
        }
    }

    /** Registers every bundled {@code stonecutting} recipe with Minestom so the client actually
     * renders the button list — see the class doc. Called once, at startup. */
    private static void declareRecipes() {
        var manager = MinecraftServer.getRecipeManager();
        for (String key : Recipes.stonecuttingInputs()) {
            Material input = Material.fromKey(key);
            if (input == null) continue;
            for (Recipes.Stonecut cut : Recipes.stonecuttingFor(input)) {
                manager.addRecipe(new StonecutterRecipe(cut.id(), input, cut.result()));
            }
        }
    }
}
