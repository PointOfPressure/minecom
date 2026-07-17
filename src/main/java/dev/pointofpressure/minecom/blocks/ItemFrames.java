package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.metadata.other.HangingMeta;
import net.minestom.server.entity.metadata.other.ItemFrameMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.utils.Direction;
import net.minestom.server.utils.Rotation;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * ItemFrame.interact/hurtServer (decompile-verified): right-clicking an empty frame with a held
 * item mounts it (consuming 1) and plays the add-item sound; right-clicking a frame that already
 * holds an item just cycles its display through the 8 real 45-degree rotation steps, regardless
 * of what's in the player's hand — an empty-handed click on a filled frame still rotates it.
 * Attacking a frame that holds an item knocks the item back out into the world WITHOUT
 * destroying the frame; only attacking an already-empty frame breaks it, dropping the item_frame
 * item itself. Glow item frames are the identical mechanic under a second entity type (their
 * only real difference is a client-side glow outline, not gameplay).
 */
public final class ItemFrames {
    private ItemFrames() {}

    private static final Map<Material, EntityType> ITEM_TYPES = Map.of(
            Material.ITEM_FRAME, EntityType.ITEM_FRAME,
            Material.GLOW_ITEM_FRAME, EntityType.GLOW_ITEM_FRAME);
    private static final Map<EntityType, Material> FRAME_ITEMS = Map.of(
            EntityType.ITEM_FRAME, Material.ITEM_FRAME,
            EntityType.GLOW_ITEM_FRAME, Material.GLOW_ITEM_FRAME);
    private static final Set<EntityType> FRAME_TYPES = Set.copyOf(ITEM_TYPES.values());

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, event -> {
            EntityType type = ITEM_TYPES.get(event.getItemStack().material());
            if (type == null) return;
            Instance instance = event.getInstance();
            Point support = event.getPosition();
            if (!instance.getBlock(support).isSolid()) return;
            Direction dir = event.getBlockFace().toDirection();
            Point framePos = support.add(dir.normalX(), dir.normalY(), dir.normalZ());
            if (!instance.getBlock(framePos).isAir()) return;

            spawnAt(instance, new Pos(framePos.blockX() + 0.5, framePos.blockY() + 0.5, framePos.blockZ() + 0.5),
                    dir, type);

            ItemStack held = event.getItemStack();
            event.getPlayer().setItemInMainHand(held.consume(1));
        });

        events.addListener(PlayerEntityInteractEvent.class, event -> {
            if (!FRAME_TYPES.contains(event.getTarget().getEntityType())) return;
            event.getTarget().editEntityMeta(ItemFrameMeta.class, meta -> {
                if (meta.getItem().isAir()) {
                    ItemStack held = event.getPlayer().getItemInMainHand();
                    if (held.isAir()) return;
                    meta.setItem(held.withAmount(1));
                    event.getPlayer().setItemInMainHand(held.consume(1));
                } else {
                    Rotation[] steps = Rotation.values();
                    meta.setRotation(steps[(meta.getRotation().ordinal() + 1) % steps.length]);
                }
            });
        });

        // attacking a filled frame ejects the item; attacking an empty one breaks the frame
        events.addListener(EntityAttackEvent.class, event -> {
            EntityType type = event.getTarget().getEntityType();
            if (!FRAME_TYPES.contains(type)) return;
            Instance instance = event.getTarget().getInstance();
            Pos at = event.getTarget().getPosition();
            ItemFrameMeta meta = (ItemFrameMeta) event.getTarget().getEntityMeta();
            ItemStack framed = meta.getItem();
            if (!framed.isAir()) {
                meta.setItem(ItemStack.AIR);
                drop(instance, at, framed);
            } else {
                event.getTarget().remove();
                drop(instance, at, ItemStack.of(FRAME_ITEMS.get(type)));
            }
        });
    }

    private static void drop(Instance instance, Pos at, ItemStack stack) {
        if (instance == null) return;
        ItemEntity item = new ItemEntity(stack);
        item.setInstance(instance, at);
        item.setPickupDelay(Duration.ofMillis(500));
    }

    /** Whether {@code type} is one of the two real frame entity types (used by RegionStore's decoration sweep). */
    public static boolean isFrame(EntityType type) {
        return FRAME_TYPES.contains(type);
    }

    /** Spawn a bare (unfilled) frame of {@code type} at its exact hanging center — no click/consume side effects. */
    public static Entity spawnAt(Instance instance, Pos center, Direction dir, EntityType type) {
        Entity frame = new Entity(type);
        frame.setNoGravity(true);
        frame.editEntityMeta(HangingMeta.class, meta -> meta.setDirection(dir));
        frame.setInstance(instance, center);
        return frame;
    }
}
