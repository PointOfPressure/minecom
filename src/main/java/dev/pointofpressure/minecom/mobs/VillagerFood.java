package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.data.LootTables;
import dev.pointofpressure.minecom.data.VanillaData;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Villager personal food economy (Villager.java, decompile-verified): an 8-slot
 * personal inventory (AbstractVillager's SimpleContainer(8)), passive pickup of
 * #minecraft:villager_picks_up items, FOOD_POINTS of bread=4 and
 * potato/carrot/beetroot=1, and the real breeding gate
 * {@code foodLevel + countFoodPointsInInventory() >= 12} with 12 points digested
 * from each parent on success (eatAndDigestFood).
 *
 * Food enters the system two ways, as in vanilla: players toss it near a
 * villager (passive pickup), or Farmer villagers harvest mature crops and share
 * with the hungry. The farmer side is a bounded stand-in for the full
 * HarvestFarmland brain task — same crops, same drops, replants from held seeds
 * — without the walk-to-claimed-farmland choreography; food sharing mirrors
 * `hasExcessFood() >= 24 -> throw toward a villager with wantsMoreFood() < 12`.
 */
public final class VillagerFood {
    private VillagerFood() {}

    /** Villager.FOOD_POINTS. */
    private static final Map<Material, Integer> FOOD_POINTS = Map.of(
            Material.BREAD, 4, Material.POTATO, 1, Material.CARROT, 1, Material.BEETROOT, 1);

    private static final int INVENTORY_SIZE = 8;
    public static final Tag<Integer> FOOD_LEVEL = Tag.Integer("minecom:villager_food_level").defaultValue(0);

    /** Per-villager 8-slot inventory, session-scoped like the villagers themselves. */
    private static final Map<Integer, ItemStack[]> INVENTORIES = new ConcurrentHashMap<>();

    public static ItemStack[] inventory(Entity villager) {
        return INVENTORIES.computeIfAbsent(villager.getEntityId(), id -> {
            ItemStack[] slots = new ItemStack[INVENTORY_SIZE];
            java.util.Arrays.fill(slots, ItemStack.AIR);
            return slots;
        });
    }

    public static int countFoodPointsInInventory(Entity villager) {
        int points = 0;
        for (ItemStack s : inventory(villager)) {
            Integer value = FOOD_POINTS.get(s.material());
            if (value != null) points += value * s.amount();
        }
        return points;
    }

    /** Villager.canBreed (the sleep half is not modeled — these villagers don't sleep). */
    public static boolean canBreed(Entity villager) {
        boolean baby = villager.getEntityMeta() instanceof net.minestom.server.entity.metadata.AgeableMobMeta meta
                && meta.isBaby();
        return !baby && villager.getTag(FOOD_LEVEL) + countFoodPointsInInventory(villager) >= 12;
    }

    /** Villager.eatAndDigestFood: top up to 12 from the inventory, then spend 12 breeding. */
    public static void eatAndDigestFood(Entity villager) {
        eatUntilFull(villager);
        villager.setTag(FOOD_LEVEL, villager.getTag(FOOD_LEVEL) - 12);
    }

    private static void eatUntilFull(Entity villager) {
        int foodLevel = villager.getTag(FOOD_LEVEL);
        if (foodLevel >= 12) return;
        ItemStack[] slots = inventory(villager);
        for (int slot = 0; slot < slots.length && foodLevel < 12; slot++) {
            Integer value = FOOD_POINTS.get(slots[slot].material());
            if (value == null) continue;
            while (!slots[slot].isAir() && foodLevel < 12) {
                foodLevel += value;
                slots[slot] = slots[slot].consume(1);
            }
        }
        villager.setTag(FOOD_LEVEL, foodLevel);
    }

    // ------------------------------------------------------------------ pickup

    /** Villager.wantsToPickUp: tag membership + room in the inventory. */
    static boolean wantsToPickUp(Entity villager, ItemStack stack) {
        return VanillaData.itemHasTag(stack.material(), "villager_picks_up")
                && canAddItem(inventory(villager), stack);
    }

    private static boolean canAddItem(ItemStack[] slots, ItemStack stack) {
        for (ItemStack s : slots) {
            if (s.isAir()) return true;
            if (s.material() == stack.material() && s.amount() < s.material().maxStackSize()) return true;
        }
        return false;
    }

    /** Add as much of the stack as fits; returns the leftover. */
    private static ItemStack addItem(ItemStack[] slots, ItemStack stack) {
        for (int i = 0; i < slots.length && !stack.isAir(); i++) {
            if (slots[i].isAir()) {
                slots[i] = stack;
                return ItemStack.AIR;
            }
            if (slots[i].material() == stack.material() && slots[i].amount() < slots[i].material().maxStackSize()) {
                int room = slots[i].material().maxStackSize() - slots[i].amount();
                int moved = Math.min(room, stack.amount());
                slots[i] = slots[i].withAmount(slots[i].amount() + moved);
                stack = stack.withAmount(stack.amount() - moved);
                if (stack.amount() <= 0) return ItemStack.AIR;
            }
        }
        return stack;
    }

    // ------------------------------------------------------------------ ticking

    public static void start(Instance instance) {
        MinecraftServer.getSchedulerManager().buildTask(() -> pickupSweep(instance))
                .repeat(TaskSchedule.tick(10)).schedule();
        MinecraftServer.getSchedulerManager().buildTask(() -> farmerSweep(instance))
                .repeat(TaskSchedule.tick(40)).schedule();
    }

    /**
     * Mob.aiStep item pickup: villagers collect wanted items overlapping their box. Public so
     * tests can drive it directly (PlayTest.scenarioVillagerFood) instead of racing the real
     * 10-tick scheduler with a sleep-based wait — same shape as Villagers.breedTick.
     */
    public static void pickupSweep(Instance instance) {
        for (Entity v : instance.getEntities()) {
            if (v.getEntityType() != EntityType.VILLAGER || v.isRemoved()) continue;
            for (Entity near : instance.getNearbyEntities(v.getPosition(), 1.5)) {
                if (!(near instanceof ItemEntity item) || item.isRemoved()) continue;
                if (item.getAliveTicks() < 10) continue; // fresh drops settle first
                ItemStack stack = item.getItemStack();
                if (!wantsToPickUp(v, stack)) continue;
                ItemStack leftover = addItem(inventory(v), stack);
                if (leftover.isAir()) item.remove();
                else item.setItemStack(leftover);
            }
        }
    }

    /**
     * Farmer behavior sweep: harvest one mature crop within 8 blocks (collecting the
     * real loot-table drops, replanting from a held seed), and throw one food item
     * toward a hungry villager when carrying excess (>= 24 food points).
     */
    public static void farmerSweep(Instance instance) {
        for (Entity v : instance.getEntities()) {
            if (v.getEntityType() != EntityType.VILLAGER || v.isRemoved()) continue;
            if (!"farmer".equals(v.getTag(VillagerTrades.PROFESSION))) continue;
            harvestNearbyCrop(instance, v);
            maybeShareFood(instance, v);
        }
    }

    private static final Map<String, String> CROP_MATURE_AGE = Map.of(
            "wheat", "7", "carrots", "7", "potatoes", "7", "beetroots", "3");
    private static final Map<String, Material> CROP_SEED = Map.of(
            "wheat", Material.WHEAT_SEEDS, "carrots", Material.CARROT,
            "potatoes", Material.POTATO, "beetroots", Material.BEETROOT_SEEDS);

    private static void harvestNearbyCrop(Instance instance, Entity farmer) {
        Pos base = farmer.getPosition();
        int bx = base.blockX(), by = base.blockY(), bz = base.blockZ();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    if (!instance.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block block = instance.getBlock(x, y, z);
                    String key = block.key().value();
                    String matureAge = CROP_MATURE_AGE.get(key);
                    if (matureAge == null || !matureAge.equals(block.getProperty("age"))) continue;
                    ItemStack[] slots = inventory(farmer);
                    for (ItemStack drop : LootTables.blockDrops(block, ItemStack.AIR)) {
                        ItemStack leftover = addItem(slots, drop);
                        if (!leftover.isAir()) { // inventory full: drop the rest on the ground
                            ItemEntity spill = new ItemEntity(leftover);
                            spill.setInstance(instance, new Vec(x + 0.5, y + 0.5, z + 0.5));
                        }
                    }
                    // replant from a held seed, else leave the farmland bare
                    Material seed = CROP_SEED.get(key);
                    ItemStack taken = takeOne(slots, seed);
                    instance.setBlock(x, y, z, taken.isAir() ? Block.AIR : block.withProperty("age", "0"));
                    return; // one crop per sweep
                }
            }
        }
    }

    private static ItemStack takeOne(ItemStack[] slots, Material material) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].material() == material && !slots[i].isAir()) {
                slots[i] = slots[i].consume(1);
                return ItemStack.of(material);
            }
        }
        return ItemStack.AIR;
    }

    /** Farmers with excess food (>= 24 points) toss one food item to a hungry villager. */
    private static void maybeShareFood(Instance instance, Entity farmer) {
        if (countFoodPointsInInventory(farmer) < 24) return;
        Entity hungry = null;
        for (Entity other : instance.getNearbyEntities(farmer.getPosition(), 8)) {
            if (other.getEntityType() != EntityType.VILLAGER || other == farmer || other.isRemoved()) continue;
            if (other.getTag(FOOD_LEVEL) + countFoodPointsInInventory(other) < 12) {
                hungry = other;
                break;
            }
        }
        if (hungry == null) return;
        ItemStack[] slots = inventory(farmer);
        for (int i = 0; i < slots.length; i++) {
            if (!FOOD_POINTS.containsKey(slots[i].material())) continue;
            ItemStack thrown = ItemStack.of(slots[i].material());
            slots[i] = slots[i].consume(1);
            ItemEntity item = new ItemEntity(thrown);
            Pos from = farmer.getPosition();
            item.setInstance(instance, from.add(0, 1.2, 0));
            Vec dir = Vec.fromPoint(hungry.getPosition().sub(from));
            if (dir.lengthSquared() > 0.01) item.setVelocity(dir.normalize().mul(4).add(0, 2, 0));
            return;
        }
    }
}
