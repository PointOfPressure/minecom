package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.data.VanillaData;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.metadata.other.AllayMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Allay entity, ported from decompiled {@code Allay.java}/{@code AllayAi.java} (26.2),
 * condensed from vanilla's Brain/Sensor/Behavior tree into one per-tick state machine at the
 * same priorities (dancing/duplication interaction outranks everything on interact; then
 * item-retrieval > deposit-at-target > idle wander) — the same "static holder + per-entity
 * State map" shape as {@code Bees.java}, since this project has no generic Brain framework.
 * <p>
 * Give/take (Allay.mobInteract): an empty-handed allay offered an item holds it (consuming 1)
 * and remembers the giver as its {@code LIKED_PLAYER}; a held-item allay approached
 * empty-handed gives the item back, drops any extra pickups it was carrying, and forgets its
 * liked player. wantsToPickUp/pickUpItem: with an item already in hand, the allay collects
 * matching (same material, same {@code PotionContents} — vanilla's
 * {@code allayConsidersItemEqual}) dropped items into its single-slot inventory, then carries
 * them to a deposit target — a liked note block (heard via {@link #hearNoteblock}, real
 * 1024-block/600-tick-cooldown gate) if one is currently "liked", else its liked player (real
 * 64-block/survival-or-creative gate), else it just holds onto them.
 * <p>
 * Dancing + duplication (the headline mechanic): idling near an actively playing jukebox
 * (real {@code GameEvent.JUKEBOX_PLAY} notification radius, 10 blocks) starts a dance; while
 * dancing, offering an amethyst shard ({@code ItemTags.DUPLICATES_ALLAYS}) with the real
 * 6000-tick (5-minute) cooldown ready spawns a second allay at the same position and consumes
 * the shard, both landing on cooldown together (real {@code duplicateAllay}).
 * <p>
 * Simplifications (AUDIT): flight is direct velocity-to-target steering, same idiom as
 * Bees/HappyGhast/ghast/phantom (no A*-ish AirRandomPos sampling — this project's ground
 * VPathfinder doesn't cover 3D flight); item-pickup cooldown is a short fixed 2-tick guard
 * against re-triggering on the same tick rather than vanilla's exact
 * {@code ITEM_PICKUP_COOLDOWN_TICKS} memory value (not found in the decompiled slice actually
 * ported — {@code GoToWantedItem}/{@code Mob} base-class internals — but the PURPOSE, not
 * re-grabbing what was just placed down, is preserved); deposit throws happen the instant the
 * allay is within range rather than vanilla's real 20-tick {@code GIVE_ITEM_TIMEOUT_DURATION}
 * travel window; no heart/amethyst-chime particles (this project has no particle idiom yet, per
 * the same note on Warden's sonic boom); no advancement trigger
 * (CriteriaTriggers.ALLAY_DROP_ITEM_ON_BLOCK — no advancement system in this project); State
 * (liked player/noteblock, held extras, dancing) is session-scoped like Bees' own hive/flower
 * memory — a restart resets an allay's relationships but not its identity/held mainhand item
 * (that rides the generic mob-equipment snapshot in RegionStore.collectMobs).
 */
public final class Allays {
    private Allays() {}

    private static final double FLY_SPEED = 0.14;
    private static final double RETRIEVE_SPEED = 0.20;
    private static final double DEPOSIT_SPEED = 0.24;
    private static final double PICKUP_REACH = 2.0;
    private static final double THROW_RANGE = 2.2;
    private static final int JUKEBOX_RADIUS = 10;
    private static final int JUKEBOX_SCAN_COOLDOWN = 20;
    private static final int VIBRATION_LISTENER_RANGE = 16;
    private static final int LIKED_NOTEBLOCK_MAX_DISTANCE = 1024;
    private static final int LIKED_NOTEBLOCK_COOLDOWN_TICKS = 600;
    private static final double LIKED_PLAYER_MAX_DISTANCE = 64.0;
    private static final long DUPLICATION_COOLDOWN_TICKS = 6000L;
    private static final int ITEM_PICKUP_COOLDOWN_TICKS = 2;

    private static final class State {
        UUID likedPlayer;
        Point likedNoteblockPos;
        int likedNoteblockCooldownTicks;
        int itemPickupCooldownTicks;
        long duplicationCooldownTicks;
        ItemStack extra = ItemStack.AIR; // matches the mainhand item, accumulated beyond it
        Point jukeboxPos;
        int jukeboxScanCooldown = ThreadLocalRandom.current().nextInt(JUKEBOX_SCAN_COOLDOWN);
        Point wanderGoal;
        long wanderUntil;
    }

    private static final Map<Integer, State> ALLAYS = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerEntityInteractEvent.class, e -> {
            if (e.getTarget().getEntityType() != EntityType.ALLAY) return;
            interact(e.getTarget(), e.getPlayer());
        });
    }

    /** Allay.createAttributes: 20 HP, 0.1 flying/movement speed, 2 attack damage (unused — no combat AI). */
    public static EntityCreature spawn(Instance instance, Pos pos) {
        EntityCreature mob = new EntityCreature(EntityType.ALLAY);
        mob.setNoGravity(true);
        State s = new State();
        ALLAYS.put(mob.getEntityId(), s);
        mob.setInstance(instance, pos);
        mob.scheduler().buildTask(() -> {
            if (mob.isDead() || mob.isRemoved() || mob.getInstance() == null) {
                ALLAYS.remove(mob.getEntityId());
                return;
            }
            tickOne(mob, s);
        }).repeat(TaskSchedule.tick(1)).schedule();
        return mob;
    }

    // ------------------------------------------------------------------ interaction

    /** Allay.mobInteract, in the real vanilla branch order (dance-duplicate first). */
    private static void interact(Entity target, Player player) {
        if (!(target instanceof EntityCreature mob)) return;
        State s = ALLAYS.get(mob.getEntityId());
        if (s == null) return;
        ItemStack held = player.getItemInMainHand();
        ItemStack itemInHand = mob.getEquipment(EquipmentSlot.MAIN_HAND);
        Instance instance = mob.getInstance();
        AllayMeta meta = mob.getEntityMeta() instanceof AllayMeta am ? am : null;

        if (meta != null && meta.isDancing() && !held.isAir()
                && VanillaData.itemHasTag(held.material(), "duplicates_allays") && canDuplicate(s)) {
            duplicate(instance, mob, s);
            playSoundAt(instance, Sound.sound(SoundEvent.BLOCK_AMETHYST_BLOCK_CHIME, Sound.Source.NEUTRAL, 2f, 1f),
                    mob.getPosition());
            player.setItemInMainHand(held.consume(1));
        } else if (itemInHand.isAir() && !held.isAir()) {
            mob.setEquipment(EquipmentSlot.MAIN_HAND, held.withAmount(1));
            player.setItemInMainHand(held.consume(1));
            playSoundAt(instance, Sound.sound(SoundEvent.ENTITY_ALLAY_ITEM_GIVEN, Sound.Source.NEUTRAL, 2f, 1f),
                    mob.getPosition());
            s.likedPlayer = player.getUuid();
        } else if (!itemInHand.isAir() && held.isAir()) {
            mob.setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.AIR);
            playSoundAt(instance, Sound.sound(SoundEvent.ENTITY_ALLAY_ITEM_TAKEN, Sound.Source.NEUTRAL, 2f, 1f),
                    mob.getPosition());
            if (!s.extra.isAir()) {
                throwItem(instance, mob.getPosition(), mob.getPosition(), s.extra);
                s.extra = ItemStack.AIR;
            }
            s.likedPlayer = null;
            dev.pointofpressure.minecom.blocks.Containers.giveOrDrop(player, itemInHand);
        }
    }

    private static boolean canDuplicate(State s) {
        return s.duplicationCooldownTicks <= 0;
    }

    /** Allay.duplicateAllay: a fresh allay at the same spot, both landing on the shared cooldown. */
    private static void duplicate(Instance instance, EntityCreature parent, State s) {
        EntityCreature twin = spawn(instance, parent.getPosition());
        s.duplicationCooldownTicks = DUPLICATION_COOLDOWN_TICKS;
        State twinState = ALLAYS.get(twin.getEntityId());
        if (twinState != null) twinState.duplicationCooldownTicks = DUPLICATION_COOLDOWN_TICKS;
    }

    // ------------------------------------------------------------------ noteblock hearing

    /**
     * Vibrations.emit's note_block_play hook (VibrationUser.getListenerRadius=16): the FIRST
     * allay to hear a given noteblock adopts it as liked with a 600-tick cooldown; hearing the
     * SAME already-liked noteblock again just refreshes that cooldown (AllayAi.hearNoteblock).
     */
    public static void hearNoteblock(Instance instance, Point pos) {
        for (Map.Entry<Integer, State> entry : ALLAYS.entrySet()) {
            State s = entry.getValue();
            Entity mob = instance.getEntityById(entry.getKey());
            if (mob == null || mob.isRemoved() || mob.getInstance() != instance) continue;
            if (mob.getPosition().distance(pos) > VIBRATION_LISTENER_RANGE) continue;
            if (s.likedNoteblockPos == null) {
                s.likedNoteblockPos = pos;
                s.likedNoteblockCooldownTicks = LIKED_NOTEBLOCK_COOLDOWN_TICKS;
            } else if (s.likedNoteblockPos.sameBlock(pos)) {
                s.likedNoteblockCooldownTicks = LIKED_NOTEBLOCK_COOLDOWN_TICKS;
            }
        }
    }

    public static boolean anyListening() {
        return !ALLAYS.isEmpty();
    }

    // ------------------------------------------------------------------ per-tick behavior

    private static void tickOne(EntityCreature mob, State s) {
        Instance instance = mob.getInstance();
        long now = instance.getWorldAge();
        // Allay.aiStep: heals 1 point every 10 ticks (not a full heal() — that's a different method).
        if (now % 10 == 0 && !mob.isDead()) {
            float max = (float) mob.getAttributeValue(net.minestom.server.entity.attribute.Attribute.MAX_HEALTH);
            mob.setHealth(Math.min(mob.getHealth() + 1f, max));
        }
        AllayMeta meta = mob.getEntityMeta() instanceof AllayMeta am ? am : null;

        if (s.duplicationCooldownTicks > 0) s.duplicationCooldownTicks--;
        if (meta != null) meta.setCanDuplicate(s.duplicationCooldownTicks <= 0);
        if (s.itemPickupCooldownTicks > 0) s.itemPickupCooldownTicks--;
        if (s.likedNoteblockCooldownTicks > 0) s.likedNoteblockCooldownTicks--;

        updateDancing(instance, mob, s, meta, now);

        ItemStack held = mob.getEquipment(EquipmentSlot.MAIN_HAND);
        if (!held.isAir()) {
            if (s.itemPickupCooldownTicks <= 0) tryPickUp(instance, mob, s, held);
            if (!s.extra.isAir()) {
                Point target = depositTarget(instance, mob, s);
                if (target != null) {
                    if (mob.getPosition().distance(target) <= THROW_RANGE) {
                        throwItem(instance, mob.getPosition(), target, s.extra);
                        s.extra = ItemStack.AIR;
                    } else {
                        flyToward(mob, target, DEPOSIT_SPEED);
                        return;
                    }
                }
            }
            // GoToWantedItem: chase a visible dropped match even without one banked yet.
            Point wanted = findWantedItem(instance, mob, held);
            if (wanted != null && mob.getPosition().distance(wanted) > 0.7) {
                flyToward(mob, wanted, RETRIEVE_SPEED);
                return;
            }
        }

        wander(mob, s, now);
    }

    private static void updateDancing(Instance instance, EntityCreature mob, State s, AllayMeta meta, long now) {
        if (meta == null) return;
        if (meta.isDancing()) {
            if (now % 20 == 0 && (s.jukeboxPos == null || mob.getPosition().distance(s.jukeboxPos) > JUKEBOX_RADIUS
                    || !"jukebox".equals(instance.getBlock(s.jukeboxPos).key().value())
                    || !dev.pointofpressure.minecom.blocks.Jukebox.isPlaying(s.jukeboxPos))) {
                meta.setDancing(false);
                s.jukeboxPos = null;
            }
        } else if (s.jukeboxScanCooldown-- <= 0) {
            s.jukeboxScanCooldown = JUKEBOX_SCAN_COOLDOWN;
            Point playing = findPlayingJukebox(instance, mob.getPosition());
            if (playing != null) {
                s.jukeboxPos = playing;
                meta.setDancing(true);
            }
        }
    }

    private static Point findPlayingJukebox(Instance instance, Point from) {
        int r = JUKEBOX_RADIUS;
        int bx = from.blockX(), by = from.blockY(), bz = from.blockZ();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -r; dy <= r; dy++) {
                    if (dx * dx + dy * dy + dz * dz > r * r) continue;
                    var p = new net.minestom.server.coordinate.BlockVec(bx + dx, by + dy, bz + dz);
                    if (!instance.isChunkLoaded(p.blockX() >> 4, p.blockZ() >> 4)) continue;
                    if (!"jukebox".equals(instance.getBlock(p).key().value())) continue;
                    if (dev.pointofpressure.minecom.blocks.Jukebox.isPlaying(p)) return p;
                }
        return null;
    }

    /** AllayAi.getItemDepositPosition: a still-liked noteblock beats the liked player. */
    private static Point depositTarget(Instance instance, EntityCreature mob, State s) {
        if (s.likedNoteblockPos != null) {
            boolean stillLiked = mob.getPosition().distance(s.likedNoteblockPos) <= LIKED_NOTEBLOCK_MAX_DISTANCE
                    && "note_block".equals(instance.getBlock(s.likedNoteblockPos).key().value())
                    && s.likedNoteblockCooldownTicks > 0;
            if (stillLiked) return s.likedNoteblockPos.add(0, 1, 0);
            s.likedNoteblockPos = null;
        }
        if (s.likedPlayer != null) {
            Player p = playerByUuid(instance, s.likedPlayer);
            if (p != null && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.CREATIVE)
                    && p.getPosition().distance(mob.getPosition()) <= LIKED_PLAYER_MAX_DISTANCE) {
                return p.getPosition();
            }
        }
        return null;
    }

    private static Player playerByUuid(Instance instance, UUID id) {
        for (Player p : instance.getPlayers()) {
            if (p.getUuid().equals(id)) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ item pickup

    /** Allay.wantsToPickUp + InventoryCarrier.pickUpItem: collect matching drops within reach. */
    private static void tryPickUp(Instance instance, EntityCreature mob, State s, ItemStack held) {
        for (Entity near : instance.getNearbyEntities(mob.getPosition(), PICKUP_REACH)) {
            if (!(near instanceof ItemEntity item) || item.isRemoved() || item.getAliveTicks() < 10) continue;
            ItemStack stack = item.getItemStack();
            if (!allayConsidersItemEqual(held, stack)) continue;
            int room = held.material().maxStackSize() - s.extra.amount();
            if (s.extra.isAir()) room = held.material().maxStackSize();
            if (room <= 0) return;
            int taken = Math.min(room, stack.amount());
            s.extra = s.extra.isAir() ? stack.withAmount(taken) : s.extra.withAmount(s.extra.amount() + taken);
            s.itemPickupCooldownTicks = ITEM_PICKUP_COOLDOWN_TICKS;
            if (taken >= stack.amount()) item.remove();
            else item.setItemStack(stack.withAmount(stack.amount() - taken));
            return;
        }
    }

    /** GoToWantedItem's NEAREST_VISIBLE_WANTED_ITEM sensor, approximated as a small radius scan. */
    private static Point findWantedItem(Instance instance, EntityCreature mob, ItemStack held) {
        for (Entity near : instance.getNearbyEntities(mob.getPosition(), 32)) {
            if (!(near instanceof ItemEntity item) || item.isRemoved() || item.getAliveTicks() < 10) continue;
            if (allayConsidersItemEqual(held, item.getItemStack())) return item.getPosition();
        }
        return null;
    }

    /** Allay.allayConsidersItemEqual: same item type AND matching (or absent) potion contents. */
    private static boolean allayConsidersItemEqual(ItemStack a, ItemStack b) {
        return a.material() == b.material()
                && Objects.equals(a.get(DataComponents.POTION_CONTENTS), b.get(DataComponents.POTION_CONTENTS));
    }

    // ------------------------------------------------------------------ movement

    private static void flyToward(EntityCreature mob, Point target, double speed) {
        Vec dir = Vec.fromPoint(target.sub(mob.getPosition()));
        double len = dir.length();
        if (len < 0.05) {
            mob.setVelocity(Vec.ZERO);
            return;
        }
        Vec v = dir.normalize().mul(speed * 20); // Minestom velocity is blocks/sec
        mob.setVelocity(v);
        double yaw = Math.toDegrees(Math.atan2(-dir.x(), dir.z()));
        mob.setView((float) yaw, mob.getPosition().pitch());
    }

    private static void wander(EntityCreature mob, State s, long now) {
        if (s.wanderGoal == null || now >= s.wanderUntil || mob.getPosition().distance(s.wanderGoal) < 0.5) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            double a = rng.nextDouble() * Math.PI * 2;
            Vec dir = new Vec(Math.cos(a), 0, Math.sin(a));
            double vy = (rng.nextDouble() - 0.5) * 2;
            Vec offset = dir.mul(6).add(0, vy, 0);
            s.wanderGoal = mob.getPosition().add(offset.x(), offset.y(), offset.z());
            s.wanderUntil = now + 60 + rng.nextInt(40);
        }
        flyToward(mob, s.wanderGoal, FLY_SPEED * 0.7);
    }

    private static void throwItem(Instance instance, Point from, Point target, ItemStack stack) {
        ItemEntity item = new ItemEntity(stack);
        item.setInstance(instance, new Pos(from.x(), from.y() + 1.0, from.z()));
        Vec dir = Vec.fromPoint(target.sub(from));
        if (dir.lengthSquared() > 0.01) item.setVelocity(dir.normalize().mul(4).add(0, 2, 0));
        playSoundAt(instance, Sound.sound(SoundEvent.ENTITY_ALLAY_ITEM_THROWN, Sound.Source.NEUTRAL, 1f, 1f), from);
    }

    private static void playSoundAt(Instance instance, Sound sound, Point at) {
        instance.playSound(sound, at.x(), at.y(), at.z());
    }

    // ------------------------------------------------------------------ test hooks

    public static UUID likedPlayer(EntityCreature mob) {
        State s = ALLAYS.get(mob.getEntityId());
        return s == null ? null : s.likedPlayer;
    }

    public static ItemStack extraInventory(EntityCreature mob) {
        State s = ALLAYS.get(mob.getEntityId());
        return s == null ? ItemStack.AIR : s.extra;
    }

    public static Point likedNoteblockPos(EntityCreature mob) {
        State s = ALLAYS.get(mob.getEntityId());
        return s == null ? null : s.likedNoteblockPos;
    }

    public static boolean canDuplicateForTest(EntityCreature mob) {
        State s = ALLAYS.get(mob.getEntityId());
        return s != null && canDuplicate(s);
    }

    public static void setDuplicationCooldownForTest(EntityCreature mob, long ticks) {
        State s = ALLAYS.get(mob.getEntityId());
        if (s != null) s.duplicationCooldownTicks = ticks;
    }

    /** Test hook: drive one state-machine step without waiting on the real per-tick scheduler
     * (CLAUDE.md rule: no wall-clock waits in PlayTest). */
    public static void tickForTest(EntityCreature mob) {
        State s = ALLAYS.get(mob.getEntityId());
        if (s != null) tickOne(mob, s);
    }
}
