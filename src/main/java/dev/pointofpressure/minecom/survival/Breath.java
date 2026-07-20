package dev.pointofpressure.minecom.survival;

import dev.pointofpressure.minecom.TickPipeline;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drowning: holding your breath underwater has a limit. Air starts full (300 ticks/15s),
 * drains 1/tick while the eyes are submerged (Respiration reduces the chance of losing a
 * tick, extending the effective time — LivingEntity.tickBreathing), refills once
 * surfaced, and once air is depleted you take 2 damage every second until you come up
 * for air. There was previously no consequence at all for staying underwater.
 */
public final class Breath {
    private Breath() {}

    private static final int MAX_AIR = 300;
    private static final Random RANDOM = new Random();
    private static final Map<UUID, Integer> AIR = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> DROWN_TIMER = new ConcurrentHashMap<>();

    public static void start(Instance instance) {
        TickPipeline.register(TickPipeline.ENTITIES, "breath", () -> tick(instance));
    }

    private static void tick(Instance instance) {
        for (Player p : instance.getPlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.isDead()) continue;
            if (dev.pointofpressure.minecom.survival.Potions.effectLevel(p,
                    net.minestom.server.potion.PotionEffect.WATER_BREATHING) > 0) {
                AIR.put(p.getUuid(), MAX_AIR);
                DROWN_TIMER.put(p.getUuid(), 0);
                continue;
            }

            int air = AIR.getOrDefault(p.getUuid(), MAX_AIR);
            if (isEyeInWater(p)) {
                int respiration = dev.pointofpressure.minecom.data.Enchants.level(
                        p.getEquipment(EquipmentSlot.HELMET), "respiration");
                if (respiration == 0 || RANDOM.nextInt(respiration + 1) == 0) air--;

                if (air <= 0) {
                    air = 0;
                    int timer = DROWN_TIMER.merge(p.getUuid(), 1, Integer::sum);
                    if (timer >= 20) {
                        p.damage(DamageType.DROWN, 2f);
                        DROWN_TIMER.put(p.getUuid(), 0);
                    }
                } else {
                    DROWN_TIMER.put(p.getUuid(), 0);
                }
            } else {
                air = MAX_AIR;
                DROWN_TIMER.put(p.getUuid(), 0);
            }
            AIR.put(p.getUuid(), air);
        }
    }

    private static boolean isEyeInWater(Player p) {
        var eye = p.getPosition().add(0, p.getEyeHeight(), 0);
        Block b = p.getInstance().getBlock(eye);
        return b.compare(Block.WATER) || b.compare(Block.BUBBLE_COLUMN);
    }

    /** Test hook: remaining air (ticks), for verifying depletion/refill directly. */
    public static int air(Player p) {
        return AIR.getOrDefault(p.getUuid(), MAX_AIR);
    }

    /** Test hook: force air to a value (skips the real 300-tick wait to reach 0). */
    public static void testSetAir(Player p, int value) {
        AIR.put(p.getUuid(), value);
    }
}
