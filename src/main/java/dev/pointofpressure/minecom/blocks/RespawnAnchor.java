package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.Bootstrap;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerRespawnEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;

/**
 * RespawnAnchorBlock: charges 0-4 with glowstone. Setting a spawn point only works where
 * {@code canSetSpawn} holds (real vanilla: the Nether's environment attribute) — anywhere
 * else it explodes instead (real power 5.0, {@code ExplosionInteraction.BLOCK}). Charge is
 * only actually consumed when the anchor is later used to respawn (not when spawn is merely
 * set), hooked via PlayerRespawnEvent since Minestom's respawn point is a plain Pos with no
 * built-in anchor bookkeeping.
 */
public final class RespawnAnchor {
    private RespawnAnchor() {}

    public static void register(GlobalEventHandler events, InstanceContainer overworld) {
        events.addListener(PlayerUseItemOnBlockEvent.class, RespawnAnchor::useOnBlock);
        events.addListener(PlayerBlockInteractEvent.class, e -> interact(e, overworld));
        events.addListener(PlayerRespawnEvent.class, RespawnAnchor::onRespawn);
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("respawn_anchor")) return;

        int charges = Integer.parseInt(block.getProperty("charges"));
        if (charges >= 4) return;
        ItemStack held = e.getItemStack();
        if (held.isAir() || held.material() != Material.GLOWSTONE) return;

        instance.setBlock(pos, block.withProperty("charges", String.valueOf(charges + 1)));
        instance.playSound(Sound.sound(SoundEvent.BLOCK_RESPAWN_ANCHOR_CHARGE, Sound.Source.BLOCK, 1f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        e.getPlayer().setItemInHand(e.getHand(), held.consume(1));
    }

    private static void interact(PlayerBlockInteractEvent e, InstanceContainer overworld) {
        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("respawn_anchor")) return;
        if (Integer.parseInt(block.getProperty("charges")) == 0) return;

        e.setBlockingItemUse(true);
        // RespawnAnchorBlock.canSetSpawn: real vanilla gates this on a per-dimension
        // environment attribute that's true only in the Nether; approximated the same way.
        if (instance != Bootstrap.netherOf(overworld)) {
            explode(instance, pos);
            return;
        }

        Pos spawnPos = new Pos(pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
        e.getPlayer().setRespawnPoint(spawnPos);
        instance.playSound(Sound.sound(SoundEvent.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, Sound.Source.BLOCK, 1f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }

    private static void explode(Instance instance, Point pos) {
        instance.setBlock(pos, Block.AIR);
        Explosions.explode(instance, new Pos(pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5), 5f, 1.0, null);
    }

    private static void onRespawn(PlayerRespawnEvent e) {
        Pos rp = e.getRespawnPosition();
        Instance instance = e.getPlayer().getInstance();
        if (rp == null || instance == null) return;

        Block block = instance.getBlock(rp);
        if (!block.key().value().equals("respawn_anchor")) return;
        int charges = Integer.parseInt(block.getProperty("charges"));
        if (charges <= 0) return;

        instance.setBlock(rp, block.withProperty("charges", String.valueOf(charges - 1)));
        instance.playSound(Sound.sound(SoundEvent.BLOCK_RESPAWN_ANCHOR_DEPLETE, Sound.Source.BLOCK, 1f, 1f),
                rp.blockX() + 0.5, rp.blockY() + 0.5, rp.blockZ() + 0.5);
    }
}
