package dev.pointofpressure.minecom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.blocks.Containers;
import dev.pointofpressure.minecom.blocks.Farming;
import dev.pointofpressure.minecom.blocks.Furnaces;
import dev.pointofpressure.minecom.data.Enchants;
import dev.pointofpressure.minecom.survival.Experience;
import dev.pointofpressure.minecom.survival.WeatherCycle;
import net.kyori.adventure.nbt.TagStringIO;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.inventory.AbstractInventory;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence coordinator (docs/PERSISTENCE.md). World-level state (time,
 * weather, difficulty, ender chests, player snapshots) lives in
 * world/minecom_state.json; all chunk-anchored state (container inventories,
 * crops, tracked redstone positions, mobs, inhabited time) lives in
 * region-sharded files under world/minecom/ written by {@link RegionStore}
 * from {@link StateAdapter}s that subsystems register at boot. Saved on
 * shutdown, every 5 minutes, and on player disconnect. Legacy v0 files that
 * still carry chests/furnaces/crops sections load once and migrate to shards
 * on the next save.
 */
public final class Persist {
    private Persist() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(Persist.class);
    private static final Gson GSON = new GsonBuilder().create();

    // thread: written once at boot (or by the playtest hook) before any save
    private static Path baseDir = Path.of("world");

    /** Saved player snapshots by UUID (kept for offline players). */
    private static final Map<String, JsonObject> PLAYERS = new ConcurrentHashMap<>();
    private static final Map<String, StateAdapter> ADAPTERS = new ConcurrentHashMap<>();
    private static Instance instance;

    private static Path stateFile() {
        return baseDir.resolve("minecom_state.json");
    }

    private static Path regionDir() {
        return baseDir.resolve("minecom");
    }

    /** Subsystems register their chunk-anchored state adapter at boot. */
    public static void register(StateAdapter adapter) {
        ADAPTERS.put(adapter.kind(), adapter);
    }

    /** Playtest hook: redirect all persistence files and forget prior state. */
    public static void setBaseDirForTest(Path dir, Instance overworld) {
        baseDir = dir;
        instance = overworld;
        PLAYERS.clear();
    }

    /** Playtest hook: wipe every registered adapter's in-memory state. */
    public static void wipeAdaptersForTest() {
        for (StateAdapter adapter : ADAPTERS.values()) adapter.wipe();
    }

    /** Reload chunk-anchored state from shards (playtest drives this directly). */
    public static void loadRegions(Instance overworld) {
        RegionStore.load(regionDir(), overworld, ADAPTERS);
    }

    // ------------------------------------------------------------------ lifecycle

    public static void load(Instance overworld) {
        instance = overworld;
        loadWorldFile(overworld);
        loadRegions(overworld);
    }

