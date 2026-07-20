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
 *
 * <p>Respawn ritual (EnderDragonFight.tryRespawn/respawnDragon/setDragonKilled, decompile-
 * verified against 26.2): once the dragon is dead, placing four end crystals on the four
 * cardinal edges of the exit portal restarts the fight — the ritual crystals are consumed, the
 * pillar crystals regenerate (so the fresh dragon is healed again), and a new dragon spawns.
 * Only the FIRST kill drops the dragon egg and 12000 XP; every repeat kill gives 500 XP and no
 * egg. The scripted circling flight is kept for all spawns (full perch/charge phases are out of
 * scope). End fight state is session-scoped (the End is not persisted through RegionStore).
 */
public final class EnderDragonFight {

    private static final Tag<Boolean> STARTED = Tag.Transient("minecom:dragon_started");
    private static final Tag<Boolean> DEFEATED = Tag.Transient("minecom:dragon_defeated");
    // ever killed in this session: gates the one-time egg drop + 12000-vs-500 XP (hasPreviouslyKilledDragon)
    private static final Tag<Boolean> PREVIOUSLY_KILLED = Tag.Transient("minecom:dragon_previously_killed");
    // Y of the exit portal's end_portal/bedrock layer, so the respawn ritual knows where the four edge cells are
    private static final Tag<Integer> PORTAL_Y = Tag.Transient("minecom:dragon_portal_y");
    private static final int MAX_HP = 200;
    // EnderDragon.tickDeath awards 12000 XP the first kill, 500 every repeat (max single orb = 2477)
    private static final int FIRST_KILL_XP = 12000;
    private static final int REPEAT_KILL_XP = 500;
    private static final short MAX_ORB = 2477;

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

    /** Test hook: wipe fight state (started/defeated/previously-killed/portalY) and clear dragons + crystals. */
    public static void resetForTest(Instance end) {
        end.removeTag(STARTED);
        end.removeTag(DEFEATED);
        end.removeTag(PREVIOUSLY_KILLED);
        end.removeTag(PORTAL_Y);
        for (Entity e : end.getEntities()) {
            if (e.getEntityType() == EntityType.ENDER_DRAGON || e.getEntityType() == EntityType.END_CRYSTAL
                    || e.getEntityType() == EntityType.EXPERIENCE_ORB) {
                e.remove();
            }
        }
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
        boolean firstKill = !Boolean.TRUE.equals(end.getTag(PREVIOUSLY_KILLED));
        end.setTag(PREVIOUSLY_KILLED, true);

        // spawnExitPortal(true) always; the dragon egg only on the very first kill (setDragonKilled)
        buildExitPortal(end, center.blockX(), center.blockZ(), firstKill);
        dropXp(end, center.add(0, 1, 0), firstKill ? FIRST_KILL_XP : REPEAT_KILL_XP);

        // the free route to the outer islands: a gateway on the main island's ring
        var gen = dev.pointofpressure.minecom.Bootstrap.endGen();
        if (gen != null) {
            int[] ring = dev.pointofpressure.minecom.worldgen.vanilla.EndGateways.ringPosition(dev.pointofpressure.minecom.Bootstrap.worldSeed());
            end.loadChunk(ring[0] >> 4, ring[2] >> 4).thenRun(() ->
                    dev.pointofpressure.minecom.worldgen.vanilla.EndGateways.spawn(end, dev.pointofpressure.minecom.Bootstrap.worldSeed(), gen));
        }
    }

    /** EnderDragon.tickDeath: award {@code total} XP split into vanilla-sized orbs (max 2477 each). */
    private static void dropXp(Instance end, Pos at, int total) {
        int remaining = total;
        while (remaining > 0) {
            short amt = (short) Math.min(remaining, MAX_ORB);
            new ExperienceOrb(amt).setInstance(end, at);
            remaining -= amt;
        }
    }

