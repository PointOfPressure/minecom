package dev.pointofpressure.minecom.survival;

import dev.pointofpressure.minecom.data.MapColors;
import dev.pointofpressure.minecom.data.VanillaData;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.MapDataPacket;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps, ported from decompiled EmptyMapItem/MapItem/MapItemSavedData (26.2). An empty map
 * ({@code minecraft:map}) used in the air consumes itself and becomes a filled map centred on
 * the user's block position at scale 0, tracking position (EmptyMapItem.use): identity
 * (id/scale/centre/tracking flags) rides on the item itself via tags rather than a separate
 * saved-data store keyed by a persisted counter (this project has no world-level saved-data
 * file format — see the class-level "not persisted" note below), so
 * {@link #idOf(int, int, int)} derives a stable id from (centerX, centerZ, scale) instead of
 * vanilla's insertion-order counter. Held-map recompute (MapItem.update, simplified — see
 * below) walks a {@code 128/scale}-block radius around the holder each pass: per pixel, the
 * first non-{@code MapColors.NONE} block found scanning down from the world ceiling gives the
 * base color (block_map_colors.json, scripts/extract_map_colors.py), and brightness is HIGH/
 * NORMAL/LOW by the exact vanilla formulas — water depth for a WATER pixel, height delta from
 * the previous column otherwise, both diced by {@code (imgX+imgY)&1} the same way real vanilla
 * does. The one decoration modeled is the holder's own player marker (real heading, via
 * {@code calculateRotation}'s non-Nether branch) — see the class doc's Simplifications.
 *
 * Simplifications (AUDIT): no fog-of-war (a fresh map still only reveals the held-scan radius,
 * but a full pass runs every {@link #RECOMPUTE_INTERVAL_TICKS} ticks rather than vanilla's
 * budgeted 1/16-columns-per-tick sweep — a perf/bandwidth optimization with no behavioral
 * difference to the eventual color, so dropped); at scale>0 each pixel samples one corner
 * block instead of averaging its full scale x scale footprint (exact at scale 0, the common
 * case); no banners/item-frame decorations, no other players' markers, no biome-preview
 * (explorer) maps, no Nether's spinning-icon branch (this project's dimension handling is
 * overworld-focused elsewhere too); map data itself (the color buffer) is SESSION-SCOPED, not
 * persisted — a restart blanks it and the next hold-tick rebuilds it live from the world,
 * exactly like this project's other "too cheap to regenerate to bother persisting" gaps (see
 * docs/PERSISTENCE.md); zoom crafting is handled by {@link #tryZoom} (recipes.json's
 * {@code map_extending}, a fixed 3x3 pattern CraftingRecipe.matches can't express generically —
 * ported as its own check, matching the pattern already used for other
 * {@code crafting_special_*} recipes' absence in this codebase).
 */
public final class Maps {
    private Maps() {}

    private static final int SIZE = 128;
    private static final int MAX_SCALE = 4;
    private static final int RECOMPUTE_INTERVAL_TICKS = 20;

    public static final Tag<Integer> MAP_ID = Tag.Integer("minecom:map_id");
    public static final Tag<Byte> SCALE = Tag.Byte("minecom:map_scale");
    public static final Tag<Integer> CENTER_X = Tag.Integer("minecom:map_center_x");
    public static final Tag<Integer> CENTER_Z = Tag.Integer("minecom:map_center_z");
    private static final Tag<Boolean> TRACK_POS = Tag.Boolean("minecom:map_track_pos");
    private static final Tag<Boolean> UNLIMITED = Tag.Boolean("minecom:map_unlimited");
    private static final Tag<Boolean> LOCKED = Tag.Boolean("minecom:map_locked");

    private static final class MapData {
        final int id;
        final byte scale;
        final int centerX, centerZ;
        final boolean trackPosition, unlimitedTracking;
        boolean locked;
        final byte[] colors = new byte[SIZE * SIZE];

        MapData(int id, byte scale, int centerX, int centerZ, boolean trackPosition, boolean unlimitedTracking) {
            this.id = id;
            this.scale = scale;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.trackPosition = trackPosition;
            this.unlimitedTracking = unlimitedTracking;
        }
    }

    private static final Map<Integer, MapData> MAPS = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events, Instance overworld) {
        events.addListener(PlayerUseItemEvent.class, Maps::useEmptyMap);
        MinecraftServer.getSchedulerManager().buildTask(() -> tickHeldMaps(overworld))
                .repeat(TaskSchedule.tick(RECOMPUTE_INTERVAL_TICKS)).schedule();
    }

    /** EmptyMapItem.use: consume 1 empty map, hand back a filled map centred on the player. */
    private static void useEmptyMap(PlayerUseItemEvent e) {
        if (e.getItemStack().material() != Material.MAP) return;
        Player player = e.getPlayer();
        ItemStack filled = create(player.getPosition().blockX(), player.getPosition().blockZ(),
                (byte) 0, true, false);
        ItemStack remaining = e.getItemStack().consume(1);
        if (remaining.isAir()) {
            player.setItemInHand(e.getHand(), filled);
        } else {
            player.setItemInHand(e.getHand(), remaining);
            if (!player.getInventory().addItemStack(filled)) Survival.dropItem(player, filled, false);
        }
        player.getInstance().playSound(Sound.sound(SoundEvent.UI_CARTOGRAPHY_TABLE_TAKE_RESULT,
                        Sound.Source.PLAYER, 1f, 1f),
                player.getPosition().x(), player.getPosition().y(), player.getPosition().z());
    }

    /** MapItemSavedData.createFresh: snap the requested origin to this scale's map-area grid. */
    public static ItemStack create(int originX, int originZ, byte scale, boolean trackPosition, boolean unlimitedTracking) {
        int size = SIZE * (1 << scale);
        int areaX = Math.floorDiv(originX + 64, size);
        int areaZ = Math.floorDiv(originZ + 64, size);
        int centerX = areaX * size + size / 2 - 64;
        int centerZ = areaZ * size + size / 2 - 64;
        int id = idOf(centerX, centerZ, scale);
        MAPS.computeIfAbsent(id, k -> new MapData(id, scale, centerX, centerZ, trackPosition, unlimitedTracking));
        return ItemStack.of(Material.FILLED_MAP)
                .withTag(MAP_ID, id).withTag(SCALE, scale)
                .withTag(CENTER_X, centerX).withTag(CENTER_Z, centerZ)
                .withTag(TRACK_POS, trackPosition).withTag(UNLIMITED, unlimitedTracking);
    }

    /** A stable id derived from identity rather than an insertion-order counter (class doc). */
    private static int idOf(int centerX, int centerZ, byte scale) {
        return Objects.hash(centerX, centerZ, (int) scale);
    }

    /** Rehydrate (or find) this item's server-side MapData from its own tags. */
    private static MapData dataOf(ItemStack stack) {
        Integer id = stack.getTag(MAP_ID);
        if (id == null) return null;
        return MAPS.computeIfAbsent(id, k -> {
            Byte scale = stack.getTag(SCALE);
            Integer cx = stack.getTag(CENTER_X), cz = stack.getTag(CENTER_Z);
            if (scale == null || cx == null || cz == null) return null;
            MapData d = new MapData(id, scale, cx, cz,
                    Boolean.TRUE.equals(stack.getTag(TRACK_POS)), Boolean.TRUE.equals(stack.getTag(UNLIMITED)));
            d.locked = Boolean.TRUE.equals(stack.getTag(LOCKED));
            return d;
        });
    }

    public static boolean isFilledMap(ItemStack stack) {
        return stack.material() == Material.FILLED_MAP && stack.hasTag(MAP_ID);
    }

    /** MapExtendingRecipe: filled map + 8 paper -> a filled map at scale+1 (capped at 4),
     * fresh center/colors like a brand-new map at the wider scale (MapItemSavedData.scaled). */
    public static ItemStack tryZoom(ItemStack mapStack) {
        MapData d = dataOf(mapStack);
        if (d == null || d.scale >= MAX_SCALE) return null;
        return create(d.centerX, d.centerZ, (byte) (d.scale + 1), d.trackPosition, d.unlimitedTracking);
    }

    /** CartographyTableMenu's paper branch: zoom out one scale level (capped at {@link #MAX_SCALE}),
     * scale must be strictly below the cap and the map must not be locked. Null if ineligible. */
    public static ItemStack tryCartographyZoom(ItemStack mapStack) {
        MapData d = dataOf(mapStack);
        if (d == null || d.locked || d.scale >= MAX_SCALE) return null;
        return create(d.centerX, d.centerZ, (byte) (d.scale + 1), d.trackPosition, d.unlimitedTracking);
    }

    /**
     * CartographyTableMenu's glass-pane branch: PREVIEW only — a copy of the item carrying the
     * {@code LOCKED} tag, null if already locked (real vanilla's {@code !mapData.locked} guard).
     * Deliberately does NOT flip {@code d.locked} itself: real vanilla's own lock doesn't fire
     * at menu-preview time either, only once the result is actually taken
     * (MapItem.onCraftedPostProcess, deferred via the {@code MAP_POST_PROCESSING} component) —
     * {@link #commitLock} is that commit step, called once from the take handler. Real vanilla
     * additionally mints the locked copy a brand-new saved-data id (so a locked map is an
     * independent snapshot, decoupled from the source), which this project's identity scheme
     * can't express — map ids here are derived from (centerX, centerZ, scale)
     * (see {@link #idOf}), not an insertion-order counter, so there's no separate "snapshot"
     * slot to hand the lock its own storage. Accepted as a documented gap (AUDIT), not silently
     * faked: locking here freezes the SAME shared id in place (no more live recompute —
     * {@link #tickHeldMaps} already skips locked maps), so any other still-held copy of the
     * exact same id/scale/center locks too, where real vanilla would leave it live.
     */
    public static ItemStack tryLock(ItemStack mapStack) {
        MapData d = dataOf(mapStack);
        if (d == null || d.locked) return null;
        return mapStack.withAmount(1).withTag(LOCKED, true);
    }

    /** Commits a pending lock preview (see {@link #tryLock}'s doc for the real-vanilla timing
     * this mirrors) — call exactly once, when the previewed locked copy is actually taken. */
    public static void commitLock(ItemStack lockedStack) {
        MapData d = dataOf(lockedStack);
        if (d != null) d.locked = true;
    }

    /** CartographyTableMenu's map branch: a plain clone sharing the same id/scale/center — real
     * vanilla's {@code MapItemSavedData} is a single shared saved-data object, so a "copy" is
     * just another item referencing the same id, not a new map. */
    public static ItemStack tryClone(ItemStack mapStack) {
        MapData d = dataOf(mapStack);
        if (d == null) return null;
        return mapStack.withAmount(2);
    }

    /** Whether this specific item's LOCKED tag matches its shared MapData (rehydrates from the
     * tag on a fresh reference, same as {@link #dataOf}) — CartographyTable playtest hook. */
    public static boolean isLocked(ItemStack mapStack) {
        MapData d = dataOf(mapStack);
        return d != null && d.locked;
    }

    // ------------------------------------------------------------------ recompute

    /** Test hook: force one recompute+send cycle for a specific held map without waiting
     * RECOMPUTE_INTERVAL_TICKS of real server time. */
    public static void recomputeForTest(ItemStack heldMap, Player player) {
        MapData d = dataOf(heldMap);
        if (d == null) return;
        recompute(d, player);
        sendPacket(d, player);
    }

    /** Test hook: the raw packed color byte at a map pixel, for asserting the sampled color. */
    public static byte colorAt(ItemStack heldMap, int x, int z) {
        MapData d = dataOf(heldMap);
        return d == null ? 0 : d.colors[x + z * SIZE];
    }

    private static void tickHeldMaps(Instance instance) {
        for (Player player : instance.getPlayers()) {
            for (PlayerHand hand : PlayerHand.values()) {
                ItemStack held = player.getItemInHand(hand);
                if (!isFilledMap(held)) continue;
                MapData d = dataOf(held);
                if (d == null || d.locked) continue;
                recompute(d, player);
                sendPacket(d, player);
            }
        }
    }

    /** MapItem.update, simplified per the class doc: a full radius pass every interval tick
     * rather than a budgeted 1/16-columns-per-tick sweep. */
    private static void recompute(MapData d, Player player) {
        int scale = 1 << d.scale;
        Instance instance = player.getInstance();
        int playerImgX = Math.floorDiv(player.getPosition().blockX() - d.centerX, scale) + 64;
        int playerImgZ = Math.floorDiv(player.getPosition().blockZ() - d.centerZ, scale) + 64;
        int radius = SIZE / scale;
        for (int imgX = Math.max(0, playerImgX - radius); imgX < Math.min(SIZE, playerImgX + radius); imgX++) {
            double previousHeight = 0;
            for (int imgZ = Math.max(0, playerImgZ - radius); imgZ < Math.min(SIZE, playerImgZ + radius); imgZ++) {
                int distSq = sq(imgX - playerImgX) + sq(imgZ - playerImgZ);
                if (distSq > radius * radius) continue;
                int wx = (d.centerX / scale + imgX - 64) * scale;
                int wz = (d.centerZ / scale + imgZ - 64) * scale;
                if (!instance.isChunkLoaded(wx >> 4, wz >> 4)) continue;
                Sample s = sampleColumn(instance, wx, wz);
                MapColors.Brightness brightness;
                if (s.color == MapColors.WATER) {
                    double diff = s.waterDepth * 0.1 + ((imgX + imgZ) & 1) * 0.2;
                    brightness = diff < 0.5 ? MapColors.Brightness.HIGH
                            : diff > 0.9 ? MapColors.Brightness.LOW : MapColors.Brightness.NORMAL;
                } else {
                    double diff = (s.height - previousHeight) * 4.0 / (scale + 4) + (((imgX + imgZ) & 1) - 0.5) * 0.4;
                    brightness = diff > 0.6 ? MapColors.Brightness.HIGH
                            : diff < -0.6 ? MapColors.Brightness.LOW : MapColors.Brightness.NORMAL;
                }
                previousHeight = s.height;
                d.colors[imgX + imgZ * SIZE] = MapColors.getPackedId(s.color, brightness);
            }
        }
    }

    private record Sample(MapColors.Color color, double height, int waterDepth) {}

    /** Scans down from the world ceiling for the first block with a real map color; a fluid
     * hit continues descending through more fluid to measure {@code waterDepth}. */
    private static Sample sampleColumn(Instance instance, int wx, int wz) {
        int y = 320;
        Block block = Block.AIR;
        MapColors.Color color = MapColors.NONE;
        while (y > -64) {
            block = instance.getBlock(wx, y, wz);
            color = colorOf(block);
            if (color != MapColors.NONE) break;
            y--;
        }
        if (color == MapColors.NONE) return new Sample(MapColors.NONE, -64, 0);
        int waterDepth = 0;
        if (block.isLiquid()) {
            int wy = y - 1;
            while (wy > -64 && instance.getBlock(wx, wy, wz).isLiquid()) {
                waterDepth++;
                wy--;
            }
        }
        return new Sample(color, y, waterDepth);
    }

    private static MapColors.Color colorOf(Block block) {
        return MapColors.byName(VanillaData.blockMapColorName(block.key().value()));
    }

    private static int sq(int v) {
        return v * v;
    }

    // ------------------------------------------------------------------ packet + decorations

    private static void sendPacket(MapData d, Player player) {
        List<MapDataPacket.Icon> icons;
        if (d.trackPosition) {
            icons = List.of(playerIcon(d, player));
        } else {
            icons = List.of();
        }
        MapDataPacket.ColorContent content = new MapDataPacket.ColorContent((byte) SIZE, (byte) SIZE, (byte) 0, (byte) 0, d.colors);
        player.sendPacket(new MapDataPacket(d.id, d.scale, d.locked, d.trackPosition, icons, content));
    }

    /** MapItemSavedData.addDecoration(PLAYER, ...) + calculateRotation's non-Nether branch. */
    private static MapDataPacket.Icon playerIcon(MapData d, Player player) {
        int scale = 1 << d.scale;
        float dx = (float) (player.getPosition().x() - d.centerX) / scale;
        float dz = (float) (player.getPosition().z() - d.centerZ) / scale;
        byte x = clampMapCoordinate(dx);
        byte z = clampMapCoordinate(dz);
        double yRot = player.getPosition().yaw();
        double adjusted = yRot < 0 ? yRot - 8.0 : yRot + 8.0;
        byte rotation = (byte) (adjusted * 16.0 / 360.0);
        return new MapDataPacket.Icon(0 /* MapDecorationTypes.PLAYER */, x, z, rotation, null);
    }

    private static byte clampMapCoordinate(float deltaFromCenter) {
        if (deltaFromCenter <= -63.0F) return -128;
        if (deltaFromCenter >= 63.0F) return 127;
        return (byte) (deltaFromCenter * 2.0F + 0.5F);
    }
}