    private static void loadWorldFile(Instance overworld) {
        if (!Files.exists(stateFile())) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(stateFile(), StandardCharsets.UTF_8), JsonObject.class);
            if (root.has("time")) overworld.setTime(root.get("time").getAsLong());
            if (root.has("raining")) WeatherCycle.setRaining(overworld, root.get("raining").getAsBoolean());
            if (root.has("difficulty")) {
                Difficulty.set(Difficulty.valueOf(root.get("difficulty").getAsString()));
            }
            if (root.has("gamerules")) {
                Map<String, String> rules = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("gamerules").entrySet()) {
                    rules.put(e.getKey(), e.getValue().getAsString());
                }
                GameRules.loadSnapshot(rules);
            }

            // legacy v0 sections (chests/furnaces/crops): loaded once here, saved
            // back out as region shards — the sections vanish on the next save
            if (root.has("chests")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("chests").entrySet()) {
                    Inventory inv = new Inventory(InventoryType.CHEST_3_ROW, Component.text("Chest"));
                    readItems(e.getValue().getAsJsonArray(), inv);
                    Containers.CHESTS.put(e.getKey(), inv);
                }
            }
            if (root.has("enderchests")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("enderchests").entrySet()) {
                    Inventory inv = new Inventory(InventoryType.CHEST_3_ROW, Component.text("Ender Chest"));
                    readItems(e.getValue().getAsJsonArray(), inv);
                    dev.pointofpressure.minecom.blocks.EnderChest.INVENTORIES.put(e.getKey(), inv);
                }
            }
            if (root.has("furnaces")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("furnaces").entrySet()) {
                    JsonObject f = e.getValue().getAsJsonObject();
                    String[] parts = e.getKey().split(",");
                    Vec pos = new Vec(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]));
                    // read the actual block back to know which of furnace/blast_furnace/
                    // smoker this position is (their recipe maps differ) rather than
                    // assuming plain "furnace" for all saved entries
                    String kind = overworld.getBlock(pos).key().value();
                    Furnaces.State state = new Furnaces.State(kind);
                    readItems(f.getAsJsonArray("items"), state.inv);
                    state.burnTicks = f.get("burn").getAsInt();
                    state.burnTotal = Math.max(1, f.get("burnTotal").getAsInt());
                    state.cookTicks = f.get("cook").getAsInt();
                    state.xpBank = f.get("xp").getAsFloat();
                    state.instance = overworld;
                    state.pos = pos;
                    Furnaces.FURNACES.put(e.getKey(), state);
                }
            }
            if (root.has("crops")) {
                root.getAsJsonArray("crops").forEach(c -> Farming.CROPS.add(c.getAsString()));
            }
            if (root.has("players")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
                    PLAYERS.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            }
            LOGGER.info("Loaded state: {} chests, {} ender chests, {} furnaces, {} crops, {} players",
                    Containers.CHESTS.size(), dev.pointofpressure.minecom.blocks.EnderChest.INVENTORIES.size(),
                    Furnaces.FURNACES.size(), Farming.CROPS.size(), PLAYERS.size());
        } catch (Exception e) {
            LOGGER.error("Failed loading {}", stateFile(), e);
        }
    }

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerDisconnectEvent.class, e -> snapshot(e.getPlayer()));
        events.addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn()) applyPlayer(e.getPlayer());
        });
        MinecraftServer.getSchedulerManager().buildTask(Persist::save)
                .repeat(TaskSchedule.tick(6000)).schedule();
        MinecraftServer.getSchedulerManager().buildShutdownTask(Persist::save);
    }

    public static synchronized void save() {
        if (instance == null) return;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("time", instance.getTime());
            root.addProperty("raining", WeatherCycle.isRaining(instance));
            root.addProperty("difficulty", Difficulty.current().name());

            Map<String, String> ruleValues = GameRules.snapshot();
            if (!ruleValues.isEmpty()) {
                JsonObject gamerules = new JsonObject();
                ruleValues.forEach(gamerules::addProperty);
                root.add("gamerules", gamerules);
            }

            JsonObject enderchests = new JsonObject();
            dev.pointofpressure.minecom.blocks.EnderChest.INVENTORIES.forEach((key, inv) -> enderchests.add(key, writeItems(inv)));
            root.add("enderchests", enderchests);

            MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(Persist::snapshot);
            JsonObject players = new JsonObject();
            PLAYERS.forEach(players::add);
            root.add("players", players);

            Files.createDirectories(stateFile().getParent());
            Files.writeString(stateFile(), GSON.toJson(root), StandardCharsets.UTF_8);
            RegionStore.save(regionDir(), instance, ADAPTERS.values().stream().toList());
        } catch (Exception e) {
            LOGGER.error("Failed saving {}", stateFile(), e);
        }
    }

    // ------------------------------------------------------------------ players

    private static void snapshot(Player p) {
        JsonObject o = new JsonObject();
        Pos pos = p.getPosition();
        JsonArray posArr = new JsonArray();
        posArr.add(pos.x());
        posArr.add(pos.y());
        posArr.add(pos.z());
        posArr.add(pos.yaw());
        posArr.add(pos.pitch());
        o.add("pos", posArr);
        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getFood());
        o.addProperty("saturation", p.getFoodSaturation());
        o.addProperty("xp", Experience.total(p));
        o.addProperty("enchantSeed", Enchants.seed(p));
        o.add("inv", writeItems(p.getInventory()));
        PLAYERS.put(p.getUuid().toString(), o);
    }

    /** Saved respawn position for a returning player, or null. */
    public static Pos savedPosition(java.util.UUID uuid) {
        JsonObject o = PLAYERS.get(uuid.toString());
        if (o == null || !o.has("pos")) return null;
        JsonArray a = o.getAsJsonArray("pos");
        return new Pos(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(),
                a.get(3).getAsFloat(), a.get(4).getAsFloat());
    }

    private static void applyPlayer(Player p) {
        JsonObject o = PLAYERS.get(p.getUuid().toString());
        if (o == null) return;
        readItems(o.getAsJsonArray("inv"), p.getInventory());
        float maxHealth = (float) p.getAttributeValue(Attribute.MAX_HEALTH);
        p.setHealth(Math.min(maxHealth, o.get("health").getAsFloat()));
        p.setFood(o.get("food").getAsInt());
        p.setFoodSaturation(o.get("saturation").getAsFloat());
        Experience.set(p, o.get("xp").getAsInt());
        if (o.has("enchantSeed")) Enchants.setSeed(p, o.get("enchantSeed").getAsInt());
    }

    // ------------------------------------------------------------------ items
    // (public: StateAdapters and RegionStore serialize through these)

    public static JsonArray writeItems(AbstractInventory inv) {
        JsonArray arr = new JsonArray();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItemStack(slot);
            if (stack.isAir()) continue;
            JsonObject item = writeItem(stack);
            item.addProperty("s", slot);
            arr.add(item);
        }
        return arr;
    }

    public static JsonArray writeItems(ItemStack[] items) {
        JsonArray arr = new JsonArray();
        for (int slot = 0; slot < items.length; slot++) {
            if (items[slot] == null || items[slot].isAir()) continue;
            JsonObject item = writeItem(items[slot]);
            item.addProperty("s", slot);
            arr.add(item);
        }
        return arr;
    }

    /**
     * One item without a slot. "nbt" (SNBT via toItemNBT) carries full component
     * fidelity — potions, enchants, names; "m"/"a" stay for debuggability and as
     * the legacy fallback readItem still accepts.
     */
    public static JsonObject writeItem(ItemStack stack) {
        JsonObject item = new JsonObject();
        item.addProperty("m", stack.material().key().asString());
        item.addProperty("a", stack.amount());
        Integer damage = stack.get(DataComponents.DAMAGE);
        if (damage != null && damage > 0) item.addProperty("d", damage);
        try {
            item.addProperty("nbt", TagStringIO.tagStringIO().asString(stack.toItemNBT()));
        } catch (Exception e) {
            LOGGER.warn("Item NBT serialization failed for {} — saving material+amount only",
                    stack.material().key(), e);
        }
        return item;
    }

    public static void readItems(JsonArray arr, AbstractInventory inv) {
        if (arr == null) return;
        for (JsonElement el : arr) {
            JsonObject item = el.getAsJsonObject();
            ItemStack stack = readItem(item);
            if (stack == null) continue;
            int slot = item.get("s").getAsInt();
            if (slot < inv.getSize()) inv.setItemStack(slot, stack);
        }
    }

    public static void readItems(JsonArray arr, ItemStack[] items) {
        if (arr == null) return;
        for (JsonElement el : arr) {
            JsonObject item = el.getAsJsonObject();
            ItemStack stack = readItem(item);
            if (stack == null) continue;
            int slot = item.get("s").getAsInt();
            if (slot < items.length) items[slot] = stack;
        }
    }

    /** Null when the material no longer exists. */
    public static ItemStack readItem(JsonObject item) {
        if (item.has("nbt")) {
            try {
                return ItemStack.fromItemNBT(
                        TagStringIO.tagStringIO().asCompound(item.get("nbt").getAsString()));
            } catch (Exception e) {
                LOGGER.warn("Item NBT parse failed — falling back to material+amount", e);
            }
        }
        Material mat = Material.fromKey(item.get("m").getAsString());
        if (mat == null) return null;
        ItemStack stack = ItemStack.of(mat, item.get("a").getAsInt());
        if (item.has("d")) {
            int damage = item.get("d").getAsInt();
            stack = stack.with(b -> b.set(DataComponents.DAMAGE, damage));
        }
        return stack;
    }

    // ------------------------------------------------------------------ positions

    /** Canonical "x,y,z" block-position key (matches Containers.posKey). */
    public static String posKey(Point p) {
        return p.blockX() + "," + p.blockY() + "," + p.blockZ();
    }

    public static Point parsePos(String key) {
        String[] parts = key.split(",");
        return new Vec(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }
}
