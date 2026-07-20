package dev.pointofpressure.minecom.survival;

import dev.pointofpressure.minecom.EntityIndex;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityProjectile;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.other.AreaEffectCloudMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithBlockEvent;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithEntityEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.potion.PotionType;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thrown splash and lingering potions. Ported from decompiled
 * ThrownSplashPotion / ThrownLingeringPotion / AreaEffectCloud (26.1.2):
 * splash hits affect living entities within a 4-block reach of the impact
 * with effect strength scaled 1 - dist/4 (durations under 20 ticks are
 * dropped); lingering hits leave an area cloud — radius 3.0 shrinking by
 * 0.5 per application plus radius/duration per tick, 10gt arming delay,
 * 600gt lifetime, effects at 1/4 duration with a 20gt per-entity cooldown.
 * Distance scaling uses impact-to-entity center distance rather than
 * vanilla's AABB-to-AABB distance (documented approximation, AUDIT.md).
 * Throw speed is vanilla's 0.5 shoot units (dispensers: 1.375) through
 * Bow.java's established 32/3 unit conversion.
 */
public final class ThrownPotions {
    private ThrownPotions() {}

    private static final Tag<String> POTION = Tag.String("minecom_thrown_potion");
    private static final Tag<Boolean> LINGERING = Tag.Boolean("minecom_thrown_lingering");
    private static final float UNIT = 32f / 3.0f; // vanilla shoot() units -> velocity, per Bow.java

    private static Instance instance;
    private static final Set<Cloud> CLOUDS = ConcurrentHashMap.newKeySet();

    /** One lingering cloud: shrinks with use and age, applies at 1/4 duration. */
    private static final class Cloud {
        final Point pos;
        final String potion;
        final Entity display;
        double radius = 3.0;
        int age;
        final Map<Integer, Integer> lastApplied = new HashMap<>();

        Cloud(Point pos, String potion, Entity display) {
            this.pos = pos;
            this.potion = potion;
            this.display = display;
        }
    }

    public static void register(GlobalEventHandler events, Instance overworld) {
        instance = overworld;
        events.addListener(PlayerUseItemEvent.class, e -> {
            Material mat = e.getItemStack().material();
            if (mat != Material.SPLASH_POTION && mat != Material.LINGERING_POTION) return;
            Player player = e.getPlayer();
            Pos from = player.getPosition().add(0, player.getEyeHeight(), 0);
            launch(player.getInstance(), player, from, player.getPosition().direction().mul(0.5f * UNIT),
                    e.getItemStack());
            if (player.getGameMode() != net.minestom.server.entity.GameMode.CREATIVE) {
                player.setItemInHand(e.getHand(), e.getItemStack().consume(1));
            }
        });
        events.addListener(ProjectileCollideWithBlockEvent.class,
                e -> onHit(e.getEntity(), e.getCollisionPosition()));
        events.addListener(ProjectileCollideWithEntityEvent.class,
                e -> onHit(e.getEntity(), e.getTarget().getPosition()));
        MinecraftServer.getSchedulerManager().buildTask(ThrownPotions::tickClouds)
                .repeat(TaskSchedule.tick(5)).schedule();
    }

    /** Shared by player throws and the dispenser behavior; returns the projectile. */
    public static EntityProjectile launch(Instance in, Entity shooter, Point from, Vec velocity,
                                          ItemStack potionItem) {
        boolean lingering = potionItem.material() == Material.LINGERING_POTION;
        PotionContents contents = potionItem.get(DataComponents.POTION_CONTENTS);
        String type = contents != null && contents.potion() != null
                ? contents.potion().key().value() : "water";
        EntityProjectile thrown = new EntityProjectile(shooter,
                lingering ? EntityType.LINGERING_POTION : EntityType.SPLASH_POTION);
        thrown.setTag(POTION, type);
        thrown.setTag(LINGERING, lingering);
        thrown.setInstance(in, new Pos(from.x(), from.y(), from.z()));
        thrown.setVelocity(velocity);
        return thrown;
    }

    private static void onHit(Entity projectile, Point at) {
        EntityType type = projectile.getEntityType();
        if (type != EntityType.SPLASH_POTION && type != EntityType.LINGERING_POTION) return;
        String potion = projectile.getTag(POTION);
        Instance in = projectile.getInstance(); // capture before remove() clears it
        projectile.remove();
        if (potion == null || in == null) return;

        if (Boolean.TRUE.equals(projectile.getTag(LINGERING))) {
            Entity display = new Entity(EntityType.AREA_EFFECT_CLOUD);
            if (display.getEntityMeta() instanceof AreaEffectCloudMeta meta) meta.setRadius(3.0f);
            display.setNoGravity(true);
            display.setInstance(in, new Pos(at.x(), at.y(), at.z()));
            CLOUDS.add(new Cloud(at, potion, display));
            return;
        }

        // splash: 4-block reach, strength scaled by distance from the impact
        PotionType potionType = resolve(potion);
        if (potionType == null) return;
        for (Entity entity : EntityIndex.near(in, at, 4.0)) {
            if (!(entity instanceof LivingEntity le) || le.isRemoved()) continue;
            double dist = le.getPosition().distance(at);
            if (dist >= 4.0) continue;
            Potions.apply(le, potionType, 1.0 - dist / 4.0);
        }
    }

    private static void tickClouds(){
        for (Cloud cloud : CLOUDS) {
            cloud.age += 5;
            if (cloud.age > 610 || cloud.radius <= 0.5) {
                cloud.display.remove();
                CLOUDS.remove(cloud);
                continue;
            }
            if (cloud.age < 10) continue; // arming delay
            cloud.radius -= 3.0 / 600 * 5; // radiusPerTick = -radius/duration
            PotionType potionType = resolve(cloud.potion);
            if (potionType == null) continue;
            for (Entity entity : EntityIndex.near(instance, cloud.pos, cloud.radius)) {
                if (!(entity instanceof LivingEntity le) || le.isRemoved()) continue;
                Point p = le.getPosition();
                double dx = p.x() - cloud.pos.x(), dz = p.z() - cloud.pos.z();
                if (dx * dx + dz * dz > cloud.radius * cloud.radius) continue;
                if (Math.abs(p.y() - cloud.pos.y()) > 2.0) continue;
                Integer last = cloud.lastApplied.get(le.getEntityId());
                if (last != null && cloud.age - last < 20) continue; // reapplication cooldown
                cloud.lastApplied.put(le.getEntityId(), cloud.age);
                Potions.apply(le, potionType, 0.25); // cloud effects at 1/4 duration
                cloud.radius -= 0.5; // radiusOnUse
            }
            if (cloud.display.getEntityMeta() instanceof AreaEffectCloudMeta meta) {
                meta.setRadius((float) cloud.radius);
            }
        }
    }

    private static PotionType resolve(String key) {
        try {
            return PotionType.fromKey(key);
        } catch (Exception invalid) {
            return null;
        }
    }
}
