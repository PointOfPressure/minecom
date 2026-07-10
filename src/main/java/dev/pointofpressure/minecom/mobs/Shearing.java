package dev.pointofpressure.minecom.mobs;

import net.minestom.server.color.DyeColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.metadata.animal.SheepMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sheep shearing: right-click a not-yet-sheared adult sheep with shears to drop 1-3 wool of
 * its color (Sheep.shear + shearing/sheep/{color}.json: uniform(1,3) rolls, one wool item per
 * roll) and mark it sheared, damaging the shears by 1 durability. Sheep colors are assigned at
 * spawn from real vanilla's SheepColorSpawnRules (biome-tiered weighted pick, including the
 * classic 1/500 pink sheep). NOT modeled: eating grass to regrow wool / grow up faster
 * (Sheep.ate) — this project has no EatBlockGoal-equivalent grazing AI yet, a separate,
 * smaller follow-up once that exists.
 */
public final class Shearing {
    private Shearing() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerEntityInteractEvent.class, Shearing::interact);
    }

    private static void interact(PlayerEntityInteractEvent e) {
        if (e.getHand() != PlayerHand.MAIN) return;
        if (!(e.getTarget() instanceof EntityCreature sheep) || sheep.isDead()) return;
        if (sheep.getEntityType() != EntityType.SHEEP) return;
        if (!(sheep.getEntityMeta() instanceof SheepMeta meta)) return;
        if (meta.isSheared() || meta.isBaby()) return;
        ItemStack held = e.getPlayer().getItemInHand(PlayerHand.MAIN);
        if (held.material() != Material.SHEARS) return;

        Random random = ThreadLocalRandom.current();
        Material wool = WOOL_BY_COLOR.get(meta.getColor());
        if (wool == null) return;
        int rolls = Math.round(1 + random.nextFloat() * 2); // uniform(1.0,3.0) rounded, per the real loot table
        Instance instance = sheep.getInstance();
        Pos at = sheep.getPosition();
        for (int i = 0; i < rolls; i++) {
            ItemEntity drop = new ItemEntity(ItemStack.of(wool));
            drop.setInstance(instance, at.add(0, 0.5, 0));
            drop.setVelocity(new net.minestom.server.coordinate.Vec(
                    (random.nextFloat() - random.nextFloat()) * 0.1,
                    random.nextFloat() * 0.05,
                    (random.nextFloat() - random.nextFloat()) * 0.1));
        }
        meta.setSheared(true);
        e.getPlayer().setItemInHand(PlayerHand.MAIN,
                dev.pointofpressure.minecom.data.Items.damageItem(e.getPlayer(), held, 1));
    }

    private static final Map<DyeColor, Material> WOOL_BY_COLOR = Map.ofEntries(
            Map.entry(DyeColor.WHITE, Material.WHITE_WOOL), Map.entry(DyeColor.ORANGE, Material.ORANGE_WOOL),
            Map.entry(DyeColor.MAGENTA, Material.MAGENTA_WOOL), Map.entry(DyeColor.LIGHT_BLUE, Material.LIGHT_BLUE_WOOL),
            Map.entry(DyeColor.YELLOW, Material.YELLOW_WOOL), Map.entry(DyeColor.LIME, Material.LIME_WOOL),
            Map.entry(DyeColor.PINK, Material.PINK_WOOL), Map.entry(DyeColor.GRAY, Material.GRAY_WOOL),
            Map.entry(DyeColor.LIGHT_GRAY, Material.LIGHT_GRAY_WOOL), Map.entry(DyeColor.CYAN, Material.CYAN_WOOL),
            Map.entry(DyeColor.PURPLE, Material.PURPLE_WOOL), Map.entry(DyeColor.BLUE, Material.BLUE_WOOL),
            Map.entry(DyeColor.BROWN, Material.BROWN_WOOL), Map.entry(DyeColor.GREEN, Material.GREEN_WOOL),
            Map.entry(DyeColor.RED, Material.RED_WOOL), Map.entry(DyeColor.BLACK, Material.BLACK_WOOL));

    // ------------------------------------------------------------------ spawn color (SheepColorSpawnRules)

    private static final Set<String> WARM_BIOMES = Set.of(
            "minecraft:desert", "minecraft:warm_ocean", "minecraft:mangrove_swamp",
            "minecraft:deep_lukewarm_ocean", "minecraft:lukewarm_ocean",
            "minecraft:bamboo_jungle", "minecraft:jungle", "minecraft:sparse_jungle",           // #is_jungle
            "minecraft:savanna", "minecraft:savanna_plateau", "minecraft:windswept_savanna",    // #is_savanna
            "minecraft:nether_wastes", "minecraft:soul_sand_valley", "minecraft:crimson_forest", // #is_nether
            "minecraft:warped_forest", "minecraft:basalt_deltas",
            "minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands");     // #is_badlands

    private static final Set<String> COLD_BIOMES = Set.of(
            "minecraft:snowy_plains", "minecraft:ice_spikes", "minecraft:frozen_peaks",
            "minecraft:jagged_peaks", "minecraft:snowy_slopes", "minecraft:frozen_ocean",
            "minecraft:deep_frozen_ocean", "minecraft:grove", "minecraft:deep_dark",
            "minecraft:frozen_river", "minecraft:snowy_taiga", "minecraft:snowy_beach",
            "minecraft:the_end", "minecraft:end_highlands", "minecraft:end_midlands",           // #is_end
            "minecraft:small_end_islands", "minecraft:end_barrens",
            "minecraft:cold_ocean", "minecraft:deep_cold_ocean", "minecraft:old_growth_pine_taiga",
            "minecraft:old_growth_spruce_taiga", "minecraft:taiga", "minecraft:windswept_forest",
            "minecraft:windswept_gravelly_hills", "minecraft:windswept_hills", "minecraft:stony_peaks");

    /** Sheep.getRandomSheepColor: biome-tiered weighted pick (5/5/5/3/82, the 82 split 499:1 default:pink). */
    public static DyeColor randomColor(String biome, Random random) {
        // weights: black=5, gray=5, light_gray=5, brown=3, common(82: default 499/pink 1)
        int r = random.nextInt(100);
        if (WARM_BIOMES.contains(biome)) {
            if (r < 5) return DyeColor.GRAY;
            if (r < 10) return DyeColor.LIGHT_GRAY;
            if (r < 15) return DyeColor.WHITE;
            if (r < 18) return DyeColor.BLACK;
            return commonOrPink(DyeColor.BROWN, random);
        } else if (COLD_BIOMES.contains(biome)) {
            if (r < 5) return DyeColor.LIGHT_GRAY;
            if (r < 10) return DyeColor.GRAY;
            if (r < 15) return DyeColor.WHITE;
            if (r < 18) return DyeColor.BROWN;
            return commonOrPink(DyeColor.BLACK, random);
        } else {
            if (r < 5) return DyeColor.BLACK;
            if (r < 10) return DyeColor.GRAY;
            if (r < 15) return DyeColor.LIGHT_GRAY;
            if (r < 18) return DyeColor.BROWN;
            return commonOrPink(DyeColor.WHITE, random);
        }
    }

    private static DyeColor commonOrPink(DyeColor common, Random random) {
        return random.nextInt(500) == 0 ? DyeColor.PINK : common;
    }
}
