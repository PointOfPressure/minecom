package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.data.Items;
import dev.pointofpressure.minecom.data.LootTables;
import dev.pointofpressure.minecom.data.VanillaData;
import dev.pointofpressure.minecom.survival.Experience;
import dev.pointofpressure.minecom.survival.Survival;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.event.entity.EntityDeathEvent;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithEntityEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.timer.TaskSchedule;

import java.time.Duration;
import java.util.Map;
import java.util.Random;

/**
 * Melee/projectile combat with vanilla weapon damage values, knockback,
 * and loot-table-driven mob drops.
 */
public final class Combat {
    private Combat() {}

    private static final Random RANDOM = new Random();

    public static void register(GlobalEventHandler events) {
        events.addListener(EntityAttackEvent.class, Combat::attack);
        events.addListener(EntityDeathEvent.class, Combat::death);
        events.addListener(ProjectileCollideWithEntityEvent.class, Combat::projectileHit);
        events.addListener(EntityDamageEvent.class, Combat::damaged);
    }

    private static void attack(EntityAttackEvent e) {
        if (!(e.getTarget() instanceof LivingEntity target) || target.isDead()) return;

        if (e.getEntity() instanceof Player player) {
            if (player.isDead()) return;
            ItemStack weapon = player.getItemInMainHand();
            float damage = Items.attackDamage(weapon);
            int sharpness = dev.pointofpressure.minecom.data.Enchants.level(weapon, "sharpness");
            if (sharpness > 0) damage += 0.5f * sharpness + 0.5f;
            int impaling = dev.pointofpressure.minecom.data.Enchants.level(weapon, "impaling");
            if (impaling > 0 && isAquatic(target.getEntityType())) damage += 2.5f * impaling;
            // vanilla crit: attacking while falling deals 1.5x
            if (!player.isOnGround() && player.getVelocity().y() < 0) damage *= 1.5f;
            int strength = dev.pointofpressure.minecom.survival.Potions.effectLevel(player,
                    net.minestom.server.potion.PotionEffect.STRENGTH);
            if (strength > 0) damage += 3 * strength;
            int weakness = dev.pointofpressure.minecom.survival.Potions.effectLevel(player,
                    net.minestom.server.potion.PotionEffect.WEAKNESS);
            if (weakness > 0) damage = Math.max(0, damage - 4 * weakness);
            target.damage(Damage.fromPlayer(player, damage));
            knockback(target, player.getPosition());
            Survival.addExhaustion(player, 0.1f);
            player.setItemInMainHand(Items.damageItem(player, weapon, 1));
            int fireAspect = dev.pointofpressure.minecom.data.Enchants.level(weapon, "fire_aspect");
            if (fireAspect > 0 && !target.isDead()) igniteFor(target, fireAspect * 4);

            // Channeling: melee hit during a thunderstorm, wielder exposed to sky, strikes the target
            if (dev.pointofpressure.minecom.data.Enchants.level(weapon, "channeling") > 0
                    && dev.pointofpressure.minecom.survival.Lightning.isThundering(player.getInstance())
                    && dev.pointofpressure.minecom.survival.Lightning.canSeeSky(player.getInstance(), player.getPosition())) {
                dev.pointofpressure.minecom.survival.Lightning.strikeAt(
                        player.getInstance(), target.getPosition().x(), target.getPosition().z());
            }

            // sweep attack: a grounded sword hit also grazes nearby entities (a bounded
            // simplification — real vanilla additionally gates this on attack-cooldown
            // timing, which this project doesn't model). Formula confirmed exactly against
            // decompiled Player.doSweepAttack: `1.0F + SWEEPING_DAMAGE_RATIO * baseDamage`
            // — the flat "+1" is the real vanilla base sweep constant, not an approximation.
            if (player.isOnGround() && weapon.material().key().value().endsWith("_sword")) {
                int sweepingEdge = dev.pointofpressure.minecom.data.Enchants.level(weapon, "sweeping_edge");
                float sweepRatio = sweepingEdge > 0 ? (float) sweepingEdge / (sweepingEdge + 1) : 0f;
                float sweepDamage = 1f + damage * sweepRatio;
                for (Entity nearby : target.getInstance().getNearbyEntities(target.getPosition(), 2.0)) {
                    if (nearby == target || nearby == player || !(nearby instanceof LivingEntity other) || other.isDead()) continue;
                    other.damage(Damage.fromPlayer(player, sweepDamage));
                    knockback(other, player.getPosition());
                }
            }
        } else if (e.getEntity() instanceof EntityCreature mob && mob.getEntityType() == EntityType.IRON_GOLEM
                && target instanceof LivingEntity) {
            // IronGolem.doHurtTarget: a real variable range (attackDamage/2 + random(attackDamage),
            // attackDamage=15) plus an UPWARD launch instead of horizontal knockback — this mob
            // targets other mobs too (village defense), not just players, unlike the generic branch below.
            float atk = (float) mob.getAttributeValue(net.minestom.server.entity.attribute.Attribute.ATTACK_DAMAGE);
            float golemDamage = (int) atk > 0 ? atk / 2f + RANDOM.nextInt((int) atk) : atk;
            target.damage(Damage.fromEntity(mob, golemDamage));
            double kbResistance = target.getAttributeValue(net.minestom.server.entity.attribute.Attribute.KNOCKBACK_RESISTANCE);
            double scale = Math.max(0.0, 1.0 - kbResistance);
            target.setVelocity(target.getVelocity().add(0, 0.4 * scale * 20, 0));
        } else if (e.getEntity() instanceof EntityCreature mob && target instanceof Player) {
            float damage = (float) mob.getAttributeValue(
                    net.minestom.server.entity.attribute.Attribute.ATTACK_DAMAGE);
            if (damage <= 0) return;
            target.damage(Damage.fromEntity(mob, damage));
            knockback(target, mob.getPosition());
            // WitherSkeleton.doHurtTarget: 10s of Wither on a successful melee hit
            if (mob.getEntityType() == EntityType.WITHER_SKELETON) {
                target.addEffect(new net.minestom.server.potion.Potion(
                        net.minestom.server.potion.PotionEffect.WITHER, 0, 200));
            }
            // CaveSpider.doHurtTarget: poison 7s on Normal, 15s on Hard, none on Easy
            if (mob.getEntityType() == EntityType.CAVE_SPIDER) {
                int poisonSeconds = switch (dev.pointofpressure.minecom.Difficulty.current()) {
                    case NORMAL -> 7;
                    case HARD -> 15;
                    default -> 0;
                };
                if (poisonSeconds > 0) {
                    target.addEffect(new net.minestom.server.potion.Potion(
                            net.minestom.server.potion.PotionEffect.POISON, 0, poisonSeconds * 20));
                }
            }
        }
    }

