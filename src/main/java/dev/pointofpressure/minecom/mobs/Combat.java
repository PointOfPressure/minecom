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
import net.minestom.server.event.entity.projectile.ProjectileCollideWithBlockEvent;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithEntityEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.timer.TaskSchedule;

import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Melee/projectile combat with vanilla weapon damage values, knockback,
 * and loot-table-driven mob drops.
 */
public final class Combat {
    private Combat() {}

    private static final Random RANDOM = new Random();

    /** LivingEntity.resolvePlayerResponsibleForDamage's 100-tick memory window
     *  (decompile-verified): a mob hit by a player, or by that player's tamed wolf, still
     *  counts as "killed by a player" for equipment drops even if the actual killing blow
     *  came from something else in between (fire, fall, a different mob) — as long as the
     *  death happens within 100 ticks of that hit. Keyed by the mob's entity id (session-
     *  scoped like Steering.BOOST_TICKS' identical shape; cleared on death, otherwise a
     *  harmless residual entry for a mob that's hit but never dies). */
    private record PlayerCredit(UUID player, long expiresAtTick) {}
    private static final Map<Integer, PlayerCredit> LAST_HURT_BY_PLAYER = new ConcurrentHashMap<>();
    private static final int HURT_MEMORY_TICKS = 100;

    /** Player.attackStrengthTicker (decompile-verified): counts ticks since the last swing,
     *  reset to 0 by Player.onAttack() on every swing and otherwise incrementing every tick
     *  forever (uncapped — getAttackStrengthScale clamps the ratio, not the counter). Modeled
     *  here as "world age at last swing" rather than a live per-tick counter: since the ticker
     *  only ever matters as (currentTick - lastResetTick), the two are equivalent without
     *  needing a scheduled per-tick task. Missing entry = never swung = treated as fully
     *  charged (real vanilla's ticker also starts at its Java default of 0, and any weapon's
     *  delay is well under a fresh entity's tick-1 lifetime in practice; here it's made exact
     *  by defaulting to "swung long enough ago to already clamp to 1.0"). */
    private static final Map<Integer, Long> LAST_ATTACK_TICK = new ConcurrentHashMap<>();

    /** Test control: force the next swing to read as fully charged. PlayTest scenarios that
     *  assert an exact melee-damage number now need this now that charge affects damage —
     *  same idiom as a scenario explicitly pinning world time or on-ground state before it
     *  measures something charge/gravity/time-sensitive. */
    public static void resetAttackCharge(Entity player) {
        LAST_ATTACK_TICK.remove(player.getEntityId());
    }

    /** EntityTypeTags.REDIRECTABLE_PROJECTILE (decompile-verified): the only entities
     *  Player.deflectProjectile() will redirect instead of taking a normal melee hit. */
    private static final Set<EntityType> REDIRECTABLE_PROJECTILES = Set.of(
            EntityType.FIREBALL, EntityType.WIND_CHARGE, EntityType.BREEZE_WIND_CHARGE);

    public static void register(GlobalEventHandler events) {
        events.addListener(EntityAttackEvent.class, Combat::attack);
        events.addListener(EntityDeathEvent.class, Combat::death);
        events.addListener(ProjectileCollideWithEntityEvent.class, Combat::projectileHit);
        events.addListener(ProjectileCollideWithBlockEvent.class, Combat::projectileHitBlock);
        events.addListener(EntityDamageEvent.class, Combat::damaged);
    }

