package dev.pointofpressure.minecom.mobs.ai;

import dev.pointofpressure.minecom.redstone.Vibrations;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.EntityProjectile;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.entity.metadata.monster.WardenMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Warden, ported from the decompiled 26.1.2 reference: Warden.java,
 * WardenAi.java, AngerManagement.java, AngerLevel.java, and the
 * behavior/warden package (Emerging, Digging, Roar, Sniffing, TryToSniff,
 * SetRoarTarget, SonicBoom), plus SpawnUtil.trySpawnMob for the shrieker
 * summon placement. The brain-activity choreography is flattened into one
 * explicit state machine (EMERGING/DIGGING/ROARING/SNIFFING/SONIC_BOOM +
 * the IDLE/INVESTIGATE/FIGHT default) with vanilla durations, cooldowns,
 * anger arithmetic and target arbitration; navigation and hurt tracking run
 * through the shared {@link VBrain} (no goals — the state machine drives
 * moveTo directly).
 * Simplifications (see AUDIT.md): digging/emerging invulnerability is total
 * (vanilla exempts BYPASSES_INVULNERABILITY damage), the sonic-boom particle
 * trail and block-digging particles are not sent, anger is session-scoped
 * (no UUID re-resolution across saves), and the boom's shield-disable is
 * whatever the damage-type tags already do in Combat.
 */
public final class WardenMob {

    // AngerLevel thresholds + AngerManagement caps
    public static final int ANGRY = 80;
    public static final int AGITATED = 40;
    public static final int MAX_ANGER = 150;

    // Warden.java constants
    private static final int VIBRATION_COOLDOWN = 40;
    private static final int DEFAULT_ANGER = 35;
    private static final int PROJECTILE_ANGER = 10;
    private static final int ON_HURT_ANGER = ANGRY + 20;
    private static final int RECENT_PROJECTILE_TICKS = 100;
    private static final int TOUCH_COOLDOWN = 20;
    private static final int DARKNESS_INTERVAL = 120;
    private static final int DARKNESS_RADIUS = 20;

    // WardenAi durations (gt)
    private static final int EMERGE_DURATION = 134;
    private static final int ROAR_DURATION = 84;
    private static final int SNIFF_DURATION = 84;
    private static final int DIG_DURATION = 100;
    private static final int DIG_COOLDOWN = 1200;
    private static final int DISTURBANCE_EXPIRY = 100;
    private static final int MELEE_COOLDOWN = 18;

    // SonicBoom behavior
    private static final int SONIC_DURATION = 60;
    private static final int SONIC_SOUND_AT = 34;
    private static final int SONIC_COOLDOWN = 40;
    private static final int SONIC_COOLDOWN_AFTER_TARGET_SWITCH = 200;
    private static final double SONIC_RANGE_XZ = 15, SONIC_RANGE_Y = 20;

    private static final Map<Integer, WardenMob> WARDENS = new ConcurrentHashMap<>();

    private enum State { EMERGING, IDLE, SNIFFING, ROARING, SONIC_BOOM, DIGGING }

    private final EntityCreature mob;
    private final VBrain brain;

    private State state;
    private int stateTicks;
    private long age;

    // thread: written on this warden's entity tick thread only; read cross-thread
    // by the playtest via angerAt() (benign racy read of an int value)
    /** AngerManagement.angerBySuspect (session-scoped: entities only, no UUIDs). */
    private final Map<LivingEntity, Integer> anger = new HashMap<>();
    private LivingEntity attackTarget;
    private LivingEntity roarTarget;
    private Point disturbance;
    private long disturbanceUntil;

    private long vibrationCooldownUntil;
    private long sonicCooldownUntil;
    private long sniffCooldownUntil;
    private long touchCooldownUntil;
    private long digAllowedAt;
    private long recentProjectileUntil;
    private long meleeReadyAt;
    private long lastSeenHurtTimestamp = -1;
    private int pathRecalcIn;

