package dev.pointofpressure.minecom.mobs.ai;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.monster.CreakingMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Creaking, ported from decompiled Creaking/CreakingAi (26.1.2). A
 * heart-bound protector is near-invulnerable — damage plays the 8gt flinch
 * and redirects to its heart's 100gt hurt call (blocks/CreakingHearts) —
 * and follows the Weeping Angel rule: it freezes (no movement, no melee —
 * MeleeAttack.create(Creaking::canMove, 40)) while a survival player within
 * follow range looks at it (0.5-tolerance cone over eye/mid/base heights +
 * line of sight; a carved-pumpkin disguise defeats the check only before
 * activation). Activation needs a look within 12 blocks; fight walks at
 * 1.0x speed with a 40gt melee cadence; idle strolls at 0.3. It dies only
 * through the heart (killed heart -> creakingDeathEffects; unlink ->
 * tearDown), both ending in the 45gt tearing-down sequence. A heartless
 * creaking (/summon) is mortal with its plain 1 HP, like vanilla's spawn
 * egg. Not modeled (AUDIT.md): teardown/trail particles, the glowing-eye
 * death flicker (client visuals), vanilla's twist-toward-target body
 * rotation control.
 */
public final class CreakingMob {

    private static final Map<Integer, CreakingMob> CREAKINGS = new ConcurrentHashMap<>();

    private static final int TEAR_DOWN_TICKS = 45;
    private static final double ACTIVATION_RANGE_SQ = 144.0;
    private static final int MELEE_COOLDOWN = 40;
    private static final int FLINCH_TICKS = 8;

    private final EntityCreature mob;
    private final VBrain brain;
    private final Point homePos;

    private Player target;
    private boolean active;
    private boolean canMove = true;
    private boolean tearingDown;
    private int deathTime;
    private int stuckCounter;
    private int flinchTicks;
    private int pathRecalcIn;
    private long meleeReadyAt;
    private long age;

    public static CreakingMob of(Entity entity) {
        return CREAKINGS.get(entity.getEntityId());
    }

    /** Creaking.createAttributes; homePos != null binds it to a heart. */
    public static EntityCreature spawn(Instance instance, Pos pos, Point homePos) {
        EntityCreature mob = new EntityCreature(EntityType.CREAKING);
        VBrain brain = VanillaMobs.brain(mob, 0.4, 32, 3, 1, 0);
        new CreakingMob(mob, brain, homePos);
        mob.setInstance(instance, pos);
        return mob;
    }

    private CreakingMob(EntityCreature mob, VBrain brain, Point homePos) {
        this.mob = mob;
        this.brain = brain;
        this.homePos = homePos;
        if (mob.getEntityMeta() instanceof CreakingMeta meta) {
            meta.setCanMove(true);
            if (homePos != null) meta.setHomePos(homePos);
        }
        CREAKINGS.put(mob.getEntityId(), this);
        mob.scheduler().buildTask(this::tick).repeat(TaskSchedule.tick(1)).schedule();
    }

    public boolean isHeartBound() {
        return homePos != null;
    }

    public Point homePos() {
        return homePos;
    }

    public boolean isTearingDown() {
        return tearingDown;
    }

    /** Playtest hook. */
    public boolean canMoveNow() {
        return canMove;
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (mob.isRemoved() || mob.getInstance() == null) {
            CREAKINGS.remove(mob.getEntityId());
            return;
        }
        age++;
        if (flinchTicks > 0) flinchTicks--;
        if (tearingDown) {
            if (++deathTime > TEAR_DOWN_TICKS) {
                sound(SoundEvent.ENTITY_CREAKING_DEATH);
                CREAKINGS.remove(mob.getEntityId());
                mob.remove();
            }
            return;
        }
        // Creaking.tick: die instantly if the heart no longer claims us
        if (homePos != null && !dev.pointofpressure.minecom.blocks.CreakingHearts.isProtector(homePos, this)) {
            tearDown();
            return;
        }

        boolean couldMove = canMove;
        canMove = checkCanMove();
        if (canMove != couldMove) {
            sound(canMove ? SoundEvent.ENTITY_CREAKING_UNFREEZE : SoundEvent.ENTITY_CREAKING_FREEZE);
            if (!canMove) brain.stopNavigation();
            if (mob.getEntityMeta() instanceof CreakingMeta meta) meta.setCanMove(canMove);
        }
        if (!canMove) return;

        if (active && target != null && validTarget(target)) {
            tickFight();
        } else {
            if (active) deactivate();
            // RandomStroll.stroll(0.3F), vanilla 1/120 cadence
            if (brain.navigationDone() && brain.random.nextInt(120) == 0) stroll();
        }
    }

