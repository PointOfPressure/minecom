package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.Difficulty;
import dev.pointofpressure.minecom.data.VanillaData;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.entity.metadata.animal.BeeMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bee entity, ported from decompiled Bee.java (26.2), condensed from its dozen individual
 * vanilla Goal classes into one per-tick state machine driven at the same priorities (sting
 * while angry outranks everything; then return-to-hive/pollinate/wander — matching
 * registerGoals' 0=attack, 1=enter-hive, 5=go-to-hive, 4=pollinate, 8=wander ordering).
 * hasNectar/hasStung are real vanilla flag bits; a stung bee's death roll is the exact formula
 * from {@code customServerAiStep} ({@code timeSinceSting % 5 == 0 && random.nextInt(clamp(1200
 * - timeSinceSting, 1, 1200)) == 0}, a rising death chance over the 1200-tick
 * STING_DEATH_COUNTDOWN). Pollination needs {@code BlockTags.BEE_ATTRACTIVE} (bundled as
 * {@code bee_attractive}) within Manhattan radius 5 and MIN_POLLINATION_TICKS=400 hovering
 * ticks before nectar is granted (real vanilla then keeps rolling 20%/tick to end early — same
 * shape kept here); wantsToEnterHive fires once nectar is carried or
 * TICKS_WITHOUT_NECTAR_BEFORE_GOING_HOME=3600 ticks pass with none found (isTiredOfLookingFor
 * Nectar) — 26.2 moved the old "always return at night" idea behind a data-driven
 * {@code EnvironmentAttributes.BEES_STAY_IN_HIVE} attribute that defaults false and has no
 * biome override wired in this project (no environment-attribute system exists here), so it's
 * omitted; there is no explicit day/night gate anywhere in the decompiled source to port.
 *
 * Simplifications (AUDIT): flight is direct velocity-to-target steering rather than vanilla's
 * A*-ish AirRandomPos sampling (this project's ground VPathfinder doesn't cover 3D flight, and
 * ghast/phantom/happy-ghast already use the same direct-velocity idiom); hive/flower discovery
 * is a block-tag scan within a fixed radius on a cooldown rather than the POI-manager-backed
 * hive registry (there's no POI-range-query system in this project); unreachable-flower/hive
 * caching, the shared "one flower memory per hive" nicety, crop-growing (BeeGrowCropGoal), and
 * the roll/hover animation state are not modeled (no client-visible effect without them beyond
 * the animation itself).
 */
public final class Bees {
    private Bees() {}

    private static final double FLY_SPEED = 0.22;
    private static final double ANGRY_SPEED = 0.32;
    private static final double STING_RANGE = 1.6;
    private static final float STING_DAMAGE = 2.0f;
    private static final int STING_DEATH_COUNTDOWN = 1200;
    private static final int MIN_POLLINATION_TICKS = 400;
    private static final int MAX_POLLINATING_TICKS = 600;
    private static final int TICKS_WITHOUT_NECTAR_BEFORE_HOME = 3600;
    private static final int FLOWER_SEARCH_RADIUS = 5;
    private static final int HIVE_SEARCH_RADIUS = 20;
    private static final int FIND_COOLDOWN = 200;
    // PERSISTENT_ANGER_TIME: TimeUtil.rangeOfSeconds(20, 39) -> ticks
    private static final int ANGER_MIN_TICKS = 400, ANGER_MAX_TICKS = 780;

    private static final class State {
        boolean hasNectar;
        boolean hasStung;
        int timeSinceSting;
        LivingEntity angryAt;
        int angerTicksLeft;
        Point hivePos;
        Point flowerPos;
        int ticksWithoutNectar;
        int pollinatingTicks;
        int successfulPollinatingTicks;
        int findFlowerCooldown = ThreadLocalRandom.current().nextInt(20, 61);
        int findHiveCooldown;
        Point wanderGoal;
        long wanderUntil;
    }

    private static final Map<Integer, State> BEES = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(EntityDamageEvent.class, e -> {
            if (e.getEntity().getEntityType() != EntityType.BEE) return;
            if (!(e.getEntity() instanceof EntityCreature bee)) return;
            if (e.getDamage().getAttacker() instanceof LivingEntity attacker) {
                setAngry(bee, attacker);
            }
        });
    }

    /** Bee.createAttributes: 10 HP, 0.3 movement (0.6 flying), 2 attack damage. */
    public static EntityCreature spawn(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.BEE);
        mob.setNoGravity(true);
        State s = new State();
        BEES.put(mob.getEntityId(), s);
        mob.setInstance(instance, pos);
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.isRemoved() || mob.getInstance() == null) {
                BEES.remove(mob.getEntityId());
                return;
            }
            tickOne(mob, s);
        }).repeat(TaskSchedule.tick(1)).schedule();
        return mob;
    }

    public static void setHivePos(EntityCreature bee, Point pos) {
        State s = BEES.get(bee.getEntityId());
        if (s != null) s.hivePos = pos;
    }

    /** NeutralMob.setTarget + startPersistentAngerTimer (20-39s). */
    public static void setAngry(EntityCreature bee, LivingEntity target) {
        State s = BEES.get(bee.getEntityId());
        if (s == null || s.hasStung) return;
        s.angryAt = target;
        s.angerTicksLeft = ThreadLocalRandom.current().nextInt(ANGER_MIN_TICKS, ANGER_MAX_TICKS + 1);
    }

    public static boolean isAngry(EntityCreature bee) {
        State s = BEES.get(bee.getEntityId());
        return s != null && s.angryAt != null;
    }

    public static boolean hasNectar(EntityCreature bee) {
        State s = BEES.get(bee.getEntityId());
        return s != null && s.hasNectar;
    }

    public static boolean hasStung(EntityCreature bee) {
        State s = BEES.get(bee.getEntityId());
        return s != null && s.hasStung;
    }

    public static Point hivePos(EntityCreature bee) {
        State s = BEES.get(bee.getEntityId());
        return s == null ? null : s.hivePos;
    }

    /** Test hook: drive one state-machine step without waiting on the mob's own real-time
     * per-tick scheduler (CLAUDE.md rule: no wall-clock waits in PlayTest). */
    public static void tickForTest(EntityCreature bee) {
        State s = BEES.get(bee.getEntityId());
        if (s != null) tickOne(bee, s);
    }

    /** Test hook: jump straight to "has stung, counting down to its death roll" without
     * needing a real sting to land first. */
    public static void setStungForTest(EntityCreature bee) {
        State s = BEES.get(bee.getEntityId());
        if (s != null) {
            s.hasStung = true;
            s.timeSinceSting = 0;
            s.angryAt = null;
        }
    }

    private static void tickOne(EntityCreature bee, State s) {
        Instance instance = bee.getInstance();
        long now = instance.getWorldAge();
        if (bee.getEntityMeta() instanceof BeeMeta meta) {
            meta.setHasNectar(s.hasNectar);
            meta.setHasStung(s.hasStung);
            meta.setAngerEndTime(s.angryAt != null ? now + s.angerTicksLeft : -1L);
        }

        // Bee.customServerAiStep: stung bees have an ever-rising death chance.
        if (s.hasStung) {
            s.timeSinceSting++;
            if (s.timeSinceSting % 5 == 0
                    && ThreadLocalRandom.current().nextInt(Math.max(1, Math.min(1200, STING_DEATH_COUNTDOWN - s.timeSinceSting))) == 0) {
                bee.damage(DamageType.GENERIC, bee.getHealth());
                return;
            }
        }

        // targetSelector priority 1-2: sting the current anger target once in range.
        if (s.angryAt != null) {
            if (s.angerTicksLeft-- <= 0 || s.angryAt.isDead() || s.angryAt.isRemoved()
                    || s.angryAt.getInstance() != instance || s.hasStung) {
                s.angryAt = null;
            } else {
                double dist = bee.getPosition().distance(s.angryAt.getPosition());
                if (dist <= STING_RANGE) {
                    sting(bee, s);
                } else {
                    flyToward(bee, s.angryAt.getPosition(), ANGRY_SPEED);
                }
                return;
            }
        }

        if (!s.hasNectar) s.ticksWithoutNectar++;
        boolean tired = s.ticksWithoutNectar > TICKS_WITHOUT_NECTAR_BEFORE_HOME;

        // BeeEnterHiveGoal / BeeGoToHiveGoal: nectar (or tired) + a known hive -> go home.
        if (s.hasNectar || tired) {
            if (s.hivePos == null && s.findHiveCooldown-- <= 0) {
                s.findHiveCooldown = FIND_COOLDOWN;
                s.hivePos = findHive(instance, bee.getPosition());
            }
            if (s.hivePos != null) {
                if (!instance.isChunkLoaded(s.hivePos.blockX() >> 4, s.hivePos.blockZ() >> 4)) {
                    s.hivePos = null;
                } else if (bee.getPosition().distance(s.hivePos) <= 2.0) {
                    boolean stored = dev.pointofpressure.minecom.blocks.Beehives.addOccupant(s.hivePos, s.hasNectar);
                    if (stored) {
                        BEES.remove(bee.getEntityId());
                        bee.remove();
                        return;
                    }
                    s.hivePos = null; // full — drop it and look elsewhere later
                } else {
                    flyToward(bee, centerOf(s.hivePos), FLY_SPEED);
                    return;
                }
            }
        }

        // BeePollinateGoal: no nectar, not raining, a flower is known or found nearby.
        if (!s.hasNectar && !instance.getWeather().isRaining()) {
            if (s.flowerPos == null && s.findFlowerCooldown-- <= 0) {
                s.findFlowerCooldown = ThreadLocalRandom.current().nextInt(20, 61);
                s.flowerPos = findFlower(instance, bee.getPosition());
            }
            if (s.flowerPos != null) {
                if (!isFlower(instance, s.flowerPos)) {
                    s.flowerPos = null;
                } else {
                    Point hover = centerOf(s.flowerPos).add(0, 0.6, 0);
                    if (bee.getPosition().distance(hover) > 1.0) {
                        flyToward(bee, hover, FLY_SPEED * 0.6);
                    } else {
                        bee.setVelocity(Vec.ZERO);
                        s.pollinatingTicks++;
                        s.successfulPollinatingTicks++;
                        boolean longEnough = s.successfulPollinatingTicks > MIN_POLLINATION_TICKS;
                        if (s.pollinatingTicks > MAX_POLLINATING_TICKS
                                || (longEnough && ThreadLocalRandom.current().nextFloat() < 0.2f)) {
                            if (longEnough) {
                                s.hasNectar = true;
                                s.ticksWithoutNectar = 0;
                            }
                            s.flowerPos = null;
                            s.pollinatingTicks = 0;
                            s.successfulPollinatingTicks = 0;
                        }
                    }
                    return;
                }
            }
        }

        // BeeWanderGoal fallback: drift, biased toward a known hive once far from it.
        wander(bee, s, instance, now);
    }

    private static void sting(EntityCreature bee, State s) {
        LivingEntity target = s.angryAt;
        boolean hurt = target.damage(DamageType.STING, STING_DAMAGE);
        if (hurt) {
            int poisonSeconds = switch (Difficulty.current()) {
                case NORMAL -> 10;
                case HARD -> 18;
                default -> 0;
            };
            if (poisonSeconds > 0) target.addEffect(new Potion(PotionEffect.POISON, (byte) 0, poisonSeconds * 20));
            s.hasStung = true;
            s.angryAt = null;
        }
    }

    // ------------------------------------------------------------------ movement

    private static void flyToward(EntityCreature bee, Point target, double speed) {
        Vec dir = target.sub(bee.getPosition()).asVec();
        double len = dir.length();
        if (len < 0.05) {
            bee.setVelocity(Vec.ZERO);
            return;
        }
        Vec v = dir.normalize().mul(speed * 20); // Minestom velocity is blocks/sec
        bee.setVelocity(v);
        double yaw = Math.toDegrees(Math.atan2(-dir.x(), dir.z()));
        bee.setView((float) yaw, bee.getPosition().pitch());
    }

    private static void wander(EntityCreature bee, State s, Instance instance, long now) {
        if (s.wanderGoal == null || now >= s.wanderUntil || bee.getPosition().distance(s.wanderGoal) < 0.5) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            Vec dir;
            if (s.hivePos != null && bee.getPosition().distance(s.hivePos) > 32) {
                dir = centerOf(s.hivePos).sub(bee.getPosition()).asVec().normalize();
            } else {
                double a = rng.nextDouble() * Math.PI * 2;
                dir = new Vec(Math.cos(a), 0, Math.sin(a));
            }
            double vy = (rng.nextDouble() - 0.5) * 2;
            Vec offset = dir.mul(6).add(0, vy, 0);
            s.wanderGoal = bee.getPosition().add(offset.x(), offset.y(), offset.z());
            s.wanderUntil = now + 60 + rng.nextInt(40);
        }
        flyToward(bee, s.wanderGoal, FLY_SPEED * 0.7);
    }

    // ------------------------------------------------------------------ flower/hive search

    /** Bee.attractsBees: BlockTags.BEE_ATTRACTIVE, waterlogged excluded, sunflower needs UPPER half. */
    private static boolean isFlower(Instance instance, Point pos) {
        if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) return false;
        var block = instance.getBlock(pos);
        if (!VanillaData.blockHasTag(block, "bee_attractive")) return false;
        if ("true".equals(block.getProperty("waterlogged"))) return false;
        if (block.key().value().equals("sunflower") && !"upper".equals(block.getProperty("half"))) return false;
        return true;
    }

    private static Point findFlower(Instance instance, Point from) {
        int r = FLOWER_SEARCH_RADIUS;
        int bx = from.blockX(), by = from.blockY(), bz = from.blockZ();
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > r) continue;
                    BlockVec p = new BlockVec(bx + dx, by + dy, bz + dz);
                    if (isFlower(instance, p)) return p;
                }
        return null;
    }

    private static Point findHive(Instance instance, Point from) {
        int r = HIVE_SEARCH_RADIUS;
        int bx = from.blockX(), by = from.blockY(), bz = from.blockZ();
        Point best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -8; dy <= 8; dy++) {
                    BlockVec p = new BlockVec(bx + dx, by + dy, bz + dz);
                    if (!instance.isChunkLoaded(p.blockX() >> 4, p.blockZ() >> 4)) continue;
                    var block = instance.getBlock(p);
                    String key = block.key().value();
                    if (!key.equals("beehive") && !key.equals("bee_nest")) continue;
                    if (dev.pointofpressure.minecom.blocks.Beehives.isFull(p)) continue;
                    double d = p.distanceSquared(from);
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
        return best;
    }

    private static Point centerOf(Point p) {
        return new Vec(p.blockX() + 0.5, p.blockY() + 0.5, p.blockZ() + 0.5);
    }
}
