package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.mobs.EnderDragonFight;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;

/**
 * End-crystal placement, ported from {@code EndCrystalItem.useOn}: right-clicking the top of an
 * obsidian or bedrock block with an end crystal — when the block directly above is empty and no
 * entity occupies the 1x2 space there — spawns an {@code end_crystal} entity centred on that block
 * (bottom plate hidden) and consumes one. In the End this then calls
 * {@link EnderDragonFight#tryRespawn(Instance)}, the trigger for the four-crystals-on-the-exit-
 * portal respawn ritual. Crystal explosion-on-attack (the bed/anvil-style detonation) is a
 * separate mechanic and out of scope here (see AUDIT.md).
 */
public final class EndCrystals {

    private EndCrystals() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, event -> {
            if (event.getItemStack().material() != Material.END_CRYSTAL) return;
            Instance instance = event.getInstance();
            Point clicked = event.getPosition();
            Block base = instance.getBlock(clicked);
            if (!base.compare(Block.OBSIDIAN) && !base.compare(Block.BEDROCK)) return;

            Point above = clicked.add(0, 1, 0);
            if (!instance.getBlock(above).isAir()) return;

            // EndCrystalItem: reject if any entity already occupies the crystal's 1x2 space
            Pos crystalPos = new Pos(above.blockX() + 0.5, above.blockY(), above.blockZ() + 0.5);
            boolean occupied = instance.getEntities().stream().anyMatch(e ->
                    !e.isRemoved()
                    && e.getPosition().blockX() == above.blockX()
                    && e.getPosition().blockZ() == above.blockZ()
                    && e.getPosition().blockY() >= above.blockY()
                    && e.getPosition().blockY() <= above.blockY() + 1);
            if (occupied) return;

            place(instance, crystalPos);
            event.getPlayer().setItemInMainHand(event.getItemStack().consume(1));

            // No-op unless this instance is an End with a killed dragon awaiting respawn.
            EnderDragonFight.tryRespawn(instance);
        });
    }

    /** Spawn a bare end-crystal entity at its exact position (no item consume / respawn side effects). */
    public static Entity place(Instance instance, Pos pos) {
        Entity crystal = new Entity(EntityType.END_CRYSTAL);
        crystal.setNoGravity(true);
        crystal.setInstance(instance, pos);
        return crystal;
    }
}
