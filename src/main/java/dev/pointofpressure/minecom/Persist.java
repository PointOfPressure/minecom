package dev.pointofpressure.minecom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.blocks.Containers;
import dev.pointofpressure.minecom.blocks.Farming;
import dev.pointofpressure.minecom.blocks.Furnaces;
import dev.pointofpressure.minecom.survival.Experience;
import dev.pointofpressure.minecom.survival.WeatherCycle;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
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
 * World-extra state persisted to world/minecom_state.json: chest + furnace
 * contents, crop positions, world time + weather, and per-player inventory,
 * health, hunger, XP and position. Saved on shutdown, every 5 minutes, and on
 * player disconnect.
 */
public final class Persist {
    private Persist() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(Persist.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static final Path FILE = Path.of("world", "minecom_state.json");

    /** Saved player snapshots by UUID (kept for offline players). */
    private static final Map<String, JsonObject> PLAYERS = new ConcurrentHashMap<>();
    private static Instance instance;

    // ------------------------------------------------------------------ lifecycle

    public static void load(Instance overworld) {
        instance = overworld;
        if (!Files.exists(FILE)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), JsonObject.class);
            if (root.has("time")) overworld.setTime(root.get("time").getAsLong());
            if (root.has("raining")) WeatherCycle.setRaining(overworld, root.get("raining").getAsBoolean());
            if (root.has("difficulty")) {
                Difficulty.set(Difficulty.valueOf(root.get("difficulty").getAsString()));
            }

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
                    Furnaces.State state = new Furnaces.State();
                    readItems(f.getAsJsonArray("items"), state.inv);
                    state.burnTicks = f.get("burn").getAsInt();
                    state.burnTotal = Math.max(1, f.get("burnTotal").getAsInt());
                    state.cookTicks = f.get("cook").getAsInt();
                    state.xpBank = f.get("xp").getAsFloat();
                    state.instance = overworld;
                    String[] parts = e.getKey().split(",");
                    state.pos = new Vec(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]));
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
            LOGGER.error("Failed loading {}", FILE, e);
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

            JsonObject chests = new JsonObject();
            Containers.CHESTS.forEach((key, inv) -> chests.add(key, writeItems(inv)));
            root.add("chests", chests);

            JsonObject enderchests = new JsonObject();
            dev.pointofpressure.minecom.blocks.EnderChest.INVENTORIES.forEach((key, inv) -> enderchests.add(key, writeItems(inv)));
            root.add("enderchests", enderchests);

            JsonObject furnaces = new JsonObject();
            Furnaces.FURNACES.forEach((key, s) -> {
                JsonObject f = new JsonObject();
                f.add("items", writeItems(s.inv));
                f.addProperty("burn", s.burnTicks);
                f.addProperty("burnTotal", s.burnTotal);
                f.addProperty("cook", s.cookTicks);
                f.addProperty("xp", s.xpBank);
                furnaces.add(key, f);
            });
            root.add("furnaces", furnaces);

            JsonArray crops = new JsonArray();
            Farming.CROPS.forEach(crops::add);
            root.add("crops", crops);

            MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(Persist::snapshot);
            JsonObject players = new JsonObject();
            PLAYERS.forEach(players::add);
            root.add("players", players);

            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("Failed saving {}", FILE, e);
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
    }

    // ------------------------------------------------------------------ items

    private static JsonArray writeItems(AbstractInventory inv) {
        JsonArray arr = new JsonArray();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItemStack(slot);
            if (stack.isAir()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("s", slot);
            item.addProperty("m", stack.material().key().asString());
            item.addProperty("a", stack.amount());
            Integer damage = stack.get(DataComponents.DAMAGE);
            if (damage != null && damage > 0) item.addProperty("d", damage);
            arr.add(item);
        }
        return arr;
    }

    private static void readItems(JsonArray arr, AbstractInventory inv) {
        if (arr == null) return;
        for (JsonElement el : arr) {
            JsonObject item = el.getAsJsonObject();
            Material mat = Material.fromKey(item.get("m").getAsString());
            if (mat == null) continue;
            ItemStack stack = ItemStack.of(mat, item.get("a").getAsInt());
            if (item.has("d")) {
                int damage = item.get("d").getAsInt();
                stack = stack.with(b -> b.set(DataComponents.DAMAGE, damage));
            }
            int slot = item.get("s").getAsInt();
            if (slot < inv.getSize()) inv.setItemStack(slot, stack);
        }
    }
}
