package dev.pointofpressure.minecom.survival;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.item.PlayerCancelItemUseEvent;
import net.minestom.server.event.item.PlayerFinishItemUseEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;

/**
 * Spyglass, decompile-verified against {@code SpyglassItem} (26.2, freshly decompiled —
 * {@code vanilla-src/net/minecraft/world/item/SpyglassItem.java}). Real vanilla's spyglass is
 * almost entirely client-visual: the zoom FOV/scope, the third-person zoom overlay, and the
 * "held to your eye" pose are all client-only rendering this project targets real vanilla
 * clients for (the same rationale AUDIT.md's elytra entry gives for not re-deriving client-side
 * physics the client already runs authoritatively). Minestom's own raw-packet handler
 * ({@code UseItemListener}) already special-cases {@code Material.SPYGLASS} exactly the way it
 * special-cases {@code Material.GOAT_HORN} (see {@code GoatHorns.java}'s class doc): it fires
 * {@code PlayerBeginItemUseEvent} with the correct {@code ItemAnimation.SPYGLASS} and real
 * vanilla's 1200-tick (60s) {@code USE_DURATION} automatically, so nothing here needs to touch
 * use-duration wiring at all — confirmed by reading the engine source, not assumed.
 *
 * <p>What real vanilla's {@code SpyglassItem} does that the engine does NOT do for us: play
 * {@code item.spyglass.use} the instant the item is raised (inline inside {@code use()}, before
 * the use animation even starts — the same "sound fires on the use event itself, not gated on
 * the animation" timing {@code GoatHorns.java} documents for its own tune) and
 * {@code item.spyglass.stop_using} whenever scoping stops — whether that's a player releasing
 * right-click early ({@code releaseUsing}) or (only reachable by literally holding it for the
 * full 60 seconds) the use duration running out on its own ({@code finishUsingItem} — both real
 * vanilla methods call the same private {@code stopUsing}). Both real vanilla calls are plain
 * {@code Player.playSound(SoundEvent, volume, pitch)}, which (decompile-verified against
 * {@code Entity.playSound}/{@code Player.playSound}/{@code ServerLevel.playSeededSound}, all
 * freshly decompiled) routes through {@code Level.playSound(Entity except, ...)} with the
 * ACTING PLAYER as the excluded entity — real vanilla broadcasts these two sounds to every
 * OTHER nearby player, never back to the player who raised/lowered the spyglass themselves.
 * {@link Instance#playSoundExcept} is Minestom's exact equivalent of that exclusion.
 *
 * <p>Not modeled (AUDIT.md's "no spyglass" entry, now closed): none of the client-visual pieces
 * above, since there is no server-side state to drive them — a real vanilla client already
 * renders its own zoom/scope/pose the instant it sees the SPYGLASS use animation start, with no
 * further server involvement.
 */
public final class Spyglass {
    private Spyglass() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemEvent.class, Spyglass::use);
        events.addListener(PlayerFinishItemUseEvent.class, e -> {
            if (e.getItemStack().material() == Material.SPYGLASS) stopUsing(e.getPlayer());
        });
        events.addListener(PlayerCancelItemUseEvent.class, e -> {
            if (e.getItemStack().material() == Material.SPYGLASS) stopUsing(e.getPlayer());
        });
    }

    private static void use(PlayerUseItemEvent e) {
        if (e.getItemStack().material() != Material.SPYGLASS) return;
        Player player = e.getPlayer();
        Instance instance = player.getInstance();
        if (instance != null) {
            instance.playSoundExcept(player,
                    Sound.sound(SoundEvent.ITEM_SPYGLASS_USE, Sound.Source.PLAYER, 1f, 1f), player.getPosition());
        }
    }

    private static void stopUsing(Player player) {
        Instance instance = player.getInstance();
        if (instance != null) {
            instance.playSoundExcept(player,
                    Sound.sound(SoundEvent.ITEM_SPYGLASS_STOP_USING, Sound.Source.PLAYER, 1f, 1f), player.getPosition());
        }
    }
}
