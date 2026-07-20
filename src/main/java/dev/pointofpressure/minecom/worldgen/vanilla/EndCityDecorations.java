package dev.pointofpressure.minecom.worldgen.vanilla;

import dev.pointofpressure.minecom.blocks.ItemFrames;
import dev.pointofpressure.minecom.mobs.Mobs;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.metadata.other.ItemFrameMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.utils.Direction;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * End-ship data-marker decorations (EndCityPieces.handleDataMarker): the framed elytra and the
 * shulker guards. The block-only worldgen {@code generate} pass places the ship's blocks (hull,
 * the two treasure chests, brewing stand, etc.) but cannot spawn entities, so — exactly like
 * VChorus/VEndSpikes/EndGateways — these are decorated onto the live End instance once the chunk
 * holding a marker loads. Faithful to the decompiled handler:
 * <ul>
 *   <li><b>Elytra</b> — an item frame at the marker position holding one elytra, facing
 *       {@code rotation.rotate(SOUTH)}. This is the only in-world source of elytra.</li>
 *   <li><b>Sentry</b> — a shulker at {@code (x+0.5, y, z+0.5)} guarding the ship.</li>
 *   <li><b>Chest</b> — vanilla only sets the END_CITY_TREASURE loot table on the chest block one
 *       below the marker; loot-table population is the project-wide established skip (AUDIT.md),
 *       so the (empty) chest block placed by the template is all that lands here.</li>
 * </ul>
 * End decoration entities are session-scoped (the End is not covered by RegionStore's
 * overworld-only decoration persistence), so the guard below is per-server-lifetime: each marker
 * spawns at most once, and a taken elytra / emptied frame is never regenerated.
 */
public final class EndCityDecorations {

    private EndCityDecorations() {}

    // thread: touched only from the End instance's InstanceChunkLoadEvent callback (tick-adjacent).
    // Concurrent set kept for the same discipline as the project's other cross-thread world state.
    private static final Set<String> PLACED = ConcurrentHashMap.newKeySet();

    /** Decorate any end-ship markers landing in the freshly loaded chunk (call once per chunk load). */
    public static void placeChunk(Instance end, int chunkX, int chunkZ, VEndGen gen) {
        if (gen == null) return;
        List<VEndGen.ShipMarker> markers = gen.shipMarkersInChunk(chunkX, chunkZ);
        for (VEndGen.ShipMarker m : markers) {
            String key = System.identityHashCode(end) + ":" + m.x() + "," + m.y() + "," + m.z();
            if (!PLACED.add(key)) continue;
            spawnMarker(end, m);
        }
    }

    private static void spawnMarker(Instance end, VEndGen.ShipMarker m) {
        switch (m.kind()) {
            case "Elytra" -> {
                Direction dir = Direction.valueOf(m.facing().name());
                Entity frame = ItemFrames.spawnAt(end,
                        new Pos(m.x() + 0.5, m.y() + 0.5, m.z() + 0.5), dir, EntityType.ITEM_FRAME);
                frame.editEntityMeta(ItemFrameMeta.class, meta -> meta.setItem(ItemStack.of(Material.ELYTRA)));
            }
            case "Sentry" -> Mobs.spawn("shulker", end, new Pos(m.x() + 0.5, m.y(), m.z() + 0.5));
            default -> { } // "Chest": loot-table skip; the chest block is placed by the template
        }
    }
}
