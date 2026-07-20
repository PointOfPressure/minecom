package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.TickPipeline;
import dev.pointofpressure.minecom.StateAdapter;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Jukebox: real per-disc song table (JukeboxSongs.bootstrap, 21 discs) driving playback length
 * and comparator output. The inserted disc + whether it's actively playing are tracked
 * separately (JukeboxSongPlayer.tick real behavior: a finished song stops emitting the
 * direct-15 signal and playing particles/sound, but the disc itself stays inserted — and
 * keeps producing its analog comparator output — until physically ejected).
 */
public final class Jukebox {
    private Jukebox() {}

    public record Song(SoundEvent sound, int lengthTicks, int comparatorOutput) {}

    private static final Map<Material, Song> SONGS = new ConcurrentHashMap<>();
    static {
        SONGS.put(Material.MUSIC_DISC_13, new Song(SoundEvent.MUSIC_DISC_13, ticks(178), 1));
        SONGS.put(Material.MUSIC_DISC_CAT, new Song(SoundEvent.MUSIC_DISC_CAT, ticks(185), 2));
        SONGS.put(Material.MUSIC_DISC_BLOCKS, new Song(SoundEvent.MUSIC_DISC_BLOCKS, ticks(345), 3));
        SONGS.put(Material.MUSIC_DISC_CHIRP, new Song(SoundEvent.MUSIC_DISC_CHIRP, ticks(185), 4));
        SONGS.put(Material.MUSIC_DISC_FAR, new Song(SoundEvent.MUSIC_DISC_FAR, ticks(174), 5));
        SONGS.put(Material.MUSIC_DISC_MALL, new Song(SoundEvent.MUSIC_DISC_MALL, ticks(197), 6));
        SONGS.put(Material.MUSIC_DISC_MELLOHI, new Song(SoundEvent.MUSIC_DISC_MELLOHI, ticks(96), 7));
        SONGS.put(Material.MUSIC_DISC_STAL, new Song(SoundEvent.MUSIC_DISC_STAL, ticks(150), 8));
        SONGS.put(Material.MUSIC_DISC_STRAD, new Song(SoundEvent.MUSIC_DISC_STRAD, ticks(188), 9));
        SONGS.put(Material.MUSIC_DISC_WARD, new Song(SoundEvent.MUSIC_DISC_WARD, ticks(251), 10));
        SONGS.put(Material.MUSIC_DISC_11, new Song(SoundEvent.MUSIC_DISC_11, ticks(71), 11));
        SONGS.put(Material.MUSIC_DISC_WAIT, new Song(SoundEvent.MUSIC_DISC_WAIT, ticks(238), 12));
        SONGS.put(Material.MUSIC_DISC_PIGSTEP, new Song(SoundEvent.MUSIC_DISC_PIGSTEP, ticks(149), 13));
        SONGS.put(Material.MUSIC_DISC_OTHERSIDE, new Song(SoundEvent.MUSIC_DISC_OTHERSIDE, ticks(195), 14));
        SONGS.put(Material.MUSIC_DISC_5, new Song(SoundEvent.MUSIC_DISC_5, ticks(178), 15));
        SONGS.put(Material.MUSIC_DISC_RELIC, new Song(SoundEvent.MUSIC_DISC_RELIC, ticks(218), 14));
        SONGS.put(Material.MUSIC_DISC_PRECIPICE, new Song(SoundEvent.MUSIC_DISC_PRECIPICE, ticks(299), 13));
        SONGS.put(Material.MUSIC_DISC_CREATOR, new Song(SoundEvent.MUSIC_DISC_CREATOR, ticks(176), 12));
        SONGS.put(Material.MUSIC_DISC_CREATOR_MUSIC_BOX, new Song(SoundEvent.MUSIC_DISC_CREATOR_MUSIC_BOX, ticks(73), 11));
        SONGS.put(Material.MUSIC_DISC_TEARS, new Song(SoundEvent.MUSIC_DISC_TEARS, ticks(175), 10));
        SONGS.put(Material.MUSIC_DISC_LAVA_CHICKEN, new Song(SoundEvent.MUSIC_DISC_LAVA_CHICKEN, ticks(134), 9));
    }

