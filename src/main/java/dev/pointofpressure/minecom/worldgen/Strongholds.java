package dev.pointofpressure.minecom.worldgen;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strongholds: a bounded portal room (the classic 12 end_portal_frame ring around a
 * 3x3 void, inside a stone-brick chamber) built at each of the 128 bit-exact
 * concentric-ring positions (see {@link dev.pointofpressure.minecom.worldgen.vanilla.VConcentricRings})
 * the first time its chunk loads. Filling all 12 frames with an eye of ender lights
 * the portal (END_PORTAL blocks) — {@link dev.pointofpressure.minecom.blocks.EndPortal}
 * already polls for players standing in any end_portal block, so travel works with no
 * further wiring. The full branching corridor/room maze (StrongholdPieces, ~30
 * hard-coded piece classes) is NOT ported — this is the portal room only, matching the
 * project's bounded-structure precedent (nether fortress).
 */
public final class Strongholds {

    private static final int ROOM_Y = 20;
    private static final Set<Long> BUILT = ConcurrentHashMap.newKeySet();

    private Strongholds() {}

    public static void register(Instance overworld) {
        var gen = dev.pointofpressure.minecom.Bootstrap.vanillaGen();
        if (gen == null) return;

        // the ring-position search is expensive (up to 128 x 3249 biome samples) —
        // warm it off the main thread so it's cached well before any player can walk there.
        CompletableFuture.runAsync(() -> gen.strongholds().positions());

        overworld.eventNode().addListener(InstanceChunkLoadEvent.class, event -> {
            int cx = event.getChunkX(), cz = event.getChunkZ();
            for (int[] pos : gen.strongholds().positions()) {
                if (pos[0] != cx || pos[1] != cz) continue;
                if (BUILT.add(chunkKey(cx, cz))) build(overworld, cx, cz);
                break;
            }
        });
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Nearest stronghold chunk position to a world chunk coordinate (for /locatestronghold). */
    public static int[] nearestTo(int chunkX, int chunkZ) {
        var gen = dev.pointofpressure.minecom.Bootstrap.vanillaGen();
        return gen == null ? null : gen.strongholds().nearestTo(chunkX, chunkZ);
    }

    /** Test hook: force-build a portal room at a chunk without needing a real ring position. */
    public static void testBuild(Instance instance, int chunkX, int chunkZ) {
        build(instance, chunkX, chunkZ);
    }

    /** Test hook: the frame plane's Y (ROOM_Y + 1) and room-centre block coords for a chunk. */
    public static int[] testCenter(int chunkX, int chunkZ) {
        return new int[]{(chunkX << 4) + 8, ROOM_Y + 1, (chunkZ << 4) + 8};
    }

    private static void build(Instance instance, int chunkX, int chunkZ) {
        int cx = (chunkX << 4) + 8, cz = (chunkZ << 4) + 8;
        int y = ROOM_Y;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean wall = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                for (int dy = 0; dy <= 4; dy++) {
                    boolean cap = dy == 0 || dy == 4;
                    instance.setBlock(cx + dx, y + dy, cz + dz, (wall || cap) ? Block.STONE_BRICKS : Block.AIR);
                }
            }
        }
        // entrance gap on the north wall
        instance.setBlock(cx, y + 1, cz - 3, Block.AIR);
        instance.setBlock(cx, y + 2, cz - 3, Block.AIR);

        // classic 12-frame ring (all 5x5 perimeter cells except the 4 corners) around a 3x3 void
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                boolean ring = (Math.abs(dx) == 2 || Math.abs(dz) == 2) && !corner;
                if (!ring) continue;
                String facing = dz == -2 ? "south" : dz == 2 ? "north" : dx == -2 ? "east" : "west"; // eye faces inward
                instance.setBlock(cx + dx, y + 1, cz + dz,
                        Block.END_PORTAL_FRAME.withProperties(Map.of("facing", facing, "eye", "false")));
            }
        }
    }

    public static void registerEyeInteraction(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, event -> {
            if (event.getItemStack().material() != Material.ENDER_EYE) return;
            Point pos = event.getPosition();
            Instance instance = event.getInstance();
            Block block = instance.getBlock(pos);
            if (!block.compare(Block.END_PORTAL_FRAME) || "true".equals(block.getProperty("eye"))) return;

            instance.setBlock(pos, block.withProperty("eye", "true"));
            Player player = event.getPlayer();
            player.setItemInMainHand(player.getItemInMainHand().consume(1));
            player.sendMessage(net.kyori.adventure.text.Component.text("The eye stares back...",
                    net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));

            maybeActivate(instance, pos.blockX(), pos.blockY(), pos.blockZ());
        });
    }

    /** All 12 frames lit -> fill the 3x3 centre with end_portal blocks. */
    private static void maybeActivate(Instance instance, int fx, int fy, int fz) {
        // search a small window for the room centre this frame belongs to (dx,dz in [-2,2])
        for (int cx = fx - 2; cx <= fx + 2; cx++) {
            for (int cz = fz - 2; cz <= fz + 2; cz++) {
                int dx = fx - cx, dz = fz - cz;
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                if (!((Math.abs(dx) == 2 || Math.abs(dz) == 2) && !corner)) continue;
                if (isFullyLit(instance, cx, fy, cz)) {
                    for (int px = cx - 1; px <= cx + 1; px++) {
                        for (int pz = cz - 1; pz <= cz + 1; pz++) {
                            instance.setBlock(px, fy, pz, Block.END_PORTAL);
                        }
                    }
                    return;
                }
            }
        }
    }

    private static boolean isFullyLit(Instance instance, int cx, int y, int cz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                if (!((Math.abs(dx) == 2 || Math.abs(dz) == 2) && !corner)) continue;
                Block b = instance.getBlock(cx + dx, y, cz + dz);
                if (!b.compare(Block.END_PORTAL_FRAME) || !"true".equals(b.getProperty("eye"))) return false;
            }
        }
        return true;
    }
}
