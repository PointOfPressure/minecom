package dev.pointofpressure.minecom.blocks;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cellular fluid simulation: water spreads 7 blocks with falling columns and
 * infinite-source creation; lava spreads 3 blocks and flows slower; water+lava
 * makes obsidian/cobblestone/stone; buckets pick up and place sources.
 * (Vanilla's nearest-hole flow weighting is not modeled — flow spreads evenly.)
 */
public final class Fluids {
    private Fluids() {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int MAX_UPDATES_PER_TICK = 300;

    private static Instance instance;
    private static final Set<Long> pendingWater = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<Long> pendingLava = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static int tickCounter;

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static int unpackX(long k) { return (int) (k >> 38); }
    private static int unpackY(long k) { return (int) (k << 26 >> 52); }
    private static int unpackZ(long k) { return (int) (k << 38 >> 38); }

    public static void start(Instance overworld) {
        instance = overworld;
        MinecraftServer.getSchedulerManager().buildTask(Fluids::tick)
                .repeat(TaskSchedule.tick(5))
                .schedule();
    }

    /** Test hooks: drive the engine without the scheduler. */
    public static void debugAttach(Instance inst) { instance = inst; }
    public static void debugTick() { tick(); }
    public static int debugPendingCount() { return pendingWater.size() + pendingLava.size(); }
    public static void debugEnqueue(int x, int y, int z) { pendingWater.add(pack(x, y, z)); }

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockBreakEvent.class, e -> notifyAround(e.getBlockPosition()));
        events.addListener(PlayerBlockPlaceEvent.class, e -> notifyAround(e.getBlockPosition()));
        events.addListener(PlayerUseItemOnBlockEvent.class, Fluids::bucket);
    }

    /** Queue the position and its neighbors for fluid re-evaluation. */
    public static void notifyAround(Point pos) {
        if (instance == null) return;
        int x = pos.blockX(), y = pos.blockY(), z = pos.blockZ();
        enqueueIfFluid(x, y, z);
        enqueueIfFluid(x + 1, y, z);
        enqueueIfFluid(x - 1, y, z);
        enqueueIfFluid(x, y, z + 1);
        enqueueIfFluid(x, y, z - 1);
        enqueueIfFluid(x, y + 1, z);
        enqueueIfFluid(x, y - 1, z);
    }

    private static void enqueueIfFluid(int x, int y, int z) {
        if (!instance.isChunkLoaded(x >> 4, z >> 4)) return;
        Block b = instance.getBlock(x, y, z);
        if (isWater(b)) pendingWater.add(pack(x, y, z));
        else if (isLava(b)) pendingLava.add(pack(x, y, z));
    }

    private static boolean isWater(Block b) { return b.key().value().equals("water"); }
    private static boolean isLava(Block b) { return b.key().value().equals("lava"); }

    private static int level(Block b) {
        String s = b.getProperty("level");
        return s == null ? 0 : Integer.parseInt(s);
    }

    // ------------------------------------------------------------------ tick

    private static void tick() {
        if (instance == null) return;
        tickCounter++;
        process(pendingWater, true);
        if (tickCounter % 6 == 0) process(pendingLava, false);
    }

    private static void process(Set<Long> pending, boolean water) {
        if (pending.isEmpty()) return;
        List<Long> batch = new ArrayList<>(Math.min(pending.size(), MAX_UPDATES_PER_TICK));
        var it = pending.iterator();
        while (it.hasNext() && batch.size() < MAX_UPDATES_PER_TICK) {
            batch.add(it.next());
            it.remove();
        }
        for (long key : batch) {
            update(unpackX(key), unpackY(key), unpackZ(key), water);
        }
    }

    private static final int NO_FEED = Integer.MAX_VALUE;

    private static void update(int x, int y, int z, boolean water) {
        if (!instance.isChunkLoaded(x >> 4, z >> 4)) return;
        Block b = instance.getBlock(x, y, z);
        if (!isSameFluid(b, water)) return;
        int lvl = level(b);
        int step = water ? 1 : 2;
        int maxLevel = water ? 7 : 6;
        Block below = loadedBlock(x, y - 1, z);
        boolean belowSolid = below != null && below.isSolid();

        // non-source cells must be fed, or they decay away (never wrap into "falling")
        if (lvl != 0) {
            int expected;
            Block above = loadedBlock(x, y + 1, z);
            if (above != null && isSameFluid(above, water)) {
                expected = 8; // falling: fed from directly above
            } else {
                int feed = bestSideFeed(x, y, z, water);
                expected = feed == NO_FEED ? NO_FEED
                        : (feed + step > maxLevel ? NO_FEED : feed + step);
            }
            if (water && expected != NO_FEED && belowSolid && sourceNeighbours(x, y, z) >= 2) {
                expected = 0; // vanilla infinite-water rule
            }
            if (expected == NO_FEED) {
                set(x, y, z, Block.AIR, water);
                return;
            }
            if (expected != lvl) {
                set(x, y, z, expected == 0 ? fluid(water)
                        : fluid(water).withProperty("level", String.valueOf(expected)), water);
                return;
            }
        }

        // flow down
        if (below == null) return;
        if (meltsInto(below, water, x, y - 1, z)) return;
        if (below.isAir() || (isSameFluid(below, water) && level(below) != 0)) {
            set(x, y - 1, z, fluid(water).withProperty("level", "8"), water);
            return;
        }

        // spread sideways over solid ground; falling water spreads at full strength when grounded
        if (!belowSolid) return;
        int strength = lvl >= 8 ? 0 : lvl;
        int spreadLevel = strength + step;
        if (spreadLevel > maxLevel) return;
        for (int[] d : DIRS) {
            int nx = x + d[0], nz = z + d[1];
            Block n = loadedBlock(nx, y, nz);
            if (n == null) continue;
            if (meltsInto(n, water, nx, y, nz)) continue;
            if (n.isAir()) {
                set(nx, y, nz, fluid(water).withProperty("level", String.valueOf(spreadLevel)), water);
            } else if (isSameFluid(n, water)) {
                int nl = level(n);
                if (nl != 0 && nl < 8 && spreadLevel < nl) {
                    set(nx, y, nz, fluid(water).withProperty("level", String.valueOf(spreadLevel)), water);
                }
            }
        }
    }

    private static Block loadedBlock(int x, int y, int z) {
        if (y < -64 || y > 319) return null;
        if (!instance.isChunkLoaded(x >> 4, z >> 4)) return null;
        return instance.getBlock(x, y, z);
    }

    /** Water meeting lava (and vice versa): obsidian for sources, cobble/stone for flows. */
    private static boolean meltsInto(Block target, boolean waterFlowing, int x, int y, int z) {
        if (waterFlowing && isLava(target)) {
            instance.setBlock(x, y, z, level(target) == 0 ? Block.OBSIDIAN : Block.COBBLESTONE);
            return true;
        }
        if (!waterFlowing && isWater(target)) {
            instance.setBlock(x, y, z, Block.STONE);
            return true;
        }
        return false;
    }

    /**
     * Strongest horizontal feeder: a source feeds at 0, flowing feeds at its level,
     * falling (8+) feeds at 0 only when it stands on solid ground (a landed column).
     */
    private static int bestSideFeed(int x, int y, int z, boolean water) {
        int best = NO_FEED;
        for (int[] d : DIRS) {
            Block n = loadedBlock(x + d[0], y, z + d[1]);
            if (n == null || !isSameFluid(n, water)) continue;
            int nl = level(n);
            int effective;
            if (nl >= 8) {
                Block underNeighbor = loadedBlock(x + d[0], y - 1, z + d[1]);
                if (underNeighbor == null || !underNeighbor.isSolid()) continue;
                effective = 0;
            } else {
                effective = nl;
            }
            if (effective < best) best = effective;
        }
        return best;
    }

    private static int sourceNeighbours(int x, int y, int z) {
        int count = 0;
        for (int[] d : DIRS) {
            Block n = instance.getBlock(x + d[0], y, z + d[1]);
            if (isWater(n) && level(n) == 0) count++;
        }
        return count;
    }

    private static boolean isSameFluid(Block b, boolean water) {
        return water ? isWater(b) : isLava(b);
    }

    private static Block fluid(boolean water) {
        return water ? Block.WATER : Block.LAVA;
    }

    private static void set(int x, int y, int z, Block block, boolean waterCtx) {
        instance.setBlock(x, y, z, block);
        notifyAround(new Vec(x, y, z));
        if (!block.isAir()) {
            (isWater(block) ? pendingWater : pendingLava).add(pack(x, y, z));
        }
    }

    // ------------------------------------------------------------------ buckets

    private static final Set<String> CAULDRONS = Set.of("cauldron", "water_cauldron", "lava_cauldron", "powder_snow_cauldron");

    private static void bucket(PlayerUseItemOnBlockEvent e) {
        // AbstractCauldronBlock.useItemOn takes priority over BucketItem's own generic
        // place-a-source-block fallback (real vanilla: the clicked BLOCK's own interaction
        // always runs before an item's fallback use) — Cauldrons.java owns this entirely.
        if (CAULDRONS.contains(e.getInstance().getBlock(e.getPosition()).key().value())) return;

        Material held = e.getItemStack().material();
        Player player = e.getPlayer();
        Point clicked = e.getPosition();

        if (held == Material.WATER_BUCKET || held == Material.LAVA_BUCKET) {
            var face = e.getBlockFace().toDirection();
            Point target = clicked.add(face.normalX(), face.normalY(), face.normalZ());
            Block existing = instance.getBlock(target);
            if (!existing.isAir() && !existing.isLiquid() && !existing.registry().isReplaceable()) return;
            Block placed = (held == Material.WATER_BUCKET ? Block.WATER : Block.LAVA);
            instance.setBlock(target, placed);
            pendingOf(held == Material.WATER_BUCKET).add(pack(target.blockX(), target.blockY(), target.blockZ()));
            if (player.getGameMode() != net.minestom.server.entity.GameMode.CREATIVE) {
                player.setItemInHand(e.getHand(), ItemStack.of(Material.BUCKET));
            }
        } else if (held == Material.BUCKET) {
            // scoop: the clicked block or the block in front of the face
            var face = e.getBlockFace().toDirection();
            Point[] candidates = {clicked, clicked.add(face.normalX(), face.normalY(), face.normalZ())};
            for (Point c : candidates) {
                Block b = instance.getBlock(c);
                if (b.isLiquid() && level(b) == 0) {
                    instance.setBlock(c, Block.AIR);
                    notifyAround(c);
                    if (player.getGameMode() != net.minestom.server.entity.GameMode.CREATIVE) {
                        player.setItemInHand(e.getHand(),
                                ItemStack.of(isWater(b) ? Material.WATER_BUCKET : Material.LAVA_BUCKET));
                    }
                    return;
                }
            }
        }
    }

    private static Set<Long> pendingOf(boolean water) {
        return water ? pendingWater : pendingLava;
    }
}
