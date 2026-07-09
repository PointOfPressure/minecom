package dev.pointofpressure.minecom.mobs.ai;

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
                for (Entity entity : brain.mob.getInstance().getEntities()) {
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
