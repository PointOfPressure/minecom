package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.StateAdapter;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryProperty;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.potion.PotionType;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Brewing stands with the vanilla recipe graph: nether wart makes awkward,
 * ingredients specialize it, redstone extends, glowstone strengthens, fermented
 * spider eye corrupts. 400-tick brews, blaze powder fuels 20 of them.
 * Slots: 0-2 bottles, 3 ingredient, 4 fuel.
 */
public final class Brewing {
    private Brewing() {}

    public static final class Stand {
        public final BrewInventory inv = new BrewInventory();
        public int brewTicks;
        public int fuel;
    }

    public static final class BrewInventory extends Inventory {
        BrewInventory() {
            super(InventoryType.BREWING_STAND, Component.text("Brewing Stand"));
        }

        void property(InventoryProperty property, int value) {
            sendProperty(property, (short) value);
        }
    }

    public static final Map<String, Stand> STANDS = new ConcurrentHashMap<>();
    private static Instance instance;

    public static void register(GlobalEventHandler events, Instance overworld) {
        instance = overworld;
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().equals("brewing_stand")) return;
            e.setBlockingItemUse(true);
            Stand stand = STANDS.computeIfAbsent(Containers.posKey(e.getBlockPosition()), k -> new Stand());
            e.getPlayer().openInventory(stand.inv);
        });
        // filling bottles from water
        events.addListener(PlayerUseItemOnBlockEvent.class, e -> {
            if (e.getItemStack().material() != Material.GLASS_BOTTLE) return;
            Point clicked = e.getPosition();
            var face = e.getBlockFace().toDirection();
            Point front = clicked.add(face.normalX(), face.normalY(), face.normalZ());
            if (instance.getBlock(clicked).key().value().equals("water")
                    || instance.getBlock(front).key().value().equals("water")) {
                ItemStack water = ItemStack.of(Material.POTION).with(b ->
                        b.set(DataComponents.POTION_CONTENTS, new PotionContents(PotionType.WATER)));
                e.getPlayer().setItemInHand(e.getHand(), e.getItemStack().consume(1));
                if (!e.getPlayer().getInventory().addItemStack(water)) {
                    dev.pointofpressure.minecom.survival.Survival.dropItem(e.getPlayer(), water, true);
                }
            }
        });
        MinecraftServer.getSchedulerManager().buildTask(Brewing::tickAll)
                .repeat(TaskSchedule.tick(1)).schedule();
        Persist.register(persistence());
    }

    /** Brewing-stand persistence (docs/PERSISTENCE.md): slots + brew progress + fuel. */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "brewing_stand";
            }

            @Override
            public void collect(Instance in, BiConsumer<Point, JsonObject> out) {
                STANDS.forEach((key, stand) -> {
                    var o = new JsonObject();
                    o.add("items", Persist.writeItems(stand.inv));
                    o.addProperty("brew", stand.brewTicks);
                    o.addProperty("fuel", stand.fuel);
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                Stand stand = new Stand();
                Persist.readItems(data.getAsJsonArray("items"), stand.inv);
                stand.brewTicks = data.get("brew").getAsInt();
                stand.fuel = data.get("fuel").getAsInt();
                STANDS.put(Containers.posKey(pos), stand);
            }

            @Override
            public void wipe() {
                STANDS.clear();
            }
        };
    }

    static void onRemoved(Instance inst, Point pos) {
        Stand stand = STANDS.remove(Containers.posKey(pos));
        if (stand != null) Containers.spill(inst, pos, stand.inv);
    }

    private static void tickAll() {
        for (Stand stand : STANDS.values()) tick(stand);
    }

    private static void tick(Stand stand) {
        ItemStack ingredient = stand.inv.getItemStack(3);
        boolean brewable = !ingredient.isAir() && anyBottleAccepts(stand, ingredient.material());

        if (stand.fuel == 0) {
            ItemStack fuel = stand.inv.getItemStack(4);
            if (brewable && fuel.material() == Material.BLAZE_POWDER) {
                stand.fuel = 20;
                stand.inv.setItemStack(4, fuel.consume(1));
            }
        }

        if (brewable && stand.fuel > 0) {
            if (++stand.brewTicks >= 400) {
                stand.brewTicks = 0;
                stand.fuel--;
                for (int slot = 0; slot < 3; slot++) {
                    ItemStack bottle = stand.inv.getItemStack(slot);
                    stand.inv.setItemStack(slot, brewSlot(bottle, ingredient.material()));
                }
                stand.inv.setItemStack(3, ingredient.consume(1));
            }
        } else {
            stand.brewTicks = 0;
        }

        if (!stand.inv.getViewers().isEmpty()) {
            stand.inv.property(InventoryProperty.BREWING_STAND_BREW_TIME,
                    stand.brewTicks > 0 ? 400 - stand.brewTicks : 0);
            stand.inv.property(InventoryProperty.BREWING_STAND_FUEL_TIME, stand.fuel);
        }
    }

    private static boolean anyBottleAccepts(Stand stand, Material ingredient) {
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = stand.inv.getItemStack(slot);
            if (containerMixResult(bottle.material(), ingredient) != null) return true;
            if (brewResult(typeOf(bottle), ingredient) != null) return true;
        }
        return false;
    }

    /** A bottle can be a regular/splash/lingering potion — all three carry PotionContents. */
    private static PotionType typeOf(ItemStack bottle) {
        if (bottle.isAir()) return null;
        Material mat = bottle.material();
        if (mat != Material.POTION && mat != Material.SPLASH_POTION && mat != Material.LINGERING_POTION) return null;
        PotionContents contents = bottle.get(DataComponents.POTION_CONTENTS);
        return contents == null ? null : contents.potion();
    }

    /**
     * PotionBrewing's separate "container mix" table (decompile-verified —
     * PotionBrewing.bootStrap: addContainerRecipe(POTION, GUNPOWDER, SPLASH_POTION),
     * addContainerRecipe(SPLASH_POTION, DRAGON_BREATH, LINGERING_POTION)): gunpowder/
     * dragon_breath change the bottle's MATERIAL, keeping whatever PotionType it
     * already has — a separate mechanism from the type-mix graph (nether_wart,
     * redstone, ...) above, which never touches the container material at all.
     */
    private static Material containerMixResult(Material bottleMat, Material ingredient) {
        String ing = ingredient.key().value();
        if (bottleMat == Material.POTION && ing.equals("gunpowder")) return Material.SPLASH_POTION;
        if (bottleMat == Material.SPLASH_POTION && ing.equals("dragon_breath")) return Material.LINGERING_POTION;
        return null;
    }

    /** Apply one brew cycle to a single bottle slot: container mix first, else type mix. */
    private static ItemStack brewSlot(ItemStack bottle, Material ingredient) {
        PotionType current = typeOf(bottle);
        if (current == null) return bottle;
        Material newContainer = containerMixResult(bottle.material(), ingredient);
        if (newContainer != null) {
            return ItemStack.of(newContainer).with(b -> b.set(DataComponents.POTION_CONTENTS, new PotionContents(current)));
        }
        PotionType result = brewResult(current, ingredient);
        if (result == null) return bottle;
        // preserve the bottle's own material (regular/splash/lingering) through a type mix
        return ItemStack.of(bottle.material()).with(b -> b.set(DataComponents.POTION_CONTENTS, new PotionContents(result)));
    }

    /** The vanilla brewing graph. Returns null when the ingredient does nothing. */
    static PotionType brewResult(PotionType current, Material ingredient) {
        if (current == null) return null;
        String key = current.key().value();
        String ing = ingredient.key().value();

        if (key.equals("water")) {
            return switch (ing) {
                case "nether_wart" -> PotionType.AWKWARD;
                case "fermented_spider_eye" -> PotionType.WEAKNESS;
                default -> null;
            };
        }
        if (key.equals("awkward")) {
            return switch (ing) {
                case "sugar" -> PotionType.SWIFTNESS;
                case "blaze_powder" -> PotionType.STRENGTH;
                case "ghast_tear" -> PotionType.REGENERATION;
                case "spider_eye" -> PotionType.POISON;
                case "glistering_melon_slice" -> PotionType.HEALING;
                case "magma_cream" -> PotionType.FIRE_RESISTANCE;
                case "rabbit_foot" -> PotionType.LEAPING;
                case "golden_carrot" -> PotionType.NIGHT_VISION;
                case "pufferfish" -> PotionType.WATER_BREATHING;
                default -> null;
            };
        }
        // modifiers
        if (ing.equals("redstone") && !key.startsWith("long_") && !key.startsWith("strong_")) {
            return PotionType.fromKey("minecraft:long_" + key);
        }
        if (ing.equals("glowstone_dust") && !key.startsWith("long_") && !key.startsWith("strong_")) {
            return PotionType.fromKey("minecraft:strong_" + key);
        }
        if (ing.equals("fermented_spider_eye")) {
            String base = key.replace("long_", "").replace("strong_", "");
            return switch (base) {
                case "swiftness", "leaping" -> PotionType.SLOWNESS;
                case "healing", "poison" -> PotionType.HARMING;
                case "night_vision" -> PotionType.INVISIBILITY;
                default -> null;
            };
        }
        return null;
    }
}