    private static void projectileHit(ProjectileCollideWithEntityEvent e) {
        if (!(e.getTarget() instanceof LivingEntity target) || target.isDead()) return;
        Entity projectile = e.getEntity();
        if (projectile.getEntityType() == EntityType.SMALL_FIREBALL) {
            target.damage(Damage.fromProjectile(projectile, projectile, 5f));
            knockback(target, projectile.getPosition());
            projectile.remove();
            return;
        }
        if (projectile.getEntityType() == EntityType.SNOWBALL) {
            // Snowball.onHitEntity: 3 damage specifically to Blazes, 0 to everything else
            // but still a real push-without-damage hit (matches vanilla exactly).
            float snowballDamage = target.getEntityType() == EntityType.BLAZE ? 3f : 0f;
            target.damage(Damage.fromProjectile(projectile, projectile, snowballDamage));
            knockback(target, projectile.getPosition());
            projectile.remove();
            return;
        }
        if (projectile.getEntityType() == EntityType.SHULKER_BULLET) {
            target.damage(Damage.fromProjectile(projectile, projectile, 4f));
            target.addEffect(new net.minestom.server.potion.Potion(
                    net.minestom.server.potion.PotionEffect.LEVITATION, 0, 200));
            projectile.remove();
            return;
        }
        if (projectile.getEntityType() == EntityType.BREEZE_WIND_CHARGE) {
            // AbstractWindCharge.onHitEntity + BreezeWindCharge.explode: 1 direct damage,
            // then a radius-3 knockback-only wind burst at the impact point
            var shooter = projectile instanceof net.minestom.server.entity.EntityProjectile ep
                    && ep.getShooter() instanceof EntityCreature breeze ? breeze : null;
            if (shooter == target) return; // never bursts on its own breeze
            dev.pointofpressure.minecom.mobs.ai.VanillaMobs.windBurst(
                    target.getInstance(), projectile.getPosition(), shooter, target);
            projectile.remove();
            return;
        }
        if (projectile.getEntityType() == EntityType.WITHER_SKULL) {
            target.damage(Damage.fromProjectile(projectile, projectile, 8f));
            knockback(target, projectile.getPosition());
            projectile.remove();
            return;
        }
        if (projectile.getEntityType() == EntityType.DRAGON_FIREBALL) {
            target.damage(Damage.fromProjectile(projectile, projectile, 6f)); // dragon breath
            knockback(target, projectile.getPosition());
            projectile.remove();
            return;
        }
        if (projectile.getEntityType() == EntityType.TRIDENT) {
            // A returning Loyalty trident flies back through its own owner's hitbox —
            // must not re-damage (or re-trigger another return chain on) the thrower.
            if (projectile instanceof net.minestom.server.entity.EntityProjectile tridentEp
                    && target == tridentEp.getShooter()) {
                return;
            }
            float dmg = projectile.hasTag(dev.pointofpressure.minecom.survival.Trident.DAMAGE)
                    ? projectile.getTag(dev.pointofpressure.minecom.survival.Trident.DAMAGE) : 8f;
            Integer impalingLevel = projectile.getTag(dev.pointofpressure.minecom.survival.Trident.IMPALING);
            if (impalingLevel != null && isAquatic(target.getEntityType())) dmg += 2.5f * impalingLevel;
            target.damage(Damage.fromProjectile(projectile, projectile, dmg));
            knockback(target, projectile.getPosition());
            if (Boolean.TRUE.equals(projectile.getTag(dev.pointofpressure.minecom.survival.Trident.CHANNELING))
                    && projectile instanceof net.minestom.server.entity.EntityProjectile channelEp
                    && channelEp.getShooter() instanceof Player channelOwner
                    && dev.pointofpressure.minecom.survival.Lightning.isThundering(target.getInstance())
                    && dev.pointofpressure.minecom.survival.Lightning.canSeeSky(
                            target.getInstance(), channelOwner.getPosition())) {
                dev.pointofpressure.minecom.survival.Lightning.strikeAt(
                        target.getInstance(), target.getPosition().x(), target.getPosition().z());
            }
            Integer loyalty = projectile.getTag(dev.pointofpressure.minecom.survival.Trident.LOYALTY);
            if (loyalty != null && loyalty > 0 && projectile instanceof net.minestom.server.entity.EntityProjectile ep
                    && ep.getShooter() instanceof Player owner) {
                dev.pointofpressure.minecom.survival.Trident.startReturn(ep, owner, loyalty);
            } else {
                projectile.remove();
            }
            return;
        }
        if (projectile.getEntityType() != EntityType.ARROW) return;
        // Piercing (crossbow-only): a fast-moving arrow can generate more than one
        // collision event against the same target on its way through; only the first
        // counts. Checked before applying damage so a duplicate event is a true no-op.
        Integer pierce = projectile.getTag(dev.pointofpressure.minecom.survival.Crossbow.PIERCE);
        if (pierce != null && !firstHitOf(projectile.getEntityId(), target.getEntityId())) return;

        // player-fired arrows (Bow.java) carry their real charge/enchant-scaled damage as
        // a tag; mob-fired arrows (skeleton BowAttack) have none and keep the flat random.
        float dmg = projectile.hasTag(dev.pointofpressure.minecom.survival.Bow.DAMAGE)
                ? projectile.getTag(dev.pointofpressure.minecom.survival.Bow.DAMAGE)
                : 3f + RANDOM.nextInt(3);
        target.damage(Damage.fromProjectile(projectile, projectile, dmg));
        knockback(target, projectile.getPosition());
        if (projectile.hasTag(dev.pointofpressure.minecom.survival.Bow.PUNCH)) {
            int punch = projectile.getTag(dev.pointofpressure.minecom.survival.Bow.PUNCH);
            target.setVelocity(target.getVelocity().mul(1f + 0.6f * punch));
        }
        if (Boolean.TRUE.equals(projectile.getTag(dev.pointofpressure.minecom.survival.Bow.FLAME)) && !target.isDead()) {
            igniteFor(target, 5);
        }
        if (pierce != null && pierce > 0) {
            projectile.setTag(dev.pointofpressure.minecom.survival.Crossbow.PIERCE, pierce - 1);
            return; // budget remains: pass through, stay alive
        }
        PIERCE_HIT_ENTITIES.remove(projectile.getEntityId());
        projectile.remove();
    }

