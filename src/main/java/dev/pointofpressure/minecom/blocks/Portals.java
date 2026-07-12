package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.worldgen.NetherGen;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nether portals: flint and steel on an obsidian frame lights a 2-21 wide
 * interior; standing in the portal for 4 seconds sends you across with vanilla
 * 8:1 coordinate scaling, finding or building a return portal.
 */
public final class Portals {
    private Portals() {}

    private static Instance overworld;
    private static Instance nether;
    private static final Map<UUID, Integer> STANDING = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events, Instance overworldInstance, Instance netherInstance) {
        overworld = overworldInstance;
        nether = netherInstance;
        events.addListener(PlayerUseItemOnBlockEvent.class, Portals::ignite);
        MinecraftServer.getSchedulerManager().buildTask(Portals::tick)
                .repeat(TaskSchedule.tick(10)).schedule();
    }

    // ------------------------------------------------------------------ lighting

    private static void ignite(PlayerUseItemOnBlockEvent e) {
        if (e.getItemStack().material() != Material.FLINT_AND_STEEL) return;
        Instance instance = e.getPlayer().getInstance();
        var face = e.getBlockFace().toDirection();
        Point inside = e.getPosition().add(face.normalX(), face.normalY(), face.normalZ());
        // try to light a portal whose interior contains the clicked space
        if (tryLight(instance, inside, "x") || tryLight(instance, inside, "z")) {
            e.getPlayer().setItemInHand(e.getHand(),
                    dev.pointofpressure.minecom.data.Items.damageItem(e.getPlayer(), e.getItemStack(), 1));
        }
    }

    /**
     * Detect an obsidian frame around `inside` in the given plane and fill it. Now called
     * unconditionally on every flint-and-steel click (not just from Portals' own listener —
     * see Redstone.java's flint-and-steel handler, which tries this first before falling back
     * to plain fire placement), so a click anywhere near the edge of loaded terrain can walk
     * this scan into an unloaded chunk; every read below goes through safeBlock/safeObsidian,
     * which treat unloaded space as air (never obsidian) instead of throwing — real vanilla
     * wouldn't find a portal frame in ungenerated space either, so this is a safe, correct
     * approximation, not just a crash guard.
     */
    public static boolean tryLight(Instance instance, Point inside, String axis) {
        int dx = axis.equals("x") ? 1 : 0;
        int dz = axis.equals("z") ? 1 : 0;

        // walk to the bottom-left interior corner
        Point at = inside;
        int guard = 0;
        while (safeAir(instance, at.add(0, -1, 0)) && guard++ < 24) at = at.add(0, -1, 0);
        guard = 0;
        while (safeAir(instance, at.add(-dx, 0, -dz)) && guard++ < 24) at = at.add(-dx, 0, -dz);

        if (!obsidian(instance, at.add(0, -1, 0)) || !obsidian(instance, at.add(-dx, 0, -dz))) return false;

        // measure interior width and height
        int width = 0;
        while (width <= 21 && safeAir(instance, at.add(dx * width, 0, dz * width))) width++;
        if (width < 2 || width > 21) return false;
        if (!obsidian(instance, at.add(dx * width, 0, dz * width))) return false;

        int height = 0;
        while (height <= 21 && safeAir(instance, at.add(0, height, 0))) height++;
        if (height < 3 || height > 21) return false;

        // validate the full frame + interior
        for (int w = 0; w < width; w++) {
            if (!obsidian(instance, at.add(dx * w, -1, dz * w))) return false;
            if (!obsidian(instance, at.add(dx * w, height, dz * w))) return false;
            for (int h = 0; h < height; h++) {
                if (!safeAir(instance, at.add(dx * w, h, dz * w))) return false;
            }
        }
        for (int h = 0; h < height; h++) {
            if (!obsidian(instance, at.add(-dx, h, -dz))) return false;
            if (!obsidian(instance, at.add(dx * width, h, dz * width))) return false;
        }

        Block portal = Block.NETHER_PORTAL.withProperty("axis", axis);
        for (int w = 0; w < width; w++) {
            for (int h = 0; h < height; h++) {
                instance.setBlock(at.add(dx * w, h, dz * w), portal);
            }
        }
        return true;
    }

    private static boolean chunkLoaded(Instance instance, Point pos) {
        return instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4);
    }

    private static boolean safeAir(Instance instance, Point pos) {
        return chunkLoaded(instance, pos) && instance.getBlock(pos).isAir();
    }

    private static boolean obsidian(Instance instance, Point pos) {
        return chunkLoaded(instance, pos) && instance.getBlock(pos).key().value().equals("obsidian");
    }

    // ------------------------------------------------------------------ travel

    /** Entity.getDimensionChangingDelay's default (Entity.java, decompile-verified) — real vanilla
     *  refreshes this to full every tick an entity is still touching a portal block while on
     *  cooldown (Entity.setAsInsidePortal), rather than processing it for teleport at all. That
     *  refresh-while-touching behavior is what actually matters here, not the exact tick count:
     *  it's what stops a player landing inside (or immediately next to) another nearby portal
     *  from instantly bouncing back the way they came, forever. */
    private static final int PORTAL_COOLDOWN_TICKS = 300;
    private static final Map<UUID, Integer> COOLDOWN = new ConcurrentHashMap<>();

    private static void tick() {
        for (Instance instance : new Instance[]{overworld, nether}) {
            for (Player player : instance.getPlayers()) {
                UUID id = player.getUuid();
                boolean inPortal = instance.getBlock(player.getPosition())
                        .key().value().equals("nether_portal");
                int cooldown = COOLDOWN.getOrDefault(id, 0);

                if (!inPortal) {
                    STANDING.remove(id);
                    if (cooldown > 0) COOLDOWN.put(id, Math.max(0, cooldown - 10));
                    continue;
                }
                if (cooldown > 0) {
                    COOLDOWN.put(id, PORTAL_COOLDOWN_TICKS);
                    continue;
                }
                // ServerPlayer.handleInsidePortal / getDimensionChangingDelay: creative and
                // spectator players cross instantly, skipping the survival 4-second wait
                // (real vanilla waits on immersion for a game mode that has nothing at stake).
                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                    STANDING.remove(id);
                    COOLDOWN.put(id, PORTAL_COOLDOWN_TICKS);
                    travel(player, instance == overworld);
                    continue;
                }
                int ticks = STANDING.merge(id, 10, Integer::sum);
                if (ticks >= 80) {
                    STANDING.remove(id);
                    COOLDOWN.put(id, PORTAL_COOLDOWN_TICKS);
                    travel(player, instance == overworld);
                }
            }
        }
    }

    private static void travel(Player player, boolean toNether) {
        Instance target = toNether ? nether : overworld;
        Pos from = player.getPosition();
        int tx = toNether ? from.blockX() / 8 : from.blockX() * 8;
        int tz = toNether ? from.blockZ() / 8 : from.blockZ() * 8;

        Pos arrival = findOrBuildPortal(target, tx, tz, toNether);
        player.setInstance(target, arrival).thenRun(() ->
                player.sendMessage(Component.text(toNether
                        ? "You step into the Nether..." : "The overworld air rushes back.",
                        NamedTextColor.LIGHT_PURPLE)));
    }

    /** Overworld surface height: vanilla generator when live, legacy noise in playtest. */
    private static int overworldSurface(int x, int z) {
        var gen = dev.pointofpressure.minecom.Bootstrap.vanillaGen();
        return gen != null ? gen.topBlock(x, z)[0]
                : dev.pointofpressure.minecom.worldgen.WorldGen.surfaceHeight(x, z);
    }

    /** Reuse a portal within 16 blocks of the target, or build a fresh frame on a safe platform. */
    private static Pos findOrBuildPortal(Instance target, int tx, int tz, boolean inNether) {
        int baseY = inNether
                ? NetherGen.floorNear(tx, tz)
                : overworldSurface(tx, tz);

        // load area and search for an existing portal
        for (int cx = (tx - 16) >> 4; cx <= (tx + 16) >> 4; cx++) {
            for (int cz = (tz - 16) >> 4; cz <= (tz + 16) >> 4; cz++) {
                target.loadChunk(cx, cz).join();
            }
        }
        for (int x = tx - 16; x <= tx + 16; x++) {
            for (int z = tz - 16; z <= tz + 16; z++) {
                for (int y = Math.max(inNether ? 5 : -60, baseY - 16);
                     y < Math.min(inNether ? 122 : 250, baseY + 24); y++) {
                    if (target.getBlock(x, y, z).key().value().equals("nether_portal")) {
                        return new Pos(x + 0.5, y, z + 0.5);
                    }
                }
            }
        }

        // build: platform + standard 4x5 frame with the portal lit
        int y = baseY + 1;
        for (int px = -1; px <= 3; px++) {
            for (int pz = -2; pz <= 2; pz++) {
                target.setBlock(tx + px, y - 1, tz + pz,
                        inNether ? Block.NETHERRACK : Block.STONE);
                for (int h = 0; h < 5; h++) {
                    target.setBlock(tx + px, y + h, tz + pz, Block.AIR);
                }
            }
        }
        for (int w = 0; w < 4; w++) {
            target.setBlock(tx + w - 1, y - 1, tz, Block.OBSIDIAN);
            target.setBlock(tx + w - 1, y + 3, tz, Block.OBSIDIAN);
        }
        for (int h = -1; h <= 3; h++) {
            target.setBlock(tx - 1, y + h, tz, Block.OBSIDIAN);
            target.setBlock(tx + 2, y + h, tz, Block.OBSIDIAN);
        }
        Block portal = Block.NETHER_PORTAL.withProperty("axis", "x");
        for (int w = 0; w < 2; w++) {
            for (int h = 0; h < 3; h++) {
                target.setBlock(tx + w, y + h, tz, portal);
            }
        }
        return new Pos(tx + 0.5, y, tz + 0.5);
    }

    /** Test hook: force immediate travel evaluation. */
    public static void debugTravel(Player player, boolean toNether) {
        travel(player, toNether);
    }
}