    /** JukeboxSong.lengthInTicks: ceil(seconds * 20). */
    private static int ticks(int seconds) {
        return (int) Math.ceil(seconds * 20.0);
    }

    private static final class State {
        Material disc;
        long ticksSinceStarted;
        boolean playing;
    }

    private static final Map<String, State> JUKEBOXES = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        Persist.register(persistence());
        TickPipeline.register(TickPipeline.BLOCK_ENTITIES, "jukebox", Jukebox::tickAll);
        events.addListener(PlayerUseItemOnBlockEvent.class, Jukebox::useOnBlock);
        events.addListener(PlayerBlockInteractEvent.class, Jukebox::interact);
    }

    /** Jukebox persistence (docs/PERSISTENCE.md): the inserted disc + playback progress. */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "jukebox";
            }

            @Override
            public void collect(Instance in, BiConsumer<Point, JsonObject> out) {
                JUKEBOXES.forEach((key, s) -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("disc", s.disc.key().asString());
                    o.addProperty("ticks", s.ticksSinceStarted);
                    o.addProperty("playing", s.playing);
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                Material disc = Material.fromKey(data.get("disc").getAsString());
                if (disc == null) return;
                State state = new State();
                state.disc = disc;
                state.ticksSinceStarted = data.get("ticks").getAsLong();
                state.playing = data.get("playing").getAsBoolean();
                JUKEBOXES.put(Persist.posKey(pos), state);
            }

            @Override
            public void wipe() {
                JUKEBOXES.clear();
            }
        };
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("jukebox")) return;
        if ("true".equals(block.getProperty("has_record"))) return;

        ItemStack held = e.getItemStack();
        if (held.isAir()) return;
        Song song = SONGS.get(held.material());
        if (song == null) return;

        instance.setBlock(pos, block.withProperty("has_record", "true"));
        State state = new State();
        state.disc = held.material();
        state.ticksSinceStarted = 0;
        state.playing = true;
        JUKEBOXES.put(Containers.posKey(pos), state);
        instance.playSound(Sound.sound(song.sound(), Sound.Source.RECORD, 4f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        e.getPlayer().setItemInHand(e.getHand(), held.consume(1));
    }

    private static void interact(PlayerBlockInteractEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("jukebox")) return;
        if (!"true".equals(block.getProperty("has_record"))) return;

        e.setBlockingItemUse(true);
        State state = JUKEBOXES.remove(Containers.posKey(pos));
        instance.setBlock(pos, block.withProperty("has_record", "false"));
        if (state != null && state.disc != null) {
            ItemEntity drop = new ItemEntity(ItemStack.of(state.disc));
            drop.setInstance(instance, new Pos(pos.x() + 0.5, pos.y() + 1.01, pos.z() + 0.5));
            drop.setPickupDelay(Duration.ofMillis(200));
        }
    }

    private static void tickAll() {
        for (State s : JUKEBOXES.values()) {
            if (!s.playing) continue;
            Song song = SONGS.get(s.disc);
            if (song == null) continue;
            s.ticksSinceStarted++;
            // JukeboxSong.hasFinished: ticksElapsed >= lengthInTicks + 20
            if (s.ticksSinceStarted >= song.lengthTicks() + 20L) {
                s.playing = false;
            }
        }
    }

    /** JukeboxBlock.getSignal: direct 15-signal in every direction while a song is playing. */
    public static boolean isPlaying(Point pos) {
        State s = JUKEBOXES.get(Containers.posKey(pos));
        return s != null && s.playing;
    }

    /**
     * JukeboxBlockEntity.getComparatorOutput: the inserted disc's fixed value, independent of
     * whether it's still actively playing (only 0 once the disc is physically ejected).
     */
    public static int comparatorOutput(Point pos, Block block) {
        if (!"true".equals(block.getProperty("has_record"))) return 0;
        State s = JUKEBOXES.get(Containers.posKey(pos));
        if (s == null || s.disc == null) return 0;
        Song song = SONGS.get(s.disc);
        return song != null ? song.comparatorOutput() : 0;
    }
}