    private static final java.util.Map<Integer, java.util.Set<Integer>> PIERCE_HIT_ENTITIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** True if this (arrow, target) pair hasn't been credited yet; records it either way. */
    private static boolean firstHitOf(int arrowId, int targetId) {
        return PIERCE_HIT_ENTITIES.computeIfAbsent(arrowId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(targetId);
    }

    /** Fire Aspect: ignite for {@code seconds}, 1 damage/second (matches vanilla fire ticks). */
    private static void igniteFor(LivingEntity target, int seconds) {
        target.getEntityMeta().setOnFire(true);
        burnTick(target, seconds * 20);
    }

    private static void burnTick(LivingEntity target, int ticksLeft) {
        if (target.isRemoved() || target.isDead()) return;
        if (ticksLeft <= 0) {
            target.getEntityMeta().setOnFire(false);
            return;
        }
        if (ticksLeft % 20 == 0) target.damage(DamageType.ON_FIRE, 1f);
        target.scheduler().buildTask(() -> burnTick(target, ticksLeft - 1)).delay(TaskSchedule.tick(1)).schedule();
    }

    // data/minecraft/tags/entity_type/aquatic.json — Impaling's "sensitive_to_impaling" gate.
    private static final java.util.Set<EntityType> AQUATIC = java.util.Set.of(
            EntityType.TURTLE, EntityType.AXOLOTL, EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN,
            EntityType.COD, EntityType.PUFFERFISH, EntityType.SALMON, EntityType.TROPICAL_FISH,
            EntityType.DOLPHIN, EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.TADPOLE);

    private static boolean isAquatic(EntityType type) {
        return AQUATIC.contains(type);
    }

    private static void knockback(LivingEntity target, Pos source) {
        double dx = target.getPosition().x() - source.x();
        double dz = target.getPosition().z() - source.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            dx = RANDOM.nextDouble() - 0.5;
            dz = RANDOM.nextDouble() - 0.5;
            len = Math.sqrt(dx * dx + dz * dz);
        }
        target.takeKnockback(0.4f, -dx / len, -dz / len);
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HELMET, EquipmentSlot.CHESTPLATE, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS};

