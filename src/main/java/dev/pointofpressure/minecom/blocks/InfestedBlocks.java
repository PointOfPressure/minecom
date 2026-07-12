package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import dev.pointofpressure.minecom.data.Enchants;
import dev.pointofpressure.minecom.mobs.Mobs;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Infested blocks + silverfish ambush spawns, verified against decompiled
 * InfestedBlock / InfestedRotatedPillarBlock / Silverfish (26.1.2). Seven
 * host&lt;-&gt;infested pairs (exhaustive for 26.1.2); breaking one without silk
 * touch spawns one silverfish at the block center (silk touch takes the host
 * item through the normal loot path — the bundled infested_* tables carry the
 * match_tool gate — and suppresses the spawn); a hurt silverfish's wake-up
 * scan (X/Z ±10, Y ±5, per-axis outward order) destroys infested blocks to
 * release fresh silverfish, stopping with 50% chance per block found.
 * Simplifications (AUDIT.md): no mobGriefing gamerule exists in this project
 * (griefing behavior is unconditional — enderman precedent), so vanilla's
 * griefing-off "revert to host, no spawn" branch isn't modeled; explosions
 * don't release silverfish (Explosions.java has no per-block spawnAfterBreak
 * hook); no spawn-poof particles (client visual).
 */
public final class InfestedBlocks {
    private InfestedBlocks() {}

    // Blocks.java: every InfestedBlock registration, keyed by host block
    private static final Map<String, Block> INFESTED_BY_HOST = Map.of(
            "stone", Block.INFESTED_STONE,
            "cobblestone", Block.INFESTED_COBBLESTONE,
            "stone_bricks", Block.INFESTED_STONE_BRICKS,
            "mossy_stone_bricks", Block.INFESTED_MOSSY_STONE_BRICKS,
            "cracked_stone_bricks", Block.INFESTED_CRACKED_STONE_BRICKS,
            "chiseled_stone_bricks", Block.INFESTED_CHISELED_STONE_BRICKS,
            "deepslate", Block.INFESTED_DEEPSLATE);
    private static final Map<String, Block> HOST_BY_INFESTED = Map.of(
            "infested_stone", Block.STONE,
            "infested_cobblestone", Block.COBBLESTONE,
            "infested_stone_bricks", Block.STONE_BRICKS,
            "infested_mossy_stone_bricks", Block.MOSSY_STONE_BRICKS,
            "infested_cracked_stone_bricks", Block.CRACKED_STONE_BRICKS,
            "infested_chiseled_stone_bricks", Block.CHISELED_STONE_BRICKS,
            "infested_deepslate", Block.DEEPSLATE);

    // SilverfishWakeUpFriendsGoal.tick: y outermost ±5, then x, then z ±10,
    // each axis independently oscillating outward from 0 (0, 1, -1, 2, -2, ...)
    private static final int[] ORDER_5 = expandingOrder(5);
    private static final int[] ORDER_10 = expandingOrder(10);

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockBreakEvent.class, e -> {
            if (e.isCancelled()) return;
            if (hostOf(e.getBlock()) == null) return;
            // InfestedBlock.spawnAfterBreak: creative breaks skip dropResources
            // entirely; a prevents_infested_spawns tool (silk touch) suppresses it
            if (e.getPlayer().getGameMode() == GameMode.CREATIVE) return;
            if (Enchants.level(e.getPlayer().getItemInMainHand(), "silk_touch") > 0) return;
            spawnInfestation(e.getInstance(), e.getBlockPosition());
        });
    }

    /**
     * InfestedBlock.isCompatibleHostBlock: the infested variant for a host
     * block, with the deepslate pillar's axis carried over
     * (InfestedBlock.infestedStateByHost / copyProperty). Null if the block
     * has no infested form.
     */
    public static Block infestedOf(Block host) {
        Block infested = INFESTED_BY_HOST.get(host.key().value());
        if (infested == Block.INFESTED_DEEPSLATE && host.getProperty("axis") != null) {
            infested = infested.withProperty("axis", host.getProperty("axis"));
        }
        return infested;
    }

    /** The inverse mapping (InfestedBlock.hostStateByInfested). Null if not infested. */
    public static Block hostOf(Block infested) {
        Block host = HOST_BY_INFESTED.get(infested.key().value());
        if (host == Block.DEEPSLATE && infested.getProperty("axis") != null) {
            host = host.withProperty("axis", infested.getProperty("axis"));
        }
        return host;
    }

    /**
     * InfestedBlock.spawnInfestation: exactly one silverfish at the block
     * center, feet on the block's own Y (used by both mining and the
     * wake-up-friends destroy path).
     */
    public static void spawnInfestation(Instance instance, Point pos) {
        Mobs.spawn("silverfish", instance,
                new Pos(pos.blockX() + 0.5, pos.blockY(), pos.blockZ() + 0.5));
    }

    /**
     * SilverfishWakeUpFriendsGoal.tick's scan, fired ~20gt after a silverfish
     * takes entity damage: destroy infested blocks around it (vanilla
     * destroyBlock with an empty tool — the silk-gated loot table drops
     * nothing) and release a fresh silverfish from each, stopping the scan
     * with 50% probability per block found.
     */
    public static void wakeFriends(Instance instance, Point origin) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int bx = origin.blockX(), by = origin.blockY(), bz = origin.blockZ();
        for (int y : ORDER_5) {
            for (int x : ORDER_10) {
                for (int z : ORDER_10) {
                    if (!instance.isChunkLoaded((bx + x) >> 4, (bz + z) >> 4)) continue;
                    Block block = instance.getBlock(bx + x, by + y, bz + z);
                    if (hostOf(block) == null) continue;
                    instance.setBlock(bx + x, by + y, bz + z, Block.AIR);
                    spawnInfestation(instance, new Pos(bx + x, by + y, bz + z));
                    if (rng.nextBoolean()) return;
                }
            }
        }
    }

    /** 0, 1, -1, 2, -2, ..., radius, -radius — vanilla's per-axis scan order. Public for SelfTest. */
    public static int[] expandingOrder(int radius) {
        int[] order = new int[radius * 2 + 1];
        int i = 0;
        order[i++] = 0;
        for (int step = 1; step <= radius; step++) {
            order[i++] = step;
            order[i++] = -step;
        }
        return order;
    }
}
