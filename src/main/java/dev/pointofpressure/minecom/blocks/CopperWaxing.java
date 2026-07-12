package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;

/**
 * HoneycombItem.useOn + the copper half of AxeItem.useOn (evaluateNewBlockState): honeycomb
 * applies a "waxed_" prefix to any unwaxed copper-family block (consuming 1 honeycomb outside
 * creative), blocking RandomTicks' oxidation handler from ever touching it again. An axe on a
 * waxed block strips the wax back off (1 durability, no oxidation change); an axe on an
 * unwaxed, already-weathered block scrapes it back one stage instead (RandomTicks.
 * previousOxidation — the WeatheringCopper.PREVIOUS_BY_BLOCK mirror of the oxidation handler's
 * own next-stage lookup, kept there so the two directions can't drift apart). Real vanilla's
 * AxeItem also strips wood logs first in this same priority chain (STRIPPABLES) — that's a
 * separate, still-missing gap (no stripped-log system at all in this codebase) noted in
 * AUDIT.md, not attempted here. Sign-waxing (HoneycombItem.tryApplyToSign) is skipped too:
 * there's no sign block-entity/text system in this codebase for it to apply to.
 */
public final class CopperWaxing {
    private CopperWaxing() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, CopperWaxing::useOnBlock);
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        Material item = e.getItemStack().material();
        if (item == Material.HONEYCOMB) {
            wax(e);
        } else if (item.key().value().endsWith("_axe")) {
            scrapeOrWaxOff(e);
        }
    }

    private static void wax(PlayerUseItemOnBlockEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        Block block = instance.getBlock(pos);
        String key = block.key().value();
        if (key.startsWith("waxed_") || !RandomTicks.isWeatheringCopper(key)) return;

        String waxedKey = "waxed_" + key;
        Block waxed = Block.fromKey(waxedKey);
        if (waxed == null) return;

        instance.setBlock(pos, waxed.withProperties(block.properties()));
        Player player = e.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setItemInHand(e.getHand(), e.getItemStack().consume(1));
        }
    }

    private static void scrapeOrWaxOff(PlayerUseItemOnBlockEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        Block block = instance.getBlock(pos);
        String key = block.key().value();

        String result;
        if (key.startsWith("waxed_")) {
            result = key.substring("waxed_".length());
            if (Block.fromKey(result) == null) return;
        } else {
            result = RandomTicks.previousOxidation(key);
            if (result == null) return;
        }

        Block newBlock = Block.fromKey(result);
        instance.setBlock(pos, newBlock.withProperties(block.properties()));
        Player player = e.getPlayer();
        player.setItemInHand(e.getHand(),
                dev.pointofpressure.minecom.data.Items.damageItem(player, e.getItemStack(), 1));
    }
}
