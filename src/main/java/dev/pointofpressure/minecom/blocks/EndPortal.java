package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.mobs.EnderDragonFight;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * End-portal travel. Standing in an {@code end_portal} block in the overworld drops
 * you onto the End arrival platform (and kicks off the dragon fight); standing in
 * one in the End — the exit portal that forms when the dragon dies — returns you to
 * the overworld spawn. Mirrors {@link Portals} for the Nether.
 */
public final class EndPortal {

    private static Instance overworld;
    private static Instance end;
    private static Pos overworldSpawn;
    private static final Map<UUID, Integer> STANDING = new ConcurrentHashMap<>();

    private EndPortal() {}

    public static void register(Instance overworldInstance, Instance endInstance, Pos spawn) {
        overworld = overworldInstance;
        end = endInstance;
        overworldSpawn = spawn;
        MinecraftServer.getSchedulerManager().buildTask(EndPortal::tick)
                .repeat(TaskSchedule.tick(5)).schedule();
    }

    private static void tick() {
        if (overworld == null || end == null) return;
        for (Instance instance : new Instance[]{overworld, end}) {
            for (Player player : instance.getPlayers()) {
                boolean inPortal = instance.getBlock(player.getPosition()).key().value().equals("end_portal");
                if (!inPortal) {
                    STANDING.remove(player.getUuid());
                    continue;
                }
                int ticks = STANDING.merge(player.getUuid(), 5, Integer::sum);
                if (ticks >= 15) {
                    STANDING.remove(player.getUuid());
                    travel(player, instance == overworld);
                }
            }
        }
    }

    /** Force an immediate portal evaluation for a player (test hook / shared entry). */
    public static void travel(Player player, boolean toEnd) {
        if (toEnd) {
            end.loadChunk(6, 0).thenRun(() -> {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        end.setBlock(100 + dx, 48, dz, Block.OBSIDIAN);
                        for (int dy = 1; dy <= 3; dy++) end.setBlock(100 + dx, 48 + dy, dz, Block.AIR);
                    }
                }
                player.setInstance(end, new Pos(100.5, 49, 0.5)).thenRun(() ->
                        player.sendMessage(Component.text("You are pulled into The End...", NamedTextColor.LIGHT_PURPLE)));
                EnderDragonFight.startIfNeeded(end);
            });
        } else {
            player.setInstance(overworld, overworldSpawn).thenRun(() ->
                    player.sendMessage(Component.text("You return home from The End.", NamedTextColor.GREEN)));
        }
    }
}
