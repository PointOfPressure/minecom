package dev.pointofpressure.minecom;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.blocks.ArmorStands;
import dev.pointofpressure.minecom.blocks.ItemFrames;
import dev.pointofpressure.minecom.mobs.Breeding;
import dev.pointofpressure.minecom.mobs.Mobs;
import dev.pointofpressure.minecom.mobs.VillagerFood;
import dev.pointofpressure.minecom.mobs.VillagerTrades;
import net.minestom.server.color.DyeColor;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.metadata.AgeableMobMeta;
import net.minestom.server.entity.metadata.animal.SheepMeta;
import net.minestom.server.entity.metadata.other.ArmorStandMeta;
import net.minestom.server.entity.metadata.other.ItemFrameMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.utils.Direction;
import net.minestom.server.utils.Rotation;
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
 * Item frames/glow item frames and armor stands ride the same generic
 * entity-sweep shape as mobs (their own "deco" array per chunk, not the
 * {@link StateAdapter} SPI — like mobs, they're roaming/placed entities at a
 * floating-point {@code Pos}, not chunk-anchored block-entity data) rather
 * than a full mob snapshot, since neither is an {@code EntityCreature}.
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
        collectDecorations(instance, regions);
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
            chunk.add("deco", new JsonArray());
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
            Integer slimeSize = mob.getTag(dev.pointofpressure.minecom.mobs.ai.VanillaMobs.SLIME_SIZE);
            if (slimeSize != null) m.addProperty("slimeSize", slimeSize);
            if (mob.getEntityMeta() instanceof AgeableMobMeta ageable && ageable.isBaby()) {
                m.addProperty("baby", true);
            }
            if (mob.getEntityMeta() instanceof SheepMeta sheep) {
                m.addProperty("sheared", sheep.isSheared());
                m.addProperty("color", sheep.getColor().name());
            }
            long cooldown = Breeding.cooldownTicksRemaining(mob);
            if (cooldown > 0) m.addProperty("breedCooldown", cooldown);
            chunkOf(regions, pos.blockX() >> 4, pos.blockZ() >> 4).getAsJsonArray("mobs").add(m);
        }
    }

    /**
     * Item frames/glow item frames and armor stands: neither is an {@code EntityCreature}, so
     * they never reach {@link #collectMobs}, and a restart would otherwise silently discard
     * every placed frame/stand plus whatever they're holding/wearing — session-scoped state
     * nobody had documented as a deliberate simplification (AUDIT.md, Tier 3 batch 3).
     */
    private static void collectDecorations(Instance instance, Map<Long, JsonObject> regions) {
        for (Entity e : instance.getEntities()) {
            if (e.isRemoved()) continue;
            EntityType type = e.getEntityType();
            Pos pos = e.getPosition();
            JsonObject d = new JsonObject();
            JsonArray at = new JsonArray();
            at.add(pos.x()); at.add(pos.y()); at.add(pos.z()); at.add(pos.yaw()); at.add(pos.pitch());

            if (ItemFrames.isFrame(type)) {
                d.addProperty("kind", type.key().value());
                d.add("pos", at);
                ItemFrameMeta frameMeta = (ItemFrameMeta) e.getEntityMeta();
                d.addProperty("dir", frameMeta.getDirection().name());
                ItemStack framed = frameMeta.getItem();
                if (!framed.isAir()) d.add("item", Persist.writeItem(framed));
                d.addProperty("rot", frameMeta.getRotation().ordinal());
            } else if (type == EntityType.ARMOR_STAND && e instanceof LivingEntity stand) {
                d.addProperty("kind", "armor_stand");
                d.add("pos", at);
                ArmorStandMeta meta = (ArmorStandMeta) stand.getEntityMeta();
                d.addProperty("invisible", stand.isInvisible());
                d.addProperty("small", meta.isSmall());
                d.addProperty("noBasePlate", meta.isHasNoBasePlate());
                d.addProperty("marker", meta.isMarker());
                d.addProperty("showArms", meta.isHasArms());
                d.add("headPose", writeVec(meta.getHeadRotation()));
                d.add("bodyPose", writeVec(meta.getBodyRotation()));
                d.add("leftArmPose", writeVec(meta.getLeftArmRotation()));
                d.add("rightArmPose", writeVec(meta.getRightArmRotation()));
                d.add("leftLegPose", writeVec(meta.getLeftLegRotation()));
                d.add("rightLegPose", writeVec(meta.getRightLegRotation()));
                JsonArray equip = new JsonArray();
                boolean anyEquip = false;
                for (EquipmentSlot slot : EQUIP_SLOTS) {
                    ItemStack item = stand.getEquipment(slot);
                    equip.add(item.isAir() ? null : Persist.writeItem(item));
                    anyEquip |= !item.isAir();
                }
                if (anyEquip) d.add("equip", equip);
            } else {
                continue;
            }
            chunkOf(regions, pos.blockX() >> 4, pos.blockZ() >> 4).getAsJsonArray("deco").add(d);
        }
    }

    private static JsonArray writeVec(net.minestom.server.coordinate.Vec v) {
        JsonArray a = new JsonArray();
        a.add(v.x()); a.add(v.y()); a.add(v.z());
        return a;
    }

    private static net.minestom.server.coordinate.Vec readVec(JsonArray a) {
        return new net.minestom.server.coordinate.Vec(
                a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    // ------------------------------------------------------------------ load

    static void load(Path dir, Instance instance, Map<String, StateAdapter> byKind) {
        if (!Files.isDirectory(dir)) return;
        int entries = 0, mobs = 0, deco = 0;
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
                    if (chunk.has("deco")) { // absent in shards written before this field existed
                        for (JsonElement el : chunk.getAsJsonArray("deco")) {
                            if (restoreDecoration(instance, el.getAsJsonObject())) deco++;
                        }
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
        if (entries > 0 || mobs > 0 || deco > 0) {
            LOGGER.info("Restored {} block-entity entries, {} mobs, {} item frames/armor stands from region shards",
                    entries, mobs, deco);
        }
    }

    private static boolean restoreMob(Instance instance, JsonObject m) {
        JsonArray at = m.getAsJsonArray("pos");
        Pos pos = new Pos(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble(),
                at.get(3).getAsFloat(), at.get(4).getAsFloat());
        String kind = m.get("kind").getAsString();
        // slime/magma_cube: Mobs.spawn's plain path rolls a fresh random size (Slime.
        // finalizeSpawn), so a saved size needs the explicit-size factory instead — same
        // one Combat.death's split-on-death already uses.
        EntityCreature mob = m.has("slimeSize") && kind.equals("slime")
                ? dev.pointofpressure.minecom.mobs.ai.VanillaMobs.slime(instance, pos, m.get("slimeSize").getAsInt())
                : m.has("slimeSize") && kind.equals("magma_cube")
                ? dev.pointofpressure.minecom.mobs.ai.VanillaMobs.magmaCube(instance, pos, m.get("slimeSize").getAsInt())
                : Mobs.spawn(kind, instance, pos);
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
        if (m.has("baby") && mob.getEntityMeta() instanceof AgeableMobMeta ageable) {
            ageable.setBaby(true);
        }
        if (mob.getEntityMeta() instanceof SheepMeta sheep) {
            if (m.has("sheared")) sheep.setSheared(m.get("sheared").getAsBoolean());
            if (m.has("color")) sheep.setColor(DyeColor.valueOf(m.get("color").getAsString()));
        }
        if (m.has("breedCooldown")) Breeding.setCooldownTicks(mob, m.get("breedCooldown").getAsLong());
        return true;
    }

    private static boolean restoreDecoration(Instance instance, JsonObject d) {
        JsonArray at = d.getAsJsonArray("pos");
        Pos pos = new Pos(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble(),
                at.get(3).getAsFloat(), at.get(4).getAsFloat());
        String kind = d.get("kind").getAsString();

        if (kind.equals("armor_stand")) {
            LivingEntity stand = ArmorStands.spawnAt(instance, pos);
            ArmorStands.applyFlags(stand,
                    d.get("invisible").getAsBoolean(), d.get("small").getAsBoolean(),
                    d.get("noBasePlate").getAsBoolean(), d.get("marker").getAsBoolean(),
                    d.get("showArms").getAsBoolean());
            ArmorStands.applyPose(stand,
                    readVec(d.getAsJsonArray("headPose")), readVec(d.getAsJsonArray("bodyPose")),
                    readVec(d.getAsJsonArray("leftArmPose")), readVec(d.getAsJsonArray("rightArmPose")),
                    readVec(d.getAsJsonArray("leftLegPose")), readVec(d.getAsJsonArray("rightLegPose")));
            if (d.has("equip")) {
                JsonArray equip = d.getAsJsonArray("equip");
                for (int i = 0; i < EQUIP_SLOTS.length && i < equip.size(); i++) {
                    if (!equip.get(i).isJsonNull()) {
                        ItemStack item = Persist.readItem(equip.get(i).getAsJsonObject());
                        if (item != null) stand.setEquipment(EQUIP_SLOTS[i], item);
                    }
                }
            }
            return true;
        }

        EntityType type = EntityType.fromKey("minecraft:" + kind);
        if (type == null || !ItemFrames.isFrame(type)) {
            LOGGER.warn("Unknown decoration kind {} — not restored", kind);
            return false;
        }
        Entity frame = ItemFrames.spawnAt(instance, pos, Direction.valueOf(d.get("dir").getAsString()), type);
        if (d.has("item")) {
            ItemStack item = Persist.readItem(d.getAsJsonObject("item"));
            if (item != null) {
                Rotation rotation = Rotation.values()[d.get("rot").getAsInt()];
                frame.editEntityMeta(ItemFrameMeta.class, meta -> {
                    meta.setItem(item);
                    meta.setRotation(rotation);
                });
            }
        }
        return true;
    }
}
