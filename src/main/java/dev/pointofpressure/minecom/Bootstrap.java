package dev.pointofpressure.minecom;

import dev.pointofpressure.minecom.blocks.Beds;
import dev.pointofpressure.minecom.blocks.BlockRules;
import dev.pointofpressure.minecom.blocks.Containers;
import dev.pointofpressure.minecom.blocks.Farming;
import dev.pointofpressure.minecom.blocks.Fluids;
import dev.pointofpressure.minecom.blocks.Placement;
import dev.pointofpressure.minecom.data.VanillaData;
import dev.pointofpressure.minecom.mobs.Combat;
import dev.pointofpressure.minecom.mobs.Mobs;
import dev.pointofpressure.minecom.survival.Experience;
import dev.pointofpressure.minecom.survival.Survival;
import dev.pointofpressure.minecom.survival.WeatherCycle;
import dev.pointofpressure.minecom.worldgen.WorldGen;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.anvil.AnvilLoader;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.Generator;

/**
 * Shared server wiring used by both the real server (Main) and the headless
 * playtest harness — identical systems, so what the harness verifies is what
 * players get.
 */
public final class Bootstrap {
    private Bootstrap() {}

    public record Config(boolean flatWorld, boolean naturalSpawns, boolean persistence) {
        public static Config production() { return new Config(false, true, true); }
        public static Config playtest() { return new Config(true, false, false); }
    }

    /** Flat stone platform for deterministic playtests: surface at y=40. */
    public static final int FLAT_SURFACE = 40;

    /** The vanilla-parity overworld generator (null in flat/playtest mode). */
    private static dev.pointofpressure.minecom.worldgen.vanilla.VanillaGen vanillaGen;

    public static dev.pointofpressure.minecom.worldgen.vanilla.VanillaGen vanillaGen() {
        return vanillaGen;
    }

    /** The vanilla-parity End generator (null in flat/playtest mode). */
    private static dev.pointofpressure.minecom.worldgen.vanilla.VEndGen endGenStatic;

    public static dev.pointofpressure.minecom.worldgen.vanilla.VEndGen endGen() {
        return endGenStatic;
    }

