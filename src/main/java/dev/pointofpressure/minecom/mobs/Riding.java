package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.data.VanillaData;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.animal.AbstractHorseMeta;
import net.minestom.server.entity.metadata.animal.AnimalMeta;
import net.minestom.server.entity.metadata.animal.ChestedHorseMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Horse-family taming-by-riding, saddling, player-steered movement, feeding, and
 * donkey/mule chest cargo. Verified against decompiled AbstractHorse/Horse/
 * AbstractChestedHorse/Donkey/Mule/SkeletonHorse/ZombieHorse (26.2).
 * <p>
 * Horse/donkey/mule/zombie_horse are tameable by riding an untamed one with an
 * empty hand: mounting always succeeds regardless of tame state (a non-food,
 * non-empty item on an untamed mount instead bucks — {@code makeMad}, consuming the
 * interaction without mounting), and {@code mobs.ai.Goals.RunAroundLikeCrazy}
 * (wired into the horse-family brain below) rolls temper/maxTemper(100) every ~50
 * ticks while ridden and untamed — success tames and sets the rider as owner,
 * failure ejects them with +5 temper. Skeleton horses are never player-tameable
 * this way: real vanilla's SkeletonHorse.mobInteract PASSes on every interaction
 * while untamed (they're normally already-tame via the lightning trap, which this
 * project doesn't generate — see AUDIT.md). Feeding follows
 * AbstractHorse.handleEating exactly (wheat/sugar/apple/carrot +3 temper, golden
 * carrot +5, golden/enchanted golden apple +10, hay heals 20 with no temper, temper
 * only applies if the feed actually healed or the horse is still untamed). Once
 * tamed and saddled, the first passenger steers: forward/strafe scaled by the
 * MOVEMENT_SPEED attribute (sideways halved, backward quartered —
 * AbstractHorse.getRiddenInput's exact factors), horse yaw snaps to the rider's
 * (AbstractHorse.getRiddenRotation), and a jump-tap applies the JUMP_STRENGTH
 * attribute at full power — real vanilla charges jump height by holding the key
 * (0.4-1.0 scale over 90 ticks) and networks a client-side charge bar this project
 * has no infrastructure for, so this is a deliberate immediate-jump simplification,
 * not a silent fake. Donkeys/mules with an equipped chest get a CHEST_3_ROW cargo
 * inventory on a sneak right-click (vanilla's real menu is a custom 3x5 grid plus
 * saddle/armor widgets — Minestom has no horse InventoryType and this project has
 * no custom-slot-click menu framework, so this is the same "plain chest as cargo
 * hold" simplification precedent as everywhere else containers appear here).
 * Jump-strength/speed/health breeding inheritance
 * (AbstractHorse.createOffspringAttribute) is ported into Breeding.java. Not
 * modeled: horse body armor (AUDIT.md, same simplification as wolf armor),
 * rearing/eating animation state and their sounds (client visuals), foals
 * following their bred mother, donkey/mule per-species movement-speed/jump-strength
 * attribute deltas beyond createBase*HorseAttributes (already applied at spawn).
 */
public final class Riding {
    private Riding() {}

    public static final Set<EntityType> HORSE_FAMILY = Set.of(
            EntityType.HORSE, EntityType.DONKEY, EntityType.MULE,
            EntityType.SKELETON_HORSE, EntityType.ZOMBIE_HORSE);
    private static final Set<EntityType> CHESTED = Set.of(EntityType.DONKEY, EntityType.MULE);
    /** Skeleton horses are excluded: real vanilla never lets you mount one untamed. */
    private static final Set<EntityType> RIDING_TAMEABLE = Set.of(
            EntityType.HORSE, EntityType.DONKEY, EntityType.MULE, EntityType.ZOMBIE_HORSE);

    private static final int MAX_TEMPER = 100;
    public static final Tag<Integer> TEMPER = Tag.Integer("minecom:horse_temper").defaultValue(0);
    public static final Tag<UUID> OWNER = Tag.UUID("minecom:horse_owner");
    private static final Map<Integer, Inventory> CHESTS = new ConcurrentHashMap<>();

    // breeding: self-contained rather than folded into Breeding.java's generic
    // same-species pairing, since horse x donkey crosses into a third species
    // (mule) and needs the attribute-inheritance step Breeding.java has no concept
    // of — see the class javadoc.
    private static final Map<Integer, Long> IN_LOVE = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> COOLDOWN = new ConcurrentHashMap<>();
    private static Instance overworld;
    private static long tick;
    private static final float MIN_HEALTH = 15, MAX_HEALTH = 30;
    private static final double MIN_JUMP = 0.4, MAX_JUMP = 1.0;
    private static final double MIN_SPEED = 0.1125, MAX_SPEED = 0.3375;

    // Tuned feel, not a port of vanilla's friction/drag travel() integration (same
    // precedent as HappyGhastMob's RIDDEN_INPUT_SCALE): converts the MOVEMENT_SPEED
    // attribute (0.1125-0.3375 for horses) into a blocks/second ground speed.
    private static final double RIDDEN_SPEED_SCALE = 43.0;
    private static final float BACKWARDS_FACTOR = 0.25f;
    private static final float SIDEWAYS_FACTOR = 0.5f;

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerEntityInteractEvent.class, Riding::interact);
    }

    /** Mirrors Breeding.start's single-overworld-instance precedent. */
    public static void start(Instance overworldInstance) {
        overworld = overworldInstance;
        MinecraftServer.getSchedulerManager().buildTask(Riding::pairTick)
                .repeat(TaskSchedule.tick(20)).schedule();
    }

    // ------------------------------------------------------------------ interaction

    private static void interact(PlayerEntityInteractEvent e) {
        if (e.getHand() != PlayerHand.MAIN) return;
        if (!(e.getTarget() instanceof EntityCreature mob) || mob.isDead()) return;
        if (!HORSE_FAMILY.contains(mob.getEntityType())) return;
        if (!(mob.getEntityMeta() instanceof AbstractHorseMeta meta)) return;
        if (meta.isBaby() || !mob.getPassengers().isEmpty()) return;
        Player player = e.getPlayer();

        if (meta.isTamed() && player.isSneaking()) {
            openInventory(mob, player);
            return;
        }
        ItemStack held = player.getItemInMainHand();
        if (!held.isAir()) {
            if (VanillaData.itemHasTag(held.material(), "horse_food")) {
                feed(mob, meta, held, player);
                return;
            }
            if (!meta.isTamed()) {
                // AbstractHorse.makeMad: a non-food item on an untamed mount just bucks
                // in place, consuming the interaction without mounting.
                return;
            }
            if (held.material() == Material.SADDLE && mob.getEquipment(EquipmentSlot.SADDLE).isAir()) {
                mob.setEquipment(EquipmentSlot.SADDLE, ItemStack.of(Material.SADDLE));
                consume(player, held);
                return;
            }
            if (CHESTED.contains(mob.getEntityType()) && held.material() == Material.CHEST
                    && meta instanceof ChestedHorseMeta chestedMeta && !chestedMeta.isHasChest()) {
                chestedMeta.setHasChest(true);
                consume(player, held);
                return;
            }
        }
        if (!meta.isTamed() && !RIDING_TAMEABLE.contains(mob.getEntityType())) return;
        mob.addPassenger(player);
    }

    private static void feed(EntityCreature mob, AbstractHorseMeta meta, ItemStack item, Player player) {
        Material m = item.material();
        float heal = 0;
        int temperGain = 0;
        if (m == Material.WHEAT || m == Material.SUGAR || m == Material.APPLE || m == Material.CARROT) {
            heal = m == Material.SUGAR ? 1 : m == Material.APPLE || m == Material.CARROT ? 3 : 2;
            temperGain = 3;
        } else if (m == Material.HAY_BLOCK) {
            heal = 20;
        } else if (m == Material.GOLDEN_CARROT) {
            heal = 4;
            temperGain = 5;
        } else if (m == Material.GOLDEN_APPLE || m == Material.ENCHANTED_GOLDEN_APPLE) {
            heal = 10;
            temperGain = 10;
        }
        boolean healed = heal > 0 && mob.getHealth() < mob.getAttributeValue(Attribute.MAX_HEALTH);
        if (healed) {
            mob.setHealth((float) Math.min(mob.getAttributeValue(Attribute.MAX_HEALTH), mob.getHealth() + heal));
        }
        if (temperGain > 0 && (healed || !meta.isTamed())) {
            setTemper(mob, Math.min(MAX_TEMPER, temper(mob) + temperGain));
        }
        boolean lovable = m == Material.GOLDEN_CARROT || m == Material.GOLDEN_APPLE || m == Material.ENCHANTED_GOLDEN_APPLE;
        boolean entersLove = lovable && meta.isTamed() && !meta.isBaby()
                && COOLDOWN.getOrDefault(mob.getEntityId(), 0L) <= tick
                && !IN_LOVE.containsKey(mob.getEntityId());
        if (entersLove) IN_LOVE.put(mob.getEntityId(), tick + 600); // 30s of love, same window as Breeding.java
        if (healed || temperGain > 0 || entersLove) consume(player, item);
    }

    private static void consume(Player player, ItemStack item) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setItemInMainHand(item.consume(1));
        }
    }

    private static void openInventory(EntityCreature mob, Player player) {
        if (!CHESTED.contains(mob.getEntityType())) return;
        if (!(mob.getEntityMeta() instanceof ChestedHorseMeta chestedMeta) || !chestedMeta.isHasChest()) return;
        Inventory inv = CHESTS.computeIfAbsent(mob.getEntityId(),
                id -> new Inventory(InventoryType.CHEST_3_ROW, Component.text("Cargo")));
        player.openInventory(inv);
    }

    // ------------------------------------------------------------------ temper / taming

    public static int temper(EntityCreature mob) {
        return mob.getTag(TEMPER);
    }

    public static void setTemper(EntityCreature mob, int value) {
        mob.setTag(TEMPER, value);
    }

    /** RunAroundLikeCrazyGoal.tick's roll, called from Goals.RunAroundLikeCrazy. */
    public static void tameRoll(EntityCreature mob, Player rider) {
        if (!(mob.getEntityMeta() instanceof AbstractHorseMeta meta) || meta.isTamed()) return;
        int temper = temper(mob);
        if (ThreadLocalRandom.current().nextInt(MAX_TEMPER) < temper) {
            meta.setTamed(true);
            mob.setTag(OWNER, rider.getUuid());
        } else {
            setTemper(mob, Math.min(MAX_TEMPER, temper + 5));
            mob.removePassenger(rider);
        }
    }

    public static boolean isTamed(EntityCreature mob) {
        return mob.getEntityMeta() instanceof AbstractHorseMeta meta && meta.isTamed();
    }

    public static UUID ownerOf(EntityCreature mob) {
        return mob.getTag(OWNER);
    }

    // ------------------------------------------------------------------ ridden steering

    private static final Map<Integer, Boolean> JUMP_HELD = new ConcurrentHashMap<>();

    /**
     * AbstractHorse.tickRidden + getRiddenInput + executeRidersJump, folded into one
     * velocity-space step (same pattern as HappyGhastMob.tickRidden). Called once
     * per tick from the horse-family spawn factory's own per-mob scheduler task
     * (mobs.ai.VanillaMobs.horseFamily) — a no-op whenever the mob has no Player
     * passenger, isn't tamed, or isn't saddled.
     */
    public static void tick(EntityCreature mob) {
        Player rider = mob.getPassengers().stream()
                .filter(p -> p instanceof Player).map(p -> (Player) p).findFirst().orElse(null);
        if (rider == null) return;
        if (rider.inputs().shift()) {
            mob.removePassenger(rider);
            JUMP_HELD.remove(mob.getEntityId());
            return;
        }
        if (!(mob.getEntityMeta() instanceof AbstractHorseMeta meta) || !meta.isTamed()
                || mob.getEquipment(EquipmentSlot.SADDLE).isAir()) {
            return;
        }
        var inputs = rider.inputs();
        float sideways = (float) ((inputs.left() ? 1 : 0) - (inputs.right() ? 1 : 0)) * SIDEWAYS_FACTOR;
        float forward = (float) ((inputs.forward() ? 1 : 0) - (inputs.backward() ? 1 : 0));
        if (forward <= 0) forward *= BACKWARDS_FACTOR;

        float yaw = rider.getPosition().yaw();
        mob.setView(yaw, mob.getPosition().pitch());

        double speed = mob.getAttributeValue(Attribute.MOVEMENT_SPEED) * RIDDEN_SPEED_SCALE;
        double yawRad = Math.toRadians(yaw);
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        Vec horizontal = new Vec(
                (sideways * cos - forward * sin) * speed,
                0,
                (forward * cos + sideways * sin) * speed);
        Vec current = mob.getVelocity();
        mob.setVelocity(new Vec(horizontal.x(), current.y(), horizontal.z()));

        boolean jumping = inputs.jump();
        boolean wasHeld = JUMP_HELD.getOrDefault(mob.getEntityId(), false);
        if (jumping && !wasHeld && mob.isOnGround()) {
            double impulse = mob.getAttributeValue(Attribute.JUMP_STRENGTH) * 20.0;
            mob.setVelocity(mob.getVelocity().add(0, impulse, 0));
        }
        JUMP_HELD.put(mob.getEntityId(), jumping);
    }

    // ------------------------------------------------------------------ breeding

    private static void pairTick() {
        tick += 20;
        IN_LOVE.entrySet().removeIf(en -> en.getValue() < tick);
        if (IN_LOVE.size() < 2 || overworld == null) return;
        var lovers = overworld.getEntities().stream()
                .filter(en -> en instanceof EntityCreature c && !c.isDead() && IN_LOVE.containsKey(c.getEntityId()))
                .map(en -> (EntityCreature) en).toList();
        for (int i = 0; i < lovers.size(); i++) {
            for (int j = i + 1; j < lovers.size(); j++) {
                EntityCreature a = lovers.get(i), b = lovers.get(j);
                String babyKind = crossKind(a.getEntityType(), b.getEntityType());
                if (babyKind == null || a.getPosition().distance(b.getPosition()) > 8) continue;
                IN_LOVE.remove(a.getEntityId());
                IN_LOVE.remove(b.getEntityId());
                COOLDOWN.put(a.getEntityId(), tick + 6000);
                COOLDOWN.put(b.getEntityId(), tick + 6000);
                spawnFoal(babyKind, a, b);
                return; // one pair per cycle, matching Breeding.java's precedent
            }
        }
    }

    /** Horse.getBreedOffspring / Donkey.getBreedOffspring: same-species stays, a
     *  horse x donkey cross is always a (sterile) mule. Mule is never a parent. */
    private static String crossKind(EntityType a, EntityType b) {
        if (a == EntityType.HORSE && b == EntityType.HORSE) return "horse";
        if (a == EntityType.DONKEY && b == EntityType.DONKEY) return "donkey";
        if ((a == EntityType.HORSE && b == EntityType.DONKEY) || (a == EntityType.DONKEY && b == EntityType.HORSE)) {
            return "mule";
        }
        return null;
    }

    private static void spawnFoal(String kind, EntityCreature a, EntityCreature b) {
        EntityCreature baby = dev.pointofpressure.minecom.mobs.Mobs.spawn(kind, overworld, a.getPosition());
        if (baby == null) return;
        if (baby.getEntityMeta() instanceof AnimalMeta babyMeta) babyMeta.setBaby(true);
        // Both parents are necessarily tamed (see the entersLove gate in feed()), and a
        // bred foal is tame/owned in real vanilla play; the exact vanilla code path for
        // this (outside AbstractHorse's own getBreedOffspring, which only sets
        // attributes) wasn't traced, so this is inferred-correct behavior, not a
        // decompile-cited line like the rest of this class.
        if (baby.getEntityMeta() instanceof AbstractHorseMeta babyHorseMeta) babyHorseMeta.setTamed(true);
        UUID owner = a.getTag(OWNER);
        baby.setTag(OWNER, owner != null ? owner : b.getTag(OWNER));
        inheritAttributes(baby, a, b);
        baby.scheduler().buildTask(() -> {
            if (!baby.isDead() && baby.getEntityMeta() instanceof AnimalMeta m) m.setBaby(false);
        }).delay(TaskSchedule.tick(24000)).schedule(); // grows up in 20 min, same window as Breeding.java
        dev.pointofpressure.minecom.survival.Experience.orb(overworld, a.getPosition(), 1 + (int) (Math.random() * 7));
    }

    /** AbstractHorse.setOffspringAttributes: health/jump/speed each independently inherited. */
    private static void inheritAttributes(EntityCreature baby, EntityCreature a, EntityCreature b) {
        float health = (float) offspringAttribute(
                a.getAttributeValue(Attribute.MAX_HEALTH), b.getAttributeValue(Attribute.MAX_HEALTH), MIN_HEALTH, MAX_HEALTH);
        baby.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);
        baby.setHealth(health);
        baby.getAttribute(Attribute.JUMP_STRENGTH).setBaseValue(offspringAttribute(
                a.getAttributeValue(Attribute.JUMP_STRENGTH), b.getAttributeValue(Attribute.JUMP_STRENGTH), MIN_JUMP, MAX_JUMP));
        baby.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(offspringAttribute(
                a.getAttributeValue(Attribute.MOVEMENT_SPEED), b.getAttributeValue(Attribute.MOVEMENT_SPEED), MIN_SPEED, MAX_SPEED));
    }

    /** AbstractHorse.createOffspringAttribute, exact port: clamp both parents into
     *  range, average +- a margin-widened spread scaled by a 3-uniform "quality"
     *  roll centered on 0, reflected back into range if it overshoots. */
    private static double offspringAttribute(double parentA, double parentB, double min, double max) {
        parentA = Math.max(min, Math.min(max, parentA));
        parentB = Math.max(min, Math.min(max, parentB));
        double margin = 0.15 * (max - min);
        double range = Math.abs(parentA - parentB) + margin * 2.0;
        double average = (parentA + parentB) / 2.0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double quality = (r.nextDouble() + r.nextDouble() + r.nextDouble()) / 3.0 - 0.5;
        double value = average + range * quality;
        if (value > max) return max - (value - max);
        if (value < min) return min + (min - value);
        return value;
    }
}
