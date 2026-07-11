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
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.potion.PotionType;
import net.minestom.server.sound.SoundEvent;

/**
 * CauldronInteractions (decompile-verified): four block states — empty "cauldron", and
 * water/lava/powder_snow variants — each with their own bucket/bottle interaction table. Water
 * and powder_snow track a 1-3 "level"; lava is single-state (always full). A water/powder_snow
 * bucket fills an empty cauldron to level 3; an empty bucket only picks up a FULL (level 3)
 * water/powder_snow cauldron, but always picks up a lava cauldron regardless of "level" (it has
 * none). A water bottle empties into an empty cauldron at level 1, or bumps a partial water
 * cauldron up one level; a glass bottle does the reverse, filling from a water cauldron and
 * dropping its level by 1 (reverting to a plain empty cauldron at 0). Pouring lava or powder
 * snow fizzles if there's water directly above the cauldron (AbstractCauldronBlock.isUnderWater).
 * Not modelled: banner washing, dyed-armor/shulker-box cleaning (this project has none of those
 * items yet — an upstream gap, not a cauldron one), natural rain/snow filling and dripstone
 * dripping (both need the random-tick engine AUDIT.md already documents as missing).
 */
public final class Cauldrons {
    private Cauldrons() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, Cauldrons::useOnBlock);
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        Block block = instance.getBlock(pos);
        String key = block.key().value();
        if (!key.equals("cauldron") && !key.equals("water_cauldron")
                && !key.equals("lava_cauldron") && !key.equals("powder_snow_cauldron")) {
            return;
        }

        Player player = e.getPlayer();
        PlayerHand hand = e.getHand();
        ItemStack held = e.getItemStack();
        Material mat = held.material();

        if (mat == Material.WATER_BUCKET && key.equals("cauldron")) {
            fill(player, hand, held, instance, pos, Block.WATER_CAULDRON.withProperty("level", "3"),
                    Material.BUCKET, SoundEvent.ITEM_BUCKET_EMPTY);
        } else if (mat == Material.LAVA_BUCKET && key.equals("cauldron") && !waterAbove(instance, pos)) {
            fill(player, hand, held, instance, pos, Block.LAVA_CAULDRON,
                    Material.BUCKET, SoundEvent.ITEM_BUCKET_EMPTY_LAVA);
        } else if (mat == Material.POWDER_SNOW_BUCKET && key.equals("cauldron") && !waterAbove(instance, pos)) {
            fill(player, hand, held, instance, pos, Block.POWDER_SNOW_CAULDRON.withProperty("level", "3"),
                    Material.BUCKET, SoundEvent.ITEM_BUCKET_EMPTY_POWDER_SNOW);
        } else if (mat == Material.BUCKET) {
            if (key.equals("water_cauldron") && "3".equals(block.getProperty("level"))) {
                fill(player, hand, held, instance, pos, Block.CAULDRON, Material.WATER_BUCKET, SoundEvent.ITEM_BUCKET_FILL);
            } else if (key.equals("lava_cauldron")) {
                fill(player, hand, held, instance, pos, Block.CAULDRON, Material.LAVA_BUCKET, SoundEvent.ITEM_BUCKET_FILL_LAVA);
            } else if (key.equals("powder_snow_cauldron") && "3".equals(block.getProperty("level"))) {
                fill(player, hand, held, instance, pos, Block.CAULDRON, Material.POWDER_SNOW_BUCKET, SoundEvent.ITEM_BUCKET_FILL_POWDER_SNOW);
            }
        } else if (mat == Material.GLASS_BOTTLE && key.equals("water_cauldron")) {
            ItemStack bottle = ItemStack.of(Material.POTION).with(DataComponents.POTION_CONTENTS, new PotionContents(PotionType.WATER));
            giveOrReplace(player, hand, held, bottle);
            lowerLevel(instance, pos, block);
            instance.playSound(soundAt(SoundEvent.ITEM_BOTTLE_FILL), pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        } else if (mat == Material.POTION && isWater(held)) {
            if (key.equals("cauldron")) {
                giveOrReplace(player, hand, held, ItemStack.of(Material.GLASS_BOTTLE));
                instance.setBlock(pos, Block.WATER_CAULDRON.withProperty("level", "1"));
                instance.playSound(soundAt(SoundEvent.ITEM_BOTTLE_EMPTY), pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
            } else if (key.equals("water_cauldron") && !"3".equals(block.getProperty("level"))) {
                giveOrReplace(player, hand, held, ItemStack.of(Material.GLASS_BOTTLE));
                int level = Integer.parseInt(block.getProperty("level"));
                instance.setBlock(pos, block.withProperty("level", String.valueOf(level + 1)));
                instance.playSound(soundAt(SoundEvent.ITEM_BOTTLE_EMPTY), pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
            }
        }
    }

    private static boolean isWater(ItemStack potion) {
        PotionContents contents = potion.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.potion() == PotionType.WATER;
    }

    private static boolean waterAbove(Instance instance, Point pos) {
        return instance.getBlock(pos.add(0, 1, 0)).compare(Block.WATER);
    }

    private static void lowerLevel(Instance instance, Point pos, Block block) {
        int level = Integer.parseInt(block.getProperty("level")) - 1;
        instance.setBlock(pos, level == 0 ? Block.CAULDRON : block.withProperty("level", String.valueOf(level)));
    }

    /** Bucket/bottle-emptying interactions: replace the cauldron block and hand back the emptied item. */
    private static void fill(Player player, PlayerHand hand, ItemStack held, Instance instance, Point pos,
                              Block newBlock, Material returned, SoundEvent sound) {
        instance.setBlock(pos, newBlock);
        giveOrReplace(player, hand, held, ItemStack.of(returned));
        instance.playSound(soundAt(sound), pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }

    private static Sound soundAt(SoundEvent event) {
        return Sound.sound(event, Sound.Source.BLOCK, 1f, 1f);
    }

    /**
     * Replace the held stack in place if it's the only one held, otherwise consume 1 and hand a
     * new item. A creative player's hotbar is infinite, so real vanilla leaves it untouched
     * entirely — no consuming, and no bonus item granted either.
     */
    private static void giveOrReplace(Player player, PlayerHand hand, ItemStack held, ItemStack newItem) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (held.amount() == 1) {
            setHeld(player, hand, newItem);
            return;
        }
        setHeld(player, hand, held.consume(1));
        if (!player.getInventory().addItemStack(newItem)) player.getInventory().setCursorItem(newItem);
    }

    private static void setHeld(Player player, PlayerHand hand, ItemStack stack) {
        if (hand == PlayerHand.OFF) {
            player.setItemInOffHand(stack);
        } else {
            player.setItemInMainHand(stack);
        }
    }
}