    private static void attack(EntityAttackEvent e) {
        if (e.getEntity() instanceof Player player && !player.isDead()
                && REDIRECTABLE_PROJECTILES.contains(e.getTarget().getEntityType())) {
            deflect(player, e.getTarget());
            return;
        }
        if (!(e.getTarget() instanceof LivingEntity target) || target.isDead()) return;

        if (e.getEntity() instanceof Player player) {
            if (player.isDead()) return;
            ItemStack weapon = player.getItemInMainHand();

            // Player.getAttackStrengthScale(0.5F) (decompile-verified): 0 right after a swing,
            // ramping linearly back to 1 over getCurrentItemAttackStrengthDelay ticks
            // (20 / attack_speed attribute — a fast weapon like a dagger-speed axe recovers
            // slower than a sword, since axes carry a steeper attack_speed penalty).
            long worldAge = player.getInstance() != null ? player.getInstance().getWorldAge() : 0;
            long lastAttack = LAST_ATTACK_TICK.getOrDefault(player.getEntityId(), Long.MIN_VALUE / 2);
            float delay = 20f / Items.attackSpeed(weapon);
            float attackStrengthScale = Math.max(0f, Math.min(1f, ((worldAge - lastAttack) + 0.5f) / delay));
            LAST_ATTACK_TICK.put(player.getEntityId(), worldAge);
            boolean fullStrengthAttack = attackStrengthScale > 0.9f;

            // Player.attack (decompile-verified): the raw weapon/attribute damage (including
            // Strength/Weakness, which are themselves attribute modifiers on ATTACK_DAMAGE in
            // real vanilla) is scaled by charge FIRST via baseDamageScaleFactor
            // (0.2 + scale^2*0.8 — quadratic, so a bare-tap hit still deals 20% damage, not 0);
            // enchantment flat bonuses (Sharpness/Impaling, ItemStack.getAttackDamageBonus) are
            // added AFTER that scaling and are NOT further charge-scaled — a weak, uncharged hit
            // still gets its full Sharpness bonus, only the base weapon number is diminished.
            float baseDamage = Items.attackDamage(weapon);
            int strength = dev.pointofpressure.minecom.survival.Potions.effectLevel(player,
                    net.minestom.server.potion.PotionEffect.STRENGTH);
            if (strength > 0) baseDamage += 3 * strength;
            int weakness = dev.pointofpressure.minecom.survival.Potions.effectLevel(player,
                    net.minestom.server.potion.PotionEffect.WEAKNESS);
            if (weakness > 0) baseDamage = Math.max(0, baseDamage - 4 * weakness);
            baseDamage *= 0.2f + attackStrengthScale * attackStrengthScale * 0.8f;

            int sharpness = dev.pointofpressure.minecom.data.Enchants.level(weapon, "sharpness");
            if (sharpness > 0) baseDamage += 0.5f * sharpness + 0.5f;
            int impaling = dev.pointofpressure.minecom.data.Enchants.level(weapon, "impaling");
            if (impaling > 0 && isAquatic(target.getEntityType())) baseDamage += 2.5f * impaling;

            // Player.canCriticalAttack (decompile-verified): falling, not sprinting, full charge.
            boolean falling = !player.isOnGround() && player.getVelocity().y() < 0;
            boolean criticalAttack = fullStrengthAttack && falling && !player.isSprinting();
            if (criticalAttack) baseDamage *= 1.5f;
            float damage = baseDamage;

            target.damage(Damage.fromPlayer(player, damage));
            // Player.attack's knockbackAttack: sprinting on a full-charge hit adds a real,
            // separate extra knockback() call (not a bigger single call — vanilla's
            // LivingEntity.knockback halves existing momentum each call, so two sequential
            // calls compound rather than just summing their strengths).
            boolean knockbackAttack = player.isSprinting() && fullStrengthAttack;
            knockback(target, player.getPosition(), knockbackAttack ? 0.5f : 0f);
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

            // Player.isSweepAttack (decompile-verified): full-charge, non-critical, non-sprint
            // hit, grounded, sword — this project doesn't track the additional real
            // "not moving faster than 2.5x walk speed" gate (no tracked horizontal-speed state
            // to check it against), everything else is exact. Formula confirmed against
            // decompiled Player.doSweepAttack: `(1.0F + SWEEPING_DAMAGE_RATIO * baseDamage) *
            // attackStrengthScale` — the flat "+1" is the real vanilla base sweep constant.
            if (fullStrengthAttack && !criticalAttack && !knockbackAttack && player.isOnGround()
                    && weapon.material().key().value().endsWith("_sword")) {
                int sweepingEdge = dev.pointofpressure.minecom.data.Enchants.level(weapon, "sweeping_edge");
                float sweepRatio = sweepingEdge > 0 ? (float) sweepingEdge / (sweepingEdge + 1) : 0f;
                float sweepDamage = (1f + damage * sweepRatio) * attackStrengthScale;
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
        } else if (e.getEntity() instanceof EntityCreature mob) {
            // Mob.doHurtTarget (decompile-verified): target-type-agnostic in real vanilla —
            // a zombie hurts a villager exactly the same way it hurts a player. This project's
            // gate used to require a Player target specifically, which meant NO mob could ever
            // actually kill another mob (e.g. a zombie could never kill a villager) — a
            // pre-existing gap surfaced while wiring villager zombie-conversion, since that
            // whole feature depends on a zombie being able to kill a villager in the first
            // place. Iron Golem is handled above with its own real formula/knockback and never
            // reaches here.
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

    /** Set on a fireball once a player deflects it, so it's allowed to hit its own
     *  original shooter afterward (see projectileHit's FIREBALL self-hit exclusion). */
    private static final net.minestom.server.tag.Tag<Boolean> DEFLECTED =
            net.minestom.server.tag.Tag.Boolean("minecom:deflected");

    /**
     * Player.deflectProjectile (decompile-verified): attacking a redirectable
     * projectile instead deflects it — ProjectileDeflection.AIM_DEFLECT sets its
     * velocity to the player's exact look direction. Real vanilla additionally
     * reassigns the projectile's owner to the deflecting player (so a returning
     * ghast fireball can then hit the ghast that fired it, and death messages
     * credit the player); this codebase has no player-vs-mob kill-attribution/
     * death-message system at all to make that reassignment meaningful, and
     * EntityProjectile's shooter is set once at construction with no setter, so
     * full ownership reassignment isn't modeled — only the DEFLECTED tag, just
     * enough to let a deflected fireball hit the ghast that fired it (the
     * player-facing point of deflecting one in the first place), same as the
     * self-hit exclusion below would otherwise still block that hit.
     */
    private static void deflect(Player player, Entity projectile) {
        Vec dir = player.getPosition().direction();
        double speed = projectile.getVelocity().length();
        projectile.setVelocity(dir.mul(speed > 0.01 ? speed : 12));
        projectile.setTag(DEFLECTED, true);
        player.playSound(net.kyori.adventure.sound.Sound.sound(
                net.minestom.server.sound.SoundEvent.ENTITY_PLAYER_ATTACK_NODAMAGE,
                net.kyori.adventure.sound.Sound.Source.PLAYER, 1f, 1f));
    }

    private static void projectileHit(ProjectileCollideWithEntityEvent e) {
        if (!(e.getTarget() instanceof LivingEntity target) || target.isDead()) return;
        Entity projectile = e.getEntity();
        // EnderMan.hurtServer (decompile-verified): any IS_PROJECTILE-tagged damage
        // source against an enderman skips normal damage/effects entirely and instead
        // rerolls its teleport-dodge — arrows (and every other projectile type this
        // method handles) simply never hit an enderman at all, dodge or no dodge.
        // Previously endermen took ordinary arrow/projectile damage like anything else.
        if (target instanceof EntityCreature endermanTarget && target.getEntityType() == EntityType.ENDERMAN) {
            dev.pointofpressure.minecom.mobs.ai.VanillaMobs.endermanTeleport(endermanTarget);
            projectile.remove();
            return;
        }
        if (projectile.getEntityType() == EntityType.FIREBALL) {
            // Projectile.canHitEntity excludes the shooter from its own projectile in
            // real vanilla (found via debug instrumentation: without this, a fireball
            // spawned overlapping its own ghast's hitbox immediately self-hit and
            // exploded on the ghast at launch, before ever reaching a target — Minestom
            // doesn't provide this exclusion automatically for a manually-constructed
            // EntityProjectile, unlike this project's arrow/trident code paths which
            // apparently don't hit this because bow arrows spawn already clear of the
            // shooter's own hitbox). A deflected fireball is the one exception — real
            // vanilla lets it hit its original shooter once the player has redirected
            // it, which is the entire point of deflecting one back at a ghast.
            boolean selfHit = projectile instanceof net.minestom.server.entity.EntityProjectile fireballEp
                    && fireballEp.getShooter() == target
                    && !Boolean.TRUE.equals(projectile.getTag(DEFLECTED));
            if (!selfHit) {
                // LargeFireball (decompile-verified): onHitEntity deals a flat 6 direct
                // damage; the shared onHit() separately ALSO triggers a real power-1
                // explosion at the impact point (Level.explode(..., MOB)) — both happen
                // together for an entity hit, reusing this project's own explosion engine.
                target.damage(Damage.fromProjectile(projectile, projectile, 6f));
                explodeFireball(projectile);
            }
            return;
        }
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
        EntityType arrowType = projectile.getEntityType();
        if (arrowType != EntityType.ARROW && arrowType != EntityType.SPECTRAL_ARROW) return;
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
        // Arrow.doPostHurtEffects/SpectralArrow.doPostHurtEffects (26.2 decompile-verified):
        // a tipped arrow applies its carried potion at the real bundled tipped_arrow item's
        // potion_duration_scale of 0.125 (1/8 duration — the same Potions.apply scaling
        // ThrownPotions already uses for splash/lingering); a spectral arrow always grants
        // Glowing for a flat 200 ticks. Runs on every successful hit, including each of a
        // piercing arrow's multiple hits, same as real vanilla.
        if (!target.isDead()) {
            if (arrowType == EntityType.SPECTRAL_ARROW) {
                target.addEffect(new net.minestom.server.potion.Potion(
                        net.minestom.server.potion.PotionEffect.GLOWING, (byte) 0, 200));
            }
            String potionKey = projectile.getTag(dev.pointofpressure.minecom.survival.Bow.POTION);
            if (potionKey != null) {
                try {
                    var potionType = net.minestom.server.potion.PotionType.fromKey(potionKey);
                    if (potionType != null) {
                        dev.pointofpressure.minecom.survival.Potions.apply(target, potionType, 0.125);
                    }
                } catch (Exception ignored) { }
            }
        }
        if (pierce != null && pierce > 0) {
            projectile.setTag(dev.pointofpressure.minecom.survival.Crossbow.PIERCE, pierce - 1);
            return; // budget remains: pass through, stay alive
        }
        PIERCE_HIT_ENTITIES.remove(projectile.getEntityId());
        projectile.remove();
    }

    /** LargeFireball hitting a block instead of an entity: still explodes, just no direct hit. */
    private static void projectileHitBlock(ProjectileCollideWithBlockEvent e) {
        dev.pointofpressure.minecom.redstone.Vibrations.emit("projectile_land",
                e.getCollisionPosition(), e.getEntity());
        if (e.getEntity().getEntityType() == EntityType.EXPERIENCE_BOTTLE) {
            // ThrownExperienceBottle.onHit: 3-11 experience where it lands
            Instance in = e.getEntity().getInstance();
            if (in != null) {
                dev.pointofpressure.minecom.survival.Experience.orb(in, e.getCollisionPosition(),
                        3 + java.util.concurrent.ThreadLocalRandom.current().nextInt(9));
            }
            e.getEntity().remove();
            return;
        }
        if (e.getEntity().getEntityType() == EntityType.SMALL_FIREBALL) {
            // SmallFireball.onHitBlock: start a fire on the struck face (dispenser fire charges)
            Instance instance = e.getEntity().getInstance();
            if (instance != null) {
                var hit = e.getCollisionPosition();
                var vel = e.getEntity().getVelocity();
                double len = Math.max(1e-6, vel.length());
                var firePos = new net.minestom.server.coordinate.Vec(
                        (int) Math.floor(hit.x() - vel.x() / len * 0.01),
                        (int) Math.floor(hit.y() - vel.y() / len * 0.01),
                        (int) Math.floor(hit.z() - vel.z() / len * 0.01));
                if (instance.getBlock(firePos).isAir()) {
                    instance.setBlock(firePos, net.minestom.server.instance.block.Block.FIRE);
                    dev.pointofpressure.minecom.blocks.FireSpread.track(firePos);
                }
            }
            e.getEntity().remove();
            return;
        }
        if (e.getEntity().getEntityType() != EntityType.FIREBALL) return;
        explodeFireball(e.getEntity());
    }

    private static void explodeFireball(Entity fireball) {
        Instance instance = fireball.getInstance();
        if (instance != null) {
            Entity shooter = fireball instanceof net.minestom.server.entity.EntityProjectile ep ? ep.getShooter() : null;
            dev.pointofpressure.minecom.blocks.Explosions.explode(
                    instance, fireball.getPosition(), 1f, 1.0, shooter);
        }
        fireball.remove();
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
        knockback(target, source, 0f);
    }

    /** LivingEntity.hurt's always-on 0.4-strength knockback, plus (for a sprinting full-charge
     *  player hit) a real SECOND, separate knockback() call for Player.attack's causeExtraKnockback
     *  — decompile-verified as sequential calls, not one combined-strength call, since
     *  LivingEntity.knockback halves existing momentum on each call before adding the new push. */
    private static void knockback(LivingEntity target, Pos source, float extra) {
        double dx = target.getPosition().x() - source.x();
        double dz = target.getPosition().z() - source.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            dx = RANDOM.nextDouble() - 0.5;
            dz = RANDOM.nextDouble() - 0.5;
            len = Math.sqrt(dx * dx + dz * dz);
        }
        target.takeKnockback(0.4f, -dx / len, -dz / len);
        if (extra > 0f) target.takeKnockback(extra, -dx / len, -dz / len);
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
        if (e.getEntity() instanceof EntityCreature mob && mob.getInstance() != null) {
            UUID credited = creditedPlayer(e.getDamage().getAttacker());
            if (credited != null) {
                LAST_HURT_BY_PLAYER.put(mob.getEntityId(),
                        new PlayerCredit(credited, mob.getInstance().getWorldAge() + HURT_MEMORY_TICKS));
            }
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
        Entity attacker = mob.getLastDamageSource() != null ? mob.getLastDamageSource().getAttacker() : null;
        // Zombie.killedEntity: a converted villager never runs the rest of death() (no loot,
        // no equipment drop, no xp) — see VillagerConversion.tryConvert.
        if (mob.getEntityType() == EntityType.VILLAGER
                && dev.pointofpressure.minecom.mobs.VillagerConversion.tryConvert(mob, instance, pos, attacker)) {
            return;
        }
        ItemStack weapon = ItemStack.AIR;
        Player killer = null;
        if (attacker instanceof Player p) {
            killer = p;
            weapon = killer.getItemInMainHand();
        }
        // slime/magma-cube size feeds the loot size predicate, xpReward and the split
        Integer slimeSize = mob.getTag(dev.pointofpressure.minecom.mobs.ai.VanillaMobs.SLIME_SIZE);
        for (ItemStack drop : LootTables.entityDrops(mob.getEntityType(), weapon, slimeSize)) {
            ItemEntity item = new ItemEntity(drop);
            item.setPickupDelay(Duration.ofMillis(500));
            item.setInstance(instance, pos.add(0, 0.5, 0));
            item.setVelocity(new Vec(RANDOM.nextDouble() - 0.5, 2, RANDOM.nextDouble() - 0.5));
        }
        PlayerCredit credit = LAST_HURT_BY_PLAYER.remove(mob.getEntityId());
        boolean killedByPlayer = killer != null
                || (credit != null && instance.getWorldAge() <= credit.expiresAtTick());
        if (killedByPlayer) dropEquipment(mob, instance, pos, weapon);
        boolean baby = mob.getEntityMeta() instanceof net.minestom.server.entity.metadata.monster.zombie.ZombieMeta zm && zm.isBaby();
        int xp = slimeSize != null ? slimeSize : Experience.mobXp(mob.getEntityType(), baby);
        if (xp > 0) Experience.orb(instance, pos, xp);
        dev.pointofpressure.minecom.mobs.ai.VanillaMobs.maybeSplitSlime(mob, instance, pos);
    }

    private static final EquipmentSlot[] DEATH_DROP_SLOTS = {
            EquipmentSlot.HELMET, EquipmentSlot.CHESTPLATE, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS,
            EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND};

    /**
     * Mob.dropCustomDeathLoot's equipment-drop pass (decompile-verified against
     * Mob.class): every worn/held slot independently rolls DropChances.DEFAULT_EQUIPMENT_
     * DROP_CHANCE (8.5%), gated on killedByPlayer at the call site (death()) — real vanilla's
     * lastHurtByPlayerMemoryTime > 0, i.e. hit by a player or that player's tamed wolf within
     * the last 100 ticks, not merely "the literal killing blow was a player's" (the "preserve"
     * guaranteed-drop path — skeleton pumpkin heads etc. — isn't modeled by any factory in
     * this codebase, so isPreserved() is always false here). The killer's Looting level raises
     * the chance
     * additively per data/minecraft/enchantment/looting.json's equipment_drops effect
     * (base 0.01, +0.01 per level above 1 — i.e. flat +1%/level, max level 3), which is
     * the one concrete case pulled out of the otherwise-unported generic enchantment-
     * effect-component system (real EnchantmentHelper.processEquipmentDropChance is
     * fully data-driven over ALL equipment_drops effects on the killer's gear; porting
     * that generically is the same "no enchantment system exists" gap already tracked
     * for loot tables/enchanting). A surviving item's durability is randomized down
     * toward "well-worn" on drop, matching Mob.class's exact
     * maxDamage - random.nextInt(1 + random.nextInt(max(maxDamage-3,1))) formula.
     */
    private static void dropEquipment(EntityCreature mob, Instance instance, Pos pos, ItemStack killerWeapon) {
        int lootingLevel = dev.pointofpressure.minecom.data.Enchants.level(killerWeapon, "looting");
        float dropChance = 0.085f + 0.01f * lootingLevel;
        for (EquipmentSlot slot : DEATH_DROP_SLOTS) {
            ItemStack equipped = mob.getEquipment(slot);
            if (equipped.isAir()) continue;
            if (RANDOM.nextFloat() >= dropChance) continue;
            ItemStack drop = equipped;
            Integer maxDamage = drop.get(net.minestom.server.component.DataComponents.MAX_DAMAGE);
            if (maxDamage != null && maxDamage > 0) {
                int damageValue = maxDamage - RANDOM.nextInt(1 + RANDOM.nextInt(Math.max(maxDamage - 3, 1)));
                int finalDamage = damageValue;
                drop = drop.with(b -> b.set(net.minestom.server.component.DataComponents.DAMAGE, Math.max(0, finalDamage)));
            }
            ItemEntity item = new ItemEntity(drop);
            item.setPickupDelay(Duration.ofMillis(500));
            item.setInstance(instance, pos.add(0, 0.5, 0));
            item.setVelocity(new Vec(RANDOM.nextDouble() - 0.5, 2, RANDOM.nextDouble() - 0.5));
            mob.setEquipment(slot, ItemStack.AIR);
        }
    }

    /** LivingEntity.resolvePlayerResponsibleForDamage: a direct player hit, or a tamed wolf's
     *  hit credited to its owner (real vanilla's other branch — an untamed/ownerless wolf's
     *  hit credits nobody, same as here). */
    private static UUID creditedPlayer(Entity damager) {
        if (damager instanceof Player p) return p.getUuid();
        if (damager instanceof EntityCreature wolf && wolf.getEntityType() == EntityType.WOLF
                && wolf.getEntityMeta() instanceof net.minestom.server.entity.metadata.animal.tameable.WolfMeta wm
                && wm.isTamed() && wm.getOwner() != null) {
            return wm.getOwner();
        }
        return null;
    }

}
