package dev.pointofpressure.minecom.worldgen.vanilla;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The end gateway created on the main island the first time the dragon dies (the
 * vanilla "free" route to the outer End islands, no need to build one by hand).
 * Faithful port of the two decompiled pieces: EnderDragonFight.spawnNewGateway
 * (which of 20 ring slots, radius 96 / y75 — same shuffle-and-angle shape as the
 * ten obsidian spikes) and TheEndGatewayBlockEntity.getPortalPosition (walk out
 * along the direction vector to ~1024 blocks, find solid ground near there, land
 * on the tallest non-bedrock block). The exact vanilla algorithm walks real chunk
 * load state to detect "empty"; here the terrain is fully deterministic so the
 * search instead queries the density function directly for solidity — same
 * outcome, no bootstrapped-chunk bookkeeping needed. Player-buildable gateways and
 * the natural ones scattered on outer islands are not ported (this is the single
 * bounded exit gateway, matching the project's other bounded End features).
 */
public final class EndGateways {

    private static final int RING_DISTANCE = 96;
    private static final int RING_Y = 75;
    private static final Map<Instance, int[]> EXIT = new ConcurrentHashMap<>();
    private static final Map<Instance, int[]> DEST = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> STANDING = new ConcurrentHashMap<>();

    private EndGateways() {}

    /** EnderDragonFight.spawnNewGateway: the first popped slot of a shuffled [0,20) ring. */
    public static int[] ringPosition(long worldSeed) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 20; i++) slots.add(i);
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(worldSeed);
        for (int i = slots.size(); i > 1; i--) Collections.swap(slots, i - 1, random.nextInt(i));
        int gateway = slots.get(slots.size() - 1); // gateways.remove(size - 1) pops the LAST shuffled entry
        int x = (int) Math.floor(RING_DISTANCE * Math.cos(2.0 * (-Math.PI + (Math.PI / 20) * gateway)));
        int z = (int) Math.floor(RING_DISTANCE * Math.sin(2.0 * (-Math.PI + (Math.PI / 20) * gateway)));
        return new int[]{x, RING_Y, z};
    }

    /** Build the gateway on the main island (call once, on dragon death). */
    public static void spawn(Instance end, long worldSeed, VEndGen gen) {
        int[] ring = ringPosition(worldSeed);
        int x = ring[0], z = ring[2];
        int y = findLanding(end, x, z);
        buildPlatform(end, x, y, z);
        EXIT.put(end, new int[]{x, y + 1, z});
        DEST.put(end, findDestination(end, gen, x, z));
    }

    private static void buildPlatform(Instance end, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                end.setBlock(x + dx, y, z + dz, Block.BEDROCK);
                for (int dy = 1; dy <= 4; dy++) end.setBlock(x + dx, y + dy, z + dz, Block.AIR);
            }
        }
        end.setBlock(x, y + 1, z, Block.END_GATEWAY);
    }

    /** Highest non-air block at (x,z) — builds a small synthetic island if the ring position is void. */
    private static int findLanding(Instance end, int x, int z) {
        for (int y = 100; y >= 20; y--) {
            if (!end.getBlock(x, y, z).isAir()) return y;
        }
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) end.setBlock(x + dx, 74, z + dz, Block.END_STONE);
        return 74;
    }

    /**
     * TheEndGatewayBlockEntity.findExitPortalXZPosTentative + findTallestBlock: walk
     * outward along the gateway's direction to distance 1024, snap onto the nearest
     * island there, land on its tallest column.
     */
    private static int[] findDestination(Instance end, VEndGen gen, int gatewayX, int gatewayZ) {
        double len = Math.sqrt((double) gatewayX * gatewayX + (double) gatewayZ * gatewayZ);
        double dx = len < 1e-6 ? 1 : gatewayX / len, dz = len < 1e-6 ? 0 : gatewayZ / len;
        double tx = dx * 1024, tz = dz * 1024;

        // walk backward while the column is empty (up to 16 steps), then forward while still empty
        for (int i = 0; i < 16 && columnEmpty(gen, (int) tx, (int) tz); i++) { tx -= dx * 16; tz -= dz * 16; }
        for (int i = 0; i < 16 && columnEmpty(gen, (int) tx, (int) tz); i++) { tx += dx * 16; tz += dz * 16; }

        int bx = (int) tx, bz = (int) tz;
        int[] tallest = null;
        for (int ox = -16; ox <= 16; ox++) {
            for (int oz = -16; oz <= 16; oz++) {
                for (int y = 120; y > (tallest == null ? 0 : tallest[1]); y--) {
                    if (gen.testSolid(bx + ox, y, bz + oz)) { tallest = new int[]{bx + ox, y, bz + oz}; break; }
                }
            }
        }
        return tallest != null ? new int[]{tallest[0], tallest[1] + 1, tallest[2]} : new int[]{bx, 75, bz};
    }

    private static boolean columnEmpty(VEndGen gen, int x, int z) {
        for (int y = 0; y < 128; y += 4) if (gen.testSolid(x, y, z)) return false;
        return true;
    }

    /** Poll players standing in the end_gateway block and teleport them to the found destination. */
    public static void register(Instance end) {
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            int[] exit = EXIT.get(end);
            int[] dest = DEST.get(end);
            if (exit == null || dest == null) return;
            for (Player player : end.getPlayers()) {
                var p = player.getPosition();
                boolean inGateway = p.blockX() == exit[0] && p.blockY() == exit[1] && p.blockZ() == exit[2];
                if (!inGateway) { STANDING.remove(player.getUuid()); continue; }
                int ticks = STANDING.merge(player.getUuid(), 5, Integer::sum);
                if (ticks >= 15) {
                    STANDING.remove(player.getUuid());
                    player.teleport(new Pos(dest[0] + 0.5, dest[1], dest[2] + 0.5));
                }
            }
        }).repeat(TaskSchedule.tick(5)).schedule();
    }

    public static int[] exitOf(Instance end) { return EXIT.get(end); }
}
