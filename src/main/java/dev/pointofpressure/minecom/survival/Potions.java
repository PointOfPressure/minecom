package dev.pointofpressure.minecom.survival;

import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.item.PlayerFinishItemUseEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.potion.PotionType;
import net.minestom.server.timer.TaskSchedule;

/**
 * Potion effects: drinking applies the type's vanilla effects (instant health/
 * harming resolve immediately); regeneration and poison tick on a schedule;
 * strength/weakness/resistance hook into combat. Movement effects (speed,
 * slowness, jump boost) apply client-side through the effect packet.
 */
public final class Potions {
    private Potions() {}

    public static void register(GlobalEventHandler events, Instance instance) {
        events.addListener(PlayerFinishItemUseEvent.class, Potions::drink);
        MinecraftServer.getSchedulerManager().buildTask(() -> tickEffects(instance))
                .repeat(TaskSchedule.tick(25)).schedule();
    }

    private static void drink(PlayerFinishItemUseEvent e) {
        if (e.getItemStack().material() != Material.POTION) return;
        Player player = e.getPlayer();
        PotionContents contents = e.getItemStack().get(DataComponents.POTION_CONTENTS);
        if (contents != null && contents.potion() != null) {
            apply(player, contents.potion());
        }
        dev.pointofpressure.minecom.redstone.Vibrations.emit("drink", player.getPosition(), player);
        if (player.getGameMode() != net.minestom.server.entity.GameMode.CREATIVE) {
            player.setItemInHand(e.getHand(), ItemStack.of(Material.GLASS_BOTTLE));
        }
    }

    /** Apply a potion type's effects with vanilla durations (ticks). Any LivingEntity
     *  (not just players) can drink one — reused by VanillaMobs.witch() for its own
     *  self-potion-drinking AI, which needs the exact same duration table. */
    public static void apply(LivingEntity player, PotionType type) {
        apply(player, type, 1.0);
    }

    /**
     * Scaled variant for splash potions (strength falls off with distance) and
     * lingering clouds (1/4 duration). Timed effects scale their duration —
     * scaled results under 20 ticks are dropped (AbstractThrownPotion's
     * endsWithin(20) rule) — instant health/harming scale their magnitude.
     */
    public static void apply(LivingEntity player, PotionType type, double scale) {
        String key = type.key().value();
        boolean strong = key.startsWith("strong_");
        boolean isLong = key.startsWith("long_");
        String base = key.replace("strong_", "").replace("long_", "");
        int amp = strong ? 1 : 0;

        switch (base) {
            case "swiftness" -> timed(player, PotionEffect.SPEED, amp,
                    isLong ? 9600 : strong ? 1800 : 3600, scale);
            case "slowness" -> timed(player, PotionEffect.SLOWNESS, strong ? 3 : 0,
                    isLong ? 4800 : strong ? 400 : 1800, scale);
            case "strength" -> timed(player, PotionEffect.STRENGTH, amp,
                    isLong ? 9600 : strong ? 1800 : 3600, scale);
            case "weakness" -> timed(player, PotionEffect.WEAKNESS, 0,
                    isLong ? 4800 : 1800, scale);
            case "regeneration" -> timed(player, PotionEffect.REGENERATION, amp,
                    isLong ? 1800 : strong ? 450 : 900, scale);
            case "poison" -> timed(player, PotionEffect.POISON, amp,
                    isLong ? 1800 : strong ? 420 : 900, scale);
            case "fire_resistance" -> timed(player, PotionEffect.FIRE_RESISTANCE, 0,
                    isLong ? 9600 : 3600, scale);
            case "leaping" -> timed(player, PotionEffect.JUMP_BOOST, amp,
                    isLong ? 9600 : strong ? 1800 : 3600, scale);
            case "night_vision" -> timed(player, PotionEffect.NIGHT_VISION, 0,
                    isLong ? 9600 : 3600, scale);
            case "invisibility" -> timed(player, PotionEffect.INVISIBILITY, 0,
                    isLong ? 9600 : 3600, scale);
            case "water_breathing" -> timed(player, PotionEffect.WATER_BREATHING, 0,
                    isLong ? 9600 : 3600, scale);
            case "healing" -> {
                float maxHealth = (float) player.getAttributeValue(
                        net.minestom.server.entity.attribute.Attribute.MAX_HEALTH);
                player.setHealth(Math.min(maxHealth,
                        player.getHealth() + (float) ((4 << (strong ? 1 : 0)) * scale)));
            }
            case "harming" -> player.damage(net.minestom.server.entity.damage.DamageType.MAGIC,
                    (float) ((6 << (strong ? 1 : 0)) * scale));
            default -> { }
        }
    }

    private static void timed(LivingEntity player, PotionEffect effect, int amp,
                              int baseDuration, double scale) {
        int duration = (int) (baseDuration * scale + 0.5);
        if (duration < 20) return;
        player.addEffect(new Potion(effect, (byte) amp, duration));
    }

    public static int effectLevel(Player player, PotionEffect effect) {
        for (var timed : player.getActiveEffects()) {
            if (timed.potion().effect() == effect) return timed.potion().amplifier() + 1;
        }
        return 0;
    }

    /** Regeneration heals and poison bites every 25 ticks (vanilla base cadence, amp halves it). */
    private static void tickEffects(Instance instance) {
        for (Player player : instance.getPlayers()) {
            if (player.isDead()) continue;
            int regen = effectLevel(player, PotionEffect.REGENERATION);
            if (regen > 0) {
                float maxHealth = (float) player.getAttributeValue(
                        net.minestom.server.entity.attribute.Attribute.MAX_HEALTH);
                player.setHealth(Math.min(maxHealth, player.getHealth() + 1));
            }
            int poison = effectLevel(player, PotionEffect.POISON);
            if (poison > 0 && player.getHealth() > 1) {
                player.damage(net.minestom.server.entity.damage.DamageType.MAGIC, 1);
            }
        }
    }
}
