package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.data.Items;
import dev.pointofpressure.minecom.data.LootTables;
import dev.pointofpressure.minecom.data.VanillaData;
import dev.pointofpressure.minecom.survival.Experience;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.metadata.other.FallingBlockMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

import java.time.Duration;
import java.util.Random;

/**
 * Block breaking with vanilla tool gating (mineable/* + needs_*_tool tags against
 * the real requiresTool registry flag), loot-table drops, and sand/gravel physics.
 */
public final class BlockRules {
    private BlockRules() {}

    private static final Random RANDOM = new Random();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockBreakEvent.class, BlockRules::onBreak);
        events.addListener(PlayerBlockPlaceEvent.class, e ->
                scheduleFallCheck(e.getInstance(), e.getBlockPosition()));
    }

    private static void onBreak(PlayerBlockBreakEvent e) {
        Block block = e.getBlock();
        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();

        Containers.onBlockRemoved(instance, pos, block);

        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) {
            ItemStack tool = e.getPlayer().getItemInMainHand();
            if (canHarvest(block, tool)) {
                for (ItemStack drop : LootTables.blockDrops(block, tool)) {
                    dropAt(instance, pos, drop);
                }
                int xp = Experience.oreXp(block);
                if (xp > 0) Experience.orb(instance, pos.add(0.5, 0.5, 0.5), xp);
            }
            if (block.registry().hardness() > 0) {
                e.getPlayer().setItemInMainHand(Items.damageItem(e.getPlayer(), tool, 1));
            }
        }
        scheduleFallCheck(instance, pos);
        if (block.key().value().endsWith("_log")) scheduleLeafDecay(instance, pos);
    }

    /** Natural leaves with no log within 4 blocks decay over the following seconds. */
    private static void scheduleLeafDecay(Instance instance, Point center) {
        instance.scheduler().buildTask(() -> {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        Point at = center.add(dx, dy, dz);
                        Block leaf = instance.getBlock(at);
                        if (!VanillaData.blockHasTag(leaf, "leaves")) continue;
                        if ("true".equals(leaf.getProperty("persistent"))) continue;
                        if (logNear(instance, at, 4)) continue;
                        instance.scheduler().buildTask(() -> {
                            Block still = instance.getBlock(at);
                            if (VanillaData.blockHasTag(still, "leaves")
                                    && !"true".equals(still.getProperty("persistent"))
                                    && !logNear(instance, at, 4)) {
                                instance.setBlock(at, Block.AIR);
                                for (ItemStack drop : LootTables.blockDrops(still, ItemStack.AIR)) {
                                    dropAt(instance, at, drop);
                                }
                                scheduleLeafDecay(instance, at); // cascade outward
                            }
                        }).delay(TaskSchedule.tick(5 + RANDOM.nextInt(30))).schedule();
                    }
                }
            }
        }).delay(TaskSchedule.tick(4)).schedule();
    }

    private static boolean logNear(Instance instance, Point pos, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > radius + 2) continue;
                    if (instance.getBlock(pos.add(dx, dy, dz)).key().value().endsWith("_log")) return true;
                }
            }
        }
        return false;
    }

    public static void dropAt(Instance instance, Point pos, ItemStack stack) {
        ItemEntity item = new ItemEntity(stack);
        item.setPickupDelay(Duration.ofMillis(500));
        item.setInstance(instance, pos.add(0.5, 0.5, 0.5));
        item.setVelocity(new Vec(RANDOM.nextDouble() - 0.5, 1.5, RANDOM.nextDouble() - 0.5));
    }

    // ------------------------------------------------------------------ tool gating

    /** Vanilla harvest rule: blocks flagged requiresTool only drop with the right tool class and tier. */
    public static boolean canHarvest(Block block, ItemStack tool) {
        if (!block.registry().requiresTool()) return true;
        Material mat = tool.isAir() ? null : tool.material();
        if (mat == null) return false;
        String key = mat.key().value();

        String toolClass = key.endsWith("_pickaxe") ? "pickaxe"
                : key.endsWith("_axe") ? "axe"
                : key.endsWith("_shovel") ? "shovel"
                : key.endsWith("_hoe") ? "hoe"
                : key.equals("shears") ? "shears"
                : key.equals("mace") ? "mace"
                : null;
        if (toolClass == null) return false;
        if (toolClass.equals("shears")) {
            return VanillaData.blockHasTag(block, "leaves") || block.compare(Block.COBWEB);
        }
        if (!VanillaData.blockHasTag(block, "mineable/" + toolClass)) return false;

        int tier = switch (key.split("_")[0]) {
            case "stone" -> 1;
            case "iron" -> 2;
            case "diamond", "netherite" -> 3;
            default -> 0; // wooden, golden
        };
        if (VanillaData.blockHasTag(block, "needs_diamond_tool")) return tier >= 3;
        if (VanillaData.blockHasTag(block, "needs_iron_tool")) return tier >= 2;
        if (VanillaData.blockHasTag(block, "needs_stone_tool")) return tier >= 1;
        return true;
    }

    // ------------------------------------------------------------------ falling blocks

    private static boolean isGravityBlock(Block block) {
        String key = block.key().value();
        return key.equals("sand") || key.equals("red_sand") || key.equals("gravel")
                || key.endsWith("_concrete_powder");
    }

    /** After a block changes at pos, let any unsupported gravity blocks above it fall. */
    public static void scheduleFallCheck(Instance instance, Point pos) {
        instance.scheduler().scheduleNextTick(() -> fallCheck(instance, pos));
    }

    private static void fallCheck(Instance instance, Point pos) {
        // the changed block itself (a just-placed sand over air), then the column above
        for (int dy = 0; dy <= 24; dy++) {
            Point at = pos.add(0, dy, 0);
            Block block = instance.getBlock(at);
            if (dy > 0 && !isGravityBlock(block)) break;
            if (!isGravityBlock(block)) continue;
            Block below = instance.getBlock(at.add(0, -1, 0));
            if (!below.isAir() && !below.isLiquid()) continue;
            startFalling(instance, at, block);
        }
    }

    private static void startFalling(Instance instance, Point pos, Block block) {
        instance.setBlock(pos, Block.AIR);
        Entity entity = new Entity(EntityType.FALLING_BLOCK);
        FallingBlockMeta meta = (FallingBlockMeta) entity.getEntityMeta();
        meta.setBlock(block);
        meta.setSpawnPosition(pos);
        entity.setInstance(instance, pos.add(0.5, 0, 0.5));

        entity.scheduler().buildTask(() -> {
            if (entity.getInstance() == null) return;
            if (entity.isOnGround()) {
                Point land = new Vec(Math.floor(entity.getPosition().x()),
                        Math.round(entity.getPosition().y()),
                        Math.floor(entity.getPosition().z()));
                Block target = instance.getBlock(land);
                if (target.isAir() || target.isLiquid()) {
                    instance.setBlock(land, block);
                } else {
                    dropAt(instance, land, ItemStack.of(block.registry().material() != null
                            ? block.registry().material() : Material.SAND));
                }
                entity.remove();
            } else if (entity.getAliveTicks() > 600) {
                entity.remove();
            }
        }).repeat(TaskSchedule.tick(1)).schedule();
    }
}
