package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.data.LootTables;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.entity.metadata.other.PrimedTntMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Vanilla explosion algorithm: 1352 rays cast from the center, intensity
 * 0.7-1.3x power, attenuated 0.225/step plus (resistance+0.3)*0.3 through
 * blocks. TNT drops 100% of destroyed blocks (Java 1.14+), creepers 1/power.
 */
public final class Explosions {
    private Explosions() {}

    private static final Random RANDOM = new Random();

    /** Charged-creeper mob-head drops (charged_creeper/{type}.json: always 1 head). */
    private static final java.util.Map<EntityType, ItemStack> CHARGED_CREEPER_HEADS = java.util.Map.of(
            EntityType.CREEPER, ItemStack.of(net.minestom.server.item.Material.CREEPER_HEAD),
            EntityType.SKELETON, ItemStack.of(net.minestom.server.item.Material.SKELETON_SKULL),
            EntityType.WITHER_SKELETON, ItemStack.of(net.minestom.server.item.Material.WITHER_SKELETON_SKULL),
            EntityType.ZOMBIE, ItemStack.of(net.minestom.server.item.Material.ZOMBIE_HEAD),
            EntityType.PIGLIN, ItemStack.of(net.minestom.server.item.Material.PIGLIN_HEAD));

    public static void explode(Instance instance, Point center, float power,
                               double dropChance, Entity source) {
        explode(instance, center, power, dropChance, source, false);
    }

    public static void explode(Instance instance, Point center, float power,
                               double dropChance, Entity source, boolean charged) {
        Set<Long> destroyed = new HashSet<>();
        double cx = center.x(), cy = center.y(), cz = center.z();

        for (int rx = 0; rx < 16; rx++) {
            for (int ry = 0; ry < 16; ry++) {
                for (int rz = 0; rz < 16; rz++) {
                    if (rx != 0 && rx != 15 && ry != 0 && ry != 15 && rz != 0 && rz != 15) continue;
                    double dx = rx / 7.5 - 1, dy = ry / 7.5 - 1, dz = rz / 7.5 - 1;
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= len; dy /= len; dz /= len;
                    float intensity = power * (0.7f + RANDOM.nextFloat() * 0.6f);
                    double px = cx, py = cy, pz = cz;
                    while (intensity > 0) {
                        int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
                        if (by < -64 || by > 319 || !instance.isChunkLoaded(bx >> 4, bz >> 4)) break;
                        Block block = instance.getBlock(bx, by, bz);
                        if (!block.isAir()) {
                            float resistance = block.registry().explosionResistance();
                            intensity -= (resistance + 0.3f) * 0.3f;
                            if (intensity > 0) destroyed.add(pack(bx, by, bz));
                        }
                        intensity -= 0.22500001f;
                        px += dx * 0.3; py += dy * 0.3; pz += dz * 0.3;
                    }
                }
            }
        }

        for (long key : destroyed) {
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            Block block = instance.getBlock(x, y, z);
            if (block.isAir()) continue;
            if (block.key().value().equals("tnt")) {
                instance.setBlock(x, y, z, Block.AIR);
                primeTnt(instance, new Vec(x, y, z), 10 + RANDOM.nextInt(21), source);
                continue;
            }
            Containers.onBlockRemoved(instance, new Vec(x, y, z), block);
            instance.setBlock(x, y, z, Block.AIR);
            if (RANDOM.nextDouble() < dropChance) {
                for (ItemStack drop : LootTables.blockDrops(block, ItemStack.AIR)) {
                    BlockRules.dropAt(instance, new Vec(x, y, z), drop);
                }
            }
            Fluids.notifyAround(new Vec(x, y, z));
            dev.pointofpressure.minecom.redstone.Redstone.neighborsChanged(new Vec(x, y, z));
        }

        double range = power * 2;
        for (Entity entity : instance.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living.isDead()) continue;
            double dist = living.getPosition().distance(center);
            if (dist >= range) continue;
            float damage = (float) ((1 - dist / range) * 7 * power + 1);
            living.damage(source != null
                    ? Damage.fromEntity(source, damage)
                    : new Damage(DamageType.EXPLOSION, null, null, center, damage));
            if (charged && living.isDead()) {
                ItemStack head = CHARGED_CREEPER_HEADS.get(living.getEntityType());
                if (head != null) {
                    ItemEntity headDrop = new ItemEntity(head);
                    headDrop.setInstance(instance, living.getPosition().add(0, 0.5, 0));
                    headDrop.setVelocity(new Vec(RANDOM.nextDouble() - 0.5, 2, RANDOM.nextDouble() - 0.5));
                }
            }
            Vec push = living.getPosition().asVec().sub(cx, cy, cz);
            if (push.length() > 0.01) {
                living.setVelocity(living.getVelocity().add(push.normalize().mul(8 * (1 - dist / range))
                        .add(0, 3 * (1 - dist / range), 0)));
            }
        }

        instance.playSound(Sound.sound(SoundEvent.ENTITY_GENERIC_EXPLODE, Sound.Source.BLOCK, 4f, 1f),
                cx, cy, cz);
    }

    /** Replace a TNT block with a primed TNT entity; explodes when the fuse runs out. */
    public static void primeTnt(Instance instance, Point pos, int fuseTicks, Entity source) {
        Entity tnt = new Entity(EntityType.TNT);
        PrimedTntMeta meta = (PrimedTntMeta) tnt.getEntityMeta();
        meta.setFuseTime(fuseTicks);
        tnt.setInstance(instance, new Vec(pos.blockX() + 0.5, pos.blockY(), pos.blockZ() + 0.5));
        tnt.setVelocity(new Vec(RANDOM.nextDouble() * 0.4 - 0.2, 4, RANDOM.nextDouble() * 0.4 - 0.2));
        tnt.scheduler().buildTask(() -> {
            Point at = tnt.getPosition();
            tnt.remove();
            explode(instance, at.add(0, 0.5, 0), 4f, 1.0, source);
        }).delay(TaskSchedule.tick(fuseTicks)).schedule();
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static int unpackX(long k) { return (int) (k >> 38); }
    private static int unpackY(long k) { return (int) (k << 26 >> 52); }
    private static int unpackZ(long k) { return (int) (k << 38 >> 38); }
}
