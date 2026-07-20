package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.TickPipeline;
import dev.pointofpressure.minecom.StateAdapter;
import dev.pointofpressure.minecom.data.Items;
import dev.pointofpressure.minecom.data.LootTables;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;
import net.minestom.server.utils.Direction;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Archaeology: brushing suspicious_sand/suspicious_gravel, decompile-verified against
 * {@code BrushItem}/{@code BrushableBlock}/{@code BrushableBlockEntity} (26.2, freshly
 * decompiled — no cached copy existed before this pass).
 *
 * <p><b>The engine gap this works around</b>: real vanilla's {@code onUseTick} is a per-tick
 * callback the server calls on the held item every tick while a use-animation is active.
 * Minestom exposes no such hook (only begin/finish/cancel, see {@code Crossbow.java}'s own
 * class doc for the same gap), so brush progress is driven by a global per-tick poll
 * ({@link #tickBrushingPlayers}) over {@code isUsingItem()}/{@code getCurrentItemUseTime()} —
 * the same tick-driven-not-wall-clock idiom {@code WeatherCycle}/{@code Campfires} already use
 * for their own periodic state, just reading the player's OWN engine-tracked use-timer instead
 * of a subsystem timer. The 10-tick brush cadence gate ({@code timeElapsed % 10 == 5} in the
 * decompile, where {@code timeElapsed = getUseDuration - ticksRemaining + 1}) becomes
 * {@code getCurrentItemUseTime() % 10 == 4} once re-derived against Minestom's up-counting
 * timer instead of vanilla's down-counting one — same tick, different arithmetic direction.
 *
 * <p>Unlike a block-targeted right-click on an air-facing item (which Minestom's own
 * {@code UseItemListener} already recognizes for BRUSH/GOAT_HORN/SPYGLASS/bundles and starts
 * automatically — see {@code GoatHorns.java}'s class doc), a BLOCK-targeted click routes
 * through {@code BlockPlacementListener} instead, which never starts the engine's use-timer on
 * its own. {@link #useOnBlock} starts it manually ({@code Player#refreshItemUse}/
 * {@code refreshActiveHand}, the exact same two calls {@code UseItemListener} makes
 * internally), mirroring vanilla's own {@code BrushItem.useOn} calling
 * {@code player.startUsingItem(hand)} explicitly.
 *
 * <p>Simplifications (AUDIT): the continuous look-direction raycast real vanilla re-evaluates
 * every tick ({@code calculateHitResult}, which cancels brushing the instant the player looks
 * away) is replaced by a single position+face capture at click time, re-validated only against
 * "is this still a suspicious block" each poll — a player can look elsewhere mid-brush without
 * interrupting it, a real behavioral difference from vanilla, not a hidden one. Falling
 * (BrushableBlock implements Fallable) rides the existing gravity-block system
 * (blocks/BlockRules.java — suspicious_sand/suspicious_gravel added to its whitelist) rather
 * than vanilla's own unconditional per-2-tick check; a block that starts falling while
 * mid-brushed orphans its tracked progress (a rare edge case: structure-placed suspicious
 * blocks normally rest on solid ground), same "in-flight state, acceptable loss" precedent
 * TrialChambers' currentMobs uses. No dust/falling-block particles (client-visual, same
 * established note as every other particle-only gap in this file). Worldgen does NOT place
 * these blocks yet (a separate, deliberately out-of-scope follow-up — see AUDIT): this file is
 * the reusable subsystem, exercised here via {@link #registerLoot} the same way
 * {@code Containers.registerLoot} lets a structure register pending chest loot without
 * generation-time resolution.
 */
public final class Archaeology {
    private Archaeology() {}

    private static final int USE_DURATION = 200; // BrushItem.USE_DURATION
    private static final int BRUSH_COOLDOWN_TICKS = 10;
    private static final int REQUIRED_BRUSHES_TO_BREAK = 10;

    private static final class State {
        Instance instance;
        Point pos;
        int brushCount;
        long brushCountResetsAtTick;
        long coolDownEndsAtTick;
        Direction hitDirection;
        String pendingLootTable; // set by registerLoot, cleared once rolled
        ItemStack revealedItem; // null once dropped or never resolved
    }

    private static final class BrushSession {
        final Player player;
        final Instance instance;
        final Point pos;
        final PlayerHand hand;
        final Direction face;
        BrushSession(Player player, Instance instance, Point pos, PlayerHand hand, Direction face) {
            this.player = player; this.instance = instance; this.pos = pos; this.hand = hand; this.face = face;
        }
    }

    private static final Map<String, State> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<UUID, BrushSession> ACTIVE_BRUSH = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        Persist.register(persistence());
        events.addListener(PlayerUseItemOnBlockEvent.class, Archaeology::useOnBlock);
        TickPipeline.register(TickPipeline.ENTITIES, "archaeologyBrush", Archaeology::tickBrushingPlayers);
        TickPipeline.register(TickPipeline.POST, "archaeologyResets", Archaeology::checkResets);
    }

    /** A structure (or a test) marks a suspicious block's eventual loot, rolled on the brush
     * that completes it — not before, matching real vanilla's LootTable NBT resolution timing
     * (same design as {@code Containers.registerLoot}). */
    public static void registerLoot(Point pos, String archaeologyTableId) {
        State state = BLOCKS.computeIfAbsent(Persist.posKey(pos), k -> new State());
        state.pendingLootTable = archaeologyTableId;
    }

    private static boolean isSuspicious(Block block) {
        String key = block.key().value();
        return key.equals("suspicious_sand") || key.equals("suspicious_gravel");
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        if (e.getItemStack().material() != Material.BRUSH) return;
        Player player = e.getPlayer();
        Instance instance = player.getInstance();
        if (instance == null) return;
        Block block = instance.getBlock(e.getPosition());
        if (!isSuspicious(block)) return;

        player.refreshItemUse(e.getHand(), USE_DURATION);
        player.refreshActiveHand(true, e.getHand() == PlayerHand.OFF, false);
        ACTIVE_BRUSH.put(player.getUuid(),
                new BrushSession(player, instance, e.getPosition(), e.getHand(), e.getBlockFace().toDirection()));
    }

    /**
     * Walks the tracked sessions directly rather than
     * {@code MinecraftServer.getConnectionManager().getOnlinePlayers()} (the more obvious
     * choice — deliberately avoided: PlayTest's fake player never performs a real login/
     * config/play handshake, so it's never in that registry, same documented gap
     * scenarioAdminCommands' class doc already calls out for command selectors; this shape
     * sidesteps it instead of being untestable).
     */
    private static void tickBrushingPlayers() {
        for (Map.Entry<UUID, BrushSession> entry : ACTIVE_BRUSH.entrySet()) {
            BrushSession session = entry.getValue();
            Player player = session.player;
            if (!player.isUsingItem() || player.getItemUseHand() != session.hand
                    || player.getItemInHand(session.hand).material() != Material.BRUSH) {
                ACTIVE_BRUSH.remove(entry.getKey());
                continue;
            }
            // timeElapsed%10==5 in the decompile, timeElapsed = currentItemUseTime+1 (see class doc)
            if (player.getCurrentItemUseTime() % 10 != 4) continue;
            progressBrush(player, session);
        }
    }

    private static int completionState(int brushCount) {
        if (brushCount == 0) return 0;
        if (brushCount < 3) return 1;
        return brushCount < 6 ? 2 : 3;
    }

    private static void progressBrush(Player player, BrushSession session) {
        Block block = session.instance.getBlock(session.pos);
        if (!isSuspicious(block)) {
            ACTIVE_BRUSH.remove(player.getUuid());
            return;
        }
        State state = BLOCKS.computeIfAbsent(Persist.posKey(session.pos), k -> new State());
        state.instance = session.instance;
        state.pos = session.pos;
        if (state.hitDirection == null) state.hitDirection = session.face; // BrushableBlockEntity.brush: set once, first stroke wins
        long gameTime = session.instance.getWorldAge();
        state.brushCountResetsAtTick = gameTime + 40;
        if (gameTime < state.coolDownEndsAtTick) return; // BRUSH_COOLDOWN_TICKS gate
        state.coolDownEndsAtTick = gameTime + BRUSH_COOLDOWN_TICKS;

        resolvePendingLoot(state);
        int previousCompletion = completionState(state.brushCount);
        state.brushCount++;
        playBrushSound(session.instance, session.pos, block, false);

        if (state.brushCount >= REQUIRED_BRUSHES_TO_BREAK) {
            complete(state, player, block);
            ACTIVE_BRUSH.remove(player.getUuid());
        } else {
            int completion = completionState(state.brushCount);
            if (completion != previousCompletion) {
                session.instance.setBlock(session.pos, block.withProperty("dusted", String.valueOf(completion)));
            }
        }
    }

    private static void resolvePendingLoot(State state) {
        if (state.pendingLootTable == null) return;
        List<ItemStack> loot = LootTables.archaeology(state.pendingLootTable);
        state.revealedItem = loot.isEmpty() ? null : loot.get(0);
        state.pendingLootTable = null;
    }

    private static void complete(State state, Player user, Block block) {
        resolvePendingLoot(state); // idempotent; mirrors brush()+brushingCompleted's double call
        dropContent(state);
        Block turnsInto = block.key().value().equals("suspicious_sand") ? Block.SAND : Block.GRAVEL;
        state.instance.setBlock(state.pos, turnsInto);
        playBrushSound(state.instance, state.pos, block, true);

        ItemStack held = user.getItemInHand(user.getItemUseHand() != null ? user.getItemUseHand() : PlayerHand.MAIN);
        // hurtAndBreak(1) only fires on the completing brush (return true), not every stroke —
        // decompile-verified: BrushItem.onUseTick guards it behind brush()'s own return value.
        PlayerHand hand = user.getItemUseHand() != null ? user.getItemUseHand() : PlayerHand.MAIN;
        user.setItemInHand(hand, Items.damageItem(user, held, 1));

        BLOCKS.remove(Persist.posKey(state.pos));
    }

    private static void dropContent(State state) {
        if (state.revealedItem == null || state.revealedItem.isAir()) return;
        Direction dir = state.hitDirection != null ? state.hitDirection : Direction.UP;
        Point dropPos = state.pos.add(dir.vec());
        ItemEntity entity = new ItemEntity(state.revealedItem);
        entity.setInstance(state.instance, new Pos(dropPos.x() + 0.5, dropPos.y() + 0.5, dropPos.z() + 0.5));
        state.revealedItem = null;
    }

    private static void playBrushSound(Instance instance, Point pos, Block block, boolean completed) {
        boolean sand = block.key().value().equals("suspicious_sand");
        SoundEvent sound = completed
                ? (sand ? SoundEvent.ITEM_BRUSH_BRUSHING_SAND_COMPLETE : SoundEvent.ITEM_BRUSH_BRUSHING_GRAVEL_COMPLETE)
                : (sand ? SoundEvent.ITEM_BRUSH_BRUSHING_SAND : SoundEvent.ITEM_BRUSH_BRUSHING_GRAVEL);
        instance.playSound(Sound.sound(sound, Sound.Source.BLOCK, 1f, 1f),
                new Pos(pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5));
    }

    /** BrushableBlockEntity.checkReset: brushCount decays by 2 every 4gt once 40gt idle. */
    private static void checkResets() {
        for (var entry : BLOCKS.entrySet()) {
            State state = entry.getValue();
            if (state.instance == null) continue;
            long gameTime = state.instance.getWorldAge();
            if (state.brushCount != 0 && gameTime >= state.brushCountResetsAtTick) {
                int previous = completionState(state.brushCount);
                state.brushCount = Math.max(0, state.brushCount - 2);
                int completion = completionState(state.brushCount);
                if (completion != previous) {
                    Block current = state.instance.getBlock(state.pos);
                    if (isSuspicious(current)) {
                        state.instance.setBlock(state.pos, current.withProperty("dusted", String.valueOf(completion)));
                    }
                }
                state.brushCountResetsAtTick = gameTime + 4;
            }
            if (state.brushCount == 0) {
                state.hitDirection = null;
                state.brushCountResetsAtTick = 0;
                state.coolDownEndsAtTick = 0;
            }
        }
    }

    /** Archaeology persistence: pending/rolled loot + in-progress brush count survive a restart
     * (worldgen never re-derives these — nothing places suspicious blocks yet, see class doc —
     * but a manually {@link #registerLoot}-tagged block still needs to ride out a save/reload
     * the same way structure chest loot does). */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() { return "archaeology"; }

            @Override
            public void collect(Instance in, java.util.function.BiConsumer<Point, JsonObject> out) {
                BLOCKS.forEach((key, s) -> {
                    if (s.instance != in) return;
                    JsonObject o = new JsonObject();
                    o.addProperty("brushCount", s.brushCount);
                    o.addProperty("resetsAt", s.brushCountResetsAtTick - s.instance.getWorldAge());
                    o.addProperty("cooldownEnds", s.coolDownEndsAtTick - s.instance.getWorldAge());
                    if (s.hitDirection != null) o.addProperty("hitDirection", s.hitDirection.name());
                    if (s.pendingLootTable != null) o.addProperty("pendingLoot", s.pendingLootTable);
                    if (s.revealedItem != null) o.add("revealedItem", Persist.writeItem(s.revealedItem));
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                State state = new State();
                state.instance = in;
                state.pos = pos;
                state.brushCount = data.get("brushCount").getAsInt();
                long age = in.getWorldAge();
                state.brushCountResetsAtTick = age + data.get("resetsAt").getAsLong();
                state.coolDownEndsAtTick = age + data.get("cooldownEnds").getAsLong();
                if (data.has("hitDirection")) state.hitDirection = Direction.valueOf(data.get("hitDirection").getAsString());
                if (data.has("pendingLoot")) state.pendingLootTable = data.get("pendingLoot").getAsString();
                if (data.has("revealedItem")) state.revealedItem = Persist.readItem(data.getAsJsonObject("revealedItem"));
                BLOCKS.put(Persist.posKey(pos), state);
            }

            @Override
            public void wipe() {
                BLOCKS.clear();
                ACTIVE_BRUSH.clear();
            }
        };
    }

    /** Test-only: current brush progress (0 if untracked). */
    public static int brushCountAt(Point pos) {
        State s = BLOCKS.get(Persist.posKey(pos));
        return s == null ? 0 : s.brushCount;
    }
}