    /** Bedrock exit-portal frame with the end portal blocks + (on the first kill) a dragon egg on the pillar. */
    private static void buildExitPortal(Instance end, int cx, int cz, boolean dropEgg) {
        int baseY = topSolidY(end, cx, cz);
        end.setTag(PORTAL_Y, baseY);
        // 5x5 bedrock ring with an end_portal core, a small bedrock pillar, and the dragon egg
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                end.setBlock(cx + dx, baseY, cz + dz, ring ? Block.BEDROCK : Block.END_PORTAL);
            }
        }
        for (int dy = 1; dy <= 4; dy++) end.setBlock(cx, baseY + dy, cz, Block.BEDROCK);
        if (dropEgg) end.setBlock(cx, baseY + 5, cz, Block.DRAGON_EGG);
    }

    // The four cardinal edge cells of the 5x5 exit portal (offset from its centre) where the
    // ritual crystals sit — vanilla checks {@code center.above(1).relative(dir, 3)} on its larger
    // podium; this project's 5x5 podium puts the crystals on the ring edges (distance 2).
    private static final int[][] RITUAL_CELLS = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};

    /**
     * EndCrystalItem.useOn's trigger, ported from EnderDragonFight.tryRespawn: once the dragon is
     * dead, if an end crystal sits on each of the four exit-portal edge cells, consume them and
     * respawn the dragon. No-op unless {@code end} is an End with a killed dragon awaiting respawn.
     */
    public static void tryRespawn(Instance end) {
        if (!Boolean.TRUE.equals(end.getTag(DEFEATED))) return;
        Integer portalY = end.getTag(PORTAL_Y);
        if (portalY == null) return;
        if (end.getEntities().stream().anyMatch(e ->
                e.getEntityType() == EntityType.ENDER_DRAGON && !e.isRemoved())) return;

        int crystalY = portalY + 1;
        Entity[] ritual = new Entity[RITUAL_CELLS.length];
        for (int i = 0; i < RITUAL_CELLS.length; i++) {
            Entity crystal = crystalAt(end, RITUAL_CELLS[i][0], crystalY, RITUAL_CELLS[i][1]);
            if (crystal == null) return; // not all four placed yet
            ritual[i] = crystal;
        }
        respawnDragon(end, ritual);
    }

    /** EnderDragonFight.respawnDragon: consume the ritual crystals, regenerate the pillar crystals, spawn a fresh dragon. */
    private static void respawnDragon(Instance end, Entity[] ritualCrystals) {
        for (Entity c : ritualCrystals) c.remove();
        respawnPillarCrystals(end);
        end.setTag(DEFEATED, false);
        end.setTag(STARTED, true);
        spawnDragon(end);
    }

    /** Put an end crystal back atop every obsidian spike that has lost one (EndSpikeFeature crystal placement). */
    public static void respawnPillarCrystals(Instance end) {
        long seed = dev.pointofpressure.minecom.Bootstrap.worldSeed();
        for (var spike : dev.pointofpressure.minecom.worldgen.vanilla.VEndSpikes.spikes(seed)) {
            Pos top = new Pos(spike.x() + 0.5, spike.height() + 1, spike.z() + 0.5);
            boolean present = end.getEntities().stream().anyMatch(e ->
                    e.getEntityType() == EntityType.END_CRYSTAL && !e.isRemoved()
                    && e.getPosition().distanceSquared(top) < 4.0);
            if (!present) {
                Entity crystal = new Entity(EntityType.END_CRYSTAL);
                crystal.setNoGravity(true);
                crystal.setInstance(end, top);
            }
        }
    }

    /** The live end crystal whose block position is exactly (x,y,z), or null. */
    private static Entity crystalAt(Instance end, int x, int y, int z) {
        return end.getEntities().stream()
                .filter(e -> e.getEntityType() == EntityType.END_CRYSTAL && !e.isRemoved())
                .filter(e -> e.getPosition().blockX() == x && e.getPosition().blockY() == y && e.getPosition().blockZ() == z)
                .findFirst().orElse(null);
    }

    private static int topSolidY(Instance end, int x, int z) {
        for (int y = 100; y > 0; y--) {
            if (!end.getBlock(x, y, z).isAir()) return y + 1;
        }
        return 65;
    }
}
