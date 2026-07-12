package dev.pointofpressure.minecom;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.mobs.Mobs;
import dev.pointofpressure.minecom.mobs.VillagerFood;
import dev.pointofpressure.minecom.mobs.VillagerTrades;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Region-sharded persistence for chunk-anchored state (docs/PERSISTENCE.md):
 * every 32x32-chunk region saves to {@code <world>/minecom/r.<rx>.<rz>.json.gz}
 * holding per-chunk block-entity entries (via the {@link StateAdapter} SPI),
 * mob snapshots, and chunk inhabited time. The shard is the same unit as the
 * planned region-ownership threading model (docs/COMMUNITY-INTEL.md), so a
 * future region thread can serialize its own shard with no cross-thread reads.
 * Writes are atomic (tmp + move); stale shards from vanished regions are
 * deleted on save. Mobs round-trip through Mobs.spawn by kind — unknown kinds
 * are skipped with a log line, villager profession/food ride along as data.
 */
public final class RegionStore {
    private RegionStore() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionStore.class);
    private static final Gson GSON = new Gson();
    private static final int VERSION = 1;

    private static final EquipmentSlot[] EQUIP_SLOTS = {
            EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HELMET,
            EquipmentSlot.CHESTPLATE, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS};

    // ------------------------------------------------------------------ save

    static synchronized void save(Path dir, Instance instance, List<StateAdapter> adapters) throws IOException {
        Map<Long, JsonObject> regions = new HashMap<>();
        for (StateAdapter adapter : adapters) {
            adapter.collect(instance, (pos, data) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("kind", adapter.kind());
                entry.addProperty("pos", Persist.posKey(pos));
                entry.add("data", data);
                chunkOf(regions, pos.blockX() >> 4, pos.blockZ() >> 4).getAsJsonArray("be").add(entry);
            });
        }
        collectMobs(instance, regions);
        Difficulty.inhabitedSnapshot(instance).forEach((packed, ticks) -> {
            int cx = (int) (packed >> 32), cz = (int) (long) packed;
            chunkOf(regions, cx, cz).addProperty("inhabited", ticks);
        });

        Files.createDirectories(dir);
        Set<String> wanted = new HashSet<>();
        for (Map.Entry<Long, JsonObject> region : regions.entrySet()) {
            int rx = (int) (region.getKey() >> 32), rz = (int) (long) region.getKey();
            String name = "r." + rx + "." + rz + ".json.gz";
            wanted.add(name);
            Path tmp = dir.resolve(name + ".tmp");
            try (Writer writer = new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(tmp)), StandardCharsets.UTF_8)) {
                GSON.toJson(region.getValue(), writer);
            }
            Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }
        // regions with no state anymore must not restore ghosts next boot
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(dir, "r.*.json.gz")) {
            for (Path shard : shards) {
                if (!wanted.contains(shard.getFileName().toString())) Files.delete(shard);
            }
        }
    }

    private static JsonObject chunkOf(Map<Long, JsonObject> regions, int cx, int cz) {
        long regionKey = ((long) (cx >> 5) << 32) | ((cz >> 5) & 0xFFFFFFFFL);
        JsonObject region = regions.computeIfAbsent(regionKey, k -> {
            JsonObject root = new JsonObject();
            root.addProperty("v", VERSION);
            root.add("chunks", new JsonObject());
            return root;
        });
        JsonObject chunks = region.getAsJsonObject("chunks");
        String chunkKey = cx + "," + cz;
        JsonObject chunk = chunks.getAsJsonObject(chunkKey);
        if (chunk == null) {
            chunk = new JsonObject();
            chunk.add("be", new JsonArray());
            chunk.add("mobs", new JsonArray());
            chunks.add(chunkKey, chunk);
        }
        return chunk;
    }

    private static void collectMobs(Instance instance, Map<Long, JsonObject> regions) {
        for (Entity e : instance.getEntities()) {
            if (!(e instanceof EntityCreature mob) || mob.isDead() || mob.isRemoved()) continue;
            String kind = mob.getEntityType().key().value();
            if (mob.getEntityType() == EntityType.ENDER_DRAGON) continue; // fight-managed
            JsonObject m = new JsonObject();
            m.addProperty("kind", kind);
            Pos pos = mob.getPosition();
            JsonArray at = new JsonArray();
            at.add(pos.x()); at.add(pos.y()); at.add(pos.z()); at.add(pos.yaw()); at.add(pos.pitch());
            m.add("pos", at);
            m.addProperty("health", mob.getHealth());
            JsonArray equip = new JsonArray();
            boolean anyEquip = false;
            for (EquipmentSlot slot : EQUIP_SLOTS) {
                ItemStack item = mob.getEquipment(slot);
                equip.add(item.isAir() ? null : Persist.writeItem(item));
                anyEquip |= !item.isAir();
            }
            if (anyEquip) m.add("equip", equip);
            if (mob.getEntityType() == EntityType.VILLAGER) {
                String profession = mob.getTag(VillagerTrades.PROFESSION);
                if (profession != null) m.addProperty("profession", profession);
                m.addProperty("food", mob.getTag(VillagerFood.FOOD_LEVEL));
                m.add("inv", Persist.writeItems(VillagerFood.inventory(mob)));
            }
            chunkOf(regions, pos.blockX() >> 4, pos.blockZ() >> 4).getAsJsonArray("mobs").add(m);
        }
    }

    // ------------------------------------------------------------------ load

    static void load(Path dir, Instance instance, Map<String, StateAdapter> byKind) {
        if (!Files.isDirectory(dir)) return;
        int entries = 0, mobs = 0;
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(dir, "r.*.json.gz")) {
            for (Path shard : shards) {
                JsonObject root;
                try (var reader = new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(shard)), StandardCharsets.UTF_8)) {
                    root = GSON.fromJson(reader, JsonObject.class);
                }
                for (Map.Entry<String, JsonElement> c : root.getAsJsonObject("chunks").entrySet()) {
                    JsonObject chunk = c.getValue().getAsJsonObject();
                    for (JsonElement el : chunk.getAsJsonArray("be")) {
                        JsonObject entry = el.getAsJsonObject();
                        StateAdapter adapter = byKind.get(entry.get("kind").getAsString());
                        if (adapter == null) {
                            LOGGER.warn("No adapter for kind {} — entry dropped", entry.get("kind"));
                            continue;
                        }
                        adapter.restore(instance, Persist.parsePos(entry.get("pos").getAsString()),
                                entry.getAsJsonObject("data"));
                        entries++;
                    }
                    for (JsonElement el : chunk.getAsJsonArray("mobs")) {
                        if (restoreMob(instance, el.getAsJsonObject())) mobs++;
                    }
                    if (chunk.has("inhabited")) {
                        String[] parts = c.getKey().split(",");
                        Difficulty.setInhabitedTicks(instance,
                                new Pos(Integer.parseInt(parts[0]) << 4, 0, Integer.parseInt(parts[1]) << 4),
                                chunk.get("inhabited").getAsLong());
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed loading region shards from {}", dir, e);
        }
        for (StateAdapter adapter : byKind.values()) adapter.finishRestore(instance);
        if (entries > 0 || mobs > 0) {
            LOGGER.info("Restored {} block-entity entries, {} mobs from region shards", entries, mobs);
        }
    }

    private static boolean restoreMob(Instance instance, JsonObject m) {
        JsonArray at = m.getAsJsonArray("pos");
        Pos pos = new Pos(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble(),
                at.get(3).getAsFloat(), at.get(4).getAsFloat());
        String kind = m.get("kind").getAsString();
        EntityCreature mob = Mobs.spawn(kind, instance, pos);
        if (mob == null) {
            LOGGER.warn("Unknown mob kind {} — not restored", kind);
            return false;
        }
        float health = m.get("health").getAsFloat();
        if (health > 0) mob.setHealth(health);
        if (m.has("equip")) {
            JsonArray equip = m.getAsJsonArray("equip");
            for (int i = 0; i < EQUIP_SLOTS.length && i < equip.size(); i++) {
                if (!equip.get(i).isJsonNull()) {
                    ItemStack item = Persist.readItem(equip.get(i).getAsJsonObject());
                    if (item != null) mob.setEquipment(EQUIP_SLOTS[i], item);
                }
            }
        }
        if (m.has("profession")) mob.setTag(VillagerTrades.PROFESSION, m.get("profession").getAsString());
        if (m.has("food")) mob.setTag(VillagerFood.FOOD_LEVEL, m.get("food").getAsInt());
        if (m.has("inv")) Persist.readItems(m.getAsJsonArray("inv"), VillagerFood.inventory(mob));
        return true;
    }
}
