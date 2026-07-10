package dev.pointofpressure.minecom.mobs.ai;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.entity.metadata.monster.CreeperMeta;
import net.minestom.server.entity.metadata.monster.zombie.ZombieMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob assembly with the vanilla goal trees and attributes, ported from the
 * decompiled reference (Zombie/AbstractSkeleton/Spider/Creeper/Cow/...): the
 * same goals at the same priorities with the same parameters.
 */
public final class VanillaMobs {
    private VanillaMobs() {}

    private static final Map<Integer, VBrain> BRAINS = new ConcurrentHashMap<>();

    public static VBrain brainOf(Entity entity) {
        return BRAINS.get(entity.getEntityId());
    }

    public static void notifyHurt(EntityCreature mob, LivingEntity attacker) {
        VBrain brain = brainOf(mob);
        if (brain != null) brain.hurtBy(attacker);
    }

    private static VBrain brain(EntityCreature mob, double speed, double followRange,
                                double attackDamage, float maxHealth, double armor) {
        mob.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(speed);
        mob.getAttribute(Attribute.FOLLOW_RANGE).setBaseValue(followRange);
        mob.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(attackDamage);
        mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(maxHealth);
        mob.getAttribute(Attribute.ARMOR).setBaseValue(armor);
        mob.setHealth(maxHealth);
        VBrain brain = new VBrain(mob);
        BRAINS.put(mob.getEntityId(), brain);
        mob.scheduler().buildTask(() -> {
            if (mob.isRemoved()) BRAINS.remove(mob.getEntityId());
        }).delay(TaskSchedule.tick(1)).repeat(TaskSchedule.tick(200)).schedule();
        return brain;
    }

    // ---------------------------------------------------------------- monsters

