package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.TickPipeline;
import dev.pointofpressure.minecom.StateAdapter;
import dev.pointofpressure.minecom.data.VanillaData;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Beehive/bee-nest block entity, ported from decompiled BeehiveBlockEntity + BeehiveBlock
 * (26.2). Holds up to {@link #MAX_OCCUPANTS} bees (occupant = ticksInHive/minTicksInHive/
 * hasNectar, matching BeehiveBlockEntity.Occupant/BeeData); a bee stored WITH nectar needs
 * {@code MIN_OCCUPATION_TICKS_NECTAR}=2400 ticks before it's eligible for release (nectarless:
 * {@code MIN_OCCUPATION_TICKS_NECTARLESS}=600), checked every tick via
 * {@code ticksInHive++ > minTicksInHive} (BeehiveBlockEntity.BeeData.tick — deterministic, no
 * roll). On release, a nectar-carrying bee delivers honey: {@code honey_level} advances by 2
 * with a 1% roll else 1, capped at 5 (BeehiveBlockEntity.releaseOccupant), and a fresh bee
 * entity is respawned just outside the hive's facing direction. isFireNearby (3x3x3 scan) and
 * playerDestroy both emergency-evacuate every occupant (BeehiveBlockEntity.
 * emptyAllLivingFromHive / BeehiveBlock.playerDestroy) and, unless the hive is
 * campfire-sedated (CampfireBlock.isSmokeyPos — up to 5 blocks straight down, stopping early
 * at a smoke-blocking solid block), anger every bee within an 8x6x8 box onto a random nearby
 * player (BeehiveBlock.angerNearbyBees). Harvesting (BeehiveBlock.useItemOn) only triggers at
 * honey_level 5: shears drop 3 honeycomb (the real HARVEST_BEEHIVE loot table's fixed,
 * non-randomized count — not itself bundled by this project's data extractor, a gameplay-loot
 * table rather than a block-loot one, so the constant is hardcoded directly) and cost 1
 * durability; a glass bottle becomes a honey bottle. Either resets honey_level to 0 and, if
 * NOT sedated, evacuates + angers occupants exactly like fire/breaking (sedated: honey resets
 * with occupants left undisturbed).
 *
 * Simplifications (AUDIT): a released bee is a fresh entity (no full NBT round-trip of the
 * bee that entered — this project doesn't serialize arbitrary entity NBT into block state),
 * front-blocked release retry/eviction and the shared "one flower memory per hive" nicety are
 * skipped, TNT/creeper/wither explosion loot-table bypass is skipped (this project has no
 * silk-touch-vs-explosion loot distinction here), and a hive placed via item retains no
 * honey_level/bee NBT copy (BeehiveBlock.playerWillDestroy's "pick up hive with bees" special
 * case — this project always drops the plain loot instead).
 */
public final class Beehives {
    private Beehives() {}

    public static final int MAX_OCCUPANTS = 3;
    private static final int MIN_TICKS_NECTAR = 2400;
    private static final int MIN_TICKS_NECTARLESS = 600;
    private static final int MAX_HONEY_LEVEL = 5;
    private static final Set<String> HIVE_BLOCKS = Set.of("beehive", "bee_nest");

    private static final class Occupant {
        int ticksInHive;
        final int minTicksInHive;
        final boolean hasNectar;

        Occupant(boolean hasNectar) {
            this.hasNectar = hasNectar;
            this.minTicksInHive = hasNectar ? MIN_TICKS_NECTAR : MIN_TICKS_NECTARLESS;
        }
    }

    private static final Map<String, List<Occupant>> HIVES = new ConcurrentHashMap<>();
    private static Instance instance;

    public static void register(GlobalEventHandler events, Instance overworld) {
        instance = overworld;
        Persist.register(persistence());
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (isHive(e.getBlock())) track(e.getBlockPosition());
        });
        events.addListener(PlayerBlockBreakEvent.class, e -> {
            if (isHive(e.getBlock())) onBroken(e.getInstance(), e.getBlockPosition(), e.getPlayer());
        });
        events.addListener(PlayerUseItemOnBlockEvent.class, Beehives::harvest);
        TickPipeline.register(TickPipeline.BLOCK_ENTITIES, "beehives", Beehives::tick);
    }

    private static boolean isHive(Block block) {
        return HIVE_BLOCKS.contains(block.key().value());
    }

    public static void track(Point pos) {
        HIVES.computeIfAbsent(Persist.posKey(pos), k -> new ArrayList<>());
    }

    /** BeehiveBlock.getAnalogOutputSignal: the raw honey_level (0-5), unscaled. Redstone.java hook. */
    public static int comparatorOutput(Block block) {
        String v = block.getProperty("honey_level");
        return v == null ? 0 : Integer.parseInt(v);
    }

    /** BeehiveBlockEntity.getHoneyLevel: the honey_level block-state property. */
    public static int honeyLevel(Point pos) {
        String v = instance.getBlock(pos).getProperty("honey_level");
        return v == null ? 0 : Integer.parseInt(v);
    }

    public static int occupantCount(Point pos) {
        List<Occupant> list = HIVES.get(Persist.posKey(pos));
        return list == null ? 0 : list.size();
    }

    public static boolean isFull(Point pos) {
        return occupantCount(pos) >= MAX_OCCUPANTS;
    }

    /** BeehiveBlockEntity.addOccupant: store a bee (discarding its entity) if there's room. */
    public static boolean addOccupant(Point pos, boolean hasNectar) {
        List<Occupant> list = HIVES.computeIfAbsent(Persist.posKey(pos), k -> new ArrayList<>());
        if (list.size() >= MAX_OCCUPANTS) return false;
        list.add(new Occupant(hasNectar));
        instance.playSound(Sound.sound(SoundEvent.BLOCK_BEEHIVE_ENTER, Sound.Source.BLOCK, 1f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        return true;
    }

    /** CampfireBlock.isSmokeyPos: up to 5 blocks straight down for a lit campfire. */
    public static boolean isSedated(Point pos) {
        for (int i = 1; i <= 5; i++) {
            Point below = new Vec(pos.blockX(), pos.blockY() - i, pos.blockZ());
            if (!instance.isChunkLoaded(below.blockX() >> 4, below.blockZ() >> 4)) return false;
            Block b = instance.getBlock(below);
            if (isLitCampfire(b)) return true;
            if (b.isSolid()) {
                Point belowThat = new Vec(below.blockX(), below.blockY() - 1, below.blockZ());
                return instance.isChunkLoaded(belowThat.blockX() >> 4, belowThat.blockZ() >> 4)
                        && isLitCampfire(instance.getBlock(belowThat));
            }
        }
        return false;
    }

    private static boolean isLitCampfire(Block b) {
        String key = b.key().value();
        return (key.equals("campfire") || key.equals("soul_campfire")) && "true".equals(b.getProperty("lit"));
    }

    /** BeehiveBlockEntity.isFireNearby: 3x3x3 centred on the hive. */
    public static boolean isFireNearby(Point pos) {
        int x = pos.blockX(), y = pos.blockY(), z = pos.blockZ();
        for (int ox = -1; ox <= 1; ox++)
            for (int oy = -1; oy <= 1; oy++)
                for (int oz = -1; oz <= 1; oz++) {
                    Block b = instance.getBlock(x + ox, y + oy, z + oz);
                    String key = b.key().value();
                    if (key.equals("fire") || key.equals("soul_fire")) return true;
                }
        return false;
    }

    /** BeehiveBlockEntity.tickOccupants + BeehiveBlockEntity.serverTick, called every tick. */
    /** Test hook: run one internal tick cycle without the real per-tick scheduler delay
     * (CLAUDE.md rule: no wall-clock waits in PlayTest — occupancy needs 600-2400 ticks). */
    public static void tickForTest() {
        tick();
    }

    private static void tick() {
        if (instance == null || HIVES.isEmpty()) return;
        for (Map.Entry<String, List<Occupant>> entry : HIVES.entrySet()) {
            Point pos = Persist.parsePos(entry.getKey());
            if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
            if (!isHive(instance.getBlock(pos))) {
                HIVES.remove(entry.getKey());
                continue;
            }
            if (isFireNearby(pos)) {
                evacuate(pos, null, true);
                continue;
            }
            List<Occupant> list = entry.getValue();
            if (list.isEmpty()) continue;
            List<Occupant> ready = new ArrayList<>();
            for (Occupant o : list) {
                if (o.ticksInHive++ > o.minTicksInHive) ready.add(o);
            }
            if (!ready.isEmpty()) {
                list.removeAll(ready);
                for (Occupant o : ready) release(pos, o, false);
            }
            if (!list.isEmpty() && ThreadLocalRandom.current().nextDouble() < 0.005) {
                instance.playSound(Sound.sound(SoundEvent.BLOCK_BEEHIVE_WORK, Sound.Source.BLOCK, 1f, 1f),
                        pos.blockX() + 0.5, pos.blockY(), pos.blockZ() + 0.5);
            }
        }
    }

    /** BeehiveBlockEntity.releaseOccupant: honey delivery + fresh bee entity spawn just outside. */
    private static void release(Point pos, Occupant o, boolean emergency) {
        if (o.hasNectar && !emergency) {
            int level = honeyLevel(pos);
            if (level < MAX_HONEY_LEVEL) {
                int inc = ThreadLocalRandom.current().nextInt(100) == 0 ? 2 : 1;
                if (level + inc > MAX_HONEY_LEVEL) inc--;
                instance.setBlock(pos, instance.getBlock(pos).withProperty("honey_level", String.valueOf(level + inc)));
            }
        }
        String facing = instance.getBlock(pos).getProperty("facing");
        Vec dir = facingVec(facing);
        double x = pos.blockX() + 0.5 + dir.x() * 0.55;
        double y = pos.blockY() + 0.5 - 0.3;
        double z = pos.blockZ() + 0.5 + dir.z() * 0.55;
        var bee = dev.pointofpressure.minecom.mobs.Bees.spawn(instance, new Pos(x, y, z));
        if (bee != null) dev.pointofpressure.minecom.mobs.Bees.setHivePos(bee, pos);
        instance.playSound(Sound.sound(SoundEvent.BLOCK_BEEHIVE_EXIT, Sound.Source.BLOCK, 1f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }

    private static Vec facingVec(String facing) {
        if (facing == null) return new Vec(0, 0, 1);
        return switch (facing) {
            case "north" -> new Vec(0, 0, -1);
            case "south" -> new Vec(0, 0, 1);
            case "west" -> new Vec(-1, 0, 0);
            case "east" -> new Vec(1, 0, 0);
            default -> new Vec(0, 0, 1);
        };
    }

    /** emptyAllLivingFromHive: evacuate every occupant as a fresh bee, no honey delivered. */
    public static void evacuate(Point pos, Player angerAt, boolean emergency) {
        List<Occupant> list = HIVES.get(Persist.posKey(pos));
        if (list == null || list.isEmpty()) return;
        List<Occupant> all = new ArrayList<>(list);
        list.clear();
        for (Occupant o : all) {
            String facing = instance.getBlock(pos).getProperty("facing");
            Vec dir = facingVec(facing);
            double x = pos.blockX() + 0.5 + dir.x() * 0.55;
            double y = pos.blockY() + 0.5 - 0.3;
            double z = pos.blockZ() + 0.5 + dir.z() * 0.55;
            var bee = dev.pointofpressure.minecom.mobs.Bees.spawn(instance, new Pos(x, y, z));
            if (bee == null) continue;
            dev.pointofpressure.minecom.mobs.Bees.setHivePos(bee, pos);
            if (angerAt != null) dev.pointofpressure.minecom.mobs.Bees.setAngry(bee, angerAt);
        }
        instance.playSound(Sound.sound(SoundEvent.BLOCK_BEEHIVE_EXIT, Sound.Source.BLOCK, 1f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }

    /** BeehiveBlock.angerNearbyBees: 8x6x8 box, un-targeted bees get a random nearby player. */
    public static void angerNearbyBees(Point pos) {
        List<Entity> bees = new ArrayList<>();
        List<Player> players = new ArrayList<>();
        for (Entity e : instance.getNearbyEntities(new Pos(pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5), 8)) {
            if (e.getEntityType() == EntityType.BEE) bees.add(e);
            else if (e instanceof Player p) players.add(p);
        }
        if (bees.isEmpty() || players.isEmpty()) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (Entity bee : bees) {
            if (bee instanceof EntityCreature ec && !dev.pointofpressure.minecom.mobs.Bees.isAngry(ec)) {
                dev.pointofpressure.minecom.mobs.Bees.setAngry(ec, players.get(rng.nextInt(players.size())));
            }
        }
    }

    /** BeehiveBlock.playerDestroy: evacuate + anger (unless PREVENTS_BEE_SPAWNS_WHEN_MINING). */
    private static void onBroken(Instance in, Point pos, Player player) {
        boolean hadBees = occupantCount(pos) > 0;
        evacuate(pos, hadBees ? player : null, true);
        HIVES.remove(Persist.posKey(pos));
    }

    /** BeehiveBlock.useItemOn: shears/glass-bottle harvest at honey_level 5. */
    private static void harvest(PlayerUseItemOnBlockEvent e) {
        Instance in = e.getInstance();
        Point pos = e.getPosition();
        if (!isHive(in.getBlock(pos))) return;
        if (honeyLevel(pos) < MAX_HONEY_LEVEL) return;
        Player player = e.getPlayer();
        ItemStack held = e.getItemStack();
        boolean emptied = false;
        if (held.material() == Material.SHEARS) {
            BlockRules.dropAt(in, pos, ItemStack.of(Material.HONEYCOMB, 3)); // fixed HARVEST_BEEHIVE loot count
            in.playSound(Sound.sound(SoundEvent.BLOCK_BEEHIVE_SHEAR, Sound.Source.BLOCK, 1f, 1f),
                    player.getPosition().x(), player.getPosition().y(), player.getPosition().z());
            player.setItemInHand(e.getHand(), damageTool(held));
            emptied = true;
        } else if (held.material() == Material.GLASS_BOTTLE) {
            in.playSound(Sound.sound(SoundEvent.ITEM_BOTTLE_FILL, Sound.Source.BLOCK, 1f, 1f),
                    player.getPosition().x(), player.getPosition().y(), player.getPosition().z());
            ItemStack remaining = held.consume(1);
            if (remaining.isAir()) {
                player.setItemInHand(e.getHand(), ItemStack.of(Material.HONEY_BOTTLE));
            } else {
                player.setItemInHand(e.getHand(), remaining);
                dev.pointofpressure.minecom.survival.Survival.dropItem(player, ItemStack.of(Material.HONEY_BOTTLE), false);
            }
            emptied = true;
        }
        if (!emptied) return;
        if (!isSedated(pos)) {
            boolean hadBees = occupantCount(pos) > 0;
            if (hadBees) angerNearbyBees(pos);
            resetHoney(pos);
            evacuate(pos, null, true);
        } else {
            resetHoney(pos);
        }
    }

    private static void resetHoney(Point pos) {
        instance.setBlock(pos, instance.getBlock(pos).withProperty("honey_level", "0"));
    }

    private static ItemStack damageTool(ItemStack shears) {
        int damage = shears.get(net.minestom.server.component.DataComponents.DAMAGE, 0) + 1;
        return shears.with(net.minestom.server.component.DataComponents.DAMAGE, damage);
    }

    /** Persistence (docs/PERSISTENCE.md): per-hive occupant list. */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "beehive";
            }

            @Override
            public void collect(Instance in, java.util.function.BiConsumer<Point, JsonObject> out) {
                HIVES.forEach((key, list) -> {
                    if (list.isEmpty()) return;
                    JsonObject o = new JsonObject();
                    JsonArray arr = new JsonArray();
                    for (Occupant occ : list) {
                        JsonObject jo = new JsonObject();
                        jo.addProperty("ticksInHive", occ.ticksInHive);
                        jo.addProperty("hasNectar", occ.hasNectar);
                        arr.add(jo);
                    }
                    o.add("occupants", arr);
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                List<Occupant> list = new ArrayList<>();
                for (var el : data.getAsJsonArray("occupants")) {
                    JsonObject jo = el.getAsJsonObject();
                    Occupant occ = new Occupant(jo.get("hasNectar").getAsBoolean());
                    occ.ticksInHive = jo.get("ticksInHive").getAsInt();
                    list.add(occ);
                }
                HIVES.put(Persist.posKey(pos), list);
            }

            @Override
            public void wipe() {
                HIVES.clear();
            }
        };
    }
}
