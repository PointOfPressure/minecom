package dev.pointofpressure.minecom.blocks;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.sound.SoundEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EnderChestBlockEntity: a single real 27-slot inventory shared by every ender chest a given
 * player opens anywhere in the world (keyed by player UUID, not by block position, unlike
 * every other container in this project) — breaking the block never spills anything, since
 * the contents belong to the player, not the block instance. No comparator signal in real
 * vanilla (EnderChestBlock has no Comparator interface), so unlike chest/decorated_pot/etc
 * there is intentionally no Redstone.java wiring for this block.
 */
public final class EnderChest {
    private EnderChest() {}

    /** Ender chest inventories keyed by player UUID string; exposed for persistence. */
    public static final Map<String, Inventory> INVENTORIES = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(InventoryCloseEvent.class, e -> {
            if (e.getInventory() == INVENTORIES.get(e.getPlayer().getUuid().toString())) {
                playAt(e.getPlayer().getInstance(), e.getPlayer().getPosition(), SoundEvent.BLOCK_ENDER_CHEST_CLOSE);
            }
        });
    }

    static void open(Player player, Instance instance, Point pos) {
        Inventory inv = INVENTORIES.computeIfAbsent(player.getUuid().toString(),
                k -> new Inventory(InventoryType.CHEST_3_ROW, Component.text("Ender Chest")));
        player.openInventory(inv);
        playAt(instance, pos, SoundEvent.BLOCK_ENDER_CHEST_OPEN);
    }

    private static void playAt(Instance instance, Point pos, SoundEvent sound) {
        instance.playSound(Sound.sound(sound, Sound.Source.BLOCK, 1f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }
}
