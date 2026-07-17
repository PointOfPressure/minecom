package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.HashMap;
import java.util.Map;

/**
 * FlowerPotBlock.useItemOn / useWithoutItem (decompile-verified against 26.2,
 * vanilla-src/net/minecraft/world/level/block/FlowerPotBlock.java). Real vanilla gives a flower
 * pot no block-entity state at all: "planting" swaps the whole block straight to its
 * potted_&lt;content&gt; variant (the real {@code POTTED_BY_CONTENT} map, reproduced here as
 * {@link #PLANT_TO_POTTED} from the 37-entry list registered against {@code Blocks.java}) and
 * "un-planting" swaps it straight back to the empty {@code flower_pot}, handing the plant item
 * back to the player — so this needs no {@code StateAdapter}, persistence rides the world's own
 * block storage for free, same as any other blockstate-only feature.
 * <p>
 * Both directions are handled from a single {@link PlayerBlockInteractEvent} listener rather
 * than the more common "insert via {@code PlayerUseItemOnBlockEvent}, remove via
 * {@code PlayerBlockInteractEvent}" split used elsewhere in this file's siblings (decorated pot,
 * chiseled bookshelf): EVERY one of the 37 pottable plant items is itself a real placeable block
 * (sapling/flower/fungus/etc.), so {@code BlockPlacementListener} (decompile-verified,
 * vanilla-src not needed — this is Minestom's own dispatcher) never reaches
 * {@code PlayerUseItemOnBlockEvent} for them at all: a block-material item skips straight from
 * the block-interact event to normal block PLACEMENT once interact doesn't consume it. Real
 * vanilla's own dispatch is item-first-with-block-fallback ({@code useItemOn} tried before
 * {@code useWithoutItem}); Minestom's is interact-first-with-item-fallback
 * ({@code PlayerBlockInteractEvent} always fires first, and can call
 * {@code setBlockingItemUse(true)} to suppress everything after it) — reproducing vanilla's
 * exact outcome table therefore means doing both checks in the one event that's guaranteed to
 * fire, reading the held item off {@code player.getItemInHand(hand)} directly rather than
 * relying on a second event that would never arrive for a block-shaped plant.
 * <p>
 * Outcome table (matches real vanilla exactly): empty pot + valid plant in hand -&gt; plants,
 * item consumed, blocks fallthrough (no accidental block placement). Empty pot + anything else
 * -&gt; no-op, but still blocks fallthrough (an empty flower pot itself isn't
 * self-replaceable in real vanilla either). Filled pot + a valid plant in hand -&gt; pure
 * no-op (real vanilla's {@code CONSUME}, doesn't extract, doesn't swap). Filled pot + anything
 * else (including empty hand) -&gt; pops the existing plant back into the player's inventory (or
 * drops it if full) and reverts to an empty pot.
 * <p>
 * Not modeled: {@code POTTED_OPEN_EYEBLOSSOM}/{@code POTTED_CLOSED_EYEBLOSSOM}'s random-tick
 * day/night auto-toggle (a biome {@code EnvironmentAttributes.EYEBLOSSOM_OPEN} cosmetic swap,
 * unrelated to planting/unplanting) — the pot can still be planted/unplanted with either
 * eyeblossom state, it just never flips on its own the way the free-standing plant does.
 */
public final class FlowerPots {
    private FlowerPots() {}

