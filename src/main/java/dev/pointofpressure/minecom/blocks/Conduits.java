package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.TickPipeline;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Conduit block entity, ported from decompiled ConduitBlockEntity (26.2).
 * updateShape first requires the 3x3x3 of blocks centred on the conduit to be all
 * water, then counts the prismarine-family frame blocks (prismarine, prismarine
 * bricks, sea lantern, dark prismarine) sitting on the octahedral ring at radius 2;
 * >=16 frame blocks activates it, >=42 (the full frame) makes it "hunt". On the
 * gameTime%40 cadence an active conduit grants Conduit Power (260 ticks) to every
 * player within (size/7*16) blocks who is in water or rain, and a hunting conduit
 * deals 4 magic damage to one random hostile mob in water/rain within 8 blocks.
 * isActive/isHunting/activeRotation carry the activation animation state, and an
 * activate/deactivate sound fires on the active-state transition.
 *
 * Simplifications (AUDIT): nautilus/attack particle emission and the ambient-sound
 * jitter are not modelled; the destroy target is re-selected each cadence rather than
 * persisted across ticks; "in water or rain" treats rain as raining + sky access.
 */
public final class Conduits {
    private Conduits() {}

    private static final int MIN_ACTIVE_SIZE = 16;
    private static final int MIN_KILL_SIZE = 42;
    private static final int KILL_RANGE = 8;
    private static final int EFFECT_DURATION = 260;
    private static final long CADENCE = 40L;
    private static final Set<String> FRAME_BLOCKS = Set.of(
            "prismarine", "prismarine_bricks", "sea_lantern", "dark_prismarine");
    // Monster mobs (the vanilla Enemy interface) a conduit can hunt while submerged.
    private static final Set<EntityType> HOSTILE = Set.of(
            EntityType.DROWNED, EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.ZOMBIE,
            EntityType.HUSK, EntityType.SKELETON, EntityType.STRAY, EntityType.SPIDER,
            EntityType.CAVE_SPIDER, EntityType.CREEPER, EntityType.WITCH, EntityType.SLIME,
            EntityType.ZOMBIE_VILLAGER, EntityType.PILLAGER, EntityType.VINDICATOR);

    private static final class Conduit {
        int size;
        boolean isActive;
        boolean isHunting;
        float activeRotation;
    }

    private static final Map<Long, Conduit> CONDUITS = new ConcurrentHashMap<>();
    private static Instance instance;