    /** Vanilla armor damage reduction for players, using the official bypasses_armor tag. */
    private static void damaged(EntityDamageEvent e) {
        // grudge memory for the vanilla brain (HurtByTargetGoal)
        if (e.getEntity() instanceof EntityCreature mob
                && e.getDamage().getAttacker() instanceof net.minestom.server.entity.LivingEntity attacker) {
            dev.pointofpressure.minecom.mobs.ai.VanillaMobs.notifyHurt(mob, attacker);
        }
        if (!(e.getEntity() instanceof Player player)) return;
        String typeId = e.getDamage().getType().key().asString();

        // Player.hurtServer: sources with scaling=when_caused_by_living_non_player
        // (mob melee/projectiles) or always (explosions) scale with world difficulty;
        // on Peaceful they cancel outright
        Entity damager = e.getDamage().getAttacker();
        boolean mobCaused = damager instanceof LivingEntity la && !(la instanceof Player)
                || damager instanceof net.minestom.server.entity.EntityProjectile ep
                        && ep.getShooter() instanceof LivingEntity shooter && !(shooter instanceof Player);
        if (mobCaused || VanillaData.damageTypeHasTag(typeId, "is_explosion")) {
            float scaled = dev.pointofpressure.minecom.Difficulty.scalePlayerDamage(e.getDamage().getAmount());
            if (scaled <= 0) {
                e.setCancelled(true);
                return;
            }
            e.getDamage().setAmount(scaled);
        }

        // shield: blocking with a raised shield negates frontal entity damage
        if (player.isUsingItem() && e.getDamage().getAttacker() != null) {
            ItemStack using = player.getItemInHand(player.getItemUseHand());
            if (using.material() == net.minestom.server.item.Material.SHIELD) {
                var toAttacker = e.getDamage().getAttacker().getPosition().sub(player.getPosition()).asVec();
                var look = player.getPosition().direction();
                if (toAttacker.length() > 0.01 && look.dot(toAttacker.normalize()) > 0) {
                    e.setCancelled(true);
                    player.setItemInHand(player.getItemUseHand(),
                            Items.damageItem(player, using, 1));
                    // LivingEntity.blockedByItem: a successful block knocks the blocker back
                    if (e.getDamage().getAttacker() instanceof LivingEntity attacker) {
                        knockback(player, attacker.getPosition());
                    }
                    return;
                }
            }
        }

        int resistance = dev.pointofpressure.minecom.survival.Potions.effectLevel(player,
                net.minestom.server.potion.PotionEffect.RESISTANCE);
        if (resistance > 0) e.getDamage().setAmount(e.getDamage().getAmount() * (1 - 0.2f * resistance));

        if (VanillaData.damageTypeHasTag(typeId, "bypasses_armor")) return;

        double armor = 0, toughness = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack piece = player.getEquipment(slot);
            armor += Items.armorPoints(piece);
            toughness += Items.armorToughness(piece);
        }
        if (armor <= 0) return;

