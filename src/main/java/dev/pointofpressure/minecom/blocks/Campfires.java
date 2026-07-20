package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.TickPipeline;
import dev.pointofpressure.minecom.StateAdapter;
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
import java.util.function.BiConsumer;

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
        Persist.register(persistence());
        TickPipeline.register(TickPipeline.BLOCK_ENTITIES, "campfires", Campfires::tickAll);
        events.addListener(PlayerUseItemOnBlockEvent.class, Campfires::useOnBlock);
    }

    /** Campfire persistence (docs/PERSISTENCE.md): the 4 cooking slots + their progress/time. */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "campfire";
            }

            @Override
            public void collect(Instance in, BiConsumer<Point, JsonObject> out) {
                CAMPFIRES.forEach((key, s) -> {
                    JsonObject o = new JsonObject();
                    o.add("items", Persist.writeItems(s.items));
                    com.google.gson.JsonArray progress = new com.google.gson.JsonArray();
                    com.google.gson.JsonArray time = new com.google.gson.JsonArray();
                    for (int slot = 0; slot < 4; slot++) {
                        progress.add(s.cookingProgress[slot]);
                        time.add(s.cookingTime[slot]);
                    }
                    o.add("progress", progress);
                    o.add("time", time);
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                State state = new State();
                Persist.readItems(data.getAsJsonArray("items"), state.items);
                com.google.gson.JsonArray progress = data.getAsJsonArray("progress");
                com.google.gson.JsonArray time = data.getAsJsonArray("time");
                for (int slot = 0; slot < 4; slot++) {
                    state.cookingProgress[slot] = progress.get(slot).getAsInt();
                    state.cookingTime[slot] = time.get(slot).getAsInt();
                }
                state.instance = in;
                state.pos = pos;
                CAMPFIRES.put(Persist.posKey(pos), state);
            }

            @Override
            public void wipe() {
                CAMPFIRES.clear();
            }
        };
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

    /** The item cooking in a given slot (0-3), or air if empty/no campfire tracked here. Test-only. */
    public static ItemStack itemAt(Point pos, int slot) {
        State s = CAMPFIRES.get(Containers.posKey(pos));
        return s == null || s.items[slot] == null ? ItemStack.AIR : s.items[slot];
    }
}