    /** World seed, persisted in world/seed.txt on first boot. */
    public static long worldSeed() {
        java.nio.file.Path path = java.nio.file.Path.of("world", "seed.txt");
        try {
            if (java.nio.file.Files.exists(path)) {
                return Long.parseLong(java.nio.file.Files.readString(path).trim());
            }
            long seed = new java.security.SecureRandom().nextLong();
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, Long.toString(seed));
            return seed;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read/write world seed", e);
        }
    }

    public static InstanceContainer boot(Config config) {
        VanillaData.load();

        InstanceContainer overworld;
        if (config.flatWorld()) {
            overworld = MinecraftServer.getInstanceManager().createInstanceContainer();
            Generator flat = unit -> unit.modifier().fillHeight(0, FLAT_SURFACE + 1, Block.STONE);
            overworld.setGenerator(flat);
        } else {
            overworld = MinecraftServer.getInstanceManager().createInstanceContainer(new AnvilLoader("world"));
            vanillaGen = new dev.pointofpressure.minecom.worldgen.vanilla.VanillaGen(worldSeed());
            overworld.setGenerator(vanillaGen);
        }
        overworld.setChunkSupplier(LightingChunk::new);
        overworld.setTime(1000);

        InstanceContainer nether = config.flatWorld()
                ? MinecraftServer.getInstanceManager().createInstanceContainer(
                        net.minestom.server.world.DimensionType.THE_NETHER)
                : MinecraftServer.getInstanceManager().createInstanceContainer(
                        net.minestom.server.world.DimensionType.THE_NETHER,
                        new AnvilLoader("world_nether"));
        nether.setChunkSupplier(LightingChunk::new);
        nether.setGenerator(new dev.pointofpressure.minecom.worldgen.NetherGen());
        overworld.setTag(NETHER_TAG, nether);

        InstanceContainer end = config.flatWorld()
                ? MinecraftServer.getInstanceManager().createInstanceContainer(
                        net.minestom.server.world.DimensionType.THE_END)
                : MinecraftServer.getInstanceManager().createInstanceContainer(
                        net.minestom.server.world.DimensionType.THE_END,
                        new AnvilLoader("world_the_end"));
        end.setChunkSupplier(LightingChunk::new);
        dev.pointofpressure.minecom.worldgen.vanilla.VEndGen endGen = null;
        if (!config.flatWorld()) {
            endGen = new dev.pointofpressure.minecom.worldgen.vanilla.VEndGen(worldSeed());
            end.setGenerator(endGen);
            // place the ten obsidian spikes on the central island once its chunks are ready
            var spikeChunks = new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
            for (int cx = -3; cx <= 2; cx++) {
                for (int cz = -3; cz <= 2; cz++) spikeChunks.add(end.loadChunk(cx, cz));
            }
            java.util.concurrent.CompletableFuture
                    .allOf(spikeChunks.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .thenRun(() -> dev.pointofpressure.minecom.worldgen.vanilla.VEndSpikes.placeAll(end, worldSeed()));
        }
        final dev.pointofpressure.minecom.worldgen.vanilla.VEndGen endGenF = endGen;
        endGenStatic = endGen;
        overworld.setTag(END_TAG, end);
        if (!config.flatWorld()) {
            end.eventNode().addListener(net.minestom.server.event.instance.InstanceChunkLoadEvent.class, event ->
                    dev.pointofpressure.minecom.worldgen.vanilla.VChorus.placeChunk(
                            end, event.getChunkX(), event.getChunkZ(), worldSeed(), endGenF));
        }
        dev.pointofpressure.minecom.worldgen.vanilla.EndGateways.register(end);

        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();
        Survival.register(events);
        Experience.register(events);
        Combat.register(events);
        // Redstone registers first: its shears-disarm-tripwire handling cancels the break
        // before BlockRules.onBreak (registered next) would otherwise drop the wire item too.
        dev.pointofpressure.minecom.redstone.Redstone.register(events);
        dev.pointofpressure.minecom.redstone.Redstone.start(overworld);
        BlockRules.register(events);
        Placement.register(events);
        Containers.register(events);
        Fluids.register(events);
        Fluids.start(overworld);
        Farming.register(events);
        Farming.start(overworld);
        Beds.register(events);
        dev.pointofpressure.minecom.redstone.Hoppers.start(overworld);
        dev.pointofpressure.minecom.data.Enchants.register(events, overworld);
        dev.pointofpressure.minecom.mobs.EnderDragonFight.register(events);
        if (!config.flatWorld()) dev.pointofpressure.minecom.mobs.Villagers.register(overworld);
        if (!config.flatWorld()) dev.pointofpressure.minecom.worldgen.Strongholds.register(overworld);
        dev.pointofpressure.minecom.worldgen.Strongholds.registerEyeInteraction(events);
        dev.pointofpressure.minecom.mobs.VillagerTrades.register(events);
        dev.pointofpressure.minecom.blocks.Minecarts.register(events);
        dev.pointofpressure.minecom.blocks.Boats.register(events);
        // sneak dismounts from any vehicle (boat, minecart, ...)
        events.addListener(net.minestom.server.event.player.PlayerStartSneakingEvent.class, e -> {
            var vehicle = e.getPlayer().getVehicle();
            if (vehicle != null) vehicle.removePassenger(e.getPlayer());
        });
        dev.pointofpressure.minecom.mobs.Breeding.register(events);
        dev.pointofpressure.minecom.mobs.Breeding.start(overworld);
        dev.pointofpressure.minecom.mobs.Shearing.register(events);
        dev.pointofpressure.minecom.blocks.PumpkinCarving.register(events);
        dev.pointofpressure.minecom.blocks.Harvesting.register(events);
        dev.pointofpressure.minecom.blocks.NoteBlocks.register(events);
        dev.pointofpressure.minecom.blocks.Campfires.register(events);
        dev.pointofpressure.minecom.blocks.Composter.register(events);
        dev.pointofpressure.minecom.blocks.Jukebox.register(events);
        dev.pointofpressure.minecom.blocks.Lectern.register(events);
        dev.pointofpressure.minecom.blocks.RespawnAnchor.register(events, overworld);
        dev.pointofpressure.minecom.survival.Potions.register(events, overworld);
        dev.pointofpressure.minecom.blocks.Brewing.register(events, overworld);
        dev.pointofpressure.minecom.blocks.Anvils.register(events);
        dev.pointofpressure.minecom.survival.Fishing.register(events);
        dev.pointofpressure.minecom.survival.Bow.register(events);
        dev.pointofpressure.minecom.survival.Crossbow.register(events);
        dev.pointofpressure.minecom.survival.Trident.register(events);
        dev.pointofpressure.minecom.blocks.Portals.register(events, overworld, nether);
        WeatherCycle.start(overworld);
        dev.pointofpressure.minecom.survival.Snow.start(overworld);
        dev.pointofpressure.minecom.survival.Lightning.start(overworld);
        dev.pointofpressure.minecom.survival.Breath.start(overworld);

        if (config.persistence()) {
            Persist.load(overworld);
            Persist.register(events);
        }
        if (config.naturalSpawns()) {
            var gen = vanillaGen;
            dev.pointofpressure.minecom.mobs.VNaturalSpawner.BiomeAt biomeAt = gen != null
                    ? (x, y, z) -> gen.biomes().biomeAt(x >> 2, y >> 2, z >> 2)
                    : (x, y, z) -> "minecraft:plains";
            Mobs.startVanillaSpawner(overworld, biomeAt);
            Mobs.startVanillaSpawner(nether,
                    (x, y, z) -> dev.pointofpressure.minecom.worldgen.NetherGen.biomeAt(x, z));
            if (!config.flatWorld()) {
                Mobs.startVanillaSpawner(end, (x, y, z) -> endGenF.biomeAt(x, z), true); // End: always dark
            }
        }

        Pos spawn = config.flatWorld()
                ? new Pos(0.5, FLAT_SURFACE + 1, 0.5)
                : findSpawn();

        var commands = MinecraftServer.getCommandManager();
        commands.register(new Commands.Gamemode());
        commands.register(new Commands.Tp());
        commands.register(new Commands.Give());
        commands.register(new Commands.Time(overworld));
        commands.register(new Commands.Spawn(spawn));
        commands.register(new Commands.KillMe());
        commands.register(new Commands.WeatherCmd(overworld));
        commands.register(new Commands.Drain());
        commands.register(new Commands.Summon(overworld));
        commands.register(new Commands.Enchant());
        commands.register(new Commands.End(overworld, spawn));
        commands.register(new Commands.RaidCmd());
        commands.register(new Commands.LocateStrongholdCmd());

        dev.pointofpressure.minecom.blocks.EndPortal.register(overworld, end, spawn);
        overworld.setTag(SPAWN_TAG, spawn);
        return overworld;
    }

    public static final net.minestom.server.tag.Tag<Pos> SPAWN_TAG =
            net.minestom.server.tag.Tag.Transient("minecom:spawn");
    public static final net.minestom.server.tag.Tag<InstanceContainer> NETHER_TAG =
            net.minestom.server.tag.Tag.Transient("minecom:nether");
    public static final net.minestom.server.tag.Tag<InstanceContainer> END_TAG =
            net.minestom.server.tag.Tag.Transient("minecom:end");

    public static InstanceContainer netherOf(InstanceContainer overworld) {
        return overworld.getTag(NETHER_TAG);
    }

    public static InstanceContainer endOf(InstanceContainer overworld) {
        return overworld.getTag(END_TAG);
    }

    public static Pos spawnOf(InstanceContainer overworld) {
        return overworld.getTag(SPAWN_TAG);
    }

    /** Walk outward from the origin until the generator reports safe dry land. */
    private static Pos findSpawn() {
        if (vanillaGen == null) {
            return new Pos(0.5, WorldGen.surfaceHeight(0, 0) + 2, 0.5);
        }
        for (int radius = 0; radius <= 2048; radius += 16) {
            for (int x = -radius; x <= radius; x += 16) {
                for (int z = -radius; z <= radius; z += 16) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                    if (vanillaGen.preliminarySurface(x, z) < 64) continue;
                    int[] top = vanillaGen.topBlock(x, z);
                    if (top[1] == dev.pointofpressure.minecom.worldgen.vanilla.VAquifer.STONE
                            && top[0] >= 62 && top[0] <= 200) {
                        return new Pos(x + 0.5, top[0] + 1, z + 0.5);
                    }
                }
            }
        }
        return new Pos(0.5, vanillaGen.topBlock(0, 0)[0] + 2, 0.5);
    }
}
