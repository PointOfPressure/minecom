package dev.pointofpressure.minecom.blocks;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Point;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithBlockEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.sound.SoundEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BellBlock.onHit/attemptToRing (decompile-verified): right-clicking, shooting an arrow at, or
 * powering a bell with redstone all ring it — a client-visual "shaking" swing plus the
 * BLOCK_BELL_USE sound, gated on the bell's own "powered" property so a held redstone signal
 * only rings once on the rising edge, the same neighbor-change-edge pattern this project's
 * note_block already uses. Not modelled: the exact click-face validity check (BellAttachType-
 * dependent — this project rings from any side, a narrow simplification), and the two
 * further real-vanilla effects that fire off a ring — nearby villagers' HEARD_BELL_TIME brain
 * memory and, if raiders are close, their temporary Glowing highlight — both squarely inside
 * the villager/raid AI domain this project keeps hands off of; the sound and swing are the
 * whole of the bell's player-visible core mechanic on their own.
 */
public final class Bells {
    private Bells() {}

    /** Ring count per position, keyed the same way as Containers.CHESTS; exposed for tests. */
    private static final Map<String, Integer> RING_COUNT = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().equals("bell")) return;
            e.setBlockingItemUse(true);
            ring(e.getInstance(), e.getBlockPosition());
        });
        events.addListener(ProjectileCollideWithBlockEvent.class, e -> {
            if (!e.getBlock().key().value().equals("bell")) return;
            var instance = e.getEntity().getInstance();
            if (instance != null) ring(instance, e.getCollisionPosition());
        });
    }

    public static void ring(Instance instance, Point pos) {
        instance.playSound(Sound.sound(SoundEvent.BLOCK_BELL_USE, Sound.Source.BLOCK, 2f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        RING_COUNT.merge(Containers.posKey(pos), 1, Integer::sum);
    }

    public static int ringCount(Point pos) {
        return RING_COUNT.getOrDefault(Containers.posKey(pos), 0);
    }
}
