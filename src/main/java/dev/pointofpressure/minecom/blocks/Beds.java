package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.Bootstrap;
import dev.pointofpressure.minecom.survival.Lightning;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;

import java.util.Set;

/**
 * Beds: set the respawn point; sleeping skips to morning at night OR during a real
 * thunderstorm (BedBlock.useWithoutItem's real gate is night-time OR thundering — NOT
 * plain rain, which this project previously (incorrectly) treated as sleep-eligible).
 * Sleeping in the Nether/End explodes the bed (real vanilla's EnvironmentAttributes.
 * BED_RULE is data-driven per dimension; approximated here as "explodes outside the
 * overworld", matching this project's existing RespawnAnchor precedent for the same
 * attribute) with real vanilla's exact power (5.0F), reusing the shared Explosions
 * helper so the explosion both destroys blocks and damages the sleeping player.
 *
 * <p>"Monsters nearby" sleep denial, decompile-verified against {@code ServerPlayer.
 * startSleepInBed} (26.2, freshly decompiled): a non-creative player can't sleep while a
 * {@code Monster} is within an AABB of ±8 blocks horizontally / ±5 vertically around the
 * bed's bottom-center, where "counts" is {@code Monster.isPreventingPlayerRest} — real
 * vanilla's base {@code Monster} implementation always returns true, with exactly ONE
 * override anywhere in the entity tree (confirmed by scanning the whole 26.2 server jar's
 * bytecode for overrides, not just the classes this project happens to implement):
 * {@code ZombifiedPiglin} only counts while angry at this specific player. {@link
 * #MONSTER_TYPES} is real vanilla's {@code Monster} subclass hierarchy (also confirmed by
 * walking each class file's superclass chain in the jar, not assumed from names) intersected
 * with the mobs this project actually spawns — several plausible-looking "monsters" are
 * real vanilla {@code Mob} subclasses, NOT {@code Monster} subclasses, and correctly do NOT
 * block sleep: ghast, hoglin, slime, magma cube/sulfur cube, phantom, shulker. Zombified
 * piglin is left out of the set entirely rather than reimplemented as "always false": this
 * project has no persistent per-mob anger/aggro timer for it (the same gap
 * {@code VanillaMobs}' wolf-anger note documents), so it can never actually be angry here,
 * making "never prevents rest" the honest behavior today.
 */
public final class Beds {
    private Beds() {}

    private static InstanceContainer overworld;

    /** Real vanilla Monster subclasses this project spawns (see class doc). */
    private static final Set<EntityType> MONSTER_TYPES = Set.of(
            EntityType.BLAZE, EntityType.BOGGED, EntityType.CAVE_SPIDER, EntityType.CREEPER,
            EntityType.DROWNED, EntityType.ELDER_GUARDIAN, EntityType.ENDERMAN, EntityType.ENDERMITE,
            EntityType.EVOKER, EntityType.GUARDIAN, EntityType.HUSK, EntityType.ILLUSIONER,
            EntityType.PARCHED, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.PILLAGER, EntityType.RAVAGER,
            EntityType.SILVERFISH, EntityType.SKELETON, EntityType.SPIDER, EntityType.STRAY,
            EntityType.VEX, EntityType.VINDICATOR, EntityType.WITCH, EntityType.WITHER,
            EntityType.WITHER_SKELETON, EntityType.ZOGLIN, EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER);

    public static void register(GlobalEventHandler events, InstanceContainer overworldInstance) {
        overworld = overworldInstance;
        events.addListener(PlayerBlockInteractEvent.class, Beds::interact);
    }

    private static void interact(PlayerBlockInteractEvent e) {
        if (!e.getBlock().key().value().endsWith("_bed")) return;
        e.setBlockingItemUse(true);
        Player player = e.getPlayer();
        Instance instance = e.getInstance();

        if (instance == Bootstrap.netherOf(overworld) || instance == Bootstrap.endOf(overworld)) {
            Point clickedPos = e.getBlockPosition();
            Block clicked = e.getBlock();
            Pos center = new Pos(clickedPos.blockX() + 0.5, clickedPos.blockY() + 0.5, clickedPos.blockZ() + 0.5);
            instance.setBlock(clickedPos, Block.AIR);
            // BedBlock.useWithoutItem removes BOTH halves, not just the one clicked
            String part = clicked.getProperty("part");
            String facing = clicked.getProperty("facing");
            if (part != null && facing != null) {
                Point otherPos = clickedPos.add("foot".equals(part) ? facingVec(facing) : facingVec(facing).mul(-1));
                if (instance.getBlock(otherPos).key().value().endsWith("_bed")) {
                    instance.setBlock(otherPos, Block.AIR);
                }
            }
            Explosions.explode(instance, center, 5f, 1.0, null);
            return;
        }

        player.setRespawnPoint(new Pos(e.getBlockPosition().blockX() + 0.5,
                e.getBlockPosition().blockY() + 0.6,
                e.getBlockPosition().blockZ() + 0.5));

        if (player.getGameMode() != GameMode.CREATIVE && nearbyMonster(instance, e.getBlockPosition())) {
            player.sendMessage(Component.text("You may not rest now, there are monsters nearby",
                    NamedTextColor.GRAY));
            return;
        }

        long time = instance.getTime() % 24000;
        if (time >= 12542 || Lightning.isThundering(instance)) {
            instance.setTime(instance.getTime() - time + 24000); // next morning
            instance.setWeather(Weather.CLEAR);
            instance.getPlayers().forEach(p ->
                    p.sendMessage(Component.text("You slept through the night.", NamedTextColor.GRAY)));
            // Stats.TIME_SINCE_REST resets only for the player who actually slept, not
            // everyone else the night-skip message goes out to.
            dev.pointofpressure.minecom.mobs.PhantomSpawning.resetTicksSinceRest(player);
        } else {
            player.sendMessage(Component.text("Respawn point set. You can only sleep at night.",
                    NamedTextColor.GRAY));
        }
    }

    /** ServerPlayer.startSleepInBed: an AABB of ±8 horizontal / ±5 vertical blocks around the
     *  bed's bottom-center, real vanilla's exact bedCenter/hRange/vRange values. */
    private static boolean nearbyMonster(Instance instance, Point bedPos) {
        double cx = bedPos.blockX() + 0.5, cy = bedPos.blockY(), cz = bedPos.blockZ() + 0.5;
        for (Entity entity : instance.getEntities()) {
            if (!MONSTER_TYPES.contains(entity.getEntityType()) || entity.isRemoved()) continue;
            Pos p = entity.getPosition();
            if (Math.abs(p.x() - cx) <= 8.0 && Math.abs(p.y() - cy) <= 5.0 && Math.abs(p.z() - cz) <= 8.0) {
                return true;
            }
        }
        return false;
    }

    private static Vec facingVec(String facing) {
        return switch (facing) {
            case "north" -> new Vec(0, 0, -1);
            case "south" -> new Vec(0, 0, 1);
            case "west" -> new Vec(-1, 0, 0);
            case "east" -> new Vec(1, 0, 0);
            default -> Vec.ZERO;
        };
    }
}