    private static final Map<Material, Block> PLANT_TO_POTTED = buildPlantToPotted();
    private static final Map<Block, Material> POTTED_TO_PLANT = buildPottedToPlant();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, FlowerPots::interact);
    }

    private static void interact(PlayerBlockInteractEvent e) {
        Block block = e.getBlock();
        boolean isPot = block == Block.FLOWER_POT;
        Material existingPlant = POTTED_TO_PLANT.get(block);
        if (!isPot && existingPlant == null) return; // not a flower pot at all

        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();
        ItemStack held = e.getPlayer().getItemInHand(e.getHand());
        Block potted = held.isAir() ? null : PLANT_TO_POTTED.get(held.material());

        if (isPot) {
            e.setBlockingItemUse(true); // empty pot is never self-replaceable in real vanilla either
            if (potted == null) return; // nothing to plant with the held item
            instance.setBlock(pos, potted);
            e.getPlayer().setItemInHand(e.getHand(), held.consume(1));
            return;
        }

        // filled pot: a matching (or any other) pottable item held is a pure no-op, real
        // vanilla's useItemOn CONSUME branch — only a non-plant hand actually extracts.
        e.setBlockingItemUse(true);
        if (potted != null) return;
        instance.setBlock(pos, Block.FLOWER_POT);
        Containers.giveOrDrop(e.getPlayer(), ItemStack.of(existingPlant));
    }

    /** The real 37-entry {@code Blocks.java} potted-flower-pot registration, keyed by plant item. */
    private static Map<Material, Block> buildPlantToPotted() {
        Map<Material, Block> m = new HashMap<>();
        m.put(Material.TORCHFLOWER, Block.POTTED_TORCHFLOWER);
        m.put(Material.OAK_SAPLING, Block.POTTED_OAK_SAPLING);
        m.put(Material.SPRUCE_SAPLING, Block.POTTED_SPRUCE_SAPLING);
        m.put(Material.BIRCH_SAPLING, Block.POTTED_BIRCH_SAPLING);
        m.put(Material.JUNGLE_SAPLING, Block.POTTED_JUNGLE_SAPLING);
        m.put(Material.ACACIA_SAPLING, Block.POTTED_ACACIA_SAPLING);
        m.put(Material.CHERRY_SAPLING, Block.POTTED_CHERRY_SAPLING);
        m.put(Material.DARK_OAK_SAPLING, Block.POTTED_DARK_OAK_SAPLING);
        m.put(Material.PALE_OAK_SAPLING, Block.POTTED_PALE_OAK_SAPLING);
        m.put(Material.MANGROVE_PROPAGULE, Block.POTTED_MANGROVE_PROPAGULE);
        m.put(Material.FERN, Block.POTTED_FERN);
        m.put(Material.DANDELION, Block.POTTED_DANDELION);
        m.put(Material.GOLDEN_DANDELION, Block.POTTED_GOLDEN_DANDELION);
        m.put(Material.POPPY, Block.POTTED_POPPY);
        m.put(Material.BLUE_ORCHID, Block.POTTED_BLUE_ORCHID);
        m.put(Material.ALLIUM, Block.POTTED_ALLIUM);
        m.put(Material.AZURE_BLUET, Block.POTTED_AZURE_BLUET);
        m.put(Material.RED_TULIP, Block.POTTED_RED_TULIP);
        m.put(Material.ORANGE_TULIP, Block.POTTED_ORANGE_TULIP);
        m.put(Material.WHITE_TULIP, Block.POTTED_WHITE_TULIP);
        m.put(Material.PINK_TULIP, Block.POTTED_PINK_TULIP);
        m.put(Material.OXEYE_DAISY, Block.POTTED_OXEYE_DAISY);
        m.put(Material.CORNFLOWER, Block.POTTED_CORNFLOWER);
        m.put(Material.LILY_OF_THE_VALLEY, Block.POTTED_LILY_OF_THE_VALLEY);
        m.put(Material.WITHER_ROSE, Block.POTTED_WITHER_ROSE);
        m.put(Material.RED_MUSHROOM, Block.POTTED_RED_MUSHROOM);
        m.put(Material.BROWN_MUSHROOM, Block.POTTED_BROWN_MUSHROOM);
        m.put(Material.DEAD_BUSH, Block.POTTED_DEAD_BUSH);
        m.put(Material.CACTUS, Block.POTTED_CACTUS);
        m.put(Material.BAMBOO, Block.POTTED_BAMBOO);
        m.put(Material.CRIMSON_FUNGUS, Block.POTTED_CRIMSON_FUNGUS);
        m.put(Material.WARPED_FUNGUS, Block.POTTED_WARPED_FUNGUS);
        m.put(Material.CRIMSON_ROOTS, Block.POTTED_CRIMSON_ROOTS);
        m.put(Material.WARPED_ROOTS, Block.POTTED_WARPED_ROOTS);
        m.put(Material.AZALEA, Block.POTTED_AZALEA_BUSH);
        m.put(Material.FLOWERING_AZALEA, Block.POTTED_FLOWERING_AZALEA_BUSH);
        m.put(Material.OPEN_EYEBLOSSOM, Block.POTTED_OPEN_EYEBLOSSOM);
        m.put(Material.CLOSED_EYEBLOSSOM, Block.POTTED_CLOSED_EYEBLOSSOM);
        return Map.copyOf(m);
    }

    private static Map<Block, Material> buildPottedToPlant() {
        Map<Block, Material> reverse = new HashMap<>();
        PLANT_TO_POTTED.forEach((plant, potted) -> reverse.put(potted, plant));
        return Map.copyOf(reverse);
    }
}