    public static void register(GlobalEventHandler events, Instance overworld) {
        instance = overworld;
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (e.getBlock().key().value().equals("conduit")) track(e.getBlockPosition());
        });
        events.addListener(PlayerBlockBreakEvent.class, e -> {
            if (e.getBlock().key().value().equals("conduit")) CONDUITS.remove(pack(e.getBlockPosition()));
        });
        TickPipeline.register(TickPipeline.BLOCK_ENTITIES, "conduits", Conduits::tick);
    }

    /** Register a conduit placed by tests or world code. */
    public static void track(Point pos) {
        CONDUITS.computeIfAbsent(pack(pos), k -> new Conduit());
    }

    /** Frame block count found by the last updateShape (0 if untracked). */
    public static int frameSize(Point pos) {
        Conduit c = CONDUITS.get(pack(pos));
        return c == null ? 0 : c.size;
    }

    public static boolean isActive(Point pos) {
        Conduit c = CONDUITS.get(pack(pos));
        return c != null && c.isActive;
    }

    public static boolean isHunting(Point pos) {
        Conduit c = CONDUITS.get(pack(pos));
        return c != null && c.isHunting;
    }

    /** Conduit power radius for a given frame size: size/7*16 (ConduitBlockEntity.applyEffects). */
    public static int effectRange(int size) {
        return size / 7 * 16;
    }

    /** ConduitBlockEntity.updateShape: recompute the frame + activation. Test hook + tick driver. */
    public static boolean recompute(Point pos) {
        Conduit c = CONDUITS.get(pack(pos));
        if (c == null || instance == null) return false;
        c.size = updateShape(pos);
        c.isActive = c.size >= MIN_ACTIVE_SIZE;
        c.isHunting = c.size >= MIN_KILL_SIZE;
        return c.isActive;
    }

    /** ConduitBlockEntity.applyEffects: grant Conduit Power to in-water/rain players in range. */
    public static void applyEffects(Point pos) {
        Conduit c = CONDUITS.get(pack(pos));
        if (c == null || instance == null || !c.isActive) return;
        int range = effectRange(c.size);
        Point centre = new Vec(pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        for (Player player : instance.getPlayers()) {
            if (player.getPosition().distance(centre) <= range && inWaterOrRain(player)) {
                player.addEffect(new Potion(PotionEffect.CONDUIT_POWER, (byte) 0, EFFECT_DURATION));
            }
        }
    }

    /** ConduitBlockEntity.updateAndAttackTarget: 4 magic damage to one hostile mob in range. */
    public static LivingEntity attackTarget(Point pos) {
        Conduit c = CONDUITS.get(pack(pos));
        if (c == null || instance == null || !c.isHunting) return null;
        Point centre = new Vec(pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        List<LivingEntity> candidates = new ArrayList<>();
        for (Entity e : instance.getNearbyEntities(centre, KILL_RANGE)) {
            if (e instanceof LivingEntity le && HOSTILE.contains(e.getEntityType())
                    && !le.isDead() && inWaterOrRain(le)) {
                candidates.add(le);
            }
        }
        if (candidates.isEmpty()) return null;
        LivingEntity target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        target.damage(DamageType.MAGIC, 4.0f);
        return target;
    }

    // ------------------------------------------------------------------ geometry

    /** ConduitBlockEntity.updateShape: 3x3x3 water gate, then count the radius-2 frame ring. */
    private static int updateShape(Point pos) {
        int x = pos.blockX(), y = pos.blockY(), z = pos.blockZ();
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if (!isWater(instance.getBlock(x + ox, y + oy, z + oz))) return 0;
                }
            }
        }
        int size = 0;
        for (int ox = -2; ox <= 2; ox++) {
            for (int oy = -2; oy <= 2; oy++) {
                for (int oz = -2; oz <= 2; oz++) {
                    int ax = Math.abs(ox), ay = Math.abs(oy), az = Math.abs(oz);
                    if ((ax > 1 || ay > 1 || az > 1)
                            && (ox == 0 && (ay == 2 || az == 2)
                            || oy == 0 && (ax == 2 || az == 2)
                            || oz == 0 && (ax == 2 || ay == 2))) {
                        if (FRAME_BLOCKS.contains(instance.getBlock(x + ox, y + oy, z + oz).key().value())) {
                            size++;
                        }
                    }
                }
            }
        }
        return size;
    }

    private static boolean isWater(Block block) {
        return block.key().value().equals("water") || "true".equals(block.getProperty("waterlogged"));
    }

    private static boolean inWaterOrRain(Entity entity) {
        Point p = entity.getPosition();
        if (isWater(instance.getBlock(p))) return true;
        return instance.getWeather().isRaining()
                && dev.pointofpressure.minecom.survival.Lightning.canSeeSky(instance,
                        new net.minestom.server.coordinate.Pos(p.x(), p.y(), p.z()));
    }

    // ------------------------------------------------------------------ tick

    private static void tick() {
        if (instance == null || CONDUITS.isEmpty()) return;
        long gameTime = instance.getWorldAge();
        for (Map.Entry<Long, Conduit> entry : CONDUITS.entrySet()) {
            Point pos = unpack(entry.getKey());
            if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
            if (!instance.getBlock(pos).key().value().equals("conduit")) {
                CONDUITS.remove(entry.getKey());
                continue;
            }
            Conduit c = entry.getValue();
            if (c.isActive) c.activeRotation++;
            if (gameTime % CADENCE != 0L) continue;
            boolean wasActive = c.isActive;
            recompute(pos);
            if (c.isActive != wasActive) {
                SoundEvent event = c.isActive ? SoundEvent.BLOCK_CONDUIT_ACTIVATE : SoundEvent.BLOCK_CONDUIT_DEACTIVATE;
                instance.playSound(net.kyori.adventure.sound.Sound.sound(
                        event, net.kyori.adventure.sound.Sound.Source.BLOCK, 1.0f, 1.0f),
                        pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
            }
            if (c.isActive) {
                applyEffects(pos);
                attackTarget(pos);
            }
        }
    }

    private static long pack(Point p) {
        return ((long) (p.blockX() & 0x3FFFFFF) << 38) | ((long) (p.blockY() & 0xFFF) << 26)
                | (p.blockZ() & 0x3FFFFFF);
    }

    private static Point unpack(long k) {
        return new Vec((int) (k >> 38), (int) (k << 26 >> 52), (int) (k << 38 >> 38));
    }
}
