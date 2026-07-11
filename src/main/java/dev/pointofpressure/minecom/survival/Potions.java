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
        if (player.getGameMode() != net.minestom.server.entity.GameMode.CREATIVE) {
            player.setItemInHand(e.getHand(), ItemStack.of(Material.GLASS_BOTTLE));
        }
    }

    /** Apply a potion type's effects with vanilla durations (ticks). Any LivingEntity
     *  (not just players) can drink one — reused by VanillaMobs.witch() for its own
     *  self-potion-drinking AI, which needs the exact same duration table. */
    public static void apply(LivingEntity player, PotionType type) {
        String key = type.key().value();
        boolean strong = key.startsWith("strong_");
        boolean isLong = key.startsWith("long_");
        String base = key.replace("strong_", "").replace("long_", "");
        int amp = strong ? 1 : 0;

        switch (base) {
            case "swiftness" -> player.addEffect(new Potion(PotionEffect.SPEED, (byte) amp,
                    isLong ? 9600 : strong ? 1800 : 3600));
            case "slowness" -> player.addEffect(new Potion(PotionEffect.SLOWNESS, (byte) (strong ? 3 : 0),
                    isLong ? 4800 : strong ? 400 : 1800));
            case "strength" -> player.addEffect(new Potion(PotionEffect.STRENGTH, (byte) amp,
                    isLong ? 9600 : strong ? 1800 : 3600));
            case "weakness" -> player.addEffect(new Potion(PotionEffect.WEAKNESS, (byte) 0,
                    isLong ? 4800 : 1800));
            case "regeneration" -> player.addEffect(new Potion(PotionEffect.REGENERATION, (byte) amp,
                    isLong ? 1800 : strong ? 450 : 900));
            case "poison" -> player.addEffect(new Potion(PotionEffect.POISON, (byte) amp,
                    isLong ? 1800 : strong ? 420 : 900));
            case "fire_resistance" -> player.addEffect(new Potion(PotionEffect.FIRE_RESISTANCE, (byte) 0,
                    isLong ? 9600 : 3600));
            case "leaping" -> player.addEffect(new Potion(PotionEffect.JUMP_BOOST, (byte) amp,
                    isLong ? 9600 : strong ? 1800 : 3600));
            case "night_vision" -> player.addEffect(new Potion(PotionEffect.NIGHT_VISION, (byte) 0,
                    isLong ? 9600 : 3600));
            case "invisibility" -> player.addEffect(new Potion(PotionEffect.INVISIBILITY, (byte) 0,
                    isLong ? 9600 : 3600));
            case "water_breathing" -> player.addEffect(new Potion(PotionEffect.WATER_BREATHING, (byte) 0,
                    isLong ? 9600 : 3600));
            case "healing" -> {
                float maxHealth = (float) player.getAttributeValue(
                        net.minestom.server.entity.attribute.Attribute.MAX_HEALTH);
                player.setHealth(Math.min(maxHealth, player.getHealth() + (4 << (strong ? 1 : 0))));
            }
            case "harming" -> player.damage(net.minestom.server.entity.damage.DamageType.MAGIC,
                    6 << (strong ? 1 : 0));
            default -> { }
        }
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
