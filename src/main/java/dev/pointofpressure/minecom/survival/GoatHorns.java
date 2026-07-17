package dev.pointofpressure.minecom.survival;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.instrument.Instrument;
import net.minestom.server.registry.Holder;
import net.minestom.server.registry.RegistryKey;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Goat horns, decompile-verified against {@code InstrumentItem}/{@code Instruments} (26.2,
 * freshly decompiled — no cached copy existed before this pass). Minestom's own raw-packet
 * handler ({@code UseItemListener}, confirmed by reading its sources: it special-cases
 * {@code Material.GOAT_HORN} directly) already resolves the held item's
 * {@code DataComponents.INSTRUMENT} component against its own built-in
 * {@code MinecraftServer.getInstrumentRegistry()} (pre-populated with the real 8 goat-horn
 * tunes, ported from vanilla's {@code Instruments.bootstrap} with identical sound/duration/
 * range data) and fires {@code PlayerBeginItemUseEvent} with the correct 140-tick duration and
 * {@code ItemAnimation.TOOT_HORN} automatically — no per-item duration wiring needed the way
 * {@code Crossbow.java} needs for its variable quick-charge duration. What real vanilla's
 * {@code InstrumentItem.use()} does that the engine does NOT do for us: play the actual sound
 * immediately (not on finish — {@code play()} runs inline inside {@code use()}, before the use
 * animation even starts) and apply a cooldown for the tune's own duration. Both happen here on
 * {@link PlayerUseItemEvent}, the earliest point matching vanilla's timing, with the event
 * cancelled while on cooldown so Minestom's engine never starts the use-animation at all
 * (matching {@code InteractionResult.FAIL} on a cooldown-blocked click).
 *
 * <p>Cooldown is per-player, keyed by material only (not by tune): real vanilla's
 * {@code ItemCooldowns} defaults a stack's cooldown group to its base {@code Item} identity,
 * and every one of the 8 tunes shares the single {@code minecraft:goat_horn} item — so blowing
 * any tune locks out every tune for the same duration, not just the one played.
 *
 * <p>Not modeled (AUDIT): acquiring a horn (goat ramming a wall with a rare drop chance and a
 * random tune) — this project has no goat-ramming AI yet (a separate mobs/ gap), so
 * {@link #withTune} is exposed as the entry point a future ramming implementation (or a test)
 * calls to produce a tuned stack, the same shape {@code InstrumentItem.create} is in vanilla;
 * the {@code CriteriaTriggers}-style "admire goat horn" advancement hook (no advancement system
 * in this project) and the {@code GameEvent.INSTRUMENT_PLAY} sculk-vibration emission (no
 * matching tap in {@code Vibrations.emit}'s table for this event kind) are also not ported.
 */
public final class GoatHorns {
    private GoatHorns() {}

    /** Instruments.java: the 8 real resource-key paths, uniform 7.0s duration / 256-block range. */
    private static final String[] TUNES = {
            "ponder_goat_horn", "sing_goat_horn", "seek_goat_horn", "feel_goat_horn",
            "admire_goat_horn", "call_goat_horn", "yearn_goat_horn", "dream_goat_horn",
    };

    private static final Map<UUID, Long> COOLDOWN_ENDS_AT = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemEvent.class, GoatHorns::use);
    }

    /** InstrumentItem.create: tags a plain goat horn with one of the 8 real tunes. */
    public static ItemStack withTune(ItemStack horn, int tuneIndex) {
        RegistryKey<Instrument> key = RegistryKey.unsafeOf(TUNES[Math.floorMod(tuneIndex, TUNES.length)]);
        return horn.with(DataComponents.INSTRUMENT, key);
    }

    private static void use(PlayerUseItemEvent e) {
        ItemStack stack = e.getItemStack();
        if (stack.material() != Material.GOAT_HORN) return;

        Player player = e.getPlayer();
        long now = player.getInstance().getWorldAge();
        Long endsAt = COOLDOWN_ENDS_AT.get(player.getUuid());
        if (endsAt != null && now < endsAt) {
            e.setCancelled(true); // InteractionResult.FAIL while on cooldown: no sound, no use-animation
            return;
        }

        Holder<Instrument> holder = stack.get(DataComponents.INSTRUMENT);
        if (holder == null) return; // matches getInstrument().isPresent()'s gate; unreachable via withTune/normal item defaults, kept for a corrupt/modded stack
        Instrument instrument = holder.resolve(MinecraftServer.getInstrumentRegistry());
        if (instrument == null) return; // an unresolvable registry key (corrupt/modded stack) — same defensive gate

        Instance instance = player.getInstance();
        float volume = instrument.range() / 16.0f; // InstrumentItem.play
        instance.playSound(Sound.sound(instrument.soundEvent(), Sound.Source.RECORD, volume, 1f), player.getPosition());

        COOLDOWN_ENDS_AT.put(player.getUuid(), now + instrument.useDurationTicks());
    }
}
