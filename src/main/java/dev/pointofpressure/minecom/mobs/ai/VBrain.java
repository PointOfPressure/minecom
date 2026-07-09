package dev.pointofpressure.minecom.mobs.ai;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.pathfinding.followers.GroundNodeFollower;
import net.minestom.server.entity.pathfinding.followers.NodeFollower;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.TaskSchedule;

import dev.pointofpressure.minecom.mobs.path.VPathfinder;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Vanilla mob brain: two goal selectors (behaviour + targeting) with the exact
 * arbitration model from the reference — running goals stop when they can't
 * continue or get preempted by a higher-priority goal sharing a control flag.
 * Also carries the vanilla per-mob state: target, last-hurt-by, aggression.
 */
public final class VBrain {
    public final EntityCreature mob;
    public final Random random = new Random();
    public final double baseSpeed;

    private final List<Wrapped> goals = new ArrayList<>();
    private final List<Wrapped> targetGoals = new ArrayList<>();
    private final Map<VGoal.Flag, Wrapped> lockedFlags = new EnumMap<>(VGoal.Flag.class);

    public LivingEntity target;
    public LivingEntity lastHurtBy;
    public long lastHurtTimestamp = -1;
    public long gameTime;
    public boolean aggressive;

    // Vanilla ground pathfinding (VPathfinder computes the route; a Minestom
    // GroundNodeFollower drives the step/jump physics along it each tick).
    private VPathfinder.MobProfile mobProfile;
    private NodeFollower follower;
    private List<Point> vanillaPath;
    private int pathIndex;
    private Point pathDestination;

    private static final class Wrapped {
        final int priority;
        final VGoal goal;
        boolean running;

        Wrapped(int priority, VGoal goal) {
            this.priority = priority;
            this.goal = goal;
        }

        boolean canBeReplacedBy(Wrapped other) {
            return goal.isInterruptable() && other.priority < priority;
        }
    }

    public VBrain(EntityCreature mob) {
        this.mob = mob;
        this.baseSpeed = mob.getAttributeValue(Attribute.MOVEMENT_SPEED);
        mob.scheduler().buildTask(this::tick).repeat(TaskSchedule.tick(1)).schedule();
    }

    public void addGoal(int priority, VGoal goal) {
        goals.add(new Wrapped(priority, goal));
    }

    public void addTargetGoal(int priority, VGoal goal) {
        targetGoals.add(new Wrapped(priority, goal));
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (mob.isDead() || mob.getInstance() == null) return;
        try {
            gameTime++;
            tickSelector(targetGoals);
            tickSelector(goals);
            followPath();
        } catch (NullPointerException ignored) {
            // The entry check above narrows but can't close a genuine cross-thread race:
            // something outside this tick thread (test teardown, or any other code calling
            // entity.remove() directly rather than through the tick thread's own task
            // queue) can null the mob's instance at any point during this method — inside
            // any goal's canContinueToUse/canUse/start/tick, or inside followPath. Chasing
            // every individual dereference site with its own pre-check or try-catch doesn't
            // scale (goal implementations call arbitrary Minestom APIs that assume a live
            // instance) — one catch around the whole tick body treats "mob vanished mid-
            // tick" uniformly as "nothing to do this tick" instead of crashing the tick
            // thread. Real logic bugs that happen to throw NPE are the accepted tradeoff;
            // none have shown up here since every prior occurrence traced to this exact race.
        }
    }

    /** The vanilla GoalSelector.tick() algorithm. */
    private void tickSelector(List<Wrapped> selector) {
        for (Wrapped w : selector) {
            if (w.running && !w.goal.canContinueToUse()) {
                w.running = false;
                w.goal.stop();
            }
        }
        lockedFlags.entrySet().removeIf(e -> !e.getValue().running);

        for (Wrapped w : selector) {
            if (w.running || !canBeReplacedForAllFlags(w) || !w.goal.canUse()) continue;
            for (VGoal.Flag flag : w.goal.getFlags()) {
                Wrapped current = lockedFlags.get(flag);
                if (current != null && current.running) {
                    current.running = false;
                    current.goal.stop();
                }
                lockedFlags.put(flag, w);
            }
            w.running = true;
            w.goal.start();
        }

        for (Wrapped w : selector) {
            if (w.running) w.goal.tick();
        }
    }

