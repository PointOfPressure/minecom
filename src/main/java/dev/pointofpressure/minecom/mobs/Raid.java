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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A bounded raid event: three escalating waves of pillagers/vindicators/evokers/ravagers
 * spawn around a village centre. Clearing all raiders in a wave brings on the next one after
 * a short pause; clearing the final wave wins. If no player stays near the site the
 * raid is abandoned. The full "Bad Omen" trigger via patrol captains is a refinement —
 * this is started directly (e.g. by command) at a village.
 */
public final class Raid {

    private record Wave(int pillagers, int vindicators, int evokers, int ravagers) {}

    private static final List<Wave> WAVES = List.of(
            new Wave(4, 2, 0, 0),
            new Wave(6, 3, 1, 1),
            new Wave(8, 4, 2, 2));

    private static final Set<Instance> ACTIVE = ConcurrentHashMap.newKeySet();

    private Raid() {}

    public static boolean isActive(Instance instance) {
        return ACTIVE.contains(instance);
    }

    /** Begin a raid centred on {@code center} if none is already running in this instance. */
    public static boolean start(Instance instance, Pos center) {
        if (dev.pointofpressure.minecom.Difficulty.isPeaceful()) return false; // no raids on Peaceful
        if (!ACTIVE.add(instance)) return false;

        BossBar bar = BossBar.bossBar(Component.text("Raid - Wave 1/" + WAVES.size()),
                1.0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        for (var p : instance.getPlayers()) p.showBossBar(bar);

        runWave(instance, center, 0, bar);
        return true;
    }

    private static void runWave(Instance instance, Pos center, int waveIndex, BossBar bar) {
        Wave wave = WAVES.get(waveIndex);
        List<Entity> raiders = new ArrayList<>();
        raiders.addAll(spawnRing(instance, center, "pillager", wave.pillagers()));
        raiders.addAll(spawnRing(instance, center, "vindicator", wave.vindicators()));
        raiders.addAll(spawnRing(instance, center, "evoker", wave.evokers()));
        raiders.addAll(spawnRing(instance, center, "ravager", wave.ravagers()));
        bar.name(Component.text("Raid - Wave " + (waveIndex + 1) + "/" + WAVES.size(), NamedTextColor.RED));
        for (var p : instance.getPlayers()) {
            p.sendMessage(Component.text("Wave " + (waveIndex + 1) + " has arrived!", NamedTextColor.RED));
        }

        int[] idleTicks = {0};
        instance.scheduler().buildTask(new Runnable() {
            @Override
            public void run() {
                if (!isActive(instance)) return;
                long alive = raiders.stream().filter(e -> !e.isRemoved()).count();
                bar.progress(alive == 0 ? 0f : Math.max(0.05f, (float) alive / raidersOf(wave)));

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
                if (waveIndex + 1 >= WAVES.size()) {
                    end(instance, bar, true);
                    return;
                }
                for (var p : instance.getPlayers()) {
                    p.sendMessage(Component.text("Wave cleared! Next wave incoming...", NamedTextColor.YELLOW));
                }
                instance.scheduler().buildTask(() -> runWave(instance, center, waveIndex + 1, bar))
                        .delay(TaskSchedule.tick(100)).schedule();
            }
        }).delay(TaskSchedule.tick(10)).schedule();
    }

    private static int raidersOf(Wave w) {
        return Math.max(1, w.pillagers() + w.vindicators() + w.evokers() + w.ravagers());
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