    private void tickFight() {
        brain.lookAt(target);
        if (--pathRecalcIn <= 0) {
            pathRecalcIn = 4 + brain.random.nextInt(7);
            brain.moveTo(target.getPosition(), 1.0);
        }
        if (age >= meleeReadyAt && brain.isWithinMeleeAttackRange(target)) {
            meleeReadyAt = age + MELEE_COOLDOWN;
            sound(SoundEvent.ENTITY_CREAKING_ATTACK);
            mob.attack(target, true);
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
                brain.moveTo(new Vec(x + 0.5, y, z + 0.5), 0.3);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ freezing

    /** Creaking.checkCanMove. */
    private boolean checkCanMove() {
        boolean anyPotentialTarget = false;
        for (Player player : mob.getInstance().getPlayers()) {
            if (!validTarget(player)) continue;
            if (mob.getPosition().distanceSquared(player.getPosition())
                    > brain.followRange() * brain.followRange()) continue;
            anyPotentialTarget = true;
            boolean looking = isLookingAtMe(player);
            if (active) {
                if (looking && !wearingDisguise(player)) return false;
            } else if (looking
                    && player.getPosition().distanceSquared(mob.getPosition()) < ACTIVATION_RANGE_SQ) {
                activate(player);
                return false;
            }
        }
        if (!anyPotentialTarget && active) deactivate();
        return true;
    }

    /**
     * LivingEntity.isLookingAtMe over three target heights (eye, base+0.5,
     * midpoint), approximated as a view-direction cone + line of sight.
     */
    private boolean isLookingAtMe(Player player) {
        Pos mp = mob.getPosition();
        Pos pp = player.getPosition();
        Vec look = pp.direction();
        double eyeY = pp.y() + player.getEyeHeight();
        double[] heights = {mp.y() + mob.getEyeHeight(), mp.y() + 0.5, mp.y() + (mob.getEyeHeight() + 0.5) / 2};
        for (double h : heights) {
            double dx = mp.x() - pp.x();
            double dy = h - eyeY;
            double dz = mp.z() - pp.z();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 0.1) return true;
            double dot = (look.x() * dx + look.y() * dy + look.z() * dz) / dist;
            if (dot > 0.925 && brain.hasLineOfSight(player)) return true;
        }
        return false;
    }

    private boolean wearingDisguise(Player player) {
        return player.getHelmet().material() == Material.CARVED_PUMPKIN;
    }

    private boolean validTarget(LivingEntity entity) {
        if (entity.isRemoved() || entity.isDead() || entity.getInstance() != mob.getInstance()) return false;
        return !(entity instanceof Player p)
                || (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR);
    }

    public void activate(Player player) {
        target = player;
        active = true;
        sound(SoundEvent.ENTITY_CREAKING_ACTIVATE);
        if (mob.getEntityMeta() instanceof CreakingMeta meta) meta.setActive(true);
    }

    public void deactivate() {
        target = null;
        active = false;
        sound(SoundEvent.ENTITY_CREAKING_DEACTIVATE);
        if (mob.getEntityMeta() instanceof CreakingMeta meta) meta.setActive(false);
    }

    public boolean isActive() {
        return active;
    }

    // ------------------------------------------------------------------ damage + death

    /**
     * Creaking.hurtServer, called from CreakingHearts' damage interceptor.
     * Heart-bound: flinch (8gt, no repeat while flinching) + redirect a
     * player-attributed hit to the heart's hurt call. Returns whether the
     * damage event must be cancelled.
     */
    public boolean handleHurt(Entity attacker) {
        if (homePos == null) return false; // heartless: mortal, take the hit
        if (flinchTicks > 0 || tearingDown) return true;
        flinchTicks = FLINCH_TICKS;
        mob.triggerStatus((byte) 66);
        sound(SoundEvent.ENTITY_CREAKING_SWAY);
        Player responsible = attacker instanceof Player p ? p
                : attacker instanceof net.minestom.server.entity.EntityProjectile projectile
                        && projectile.getShooter() instanceof Player p ? p : null;
        if (responsible != null) {
            dev.pointofpressure.minecom.blocks.CreakingHearts.creakingHurt(homePos);
        }
        return true;
    }

    /** CreakingHeartBlockEntity.removeProtector(null): silent unlink teardown. */
    public void tearDown() {
        if (tearingDown) return;
        tearingDown = true;
        deathTime = 0;
        brain.stopNavigation();
        if (mob.getEntityMeta() instanceof CreakingMeta meta) meta.setTearingDown(true);
    }

    /** removeProtector(damageSource): the heart was destroyed by someone. */
    public void deathEffects() {
        sound(SoundEvent.ENTITY_CREAKING_TWITCH);
        tearDown();
    }

    /** CreakingHeartBlockEntity's player-stuck check: eyes inside us for 5+ ticks. */
    public boolean playerIsStuckInYou() {
        for (Player player : mob.getInstance().getPlayers()) {
            if (!validTarget(player)) continue;
            double dx = Math.abs(player.getPosition().x() - mob.getPosition().x());
            double dz = Math.abs(player.getPosition().z() - mob.getPosition().z());
            double eyeY = player.getPosition().y() + player.getEyeHeight();
            if (dx < 0.45 && dz < 0.45 && eyeY > mob.getPosition().y()
                    && eyeY < mob.getPosition().y() + 2.7) {
                return ++stuckCounter > 4;
            }
        }
        stuckCounter = 0;
        return false;
    }

    public EntityCreature entity() {
        return mob;
    }

    private void sound(SoundEvent event) {
        Instance in = mob.getInstance();
        if (in == null) return;
        Pos at = mob.getPosition();
        in.playSound(Sound.sound(event, Sound.Source.HOSTILE, 1f, 1f), at.x(), at.y(), at.z());
    }
}
