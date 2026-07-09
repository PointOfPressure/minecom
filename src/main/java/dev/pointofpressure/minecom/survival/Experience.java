package dev.pointofpressure.minecom.survival;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ExperienceOrb;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.item.PickupExperienceEvent;
import net.minestom.server.event.player.PlayerDeathEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** XP orbs, the vanilla level curve, mob/ore/smelting experience, death penalty. */
public final class Experience {
    private Experience() {}

    private static final Random RANDOM = new Random();
    /** Total XP per player; exposed for persistence. */
    public static final Map<UUID, Integer> TOTAL = new ConcurrentHashMap<>();

    private static final net.minestom.server.entity.EquipmentSlot[] MENDABLE_SLOTS = {
            net.minestom.server.entity.EquipmentSlot.MAIN_HAND, net.minestom.server.entity.EquipmentSlot.OFF_HAND,
            net.minestom.server.entity.EquipmentSlot.HELMET, net.minestom.server.entity.EquipmentSlot.CHESTPLATE,
            net.minestom.server.entity.EquipmentSlot.LEGGINGS, net.minestom.server.entity.EquipmentSlot.BOOTS};

    public static void register(GlobalEventHandler events) {
        events.addListener(PickupExperienceEvent.class, e -> {
            if (mend(e.getPlayer(), e.getExperienceCount())) return;
            add(e.getPlayer(), e.getExperienceCount());
        });
        events.addListener(PlayerDeathEvent.class, e -> {
            Player p = e.getPlayer();
            int dropped = Math.min(100, p.getLevel() * 7);
            if (dropped > 0) orb(p.getInstance(), p.getPosition(), dropped);
            TOTAL.put(p.getUuid(), 0);
            sync(p);
        });
    }

    /**
     * enchantment/mending.json repair_with_xp (multiply factor 2): picks one random
     * damaged mending-enchanted item across hands+armor and repairs it 2 durability per
     * XP point absorbed, consuming the orb entirely instead of granting XP.
     */
    private static boolean mend(Player player, int xp) {
        java.util.List<net.minestom.server.entity.EquipmentSlot> candidates = new java.util.ArrayList<>();
        for (var slot : MENDABLE_SLOTS) {
            var item = player.getEquipment(slot);
            if (item.isAir()) continue;
            if (dev.pointofpressure.minecom.data.Enchants.level(item, "mending") <= 0) continue;
            Integer damage = item.get(net.minestom.server.component.DataComponents.DAMAGE);
            if (damage == null || damage <= 0) continue;
            candidates.add(slot);
        }
        if (candidates.isEmpty()) return false;
        var slot = candidates.get(RANDOM.nextInt(candidates.size()));
        var item = player.getEquipment(slot);
        int damage = item.get(net.minestom.server.component.DataComponents.DAMAGE);
        int repaired = Math.min(damage, xp * 2);
        player.setEquipment(slot, item.with(builder ->
                builder.set(net.minestom.server.component.DataComponents.DAMAGE, damage - repaired)));
        return true;
    }

    public static void add(Player player, int amount) {
        TOTAL.merge(player.getUuid(), amount, Integer::sum);
        sync(player);
    }

    public static void set(Player player, int total) {
        TOTAL.put(player.getUuid(), Math.max(0, total));
        sync(player);
    }

    /** Deduct whole levels (enchanting cost). */
    public static void takeLevels(Player player, int levels) {
        int current = player.getLevel();
        int target = Math.max(0, current - levels);
        int cost = xpForLevel(current) - xpForLevel(target);
        set(player, Math.max(0, total(player) - cost));
    }

    public static int total(Player player) {
        return TOTAL.getOrDefault(player.getUuid(), 0);
    }

    /** Push level + progress bar to the client using the vanilla level curve. */
    private static void sync(Player player) {
        int xp = total(player);
        int level = 0;
        while (xpForLevel(level + 1) <= xp) level++;
        int base = xpForLevel(level);
        int next = xpForLevel(level + 1);
        player.setLevel(level);
        player.setExp(next == base ? 0f : (float) (xp - base) / (next - base));
    }

    /** Total XP required to reach a level (vanilla formula). */
    public static int xpForLevel(int level) {
        if (level <= 16) return level * level + 6 * level;
        if (level <= 31) return (int) (2.5 * level * level - 40.5 * level + 360);
        return (int) (4.5 * level * level - 162.5 * level + 2220);
    }

    public static void orb(Instance instance, Point pos, int amount) {
        while (amount > 0) {
            short chunk = (short) Math.min(amount, 32);
            ExperienceOrb orb = new ExperienceOrb(chunk);
            orb.setInstance(instance, new Vec(
                    pos.x() + RANDOM.nextDouble() * 0.4 - 0.2,
                    pos.y() + 0.4,
                    pos.z() + RANDOM.nextDouble() * 0.4 - 0.2));
            amount -= chunk;
        }
    }

    /** Mob.getBaseExperienceReward (per-species base, before the baby 2.5x multiplier). */
    public static int mobXp(EntityType type) {
        return switch (type.key().value()) {
            case "zombie", "husk", "drowned", "zombie_villager", "parched",
                 "skeleton", "stray", "bogged", "wither_skeleton",
                 "spider", "creeper", "enderman", "witch",
                 "piglin", "hoglin", "zombified_piglin",
                 "pillager", "vindicator", "ghast", "slime", "magma_cube" -> 5;
            case "blaze", "evoker" -> 10;
            case "cow", "pig", "sheep", "chicken", "mooshroom", "rabbit", "goat", "horse",
                 "donkey", "llama", "turtle", "panda", "polar_bear", "armadillo", "camel", "fox", "frog",
                 "wolf", "parrot", "zombie_horse", "ocelot",
                 "squid", "glow_squid", "cod", "salmon", "pufferfish", "tropical_fish", "dolphin",
                 "axolotl", "nautilus" -> 1 + RANDOM.nextInt(3);
            default -> 0; // strider, bat, wandering_trader: no combat xp in vanilla either
        };
    }

    /** Baby zombie-family mobs give 2.5x xp (Zombie.getBaseExperienceReward). */
    public static int mobXp(EntityType type, boolean baby) {
        int base = mobXp(type);
        return baby ? (int) (base * 2.5) : base;
    }

    /** Vanilla ore mining XP (only when the ore itself doesn't drop, i.e. non-silk). */
    public static int oreXp(Block block) {
        String key = block.key().value().replace("deepslate_", "");
        return switch (key) {
            case "coal_ore" -> RANDOM.nextInt(3);
            case "diamond_ore", "emerald_ore" -> 3 + RANDOM.nextInt(5);
            case "lapis_ore" -> 2 + RANDOM.nextInt(4);
            case "redstone_ore" -> 1 + RANDOM.nextInt(5);
            default -> 0;
        };
    }
}
