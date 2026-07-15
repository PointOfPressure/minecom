package dev.pointofpressure.minecom.mobs;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A raid event, decompile-verified against {@code net.minecraft.world.entity.raid.Raid}
 * (26.2): wave count scales with world difficulty ({@code getNumGroups}: Easy 3 / Normal 5 /
 * Hard 7 groups), and each wave's raider composition is read straight from the real per-type
 * {@code spawnsPerWaveBeforeBonus} tables (indexed by group number, capped at the difficulty's
 * own group count) plus the real per-wave random bonus-spawn rolls
 * ({@code getPotentialBonusSpawns}) for pillagers/vindicators/witches. {@link #start} also
 * takes a Bad Omen level: above 1 (real vanilla's {@code raidOmenLevel}, stacked by killing
 * multiple patrol captains while already under the effect) adds one real bonus wave once the
 * last normal wave clears ({@code shouldSpawnBonusGroup}/{@code hasBonusWave}). Nothing in
 * this codebase currently drives a level above 1 — there's no patrol/Bad Omen potion chain
 * yet (AUDIT.md) — so that path exists and is reachable through the API but is presently
 * always dormant; the two-argument overload is the only one wired to a caller today. Started
 * directly at a village (command/bell proximity) rather than via the real patrol-captain
 * trigger, which is still the case this project doesn't model. Also not modeled: the raid-bar
 * percentage from raider HP specifically (this project's bar already tracks a coarser
 * alive-count fraction — see the wave-loop below), hero of the village, ravager riders, and
 * bell-ring glowing (all AUDIT.md).
 */
public final class Raid {

    /** Raid.RaiderType (decompile-verified): each type's {@code spawnsPerWaveBeforeBonus},
     *  indexed by group/wave number 0-7 (index 0 unused — waves are 1-based). */
    private enum RaiderType {
        VINDICATOR("vindicator", new int[]{0, 0, 2, 0, 1, 4, 2, 5}),
        EVOKER("evoker", new int[]{0, 0, 0, 0, 0, 1, 1, 2}),
        PILLAGER("pillager", new int[]{0, 4, 3, 3, 4, 4, 4, 2}),
        WITCH("witch", new int[]{0, 0, 0, 0, 3, 0, 0, 1}),
        RAVAGER("ravager", new int[]{0, 0, 0, 1, 0, 1, 0, 2});

        final String kind;
        final int[] spawnsPerWaveBeforeBonus;

        RaiderType(String kind, int[] spawnsPerWaveBeforeBonus) {
            this.kind = kind;
            this.spawnsPerWaveBeforeBonus = spawnsPerWaveBeforeBonus;
        }
    }

    private static final Random RANDOM = new Random();
    private static final Set<Instance> ACTIVE = ConcurrentHashMap.newKeySet();

    private Raid() {}

    public static boolean isActive(Instance instance) {
        return ACTIVE.contains(instance);
    }

    /** Begin a raid centred on {@code center} if none is already running in this instance,
     *  at a plain (non-stacked) Bad Omen level — no bonus wave. */
    public static boolean start(Instance instance, Pos center) {
        return start(instance, center, 1);
    }

    /** @param omenLevel Raid.raidOmenLevel: 1 is a plain trigger (no bonus wave); above 1 adds
     *  one real bonus wave after the last normal one (see the class doc for why nothing in
     *  this codebase can currently pass anything above 1). Clamped to Raid's own 0-5 range. */
    public static boolean start(Instance instance, Pos center, int omenLevel) {
        if (dev.pointofpressure.minecom.Difficulty.isPeaceful()) return false; // no raids on Peaceful
        if (!ACTIVE.add(instance)) return false;

        int numGroups = numGroups(dev.pointofpressure.minecom.Difficulty.current());
        int clampedOmen = Math.max(0, Math.min(5, omenLevel));

        BossBar bar = BossBar.bossBar(Component.text("Raid - Wave 1/" + numGroups),
                1.0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        for (var p : instance.getPlayers()) p.showBossBar(bar);

        runWave(instance, center, 1, numGroups, clampedOmen, bar);
        return true;
    }

    /** Raid.getNumGroups (decompile-verified). */
    private static int numGroups(dev.pointofpressure.minecom.Difficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> 0;
            case EASY -> 3;
            case NORMAL -> 5;
            case HARD -> 7;
        };
    }

    /** Test-only: exposes {@link #numGroups} directly instead of needing a real, timed
     *  end-to-end raid run just to prove the wave count scales with difficulty. */
    public static int numGroupsForTest(dev.pointofpressure.minecom.Difficulty difficulty) {
        return numGroups(difficulty);
    }

    private static void runWave(Instance instance, Pos center, int wave, int numGroups, int omenLevel, BossBar bar) {
        // Raid.shouldSpawnBonusGroup: one extra group past the last real one, using the same
        // per-type table index as the final wave (getDefaultNumSpawns's isBonusWave branch).
        boolean bonusWave = wave > numGroups;
        int tableIndex = bonusWave ? numGroups : wave;

        List<Entity> raiders = new ArrayList<>();
        int plannedTotal = 0;
        for (RaiderType type : RaiderType.values()) {
            int count = type.spawnsPerWaveBeforeBonus[tableIndex] + bonusSpawns(type, wave, bonusWave);
            plannedTotal += count;
            raiders.addAll(spawnRing(instance, center, type.kind, count));
        }
        int plannedWaves = numGroups + (omenLevel > 1 ? 1 : 0);
        bar.name(Component.text("Raid - Wave " + wave + "/" + plannedWaves, NamedTextColor.RED));
        for (var p : instance.getPlayers()) {
            p.sendMessage(Component.text("Wave " + wave + " has arrived!", NamedTextColor.RED));
        }

        int progressDenominator = Math.max(1, plannedTotal);
        int[] idleTicks = {0};
        instance.scheduler().buildTask(new Runnable() {
            @Override
            public void run() {
                if (!isActive(instance)) return;
                long alive = raiders.stream().filter(e -> !e.isRemoved()).count();
                bar.progress(alive == 0 ? 0f : Math.max(0.05f, (float) alive / progressDenominator));

                boolean playerNear = instance.getPlayers().stream()
                        .anyMatch(p -> p.getPosition().distanceSquared(center) < 64 * 64);
                idleTicks[0] = playerNear ? 0 : idleTicks[0] + 10;
                if (idleTicks[0] > 1200) {
                    end(instance, bar, false);
                    for (Entity e : raiders) if (!e.isRemoved()) e.remove();
                    return;
                }

                if (alive > 0) {
                    instance.scheduler().buildTask(this).delay(TaskSchedule.tick(10)).schedule();
                    return;
                }
                boolean lastWave = wave >= numGroups && (bonusWave || omenLevel <= 1);
                if (lastWave) {
                    end(instance, bar, true);
                    return;
                }
                for (var p : instance.getPlayers()) {
                    p.sendMessage(Component.text("Wave cleared! Next wave incoming...", NamedTextColor.YELLOW));
                }
                instance.scheduler().buildTask(() -> runWave(instance, center, wave + 1, numGroups, omenLevel, bar))
                        .delay(TaskSchedule.tick(100)).schedule();
            }
        }).delay(TaskSchedule.tick(10)).schedule();
    }

    /** Raid.getPotentialBonusSpawns (decompile-verified): a real extra random roll layered on
     *  top of the base per-type table count, scaled by difficulty (Evoker never gets one;
     *  Ravager's only fires on the bonus wave itself; Witch only from wave 3 on, skipping
     *  wave 4 exactly as in the real switch). */
    private static int bonusSpawns(RaiderType type, int wave, boolean isBonusWave) {
        var difficulty = dev.pointofpressure.minecom.Difficulty.current();
        boolean easy = difficulty == dev.pointofpressure.minecom.Difficulty.EASY;
        boolean normal = difficulty == dev.pointofpressure.minecom.Difficulty.NORMAL;
        int bonusCap;
        switch (type) {
            case VINDICATOR, PILLAGER -> bonusCap = easy ? RANDOM.nextInt(2) : normal ? 1 : 2;
            case WITCH -> {
                if (easy || wave <= 2 || wave == 4) return 0;
                bonusCap = 1;
            }
            case RAVAGER -> bonusCap = !easy && isBonusWave ? 1 : 0;
            default -> {
                return 0; // Evoker: no bonus roll, ever
            }
        }
        return bonusCap > 0 ? RANDOM.nextInt(bonusCap + 1) : 0;
    }

    private static void end(Instance instance, BossBar bar, boolean victory) {
        ACTIVE.remove(instance);
        for (var p : instance.getPlayers()) {
            p.hideBossBar(bar);
            p.sendMessage(victory
                    ? Component.text("Raid won! The village is safe.", NamedTextColor.GREEN)
                    : Component.text("The raid has been abandoned.", NamedTextColor.GRAY));
        }
    }

    private static List<Entity> spawnRing(Instance instance, Pos center, String kind, int count) {
        List<Entity> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2 / Math.max(1, count)) * i;
            double dist = 16 + Math.random() * 8;
            int x = (int) Math.floor(center.x() + Math.cos(angle) * dist);
            int z = (int) Math.floor(center.z() + Math.sin(angle) * dist);
            Integer y = topSolid(instance, x, (int) center.y(), z);
            if (y == null) continue;
            Entity e = dev.pointofpressure.minecom.mobs.Mobs.spawn(kind, instance, new Pos(x + 0.5, y + 1, z + 0.5));
            if (e != null) out.add(e);
        }
        return out;
    }

    private static Integer topSolid(Instance instance, int x, int nearY, int z) {
        for (int y = Math.min(nearY + 20, 250); y > Math.max(nearY - 24, -60); y--) {
            var block = instance.getBlock(x, y, z);
            if (!block.isSolid() || block.isLiquid()) continue;
            if (instance.getBlock(x, y + 1, z).isAir() && instance.getBlock(x, y + 2, z).isAir()) return y;
        }
        return null;
    }
}