    public static WardenMob of(Entity entity) {
        return WARDENS.get(entity.getEntityId());
    }

    /** Any live warden within `radius` of `pos` (WardenSpawnTracker.hasNearbyWarden). */
    public static boolean anyWithin(Instance instance, Point pos, double radius) {
        for (WardenMob w : WARDENS.values()) {
            if (w.mob.getInstance() == instance && !w.mob.isRemoved()
                    && w.mob.getPosition().distance(pos) <= radius) return true;
        }
        return false;
    }

    public static boolean anyListening() {
        return !WARDENS.isEmpty();
    }

    /**
     * SpawnUtil.trySpawnMob(WARDEN, TRIGGERED, pos, 20, 5, 6, ON_TOP_OF_COLLIDER):
     * 20 attempts at ±5 xz; from +6 blocks up, walk down through 13 candidate
     * cells to the first solid-topped block with room above, and emerge there.
     */
    public static EntityCreature trySummon(Instance instance, Point around) {
        var rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 20; attempt++) {
            int x = around.blockX() + rng.nextInt(-5, 6);
            int z = around.blockZ() + rng.nextInt(-5, 6);
            for (int y = around.blockY() + 6; y >= around.blockY() - 6; y--) {
                if (!instance.isChunkLoaded(x >> 4, z >> 4)) break;
                var floor = instance.getBlock(x, y - 1, z);
                if (!floor.isSolid()) continue;
                // ON_TOP_OF_COLLIDER + the warden's 0.9x2.9 spawn box above it
                if (instance.getBlock(x, y, z).isSolid() || instance.getBlock(x, y + 1, z).isSolid()
                        || instance.getBlock(x, y + 2, z).isSolid()) break;
                return spawn(instance, new Pos(x + 0.5, y, z + 0.5), true);
            }
        }
        return null;
    }

    /** Warden.createAttributes + finalizeSpawn (emerging = the TRIGGERED spawn reason). */
    public static EntityCreature spawn(Instance instance, Pos pos, boolean emerging) {
        EntityCreature mob = new EntityCreature(EntityType.WARDEN);
        VBrain brain = VanillaMobs.brain(mob, 0.3, 24, 30, 500, 0);
        mob.getAttribute(Attribute.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        new WardenMob(mob, brain, emerging);
        mob.setInstance(instance, pos);
        if (emerging) {
            sound(instance, pos, SoundEvent.ENTITY_WARDEN_EMERGE, 5f);
        }
        return mob;
    }

    private WardenMob(EntityCreature mob, VBrain brain, boolean emerging) {
        this.mob = mob;
        this.brain = brain;
        this.digAllowedAt = DIG_COOLDOWN; // finalizeSpawn: DIG_COOLDOWN 1200 on any spawn
        if (emerging) {
            this.state = State.EMERGING;
            mob.setPose(EntityPose.EMERGING);
            mob.setInvulnerable(true);
        } else {
            this.state = State.IDLE;
        }
        WARDENS.put(mob.getEntityId(), this);
        mob.scheduler().buildTask(this::tick).repeat(TaskSchedule.tick(1)).schedule();
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (mob.isRemoved() || mob.isDead() || mob.getInstance() == null) {
            WARDENS.remove(mob.getEntityId());
            return;
        }
        age++;
        stateTicks++;
        consumeHurt();
        if (age % 20 == 0) angerTick();
        // Warden.customServerAiStep: darkness pulse every 120gt around the warden
        if ((age + mob.getEntityId()) % DARKNESS_INTERVAL == 0) {
            applyDarknessAround(mob.getInstance(), mob.getPosition(), DARKNESS_RADIUS);
        }
        if (disturbance != null && age > disturbanceUntil) disturbance = null;
        switch (state) {
            case EMERGING -> tickEmerging();
            case DIGGING -> tickDigging();
            case ROARING -> tickRoaring();
            case SNIFFING -> tickSniffing();
            case SONIC_BOOM -> tickSonicBoom();
            case IDLE -> tickDefault();
        }
        if (state != State.EMERGING && state != State.DIGGING) tickTouch();
    }

    private void enter(State next) {
        state = next;
        stateTicks = 0;
    }

    private void tickEmerging() {
        if (stateTicks >= EMERGE_DURATION) {
            mob.setPose(EntityPose.STANDING);
            mob.setInvulnerable(false);
            enter(State.IDLE);
        }
    }

    private void tickDigging() {
        if (stateTicks >= DIG_DURATION) {
            WARDENS.remove(mob.getEntityId());
            mob.remove();
        }
    }

    private void tickRoaring() {
        if (roarTarget == null || !canTarget(roarTarget)) {
            mob.setPose(EntityPose.STANDING);
            roarTarget = null;
            enter(State.IDLE);
            return;
        }
        brain.lookAt(roarTarget);
        if (stateTicks == 25) { // ROAR_SOUND_DELAY
            sound(mob.getInstance(), mob.getPosition(), SoundEvent.ENTITY_WARDEN_ROAR, 3f);
        }
        if (stateTicks >= ROAR_DURATION) {
            mob.setPose(EntityPose.STANDING);
            setAttackTarget(roarTarget);
            roarTarget = null;
            enter(State.IDLE);
        }
    }

    private void tickSniffing() {
        if (stateTicks >= SNIFF_DURATION) {
            mob.setPose(EntityPose.STANDING);
            enter(State.IDLE);
            // Sniffing.stop: anger at the nearest attackable within 6 xz / 20 y
            LivingEntity nearest = nearestAttackable();
            if (nearest != null) {
                if (closerThan(nearest, 6.0, 20.0)) increaseAnger(nearest, DEFAULT_ANGER, true);
                if (disturbance == null) setDisturbance(nearest.getPosition());
            }
        }
    }

    private void tickSonicBoom() {
        LivingEntity target = attackTarget;
        if (target != null) brain.lookAt(target);
        if (stateTicks == SONIC_SOUND_AT) {
            if (target != null && canTarget(target) && closerThan(target, SONIC_RANGE_XZ, SONIC_RANGE_Y)) {
                sound(mob.getInstance(), mob.getPosition(), SoundEvent.ENTITY_WARDEN_SONIC_BOOM, 3f);
                if (target.damage(DamageType.SONIC_BOOM, 10f)) {
                    double kbRes = target.getAttributeValue(Attribute.KNOCKBACK_RESISTANCE);
                    Vec dir = Vec.fromPoint(target.getPosition().add(0, target.getEyeHeight(), 0)
                            .sub(mob.getPosition().add(0, 1.6, 0))).normalize();
                    target.setVelocity(target.getVelocity().add(
                            dir.x() * 2.5 * (1 - kbRes) * 20,
                            dir.y() * 0.5 * (1 - kbRes) * 20,
                            dir.z() * 2.5 * (1 - kbRes) * 20));
                }
            }
        }
        if (stateTicks >= SONIC_DURATION) {
            sonicCooldownUntil = age + SONIC_COOLDOWN;
            enter(State.IDLE);
        }
    }

    /** The IDLE/INVESTIGATE/FIGHT activity arbitration from WardenAi.updateActivity. */
    private void tickDefault() {
        if (attackTarget != null && (!canTarget(attackTarget) || anger.getOrDefault(attackTarget, 0) < ANGRY)) {
            // StopAttackingIfTargetInvalid + onTargetInvalid
            if (!canTarget(attackTarget)) anger.remove(attackTarget);
            attackTarget = null;
            brain.setTarget(null);
            brain.stopNavigation();
            digAllowedAt = age + DIG_COOLDOWN;
        }

        if (attackTarget != null) {
            tickFight();
            return;
        }

        // SetRoarTarget: angry at the top suspect -> roar first, then fight
        LivingEntity angryAt = entityAngryAt();
        if (angryAt != null) {
            roarTarget = angryAt;
            digAllowedAt = age + DIG_COOLDOWN;
            increaseAnger(angryAt, 20, false); // Roar.start: ROAR_ANGER_INCREASE
            brain.stopNavigation();
            brain.lookAt(angryAt);
            mob.setPose(EntityPose.ROARING);
            enter(State.ROARING);
            return;
        }

        if (disturbance != null) {
            // INVESTIGATE: GoToTargetLocation(DISTURBANCE_LOCATION, 2, 0.7)
            brain.lookAt(disturbance);
            if (mob.getPosition().distance(disturbance) <= 2) {
                disturbance = null;
                brain.stopNavigation();
            } else if (--pathRecalcIn <= 0) {
                pathRecalcIn = 10;
                brain.moveTo(disturbance, 0.7);
            }
            return;
        }

        // Digging: 1200gt of calm -> burrow back down (immediate despawn if airborne).
        // Vanilla activity order puts DIG ahead of SNIFF (WardenAi.updateActivity).
        if (age >= digAllowedAt) {
            if (mob.isOnGround()) {
                brain.stopNavigation();
                mob.setPose(EntityPose.DIGGING);
                mob.setInvulnerable(true);
                sound(mob.getInstance(), mob.getPosition(), SoundEvent.ENTITY_WARDEN_DIG, 5f);
                enter(State.DIGGING);
            } else {
                sound(mob.getInstance(), mob.getPosition(), SoundEvent.ENTITY_WARDEN_AGITATED, 5f);
                WARDENS.remove(mob.getEntityId());
                mob.remove();
            }
            return;
        }

        // TryToSniff: cooldown 100-200gt, needs a nearby attackable
        if (age >= sniffCooldownUntil && nearestAttackable() != null) {
            sniffCooldownUntil = age + 100 + brain.random.nextInt(101);
            brain.stopNavigation();
            mob.setPose(EntityPose.SNIFFING);
            sound(mob.getInstance(), mob.getPosition(), SoundEvent.ENTITY_WARDEN_SNIFF, 5f);
            enter(State.SNIFFING);
            return;
        }

        // RandomStroll at 0.5, vanilla 1/120 cadence
        if (brain.navigationDone() && brain.random.nextInt(120) == 0) {
            stroll();
        }
    }

    private void tickFight() {
        LivingEntity target = attackTarget;
        digAllowedAt = age + DIG_COOLDOWN; // DIG_COOLDOWN_SETTER runs while fighting
        brain.setTarget(target);
        brain.lookAt(target);

        if (age >= sonicCooldownUntil && closerThan(target, SONIC_RANGE_XZ, SONIC_RANGE_Y)) {
            brain.stopNavigation();
            mob.triggerStatus((byte) 62); // sonic-boom charge animation
            sound(mob.getInstance(), mob.getPosition(), SoundEvent.ENTITY_WARDEN_SONIC_CHARGE, 3f);
            enter(State.SONIC_BOOM);
            return;
        }

        // SetWalkTargetFromAttackTargetIfTargetOutOfReach(1.2) + MeleeAttack(18)
        if (--pathRecalcIn <= 0) {
            pathRecalcIn = 4 + brain.random.nextInt(7);
            brain.moveTo(target.getPosition(), 1.2);
        }
        if (age >= meleeReadyAt && brain.isWithinMeleeAttackRange(target)) {
            meleeReadyAt = age + MELEE_COOLDOWN;
            sonicCooldownUntil = Math.max(sonicCooldownUntil, age + SONIC_COOLDOWN); // doHurtTarget
            mob.triggerStatus((byte) 4);
            sound(mob.getInstance(), mob.getPosition(), SoundEvent.ENTITY_WARDEN_ATTACK_IMPACT, 10f);
            mob.attack(target, true);
        }
    }

    /** Warden.doPush: touching entities anger it (20gt cooldown) and mark a disturbance. */
    private void tickTouch() {
        if (age < touchCooldownUntil) return;
        for (Entity e : mob.getInstance().getNearbyEntities(mob.getPosition(), 1.5)) {
            if (!(e instanceof LivingEntity living) || !canTarget(living)) continue;
            touchCooldownUntil = age + TOUCH_COOLDOWN;
            increaseAnger(living, DEFAULT_ANGER, true);
            setDisturbance(living.getPosition());
            break;
        }
    }

    private void stroll() {
        var rng = brain.random;
        Pos base = mob.getPosition();
        int x = base.blockX() + rng.nextInt(21) - 10;
        int z = base.blockZ() + rng.nextInt(21) - 10;
        Instance instance = mob.getInstance();
        if (!instance.isChunkLoaded(x >> 4, z >> 4)) return;
        for (int y = base.blockY() + 3; y >= base.blockY() - 3; y--) {
            if (instance.getBlock(x, y - 1, z).isSolid() && !instance.getBlock(x, y, z).isSolid()) {
                brain.moveTo(new Vec(x + 0.5, y, z + 0.5), 0.5);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ anger management

    /** AngerManagement.tick (20gt cadence): decay 1/s, prune invalid, resync the client. */
    private void angerTick() {
        anger.entrySet().removeIf(e -> {
            int decayed = e.getValue() - 1;
            if (decayed <= 0 || e.getKey().isRemoved() || e.getKey().isDead() || !canTarget(e.getKey())) return true;
            e.setValue(decayed);
            return false;
        });
        if (mob.getEntityMeta() instanceof WardenMeta meta) {
            meta.setAngerLevel(activeAnger());
        }
    }

    /** Warden.increaseAngerAt: cap 150, retarget players, reset the dig clock. */
    public void increaseAnger(LivingEntity entity, int amount, boolean playSound) {
        if (!canTarget(entity)) return;
        digAllowedAt = age + DIG_COOLDOWN;
        boolean maybeSwitchTarget = !(attackTarget instanceof Player);
        int newAnger = Math.min(MAX_ANGER, anger.getOrDefault(entity, 0) + amount);
        anger.put(entity, newAnger);
        if (entity instanceof Player && maybeSwitchTarget && newAnger >= ANGRY) {
            attackTarget = null;
        }
        if (playSound && state != State.ROARING) {
            sound(mob.getInstance(), mob.getPosition(), activeAnger() >= AGITATED
                    ? SoundEvent.ENTITY_WARDEN_LISTENING_ANGRY : SoundEvent.ENTITY_WARDEN_LISTENING, 10f);
        }
    }

    /** AngerManagement suspect order: angry first, then players, then most anger. */
    private LivingEntity topSuspect() {
        List<Map.Entry<LivingEntity, Integer>> entries = new ArrayList<>(anger.entrySet());
        entries.sort((a, b) -> compareSuspects(
                a.getKey() instanceof Player, a.getValue(),
                b.getKey() instanceof Player, b.getValue()));
        for (var e : entries) {
            if (canTarget(e.getKey())) return e.getKey();
        }
        return null;
    }

    /** AngerManagement.Sorter, extracted pure for SelfTest. Negative = a first. */
    public static int compareSuspects(boolean aPlayer, int aAnger, boolean bPlayer, int bAnger) {
        boolean aAngry = aAnger >= ANGRY, bAngry = bAnger >= ANGRY;
        if (aAngry != bAngry) return aAngry ? -1 : 1;
        if (aPlayer != bPlayer) return aPlayer ? -1 : 1;
        return Integer.compare(bAnger, aAnger);
    }

    /** Warden.getEntityAngryAt: the top suspect, only once its anger is ANGRY. */
    private LivingEntity entityAngryAt() {
        LivingEntity top = topSuspect();
        return top != null && anger.getOrDefault(top, 0) >= ANGRY ? top : null;
    }

    private int activeAnger() {
        if (attackTarget != null) return anger.getOrDefault(attackTarget, 0);
        int highest = 0;
        for (int a : anger.values()) highest = Math.max(highest, a);
        return highest;
    }

    /** Warden.setAttackTarget: 200gt sonic grace on a fresh target. */
    private void setAttackTarget(LivingEntity target) {
        attackTarget = target;
        brain.setTarget(target);
        sonicCooldownUntil = Math.max(sonicCooldownUntil, age + SONIC_COOLDOWN_AFTER_TARGET_SWITCH);
    }

    /** Entity.closerThan(entity, horizontal, vertical): cylindrical range check. */
    private boolean closerThan(LivingEntity other, double horizontal, double vertical) {
        double dx = mob.getPosition().x() - other.getPosition().x();
        double dz = mob.getPosition().z() - other.getPosition().z();
        double dy = Math.abs(mob.getPosition().y() - other.getPosition().y());
        return dx * dx + dz * dz <= horizontal * horizontal && dy <= vertical;
    }

    /** Warden.canTargetEntity. */
    private boolean canTarget(LivingEntity entity) {
        if (entity == null || entity.isRemoved() || entity.isDead() || entity.isInvulnerable()) return false;
        if (entity.getEntityType() == EntityType.WARDEN || entity.getEntityType() == EntityType.ARMOR_STAND) return false;
        if (entity instanceof Player p
                && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) return false;
        return entity.getInstance() == mob.getInstance();
    }

    private LivingEntity nearestAttackable() {
        // WardenEntitySensor: nearest valid player first, then any living entity
        LivingEntity best = null;
        double bestDist = brain.followRange() * brain.followRange();
        for (Player p : mob.getInstance().getPlayers()) {
            if (!canTarget(p)) continue;
            double d = p.getPosition().distanceSquared(mob.getPosition());
            if (d < bestDist) { bestDist = d; best = p; }
        }
        if (best != null) return best;
        for (Entity e : mob.getInstance().getNearbyEntities(mob.getPosition(), brain.followRange())) {
            if (!(e instanceof LivingEntity living) || living instanceof Player || !canTarget(living)) continue;
            double d = e.getPosition().distanceSquared(mob.getPosition());
            if (d < bestDist) { bestDist = d; best = living; }
        }
        return best;
    }

    // ------------------------------------------------------------------ stimuli

    /** Warden.hurtServer, fed by VBrain's hurtBy tracking (Combat.notifyHurt). */
    private void consumeHurt() {
        if (brain.lastHurtTimestamp == lastSeenHurtTimestamp) return;
        lastSeenHurtTimestamp = brain.lastHurtTimestamp;
        LivingEntity attacker = brain.lastHurtBy;
        if (attacker == null || state == State.EMERGING || state == State.DIGGING) return;
        increaseAnger(attacker, ON_HURT_ANGER, false);
        if (attackTarget == null && canTarget(attacker)
                && mob.getPosition().distance(attacker.getPosition()) <= 5.0) {
            setAttackTarget(attacker);
        }
    }

    /**
     * Warden.VibrationUser.onReceiveVibration, routed from Vibrations.emit
     * (radius 16, wool occlusion applied by the caller). Projectile events
     * resolve to the shooter: first projectile angers 10, repeats 35.
     */
    public static void hearVibration(String event, Point pos, Entity source) {
        // prune here: a killed warden's entity scheduler stops before its own
        // tick cleanup can run, so the registry is swept on the listen path
        WARDENS.values().removeIf(w -> w.mob.isRemoved());
        for (WardenMob w : WARDENS.values()) {
            w.onVibration(event, pos, source);
        }
    }

    private void onVibration(String event, Point pos, Entity source) {
        if (mob.isRemoved() || mob.getInstance() == null) return;
        if (source == mob) return;
        if (state == State.EMERGING || state == State.DIGGING) return;
        if (age < vibrationCooldownUntil) return;
        Instance sourceInstance = source != null ? source.getInstance() : mob.getInstance();
        if (sourceInstance != mob.getInstance()) return;
        Vec ear = Vec.fromPoint(mob.getPosition().add(0, mob.getEyeHeight(), 0));
        Vec origin = Vec.fromPoint(pos);
        if (ear.distance(origin) > 16) return;
        if (Vibrations.woolOccludes(mob.getInstance(), ear, origin)) return;

        Entity projectileOwner = source instanceof EntityProjectile projectile ? projectile.getShooter() : null;
        LivingEntity sourceLiving = source instanceof LivingEntity living ? living
                : projectileOwner instanceof LivingEntity living ? living : null;
        if (sourceLiving != null && !canTarget(sourceLiving)) return;

        vibrationCooldownUntil = age + VIBRATION_COOLDOWN;
        mob.triggerStatus((byte) 61); // tendril wiggle
        sound(mob.getInstance(), mob.getPosition(), SoundEvent.ENTITY_WARDEN_TENDRIL_CLICKS, 5f);

        Point suspiciousPos = pos;
        if (projectileOwner instanceof LivingEntity owner
                && mob.getPosition().distance(owner.getPosition()) <= 30) {
            if (age < recentProjectileUntil) {
                if (canTarget(owner)) suspiciousPos = owner.getPosition();
                increaseAnger(owner, DEFAULT_ANGER, true);
            } else {
                increaseAnger(owner, PROJECTILE_ANGER, true);
            }
            recentProjectileUntil = age + RECENT_PROJECTILE_TICKS;
        } else if (sourceLiving != null) {
            increaseAnger(sourceLiving, DEFAULT_ANGER, true);
        }

        if (activeAnger() < ANGRY) {
            setDisturbance(suspiciousPos);
        }
    }

    /** WardenAi.setDisturbanceLocation. */
    private void setDisturbance(Point pos) {
        if (entityAngryAt() != null || attackTarget != null) return;
        digAllowedAt = age + DIG_COOLDOWN;
        sniffCooldownUntil = Math.max(sniffCooldownUntil, age + 100);
        disturbance = pos;
        disturbanceUntil = age + DISTURBANCE_EXPIRY;
    }

    /** Warden.applyDarknessAround: Darkness 260gt to survival players in range. */
    public static void applyDarknessAround(Instance instance, Point center, double radius) {
        for (Player player : instance.getPlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) continue;
            if (player.getPosition().distance(center) <= radius) {
                player.addEffect(new Potion(PotionEffect.DARKNESS, (byte) 0, 260));
            }
        }
    }

    // ------------------------------------------------------------------ test hooks

    /** Playtest hook: force the dig clock so despawn behavior can be observed quickly. */
    public void forceDigNow() {
        digAllowedAt = age;
        disturbance = null;
        attackTarget = null;
        roarTarget = null;
        anger.clear();
    }

    /** Playtest hook: current anger toward an entity. */
    public int angerAt(LivingEntity entity) {
        return anger.getOrDefault(entity, 0);
    }

    /** Playtest hook: fighting = roar finished, attack target locked. */
    public boolean isFighting() {
        return attackTarget != null;
    }

    /** Playtest hook: skip the 200gt fresh-target sonic grace. */
    public void hastenSonicBoom() {
        sonicCooldownUntil = age;
    }

    public boolean isDiggingOrEmerging() {
        return state == State.EMERGING || state == State.DIGGING;
    }

    private static void sound(Instance instance, Point at, SoundEvent event, float volume) {
        if (instance == null) return;
        instance.playSound(Sound.sound(event, Sound.Source.HOSTILE, volume, 1f),
                at.x(), at.y(), at.z());
    }
}
