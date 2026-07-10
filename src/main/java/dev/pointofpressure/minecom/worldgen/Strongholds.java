package dev.pointofpressure.minecom.worldgen;

import dev.pointofpressure.minecom.worldgen.vanilla.VStrongholdGen;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strongholds: the full real branching corridor/room maze ({@link VStrongholdGen} — a port of
 * StrongholdPieces' ~11 hand-coded piece classes), assembled once per stronghold (cached, keyed
 * by anchor chunk) and rendered chunk-clipped as each intersecting chunk loads — the same
 * cache-full-assembly-then-clip-per-chunk pattern already established for end_city/bastion.
 * Eye-of-ender interaction reads the real portal room piece's 12 frame positions directly
 * (see {@link VStrongholdGen#portalFramePositions}), so it works regardless of which piece
 * orientation/geometry the real RNG produced.
 */
public final class Strongholds {

    /** Real MAX_DEPTH=50 pieces / up to 112-block radius from the anchor keeps every piece within ~8 chunks. */
    private static final int SEARCH_RADIUS_CHUNKS = 8;

    private static final Map<Long, List<VStrongholdGen.Piece>> ASSEMBLY_CACHE = new ConcurrentHashMap<>();
    private static final Set<Long> RENDERED = ConcurrentHashMap.newKeySet();

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
                int acx = pos[0], acz = pos[1];
                if (Math.abs(cx - acx) > SEARCH_RADIUS_CHUNKS || Math.abs(cz - acz) > SEARCH_RADIUS_CHUNKS) continue;
                long renderKey = renderKey(acx, acz, cx, cz);
                if (!RENDERED.add(renderKey)) continue;
                long anchorKey = chunkKey(acx, acz);
                List<VStrongholdGen.Piece> pieces = ASSEMBLY_CACHE.computeIfAbsent(anchorKey,
                        k -> VStrongholdGen.assemble(gen.seed(), acx, acz));
                VStrongholdGen.render(pieces, gen.seed(), sink(overworld), cx, cz);
            }
        });
    }

    private static VStrongholdGen.Sink sink(Instance instance) {
        return new VStrongholdGen.Sink() {
            public Block get(int x, int y, int z) { return instance.getBlock(x, y, z); }
            public void set(int x, int y, int z, Block b) { instance.setBlock(x, y, z, b); }
        };
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static long renderKey(int acx, int acz, int cx, int cz) {
        return chunkKey(acx, acz) * 1_000_003L + chunkKey(cx, cz);
    }

    /** Nearest stronghold chunk position to a world chunk coordinate (for /locatestronghold). */
    public static int[] nearestTo(int chunkX, int chunkZ) {
        var gen = dev.pointofpressure.minecom.Bootstrap.vanillaGen();
        return gen == null ? null : gen.strongholds().nearestTo(chunkX, chunkZ);
    }

    /** Test hook: assemble (without caching) the stronghold at a given anchor chunk. */
    public static List<VStrongholdGen.Piece> testAssemble(long seed, int chunkX, int chunkZ) {
        return VStrongholdGen.assemble(seed, chunkX, chunkZ);
    }

    /**
     * Test hook: seed the same assembly cache {@link #registerEyeInteraction}'s handler scans,
     * so a test-assembled tree (bypassing the real ring-position search) still exercises the
     * real production eye-of-ender click handler end-to-end.
     */
    public static void testSeedCache(int anchorChunkX, int anchorChunkZ, List<VStrongholdGen.Piece> pieces) {
        ASSEMBLY_CACHE.put(chunkKey(anchorChunkX, anchorChunkZ), pieces);
    }

    /** Test hook: render an already-assembled piece list into an arbitrary sink for one chunk. */
    public static void testRender(List<VStrongholdGen.Piece> pieces, long seed, VStrongholdGen.Sink sink, int chunkX, int chunkZ) {
        VStrongholdGen.render(pieces, seed, sink, chunkX, chunkZ);
    }

    public static void registerEyeInteraction(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, event -> {
            if (event.getItemStack().material() != Material.ENDER_EYE) return;
            Point pos = event.getPosition();
            Instance instance = event.getInstance();
            Block block = instance.getBlock(pos);
            if (!block.compare(Block.END_PORTAL_FRAME) || "true".equals(block.getProperty("eye"))) return;

            VStrongholdGen.Piece portalRoom = findPortalRoomAt(pos.blockX(), pos.blockY(), pos.blockZ());
            if (portalRoom == null) return;

            instance.setBlock(pos, block.withProperty("eye", "true"));
            Player player = event.getPlayer();
            player.setItemInMainHand(player.getItemInMainHand().consume(1));
            player.sendMessage(net.kyori.adventure.text.Component.text("The eye stares back...",
                    net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));

            maybeActivate(instance, portalRoom);
        });
    }

    /** Finds the cached PORTAL_ROOM piece whose 12 frame positions include the given world position. */
    private static VStrongholdGen.Piece findPortalRoomAt(int x, int y, int z) {
        for (List<VStrongholdGen.Piece> pieces : ASSEMBLY_CACHE.values()) {
            for (VStrongholdGen.Piece p : pieces) {
                if (p.kind != VStrongholdGen.Kind.PORTAL_ROOM) continue;
                for (int[] fp : VStrongholdGen.portalFramePositions(p)) {
                    if (fp[0] == x && fp[1] == y && fp[2] == z) return p;
                }
            }
        }
        return null;
    }

    /** All 12 frames lit -> fill the 3x3 centre with end_portal blocks. */
    private static void maybeActivate(Instance instance, VStrongholdGen.Piece portalRoom) {
        for (int[] fp : VStrongholdGen.portalFramePositions(portalRoom)) {
            Block b = instance.getBlock(fp[0], fp[1], fp[2]);
            if (!b.compare(Block.END_PORTAL_FRAME) || !"true".equals(b.getProperty("eye"))) return;
        }
        for (int[] core : VStrongholdGen.portalCorePositions(portalRoom)) {
            instance.setBlock(core[0], core[1], core[2], Block.END_PORTAL);
        }
    }
}
