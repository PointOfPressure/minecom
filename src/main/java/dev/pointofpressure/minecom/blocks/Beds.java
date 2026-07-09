package dev.pointofpressure.minecom.blocks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;

/** Beds: set the respawn point; sleeping at night skips to morning and clears rain. */
public final class Beds {
    private Beds() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, Beds::interact);
    }

    private static void interact(PlayerBlockInteractEvent e) {
        if (!e.getBlock().key().value().endsWith("_bed")) return;
        e.setBlockingItemUse(true);
        Player player = e.getPlayer();
        Instance instance = e.getInstance();

        player.setRespawnPoint(new Pos(e.getBlockPosition().blockX() + 0.5,
                e.getBlockPosition().blockY() + 0.6,
                e.getBlockPosition().blockZ() + 0.5));

        long time = instance.getTime() % 24000;
        if (time >= 12542 || instance.getWeather().isRaining()) {
            instance.setTime(instance.getTime() - time + 24000); // next morning
            instance.setWeather(Weather.CLEAR);
            instance.getPlayers().forEach(p ->
                    p.sendMessage(Component.text("You slept through the night.", NamedTextColor.GRAY)));
        } else {
            player.sendMessage(Component.text("Respawn point set. You can only sleep at night.",
                    NamedTextColor.GRAY));
        }
    }
}
