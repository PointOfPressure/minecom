package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ComposterBlock: real per-item compost chance (COMPOSTABLES, ported verbatim from
 * ComposterBlock.bootStrap — 115 items across 5 chance tiers). Fill level lives directly in
 * the block's own "level" state property (0-8), so no separate position-state map is needed
 * — only the 7-to-8 "ready" transition needs a timer, matching real vanilla's
 * {@code level.scheduleTick(pos, block, 20)}.
 */
public final class Composter {
    private Composter() {}

    private static final Map<Material, Float> COMPOSTABLES = new ConcurrentHashMap<>();
    static {
        COMPOSTABLES.put(Material.JUNGLE_LEAVES, 0.3f);
        COMPOSTABLES.put(Material.OAK_LEAVES, 0.3f);
        COMPOSTABLES.put(Material.SPRUCE_LEAVES, 0.3f);
        COMPOSTABLES.put(Material.DARK_OAK_LEAVES, 0.3f);
        COMPOSTABLES.put(Material.PALE_OAK_LEAVES, 0.3f);
        COMPOSTABLES.put(Material.ACACIA_LEAVES, 0.3f);
        COMPOSTABLES.put(Material.CHERRY_LEAVES, 0.3f);
        COMPOSTABLES.put(Material.BIRCH_LEAVES, 0.3f);
        COMPOSTABLES.put(Material.AZALEA_LEAVES, 0.3f);
        COMPOSTABLES.put(Material.MANGROVE_LEAVES, 0.3f);
        COMPOSTABLES.put(Material.OAK_SAPLING, 0.3f);
        COMPOSTABLES.put(Material.SPRUCE_SAPLING, 0.3f);
        COMPOSTABLES.put(Material.BIRCH_SAPLING, 0.3f);
        COMPOSTABLES.put(Material.JUNGLE_SAPLING, 0.3f);
        COMPOSTABLES.put(Material.ACACIA_SAPLING, 0.3f);
        COMPOSTABLES.put(Material.CHERRY_SAPLING, 0.3f);
        COMPOSTABLES.put(Material.DARK_OAK_SAPLING, 0.3f);
        COMPOSTABLES.put(Material.PALE_OAK_SAPLING, 0.3f);
        COMPOSTABLES.put(Material.MANGROVE_PROPAGULE, 0.3f);
        COMPOSTABLES.put(Material.BEETROOT_SEEDS, 0.3f);
        COMPOSTABLES.put(Material.DRIED_KELP, 0.3f);
        COMPOSTABLES.put(Material.SHORT_GRASS, 0.3f);
        COMPOSTABLES.put(Material.KELP, 0.3f);
        COMPOSTABLES.put(Material.MELON_SEEDS, 0.3f);
        COMPOSTABLES.put(Material.PUMPKIN_SEEDS, 0.3f);
        COMPOSTABLES.put(Material.SEAGRASS, 0.3f);
        COMPOSTABLES.put(Material.SWEET_BERRIES, 0.3f);
        COMPOSTABLES.put(Material.GLOW_BERRIES, 0.3f);
        COMPOSTABLES.put(Material.WHEAT_SEEDS, 0.3f);
        COMPOSTABLES.put(Material.MOSS_CARPET, 0.3f);
        COMPOSTABLES.put(Material.PALE_MOSS_CARPET, 0.3f);
        COMPOSTABLES.put(Material.PALE_HANGING_MOSS, 0.3f);
        COMPOSTABLES.put(Material.PINK_PETALS, 0.3f);
        COMPOSTABLES.put(Material.WILDFLOWERS, 0.3f);
        COMPOSTABLES.put(Material.LEAF_LITTER, 0.3f);
        COMPOSTABLES.put(Material.SMALL_DRIPLEAF, 0.3f);
        COMPOSTABLES.put(Material.HANGING_ROOTS, 0.3f);
        COMPOSTABLES.put(Material.MANGROVE_ROOTS, 0.3f);
        COMPOSTABLES.put(Material.TORCHFLOWER_SEEDS, 0.3f);
        COMPOSTABLES.put(Material.PITCHER_POD, 0.3f);
        COMPOSTABLES.put(Material.FIREFLY_BUSH, 0.3f);
        COMPOSTABLES.put(Material.BUSH, 0.3f);
        COMPOSTABLES.put(Material.CACTUS_FLOWER, 0.3f);
        COMPOSTABLES.put(Material.SHORT_DRY_GRASS, 0.3f);
        COMPOSTABLES.put(Material.TALL_DRY_GRASS, 0.3f);
        COMPOSTABLES.put(Material.DRIED_KELP_BLOCK, 0.5f);
        COMPOSTABLES.put(Material.TALL_GRASS, 0.5f);
        COMPOSTABLES.put(Material.FLOWERING_AZALEA_LEAVES, 0.5f);
        COMPOSTABLES.put(Material.CACTUS, 0.5f);
        COMPOSTABLES.put(Material.SUGAR_CANE, 0.5f);
        COMPOSTABLES.put(Material.VINE, 0.5f);
        COMPOSTABLES.put(Material.NETHER_SPROUTS, 0.5f);
        COMPOSTABLES.put(Material.WEEPING_VINES, 0.5f);
        COMPOSTABLES.put(Material.TWISTING_VINES, 0.5f);
        COMPOSTABLES.put(Material.MELON_SLICE, 0.5f);
        COMPOSTABLES.put(Material.GLOW_LICHEN, 0.5f);
        COMPOSTABLES.put(Material.SEA_PICKLE, 0.65f);
        COMPOSTABLES.put(Material.LILY_PAD, 0.65f);
        COMPOSTABLES.put(Material.PUMPKIN, 0.65f);
        COMPOSTABLES.put(Material.CARVED_PUMPKIN, 0.65f);
        COMPOSTABLES.put(Material.MELON, 0.65f);
        COMPOSTABLES.put(Material.APPLE, 0.65f);
        COMPOSTABLES.put(Material.BEETROOT, 0.65f);
        COMPOSTABLES.put(Material.CARROT, 0.65f);
        COMPOSTABLES.put(Material.COCOA_BEANS, 0.65f);
        COMPOSTABLES.put(Material.POTATO, 0.65f);
        COMPOSTABLES.put(Material.WHEAT, 0.65f);
        COMPOSTABLES.put(Material.BROWN_MUSHROOM, 0.65f);
        COMPOSTABLES.put(Material.RED_MUSHROOM, 0.65f);
        COMPOSTABLES.put(Material.MUSHROOM_STEM, 0.65f);
        COMPOSTABLES.put(Material.CRIMSON_FUNGUS, 0.65f);
        COMPOSTABLES.put(Material.WARPED_FUNGUS, 0.65f);
        COMPOSTABLES.put(Material.NETHER_WART, 0.65f);
        COMPOSTABLES.put(Material.CRIMSON_ROOTS, 0.65f);
        COMPOSTABLES.put(Material.WARPED_ROOTS, 0.65f);
        COMPOSTABLES.put(Material.SHROOMLIGHT, 0.65f);
        COMPOSTABLES.put(Material.DANDELION, 0.65f);
        COMPOSTABLES.put(Material.POPPY, 0.65f);
        COMPOSTABLES.put(Material.BLUE_ORCHID, 0.65f);
        COMPOSTABLES.put(Material.ALLIUM, 0.65f);
        COMPOSTABLES.put(Material.AZURE_BLUET, 0.65f);
        COMPOSTABLES.put(Material.RED_TULIP, 0.65f);
        COMPOSTABLES.put(Material.ORANGE_TULIP, 0.65f);
        COMPOSTABLES.put(Material.WHITE_TULIP, 0.65f);
        COMPOSTABLES.put(Material.PINK_TULIP, 0.65f);
        COMPOSTABLES.put(Material.OXEYE_DAISY, 0.65f);
        COMPOSTABLES.put(Material.CORNFLOWER, 0.65f);
        COMPOSTABLES.put(Material.LILY_OF_THE_VALLEY, 0.65f);
        COMPOSTABLES.put(Material.WITHER_ROSE, 0.65f);
        COMPOSTABLES.put(Material.OPEN_EYEBLOSSOM, 0.65f);
        COMPOSTABLES.put(Material.CLOSED_EYEBLOSSOM, 0.65f);
        COMPOSTABLES.put(Material.FERN, 0.65f);
        COMPOSTABLES.put(Material.SUNFLOWER, 0.65f);
        COMPOSTABLES.put(Material.LILAC, 0.65f);
        COMPOSTABLES.put(Material.ROSE_BUSH, 0.65f);
        COMPOSTABLES.put(Material.PEONY, 0.65f);
        COMPOSTABLES.put(Material.LARGE_FERN, 0.65f);
        COMPOSTABLES.put(Material.SPORE_BLOSSOM, 0.65f);
        COMPOSTABLES.put(Material.AZALEA, 0.65f);
        COMPOSTABLES.put(Material.MOSS_BLOCK, 0.65f);
        COMPOSTABLES.put(Material.PALE_MOSS_BLOCK, 0.65f);
        COMPOSTABLES.put(Material.BIG_DRIPLEAF, 0.65f);
        COMPOSTABLES.put(Material.HAY_BLOCK, 0.85f);
        COMPOSTABLES.put(Material.BROWN_MUSHROOM_BLOCK, 0.85f);
        COMPOSTABLES.put(Material.RED_MUSHROOM_BLOCK, 0.85f);
        COMPOSTABLES.put(Material.NETHER_WART_BLOCK, 0.85f);
        COMPOSTABLES.put(Material.WARPED_WART_BLOCK, 0.85f);
        COMPOSTABLES.put(Material.FLOWERING_AZALEA, 0.85f);
        COMPOSTABLES.put(Material.BREAD, 0.85f);
        COMPOSTABLES.put(Material.BAKED_POTATO, 0.85f);
        COMPOSTABLES.put(Material.COOKIE, 0.85f);
        COMPOSTABLES.put(Material.TORCHFLOWER, 0.85f);
        COMPOSTABLES.put(Material.PITCHER_PLANT, 0.85f);
        COMPOSTABLES.put(Material.CAKE, 1.0f);
        COMPOSTABLES.put(Material.PUMPKIN_PIE, 1.0f);
    }

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, Composter::useOnBlock);
        events.addListener(PlayerBlockInteractEvent.class, Composter::interact);
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("composter")) return;

        ItemStack held = e.getItemStack();
        if (held.isAir()) return;
        Float chance = COMPOSTABLES.get(held.material());
        if (chance == null) return;

        int level = Integer.parseInt(block.getProperty("level"));
        if (level >= 7) return; // real vanilla: SUCCESS but no-op past this point

        // ComposterBlock.addItem: an empty composter (level 0) always accepts its first item,
        // regardless of chance; every later item is gated by the real per-item chance roll.
        boolean succeeds = level == 0 || ThreadLocalRandom.current().nextDouble() < chance;
        if (!succeeds) {
            e.getPlayer().setItemInHand(e.getHand(), held.consume(1));
            return;
        }

        int newLevel = level + 1;
        instance.setBlock(pos, block.withProperty("level", String.valueOf(newLevel)));
        e.getPlayer().setItemInHand(e.getHand(), held.consume(1));
        if (newLevel == 7) {
            instance.scheduler().buildTask(() -> {
                Block now = instance.getBlock(pos);
                if (now.key().value().equals("composter") && "7".equals(now.getProperty("level"))) {
                    instance.setBlock(pos, now.withProperty("level", "8"));
                }
            }).delay(TaskSchedule.tick(20)).schedule();
        }
    }

    private static void interact(PlayerBlockInteractEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("composter")) return;
        if (!"8".equals(block.getProperty("level"))) return;

        e.setBlockingItemUse(true);
        ItemEntity drop = new ItemEntity(ItemStack.of(Material.BONE_MEAL));
        drop.setInstance(instance, new Pos(pos.x() + 0.5, pos.y() + 1.01, pos.z() + 0.5));
        drop.setPickupDelay(java.time.Duration.ofMillis(200));
        instance.setBlock(pos, block.withProperty("level", "0"));
    }
}
