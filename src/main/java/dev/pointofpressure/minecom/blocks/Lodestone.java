package dev.pointofpressure.minecom.blocks;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.LodestoneTracker;
import net.minestom.server.sound.SoundEvent;

/**
 * CompassItem.useOn: right-clicking a lodestone with a compass locks it onto that exact block
 * (DataComponents.LODESTONE_TRACKER, tracked=true) — a stack of 1 (or a creative player's
 * infinite stack) is bound in place; a larger stack instead splits off one newly-bound compass
 * so the rest of the stack stays unbound. Real vanilla resets the needle to point at real north
 * instead once its target's chunk is force-loaded and found to no longer be a lodestone, but
 * this project doesn't track lodestone removal, so a bound compass simply keeps pointing at
 * wherever the lodestone was (a narrow, rarely-hit simplification).
 */
public final class Lodestone {
    private Lodestone() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, Lodestone::useOnBlock);
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        if (e.getItemStack().material() != Material.COMPASS) return;
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        if (!instance.getBlock(pos).key().value().equals("lodestone")) return;

        Player player = e.getPlayer();
        instance.playSound(Sound.sound(SoundEvent.ITEM_LODESTONE_COMPASS_LOCK, Sound.Source.PLAYER, 1f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);

        LodestoneTracker target = new LodestoneTracker(instance.getDimensionName(), pos, true);
        ItemStack held = e.getItemStack();
        boolean replaceExistingStack = player.getGameMode() != GameMode.CREATIVE && held.amount() == 1;

        if (replaceExistingStack) {
            setHeld(player, e.getHand(), held.with(DataComponents.LODESTONE_TRACKER, target));
        } else {
            if (player.getGameMode() != GameMode.CREATIVE) {
                setHeld(player, e.getHand(), held.consume(1));
            }
            ItemStack lodestoneCompass = ItemStack.of(Material.COMPASS)
                    .with(DataComponents.LODESTONE_TRACKER, target);
            if (!player.getInventory().addItemStack(lodestoneCompass)) {
                player.getInventory().setCursorItem(lodestoneCompass);
            }
        }
    }

    private static void setHeld(Player player, PlayerHand hand, ItemStack stack) {
        if (hand == PlayerHand.OFF) {
            player.setItemInOffHand(stack);
        } else {
            player.setItemInMainHand(stack);
        }
    }
}
