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
import net.minestom.server.instance.block.Block;
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
        boolean openWater = isOpenWater(instance, bobberPos);
        for (ItemStack loot : LootTables.gameplay("fishing", player.getItemInMainHand(), openWater)) {
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

    private enum WaterLayer { ABOVE, INSIDE, INVALID }

    /**
     * FishingHook.calculateOpenWater (decompile-verified): four horizontal layers,
     * y-offsets -1..2 from the bobber's block, each a 5x5 area (x/z -2..2). A layer is
     * ABOVE only if every block in it is air or a lily pad; INSIDE only if every block
     * is a water source block with no collision shape; any mix within a layer, or any
     * non-water/non-air block, makes that layer INVALID and fails the whole check
     * immediately. The valid sequence is INSIDE layers first, optionally transitioning
     * to ABOVE layers once (never back to INSIDE) — i.e. clear water with clear air
     * above it, gated on ALL four layers agreeing. Real vanilla also requires the
     * water be a source (not flowing); this project's water blocks track that via the
     * same "level" property Fluids.java's own flow engine uses (absent/0 = source).
     * Only the treasure pool of the real fishing loot table is gated on this
     * (data/minecraft/loot_table/gameplay/fishing.json's is_open_water predicate,
     * which the generic LootTables condition evaluator now reads off this flag) —
     * previously that predicate was silently ignored, so treasure could drop from any
     * fishable puddle, not just real open water.
     */
    private static boolean isOpenWater(Instance instance, Pos bobberPos) {
        int bx = bobberPos.blockX(), by = bobberPos.blockY(), bz = bobberPos.blockZ();
        WaterLayer previous = WaterLayer.INVALID;
        for (int dy = -1; dy <= 2; dy++) {
            WaterLayer layer = layerType(instance, bx, by + dy, bz);
            switch (layer) {
                case ABOVE -> { if (previous == WaterLayer.INVALID) return false; }
                case INSIDE -> { if (previous == WaterLayer.ABOVE) return false; }
                case INVALID -> { return false; }
            }
            previous = layer;
        }
        return true;
    }

    private static WaterLayer layerType(Instance instance, int cx, int cy, int cz) {
        WaterLayer result = null;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                WaterLayer t = blockType(instance, cx + dx, cy, cz + dz);
                if (result == null) result = t;
                else if (result != t) return WaterLayer.INVALID;
            }
        }
        return result == null ? WaterLayer.INVALID : result;
    }

    private static WaterLayer blockType(Instance instance, int x, int y, int z) {
        Block b = instance.getBlock(x, y, z);
        if (b.isAir() || b.key().value().equals("lily_pad")) return WaterLayer.ABOVE;
        if (!b.key().value().equals("water")) return WaterLayer.INVALID;
        String level = b.getProperty("level");
        return level == null || level.equals("0") ? WaterLayer.INSIDE : WaterLayer.INVALID;
    }

    /** Test hook: exposes isOpenWater directly, without needing a real cast/bite cycle. */
    public static boolean debugIsOpenWater(Instance instance, Pos pos) {
        return isOpenWater(instance, pos);
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
