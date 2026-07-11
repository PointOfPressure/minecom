package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

/**
 * Candles: 1-4 stack per block (CandleBlock.getStateForPlacement's cycle-on-right-click, only
 * with a matching-color candle item — different colors are different block types in real
 * vanilla, not a shared color property, so stacking requires an exact block-key match), real
 * light emission of {@code 3 * candles} while lit (AbstractCandleBlock.LIGHT_EMISSION) needs
 * no extra code here since Minestom already bakes registered per-blockstate luminance into
 * its lighting engine — setting "lit"/"candles" is enough, the same way a redstone_lamp's
 * light just works from its own "lit" property. Lit via flint-and-steel (matching the
 * project's existing TNT/portal precedent), extinguished via an empty-hand right-click while
 * lit (AbstractCandleBlock.extinguish). NOT modeled: water auto-extinguishing a lit candle
 * (would need a Fluids.java hook this project doesn't have for arbitrary blocks yet) and
 * candle_cake (depends on cake's own bite-eating mechanic, which isn't implemented at all).
 */
public final class Candle {
    private Candle() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, Candle::useOnBlock);
        events.addListener(PlayerBlockInteractEvent.class, Candle::interact);
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        Block block = instance.getBlock(pos);
        if (!isCandle(block.key().value())) return;

        ItemStack held = e.getItemStack();
        if (held.isAir()) return;
        Player player = e.getPlayer();

        if (held.material() == Material.FLINT_AND_STEEL) {
            if ("true".equals(block.getProperty("lit")) || "true".equals(block.getProperty("waterlogged"))) return;
            instance.setBlock(pos, block.withProperty("lit", "true"));
            player.setItemInHand(e.getHand(),
                    dev.pointofpressure.minecom.data.Items.damageItem(player, held, 1));
            return;
        }

        // stacking: only the exact same candle color/type, up to 4
        if (!held.material().key().value().equals(block.key().value())) return;
        int candles = Integer.parseInt(block.getProperty("candles"));
        if (candles >= 4) return;
        instance.setBlock(pos, block.withProperty("candles", String.valueOf(candles + 1)));
        player.setItemInHand(e.getHand(), held.consume(1));
    }

    private static void interact(PlayerBlockInteractEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();
        Block block = instance.getBlock(pos);
        if (!isCandle(block.key().value())) return;
        if (!"true".equals(block.getProperty("lit"))) return;

        e.setBlockingItemUse(true);
        instance.setBlock(pos, block.withProperty("lit", "false"));
    }

    private static boolean isCandle(String key) {
        return key.equals("candle") || key.endsWith("_candle");
    }
}
