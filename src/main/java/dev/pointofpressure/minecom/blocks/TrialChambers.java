package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.StateAdapter;
import dev.pointofpressure.minecom.data.LootTables;
import dev.pointofpressure.minecom.data.VanillaData;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.timer.TaskSchedule;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Trial Chambers functional blocks: the trial spawner wave state machine and the
 * vault key-unlock flow, both decompile-verified against TrialSpawner /
 * TrialSpawnerState / TrialSpawnerStateData / VaultBlockEntity / VaultState.
 *
 * Positions and per-block config ids are captured at structure placement time
 * (VStructureGen sees each template block's entity NBT: the spawner's
 * normal_config/ominous_config and the vault's config.loot_table/key_item).
 * Both the config (SPAWNER_DEFS/VAULT_DEFS) AND the runtime progress
 * (SpawnerData/VaultData, plus the block's own trial_spawner_state/
 * vault_state/ominous properties) are persisted (done 2026-07-12, Sonnet):
 * this project's production config uses a real AnvilLoader (see Bootstrap —
 * playtest's flat/in-memory config does not), so an already-visited chunk
 * restores its saved block data directly on restart rather than
 * regenerating from the seed, meaning the structure-placement NBT hook
 * above never fires again for it. Losing the config alongside the progress
 * would leave a correctly-restored SpawnerData/VaultData permanently
 * inert, since tick() only iterates SPAWNER_DEFS/VAULT_DEFS.entrySet() to
 * decide what to process at all — see each adapter's own Javadoc below.
 *
 * Spawner flow: waiting_for_players detects a player in line of sight within 14
 * blocks -> active spawns the config's mobs (total 6 +2/extra player,
 * simultaneous 2 +1/extra, every 40 ticks by default — per-config JSON is
 * bundled) -> all dead -> 40-tick shutter pause -> one reward roll ejected per
 * detected player every 30 ticks (consumables/key tables) -> 30-minute cooldown.
 * A player carrying Bad Omen converts it to Trial Omen and flips the spawner
 * ominous (harder config, better key table). The ominous item-spawner drips and
 * per-mob equipment tables are not modeled.
 *
 * Vault flow: lights up within 4 blocks; right-clicking with the (ominous) trial
 * key while active rolls chests/trial_chambers/reward(_ominous), consumes the
 * key, ejects the items one per second, and never rewards the same player twice.
 */
public final class TrialChambers {
    private TrialChambers() {}

    private static final int REQUIRED_PLAYER_RANGE = 14;
    private static final int TARGET_COOLDOWN_LENGTH = 36000;
    private static final int MAX_MOB_TRACKING_DISTANCE_SQR = 47 * 47;

    // ------------------------------------------------------------------ registration

    private record SpawnerDef(String normalConfig, String ominousConfig) {}
    private record VaultDef(String lootTable, String keyItem, boolean ominous) {}

    private static final Map<Long, SpawnerDef> SPAWNER_DEFS = new ConcurrentHashMap<>();
    private static final Map<Long, VaultDef> VAULT_DEFS = new ConcurrentHashMap<>();
    private static final Map<Long, SpawnerData> SPAWNERS = new ConcurrentHashMap<>();
    private static final Map<Long, VaultData> VAULTS = new ConcurrentHashMap<>();

    private static Instance instance;

    /** Called from VStructureGen for every placed trial_spawner/vault template block. */
    public static void registerTemplateBlockEntity(int x, int y, int z, String blockName, CompoundBinaryTag nbt) {
        if (nbt == null) return;
        switch (blockName) {
            case "minecraft:trial_spawner" -> SPAWNER_DEFS.put(pack(x, y, z), new SpawnerDef(
                    nbt.getString("normal_config", "minecraft:trial_chamber/melee/zombie"),
                    nbt.getString("ominous_config", "minecraft:trial_chamber/melee/zombie/ominous")));
            case "minecraft:vault" -> {
                CompoundBinaryTag config = nbt.getCompound("config");
                String loot = config.getString("loot_table", "minecraft:chests/trial_chambers/reward");
                String key = config.getCompound("key_item").getString("id", "minecraft:trial_key");
                VAULT_DEFS.put(pack(x, y, z), new VaultDef(loot, key, key.contains("ominous")));
            }
        }
    }

