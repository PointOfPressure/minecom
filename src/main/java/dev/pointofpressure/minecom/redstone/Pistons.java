package dev.pointofpressure.minecom.redstone;

import dev.pointofpressure.minecom.blocks.BlockRules;
import dev.pointofpressure.minecom.data.LootTables;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Pistons with vanilla rules: 12-block push limit, immovable blocks (bedrock,
 * obsidian, extended pistons, block entities), destructible blocks pop off,
 * sticky retraction pulls one block — and quasi-connectivity: the piston also
 * accepts power at the position above itself, re-checked only on block updates.
 */
final class Pistons {
    private Pistons() {}

    static void evaluate(Instance instance, Point pos, Block piston) {
        boolean extended = "true".equals(piston.getProperty("extended"));
        boolean powered = Redstone.activated(pos) || Redstone.activated(pos.add(0, 1, 0)); // QC
        if (powered && !extended) extend(instance, pos, piston);
        else if (!powered && extended) retract(instance, pos, piston);
    }

    private static boolean immovable(Block block) {
        if (block.registry().isBlockEntity()) return true;
        String key = block.key().value();
        if (key.equals("obsidian") || key.equals("bedrock") || key.equals("piston_head")) return true;
        if ((key.equals("piston") || key.equals("sticky_piston"))
                && "true".equals(block.getProperty("extended"))) return true;
        return block.registry().hardness() < 0;
    }

    /** Blocks with no collision pop off instead of being pushed. */
    private static boolean poppable(Block block) {
        return !block.isSolid() && !block.isLiquid() && !block.isAir();
    }

    private static void extend(Instance instance, Point pos, Block piston) {
        Vec dir = Redstone.facingVec(piston.getProperty("facing"));
        List<Point> line = new ArrayList<>();
        Point at = pos.add(dir);
        while (line.size() <= 12) {
            Block block = instance.getBlock(at);
            if (block.isAir() || block.isLiquid()) break;
            if (poppable(block)) {
                for (ItemStack drop : LootTables.blockDrops(block, ItemStack.AIR)) {
                    BlockRules.dropAt(instance, at, drop);
                }
                instance.setBlock(at, Block.AIR);
                break;
            }
            if (immovable(block)) return; // blocked, no extension
            line.add(at);
            at = at.add(dir);
        }
        if (line.size() > 12) return;

        // shift from the far end backward
        for (int i = line.size() - 1; i >= 0; i--) {
            Point from = line.get(i);
            instance.setBlock(from.add(dir), instance.getBlock(from));
        }
        // push entities standing in the swept volume
        for (var entity : instance.getEntities()) {
            var ep = entity.getPosition();
            for (int i = 0; i <= line.size(); i++) {
                Point cell = pos.add(dir.mul(i + 1));
                if (ep.blockX() == cell.blockX() && ep.blockZ() == cell.blockZ()
                        && Math.abs(ep.y() - cell.blockY()) < 1.2) {
                    entity.teleport(ep.add(dir.x(), Math.max(0, dir.y()), dir.z()));
                    break;
                }
            }
        }

        Point headPos = pos.add(dir);
        boolean sticky = piston.key().value().equals("sticky_piston");
        instance.setBlock(headPos, Block.PISTON_HEAD
                .withProperty("facing", piston.getProperty("facing"))
                .withProperty("type", sticky ? "sticky" : "normal"));
        instance.setBlock(pos, piston.withProperty("extended", "true"));

        Redstone.neighborsChanged(pos);
        Redstone.neighborsChanged(headPos);
        for (Point moved : line) {
            Redstone.neighborsChanged(moved);
            Redstone.neighborsChanged(moved.add(dir));
            dev.pointofpressure.minecom.blocks.Fluids.notifyAround(moved);
        }
        BlockRules.scheduleFallCheck(instance, pos.add(0, 1, 0));
    }

    private static void retract(Instance instance, Point pos, Block piston) {
        Vec dir = Redstone.facingVec(piston.getProperty("facing"));
        Point headPos = pos.add(dir);
        if (instance.getBlock(headPos).key().value().equals("piston_head")) {
            instance.setBlock(headPos, Block.AIR);
        }
        boolean sticky = piston.key().value().equals("sticky_piston");
        if (sticky) {
            Point pulled = headPos.add(dir);
            Block target = instance.getBlock(pulled);
            if (!target.isAir() && !target.isLiquid() && !immovable(target) && !poppable(target)) {
                instance.setBlock(headPos, target);
                instance.setBlock(pulled, Block.AIR);
                Redstone.neighborsChanged(pulled);
                dev.pointofpressure.minecom.blocks.Fluids.notifyAround(pulled);
            }
        }
        instance.setBlock(pos, piston.withProperty("extended", "false"));
        Redstone.neighborsChanged(pos);
        Redstone.neighborsChanged(headPos);
        dev.pointofpressure.minecom.blocks.Fluids.notifyAround(headPos);
    }
}
