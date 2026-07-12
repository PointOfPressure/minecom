package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.StateAdapter;
import dev.pointofpressure.minecom.data.Items;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Farming: hoes till farmland, seeds plant crops, crops grow over time (tracked
 * positions, persisted), bone meal accelerates, saplings grow into trees.
 */
public final class Farming {
    private Farming() {}

    private static final Random RANDOM = new Random();
    private static Instance instance;

    /** Planted crop positions as "x,y,z"; exposed for persistence. */
    public static final Set<String> CROPS = ConcurrentHashMap.newKeySet();

    private static final Map<Material, Block> SEEDS = Map.of(
            Material.WHEAT_SEEDS, Block.WHEAT,
            Material.BEETROOT_SEEDS, Block.BEETROOTS,
            Material.CARROT, Block.CARROTS,
            Material.POTATO, Block.POTATOES);

    public static void start(Instance overworld) {
        instance = overworld;
        MinecraftServer.getSchedulerManager().buildTask(Farming::growthTick)
                .repeat(TaskSchedule.tick(100))
                .schedule();
    }

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, Farming::useOnBlock);
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            String key = e.getBlock().key().value();
            if (key.endsWith("_sapling")) {
                schedulSaplingGrowth(e.getInstance(), e.getBlockPosition(), 1200 + RANDOM.nextInt(1200));
            }
        });
        Persist.register(persistence());
    }

    /** Crop-position persistence (docs/PERSISTENCE.md); ages live in block states. */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "crop";
            }

            @Override
            public void collect(Instance in, BiConsumer<Point, JsonObject> out) {
                for (String key : CROPS) out.accept(Persist.parsePos(key), new JsonObject());
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                CROPS.add(key(pos));
            }

            @Override
            public void wipe() {
                CROPS.clear();
            }
        };
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        Material held = e.getItemStack().material();
        Player player = e.getPlayer();
        Point pos = e.getPosition();
        Block clicked = e.getInstance().getBlock(pos);
        String clickedKey = clicked.key().value();

        if (held.key().value().endsWith("_hoe")) {
            if ((clickedKey.equals("grass_block") || clickedKey.equals("dirt") || clickedKey.equals("dirt_path"))
                    && e.getInstance().getBlock(pos.add(0, 1, 0)).isAir()) {
                e.getInstance().setBlock(pos, Block.FARMLAND);
                player.setItemInHand(e.getHand(), Items.damageItem(player, e.getItemStack(), 1));
            }
            return;
        }

        Block crop = SEEDS.get(held);
        if (crop != null && clickedKey.equals("farmland")) {
            Point above = pos.add(0, 1, 0);
            if (!e.getInstance().getBlock(above).isAir()) return;
            e.getInstance().setBlock(above, crop.withProperty("age", "0"));
            CROPS.add(key(above));
            consume(player, e);
            return;
        }

        if (held == Material.BONE_MEAL) {
            if (boneMeal(e.getInstance(), pos)) consume(player, e);
        }
    }

    /**
     * Bone-meal a position: crops jump 2-3 ages, saplings grow into trees, grass blocks
     * scatter a burst of short_grass nearby (boneMealGrass).
     * Shared by player use above and the dispenser BONE_MEAL behavior
     * (DispenseItemBehavior.bootStrap -> BoneMealItem.growCrop).
     */
    public static boolean boneMeal(net.minestom.server.instance.Instance instance, Point pos) {
        Block target = instance.getBlock(pos);
        String age = target.getProperty("age");
        if (age != null && CROPS.contains(key(pos))) {
            int max = maxAge(target);
            int newAge = Math.min(max, Integer.parseInt(age) + 2 + RANDOM.nextInt(2));
            instance.setBlock(pos, target.withProperty("age", String.valueOf(newAge)));
            return true;
        }
        if (target.key().value().endsWith("_sapling")) {
            growTree(instance, pos, target);
            return true;
        }
        if (target.key().value().equals("grass_block")) {
            return boneMealGrass(instance, pos);
        }
        return false;
    }

    /**
     * GrassBlock.performBonemeal (decompile-verified): 128 attempts, quantized into 8 groups of
     * 16 with a progressively longer random walk (0 steps for attempts 0-15, 1 step for 16-31,
     * ..., 7 steps for 112-127), each step re-checking the walker is still directly above
     * grass_block and hasn't wandered into a solid block. A landing on air scatters a
     * short_grass there ~7/8 of the time — the real GRASS_BONEMEAL placed feature (bundled data,
     * not approximated: {@code minecraft:grass_bonemeal} -> {@code minecraft:grass} -> a plain
     * simple_block short_grass placement, verified against placed_features.json/
     * configured_features.json). Two secondary branches are simplified out, documented in
     * AUDIT.md: re-rolling an existing short_grass into tall_grass, and the other 1/8
     * "biome-specific decoration feature" branch (flowers/mushrooms/saplings, even whole trees
     * for some biomes) — the real per-biome feature list is bundled (biome_features.json's
     * VEGETAL_DECORATION step), but placing an arbitrarily complex feature needs this project's
     * worldgen-time Canvas system, which isn't bridged to live gameplay.
     */
    private static boolean boneMealGrass(Instance instance, Point pos) {
        Point above = pos.add(0, 1, 0);
        if (!instance.getBlock(above).isAir()) return false; // isValidBonemealTarget

        attempts:
        for (int j = 0; j < 128; j++) {
            int steps = j / 16;
            Point testPos = above;
            for (int i = 0; i < steps; i++) {
                testPos = testPos.add(RANDOM.nextInt(3) - 1,
                        (RANDOM.nextInt(3) - 1) * (RANDOM.nextInt(3) / 2), RANDOM.nextInt(3) - 1);
                Block below = instance.getBlock(testPos.add(0, -1, 0));
                if (!below.key().value().equals("grass_block") || instance.getBlock(testPos).isSolid()) {
                    continue attempts;
                }
            }
            if (instance.getBlock(testPos).isAir() && RANDOM.nextInt(8) != 0) {
                instance.setBlock(testPos, Block.SHORT_GRASS);
            }
            // else air + 1/8 roll: biome-specific decoration feature branch, simplified out
        }
        return true;
    }

    private static void consume(Player player, PlayerUseItemOnBlockEvent e) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setItemInHand(e.getHand(), e.getItemStack().consume(1));
        }
    }

    private static String key(Point p) {
        return p.blockX() + "," + p.blockY() + "," + p.blockZ();
    }

    private static int maxAge(Block crop) {
        return crop.key().value().equals("beetroots") ? 3 : 7;
    }

    private static void growthTick() {
        if (instance == null) return;
        for (String key : CROPS) {
            String[] parts = key.split(",");
            int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]);
            if (!instance.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block block = instance.getBlock(x, y, z);
            String age = block.getProperty("age");
            if (age == null) {
                CROPS.remove(key);
                continue;
            }
            int current = Integer.parseInt(age);
            int max = maxAge(block);
            if (current < max && RANDOM.nextDouble() < 0.20) {
                instance.setBlock(x, y, z, block.withProperty("age", String.valueOf(current + 1)));
            }
        }
    }

    // ------------------------------------------------------------------ saplings

    private static void schedulSaplingGrowth(Instance inst, Point pos, int delayTicks) {
        inst.scheduler().buildTask(() -> {
            Block block = inst.getBlock(pos);
            if (block.key().value().endsWith("_sapling")) {
                growTree(inst, pos, block);
            }
        }).delay(TaskSchedule.tick(delayTicks)).schedule();
    }

    private static void growTree(Instance inst, Point pos, Block sapling) {
        String kind = sapling.key().value().replace("_sapling", "");
        Block log = switch (kind) {
            case "birch" -> Block.BIRCH_LOG;
            case "spruce" -> Block.SPRUCE_LOG;
            default -> Block.OAK_LOG;
        };
        Block leaves = switch (kind) {
            case "birch" -> Block.BIRCH_LEAVES;
            case "spruce" -> Block.SPRUCE_LEAVES;
            default -> Block.OAK_LEAVES;
        };
        boolean spruce = kind.equals("spruce");
        int x = pos.blockX(), y = pos.blockY(), z = pos.blockZ();
        int trunk = spruce ? 6 + RANDOM.nextInt(3) : 4 + RANDOM.nextInt(3);

        for (int dy = 0; dy < trunk; dy++) setIfSoft(inst, x, y + dy, z, log);
        int top = y + trunk;
        if (spruce) {
            for (int layer = 0; layer < trunk - 2; layer++) {
                int radius = (layer % 2 == 0) ? 1 + layer / 3 : 0;
                for (int lx = -radius; lx <= radius; lx++) {
                    for (int lz = -radius; lz <= radius; lz++) {
                        if (lx == 0 && lz == 0) continue;
                        setIfSoft(inst, x + lx, top - layer, z + lz, leaves);
                    }
                }
            }
        } else {
            for (int lx = -2; lx <= 2; lx++) {
                for (int lz = -2; lz <= 2; lz++) {
                    for (int ly = -2; ly <= 0; ly++) {
                        if (lx == 0 && lz == 0 && ly < 0) continue;
                        if (Math.abs(lx) == 2 && Math.abs(lz) == 2) continue;
                        if (ly == 0 && (Math.abs(lx) > 1 || Math.abs(lz) > 1)) continue;
                        setIfSoft(inst, x + lx, top + ly, z + lz, leaves);
                    }
                }
            }
        }
        setIfSoft(inst, x, top + 1, z, leaves);
        inst.setBlock(new Vec(x, y, z), log); // trunk base replaces the sapling
    }

    private static void setIfSoft(Instance inst, int x, int y, int z, Block block) {
        Block existing = inst.getBlock(x, y, z);
        if (existing.isAir() || existing.registry().isReplaceable()
                || existing.key().value().endsWith("_sapling")) {
            inst.setBlock(x, y, z, block);
        }
    }
}
