package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.metadata.other.ArmorStandMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import net.minestom.server.utils.Direction;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Armor stand entity, ported from decompiled ArmorStand / ArmorStandItem (26.2).
 * ArmorStandItem.useOn places one at the bottom-centre of the block above the
 * clicked face (never off a DOWN face), snapping yaw to the nearest 45 degrees
 * from the placer's facing and consuming one item. ArmorStand.interact drives
 * equipment: an empty-handed click takes the item out of whichever body slot the
 * cursor height maps to (getClickedSlot), a click holding armour/a weapon swaps it
 * into that item's natural slot (hand slots need showArms), respecting the
 * disabled-slot mask. Attacking breaks it the vanilla incremental way — a first
 * hit only shows breaking particles, a second within 5 ticks (brokenByPlayer)
 * pops the armor_stand item plus every equipped stack and removes the entity.
 * The Invisible / Small / NoBasePlate / Marker / ShowArms flags and the six pose
 * Rotations (readAdditionalSaveData) are parsed off the placed item's NBT and
 * applied to the entity meta; a Marker stand ignores all interaction.
 *
 * Simplifications (AUDIT): no per-stand health bar / fire / explosion breaking
 * branches (only the player two-hit break path), no disabled-slots persistence
 * (always 0), poses carried as meta only (no client physics), no gravity toggle
 * beyond Minestom's own — the decoration + equipment + flag behaviour is modelled.
 */
public final class ArmorStands {
    private ArmorStands() {}

    /** Game-time (worldAge) of the last attack hit per stand, for the two-hit break window. */
    private static final java.util.Map<Entity, Long> LAST_HIT = new ConcurrentHashMap<>();
    /** Two hits within this many game ticks break the stand — ArmorStand.hurtServer / lastHit. */
    private static final long BREAK_WINDOW_TICKS = 5L;