    private boolean canBeReplacedForAllFlags(Wrapped candidate) {
        for (VGoal.Flag flag : candidate.goal.getFlags()) {
            Wrapped current = lockedFlags.get(flag);
            if (current != null && current.running && !current.canBeReplacedBy(candidate)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ vanilla mob services

    public void setTarget(LivingEntity newTarget) {
        this.target = newTarget;
    }

    public void hurtBy(LivingEntity attacker) {
        this.lastHurtBy = attacker;
        this.lastHurtTimestamp = gameTime;
    }

    public double followRange() {
        double value = mob.getAttributeValue(Attribute.FOLLOW_RANGE);
        return value > 0 ? value : 16;
    }

    /** Sampled block-occlusion line of sight between eye positions. */
    public boolean hasLineOfSight(LivingEntity other) {
        Instance instance = mob.getInstance();
        if (instance == null || other.getInstance() != instance) return false;
        Vec from = mob.getPosition().add(0, mob.getEyeHeight(), 0).asVec();
        Vec to = other.getPosition().add(0, other.getEyeHeight(), 0).asVec();
        Vec delta = to.sub(from);
        double length = delta.length();
        if (length < 0.01) return true;
        Vec step = delta.normalize().mul(0.5);
        Vec at = from;
        for (double d = 0; d < length; d += 0.5) {
            at = at.add(step);
            if (!instance.isChunkLoaded(at.blockX() >> 4, at.blockZ() >> 4)) return false;
            var block = instance.getBlock(at);
            if (block.isSolid() && block.registry().occludes()) return false;
        }
        return true;
    }

    public void lookAt(Point point) {
        Pos pos = mob.getPosition();
        double dx = point.x() - pos.x();
        double dy = point.y() - (pos.y() + mob.getEyeHeight());
        double dz = point.z() - pos.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        mob.setView(yaw, pitch);
    }

    public void lookAt(LivingEntity entity) {
        lookAt(entity.getPosition().add(0, entity.getEyeHeight(), 0));
    }

    // navigation with vanilla speed modifiers, routed by the vanilla A* port
    public void moveTo(Point point, double speedModifier) {
        mob.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(baseSpeed * speedModifier);
        Instance instance = mob.getInstance();
        if (instance == null) return;
        List<Point> path = VPathfinder.findPath(
                instance, mob, profile(), point, (float) followRange());
        this.vanillaPath = path;
        this.pathIndex = 0;
        this.pathDestination = point;
        if (follower == null) follower = new GroundNodeFollower(mob);
    }

    public void stopNavigation() {
        mob.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(baseSpeed);
        this.vanillaPath = null;
        this.pathDestination = null;
        this.pathIndex = 0;
    }

    public boolean navigationDone() {
        return vanillaPath == null || pathIndex >= vanillaPath.size();
    }

    public Point navigationGoal() {
        return pathDestination;
    }

    /** Vanilla Mob bounding box → pathing profile (cached; land mob, no float). */
    private VPathfinder.MobProfile profile() {
        if (mobProfile == null) {
            var bb = mob.getBoundingBox();
            mobProfile = new VPathfinder.MobProfile(
                    (float) bb.width(), (float) bb.height(), false);
        }
        return mobProfile;
    }

    /** Drive the mob one step along the vanilla route. Called every tick. */
    private void followPath() {
        if (vanillaPath == null || follower == null) return;
        if (pathIndex >= vanillaPath.size()) {
            stopNavigation();
            return;
        }
        Point waypoint = vanillaPath.get(pathIndex);
        if (follower.isAtPoint(waypoint)) {
            pathIndex++;
            if (pathIndex >= vanillaPath.size()) {
                stopNavigation();
                return;
            }
            waypoint = vanillaPath.get(pathIndex);
        }
        // jump up a full block (step-up beyond the 0.6 auto-step) when the next
        // waypoint sits above the mob and it is grounded — mirrors vanilla. (A concurrent
        // removal here surfaces as NullPointerException, caught by tick()'s single
        // top-level catch — see its comment for why that's the right place for it.)
        if (waypoint.y() - mob.getPosition().y() >= 1.0 && mob.isOnGround()) {
            follower.jump(waypoint, waypoint);
        }
        follower.moveTowards(waypoint, follower.movementSpeed(), waypoint);
    }

    /** Vanilla melee reach: attack box expanded 0.8 horizontally intersects the target. */
    public boolean isWithinMeleeAttackRange(LivingEntity other) {
        double reach = mob.getEntityType().registry().width() / 2 + 0.8
                + other.getEntityType().registry().width() / 2;
        double dx = mob.getPosition().x() - other.getPosition().x();
        double dz = mob.getPosition().z() - other.getPosition().z();
        double dy = Math.abs(mob.getPosition().y() - other.getPosition().y());
        return dx * dx + dz * dz <= reach * reach && dy <= 2.0;
    }
}