        float damage = e.getDamage().getAmount();
        double reduction = Math.min(20.0, Math.max(armor / 5.0,
                armor - damage / (2.0 + toughness / 4.0))) / 25.0;
        double afterArmor = damage * (1 - reduction);
        // type-specific protections (fire/blast/projectile) stack with generic Protection, 2 EPF/level each
        String typeProtection = VanillaData.damageTypeHasTag(typeId, "is_fire") ? "fire_protection"
                : VanillaData.damageTypeHasTag(typeId, "is_explosion") ? "blast_protection"
                : VanillaData.damageTypeHasTag(typeId, "is_projectile") ? "projectile_protection" : null;
        int epf = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack piece = player.getEquipment(slot);
            epf += dev.pointofpressure.minecom.data.Enchants.level(piece, "protection");
            if (typeProtection != null) epf += 2 * dev.pointofpressure.minecom.data.Enchants.level(piece, typeProtection);
        }
        if (epf > 0) afterArmor *= 1 - Math.min(20, epf) / 25.0;
        e.getDamage().setAmount((float) afterArmor);

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack piece = player.getEquipment(slot);
            if (!piece.isAir()) player.setEquipment(slot, Items.damageItem(player, piece, 1));
        }

        // thorns.json post_attack: each worn piece independently rolls 0.15*level to reflect 1-5 dmg
        if (e.getDamage().getAttacker() instanceof LivingEntity attacker) {
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                int thorns = dev.pointofpressure.minecom.data.Enchants.level(player.getEquipment(slot), "thorns");
                if (thorns <= 0) continue;
                if (RANDOM.nextFloat() < 0.15f * thorns) {
                    attacker.damage(net.minestom.server.entity.damage.DamageType.THORNS,
                            1f + RANDOM.nextFloat() * 4f);
                    player.setEquipment(slot, Items.damageItem(player, player.getEquipment(slot), 2));
                }
            }
        }
    }

    private static void death(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof EntityCreature mob)) return;
        Instance instance = mob.getInstance();
        if (instance == null) return;
        Pos pos = mob.getPosition();
        ItemStack weapon = ItemStack.AIR;
        if (mob.getLastDamageSource() != null
                && mob.getLastDamageSource().getAttacker() instanceof Player killer) {
            weapon = killer.getItemInMainHand();
        }
        for (ItemStack drop : LootTables.entityDrops(mob.getEntityType(), weapon)) {
            ItemEntity item = new ItemEntity(drop);
            item.setPickupDelay(Duration.ofMillis(500));
            item.setInstance(instance, pos.add(0, 0.5, 0));
            item.setVelocity(new Vec(RANDOM.nextDouble() - 0.5, 2, RANDOM.nextDouble() - 0.5));
        }
        boolean baby = mob.getEntityMeta() instanceof net.minestom.server.entity.metadata.monster.zombie.ZombieMeta zm && zm.isBaby();
        int xp = Experience.mobXp(mob.getEntityType(), baby);
        if (xp > 0) Experience.orb(instance, pos, xp);
    }

}
