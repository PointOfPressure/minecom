package dev.pointofpressure.minecom.mobs.ai;

import dev.pointofpressure.minecom.EntityIndex;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.item.Material;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The vanilla goal roster, each reimplemented from the decompiled reference
 * with its exact parameters: timings, ranges, probabilities and interrupts.
 */
public final class Goals {
    private Goals() {}

    // ---------------------------------------------------------------- melee

    /** MeleeAttackGoal: 20-tick canUse cooldown, 20-tick attack interval, path recalc 4-14 ticks. */
    public static class MeleeAttack extends VGoal {
        protected final VBrain brain;
        private final double speedModifier;
        private final boolean followEvenIfNotSeen;
        private long lastCanUseCheck;
        private int ticksUntilNextPathRecalculation;
        private int ticksUntilNextAttack;

        public MeleeAttack(VBrain brain, double speedModifier, boolean followEvenIfNotSeen) {
            this.brain = brain;
            this.speedModifier = speedModifier;
            this.followEvenIfNotSeen = followEvenIfNotSeen;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (brain.gameTime - lastCanUseCheck < 20) return false;
            lastCanUseCheck = brain.gameTime;
            LivingEntity target = brain.target;
            return target != null && !target.isDead();
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) return false;
            if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE
                    || p.getGameMode() == GameMode.SPECTATOR)) return false;
            return followEvenIfNotSeen || !brain.navigationDone();
        }

        @Override
        public void start() {
            if (brain.target != null) brain.moveTo(brain.target.getPosition(), speedModifier);
            brain.aggressive = true;
            ticksUntilNextPathRecalculation = 0;
            ticksUntilNextAttack = 0;
        }

        @Override
        public void stop() {
            brain.aggressive = false;
            brain.stopNavigation();
        }

        @Override
        public void tick() {
            LivingEntity target = brain.target;
            if (target == null) return;
            brain.lookAt(target);
            ticksUntilNextPathRecalculation = Math.max(ticksUntilNextPathRecalculation - 1, 0);
            if ((followEvenIfNotSeen || brain.hasLineOfSight(target)) && ticksUntilNextPathRecalculation <= 0) {
                ticksUntilNextPathRecalculation = 4 + brain.random.nextInt(7);
                double dist = brain.mob.getPosition().distance(target.getPosition());
                if (dist > 32) ticksUntilNextPathRecalculation += 10;
                else if (dist > 16) ticksUntilNextPathRecalculation += 5;
                brain.moveTo(target.getPosition(), speedModifier);
            }
            ticksUntilNextAttack = Math.max(ticksUntilNextAttack - 1, 0);
            if (ticksUntilNextAttack <= 0 && brain.isWithinMeleeAttackRange(target)) {
                ticksUntilNextAttack = 20;
                brain.mob.attack(target, true);
            }
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }
    }

    // ---------------------------------------------------------------- movement

    /** RandomStrollGoal/WaterAvoidingRandomStrollGoal: 1/120 chance, land positions within 10x7. */
    public static class WaterAvoidingRandomStroll extends VGoal {
        private final VBrain brain;
        private final double speedModifier;
        private final int interval;
        private Pos wanted;

        public WaterAvoidingRandomStroll(VBrain brain, double speedModifier) {
            this(brain, speedModifier, 120);
        }

        public WaterAvoidingRandomStroll(VBrain brain, double speedModifier, int interval) {
            this.brain = brain;
            this.speedModifier = speedModifier;
            this.interval = interval;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            // A mounted horse-family mob is steered by mobs.Riding.tick's direct
            // velocity writes; letting this goal's own moveTo/pathfinding fire at the
            // same time would fight the rider for control (no other VBrain-driven mob
            // is currently rideable, so this is a no-op guard for everything else).
            if (!brain.mob.getPassengers().isEmpty()) return false;
            if (brain.aggressive || brain.target != null) return false;
            if (brain.random.nextInt(interval) != 0) return false;
            wanted = landPosition();
            return wanted != null;
        }

        private Pos landPosition() {
            var instance = brain.mob.getInstance();
            Pos origin = brain.mob.getPosition();
            for (int attempt = 0; attempt < 10; attempt++) {
                int dx = brain.random.nextInt(21) - 10;
                int dy = brain.random.nextInt(15) - 7;
                int dz = brain.random.nextInt(21) - 10;
                Pos candidate = origin.add(dx, dy, dz);
                if (!instance.isChunkLoaded(candidate.blockX() >> 4, candidate.blockZ() >> 4)) continue;
                // walk down to ground
                int y = candidate.blockY();
                int guard = 0;
                while (y > -60 && instance.getBlock(candidate.blockX(), y - 1, candidate.blockZ()).isAir()
                        && guard++ < 12) y--;
                var floor = instance.getBlock(candidate.blockX(), y - 1, candidate.blockZ());
                var at = instance.getBlock(candidate.blockX(), y, candidate.blockZ());
                if (floor.isSolid() && at.isAir() && !floor.isLiquid()) {
                    return new Pos(candidate.blockX() + 0.5, y, candidate.blockZ() + 0.5);
                }
            }
            return null;
        }

        @Override
        public boolean canContinueToUse() {
            return !brain.navigationDone() && brain.target == null;
        }

        @Override
        public void start() {
            brain.moveTo(wanted, speedModifier);
        }

        @Override
        public void stop() {
            brain.stopNavigation();
        }
    }

    /** PanicGoal: after taking damage, sprint to a random spot until calm. */
    public static class Panic extends VGoal {
        private final VBrain brain;
        private final double speedModifier;
        private long panicSince = -1;

        public Panic(VBrain brain, double speedModifier) {
            this.brain = brain;
            this.speedModifier = speedModifier;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return brain.lastHurtTimestamp >= 0 && brain.gameTime - brain.lastHurtTimestamp < 100;
        }

        @Override
        public boolean canContinueToUse() {
            return brain.gameTime - brain.lastHurtTimestamp < 100 && !brain.navigationDone();
        }

        @Override
        public void start() {
            panicSince = brain.gameTime;
            Pos origin = brain.mob.getPosition();
            Vec away = brain.lastHurtBy != null
                    ? origin.sub(brain.lastHurtBy.getPosition()).asVec().normalize().mul(5)
                    : new Vec(brain.random.nextInt(11) - 5, 0, brain.random.nextInt(11) - 5);
            brain.moveTo(origin.add(away.x(), 0, away.z()), speedModifier);
        }

        @Override
        public void stop() {
            brain.stopNavigation();
        }
    }

    /** TemptGoal: follow a player holding tempting items, stop at 2.5 blocks, 100-tick calm-down. */
    public static class Tempt extends VGoal {
        private final VBrain brain;
        private final double speedModifier;
        private final Predicate<Material> items;
        private Player temptingPlayer;
        private int calmDown;

        public Tempt(VBrain brain, double speedModifier, Set<Material> items) {
            this.brain = brain;
            this.speedModifier = speedModifier;
            this.items = items::contains;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (calmDown > 0) {
                calmDown--;
                return false;
            }
            temptingPlayer = null;
            for (Player player : brain.mob.getInstance().getPlayers()) {
                if (player.isDead()) continue;
                if (player.getPosition().distanceSquared(brain.mob.getPosition()) > 100) continue;
                if (items.test(player.getItemInMainHand().material())
                        || items.test(player.getItemInOffHand().material())) {
                    temptingPlayer = player;
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            return temptingPlayer != null && !temptingPlayer.isDead()
                    && temptingPlayer.getPosition().distanceSquared(brain.mob.getPosition()) <= 100
                    && (items.test(temptingPlayer.getItemInMainHand().material())
                        || items.test(temptingPlayer.getItemInOffHand().material()));
        }

        @Override
        public void tick() {
            brain.lookAt(temptingPlayer);
            if (brain.mob.getPosition().distanceSquared(temptingPlayer.getPosition()) < 6.25) {
                brain.stopNavigation();
            } else {
                brain.moveTo(temptingPlayer.getPosition(), speedModifier);
            }
        }

        @Override
        public void stop() {
            temptingPlayer = null;
            brain.stopNavigation();
            calmDown = 100;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }
    }

    // ---------------------------------------------------------------- looking

    /** LookAtPlayerGoal: 0.02 chance, 8-block range, 40-80 tick gaze. */
    public static class LookAtPlayer extends VGoal {
        private final VBrain brain;
        private final float distance;
        private final float probability;
        private Player lookAt;
        private int lookTime;

        public LookAtPlayer(VBrain brain, float distance) {
            this(brain, distance, 0.02f);
        }

        public LookAtPlayer(VBrain brain, float distance, float probability) {
            this.brain = brain;
            this.distance = distance;
            this.probability = probability;
            setFlags(EnumSet.of(Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (brain.random.nextFloat() >= probability) return false;
            lookAt = null;
            double best = distance * distance;
            for (Player player : brain.mob.getInstance().getPlayers()) {
                double d = player.getPosition().distanceSquared(brain.mob.getPosition());
                if (d < best) {
                    best = d;
                    lookAt = player;
                }
            }
            return lookAt != null;
        }

        @Override
        public boolean canContinueToUse() {
            return lookAt != null && !lookAt.isDead() && lookTime > 0
                    && brain.mob.getPosition().distanceSquared(lookAt.getPosition()) <= distance * distance;
        }

        @Override
        public void start() {
            lookTime = 40 + brain.random.nextInt(40);
        }

        @Override
        public void tick() {
            lookTime--;
            if (lookAt != null) brain.lookAt(lookAt);
        }
    }

    /** RandomLookAroundGoal: 0.02 chance, 20-40 tick gaze at a random direction. */
    public static class RandomLookAround extends VGoal {
        private final VBrain brain;
        private double relX;
        private double relZ;
        private int lookTime;

        public RandomLookAround(VBrain brain) {
            this.brain = brain;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return brain.random.nextFloat() < 0.02f;
        }

        @Override
        public boolean canContinueToUse() {
            return lookTime >= 0;
        }

        @Override
        public void start() {
            double angle = Math.PI * 2 * brain.random.nextDouble();
            relX = Math.cos(angle);
            relZ = Math.sin(angle);
            lookTime = 20 + brain.random.nextInt(20);
        }

        @Override
        public void tick() {
            lookTime--;
            Pos pos = brain.mob.getPosition();
            brain.lookAt(new Vec(pos.x() + relX, pos.y() + brain.mob.getEyeHeight(), pos.z() + relZ));
        }
    }

    // ---------------------------------------------------------------- targeting

    /** HurtByTargetGoal: retaliate against the last attacker, optionally alerting same-species allies within follow range. */
    public static class HurtByTarget extends VGoal {
        private final VBrain brain;
        private final boolean alertOthers;
        private long seenTimestamp = -1;

        public HurtByTarget(VBrain brain, boolean alertOthers) {
            this.brain = brain;
            this.alertOthers = alertOthers;
            setFlags(EnumSet.of(Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            return brain.lastHurtTimestamp != seenTimestamp && brain.lastHurtBy != null
                    && !brain.lastHurtBy.isDead();
        }

        @Override
        public void start() {
            seenTimestamp = brain.lastHurtTimestamp;
            brain.setTarget(brain.lastHurtBy);
            if (alertOthers) {
                double range = brain.followRange();
                for (Entity entity : EntityIndex.near(brain.mob.getInstance(), brain.mob.getPosition(), range)) {
                    if (entity == brain.mob) continue;
                    if (entity.getEntityType() != brain.mob.getEntityType()) continue;
                    if (entity.getPosition().distanceSquared(brain.mob.getPosition()) > range * range) continue;
                    if (Math.abs(entity.getPosition().y() - brain.mob.getPosition().y()) > 10) continue;
                    VBrain other = VanillaMobs.brainOf(entity);
                    if (other != null && other.target == null) {
                        other.setTarget(brain.lastHurtBy);
                    }
                }
            }
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) return false;
            double range = brain.followRange();
            return brain.mob.getPosition().distanceSquared(target.getPosition()) <= range * range;
        }

        @Override
        public void stop() {
            if (brain.target == brain.lastHurtBy) brain.setTarget(null);
        }
    }

    /** NearestAttackableTargetGoal(Player): 1/10 tick scan, follow-range limited, must-see with 60-tick memory. */
    public static class NearestAttackablePlayer extends VGoal {
        private final VBrain brain;
        private final boolean mustSee;
        private Player found;
        private int unseenTicks;

        public NearestAttackablePlayer(VBrain brain, boolean mustSee) {
            this.brain = brain;
            this.mustSee = mustSee;
            setFlags(EnumSet.of(Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            if (brain.random.nextInt(10) != 0) return false;
            found = null;
            double range = brain.followRange();
            double best = range * range;
            for (Player player : brain.mob.getInstance().getPlayers()) {
                if (player.isDead() || player.getGameMode() == GameMode.CREATIVE
                        || player.getGameMode() == GameMode.SPECTATOR) continue;
                double d = player.getPosition().distanceSquared(brain.mob.getPosition());
                if (d < best && (!mustSee || brain.hasLineOfSight(player))) {
                    best = d;
                    found = player;
                }
            }
            return found != null;
        }

        @Override
        public void start() {
            brain.setTarget(found);
            unseenTicks = 0;
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) return false;
            if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE
                    || p.getGameMode() == GameMode.SPECTATOR)) return false;
            double range = brain.followRange();
            if (brain.mob.getPosition().distanceSquared(target.getPosition()) > range * range) return false;
            if (mustSee) {
                if (brain.hasLineOfSight(target)) {
                    unseenTicks = 0;
                } else if (++unseenTicks > 60) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void stop() {
            brain.setTarget(null);
        }
    }

    /** Iron golem village-defense targeting: nearest hostile mob within range, no line-of-sight requirement. */
    public static class NearestHostileMob extends VGoal {
        private static final Set<net.minestom.server.entity.EntityType> HOSTILE = Set.of(
                net.minestom.server.entity.EntityType.ZOMBIE, net.minestom.server.entity.EntityType.HUSK,
                net.minestom.server.entity.EntityType.DROWNED, net.minestom.server.entity.EntityType.ZOMBIE_VILLAGER,
                net.minestom.server.entity.EntityType.SKELETON, net.minestom.server.entity.EntityType.STRAY,
                net.minestom.server.entity.EntityType.BOGGED, net.minestom.server.entity.EntityType.CREEPER,
                net.minestom.server.entity.EntityType.SPIDER, net.minestom.server.entity.EntityType.WITCH,
                net.minestom.server.entity.EntityType.PILLAGER, net.minestom.server.entity.EntityType.VINDICATOR,
                net.minestom.server.entity.EntityType.EVOKER, net.minestom.server.entity.EntityType.RAVAGER,
                net.minestom.server.entity.EntityType.ENDERMAN, net.minestom.server.entity.EntityType.BLAZE,
                net.minestom.server.entity.EntityType.WITHER_SKELETON, net.minestom.server.entity.EntityType.SILVERFISH,
                net.minestom.server.entity.EntityType.GUARDIAN, net.minestom.server.entity.EntityType.SHULKER,
                net.minestom.server.entity.EntityType.PHANTOM, net.minestom.server.entity.EntityType.HOGLIN);

        private final VBrain brain;
        private LivingEntity found;

        public NearestHostileMob(VBrain brain) {
            this.brain = brain;
            setFlags(EnumSet.of(Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            if (brain.random.nextInt(10) != 0) return false;
            found = null;
            double range = brain.followRange();
            double best = range * range;
            for (Entity e : brain.mob.getInstance().getNearbyEntities(brain.mob.getPosition(), range)) {
                if (!HOSTILE.contains(e.getEntityType()) || !(e instanceof LivingEntity le) || le.isDead()) continue;
                double d = e.getPosition().distanceSquared(brain.mob.getPosition());
                if (d < best) {
                    best = d;
                    found = le;
                }
            }
            return found != null;
        }

        @Override
        public void start() {
            brain.setTarget(found);
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) return false;
            double range = brain.followRange();
            return brain.mob.getPosition().distanceSquared(target.getPosition()) <= range * range;
        }

        @Override
        public void stop() {
            brain.setTarget(null);
        }
    }

    // ---------------------------------------------------------------- owner (taming)

    /** SitWhenOrderedToGoal: while ordered to sit, hold MOVE and stay put — priority 2
     *  outranks LeapAtTarget/MeleeAttack/FollowOwner so a sitting wolf never chases. */
    public static class SitWhenOrdered extends VGoal {
        private final VBrain brain;

        public SitWhenOrdered(VBrain brain) {
            this.brain = brain;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        private boolean sitting() {
            return brain.mob.getEntityMeta()
                    instanceof net.minestom.server.entity.metadata.animal.tameable.TameableAnimalMeta m
                    && m.isSitting();
        }

        @Override
        public boolean canUse() {
            return sitting();
        }

        @Override
        public void start() {
            brain.stopNavigation();
        }

        @Override
        public void tick() {
            brain.stopNavigation();
        }
    }

    /**
     * FollowOwnerGoal + TamableAnimal.tryToTeleportToOwner folded together: follow the
     * tamed mob's owner once farther than startDistance, teleport into a 7x3x7 box
     * around the owner (skipping the inner 3x3) once past TELEPORT_WHEN_DISTANCE_IS_SQ
     * (144, i.e. 12 blocks — decompile-verified constant), stop within stopDistance.
     * Never runs while sitting (mirrors unableToMoveToOwner's isOrderedToSit check).
     */
    public static class FollowOwner extends VGoal {
        private final VBrain brain;
        private final double speedModifier;
        private final float startDistance;
        private final float stopDistance;

        public FollowOwner(VBrain brain, double speedModifier, float startDistance, float stopDistance) {
            this.brain = brain;
            this.speedModifier = speedModifier;
            this.startDistance = startDistance;
            this.stopDistance = stopDistance;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        private Player owner() {
            return dev.pointofpressure.minecom.mobs.Taming.ownerOf(brain.mob);
        }

        private boolean sitting() {
            return brain.mob.getEntityMeta()
                    instanceof net.minestom.server.entity.metadata.animal.tameable.TameableAnimalMeta m
                    && m.isSitting();
        }

        @Override
        public boolean canUse() {
            if (sitting()) return false;
            Player owner = owner();
            if (owner == null || owner.isDead()) return false;
            return brain.mob.getPosition().distanceSquared(owner.getPosition()) >= startDistance * startDistance;
        }

        @Override
        public boolean canContinueToUse() {
            if (sitting()) return false;
            Player owner = owner();
            if (owner == null || owner.isDead() || brain.navigationDone()) return false;
            return brain.mob.getPosition().distanceSquared(owner.getPosition()) > stopDistance * stopDistance;
        }

        @Override
        public void start() {
            Player owner = owner();
            if (owner != null) brain.moveTo(owner.getPosition(), speedModifier);
        }

        @Override
        public void stop() {
            brain.stopNavigation();
        }

        @Override
        public void tick() {
            Player owner = owner();
            if (owner == null) return;
            brain.lookAt(owner);
            if (brain.mob.getPosition().distanceSquared(owner.getPosition()) >= 144.0) {
                teleportToOwner(owner);
            } else if (brain.navigationDone()) {
                brain.moveTo(owner.getPosition(), speedModifier);
            }
        }

        private void teleportToOwner(Player owner) {
            var instance = brain.mob.getInstance();
            for (int attempt = 0; attempt < 10; attempt++) {
                int xd = brain.random.nextInt(7) - 3;
                int zd = brain.random.nextInt(7) - 3;
                if (Math.abs(xd) < 2 && Math.abs(zd) < 2) continue;
                int yd = brain.random.nextInt(3) - 1;
                int x = owner.getPosition().blockX() + xd;
                int y = owner.getPosition().blockY() + yd;
                int z = owner.getPosition().blockZ() + zd;
                if (!instance.isChunkLoaded(x >> 4, z >> 4)) continue;
                var floor = instance.getBlock(x, y - 1, z);
                var at = instance.getBlock(x, y, z);
                var above = instance.getBlock(x, y + 1, z);
                if (floor.isSolid() && !floor.isLiquid() && at.isAir() && above.isAir()) {
                    Pos p = brain.mob.getPosition();
                    brain.mob.teleport(new Pos(x + 0.5, y, z + 0.5, p.yaw(), p.pitch()));
                    brain.stopNavigation();
                    return;
                }
            }
        }
    }

    /**
     * RunAroundLikeCrazyGoal: while ridden and untamed, run wild toward a random
     * nearby point; every ~50 ticks (1-in-50 roll, matching adjustedTickDelay(50))
     * the rider rolls temper/maxTemper — success tames via Riding.tameRoll, failure
     * ejects them with +5 temper. Horse-family only (Riding.HORSE_FAMILY gate lives
     * in the factory that adds this goal, not here).
     */
    public static class RunAroundLikeCrazy extends VGoal {
        private final VBrain brain;
        private final double speedModifier;
        private Pos wanted;

        public RunAroundLikeCrazy(VBrain brain, double speedModifier) {
            this.brain = brain;
            this.speedModifier = speedModifier;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        private boolean tamed() {
            return brain.mob.getEntityMeta()
                    instanceof net.minestom.server.entity.metadata.animal.AbstractHorseMeta m && m.isTamed();
        }

        @Override
        public boolean canUse() {
            if (tamed() || brain.mob.getPassengers().isEmpty()) return false;
            wanted = randomNearbyGround();
            return wanted != null;
        }

        @Override
        public boolean canContinueToUse() {
            return !tamed() && !brain.navigationDone() && !brain.mob.getPassengers().isEmpty();
        }

        @Override
        public void start() {
            brain.moveTo(wanted, speedModifier);
        }

        @Override
        public void stop() {
            brain.stopNavigation();
        }

        @Override
        public void tick() {
            if (tamed() || brain.random.nextInt(50) != 0) return;
            Entity passenger = brain.mob.getPassengers().stream().findFirst().orElse(null);
            if (passenger instanceof Player player) {
                dev.pointofpressure.minecom.mobs.Riding.tameRoll(brain.mob, player);
            }
        }

        private Pos randomNearbyGround() {
            var instance = brain.mob.getInstance();
            Pos origin = brain.mob.getPosition();
            for (int attempt = 0; attempt < 10; attempt++) {
                int dx = brain.random.nextInt(21) - 10;
                int dz = brain.random.nextInt(21) - 10;
                int x = origin.blockX() + dx, z = origin.blockZ() + dz;
                if (!instance.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = origin.blockY();
                int guard = 0;
                while (y > -60 && instance.getBlock(x, y - 1, z).isAir() && guard++ < 8) y--;
                var floor = instance.getBlock(x, y - 1, z);
                var at = instance.getBlock(x, y, z);
                if (floor.isSolid() && !floor.isLiquid() && at.isAir()) {
                    return new Pos(x + 0.5, y, z + 0.5);
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- special attacks

    /** LeapAtTargetGoal: spiders lunge with 0.4 upward velocity when 4-16 blocks away and grounded. */
    public static class LeapAtTarget extends VGoal {
        private final VBrain brain;
        private final float yd;

        public LeapAtTarget(VBrain brain, float yd) {
            this.brain = brain;
            this.yd = yd;
            setFlags(EnumSet.of(Flag.JUMP, Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = brain.target;
            if (target == null || !brain.mob.isOnGround()) return false;
            double d = brain.mob.getPosition().distanceSquared(target.getPosition());
            return d >= 4 && d <= 16 && brain.random.nextInt(5) == 0;
        }

        @Override
        public boolean canContinueToUse() {
            return !brain.mob.isOnGround();
        }

        @Override
        public void start() {
            LivingEntity target = brain.target;
            if (target == null) return;
            Vec delta = target.getPosition().sub(brain.mob.getPosition()).asVec();
            Vec horizontal = new Vec(delta.x(), 0, delta.z());
            if (horizontal.length() > 0.01) {
                Vec leap = horizontal.normalize().mul(8).add(brain.mob.getVelocity().mul(0.2));
                brain.mob.setVelocity(new Vec(leap.x(), yd * 20, leap.z()));
            }
        }
    }

    /** Skeleton bow AI: keep ~15 block range, strafe while shooting every 20 ticks. */
    public static class BowAttack extends VGoal {
        private final VBrain brain;
        private final double speedModifier;
        private final int attackInterval;
        private final float attackRadius;
        private int attackTime = -1;
        private int strafingTime = -1;
        private boolean strafingClockwise;

        public BowAttack(VBrain brain, double speedModifier, int attackInterval, float attackRadius) {
            this.brain = brain;
            this.speedModifier = speedModifier;
            this.attackInterval = attackInterval;
            this.attackRadius = attackRadius;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return brain.target != null && !brain.target.isDead();
        }

        @Override
        public void stop() {
            brain.stopNavigation();
            attackTime = -1;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = brain.target;
            if (target == null) return;
            double distSq = brain.mob.getPosition().distanceSquared(target.getPosition());
            boolean seen = brain.hasLineOfSight(target);

            if (distSq <= attackRadius * attackRadius && seen) {
                brain.stopNavigation();
                if (++strafingTime > 20 && brain.random.nextFloat() < 0.3f) {
                    strafingClockwise = !strafingClockwise;
                }
                // strafe sideways around the target
                Vec toTarget = target.getPosition().sub(brain.mob.getPosition()).asVec();
                Vec side = new Vec(-toTarget.z(), 0, toTarget.x()).normalize()
                        .mul(strafingClockwise ? 0.35 : -0.35);
                brain.mob.setVelocity(brain.mob.getVelocity().add(side.mul(2)));
            } else {
                brain.moveTo(target.getPosition(), speedModifier);
                strafingTime = -1;
            }

            brain.lookAt(target);
            if (--attackTime <= 0) {
                if (!seen) return;
                attackTime = attackInterval;
                shootArrow(target);
            }
        }

        private void shootArrow(LivingEntity target) {
            var arrow = new net.minestom.server.entity.EntityProjectile(brain.mob,
                    net.minestom.server.entity.EntityType.ARROW);
            Pos from = brain.mob.getPosition().add(0, brain.mob.getEyeHeight() - 0.1, 0);
            arrow.setInstance(brain.mob.getInstance(), from);
            Vec direction = target.getPosition().add(0, target.getEyeHeight() / 2, 0)
                    .sub(from).asVec().normalize();
            arrow.setVelocity(direction.mul(32).add(0, 3, 0));
        }
    }

    /**
     * RangedCrossbowAttackGoal: unlike a bow's fixed-interval shot, a crossbow visibly charges
     * (25 ticks, CrossbowItem.getChargeDuration at 1.25s/no enchant) before a 20-39 tick
     * (20+random.nextInt(20)) post-charge delay, then fires once ready and back in sight —
     * a real vanilla pillager/illusioner/witch-adjacent AI shape, not a skeleton's bow rhythm.
     * setChargingCrossbow drives the client-visible raised-crossbow pose during CHARGING.
     */
    public static class CrossbowAttack extends VGoal {
        private enum State { UNCHARGED, CHARGING, CHARGED, READY_TO_ATTACK }

        private final VBrain brain;
        private final double speedModifier;
        private final float attackRadiusSqr;
        private State state = State.UNCHARGED;
        private int seeTime;
        private int attackDelay;
        private int updatePathDelay;

        public CrossbowAttack(VBrain brain, double speedModifier, float attackRadius) {
            this.brain = brain;
            this.speedModifier = speedModifier;
            this.attackRadiusSqr = attackRadius * attackRadius;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return brain.target != null && !brain.target.isDead();
        }

        @Override
        public void stop() {
            brain.stopNavigation();
            seeTime = 0;
            if (state != State.UNCHARGED) {
                brain.mob.refreshActiveHand(false, false, false);
                setCharging(false);
            }
            state = State.UNCHARGED;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = brain.target;
            if (target == null) return;
            boolean seen = brain.hasLineOfSight(target);
            boolean hadSeen = seeTime > 0;
            if (seen != hadSeen) seeTime = 0;
            seeTime += seen ? 1 : -1;

            double distSq = brain.mob.getPosition().distanceSquared(target.getPosition());
            boolean needsToMove = (distSq > attackRadiusSqr || seeTime < 5) && attackDelay == 0;
            if (needsToMove) {
                if (--updatePathDelay <= 0) {
                    brain.moveTo(target.getPosition(), state == State.UNCHARGED ? speedModifier : speedModifier * 0.5);
                    updatePathDelay = 20 + brain.random.nextInt(21); // PATHFINDING_DELAY_RANGE: rangeOfSeconds(1,2) = uniform[20,40]
                }
            } else {
                updatePathDelay = 0;
                brain.stopNavigation();
            }
            brain.lookAt(target);

            switch (state) {
                case UNCHARGED -> {
                    if (!needsToMove) {
                        brain.mob.refreshActiveHand(true, false, false);
                        setCharging(true);
                        state = State.CHARGING;
                        chargeTicks = 0;
                    }
                }
                case CHARGING -> {
                    chargeTicks++;
                    if (chargeTicks >= CHARGE_DURATION_TICKS) {
                        brain.mob.refreshActiveHand(false, false, false);
                        setCharging(false);
                        state = State.CHARGED;
                        attackDelay = 20 + brain.random.nextInt(20);
                    }
                }
                case CHARGED -> {
                    if (--attackDelay <= 0) state = State.READY_TO_ATTACK;
                }
                case READY_TO_ATTACK -> {
                    if (seen) {
                        shootArrow(target);
                        state = State.UNCHARGED;
                    }
                }
            }
        }

        private int chargeTicks;
        private static final int CHARGE_DURATION_TICKS = 25; // CrossbowItem.getChargeDuration: floor(1.25f * 20)

        private void setCharging(boolean charging) {
            if (brain.mob.getEntityMeta() instanceof net.minestom.server.entity.metadata.monster.raider.AbstractIllagerMeta illager) {
                if (illager instanceof net.minestom.server.entity.metadata.monster.raider.PillagerMeta pillager) {
                    pillager.setChargingCrossbow(charging);
                }
            }
        }

        private void shootArrow(LivingEntity target) {
            var arrow = new net.minestom.server.entity.EntityProjectile(brain.mob,
                    net.minestom.server.entity.EntityType.ARROW);
            Pos from = brain.mob.getPosition().add(0, brain.mob.getEyeHeight() - 0.1, 0);
            arrow.setInstance(brain.mob.getInstance(), from);
            Vec direction = target.getPosition().add(0, target.getEyeHeight() / 2, 0)
                    .sub(from).asVec().normalize();
            arrow.setVelocity(direction.mul(32).add(0, 3, 0));
        }
    }

    /** Creeper swell driven from SwellGoal: start under 3 blocks, abort beyond 7 or lost sight. */
    public static class Swell extends VGoal {
        private final VBrain brain;
        private final CreeperState state;
        private LivingEntity swellTarget;

        public interface CreeperState {
            void setSwellDir(int dir);
        }

        public Swell(VBrain brain, CreeperState state) {
            this.brain = brain;
            this.state = state;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = brain.target;
            return target != null && !target.isDead()
                    && brain.mob.getPosition().distanceSquared(target.getPosition()) < 9;
        }

        @Override
        public void start() {
            brain.stopNavigation();
            swellTarget = brain.target;
        }

        @Override
        public void stop() {
            swellTarget = null;
            state.setSwellDir(-1);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (swellTarget == null || swellTarget.isDead()
                    || brain.mob.getPosition().distanceSquared(swellTarget.getPosition()) > 49
                    || !brain.hasLineOfSight(swellTarget)) {
                state.setSwellDir(-1);
            } else {
                state.setSwellDir(1);
            }
        }
    }
}
