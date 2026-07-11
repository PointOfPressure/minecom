package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.TaskSchedule;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TargetBlock: real accuracy-based direct redstone signal. A projectile's hit accuracy
 * (distance from the struck face's center, on that face's own 2D plane) maps to strength
 * 1-15 via {@code max(1, ceil(15 * clamp((0.5-distance)/0.5, 0, 1)))} — a dead-center hit is
 * 15, a grazing edge hit is 1. Real vanilla ignores hits while a previous hit's reset timer
 * is still pending (TargetBlock.updateRedstoneOutput's hasScheduledTick guard) rather than
 * refreshing/extending it; ported the same way with a tracked pending-reset set.
 */
public final class TargetBlock {
    private TargetBlock() {}

    private static final Set<String> PENDING_RESET = ConcurrentHashMap.newKeySet();

    public static void register(GlobalEventHandler events) {
        events.addListener(ProjectileCollideWithBlockEvent.class, TargetBlock::onHit);
    }

    private static void onHit(ProjectileCollideWithBlockEvent e) {
        if (!e.getBlock().key().value().equals("target")) return;
        Entity projectile = e.getEntity();
        Instance instance = projectile.getInstance();
        if (instance == null) return;

        Pos hit = e.getCollisionPosition();
        BlockVec blockPos = resolveBlockPos(hit, projectile.getVelocity());
        String posKey = Containers.posKey(blockPos);
        if (PENDING_RESET.contains(posKey)) return;

        int strength = accuracyStrength(hit, blockPos);
        Block block = instance.getBlock(blockPos);
        if (!block.key().value().equals("target")) return;

        instance.setBlock(blockPos, block.withProperty("power", String.valueOf(strength)));
        dev.pointofpressure.minecom.redstone.Redstone.neighborsChanged(blockPos);

        boolean isArrow = projectile.getEntityType() == EntityType.ARROW
                || projectile.getEntityType() == EntityType.SPECTRAL_ARROW;
        int durationTicks = isArrow ? 20 : 8;
        PENDING_RESET.add(posKey);
        instance.scheduler().buildTask(() -> {
            PENDING_RESET.remove(posKey);
            Block now = instance.getBlock(blockPos);
            if (now.key().value().equals("target") && !"0".equals(now.getProperty("power"))) {
                instance.setBlock(blockPos, now.withProperty("power", "0"));
                dev.pointofpressure.minecom.redstone.Redstone.neighborsChanged(blockPos);
            }
        }).delay(TaskSchedule.tick(durationTicks)).schedule();
    }

    /**
     * Minestom's collision event gives only the exact (possibly boundary-exact) impact point,
     * not the struck block's own coordinates — nudge a hair further along the projectile's
     * travel direction before flooring so the sample lands just inside the target block
     * instead of, on some approach angles, its neighbor.
     */
    private static BlockVec resolveBlockPos(Pos hit, Vec velocity) {
        Vec dir = velocity.length() > 1e-6 ? velocity.normalize() : new Vec(0, -1, 0);
        double eps = 1e-4;
        return new BlockVec(
                (int) Math.floor(hit.x() + dir.x() * eps),
                (int) Math.floor(hit.y() + dir.y() * eps),
                (int) Math.floor(hit.z() + dir.z() * eps));
    }

    /** TargetBlock.getRedstoneStrength: infers the struck face from which axis sits nearest a block boundary. */
    private static int accuracyStrength(Pos hit, BlockVec blockPos) {
        double fx = hit.x() - blockPos.blockX();
        double fy = hit.y() - blockPos.blockY();
        double fz = hit.z() - blockPos.blockZ();
        double boundaryX = Math.min(fx, 1 - fx);
        double boundaryY = Math.min(fy, 1 - fy);
        double boundaryZ = Math.min(fz, 1 - fz);

        double distX = Math.abs(fx - 0.5);
        double distY = Math.abs(fy - 0.5);
        double distZ = Math.abs(fz - 0.5);
        double distance;
        if (boundaryY <= boundaryX && boundaryY <= boundaryZ) {
            distance = Math.max(distX, distZ); // hit the top/bottom face
        } else if (boundaryZ <= boundaryX && boundaryZ <= boundaryY) {
            distance = Math.max(distX, distY); // hit the north/south face
        } else {
            distance = Math.max(distY, distZ); // hit the east/west face
        }

        double clamped = Math.max(0.0, Math.min(1.0, (0.5 - distance) / 0.5));
        return Math.max(1, (int) Math.ceil(15.0 * clamped));
    }
}
