package dev.pointofpressure.minecom.survival;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.inventory.AbstractInventory;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.sound.SoundEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bundles, decompile-verified against {@code BundleItem}/{@code BundleContents} (26.2,
 * freshly decompiled — no cached copy existed before this pass). Vanilla's
 * {@code overrideStackedOnOther}/{@code overrideOtherStackedOnMe} hooks let a bundle item
 * intercept the generic inventory-click pipeline no matter which menu it's in (player
 * inventory, a chest, a furnace...); this project has no equivalent per-item click-override
 * hook, so the same outcome is reproduced with one global
 * {@link InventoryPreClickEvent} listener that checks whether the cursor or the clicked slot
 * holds a bundle and, if so, cancels the default click and runs the insert/remove logic
 * itself — the same "cancel + hand-roll" shape {@code blocks.Crafting} already uses for its
 * result slot.
 *
 * <p>Capacity rides {@code DataComponents.BUNDLE_CONTENTS} — Minestom's own native component
 * (a plain {@code List<ItemStack>}), so the client already renders the bundle's fullness bar
 * and contents tooltip from it with zero extra wiring, same precedent as Banners'
 * {@code BANNER_PATTERNS}/Maps' map-id tags. Persistence is free too: {@code Persist.java}'s
 * item round-trip already goes through Minestom's generic {@code ItemStack#toItemNBT}, which
 * walks every component including this one.
 *
 * <p>Weight math: real vanilla tracks capacity as an {@code org.apache.commons.lang3.Fraction}
 * out of 1.0, one item's weight being {@code 1/maxStackSize} (or, for a nested bundle,
 * {@code 1/16 + that bundle's own weight}). Since every stack size in this game is 1, 16, or
 * 64 — all divisors of 64 — the exact same math is reproduced with plain {@code int} "weight
 * units out of 64" instead of a fraction type, avoiding a new dependency for a value nothing
 * else in this project consumes as a true fraction (not a simplification of the vanilla
 * formula, just an exact integer re-encoding of it).
 *
 * <p>Simplifications (AUDIT): the sub-item click-to-select-before-removing UI (vanilla's
 * {@code toggleSelectedItem}, which needs the client to report which icon inside the bundle's
 * own grid tooltip was clicked — a level of click-target precision Minestom's inventory-click
 * protocol doesn't expose) is not modeled; {@code removeTop} always pops the same entry
 * vanilla's own un-selected fallback does (index 0, the most-recently-inserted stack), which
 * is a real vanilla behavior, not an approximation of one. Holding right-click to continuously
 * spill contents (vanilla's {@code onUseTick}, a per-tick drop while the 200-tick "use"
 * animation is held) collapses to a single tap-to-pop-one-entry on {@link PlayerUseItemEvent}
 * — this project has no per-tick "item still being used" callback the way it has
 * begin/finish/cancel hooks (see {@code Crossbow.java}), and one bundle already holds a whole
 * merged stack per entry, so a single tap already empties a meaningful chunk. Beehive-with-bees
 * items force-filling a bundle to weight 1.0 (vanilla's one hardcoded exception to the
 * per-max-stack-size formula) is not modeled — this project has no beehive-holds-bees item
 * component to check against.
 */
public final class Bundles {
    private Bundles() {}

    private static final int CAPACITY_UNITS = 64;
    /** BundleContents.BUNDLE_IN_BUNDLE_WEIGHT = 1/16; in 64ths that's 4. */
    private static final int BUNDLE_IN_BUNDLE_UNITS = 4;

    public static void register(GlobalEventHandler events) {
        events.addListener(InventoryPreClickEvent.class, Bundles::preClick);
        events.addListener(PlayerUseItemEvent.class, Bundles::useInAir);
    }

    // ------------------------------------------------------------------ item-shape helpers

    public static boolean isBundle(ItemStack stack) {
        if (stack == null || stack.isAir()) return false;
        String key = stack.material().key().value();
        return key.equals("bundle") || key.endsWith("_bundle");
    }

    /** BundleContents.canItemBeInBundle: everything except a shulker box (any color). */
    private static boolean canFitInBundle(ItemStack stack) {
        return !stack.isAir() && !stack.material().key().value().endsWith("shulker_box");
    }

    public static List<ItemStack> contentsOf(ItemStack bundle) {
        List<ItemStack> c = bundle.get(DataComponents.BUNDLE_CONTENTS);
        return c == null ? List.of() : c;
    }

    private static int unitWeight(ItemStack single) {
        List<ItemStack> nested = single.get(DataComponents.BUNDLE_CONTENTS);
        if (nested != null) return BUNDLE_IN_BUNDLE_UNITS + totalWeight(nested);
        int maxStack = Math.max(1, single.material().maxStackSize());
        return Math.max(1, CAPACITY_UNITS / maxStack);
    }

    public static int totalWeight(List<ItemStack> items) {
        int total = 0;
        for (ItemStack s : items) total += unitWeight(s) * s.amount();
        return total;
    }

    // ------------------------------------------------------------------ contents mutation

    public record InsertOutcome(List<ItemStack> contents, int inserted) {}

    /** BundleContents.Mutable.tryInsert, folded with tryTransfer's caller-side amount math. */
    public static InsertOutcome insert(List<ItemStack> current, ItemStack incoming) {
        if (!canFitInBundle(incoming) || incoming.amount() <= 0) return new InsertOutcome(current, 0);
        int perUnit = unitWeight(incoming.withAmount(1));
        int remaining = CAPACITY_UNITS - totalWeight(current);
        int maxAdd = perUnit <= 0 ? 0 : Math.max(0, remaining / perUnit);
        int amount = Math.min(incoming.amount(), maxAdd);
        if (amount <= 0) return new InsertOutcome(current, 0);

        List<ItemStack> updated = new ArrayList<>(current);
        int mergeIdx = -1;
        if (incoming.material().maxStackSize() > 1) { // BundleContents.findStackIndex: isStackable() gate
            for (int i = 0; i < updated.size(); i++) {
                if (updated.get(i).isSimilar(incoming)) { mergeIdx = i; break; }
            }
        }
        if (mergeIdx != -1) {
            ItemStack merged = updated.remove(mergeIdx);
            updated.add(0, merged.withAmount(merged.amount() + amount));
        } else {
            updated.add(0, incoming.withAmount(amount));
        }
        return new InsertOutcome(updated, amount);
    }

    public record RemoveOutcome(List<ItemStack> contents, ItemStack removed) {}

    /** BundleContents.Mutable.removeOne: pops the WHOLE top entry, not one item unit. */
    public static RemoveOutcome removeTop(List<ItemStack> current) {
        if (current.isEmpty()) return new RemoveOutcome(current, ItemStack.AIR);
        List<ItemStack> updated = new ArrayList<>(current);
        ItemStack top = updated.remove(0);
        return new RemoveOutcome(updated, top);
    }

    // ------------------------------------------------------------------ click handling

    private static void preClick(InventoryPreClickEvent e) {
        Click click = e.getClick();
        boolean left = click instanceof Click.Left;
        boolean right = click instanceof Click.Right;
        if (!left && !right) return;
        int slot = e.getSlot();
        if (slot < 0) return; // drops/drags: vanilla's own hooks don't cover these either

        Player player = e.getPlayer();
        AbstractInventory inv = e.getInventory();
        ItemStack cursor = cursorOf(inv, player);
        ItemStack slotItem = inv.getItemStack(slot);

        if (isBundle(cursor)) {
            List<ItemStack> contents = contentsOf(cursor);
            if (left && !slotItem.isAir()) {
                InsertOutcome outcome = insert(contents, slotItem);
                e.setCancelled(true);
                if (outcome.inserted() > 0) {
                    setCursor(inv, player, cursor.with(DataComponents.BUNDLE_CONTENTS, outcome.contents()));
                    int rest = slotItem.amount() - outcome.inserted();
                    inv.setItemStack(slot, rest <= 0 ? ItemStack.AIR : slotItem.withAmount(rest));
                    playSound(player, SoundEvent.ITEM_BUNDLE_INSERT);
                } else {
                    playSound(player, SoundEvent.ITEM_BUNDLE_INSERT_FAIL);
                }
            } else if (right && slotItem.isAir()) {
                RemoveOutcome outcome = removeTop(contents);
                e.setCancelled(true);
                if (!outcome.removed().isAir()) {
                    setCursor(inv, player, cursor.with(DataComponents.BUNDLE_CONTENTS, outcome.contents()));
                    inv.setItemStack(slot, outcome.removed());
                    playSound(player, SoundEvent.ITEM_BUNDLE_REMOVE_ONE);
                }
            }
            // Left+empty slot, Right+filled slot: default click handling (move/swap the bundle itself)
            return;
        }

        if (isBundle(slotItem)) {
            List<ItemStack> contents = contentsOf(slotItem);
            if (left && !cursor.isAir()) {
                InsertOutcome outcome = insert(contents, cursor);
                e.setCancelled(true);
                inv.setItemStack(slot, slotItem.with(DataComponents.BUNDLE_CONTENTS, outcome.contents()));
                if (outcome.inserted() > 0) {
                    int rest = cursor.amount() - outcome.inserted();
                    setCursor(inv, player, rest <= 0 ? ItemStack.AIR : cursor.withAmount(rest));
                    playSound(player, SoundEvent.ITEM_BUNDLE_INSERT);
                } else {
                    playSound(player, SoundEvent.ITEM_BUNDLE_INSERT_FAIL);
                }
            } else if (right && cursor.isAir()) {
                RemoveOutcome outcome = removeTop(contents);
                e.setCancelled(true);
                if (!outcome.removed().isAir()) {
                    inv.setItemStack(slot, slotItem.with(DataComponents.BUNDLE_CONTENTS, outcome.contents()));
                    setCursor(inv, player, outcome.removed());
                    playSound(player, SoundEvent.ITEM_BUNDLE_REMOVE_ONE);
                }
            }
        }
    }

    /** Right-click in the air (no target block/entity): pop-and-drop one entry (see class doc). */
    private static void useInAir(PlayerUseItemEvent e) {
        ItemStack stack = e.getItemStack();
        if (!isBundle(stack)) return;
        e.setCancelled(true); // no held-use animation modeled — single tap, see class doc

        List<ItemStack> contents = contentsOf(stack);
        if (contents.isEmpty()) return;
        RemoveOutcome outcome = removeTop(contents);
        Player player = e.getPlayer();
        player.setItemInHand(e.getHand(), stack.with(DataComponents.BUNDLE_CONTENTS, outcome.contents()));
        Survival.dropItem(player, outcome.removed(), false);
        playSound(player, SoundEvent.ITEM_BUNDLE_DROP_CONTENTS);
    }

    private static ItemStack cursorOf(AbstractInventory inv, Player player) {
        if (inv instanceof Inventory window) return window.getCursorItem(player);
        return player.getInventory().getCursorItem();
    }

    private static void setCursor(AbstractInventory inv, Player player, ItemStack stack) {
        if (inv instanceof Inventory window) window.setCursorItem(player, stack);
        else player.getInventory().setCursorItem(stack);
    }

    private static void playSound(Player player, SoundEvent event) {
        float pitch = 0.8f + ThreadLocalRandom.current().nextFloat() * 0.4f;
        player.playSound(Sound.sound(event, Sound.Source.PLAYER, 0.8f, pitch));
    }
}