    // NBT keys mirror ArmorStand.readAdditionalSaveData / addAdditionalSaveData.
    private static final Tag<Boolean> TAG_INVISIBLE = Tag.Boolean("Invisible");
    private static final Tag<Boolean> TAG_SMALL = Tag.Boolean("Small");
    private static final Tag<Boolean> TAG_NO_BASE_PLATE = Tag.Boolean("NoBasePlate");
    private static final Tag<Boolean> TAG_MARKER = Tag.Boolean("Marker");
    private static final Tag<Boolean> TAG_SHOW_ARMS = Tag.Boolean("ShowArms");

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, event -> {
            if (event.getItemStack().material() != Material.ARMOR_STAND) return;
            Direction face = event.getBlockFace().toDirection();
            if (face == Direction.DOWN) return; // ArmorStandItem.useOn fails off a DOWN face
            Instance instance = event.getInstance();
            Point base = event.getPosition().add(face.normalX(), face.normalY(), face.normalZ());
            if (!instance.getBlock(base).isAir()) return;

            place(instance, base, event.getPlayer().getPosition().yaw(), event.getItemStack());
            event.getPlayer().setItemInMainHand(event.getItemStack().consume(1));
        });

        events.addListener(PlayerEntityInteractEvent.class, event -> {
            if (event.getTarget().getEntityType() != EntityType.ARMOR_STAND) return;
            LivingEntity stand = (LivingEntity) event.getTarget();
            ArmorStandMeta meta = (ArmorStandMeta) stand.getEntityMeta();
            if (meta.isMarker()) return; // ArmorStand.interact bails for markers
            ItemStack held = event.getPlayer().getItemInMainHand();
            double clickY = event.getInteractPosition().y() / (meta.isSmall() ? 0.5 : 1.0);

            if (held.isAir()) {
                EquipmentSlot clicked = clickedSlot(stand, meta, clickY);
                ItemStack taken = stand.getEquipment(clicked);
                if (!taken.isAir()) {
                    stand.setEquipment(clicked, ItemStack.AIR);
                    event.getPlayer().getInventory().addItemStack(taken);
                }
            } else {
                EquipmentSlot target = slotForItem(held.material());
                if (isDisabled(meta, target)) return;
                ItemStack existing = stand.getEquipment(target);
                stand.setEquipment(target, held.withAmount(1));
                if (!existing.isAir()) {
                    event.getPlayer().getInventory().addItemStack(existing);
                }
                event.getPlayer().setItemInMainHand(held.consume(1));
            }
        });

        events.addListener(EntityAttackEvent.class, event -> {
            if (event.getTarget().getEntityType() != EntityType.ARMOR_STAND) return;
            LivingEntity stand = (LivingEntity) event.getTarget();
            ArmorStandMeta meta = (ArmorStandMeta) stand.getEntityMeta();
            if (meta.isMarker() || stand.isInvisible()) return; // hurtServer no-ops on these
            long now = stand.getInstance() == null ? 0L : stand.getInstance().getWorldAge();
            Long last = LAST_HIT.get(stand);
            if (last != null && now - last <= BREAK_WINDOW_TICKS) {
                breakStand(stand);
            } else {
                LAST_HIT.put(stand, now);
            }
        });
    }

    /**
     * Spawn an armor stand at the bottom-centre of {@code base}, snapping yaw to
     * the nearest 45 degrees (ArmorStandItem.useOn) and applying any flags/poses
     * carried on the placement item. {@code item} may be {@code null} for a plain
     * default stand.
     */
    public static LivingEntity place(Instance instance, Point base, float placerYaw, ItemStack item) {
        // Mth.floor((wrapDegrees(rot - 180) + 22.5) / 45) * 45 in ArmorStandItem.useOn.
        float yaw = (float) Math.floor((wrapDegrees(placerYaw - 180.0f) + 22.5f) / 45.0f) * 45.0f;
        LivingEntity stand = spawnAt(instance, new Pos(base.blockX() + 0.5, base.blockY(), base.blockZ() + 0.5, yaw, 0));
        applyFlags(stand, item);
        return stand;
    }

    /** Spawn a bare, default-flags armor stand at its exact position — no click/consume side effects. */
    public static LivingEntity spawnAt(Instance instance, Pos pos) {
        LivingEntity stand = new LivingEntity(EntityType.ARMOR_STAND);
        stand.setInstance(instance, pos);
        return stand;
    }

    /** Apply the Invisible/Small/NoBasePlate/Marker/ShowArms flags off an item's NBT. */
    public static void applyFlags(LivingEntity stand, ItemStack item) {
        applyFlags(stand,
                item != null && Boolean.TRUE.equals(item.getTag(TAG_INVISIBLE)),
                item != null && Boolean.TRUE.equals(item.getTag(TAG_SMALL)),
                item != null && Boolean.TRUE.equals(item.getTag(TAG_NO_BASE_PLATE)),
                item != null && Boolean.TRUE.equals(item.getTag(TAG_MARKER)),
                item != null && Boolean.TRUE.equals(item.getTag(TAG_SHOW_ARMS)));
    }

    /** Apply the same five flags from explicit values (persistence restore path — no source item). */
    public static void applyFlags(LivingEntity stand, boolean invisible, boolean small,
            boolean noBasePlate, boolean marker, boolean showArms) {
        ArmorStandMeta meta = (ArmorStandMeta) stand.getEntityMeta();
        stand.setInvisible(invisible);
        meta.setSmall(small);
        meta.setHasNoBasePlate(noBasePlate);
        meta.setMarker(marker);
        meta.setHasArms(showArms);
    }

    /** Set the six body-part pose rotations (degrees). ArmorStand.setXxxPose. */
    public static void applyPose(LivingEntity stand, Vec head, Vec body,
            Vec leftArm, Vec rightArm, Vec leftLeg, Vec rightLeg) {
        ArmorStandMeta meta = (ArmorStandMeta) stand.getEntityMeta();
        if (head != null) meta.setHeadRotation(head);
        if (body != null) meta.setBodyRotation(body);
        if (leftArm != null) meta.setLeftArmRotation(leftArm);
        if (rightArm != null) meta.setRightArmRotation(rightArm);
        if (leftLeg != null) meta.setLeftLegRotation(leftLeg);
        if (rightLeg != null) meta.setRightLegRotation(rightLeg);
    }

    /** Break a stand: pop the armor_stand item plus every equipped stack, then remove. */
    public static void breakStand(LivingEntity stand) {
        Instance instance = stand.getInstance();
        Pos at = stand.getPosition();
        drop(instance, at, ItemStack.of(Material.ARMOR_STAND));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.BODY || slot == EquipmentSlot.SADDLE) continue;
            ItemStack worn = stand.getEquipment(slot);
            if (!worn.isAir()) {
                stand.setEquipment(slot, ItemStack.AIR);
                drop(instance, at.add(0, 1, 0), worn);
            }
        }
        LAST_HIT.remove(stand);
        stand.remove();
    }

    /** ArmorStand.getClickedSlot: cursor height picks the filled slot to take from. */
    private static EquipmentSlot clickedSlot(LivingEntity stand, ArmorStandMeta meta, double y) {
        boolean small = meta.isSmall();
        if (y >= 0.1 && y < 0.1 + (small ? 0.8 : 0.45) && !stand.getEquipment(EquipmentSlot.BOOTS).isAir()) {
            return EquipmentSlot.BOOTS;
        } else if (y >= 0.9 + (small ? 0.3 : 0.0) && y < 0.9 + (small ? 1.0 : 0.7)
                && !stand.getEquipment(EquipmentSlot.CHESTPLATE).isAir()) {
            return EquipmentSlot.CHESTPLATE;
        } else if (y >= 0.4 && y < 0.4 + (small ? 1.0 : 0.8) && !stand.getEquipment(EquipmentSlot.LEGGINGS).isAir()) {
            return EquipmentSlot.LEGGINGS;
        } else if (y >= 1.6 && !stand.getEquipment(EquipmentSlot.HELMET).isAir()) {
            return EquipmentSlot.HELMET;
        } else if (stand.getEquipment(EquipmentSlot.MAIN_HAND).isAir()
                && !stand.getEquipment(EquipmentSlot.OFF_HAND).isAir()) {
            return EquipmentSlot.OFF_HAND;
        }
        return EquipmentSlot.MAIN_HAND;
    }

    /** ArmorStand.isDisabled: hand slots need arms shown; no disabled mask otherwise. */
    private static boolean isDisabled(ArmorStandMeta meta, EquipmentSlot slot) {
        return (slot == EquipmentSlot.MAIN_HAND || slot == EquipmentSlot.OFF_HAND) && !meta.isHasArms();
    }

    /** Natural equipment slot for a held item — armour to its slot, else the main hand. */
    private static EquipmentSlot slotForItem(Material material) {
        String id = material.key().value();
        if (id.endsWith("_helmet") || id.equals("carved_pumpkin")
                || id.endsWith("_skull") || id.endsWith("_head")) return EquipmentSlot.HELMET;
        if (id.endsWith("_chestplate") || id.equals("elytra")) return EquipmentSlot.CHESTPLATE;
        if (id.endsWith("_leggings")) return EquipmentSlot.LEGGINGS;
        if (id.endsWith("_boots")) return EquipmentSlot.BOOTS;
        return EquipmentSlot.MAIN_HAND;
    }

    private static void drop(Instance instance, Pos at, ItemStack stack) {
        if (instance == null || stack.isAir()) return;
        ItemEntity item = new ItemEntity(stack);
        item.setInstance(instance, at);
        item.setPickupDelay(Duration.ofMillis(500));
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }
}