    /** Zombie.registerGoals: attack 3, stroll 7, look 8; targets: hurtBy(alert) 1, nearestPlayer 2. */
    public static EntityCreature zombie(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.ZOMBIE);
        VBrain brain = brain(mob, 0.23, 35, 3, 20, 2);
        brain.addGoal(3, new Goals.MeleeAttack(brain, 1.0, false));
        brain.addGoal(7, new Goals.WaterAvoidingRandomStroll(brain, 1.0));
        brain.addGoal(8, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, true));
        brain.addTargetGoal(2, new Goals.NearestAttackablePlayer(brain, true));
        sunburn(mob, instance);
        maybeEquipArmor(mob);
        maybeEquipZombieWeapon(mob);
        maybeBabyZombie(mob);
        mob.setInstance(instance, pos);
        return mob;
    }

    /** AbstractSkeleton: bow 4, stroll 5, look 6; targets like zombie (no alert). */
    public static EntityCreature skeleton(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.SKELETON);
        mob.setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.of(Material.BOW));
        VBrain brain = brain(mob, 0.25, 16, 2, 20, 0);
        brain.addGoal(4, new Goals.BowAttack(brain, 1.0, 20, 15));
        brain.addGoal(5, new Goals.WaterAvoidingRandomStroll(brain, 1.0));
        brain.addGoal(6, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(6, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        brain.addTargetGoal(2, new Goals.NearestAttackablePlayer(brain, true));
        sunburn(mob, instance);
        maybeEquipArmor(mob);
        mob.setInstance(instance, pos);
        return mob;
    }

    /** Spider: leap 4, attack 5 (aborts in daylight per SpiderAttackGoal), targets night-only. */
    public static EntityCreature spider(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.SPIDER);
        VBrain brain = brain(mob, 0.3, 16, 2, 16, 0);
        brain.addGoal(4, new Goals.LeapAtTarget(brain, 0.4f));
        brain.addGoal(5, new Goals.MeleeAttack(brain, 1.0, true) {
            @Override
            public boolean canContinueToUse() {
                // SpiderAttackGoal: gives up in bright daylight
                return !isDaySurface(mob) && super.canContinueToUse();
            }
        });
        brain.addGoal(7, new Goals.WaterAvoidingRandomStroll(brain, 0.8));
        brain.addGoal(8, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        brain.addTargetGoal(2, new Goals.NearestAttackablePlayer(brain, true) {
            @Override
            public boolean canUse() {
                return !isDaySurface(mob) && super.canUse();
            }
        });
        mob.setInstance(instance, pos);
        return mob;
    }

    /** Creeper: swell 2, melee-approach 4, stroll 5; swell counter explodes at 30 (radius 3). */
    public static EntityCreature creeper(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.CREEPER);
        VBrain brain = brain(mob, 0.25, 16, 0, 20, 0);
        int[] swell = {0};
        int[] swellDir = {-1};
        Goals.Swell.CreeperState state = dir -> swellDir[0] = dir;
        brain.addGoal(2, new Goals.Swell(brain, state));
        brain.addGoal(4, new Goals.MeleeAttack(brain, 1.0, false) {
            @Override
            public void tick() {
                // creepers close in but never punch; the swell goal finishes the job
                LivingEntity target = brain.target;
                if (target == null) return;
                brain.lookAt(target);
                brain.moveTo(target.getPosition(), 1.0);
            }
        });
        brain.addGoal(5, new Goals.WaterAvoidingRandomStroll(brain, 0.8));
        brain.addGoal(6, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(6, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.NearestAttackablePlayer(brain, true));
        brain.addTargetGoal(2, new Goals.HurtByTarget(brain, false));

        CreeperMeta meta = (CreeperMeta) mob.getEntityMeta();
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            int before = swell[0];
            swell[0] = Math.max(0, Math.min(30, swell[0] + swellDir[0]));
            if (swell[0] > 0 && before == 0) meta.setState(CreeperMeta.State.FUSE);
            if (swell[0] == 0 && before > 0) meta.setState(CreeperMeta.State.IDLE);
            if (swell[0] >= 30) {
                Pos at = mob.getPosition();
                Instance in = mob.getInstance();
                boolean charged = meta.isCharged();
                mob.remove();
                dev.pointofpressure.minecom.blocks.Explosions.explode(in, at.add(0, 0.5, 0),
                        3f, 1.0 / 3, mob, charged);
            }
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /** ZombifiedPiglin: zombie body, neutral — only the grudge targets, and it alerts the pack. */
    public static EntityCreature zombifiedPiglin(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.ZOMBIFIED_PIGLIN);
        mob.setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.of(Material.GOLDEN_SWORD));
        VBrain brain = brain(mob, 0.23, 35, 5, 20, 2);
        brain.addGoal(2, new Goals.MeleeAttack(brain, 1.0, false));
        brain.addGoal(7, new Goals.WaterAvoidingRandomStroll(brain, 1.0));
        brain.addGoal(8, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, true));
        mob.setInstance(instance, pos);
        return mob;
    }

    /** Blaze: hovering fireball volleys — 3 shots, 6 ticks apart, every ~110 ticks (from Blaze.BlazeAttackGoal). */
    public static EntityCreature blaze(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.BLAZE);
        VBrain brain = brain(mob, 0.23, 48, 6, 20, 0);
        brain.addGoal(7, new Goals.WaterAvoidingRandomStroll(brain, 1.0));
        brain.addGoal(8, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        brain.addTargetGoal(2, new Goals.NearestAttackablePlayer(brain, false));
        int[] volley = {0};
        int[] cooldown = {60};
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) return;
            brain.lookAt(target);
            if (--cooldown[0] > 0) return;
            if (volley[0] < 3) {
                volley[0]++;
                cooldown[0] = 6;
                var fireball = new net.minestom.server.entity.EntityProjectile(mob, EntityType.SMALL_FIREBALL);
                Pos from = mob.getPosition().add(0, mob.getEyeHeight(), 0);
                fireball.setInstance(mob.getInstance(), from);
                Vec dir = target.getPosition().add(0, target.getEyeHeight() / 2, 0).sub(from).asVec().normalize();
                fireball.setVelocity(dir.mul(24));
            } else {
                volley[0] = 0;
                cooldown[0] = 100;
            }
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /** Magma cube: slime hop movement toward targets, contact damage. */
    public static EntityCreature magmaCube(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.MAGMA_CUBE);
        VBrain brain = brain(mob, 0.2, 16, 6, 16, 3);
        brain.addTargetGoal(1, new Goals.NearestAttackablePlayer(brain, false));
        brain.addTargetGoal(2, new Goals.HurtByTarget(brain, false));
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null || !mob.isOnGround()) return;
            if (brain.random.nextInt(20) != 0) return;
            LivingEntity target = brain.target;
            Vec dir;
            if (target != null && !target.isDead()) {
                brain.lookAt(target);
                dir = target.getPosition().sub(mob.getPosition()).asVec();
                if (dir.length() < 0.1) dir = new Vec(0, 0, 0);
                else dir = dir.normalize();
                // contact damage
                if (mob.getPosition().distanceSquared(target.getPosition()) < 2.5) {
                    mob.attack(target, false);
                }
            } else {
                double angle = brain.random.nextDouble() * Math.PI * 2;
                dir = new Vec(Math.cos(angle), 0, Math.sin(angle));
            }
            mob.setVelocity(new Vec(dir.x() * 4, 8.5, dir.z() * 4));
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    // ---------------------------------------------------------------- hostile variants

    // armor tiers by slot (leather, gold, chainmail, iron, diamond) — Mob.populateDefaultEquipment
    private static final Material[][] ARMOR = {
            {Material.LEATHER_HELMET, Material.GOLDEN_HELMET, Material.CHAINMAIL_HELMET, Material.IRON_HELMET, Material.DIAMOND_HELMET},
            {Material.LEATHER_CHESTPLATE, Material.GOLDEN_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE, Material.IRON_CHESTPLATE, Material.DIAMOND_CHESTPLATE},
            {Material.LEATHER_LEGGINGS, Material.GOLDEN_LEGGINGS, Material.CHAINMAIL_LEGGINGS, Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS},
            {Material.LEATHER_BOOTS, Material.GOLDEN_BOOTS, Material.CHAINMAIL_BOOTS, Material.IRON_BOOTS, Material.DIAMOND_BOOTS}};
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HELMET, EquipmentSlot.CHESTPLATE, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS};

    /**
     * Vanilla Mob.populateDefaultEquipmentSlots: ~15% chance to wear armor, one tier
     * (weighted leather..diamond), filling helmet down with decreasing probability.
     */
    static void maybeEquipArmor(EntityCreature mob) {
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        if (rng.nextFloat() >= 0.15f) return;
        float t = rng.nextFloat();
        int tier = t < 0.37f ? 0 : t < 0.64f ? 1 : t < 0.84f ? 2 : t < 0.95f ? 3 : 4;
        float chance = 1.0f;
        for (int i = 0; i < 4; i++) {              // helmet -> boots, each less likely
            if (rng.nextFloat() < chance) mob.setEquipment(ARMOR_SLOTS[i], ItemStack.of(ARMOR[i][tier]));
            else break;
            chance *= 0.6f;
        }
    }

    /**
     * Zombie.populateDefaultEquipmentSlots weapon roll (on top of the base armor roll):
     * 1% chance (5% on hard, approximated flat at 1% since this project has no difficulty
     * setting) to hold an iron_sword, iron_spear, or iron_shovel (1/6, 1/6, 4/6).
     */
    static void maybeEquipZombieWeapon(EntityCreature mob) {
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        if (rng.nextFloat() >= 0.01f) return;
        int roll = rng.nextInt(6);
        Material weapon = roll == 0 ? Material.IRON_SWORD : roll == 1 ? Material.IRON_SPEAR : Material.IRON_SHOVEL;
        mob.setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.of(weapon));
    }

    /** Drowned.populateDefaultEquipmentSlots: 10% chance to hold a trident (10/16) or fishing_rod (6/16). */
    static void maybeEquipDrownedWeapon(EntityCreature mob) {
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        if (rng.nextFloat() <= 0.9f) return;
        int roll = rng.nextInt(16);
        mob.setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.of(roll < 10 ? Material.TRIDENT : Material.FISHING_ROD));
    }

    /**
     * Zombie.getSpawnAsBabyOdds: 5% chance to spawn as a baby — smaller hitbox
     * (ZombieMeta handles the client-visible scaling), 1.5x movement speed
     * (Zombie.SPEED_MODIFIER_BABY: ADD_MULTIPLIED_BASE 0.5, i.e. base*(1+0.5)).
     */
    static void maybeBabyZombie(EntityCreature mob) {
        if (java.util.concurrent.ThreadLocalRandom.current().nextFloat() >= 0.05f) return;
        if (mob.getEntityMeta() instanceof ZombieMeta meta) meta.setBaby(true);
        var speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        speed.setBaseValue(speed.getBaseValue() * 1.5);
    }

    /** Test hook: the observed baby-zombie trigger rate over N trials (expect ~0.05). */
    public static double testBabyZombieTriggerRate(int trials) {
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        int hits = 0;
        for (int i = 0; i < trials; i++) if (rng.nextFloat() < 0.05f) hits++;
        return hits / (double) trials;
    }

    /** Test hook: the observed zombie weapon-roll trigger rate over N trials (expect ~0.01). */
    public static double testZombieWeaponTriggerRate(int trials) {
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        int hits = 0;
        for (int i = 0; i < trials; i++) if (rng.nextFloat() < 0.01f) hits++;
        return hits / (double) trials;
    }

    /** Test hook: among triggered rolls, the fraction landing on iron_shovel (expect ~4/6). */
    public static double testZombieWeaponShovelShare(int trials) {
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        int shovel = 0;
        for (int i = 0; i < trials; i++) {
            int roll = rng.nextInt(6);
            if (roll != 0 && roll != 1) shovel++;
        }
        return shovel / (double) trials;
    }

    /** Zombie-family reskins share Zombie.registerGoals (melee 3, stroll 7, look 8; hurt+player targets). */
    private static EntityCreature zombieLike(EntityType type, Instance instance, Pos pos, boolean burns) {
        EntityCreature mob = new EntityCreature(type);
        VBrain brain = brain(mob, 0.23, 35, 3, 20, 2);
        brain.addGoal(3, new Goals.MeleeAttack(brain, 1.0, false));
        brain.addGoal(7, new Goals.WaterAvoidingRandomStroll(brain, 1.0));
        brain.addGoal(8, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, true));
        brain.addTargetGoal(2, new Goals.NearestAttackablePlayer(brain, true));
        if (burns) sunburn(mob, instance);
        if (type == EntityType.DROWNED) {
            // Drowned.populateDefaultEquipmentSlots overrides entirely (no armor, no super call)
            maybeEquipDrownedWeapon(mob);
        } else {
            maybeEquipArmor(mob);
            maybeEquipZombieWeapon(mob);
        }
        maybeBabyZombie(mob);
        mob.setInstance(instance, pos);
        return mob;
    }

    /** Generic ground melee mob; hostileToPlayers adds the seek-player target goal. */
    private static EntityCreature melee(EntityType type, Instance instance, Pos pos, double spd, double follow,
                                        double atk, float hp, double armor, boolean hostileToPlayers) {
        EntityCreature mob = new EntityCreature(type);
        VBrain brain = brain(mob, spd, follow, atk, hp, armor);
        brain.addGoal(3, new Goals.MeleeAttack(brain, 1.0, false));
        brain.addGoal(7, new Goals.WaterAvoidingRandomStroll(brain, 1.0));
        brain.addGoal(8, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        if (hostileToPlayers) brain.addTargetGoal(2, new Goals.NearestAttackablePlayer(brain, true));
        mob.setInstance(instance, pos);
        return mob;
    }

    /** Piglin: neutral (retaliates only), carries a golden sword. */
    public static EntityCreature piglin(Instance i, Pos p) {
        EntityCreature mob = melee(EntityType.PIGLIN, i, p, 0.35, 16, 5, 16, 0, false);
        mob.setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.of(Material.GOLDEN_SWORD));
        return mob;
    }

    /** Hoglin: hostile brute, high HP and knockback attack. */
    public static EntityCreature hoglin(Instance i, Pos p) {
        return melee(EntityType.HOGLIN, i, p, 0.3, 16, 6, 40, 0, true);
    }

    /** Wither skeleton: fortress melee brute with a stone sword. */
    public static EntityCreature witherSkeleton(Instance i, Pos p) {
        EntityCreature mob = melee(EntityType.WITHER_SKELETON, i, p, 0.25, 20, 4, 20, 4, true);
        mob.setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.of(Material.STONE_SWORD));
        return mob;
    }

    /** Strider: passive nether lava-strider (hovers on the lava surface, drifts gently). */
    public static EntityCreature strider(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.STRIDER);
        VBrain brain = brain(mob, 0.16, 16, 0, 20, 0);
        mob.setNoGravity(true);
        brain.addGoal(7, new Goals.LookAtPlayer(brain, 6));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            if (brain.random.nextInt(6) != 0) return;
            double a = brain.random.nextDouble() * Math.PI * 2;
            mob.setVelocity(new Vec(Math.cos(a) * 1.0, 0, Math.sin(a) * 1.0));
        }).repeat(TaskSchedule.tick(10)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /** Witch: keeps its distance and lobs harming "splash potions" (modelled as ranged magic hits). */
    public static EntityCreature witch(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.WITCH);
        VBrain brain = brain(mob, 0.25, 16, 0, 26, 0);
        brain.addGoal(6, new Goals.WaterAvoidingRandomStroll(brain, 1.0));
        brain.addGoal(7, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        brain.addTargetGoal(2, new Goals.NearestAttackablePlayer(brain, true));
        int[] cd = {40};
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) return;
            brain.lookAt(target);
            double d2 = mob.getPosition().distanceSquared(target.getPosition());
            if (d2 > 100) brain.moveTo(target.getPosition(), 1.0);   // stay ~10 blocks out
            if (--cd[0] > 0) return;
            cd[0] = 60;
            if (d2 <= 144) target.damage(DamageType.MAGIC, 3f);      // splash-potion of harming (approx)
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /** Ghast: floating nether mob that charges and lobs an explosive fireball at players. */
    public static EntityCreature ghast(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.GHAST);
        VBrain brain = brain(mob, 0.03, 64, 0, 10, 0);
        mob.setNoGravity(true);
        brain.addTargetGoal(1, new Goals.NearestAttackablePlayer(brain, false));
        int[] charge = {0};
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) {
                if (brain.random.nextInt(20) == 0) {
                    double a = brain.random.nextDouble() * Math.PI * 2;
                    mob.setVelocity(new Vec(Math.cos(a) * 2, (brain.random.nextDouble() - 0.5) * 2, Math.sin(a) * 2));
                }
                charge[0] = 0;
                return;
            }
            brain.lookAt(target);
            if (mob.getPosition().distanceSquared(target.getPosition()) > 64 * 64) return;
            if (++charge[0] < 60) return;
            charge[0] = -40; // fired: cooldown
            var fireball = new net.minestom.server.entity.EntityProjectile(mob, EntityType.FIREBALL);
            Pos from = mob.getPosition().add(0, mob.getEyeHeight(), 0);
            fireball.setInstance(mob.getInstance(), from);
            Vec dir = target.getPosition().add(0, target.getEyeHeight() / 2, 0).sub(from).asVec().normalize();
            fireball.setVelocity(dir.mul(12));
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /**
     * Phantom: a flying insomnia mob that orbits above its target and periodically dives
     * in for a melee strike. Real vanilla's attack damage is {@code 6 + phantomSize}, where
     * size grows with consecutive sleepless nights tracked per-player (Phantom.java,
     * decompiled) — bounded here to a flat size-0 phantom (6 damage), since this project
     * doesn't track per-player sleepless-night counts. Unlike every other mob in this
     * roster, it doesn't use VBrain's ground-pathfinding goals at all (no ground to path
     * over) — same "no-gravity, direct velocity control" pattern as ghast(), just with a
     * scripted circle-then-dive state machine instead of a charge-and-fire cadence.
     */
    public static EntityCreature phantom(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.PHANTOM);
        mob.setNoGravity(true);
        VBrain brain = brain(mob, 0, 64, 6, 20, 0);
        brain.addTargetGoal(1, new Goals.NearestAttackablePlayer(brain, false));
        double[] angle = {0};
        int[] diveCooldown = {100 + brain.random.nextInt(100)};
        boolean[] diving = {false};
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) return;
            brain.lookAt(target);
            Pos targetPos = target.getPosition();

            if (!diving[0]) {
                angle[0] += 0.1;
                double orbitRadius = 8;
                double ox = targetPos.x() + Math.cos(angle[0]) * orbitRadius;
                double oz = targetPos.z() + Math.sin(angle[0]) * orbitRadius;
                double oy = targetPos.y() + 10;
                Vec toOrbit = new Vec(ox - mob.getPosition().x(), oy - mob.getPosition().y(), oz - mob.getPosition().z());
                mob.setVelocity(toOrbit.normalize().mul(6));
                if (--diveCooldown[0] <= 0) diving[0] = true;
            } else {
                Vec toTarget = new Vec(targetPos.x() - mob.getPosition().x(),
                        (targetPos.y() + 1) - mob.getPosition().y(), targetPos.z() - mob.getPosition().z());
                double dist = toTarget.length();
                if (dist < 1.5) {
                    net.minestom.server.event.EventDispatcher.call(
                            new net.minestom.server.event.entity.EntityAttackEvent(mob, target));
                    diving[0] = false;
                    diveCooldown[0] = 100 + brain.random.nextInt(100);
                } else {
                    mob.setVelocity(toTarget.normalize().mul(14));
                }
            }
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /**
     * Guardian: charges a laser beam at a target held in continuous line of sight, then
     * fires (`Guardian$GuardianAttackGoal`, decompiled): charge starts at -10 and fires once
     * it reaches {@code getAttackDuration()}=80 (90 ticks total, ~4.5s), dealing 1.0 indirect
     * magic damage (Normal difficulty — this project has no difficulty setting, so the
     * Hard-only +2 and elder-only +2 bonuses don't apply) PLUS a normal melee hit via
     * {@code doHurtTarget} (this project's 6 ATTACK_DAMAGE, routed through the shared
     * combat pipeline like every other mob). Breaking line of sight resets the charge to
     * zero, matching vanilla exactly. Bounded: no real underwater swim AI (this project has
     * none at all — the same line already drawn for Aqua Affinity/Depth Strider), so it
     * moves via the standard ground-pathfinding VBrain goals instead of vanilla's aquatic
     * movement, and the beam has no client-visible charging animation.
     */
    public static EntityCreature guardian(Instance instance, Pos pos) {
        return guardianCore(EntityType.GUARDIAN, instance, pos, 0.5, 6, 30, 80);
    }

    /**
     * Elder Guardian: a bigger, tougher Guardian variant (`ElderGuardian.createAttributes`,
     * decompiled: 80 max health, 8 attack damage, 0.3 movement speed) with a faster laser
     * charge (`getAttackDuration()`=60, so 70 total ticks/~3.5s vs base Guardian's 90/~4.5s)
     * and a periodic area debuff: every 1200 ticks (60s), Mining Fatigue III for 6000 ticks
     * (5 min) to every player within 50 blocks (`customServerAiStep`, decompiled). Reuses
     * the exact laser mechanic via the same shared {@code guardianCore} helper — the only
     * real difference between the two mobs is stats plus this one extra aura. Bounded: this
     * project has no server-enforced mining-speed system at all (the same reason Aqua
     * Affinity/Depth Strider were abandoned), so Mining Fatigue is applied as a real,
     * queryable status effect but doesn't mechanically slow anything down — the same
     * "effect applied, some downstream consequences unmodeled" line already drawn
     * elsewhere in this project.
     */
    public static EntityCreature elderGuardian(Instance instance, Pos pos) {
        EntityCreature mob = guardianCore(EntityType.ELDER_GUARDIAN, instance, pos, 0.3, 8, 80, 60);
        int[] auraCooldown = {1200};
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            if (--auraCooldown[0] > 0) return;
            auraCooldown[0] = 1200;
            for (net.minestom.server.entity.Player p : mob.getInstance().getPlayers()) {
                if (p.getPosition().distanceSquared(mob.getPosition()) <= 2500.0) {
                    p.addEffect(new net.minestom.server.potion.Potion(
                            net.minestom.server.potion.PotionEffect.MINING_FATIGUE, 2, 6000));
                }
            }
        }).repeat(TaskSchedule.tick(1)).schedule();
        return mob;
    }

    private static EntityCreature guardianCore(EntityType type, Instance instance, Pos pos,
                                                 double speed, double attackDamage, float maxHealth, int attackDuration) {
        EntityCreature mob = new EntityCreature(type);
        VBrain brain = brain(mob, speed, 16, attackDamage, maxHealth, 0);
        brain.addGoal(7, new Goals.WaterAvoidingRandomStroll(brain, speed));
        brain.addGoal(8, new Goals.LookAtPlayer(brain, 8));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        brain.addTargetGoal(2, new Goals.NearestAttackablePlayer(brain, true));
        int[] chargeTicks = {-10};
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            LivingEntity target = brain.target;
            if (target == null || target.isDead() || !brain.hasLineOfSight(target)) {
                chargeTicks[0] = -10;
                return;
            }
            brain.lookAt(target);
            brain.stopNavigation();
            if (++chargeTicks[0] >= attackDuration + 10) {
                target.damage(DamageType.INDIRECT_MAGIC, 1f);
                net.minestom.server.event.EventDispatcher.call(
                        new net.minestom.server.event.entity.EntityAttackEvent(mob, target));
                chargeTicks[0] = -10;
            }
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /**
     * Shulker: entirely stationary (clings to whatever surface it spawns on, no locomotion
     * at all — the one mob in this roster with zero movement AI, matching real vanilla),
     * fires shulker bullets at anything in range. `Shulker$ShulkerAttackGoal` (decompiled):
     * fire cooldown resets to {@code 20 + random(10)*10} ticks (1-5.5s, 20-tick base) once
     * the target is within 400 (20²) blocks squared. `ShulkerBullet.onHitEntity`
     * (decompiled): 4.0 damage plus 200-tick (10s) Levitation. Bounded: no block-face
     * attachment/peek-open animation, and the bullet is a straight shot at the target's
     * position when fired rather than vanilla's real mid-flight homing correction.
     */
    public static EntityCreature shulker(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.SHULKER);
        mob.setNoGravity(true);
        VBrain brain = brain(mob, 0, 20, 0, 30, 0);
        brain.addTargetGoal(1, new Goals.NearestAttackablePlayer(brain, true));
        int[] cooldown = {20};
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) return;
            brain.lookAt(target);
            if (mob.getPosition().distanceSquared(target.getPosition()) >= 400.0) return;
            if (--cooldown[0] > 0) return;
            cooldown[0] = 20 + brain.random.nextInt(10) * 10;
            var bullet = new net.minestom.server.entity.EntityProjectile(mob, EntityType.SHULKER_BULLET);
            // Real vanilla ShulkerBullet flies a straight/homing line unaffected by gravity —
            // unlike arrows/tridents (which genuinely do arc), it must not fall; at only 6
            // blocks/sec (much slower than arrow speeds) an un-flagged bullet had enough
            // flight time for gravity to visibly drop it below the target's hitbox.
            bullet.setNoGravity(true);
            Pos from = mob.getPosition().add(0, mob.getEyeHeight() / 2, 0);
            bullet.setInstance(mob.getInstance(), from);
            Vec dir = target.getPosition().add(0, target.getEyeHeight() / 2, 0).sub(from).asVec().normalize();
            bullet.setVelocity(dir.mul(6));
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /**
     * Wither: 300 HP boss, flies, fires wither skulls at any target in range
     * (`WitherBoss.createAttributes`: 300 max health, 0.6 movement/flying speed, 4 armor;
     * `WitherSkull.onHitEntity`, decompiled: the STANDARD black skull deals 8.0 damage —
     * only the rare 0.1%-chance "dangerous"/charged blue skull variant, fired only while
     * the wither is in its invulnerable spawn-shield phase, deals less raw damage but adds
     * Wither; that variant, the shield phase itself, and boss-bar/explosion-immunity are
     * all out of scope for this bounded port). Bounded to a SINGLE firing cooldown
     * (ghast's established charge/cooldown shape) rather than vanilla's independent
     * 3-head aiming/cooldown array.
     */
    public static EntityCreature wither(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.WITHER);
        mob.setNoGravity(true);
        VBrain brain = brain(mob, 0.6, 40, 0, 300, 4);
        brain.addTargetGoal(1, new Goals.NearestAttackablePlayer(brain, false));
        int[] cooldown = {20};
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) return;
            brain.lookAt(target);
            if (mob.getPosition().distanceSquared(target.getPosition()) > 900.0) return; // 30 blocks
            if (--cooldown[0] > 0) return;
            cooldown[0] = 20 + brain.random.nextInt(20);
            var skull = new net.minestom.server.entity.EntityProjectile(mob, EntityType.WITHER_SKULL);
            // Real WitherSkull flies a straight guided line, unaffected by gravity — same
            // fix already required for the shulker bullet, applied here from the start.
            skull.setNoGravity(true);
            Pos from = mob.getPosition().add(0, mob.getEyeHeight(), 0);
            skull.setInstance(mob.getInstance(), from);
            Vec dir = target.getPosition().add(0, target.getEyeHeight() * 0.5, 0).sub(from).asVec().normalize();
            skull.setVelocity(dir.mul(10));
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /**
     * Iron Golem: village defender — 100 HP, full knockback immunity, attacks nearby
     * hostile mobs unprompted (defends villagers, {@link Goals.NearestHostileMob}) and
     * retaliates if struck. {@code IronGolem.doHurtTarget} (decompiled): damage is a real
     * variable range, not the flat attribute value every other mob in this roster uses —
     * {@code attackDamage/2 + random(attackDamage)} with attackDamage=15 (so 7.5-22.5 per
     * hit), plus an UPWARD launch ({@code 0.4 * (1 - targetKnockbackResistance)}, added
     * directly as vertical velocity) instead of horizontal knockback — the iconic "throws
     * you in the air" hit, handled as a dedicated `IRON_GOLEM` branch in
     * {@code Combat.attack()}'s mob-vs-target path rather than the generic flat-damage one.
     * Bounded: no population-threshold auto-spawn near villages (this project has no
     * village-population-tracking system) — spawnable directly via {@code Mobs.spawn}/
     * summon like every other mob in this session's roster additions.
     */
    public static EntityCreature ironGolem(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.IRON_GOLEM);
        VBrain brain = brain(mob, 0.25, 16, 15, 100, 0);
        mob.getAttribute(Attribute.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        brain.addGoal(3, new Goals.MeleeAttack(brain, 1.0, false));
        brain.addGoal(7, new Goals.WaterAvoidingRandomStroll(brain, 0.6));
        brain.addGoal(8, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        brain.addTargetGoal(2, new Goals.NearestHostileMob(brain));
        mob.setInstance(instance, pos);
        return mob;
    }

    /**
     * Snow Golem: 4 HP (extremely fragile — real vanilla constant, not a placeholder),
     * throws snowballs at hostile mobs within 10 blocks (`SnowGolem.createAttributes` +
     * `RangedAttackGoal(this, 1.25, 20, 10.0F)`, decompiled: 20-tick cooldown, 10-block
     * range). `Snowball.onHitEntity` (decompiled): 3 damage specifically to Blazes (their
     * fire/ice weakness — a real, deliberate vanilla rule, not a bug), 0 damage to
     * everything else but still applies a push (a real "hit without damage" in vanilla).
     * Out of scope for this pass: player-thrown snowballs (right-clicking a snowball item)
     * — this collision handling covers ANY snowball, golem-fired or otherwise, but nothing
     * currently spawns a player-thrown one.
     */
    public static EntityCreature snowGolem(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.SNOW_GOLEM);
        VBrain brain = brain(mob, 0.2, 10, 0, 4, 0);
        brain.addGoal(7, new Goals.WaterAvoidingRandomStroll(brain, 0.6));
        brain.addGoal(8, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        brain.addTargetGoal(2, new Goals.NearestHostileMob(brain));
        int[] cooldown = {20};
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) return;
            brain.lookAt(target);
            if (mob.getPosition().distanceSquared(target.getPosition()) > 100.0) return; // 10 blocks
            if (--cooldown[0] > 0) return;
            cooldown[0] = 20;
            var snowball = new net.minestom.server.entity.EntityProjectile(mob, EntityType.SNOWBALL);
            Pos from = mob.getPosition().add(0, mob.getEyeHeight(), 0);
            snowball.setInstance(mob.getInstance(), from);
            Vec dir = target.getPosition().add(0, target.getEyeHeight() * 0.5, 0).sub(from).asVec().normalize();
            snowball.setVelocity(dir.mul(20));
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /**
     * Village villager: wander AI, jobless at spawn. Real vanilla villagers only gain a
     * profession (and its trade table) by claiming a nearby job-site block — see
     * {@link dev.pointofpressure.minecom.mobs.VillagerTrades#assignProfession}, which
     * {@link dev.pointofpressure.minecom.mobs.Villagers} calls at spawn and periodically
     * thereafter. A jobless villager still opens the trade GUI on interact (falling back
     * to the "farmer" table) so it's never a hard dead end before it claims a real job.
     */
    public static EntityCreature villager(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.VILLAGER);
        VBrain brain = brain(mob, 0.5, 16, 0, 20, 0);
        brain.addGoal(6, new Goals.WaterAvoidingRandomStroll(brain, 0.6));
        brain.addGoal(7, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));

        mob.setInstance(instance, pos);
        return mob;
    }

    /** Wandering trader: roams with a fixed exotic trade list, despawns after a while. */
    public static EntityCreature wanderingTrader(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.WANDERING_TRADER);
        VBrain brain = brain(mob, 0.5, 16, 0, 20, 0);
        brain.addGoal(6, new Goals.WaterAvoidingRandomStroll(brain, 0.6));
        brain.addGoal(7, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        mob.scheduler().buildTask(() -> { if (!mob.isRemoved()) mob.remove(); })
                .delay(TaskSchedule.tick(48000)).schedule();   // despawn after ~40 min
        mob.setInstance(instance, pos);
        return mob;
    }

    /** Bat: harmless ambient flyer that drifts through dark caves. */
    public static EntityCreature bat(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.BAT);
        VBrain brain = brain(mob, 0.1, 16, 0, 6, 0);
        mob.setNoGravity(true);
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            if (brain.random.nextInt(4) != 0) return;
            double a = brain.random.nextDouble() * Math.PI * 2;
            double vy = (brain.random.nextDouble() - 0.45) * 1.2;
            mob.setVelocity(new Vec(Math.cos(a) * 1.2, vy, Math.sin(a) * 1.2));
        }).repeat(TaskSchedule.tick(8)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    public static EntityCreature parched(Instance i, Pos p) { return zombieLike(EntityType.PARCHED, i, p, false); }

    public static EntityCreature husk(Instance i, Pos p) { return zombieLike(EntityType.HUSK, i, p, false); }
    public static EntityCreature drowned(Instance i, Pos p) { return zombieLike(EntityType.DROWNED, i, p, false); }
    public static EntityCreature zombieVillager(Instance i, Pos p) { return zombieLike(EntityType.ZOMBIE_VILLAGER, i, p, true); }

    /** Skeleton-family reskins share AbstractSkeleton.registerGoals (bow 4, stroll 5, look 6). */
    private static EntityCreature skeletonLike(EntityType type, Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(type);
        mob.setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.of(Material.BOW));
        VBrain brain = brain(mob, 0.25, 16, 2, 20, 0);
        brain.addGoal(4, new Goals.BowAttack(brain, 1.0, 20, 15));
        brain.addGoal(5, new Goals.WaterAvoidingRandomStroll(brain, 1.0));
        brain.addGoal(6, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(6, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        brain.addTargetGoal(2, new Goals.NearestAttackablePlayer(brain, true));
        sunburn(mob, instance);
        maybeEquipArmor(mob);
        mob.setInstance(instance, pos);
        return mob;
    }

    public static EntityCreature stray(Instance i, Pos p) { return skeletonLike(EntityType.STRAY, i, p); }
    public static EntityCreature bogged(Instance i, Pos p) { return skeletonLike(EntityType.BOGGED, i, p); }

    /**
     * Enderman: neutral until a player stares at it (crosshair within a narrow cone,
     * &lt;64 blocks, line of sight), then melee-hostile. Blinks away when hurt and
     * flees water. 40 HP, fast, high reach.
     */
    public static EntityCreature enderman(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.ENDERMAN);
        VBrain brain = brain(mob, 0.3, 64, 7, 40, 0);
        brain.addGoal(2, new Goals.MeleeAttack(brain, 1.0, false));
        brain.addGoal(7, new Goals.WaterAvoidingRandomStroll(brain, 1.0));
        brain.addGoal(8, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        brain.addTargetGoal(2, new EndermanStareTarget(brain));
        float[] lastHealth = {40};
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            // flee water; blink away when hurt
            if (mob.getInstance().getBlock(mob.getPosition()).compare(Block.WATER)) endermanTeleport(mob);
            float h = mob.getHealth();
            if (h < lastHealth[0]) endermanTeleport(mob);
            lastHealth[0] = mob.getHealth();
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /** Random blink within 32 blocks to a valid stand-up spot (EndermanBlockPlacePos analogue). */
    private static void endermanTeleport(EntityCreature mob) {
        Instance in = mob.getInstance();
        if (in == null) return;
        Pos base = mob.getPosition();
        for (int i = 0; i < 16; i++) {
            int tx = base.blockX() + ThreadLocalRandom.current().nextInt(65) - 32;
            int tz = base.blockZ() + ThreadLocalRandom.current().nextInt(65) - 32;
            for (int y = Math.min(base.blockY() + 16, 318); y > Math.max(base.blockY() - 24, -63); y--) {
                if (!in.getBlock(tx, y, tz).isSolid()) continue;
                if (in.getBlock(tx, y, tz).isLiquid()) break;
                if (in.getBlock(tx, y + 1, tz).isAir() && in.getBlock(tx, y + 2, tz).isAir()) {
                    mob.teleport(new Pos(tx + 0.5, y + 1, tz + 0.5, base.yaw(), base.pitch()));
                    return;
                }
                break;
            }
        }
    }

    /** EndermanLookForPlayerGoal: anger at a player whose crosshair is on the enderman. */
    private static final class EndermanStareTarget extends VGoal {
        private final VBrain brain;
        private Player found;

        EndermanStareTarget(VBrain brain) { this.brain = brain; setFlags(java.util.EnumSet.of(Flag.TARGET)); }

        @Override public boolean canUse() {
            found = null;
            double best = 64 * 64;
            for (Player p : brain.mob.getInstance().getPlayers()) {
                if (p.isDead() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
                if (!isStaring(p)) continue;
                double d = p.getPosition().distanceSquared(brain.mob.getPosition());
                if (d < best) { best = d; found = p; }
            }
            return found != null;
        }

        private boolean isStaring(Player p) {
            Pos mp = brain.mob.getPosition();
            Pos pp = p.getPosition();
            double dx = mp.x() - pp.x();
            double dy = (mp.y() + 2.55) - (pp.y() + 1.62); // enderman head vs player eye
            double dz = mp.z() - pp.z();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > 64 || dist < 0.1) return false;
            Vec look = pp.direction();
            double dot = (look.x() * dx + look.y() * dy + look.z() * dz) / dist;
            return dot > 0.985 && brain.hasLineOfSight(p); // narrow cone + LOS
        }

        @Override public void start() { brain.setTarget(found); }

        @Override public boolean canContinueToUse() {
            LivingEntity t = brain.target;
            return t != null && !t.isDead()
                    && brain.mob.getPosition().distanceSquared(t.getPosition()) < 64 * 64;
        }

        @Override public void stop() { brain.setTarget(null); }
    }

    /** Overworld slime: Slime.registerGoals bounce + touch damage (small size, 4 HP). */
    public static EntityCreature slime(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.SLIME);
        VBrain brain = brain(mob, 0.2, 16, 2, 4, 0);
        brain.addTargetGoal(1, new Goals.NearestAttackablePlayer(brain, false));
        brain.addTargetGoal(2, new Goals.HurtByTarget(brain, false));
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null || !mob.isOnGround()) return;
            if (brain.random.nextInt(20) != 0) return;
            LivingEntity target = brain.target;
            Vec dir;
            if (target != null && !target.isDead()) {
                brain.lookAt(target);
                dir = target.getPosition().sub(mob.getPosition()).asVec();
                dir = dir.length() < 0.1 ? new Vec(0, 0, 0) : dir.normalize();
                if (mob.getPosition().distanceSquared(target.getPosition()) < 2.5) mob.attack(target, false);
            } else {
                double angle = brain.random.nextDouble() * Math.PI * 2;
                dir = new Vec(Math.cos(angle), 0, Math.sin(angle));
            }
            mob.setVelocity(new Vec(dir.x() * 3, 7.0, dir.z() * 3));
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    // ---------------------------------------------------------------- animals

    private record AnimalSpec(EntityType type, float health, double speed, double panicSpeed,
                              double temptSpeed, Set<Material> foods) {}

    private static final Map<String, AnimalSpec> ANIMALS = Map.ofEntries(
            Map.entry("cow", new AnimalSpec(EntityType.COW, 10, 0.2, 2.0, 1.25, Set.of(Material.WHEAT))),
            Map.entry("sheep", new AnimalSpec(EntityType.SHEEP, 8, 0.23, 1.25, 1.1, Set.of(Material.WHEAT))),
            Map.entry("pig", new AnimalSpec(EntityType.PIG, 10, 0.25, 1.25, 1.2,
                    Set.of(Material.CARROT, Material.POTATO, Material.BEETROOT))),
            Map.entry("chicken", new AnimalSpec(EntityType.CHICKEN, 4, 0.25, 1.4, 1.0,
                    Set.of(Material.WHEAT_SEEDS, Material.BEETROOT_SEEDS,
                            Material.MELON_SEEDS, Material.PUMPKIN_SEEDS))),
            Map.entry("mooshroom", new AnimalSpec(EntityType.MOOSHROOM, 10, 0.2, 2.0, 1.25, Set.of(Material.WHEAT))),
            Map.entry("rabbit", new AnimalSpec(EntityType.RABBIT, 3, 0.3, 2.2, 1.0,
                    Set.of(Material.CARROT, Material.GOLDEN_CARROT, Material.DANDELION))),
            Map.entry("goat", new AnimalSpec(EntityType.GOAT, 10, 0.2, 2.0, 1.25, Set.of(Material.WHEAT))),
            Map.entry("horse", new AnimalSpec(EntityType.HORSE, 15, 0.2, 1.2, 1.0,
                    Set.of(Material.WHEAT, Material.APPLE, Material.GOLDEN_CARROT, Material.GOLDEN_APPLE, Material.SUGAR))),
            Map.entry("donkey", new AnimalSpec(EntityType.DONKEY, 15, 0.2, 1.2, 1.0,
                    Set.of(Material.WHEAT, Material.APPLE, Material.GOLDEN_CARROT, Material.GOLDEN_APPLE, Material.SUGAR))),
            Map.entry("llama", new AnimalSpec(EntityType.LLAMA, 22, 0.2, 1.2, 1.0, Set.of(Material.WHEAT, Material.HAY_BLOCK))),
            Map.entry("turtle", new AnimalSpec(EntityType.TURTLE, 30, 0.1, 1.2, 1.1, Set.of(Material.SEAGRASS))),
            Map.entry("panda", new AnimalSpec(EntityType.PANDA, 20, 0.15, 2.0, 1.0, Set.of(Material.BAMBOO))),
            Map.entry("polar_bear", new AnimalSpec(EntityType.POLAR_BEAR, 30, 0.25, 2.2, 1.0, Set.of())),
            Map.entry("armadillo", new AnimalSpec(EntityType.ARMADILLO, 12, 0.14, 2.0, 1.0, Set.of(Material.SPIDER_EYE))),
            Map.entry("camel", new AnimalSpec(EntityType.CAMEL, 32, 0.09, 1.2, 1.0, Set.of(Material.CACTUS))),
            Map.entry("fox", new AnimalSpec(EntityType.FOX, 10, 0.3, 2.2, 1.1, Set.of(Material.SWEET_BERRIES, Material.GLOW_BERRIES))),
            Map.entry("frog", new AnimalSpec(EntityType.FROG, 10, 0.1, 1.5, 1.0, Set.of(Material.SLIME_BALL))),
            Map.entry("wolf", new AnimalSpec(EntityType.WOLF, 8, 0.3, 1.5, 1.0,
                    Set.of(Material.BEEF, Material.MUTTON, Material.CHICKEN, Material.RABBIT))),
            Map.entry("parrot", new AnimalSpec(EntityType.PARROT, 6, 0.4, 1.5, 1.0,
                    Set.of(Material.WHEAT_SEEDS, Material.MELON_SEEDS, Material.PUMPKIN_SEEDS, Material.BEETROOT_SEEDS))),
            Map.entry("zombie_horse", new AnimalSpec(EntityType.ZOMBIE_HORSE, 15, 0.2, 1.2, 1.0, Set.of())),
            Map.entry("ocelot", new AnimalSpec(EntityType.OCELOT, 10, 0.3, 2.0, 1.0, Set.of(Material.COD, Material.SALMON))));

    /** Animal.registerGoals shape: panic 1, tempt 4, stroll 6, look 7, random look 8. */
    public static EntityCreature animal(String kind, Instance instance, Pos pos) {
        AnimalSpec spec = ANIMALS.get(kind);
        if (spec == null) return null;
        EntityCreature mob = new EntityCreature(spec.type());
        VBrain brain = brain(mob, spec.speed(), 16, 0, spec.health(), 0);
        brain.addGoal(1, new Goals.Panic(brain, spec.panicSpeed()));
        brain.addGoal(4, new Goals.Tempt(brain, spec.temptSpeed(), spec.foods()));
        brain.addGoal(6, new Goals.WaterAvoidingRandomStroll(brain, 1.0));
        brain.addGoal(7, new Goals.LookAtPlayer(brain, 6));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        mob.setInstance(instance, pos);
        if (spec.type() == EntityType.SHEEP && mob.getEntityMeta()
                instanceof net.minestom.server.entity.metadata.animal.SheepMeta sheepMeta) {
            String biome = instance.getBiome(pos.blockX(), pos.blockY(), pos.blockZ()).name();
            sheepMeta.setColor(dev.pointofpressure.minecom.mobs.Shearing.randomColor(biome, ThreadLocalRandom.current()));
        }
        return mob;
    }

    // ---------------------------------------------------------------- water mobs

    private static final Map<String, float[]> WATER = Map.ofEntries(
            Map.entry("squid", new float[]{10}), Map.entry("glow_squid", new float[]{10}),
            Map.entry("cod", new float[]{3}), Map.entry("salmon", new float[]{3}),
            Map.entry("pufferfish", new float[]{3}), Map.entry("tropical_fish", new float[]{3}),
            Map.entry("dolphin", new float[]{10}), Map.entry("axolotl", new float[]{14}),
            Map.entry("nautilus", new float[]{8}));

    private static final Map<String, EntityType> WATER_TYPES = Map.ofEntries(
            Map.entry("squid", EntityType.SQUID), Map.entry("glow_squid", EntityType.GLOW_SQUID),
            Map.entry("cod", EntityType.COD), Map.entry("salmon", EntityType.SALMON),
            Map.entry("pufferfish", EntityType.PUFFERFISH), Map.entry("tropical_fish", EntityType.TROPICAL_FISH),
            Map.entry("dolphin", EntityType.DOLPHIN), Map.entry("axolotl", EntityType.AXOLOTL),
            Map.entry("nautilus", EntityType.NAUTILUS));

    /** Aquatic mobs: neutrally buoyant (no gravity) with a gentle random drift while submerged. */
    public static EntityCreature waterAnimal(String kind, Instance instance, Pos pos) {
        EntityType type = WATER_TYPES.get(kind);
        if (type == null) return null;
        float health = WATER.get(kind)[0];
        EntityCreature mob = new EntityCreature(type);
        VBrain brain = brain(mob, 0.12, 16, 0, health, 0);
        mob.setNoGravity(true);
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            // only swim while in water; otherwise flop (slight downward drift)
            boolean inWater = mob.getInstance().getBlock(mob.getPosition()).compare(Block.WATER);
            if (!inWater) { mob.setVelocity(new Vec(0, -1, 0)); return; }
            if (brain.random.nextInt(8) != 0) return;
            double a = brain.random.nextDouble() * Math.PI * 2;
            double p = (brain.random.nextDouble() - 0.5) * 0.6;
            mob.setVelocity(new Vec(Math.cos(a) * 1.5, p, Math.sin(a) * 1.5));
        }).repeat(TaskSchedule.tick(10)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    // ---------------------------------------------------------------- raiders

    /** Pillager: crossbow-ranged illager, 24 HP. RangedCrossbowAttackGoal(mob, 1.0, 8.0F). */
    public static EntityCreature pillager(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.PILLAGER);
        mob.setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.of(Material.CROSSBOW));
        VBrain brain = brain(mob, 0.35, 16, 2, 24, 0);
        brain.addGoal(4, new Goals.CrossbowAttack(brain, 1.0, 8.0f));
        brain.addGoal(7, new Goals.WaterAvoidingRandomStroll(brain, 0.6));
        brain.addGoal(8, new Goals.LookAtPlayer(brain, 8));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        brain.addTargetGoal(2, new Goals.NearestAttackablePlayer(brain, true));
        mob.setInstance(instance, pos);
        return mob;
    }

    /** Vindicator: axe-wielding illager brute, 24 HP. */
    public static EntityCreature vindicator(Instance instance, Pos pos) {
        EntityCreature mob = melee(EntityType.VINDICATOR, instance, pos, 0.35, 16, 5, 24, 0, true);
        mob.setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.of(Material.IRON_AXE));
        return mob;
    }

    /** Ravager: raid siege beast — 100 HP, 12 melee damage, heavily knockback-resistant (Ravager.createAttributes). */
    public static EntityCreature ravager(Instance instance, Pos pos) {
        EntityCreature mob = melee(EntityType.RAVAGER, instance, pos, 0.3, 16, 12, 100, 0, true);
        mob.getAttribute(Attribute.KNOCKBACK_RESISTANCE).setBaseValue(0.75);
        return mob;
    }

    /** Evoker: illager spellcaster — lobs a line of evoker fangs and periodically summons vexes, 24 HP. */
    public static EntityCreature evoker(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.EVOKER);
        VBrain brain = brain(mob, 0.5, 16, 0, 24, 0);
        brain.addGoal(6, new Goals.WaterAvoidingRandomStroll(brain, 0.6));
        brain.addGoal(7, new Goals.LookAtPlayer(brain, 12));
        brain.addGoal(8, new Goals.RandomLookAround(brain));
        brain.addTargetGoal(1, new Goals.HurtByTarget(brain, false));
        brain.addTargetGoal(2, new Goals.NearestAttackablePlayer(brain, true));
        int[] fangCd = {40};
        int[] vexCd = {160};
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            LivingEntity target = brain.target;
            if (target == null || target.isDead()) return;
            brain.lookAt(target);
            double distSq = mob.getPosition().distanceSquared(target.getPosition());
            if (distSq > 144) brain.moveTo(target.getPosition(), 0.5);
            else brain.stopNavigation();

            if (distSq <= 12 * 12 && brain.hasLineOfSight(target) && --fangCd[0] <= 0) {
                fangCd[0] = 100;
                fireFangs(mob, target);
            }
            if (--vexCd[0] <= 0) {
                vexCd[0] = 400;
                summonVexes(mob, target);
            }
        }).repeat(TaskSchedule.tick(1)).schedule();
        mob.setInstance(instance, pos);
        return mob;
    }

    /** A line of evoker fangs walks toward the target, biting ~10 ticks after it appears (6 dmg). */
    private static void fireFangs(EntityCreature caster, LivingEntity target) {
        Instance instance = caster.getInstance();
        Vec toTarget = target.getPosition().sub(caster.getPosition()).asVec();
        if (toTarget.lengthSquared() < 1e-4) return;
        final Vec dir = new Vec(toTarget.x(), 0, toTarget.z()).normalize();
        Pos start = caster.getPosition();
        for (int i = 0; i < 5; i++) {
            final int step = i;
            caster.scheduler().buildTask(() -> {
                if (instance == null) return;
                Pos at = start.add(dir.x() * (1 + step), 0, dir.z() * (1 + step));
                var fangs = new net.minestom.server.entity.Entity(EntityType.EVOKER_FANGS);
                fangs.setInstance(instance, at);
                fangs.scheduler().buildTask(() -> {
                    if (!target.isDead() && target.getPosition().distanceSquared(at) < 2.5 * 2.5) {
                        target.damage(DamageType.MAGIC, 6f);
                    }
                    fangs.remove();
                }).delay(TaskSchedule.tick(10)).schedule();
            }).delay(TaskSchedule.tick(step * 3)).schedule();
        }
    }

    /** Summons a pair of vexes that harass the evoker's current target for ~20s. */
    private static void summonVexes(EntityCreature caster, LivingEntity target) {
        Instance instance = caster.getInstance();
        if (instance == null) return;
        for (int i = 0; i < 2; i++) {
            EntityCreature vex = new EntityCreature(EntityType.VEX);
            vex.getAttribute(Attribute.MAX_HEALTH).setBaseValue(14);
            vex.setHealth(14);
            vex.setNoGravity(true);
            Pos at = caster.getPosition().add((Math.random() - 0.5) * 3, 1 + Math.random(), (Math.random() - 0.5) * 3);
            vex.setInstance(instance, at);
            int[] life = {400};
            vex.scheduler().buildTask(() -> {
                if (vex.isDead() || vex.getInstance() == null) return;
                if (--life[0] <= 0 || target.isDead()) { vex.remove(); return; }
                Vec dir = target.getPosition().add(0, 1, 0).sub(vex.getPosition()).asVec();
                double d2 = dir.lengthSquared();
                if (d2 > 0.01) vex.setVelocity(dir.normalize().mul(Math.min(6, Math.sqrt(d2) * 2)));
                if (d2 < 2.5 * 2.5) target.damage(DamageType.MOB_ATTACK, 4f);
            }).repeat(TaskSchedule.tick(2)).schedule();
        }
    }

    // ---------------------------------------------------------------- shared behaviors

    private static boolean isDaySurface(EntityCreature mob) {
        Instance instance = mob.getInstance();
        if (instance == null) return false;
        long time = instance.getTime() % 24000;
        if (time >= 12542) return false;
        return skyVisible(mob);
    }

    private static boolean skyVisible(EntityCreature mob) {
        Instance instance = mob.getInstance();
        Pos pos = mob.getPosition();
        for (int y = pos.blockY() + 2; y < Math.min(pos.blockY() + 40, 320); y++) {
            if (instance.getBlock(pos.blockX(), y, pos.blockZ()).isSolid()) return false;
        }
        return true;
    }

    /** Zombie/Skeleton aiStep: burn in direct daylight (1 fire damage per second). */
    private static void sunburn(EntityCreature mob, Instance instance) {
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.getInstance() == null) return;
            if (isDaySurface(mob)) {
                mob.getEntityMeta().setOnFire(true);
                mob.damage(DamageType.ON_FIRE, 1f);
            } else if (mob.getEntityMeta().isOnFire()) {
                mob.getEntityMeta().setOnFire(false);
            }
        }).repeat(TaskSchedule.tick(20)).schedule();
    }
}
