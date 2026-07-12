package dev.pointofpressure.minecom;

import dev.pointofpressure.minecom.data.SelfTest;
import dev.pointofpressure.minecom.playtest.PlayTest;
import dev.pointofpressure.minecom.survival.Survival;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.server.ServerListPingEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.ping.Status;
import net.minestom.server.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--selftest")) {
            MinecraftServer.init(); // binds registries so component reads work
            System.out.println(SelfTest.run());
            System.exit(0);
        }
        if (args.length > 0 && args[0].equals("--playtest")) {
            System.exit(PlayTest.run(args.length > 1 ? args[1] : null));
        }

        MinecraftServer server = MinecraftServer.init();
        MinecraftServer.setBrandName("Minecom");

        LOGGER.info("Loading vanilla 26.1.2 data (recipes, loot tables, tags)...");
        InstanceContainer overworld = Bootstrap.boot(Bootstrap.Config.production());
        Pos spawn = Bootstrap.spawnOf(overworld);

        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();
        registerConnectionFlow(events, overworld, spawn);

        // day/night: advance time manually unless the instance already does
        long before = overworld.getTime();
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            if (overworld.getTime() == before) {
                LOGGER.info("Instance clock is static; driving day/night cycle manually");
                MinecraftServer.getSchedulerManager().buildTask(() ->
                        overworld.setTime(overworld.getTime() + 1))
                        .repeat(TaskSchedule.tick(1)).schedule();
            } else {
                LOGGER.info("Instance clock advances on its own");
            }
        }).delay(TaskSchedule.tick(40)).schedule();

        MinecraftServer.getSchedulerManager().buildShutdownTask(() -> {
            LOGGER.info("Saving world...");
            overworld.saveChunksToStorage().join();
        });

        server.start("0.0.0.0", 25565);
        LOGGER.info("Minecom started on :25565 (spawn at {}, biome-correct, survival)", spawn);
    }

    /** Join/spawn/pickup/drop/ping handlers — shared shape with the playtest harness. */
    public static void registerConnectionFlow(GlobalEventHandler events, InstanceContainer overworld, Pos spawn) {
        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(overworld);
            Player player = event.getPlayer();
            Pos saved = Persist.savedPosition(player.getUuid());
            player.setRespawnPoint(saved != null ? saved : spawn);
            player.setGameMode(GameMode.SURVIVAL);
        });

        events.addListener(PlayerSpawnEvent.class, event -> {
            if (!event.isFirstSpawn()) return;
            Player player = event.getPlayer();
            player.sendMessage(Component.text("Welcome to Minecom — vanilla, rebuilt on Minestom.",
                    NamedTextColor.GREEN));
            MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(p ->
                    p.sendMessage(Component.text(player.getUsername() + " joined the game",
                            NamedTextColor.YELLOW)));
        });

        events.addListener(PickupItemEvent.class, event -> {
            if (event.getEntity() instanceof Player player) {
                if (!player.getInventory().addItemStack(event.getItemStack())) event.setCancelled(true);
            } else {
                event.setCancelled(true);
            }
        });

        events.addListener(ItemDropEvent.class, event ->
                Survival.dropItem(event.getPlayer(), event.getItemStack(), false));

        events.addListener(ServerListPingEvent.class, event -> event.setStatus(Status.builder()
                .description(Component.text("Minecom", NamedTextColor.GREEN)
                        .append(Component.text(" — vanilla, rebuilt on Minestom", NamedTextColor.GRAY)))
                .playerInfo(MinecraftServer.getConnectionManager().getOnlinePlayers().size(), 20)
                .build()));
    }
}