    /** Test hook / manual registration for a spawner at a known position. */
    public static void registerSpawner(Point pos, String normalConfig, String ominousConfig) {
        SPAWNER_DEFS.put(pack(pos.blockX(), pos.blockY(), pos.blockZ()),
                new SpawnerDef(normalConfig, ominousConfig));
    }

    /** Test hook / manual registration for a vault at a known position. */
    public static void registerVault(Point pos, String lootTable, String keyItem) {
        VAULT_DEFS.put(pack(pos.blockX(), pos.blockY(), pos.blockZ()),
                new VaultDef(lootTable, keyItem, keyItem.contains("ominous")));
    }

    public static void start(Instance overworld, GlobalEventHandler events) {
        instance = overworld;
        MinecraftServer.getSchedulerManager().buildTask(TrialChambers::tick)
                .repeat(TaskSchedule.tick(1)).schedule();
        events.addListener(PlayerBlockInteractEvent.class, TrialChambers::interact);
        Persist.register(spawnerPersistence());
        Persist.register(vaultPersistence());
    }

    /**
     * Trial spawner persistence (docs/PERSISTENCE.md). Persists SPAWNER_DEFS too, not just the
     * runtime SpawnerData: this project's PRODUCTION config uses a real AnvilLoader (Bootstrap
     * — playtest's flat/in-memory config does not), so an already-visited chunk loads its saved
     * block data directly on restart rather than regenerating from the seed, meaning
     * VStructureGen's registerTemplateBlockEntity call never fires again for it. Without this,
     * a restored SpawnerData would sit correctly in SPAWNERS but tick() would never find it —
     * it only iterates SPAWNER_DEFS.entrySet() — leaving an already-progressed spawner
     * permanently inert despite "successfully" restoring. (An earlier version of this adapter
     * only persisted SpawnerData and passed every playtest check anyway, because the playtest
     * harness never runs a real AnvilLoader restart — a real gap a green test suite alone
     * wouldn't have caught.) The block's own trial_spawner_state/ominous properties are
     * restored explicitly too, since regeneration (for a genuinely fresh chunk) would reset
     * them to the template default regardless. World-age-relative fields are stored as deltas
     * from "now" at save time and re-anchored against the fresh world age at load time — the
     * same technique Breeding.cooldownTicksRemaining/setCooldownTicks already use, since a
     * fresh Instance's world age always restarts at 0. currentMobs (live entity ids, ephemeral
     * across a restart) is intentionally NOT persisted — the state machine already tolerates an
     * empty currentMobs correctly (it just spawns fresh wave mobs up to totalMobsSpawned's
     * remaining budget), matching this project's established "in-flight state, acceptable
     * loss" precedent for other ephemeral things (item entities, IN_LOVE windows).
     */
    private static StateAdapter spawnerPersistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "trial_spawner";
            }

            @Override
            public void collect(Instance in, java.util.function.BiConsumer<Point, JsonObject> out) {
                long now = in.getWorldAge();
                SPAWNER_DEFS.forEach((key, def) -> {
                    Point pos = unpack(key);
                    JsonObject o = new JsonObject();
                    o.addProperty("normalConfig", def.normalConfig());
                    o.addProperty("ominousConfig", def.ominousConfig());
                    SpawnerData data = SPAWNERS.get(key);
                    if (data != null) {
                        Block block = in.getBlock(pos);
                        o.addProperty("state", block.getProperty("trial_spawner_state") == null
                                ? "inactive" : block.getProperty("trial_spawner_state"));
                        o.addProperty("ominous", data.ominous);
                        o.addProperty("totalMobsSpawned", data.totalMobsSpawned);
                        o.addProperty("nextMobSpawnsAtDelta", data.nextMobSpawnsAt - now);
                        o.addProperty("cooldownEndsAtDelta", data.cooldownEndsAt - now);
                        o.addProperty("lastAllDeadAtDelta", data.lastAllDeadAt - now);
                        o.addProperty("nextEjectAtDelta", data.nextEjectAt - now);
                        JsonArray players = new JsonArray();
                        for (UUID u : data.detectedPlayers) players.add(u.toString());
                        o.add("detectedPlayers", players);
                    }
                    out.accept(pos, o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject d) {
                long key = pack(pos.blockX(), pos.blockY(), pos.blockZ());
                SPAWNER_DEFS.put(key, new SpawnerDef(
                        d.get("normalConfig").getAsString(), d.get("ominousConfig").getAsString()));
                if (!d.has("totalMobsSpawned")) return; // never ticked yet: def only, no progress
                long now = in.getWorldAge();
                SpawnerData data = new SpawnerData();
                data.ominous = d.get("ominous").getAsBoolean();
                data.totalMobsSpawned = d.get("totalMobsSpawned").getAsInt();
                data.nextMobSpawnsAt = now + d.get("nextMobSpawnsAtDelta").getAsLong();
                data.cooldownEndsAt = now + d.get("cooldownEndsAtDelta").getAsLong();
                data.lastAllDeadAt = now + d.get("lastAllDeadAtDelta").getAsLong();
                data.nextEjectAt = now + d.get("nextEjectAtDelta").getAsLong();
                for (JsonElement el : d.getAsJsonArray("detectedPlayers")) {
                    data.detectedPlayers.add(UUID.fromString(el.getAsString()));
                }
                SPAWNERS.put(key, data);
                Block block = in.getBlock(pos);
                if (block.key().value().equals("trial_spawner")) {
                    in.setBlock(pos, block.withProperty("trial_spawner_state", d.get("state").getAsString())
                            .withProperty("ominous", String.valueOf(data.ominous)));
                }
            }

            @Override
            public void wipe() {
                SPAWNER_DEFS.clear();
                SPAWNERS.clear();
            }
        };
    }

    /**
     * Vault persistence (docs/PERSISTENCE.md) — same shape and same AnvilLoader-vs-playtest
     * reasoning as the spawner adapter above: VAULT_DEFS is persisted too, not just VaultData,
     * since an already-visited chunk restores from a real Anvil save on restart rather than
     * regenerating and re-registering. rewardedPlayers is the one field that MUST survive a
     * restart correctly (real vanilla: exactly one unlock per player, ever — losing this would
     * let a restart un-gate a re-roll). itemsToEject is persisted too (via
     * Persist.writeItem/readItem, same per-item helpers the chest/hopper adapters use) rather
     * than dropped as "in-flight, acceptable loss" — unlike a mid-wave mob, an already-unlocked
     * reward is something the player has genuinely earned, just not received the last item of
     * yet.
     */
    private static StateAdapter vaultPersistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "trial_vault";
            }

            @Override
            public void collect(Instance in, java.util.function.BiConsumer<Point, JsonObject> out) {
                long now = in.getWorldAge();
                VAULT_DEFS.forEach((key, def) -> {
                    Point pos = unpack(key);
                    JsonObject o = new JsonObject();
                    o.addProperty("lootTable", def.lootTable());
                    o.addProperty("keyItem", def.keyItem());
                    o.addProperty("defOminous", def.ominous());
                    VaultData data = VAULTS.get(key);
                    if (data != null) {
                        Block block = in.getBlock(pos);
                        o.addProperty("state", block.getProperty("vault_state") == null
                                ? "inactive" : block.getProperty("vault_state"));
                        o.addProperty("stateResumesAtDelta", data.stateResumesAt - now);
                        JsonArray players = new JsonArray();
                        for (UUID u : data.rewardedPlayers) players.add(u.toString());
                        o.add("rewardedPlayers", players);
                        JsonArray items = new JsonArray();
                        for (ItemStack stack : data.itemsToEject) items.add(Persist.writeItem(stack));
                        o.add("itemsToEject", items);
                    }
                    out.accept(pos, o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject d) {
                long key = pack(pos.blockX(), pos.blockY(), pos.blockZ());
                VAULT_DEFS.put(key, new VaultDef(
                        d.get("lootTable").getAsString(), d.get("keyItem").getAsString(),
                        d.get("defOminous").getAsBoolean()));
                if (!d.has("stateResumesAtDelta")) return; // never ticked yet: def only, no progress
                long now = in.getWorldAge();
                VaultData data = new VaultData();
                data.stateResumesAt = now + d.get("stateResumesAtDelta").getAsLong();
                for (JsonElement el : d.getAsJsonArray("rewardedPlayers")) {
                    data.rewardedPlayers.add(UUID.fromString(el.getAsString()));
                }
                for (JsonElement el : d.getAsJsonArray("itemsToEject")) {
                    ItemStack stack = Persist.readItem(el.getAsJsonObject());
                    if (stack != null) data.itemsToEject.add(stack);
                }
                VAULTS.put(key, data);
                Block block = in.getBlock(pos);
                if (block.key().value().equals("vault")) {
                    in.setBlock(pos, block.withProperty("vault_state", d.get("state").getAsString()));
                }
            }

            @Override
            public void wipe() {
                VAULT_DEFS.clear();
                VAULTS.clear();
            }
        };
    }

    // ------------------------------------------------------------------ trial spawner

    private static final class SpawnerData {
        final Set<UUID> detectedPlayers = new HashSet<>();
        final Set<Integer> currentMobs = new HashSet<>();
        int totalMobsSpawned;
        long nextMobSpawnsAt;
        long cooldownEndsAt;
        long lastAllDeadAt;
        long nextEjectAt;
        boolean ominous;
    }

    private static void tick() {
        if (instance == null) return;
        long now = instance.getWorldAge();
        for (Map.Entry<Long, SpawnerDef> e : SPAWNER_DEFS.entrySet()) {
            tickSpawner(e.getKey(), e.getValue(), now);
        }
        for (Map.Entry<Long, VaultDef> e : VAULT_DEFS.entrySet()) {
            tickVault(e.getKey(), e.getValue(), now);
        }
    }

    private static void tickSpawner(long key, SpawnerDef def, long now) {
        Point pos = unpack(key);
        if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) return;
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("trial_spawner")) {
            SPAWNER_DEFS.remove(key);
            SPAWNERS.remove(key);
            return;
        }
        SpawnerData data = SPAWNERS.computeIfAbsent(key, k -> new SpawnerData());
        data.ominous = "true".equals(block.getProperty("ominous"));
        // TrialSpawner.tickServer: forget dead or far-away mobs
        data.currentMobs.removeIf(id -> {
            Entity mob = mobById(id);
            return mob == null || mob.isRemoved() || (mob instanceof EntityCreature c && c.isDead())
                    || mob.getPosition().distanceSquared(center(pos)) > MAX_MOB_TRACKING_DISTANCE_SQR;
        });

        String state = block.getProperty("trial_spawner_state");
        JsonObject config = activeConfig(def, data);
        switch (state == null ? "inactive" : state) {
            case "inactive" -> setState(pos, block, "waiting_for_players");
            case "waiting_for_players" -> {
                // TrialSpawner.canSpawnInLevel: trials don't run on Peaceful
                if (dev.pointofpressure.minecom.Difficulty.isPeaceful()) {
                    data.detectedPlayers.clear();
                    break;
                }
                tryDetectPlayers(pos, data, now, false);
                if (!data.detectedPlayers.isEmpty()) setState(pos, instance.getBlock(pos), "active");
            }
            case "active" -> {
                if (dev.pointofpressure.minecom.Difficulty.isPeaceful()) {
                    data.detectedPlayers.clear();
                    setState(pos, block, "waiting_for_players");
                    break;
                }
                int additionalPlayers = Math.max(0, data.detectedPlayers.size() - 1);
                tryDetectPlayers(pos, data, now, false);
                int targetTotal = (int) Math.floor(f(config, "total_mobs", 6.0f)
                        + f(config, "total_mobs_added_per_player", 2.0f) * additionalPlayers);
                int targetSimultaneous = (int) Math.floor(f(config, "simultaneous_mobs", 2.0f)
                        + f(config, "simultaneous_mobs_added_per_player", 1.0f) * additionalPlayers);
                if (data.totalMobsSpawned >= targetTotal) {
                    if (data.currentMobs.isEmpty()) {
                        data.cooldownEndsAt = now + TARGET_COOLDOWN_LENGTH;
                        data.lastAllDeadAt = now;
                        data.totalMobsSpawned = 0;
                        data.nextMobSpawnsAt = 0;
                        setState(pos, block, "waiting_for_reward_ejection");
                    }
                } else if (now >= data.nextMobSpawnsAt && data.currentMobs.size() < targetSimultaneous) {
                    Entity spawned = spawnWaveMob(pos, config);
                    if (spawned != null) {
                        data.currentMobs.add(spawned.getEntityId());
                        data.totalMobsSpawned++;
                        data.nextMobSpawnsAt = now + i(config, "ticks_between_spawn", 40);
                    }
                }
            }
            case "waiting_for_reward_ejection" -> {
                if (now >= data.lastAllDeadAt + 40) {
                    data.nextEjectAt = now;
                    setState(pos, block, "ejecting_reward");
                }
            }
            case "ejecting_reward" -> {
                if (now < data.nextEjectAt) break;
                if (data.detectedPlayers.isEmpty()) {
                    setState(pos, block, "cooldown");
                    break;
                }
                String table = pickEjectTable(config);
                for (ItemStack item : LootTables.trial(table)) ejectItem(pos, item);
                data.detectedPlayers.remove(data.detectedPlayers.iterator().next());
                data.nextEjectAt = now + 30;
            }
            case "cooldown" -> {
                tryDetectPlayers(pos, data, now, true);
                if (!data.detectedPlayers.isEmpty()) {
                    data.totalMobsSpawned = 0;
                    data.nextMobSpawnsAt = 0;
                    setState(pos, block, "active");
                } else if (now >= data.cooldownEndsAt) {
                    // cooldown over: drop the ominous upgrade and reset for the next trial
                    if (data.ominous) {
                        instance.setBlock(pos, instance.getBlock(pos).withProperty("ominous", "false"));
                    }
                    data.detectedPlayers.clear();
                    data.currentMobs.clear();
                    data.totalMobsSpawned = 0;
                    setState(pos, instance.getBlock(pos), "waiting_for_players");
                }
            }
        }
    }

    private static JsonObject activeConfig(SpawnerDef def, SpawnerData data) {
        JsonObject config = VanillaData.trialSpawnerConfig(data.ominous ? def.ominousConfig : def.normalConfig);
        return config == null ? new JsonObject() : config;
    }

    /**
     * TrialSpawnerStateData.tryDetectPlayers, throttled to every 20 ticks: players in
     * line of sight within 14 blocks join the trial. A detected player carrying Bad
     * Omen (or Trial Omen) flips the spawner ominous; a normal spawner in cooldown
     * ignores players entirely.
     */
    private static void tryDetectPlayers(Point pos, SpawnerData data, long now, boolean cooldown) {
        if ((pack(pos.blockX(), pos.blockY(), pos.blockZ()) + now) % 20 != 0) return;
        List<Player> inSight = new ArrayList<>();
        for (Player p : instance.getPlayers()) {
            if (p.getGameMode() == net.minestom.server.entity.GameMode.SPECTATOR) continue;
            if (p.getPosition().distanceSquared(center(pos)) > (double) REQUIRED_PLAYER_RANGE * REQUIRED_PLAYER_RANGE) continue;
            if (lineOfSight(pos, p)) inSight.add(p);
        }
        boolean becameOminous = false;
        if (!data.ominous) {
            for (Player p : inSight) {
                boolean omen = hasEffect(p, PotionEffect.TRIAL_OMEN) || hasEffect(p, PotionEffect.BAD_OMEN);
                if (omen) {
                    applyOminous(pos, data, p);
                    becameOminous = true;
                    break;
                }
            }
        }
        if (!cooldown || becameOminous) {
            boolean grew = false;
            for (Player p : inSight) grew |= data.detectedPlayers.add(p.getUuid());
            if (grew) data.nextMobSpawnsAt = Math.max(now + 40, data.nextMobSpawnsAt);
        }
    }

    /** TrialSpawner.applyOminous + resetAfterBecomingOminous: harder config, wave restart. */
    private static void applyOminous(Point pos, SpawnerData data, Player trigger) {
        instance.setBlock(pos, instance.getBlock(pos).withProperty("ominous", "true"));
        data.ominous = true;
        // Bad Omen transforms into Trial Omen (15 minutes) on the triggering player
        if (hasEffect(trigger, PotionEffect.BAD_OMEN)) {
            trigger.removeEffect(PotionEffect.BAD_OMEN);
            trigger.addEffect(new net.minestom.server.potion.Potion(PotionEffect.TRIAL_OMEN, 0, 18000));
        }
        for (int id : data.currentMobs) {
            Entity mob = mobById(id);
            if (mob != null) mob.remove();
        }
        data.currentMobs.clear();
        data.totalMobsSpawned = 0;
    }

    private static boolean hasEffect(Player p, PotionEffect effect) {
        return p.getActiveEffects().stream().anyMatch(t -> t.potion().effect() == effect);
    }

    /** TrialSpawner.spawnMob: random position in spawn range, needs room + line of sight. */
    private static Entity spawnWaveMob(Point pos, JsonObject config) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String kind = pickSpawnPotential(config);
        if (kind == null) return null;
        int spawnRange = i(config, "spawn_range", 4);
        for (int attempt = 0; attempt < 10; attempt++) {
            double x = pos.blockX() + (rng.nextDouble() - rng.nextDouble()) * spawnRange + 0.5;
            double y = pos.blockY() + rng.nextInt(3) - 1;
            double z = pos.blockZ() + (rng.nextDouble() - rng.nextDouble()) * spawnRange + 0.5;
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            if (instance.getBlock(bx, by, bz).isSolid() || instance.getBlock(bx, by + 1, bz).isSolid()) continue;
            EntityCreature mob = dev.pointofpressure.minecom.mobs.Mobs.spawn(kind, instance, new Pos(x, y, z));
            if (mob == null) return null; // unknown kind: don't spin forever
            return mob;
        }
        return null;
    }

    private static String pickSpawnPotential(JsonObject config) {
        JsonArray potentials = config.getAsJsonArray("spawn_potentials");
        if (potentials == null || potentials.isEmpty()) return null;
        int total = 0;
        for (JsonElement el : potentials) total += weightOf(el);
        int pick = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (JsonElement el : potentials) {
            pick -= weightOf(el);
            if (pick < 0) {
                JsonObject entity = el.getAsJsonObject().getAsJsonObject("data").getAsJsonObject("entity");
                return VanillaData.path(entity.get("id").getAsString());
            }
        }
        return null;
    }

    private static int weightOf(JsonElement el) {
        JsonObject o = el.getAsJsonObject();
        return o.has("weight") ? o.get("weight").getAsInt() : 1;
    }

    private static String pickEjectTable(JsonObject config) {
        JsonArray tables = config.getAsJsonArray("loot_tables_to_eject");
        if (tables == null || tables.isEmpty()) {
            // TrialSpawnerConfig.Builder default: consumables + key at equal weight
            return ThreadLocalRandom.current().nextBoolean()
                    ? "spawners/trial_chamber/consumables" : "spawners/trial_chamber/key";
        }
        int total = 0;
        for (JsonElement el : tables) total += weightOf(el);
        int pick = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (JsonElement el : tables) {
            pick -= weightOf(el);
            if (pick < 0) return el.getAsJsonObject().get("data").getAsString();
        }
        return "spawners/trial_chamber/consumables";
    }

    private static void ejectItem(Point pos, ItemStack item) {
        ItemEntity entity = new ItemEntity(item);
        entity.setPickupDelay(Duration.ofMillis(500));
        entity.setInstance(instance, new Vec(pos.blockX() + 0.5, pos.blockY() + 1.2, pos.blockZ() + 0.5));
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        entity.setVelocity(new Vec((rng.nextDouble() - 0.5) * 2, 4, (rng.nextDouble() - 0.5) * 2));
    }

    private static void setState(Point pos, Block block, String state) {
        if (!block.key().value().equals("trial_spawner")) return;
        instance.setBlock(pos, block.withProperty("trial_spawner_state", state));
    }

    /** Sampled ray from the spawner centre to the player's eyes; solid full blocks occlude. */
    private static boolean lineOfSight(Point pos, Player p) {
        Vec from = new Vec(pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        Vec to = new Vec(p.getPosition().x(), p.getPosition().y() + p.getEyeHeight(), p.getPosition().z());
        Vec delta = to.sub(from);
        double length = delta.length();
        if (length < 0.01) return true;
        Vec step = delta.div(length).mul(0.5);
        Vec at = from;
        for (double d = 0; d < length; d += 0.5) {
            at = at.add(step);
            Block b = instance.getBlock((int) Math.floor(at.x()), (int) Math.floor(at.y()), (int) Math.floor(at.z()));
            if (b.isSolid() && b.registry().collisionShape().relativeEnd().y() >= 1
                    && !b.key().value().equals("trial_spawner")) {
                return false;
            }
        }
        return true;
    }

    private static Entity mobById(int entityId) {
        for (Entity e : instance.getEntities()) {
            if (e.getEntityId() == entityId) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------ vault

    private static final class VaultData {
        final Set<UUID> rewardedPlayers = new HashSet<>();
        final ArrayDeque<ItemStack> itemsToEject = new ArrayDeque<>();
        long stateResumesAt;
    }

    private static void tickVault(long key, VaultDef def, long now) {
        Point pos = unpack(key);
        if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) return;
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("vault")) {
            VAULT_DEFS.remove(key);
            VAULTS.remove(key);
            return;
        }
        VaultData data = VAULTS.computeIfAbsent(key, k -> new VaultData());
        if (now < data.stateResumesAt) return;
        String state = block.getProperty("vault_state");
        switch (state == null ? "inactive" : state) {
            case "inactive", "active" -> {
                boolean near = playerWithin(pos, "active".equals(state) ? 4.5 : 4.0);
                String next = near ? "active" : "inactive";
                if (!next.equals(state)) instance.setBlock(pos, block.withProperty("vault_state", next));
                data.stateResumesAt = now + 20;
            }
            case "unlocking" -> {
                instance.setBlock(pos, block.withProperty("vault_state", "ejecting"));
                data.stateResumesAt = now + 20;
            }
            case "ejecting" -> {
                if (data.itemsToEject.isEmpty()) {
                    instance.setBlock(pos, block.withProperty("vault_state",
                            playerWithin(pos, 4.5) ? "active" : "inactive"));
                } else {
                    ejectItem(pos, data.itemsToEject.poll());
                    data.stateResumesAt = now + 20;
                }
            }
        }
    }

    private static boolean playerWithin(Point pos, double range) {
        for (Player p : instance.getPlayers()) {
            if (p.getPosition().distanceSquared(center(pos)) <= range * range) return true;
        }
        return false;
    }

    /** VaultBlockEntity.Server.tryInsertKey. */
    private static void interact(PlayerBlockInteractEvent e) {
        if (!e.getBlock().key().value().equals("vault")) return;
        long key = pack(e.getBlockPosition().blockX(), e.getBlockPosition().blockY(), e.getBlockPosition().blockZ());
        VaultDef def = VAULT_DEFS.get(key);
        if (def == null) return;
        e.setBlockingItemUse(true);
        Block block = e.getBlock();
        if (!"active".equals(block.getProperty("vault_state"))) return;
        Player player = e.getPlayer();
        ItemStack held = player.getItemInMainHand();
        if (!held.material().key().asString().equals(def.keyItem)) return;
        VaultData data = VAULTS.computeIfAbsent(key, k -> new VaultData());
        if (data.rewardedPlayers.contains(player.getUuid())) return;
        List<ItemStack> loot = LootTables.trial(def.lootTable);
        if (loot.isEmpty()) return;
        player.setItemInMainHand(held.consume(1));
        data.rewardedPlayers.add(player.getUuid());
        data.itemsToEject.addAll(loot);
        data.stateResumesAt = instance.getWorldAge() + 14;
        instance.setBlock(e.getBlockPosition(), block.withProperty("vault_state", "unlocking"));
    }

    // ------------------------------------------------------------------ util

    private static float f(JsonObject config, String field, float fallback) {
        JsonElement v = config.get(field);
        return v == null ? fallback : v.getAsFloat();
    }

    private static int i(JsonObject config, String field, int fallback) {
        JsonElement v = config.get(field);
        return v == null ? fallback : v.getAsInt();
    }

    private static Vec center(Point pos) {
        return new Vec(pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static Point unpack(long k) {
        return new Vec((int) (k >> 38), (int) (k << 26 >> 52), (int) (k << 38 >> 38));
    }
}
