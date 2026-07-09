package dev.pointofpressure.minecom.survival;

import dev.pointofpressure.minecom.data.Items;
import dev.pointofpressure.minecom.data.LootTables;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fishing: cast a bobber, wait 5-30 seconds for the bite (splash sound), reel in
 * within 1.5 seconds to catch from Mojang's real fishing loot tables (fish, junk
 * and treasure pools), 1-6 XP per catch, rod durability.
 */
public final class Fishing {
    private Fishing() {}

    private static final Random RANDOM = new Random();

    private static final class Cast {
        Entity bobber;
        long biteAtTick;
        long tick;
        boolean bitten;
    }

    private static final Map<UUID, Cast> CASTS = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemEvent.class, e -> {
            if (e.getItemStack().material() != Material.FISHING_ROD) return;
            Player player = e.getPlayer();
            Cast existing = CASTS.remove(player.getUuid());
            if (existing != null) {
                reel(player, existing);
            } else {
                cast(player);
            }
        });
    }

    /** Lure: reduces the random wait-time ceiling by 100 ticks (5s) per level, floor 20 ticks. */
    public static int lureRange(Player player) {
        int lure = dev.pointofpressure.minecom.data.Enchants.level(player.getItemInMainHand(), "lure");
        return Math.max(20, 500 - 100 * lure);
    }

    private static void cast(Player player) {
        Instance instance = player.getInstance();
        Cast cast = new Cast();
        cast.bobber = new Entity(EntityType.FISHING_BOBBER);
        Vec dir = player.getPosition().direction();
        cast.bobber.setInstance(instance, player.getPosition().add(0, player.getEyeHeight(), 0));
        cast.bobber.setVelocity(dir.mul(12).add(0, 3, 0));
        cast.biteAtTick = 100 + RANDOM.nextInt(lureRange(player)); // 5-30s, less with Lure
        CASTS.put(player.getUuid(), cast);

        cast.bobber.scheduler().buildTask(() -> {
            cast.tick++;
            if (cast.bobber.isRemoved()) return;
            if (!cast.bitten && cast.tick >= cast.biteAtTick) {
                // the bobber must be floating in water for the bite
                if (instance.getBlock(cast.bobber.getPosition()).key().value().equals("water")) {
                    cast.bitten = true;
                    cast.biteAtTick = cast.tick; // bite moment
                    player.playSound(Sound.sound(SoundEvent.ENTITY_FISHING_BOBBER_SPLASH,
                            Sound.Source.NEUTRAL, 1f, 1f), cast.bobber.getPosition());
                } else {
                    cast.biteAtTick = cast.tick + 60 + RANDOM.nextInt(200); // retry later
                }
            }
            if (cast.bitten && cast.tick > cast.biteAtTick + 30) {
                cast.bitten = false; // missed the window; fish escapes
                cast.biteAtTick = cast.tick + 100 + RANDOM.nextInt(lureRange(player));
            }
            if (cast.tick > 6000) { // stale line
                cast.bobber.remove();
                CASTS.remove(player.getUuid());
            }
        }).repeat(TaskSchedule.tick(1)).schedule();
    }

    private static void reel(Player player, Cast cast) {
        boolean caught = cast.bitten && cast.tick <= cast.biteAtTick + 30;
        Pos bobberPos = cast.bobber.getPosition();
        cast.bobber.remove();
        if (!caught) return;

        Instance instance = player.getInstance();
        for (ItemStack loot : LootTables.gameplay("fishing", player.getItemInMainHand())) {
            ItemEntity item = new ItemEntity(loot);
            item.setPickupDelay(Duration.ofMillis(200));
            item.setInstance(instance, bobberPos.add(0, 0.5, 0));
            // fling the catch toward the player
            Vec toPlayer = player.getPosition().add(0, 1, 0).sub(bobberPos).asVec();
            item.setVelocity(toPlayer.mul(0.35).add(0, Math.sqrt(toPlayer.length()) * 1.4, 0));
        }
        Experience.orb(instance, player.getPosition(), 1 + RANDOM.nextInt(6));
        player.setItemInMainHand(Items.damageItem(player, player.getItemInMainHand(), 1));
    }

    /** Test hook: is a cast active with a bite ready to reel? */
    public static boolean debugForceBite(Player player) {
        Cast cast = CASTS.get(player.getUuid());
        if (cast == null) return false;
        cast.bitten = true;
        cast.biteAtTick = cast.tick;
        return true;
    }
}
