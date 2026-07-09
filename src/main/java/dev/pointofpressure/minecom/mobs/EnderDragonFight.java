package dev.pointofpressure.minecom.mobs;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ExperienceOrb;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.tag.Tag;
import net.minestom.server.timer.TaskSchedule;

/**
 * A bounded ender-dragon fight: on first entry the dragon circles the central
 * island and is healed by the end crystals atop the obsidian spikes — so players
 * must destroy the crystals before its health will fall. When it dies the exit
 * portal (bedrock frame + end portal + dragon egg) forms at the centre and drops
 * the reward XP. The full waypoint flight, breath, and charge attacks are refinements.
 */
public final class EnderDragonFight {

    private static final Tag<Boolean> STARTED = Tag.Transient("minecom:dragon_started");
    private static final Tag<Boolean> DEFEATED = Tag.Transient("minecom:dragon_defeated");
    private static final int MAX_HP = 200;

    private EnderDragonFight() {}

    /** Wire player melee against the dragon (arrows/breath are refinements). */
    public static void register(net.minestom.server.event.GlobalEventHandler events) {
        events.addListener(net.minestom.server.event.entity.EntityAttackEvent.class, event -> {
            if (event.getTarget().getEntityType() != EntityType.ENDER_DRAGON) return;
            if (!(event.getEntity() instanceof net.minestom.server.entity.Player)) return;
            if (event.getTarget() instanceof LivingEntity dragon) {
                dragon.damage(net.minestom.server.entity.damage.Damage.fromPlayer(
                        (net.minestom.server.entity.Player) event.getEntity(), 8f));
            }
        });
    }

    /** Spawn the dragon the first time the End is entered (idempotent). */
    public static void startIfNeeded(Instance end) {
        if (Boolean.TRUE.equals(end.getTag(STARTED)) || Boolean.TRUE.equals(end.getTag(DEFEATED))) return;
        end.setTag(STARTED, true);
        spawnDragon(end);
    }

    public static LivingEntity spawnDragon(Instance end) {
        LivingEntity dragon = new LivingEntity(EntityType.ENDER_DRAGON);
        dragon.getAttribute(Attribute.MAX_HEALTH).setBaseValue(MAX_HP);
        dragon.setHealth(MAX_HP);
        dragon.setInstance(end, new Pos(0, 80, 0));

        net.kyori.adventure.bossbar.BossBar bar = net.kyori.adventure.bossbar.BossBar.bossBar(
                net.kyori.adventure.text.Component.text("Ender Dragon"), 1.0f,
                net.kyori.adventure.bossbar.BossBar.Color.PURPLE,
                net.kyori.adventure.bossbar.BossBar.Overlay.NOTCHED_10);

        double[] angle = {0};
        int[] fireCd = {40};
        dragon.scheduler().buildTask(() -> {
            if (dragon.getInstance() == null) return;
            if (dragon.getHealth() <= 0 || dragon.isDead()) {
                for (var p : end.getPlayers()) p.hideBossBar(bar);
                onDragonDeath(end, new Pos(0, topSolidY(end, 0, 0), 0));
                dragon.remove();
                return;
            }
            // boss bar tracks the dragon's health for everyone in the End
            bar.progress(Math.max(0f, Math.min(1f, dragon.getHealth() / (float) MAX_HP)));
            for (var p : end.getPlayers()) p.showBossBar(bar);

            // crystal healing: while any end crystal survives, the dragon regenerates
            boolean crystalsAlive = end.getEntities().stream()
                    .anyMatch(e -> e.getEntityType() == EntityType.END_CRYSTAL && !e.isRemoved());
            if (crystalsAlive && dragon.getHealth() < MAX_HP) {
                dragon.setHealth(Math.min(MAX_HP, dragon.getHealth() + 1f));
            }

            // circular perimeter flight around the central island
            angle[0] += 0.05;
            double r = 40;
            double x = Math.cos(angle[0]) * r;
            double z = Math.sin(angle[0]) * r;
            double y = 78 + Math.sin(angle[0] * 2) * 6;
            Pos next = new Pos(x, y, z);
            Vec dir = next.sub(dragon.getPosition()).asVec();
            float yaw = dir.lengthSquared() > 1e-4 ? (float) Math.toDegrees(Math.atan2(-dir.x(), dir.z())) : dragon.getPosition().yaw();
            dragon.teleport(next.withView(yaw, 0));

            // breath attack: periodically lob a dragon fireball at the nearest player
            if (--fireCd[0] <= 0) {
                fireCd[0] = 50;
                var target = end.getPlayers().stream()
                        .min(java.util.Comparator.comparingDouble(pl -> pl.getPosition().distanceSquared(dragon.getPosition())))
                        .orElse(null);
                if (target != null && target.getPosition().distanceSquared(dragon.getPosition()) < 80 * 80) {
                    var fb = new net.minestom.server.entity.EntityProjectile(dragon, EntityType.DRAGON_FIREBALL);
                    Pos from = dragon.getPosition().add(0, 2, 0);
                    fb.setInstance(end, from);
                    Vec fd = target.getPosition().add(0, 1, 0).sub(from).asVec().normalize();
                    fb.setVelocity(fd.mul(16));
                }
            }
        }).repeat(TaskSchedule.tick(2)).schedule();

        return dragon;
    }

    /** Call when the dragon's health reaches 0: form the exit portal + gateway, and drop XP. */
    public static void onDragonDeath(Instance end, Pos center) {
        if (Boolean.TRUE.equals(end.getTag(DEFEATED))) return;
        end.setTag(DEFEATED, true);
        buildExitPortal(end, center.blockX(), center.blockZ());
        // reward XP (vanilla drops ~12000 the first time)
        for (int i = 0; i < 20; i++) {
            ExperienceOrb orb = new ExperienceOrb((short) 500);
            orb.setInstance(end, center.add(0, 1, 0));
        }

        // the free route to the outer islands: a gateway on the main island's ring
        var gen = dev.pointofpressure.minecom.Bootstrap.endGen();
        if (gen != null) {
            int[] ring = dev.pointofpressure.minecom.worldgen.vanilla.EndGateways.ringPosition(dev.pointofpressure.minecom.Bootstrap.worldSeed());
            end.loadChunk(ring[0] >> 4, ring[2] >> 4).thenRun(() ->
                    dev.pointofpressure.minecom.worldgen.vanilla.EndGateways.spawn(end, dev.pointofpressure.minecom.Bootstrap.worldSeed(), gen));
        }
    }

    /** Bedrock exit-portal frame with the end portal blocks + dragon egg on the pillar. */
    private static void buildExitPortal(Instance end, int cx, int cz) {
        int baseY = topSolidY(end, cx, cz);
        // 5x5 bedrock ring with an end_portal core, a small bedrock pillar, and the dragon egg
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                end.setBlock(cx + dx, baseY, cz + dz, ring ? Block.BEDROCK : Block.END_PORTAL);
            }
        }
        for (int dy = 1; dy <= 4; dy++) end.setBlock(cx, baseY + dy, cz, Block.BEDROCK);
        end.setBlock(cx, baseY + 5, cz, Block.DRAGON_EGG);
    }

    private static int topSolidY(Instance end, int x, int z) {
        for (int y = 100; y > 0; y--) {
            if (!end.getBlock(x, y, z).isAir()) return y + 1;
        }
        return 65;
    }
}
