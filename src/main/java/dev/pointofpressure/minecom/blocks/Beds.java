package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.Bootstrap;
import dev.pointofpressure.minecom.survival.Lightning;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;

/**
 * Beds: set the respawn point; sleeping skips to morning at night OR during a real
 * thunderstorm (BedBlock.useWithoutItem's real gate is night-time OR thundering — NOT
 * plain rain, which this project previously (incorrectly) treated as sleep-eligible).
 * Sleeping in the Nether/End explodes the bed (real vanilla's EnvironmentAttributes.
 * BED_RULE is data-driven per dimension; approximated here as "explodes outside the
 * overworld", matching this project's existing RespawnAnchor precedent for the same
 * attribute) with real vanilla's exact power (5.0F), reusing the shared Explosions
 * helper so the explosion both destroys blocks and damages the sleeping player.
 * Not modeled: the "monsters nearby" sleep denial (needs a decompile of the exact
 * search radius/line-of-sight rule, not yet investigated).
 */
public final class Beds {
    private Beds() {}

    private static InstanceContainer overworld;

    public static void register(GlobalEventHandler events, InstanceContainer overworldInstance) {
        overworld = overworldInstance;
        events.addListener(PlayerBlockInteractEvent.class, Beds::interact);
    }

    private static void interact(PlayerBlockInteractEvent e) {
        if (!e.getBlock().key().value().endsWith("_bed")) return;
        e.setBlockingItemUse(true);
        Player player = e.getPlayer();
        Instance instance = e.getInstance();

        if (instance == Bootstrap.netherOf(overworld) || instance == Bootstrap.endOf(overworld)) {
            Point clickedPos = e.getBlockPosition();
            Block clicked = e.getBlock();
            Pos center = new Pos(clickedPos.blockX() + 0.5, clickedPos.blockY() + 0.5, clickedPos.blockZ() + 0.5);
            instance.setBlock(clickedPos, Block.AIR);
            // BedBlock.useWithoutItem removes BOTH halves, not just the one clicked
            String part = clicked.getProperty("part");
            String facing = clicked.getProperty("facing");
            if (part != null && facing != null) {
                Point otherPos = clickedPos.add("foot".equals(part) ? facingVec(facing) : facingVec(facing).mul(-1));
                if (instance.getBlock(otherPos).key().value().endsWith("_bed")) {
                    instance.setBlock(otherPos, Block.AIR);
                }
            }
            Explosions.explode(instance, center, 5f, 1.0, null);
            return;
        }

        player.setRespawnPoint(new Pos(e.getBlockPosition().blockX() + 0.5,
                e.getBlockPosition().blockY() + 0.6,
                e.getBlockPosition().blockZ() + 0.5));

        long time = instance.getTime() % 24000;
        if (time >= 12542 || Lightning.isThundering(instance)) {
            instance.setTime(instance.getTime() - time + 24000); // next morning
            instance.setWeather(Weather.CLEAR);
            instance.getPlayers().forEach(p ->
                    p.sendMessage(Component.text("You slept through the night.", NamedTextColor.GRAY)));
        } else {
            player.sendMessage(Component.text("Respawn point set. You can only sleep at night.",
                    NamedTextColor.GRAY));
        }
    }

    private static Vec facingVec(String facing) {
        return switch (facing) {
            case "north" -> new Vec(0, 0, -1);
            case "south" -> new Vec(0, 0, 1);
            case "west" -> new Vec(-1, 0, 0);
            case "east" -> new Vec(1, 0, 0);
            default -> Vec.ZERO;
        };
    }
}
