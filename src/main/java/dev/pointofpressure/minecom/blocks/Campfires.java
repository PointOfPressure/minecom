package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.data.Recipes;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Campfire cooking: real 4-slot per-slot progress (CampfireBlockEntity.cookTick/cooldownTick)
 * against the bundled {@code campfire_cooking} recipe data (600-tick default, e.g. raw meat ->
 * cooked meat). Standing-in-lit-campfire fire damage lives in {@link
 * dev.pointofpressure.minecom.survival.Survival} alongside the analogous lava-damage check,
 * since it's a per-player-tick hazard check, not per-block-entity state.
 */
public final class Campfires {
    private Campfires() {}

    private static final class State {
        final ItemStack[] items = new ItemStack[4];
        final int[] cookingProgress = new int[4];
        final int[] cookingTime = new int[4];
        Instance instance;
        Point pos;
    }

    private static final Map<String, State> CAMPFIRES = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        MinecraftServer.getSchedulerManager().buildTask(Campfires::tickAll)
                .repeat(TaskSchedule.tick(1))
                .schedule();
        events.addListener(PlayerUseItemOnBlockEvent.class, Campfires::useOnBlock);
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        String key = instance.getBlock(pos).key().value();
        if (!key.equals("campfire") && !key.equals("soul_campfire")) return;

        ItemStack held = e.getItemStack();
        if (held.isAir()) return;
        Recipes.Cook recipe = Recipes.campfireCook(held.material());
        if (recipe == null) return;

        State state = CAMPFIRES.computeIfAbsent(Containers.posKey(pos), k -> new State());
        state.instance = instance;
        state.pos = pos;
        for (int slot = 0; slot < 4; slot++) {
            if (state.items[slot] == null) {
                state.cookingTime[slot] = recipe.cookTicks();
                state.cookingProgress[slot] = 0;
                state.items[slot] = held.withAmount(1);
                e.getPlayer().setItemInHand(e.getHand(), held.consume(1));
                return;
            }
        }
    }

    private static void tickAll() {
        for (State s : CAMPFIRES.values()) tick(s);
    }

    private static void tick(State s) {
        if (s.instance == null) return;
        Block block = s.instance.getBlock(s.pos);
        String key = block.key().value();
        if (!key.equals("campfire") && !key.equals("soul_campfire")) {
            for (ItemStack item : s.items) if (item != null) drop(s.instance, s.pos, item);
            CAMPFIRES.remove(Containers.posKey(s.pos));
            return;
        }

        boolean lit = "true".equals(block.getProperty("lit"));
        for (int slot = 0; slot < 4; slot++) {
            if (s.items[slot] == null) continue;
            if (lit) {
                if (++s.cookingProgress[slot] >= s.cookingTime[slot]) {
                    Recipes.Cook recipe = Recipes.campfireCook(s.items[slot].material());
                    drop(s.instance, s.pos, recipe != null ? recipe.result() : s.items[slot]);
                    s.items[slot] = null;
                    s.cookingProgress[slot] = 0;
                }
            } else {
                // BURN_COOL_SPEED = 2
                s.cookingProgress[slot] = Math.max(0, s.cookingProgress[slot] - 2);
            }
        }
    }

    private static void drop(Instance instance, Point pos, ItemStack item) {
        ItemEntity drop = new ItemEntity(item);
        drop.setInstance(instance, new Pos(pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5));
        drop.setPickupDelay(java.time.Duration.ofMillis(200));
    }
}
