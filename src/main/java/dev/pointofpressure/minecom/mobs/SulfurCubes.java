package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.data.VanillaData;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.attribute.AttributeModifier;
import net.minestom.server.entity.attribute.AttributeOperation;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.sound.SoundEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sulfur cube's data-driven archetype system, decompile-verified against
 * {@code SulfurCubeArchetype}/{@code SulfurCubeArchetypes} (26.2, {@code vanilla-src/net/
 * minecraft/world/entity/SulfurCubeArchetype*.java}) and {@code SulfurCube.
 * collectEquipmentChanges} ({@code vanilla-src/.../monster/cubemob/SulfurCube.java}): the item
 * swallowed into the BODY equipment slot is matched against the 12 real archetype item tags
 * (bundled in {@code tags_item.json}'s {@code sulfur_cube_archetype/*} entries — regular,
 * bouncy, slow_bouncy, slow_flat, fast_flat, light, fast_sliding, slow_sliding,
 * high_resistance, sticky, explosive, hot), and every matching archetype's real
 * speed/bounce/friction/drag constants ({@code SulfurCubeArchetypes.bootstrap}, ported verbatim)
 * become {@code AttributeModifier}s on the 5 real Minestom attributes vanilla's own archetype
 * data drives (KNOCKBACK_RESISTANCE, EXPLOSION_KNOCKBACK_RESISTANCE, BOUNCINESS,
 * FRICTION_MODIFIER, AIR_DRAG_MODIFIER — no new attributes needed, Minestom already has all 5).
 * Buoyancy/explosion/contact-damage/knockback-scale state is likewise assigned per real
 * vanilla's accumulation rule (a swallowed item can satisfy more than one archetype tag at
 * once — {@code matchingArchetypes} returns a list, and {@code collectEquipmentChanges} unions
 * every match's effects rather than picking one).
 * <p>
 * State (assigned archetypes, buoyant/explosion/contactDamage/knockback) is session-scoped
 * exactly like {@link Allays}' own {@code State} map (same note there): real vanilla doesn't
 * persist any of this either ({@code SulfurCube.addAdditionalSaveData} only ever saves
 * pickup_timer/from_bucket/fuse) — it's entirely re-derived from the BODY item whenever
 * equipment changes. A world reload restores the equipped body item generically (RegionStore's
 * mob-equipment snapshot) but this project's mob-persistence layer has no generic
 * "entity (re)loaded" hook yet to re-run that derivation automatically, so a cube carrying a
 * body item across a restart needs {@link #equipBody} called again to recompute its archetype
 * state — tracked in AUDIT.md, not attempted here.
 * <p>
 * Scope of this pass (Tier follow-up, AUDIT.md): the archetype DATA MODEL, its assignment on
 * equip, and the resulting attribute modifiers are real and tested here. Slice (b), this pass,
 * additionally wires the "explosive" archetype's fuse-priming/detonation
 * ({@link #primeFuse}/{@link #tickFuse}, decompile-verified against {@code SulfurCube.
 * hurtServer}/{@code primeTime}/{@code tickFuse} and {@code PrimedTnt.getRandomShortFuse} —
 * {@code vanilla-src/net/minecraft/world/entity/monster/cubemob/SulfurCube.java},
 * {@code vanilla-src/net/minecraft/world/entity/item/PrimedTnt.java}). STILL not wired: the
 * actual swallow/give/bucket/shear player interactions that would call {@link #equipBody} from
 * gameplay (still nothing right-clicks a sulfur cube — fuse-priming is exercised directly via
 * {@link #equipBody} + damage in tests, same as slice (a)'s archetype assignment), and the
 * remaining physics/gameplay CONSUMPTION of buoyant/contactDamage/knockback state (floating,
 * touch damage, knockback-scale hit reaction) — those still read real per-archetype sound data
 * too, deliberately not ported yet since nothing consumes it. {@link #equipBody} is exposed for
 * whichever of those lands next to call. Unlike {@link Allays}, this class has no per-mob
 * scheduled task of its own to notice a despawned mob and evict its {@code STATES} entry (that
 * task lives in {@code VanillaMobs.slimeLike}, shared with slime/magma cube) — a swallowed cube
 * that dies leaves a small State object behind for the process lifetime. Bounded by the total
 * count of cubes ever fed something in a run, session-scoped anyway (see above), not wired to
 * avoid reaching into a shared cross-package tick loop for this pass; worth a one-line
 * {@code slimeLike} hook whenever that file is next touched for cube mobs.
 * <p>
 * <b>Fuse-priming thread-affinity (docs/HANDOFF.md, "Sulfur fuse-priming flake DIAGNOSED":
 * cross-thread race CONFIRMED, 2026-07-19):</b> a first attempt at this slice mutated the fuse
 * field directly from {@link #primeFuse}, called synchronously from {@code EntityDamageEvent}'s
 * dispatch thread — which is NOT guaranteed to be this mob's own {@code TickThread} (Minestom's
 * thread-per-region model can and does dispatch damage from another entity's tick thread, or the
 * caller/main thread), while the per-mob countdown ran on a free-running {@code
 * scheduler().repeat(tick(1))} task pinned to the mob's real {@code TickThread}. Two threads
 * mutating the same non-volatile fields with no happens-before produced exactly the flake the
 * diagnosis's instrumented reproduction confirmed (a background decrement landing between a
 * main-thread arm and the main-thread's own synchronous read of the value it just set). Real
 * vanilla is immune because {@code primeTime}/{@code tickFuse} both run on the ONE server thread
 * (fuse is a plain, never-cross-thread {@code int} there). The fix restores that single-writer
 * invariant on Minestom's thread-per-region model: {@link #primeFuse} (any thread) only ever
 * records a request ({@code State.primeRequest}, an {@link AtomicInteger}); {@link #tickFuse}
 * (always the mob's own per-mob {@code TickThread}, via {@code VanillaMobs.slimeLike}'s shared
 * cube task) is the SOLE mutator of {@code State.fuse}/{@code State.detonated} — it consumes the
 * request, arms, counts down, and detonates. Corollary for tests: {@code State.fuse} is a
 * scheduler-driven field with a free-running writer, so PlayTest checks assert INVARIANTS that
 * hold under polling ("armed implies detonates within N ticks", "fuse reads are monotonically
 * non-increasing once armed", "detonation occurred") — never an exact instantaneous value read
 * off another thread with no elapsed tick, which is the exact shape of check that flaked before.
 */
public final class SulfurCubes {
    private SulfurCubes() {}

    /** One {@code SulfurCubeArchetype.AttributeEntry}: a real Minestom attribute + modifier. */
    public record AttrMod(Attribute attribute, AttributeModifier modifier) {}

    /** {@code SulfurCubeArchetype.ExplosionData}. */
    public record ExplosionData(int power, boolean causesFire, int fuse) {}

    /** {@code SulfurCubeArchetype.ContactDamage}: {@code amount} is a plain float since the only
     * real archetype using this (hot) carries a {@code ConstantFloat} — not modeling the general
     * {@code FloatProvider} range case since nothing else needs it. Not yet consumed (see class
     * doc) — the damage type id is kept as the raw string for whichever pass wires it. */
    public record ContactDamage(String damageTypeId, float amount, boolean attributeToSource) {}

    /** {@code SulfurCubeArchetype}, minus the registry indirection (this project has no generic
     * moddable-datapack-registry system, so the 12 real archetypes are a plain static list
     * instead — see {@link #ARCHETYPES}) and minus the sound-settings field, deferred with the
     * physics/interaction consumption this pass doesn't wire yet. */
    public record Archetype(
            String tagName,
            List<AttrMod> attributeModifiers,
            boolean buoyant,
            Optional<ExplosionData> explosion,
            Optional<ContactDamage> contactDamage,
            float knockbackHorizontal,
            float knockbackVertical
    ) {}

    private static AttrMod add(String archetypeName, Attribute attribute, double amount) {
        String attrName = attribute.key().value();
        return new AttrMod(attribute, new AttributeModifier(
                "minecom:" + archetypeName + "_add_" + attrName, amount, AttributeOperation.ADD_VALUE));
    }

    private static AttrMod multiply(String archetypeName, Attribute attribute, double amount) {
        String attrName = attribute.key().value();
        // SulfurCubeArchetype.AttributeEntry.multiply: stores (amount - 1.0) under
        // ADD_MULTIPLIED_TOTAL, so applyModifiers ends up multiplying by the real "amount".
        return new AttrMod(attribute, new AttributeModifier(
                "minecom:" + archetypeName + "_mul_" + attrName, amount - 1.0, AttributeOperation.ADD_MULTIPLIED_TOTAL));
    }

    /** {@code SulfurCubeArchetypes.archetype(speed, bounce, friction, drag)}: the 5 modifiers
     * every archetype carries, only the 4 constants differ per archetype. */
    private static List<AttrMod> stats(String name, float speed, float bounce, float friction, float drag) {
        return List.of(
                add(name, Attribute.KNOCKBACK_RESISTANCE, -speed),
                add(name, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE, -speed),
                add(name, Attribute.BOUNCINESS, bounce),
                multiply(name, Attribute.FRICTION_MODIFIER, friction),
                multiply(name, Attribute.AIR_DRAG_MODIFIER, drag)
        );
    }

    private static Archetype archetype(String name, float speed, float bounce, float friction, float drag,
                                        boolean buoyant, Optional<ExplosionData> explosion,
                                        Optional<ContactDamage> contactDamage, float kbH, float kbV) {
        return new Archetype(name, stats(name, speed, bounce, friction, drag), buoyant, explosion, contactDamage, kbH, kbV);
    }

    /** {@code SulfurCubeArchetypes.bootstrap}'s 12 entries, ported verbatim (same constants,
     * same order); tag membership is resolved against the real bundled data
     * ({@code tags_item.json}'s {@code sulfur_cube_archetype/<name>}) rather than hardcoded here. */
    public static final List<Archetype> ARCHETYPES = List.of(
            archetype("regular", 1.0f, 0.5f, 0.3f, 0.1f, true, Optional.empty(), Optional.empty(), 0.4125f, 0.09f),
            archetype("bouncy", 2.0f, 0.9f, 0.3f, 0.01f, true, Optional.empty(), Optional.empty(), 0.4125f, 0.105f),
            archetype("slow_bouncy", -0.4f, 0.6f, 0.3f, 0.05f, false, Optional.empty(), Optional.empty(), 0.4125f, 0.24f),
            archetype("slow_flat", -0.5f, 0.4f, 0.4f, 0.1f, false, Optional.empty(), Optional.empty(), 0.4125f, 0.105f),
            archetype("fast_flat", 1.0f, 0.5f, 0.2f, 0.01f, false, Optional.empty(), Optional.empty(), 0.9125f, 0.09f),
            archetype("light", 1.0f, 1.0f, 0.3f, 1.8f, true, Optional.empty(), Optional.empty(), 0.4125f, 0.18f),
            archetype("fast_sliding", -0.5f, 0.1f, 0.05f, 0.01f, false, Optional.empty(), Optional.empty(), 0.6625f, 0.09f),
            archetype("slow_sliding", -0.8f, 0.1f, 0.05f, 0.01f, false, Optional.empty(), Optional.empty(), 0.4125f, 0.09f),
            archetype("sticky", 2.0f, 0.0f, 2.0f, 0.01f, false, Optional.empty(), Optional.empty(), 0.4125f, 0.09f),
            archetype("high_resistance", -0.7f, 0.2f, 1.0f, 0.01f, false, Optional.empty(), Optional.empty(), 0.4125f, 0.09f),
            archetype("explosive", 1.0f, 0.5f, 0.3f, 0.3f, true,
                    Optional.of(new ExplosionData(3, false, 120)), Optional.empty(), 0.4125f, 0.09f),
            archetype("hot", 1.0f, 0.5f, 0.3f, 0.1f, true,
                    Optional.empty(), Optional.of(new ContactDamage("minecraft:sulfur_cube_hot", 1.0f, false)),
                    0.4125f, 0.09f)
    );

    private static final class State {
        List<Archetype> archetypes = List.of();
        boolean buoyant;
        Optional<ExplosionData> explosion = Optional.empty();
        List<ContactDamage> contactDamages = List.of();
        float knockbackHorizontal = 0.4125f;
        float knockbackVertical = 0.09f;

        // ---- fuse (owned exclusively by tickFuse, see class doc's thread-affinity note) ----
        /** SulfurCube.fuse: -1 = not primed. Written ONLY by {@link #tickFuse}. */
        volatile int fuse = NOT_PRIMED;
        /** SulfurCube.tickFuse's discard/explode branch already having fired, once ever. */
        volatile boolean detonated = false;
        /** Cross-thread arm signal: {@link #NO_REQUEST}/{@link #REQUEST_FULL}/
         *  {@link #REQUEST_IMMINENT}. Set by {@link #primeFuse} from any thread (CAS,
         *  first-request-wins); consumed exclusively by {@link #tickFuse}. */
        final AtomicInteger primeRequest = new AtomicInteger(NO_REQUEST);
    }

    private static final int NOT_PRIMED = -1;
    private static final int NO_REQUEST = 0;
    private static final int REQUEST_FULL = 1;
    private static final int REQUEST_IMMINENT = 2;

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    /** {@code SulfurCube.matchingArchetypes}: every archetype whose item tag the stack belongs
     * to (real vanilla can and does match more than one at once). */
    public static List<Archetype> matchingArchetypes(ItemStack stack) {
        if (stack == null || stack.isAir()) return List.of();
        List<Archetype> matches = new ArrayList<>();
        for (Archetype a : ARCHETYPES) {
            if (VanillaData.itemHasTag(stack.material(), "sulfur_cube_archetype/" + a.tagName())) {
                matches.add(a);
            }
        }
        return matches;
    }

    /** {@code SulfurCube.equipItem} + {@code collectEquipmentChanges}: sets the BODY slot and
     * re-derives every archetype-driven modifier/state from it (removing whatever the previous
     * body item contributed first). Returns the previous body item stack (empty if none), same
     * shape as vanilla spawning it back into the world on a swallow-over-swallow swap. */
    public static ItemStack equipBody(EntityCreature mob, ItemStack item) {
        ItemStack previous = mob.getEquipment(EquipmentSlot.BODY);
        State state = STATES.computeIfAbsent(mob.getEntityId(), id -> new State());

        for (Archetype a : state.archetypes) {
            for (AttrMod mod : a.attributeModifiers()) {
                var attr = mob.getAttribute(mod.attribute());
                if (attr != null) attr.removeModifier(mod.modifier().id());
            }
        }

        mob.setEquipment(EquipmentSlot.BODY, item == null ? ItemStack.AIR : item);

        List<Archetype> matches = matchingArchetypes(item);
        boolean buoyant = false;
        Optional<ExplosionData> explosion = Optional.empty();
        List<ContactDamage> contactDamages = new ArrayList<>();
        float kbH = 0.4125f, kbV = 0.09f;
        for (Archetype a : matches) {
            if (a.buoyant()) buoyant = true;
            if (a.explosion().isPresent()) explosion = a.explosion();
            a.contactDamage().ifPresent(contactDamages::add);
            kbH = a.knockbackHorizontal();
            kbV = a.knockbackVertical();
            for (AttrMod mod : a.attributeModifiers()) {
                var attr = mob.getAttribute(mod.attribute());
                if (attr != null) attr.addModifier(mod.modifier());
            }
        }
        state.archetypes = matches;
        state.buoyant = buoyant;
        state.explosion = explosion;
        state.contactDamages = List.copyOf(contactDamages);
        state.knockbackHorizontal = kbH;
        state.knockbackVertical = kbV;

        return previous == null ? ItemStack.AIR : previous;
    }

    /** {@code SulfurCube.hasBodyItem}. */
    public static ItemStack bodyItem(EntityCreature mob) {
        return mob.getEquipment(EquipmentSlot.BODY);
    }

    /** Test/future-slice hook: the archetype(s) currently assigned from the swallowed item. */
    public static List<Archetype> archetypesOf(EntityCreature mob) {
        State state = STATES.get(mob.getEntityId());
        return state == null ? List.of() : state.archetypes;
    }

    public static boolean isBuoyant(EntityCreature mob) {
        State state = STATES.get(mob.getEntityId());
        return state != null && state.buoyant;
    }

    public static Optional<ExplosionData> explosionOf(EntityCreature mob) {
        State state = STATES.get(mob.getEntityId());
        return state == null ? Optional.empty() : state.explosion;
    }

    public static List<ContactDamage> contactDamagesOf(EntityCreature mob) {
        State state = STATES.get(mob.getEntityId());
        return state == null ? List.of() : state.contactDamages;
    }

    // ------------------------------------------------------------------ fuse-priming (slice b)

    /** {@code SulfurCube.hurtServer}'s two priming branches: fire (or a burning arrow — not
     * modeled, see AUDIT.md, no fire-arrow tracking exists elsewhere in this project either)
     * arms the full archetype fuse; an explosion arms a short/"imminent" fuse. Only entities
     * that already carry the explosive archetype ({@code canExplode()}: explosionData present)
     * are eligible — matches {@code hurtServer}'s own {@code this.canExplode() && !this.isPrimed()}
     * gate, checked here as a fast-path hint only ({@link #primeFuse} re-checks authoritatively
     * on the owning thread). */
    public static void register(GlobalEventHandler events) {
        events.addListener(EntityDamageEvent.class, SulfurCubes::onDamage);
    }

    private static void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof EntityCreature mob) || mob.getEntityType() != EntityType.SULFUR_CUBE) return;
        State s = STATES.get(mob.getEntityId());
        if (s == null || s.explosion.isEmpty() || s.fuse != NOT_PRIMED) return;
        String typeId = e.getDamage().getType().key().asString();
        if (VanillaData.damageTypeHasTag(typeId, "is_fire")) {
            primeFuse(mob, false);
        } else if (VanillaData.damageTypeHasTag(typeId, "is_explosion")) {
            primeFuse(mob, true);
        }
    }

    /**
     * {@code SulfurCube.primeTime}'s arm TRIGGER — callable from ANY thread (real callers:
     * {@code onDamage} above, itself running on whichever thread dispatched the
     * {@code EntityDamageEvent}, which docs/HANDOFF.md's diagnosis confirmed is not reliably
     * this mob's own {@code TickThread}). Per the class doc's thread-affinity note, this NEVER
     * touches {@code fuse}/{@code detonated} — it only records a request, consumed by
     * {@link #tickFuse} the next time it runs on the mob's own tick thread. First request wins
     * (CAS from {@link #NO_REQUEST} only) — a second/duplicate signal while one is already
     * pending, or while already primed, is simply dropped, same end state as vanilla's
     * {@code !isPrimed()} gate (re-checked authoritatively in {@link #tickFuse} regardless).
     *
     * @param imminent false = full archetype fuse (fire), true = {@code PrimedTnt.
     *                 getRandomShortFuse}'s short/random fuse (explosion)
     */
    public static void primeFuse(EntityCreature mob, boolean imminent) {
        State s = STATES.get(mob.getEntityId());
        if (s == null) return;
        s.primeRequest.compareAndSet(NO_REQUEST, imminent ? REQUEST_IMMINENT : REQUEST_FULL);
    }

    /**
     * {@code SulfurCube.tickFuse} + the consuming half of {@code primeTime} — run ONCE PER TICK
     * from this mob's own per-mob scheduler task ({@code VanillaMobs.slimeLike}'s shared cube
     * task, always on the entity's {@code TickThread}). The SOLE mutator of
     * {@code State.fuse}/{@code State.detonated}: consumes a pending {@link #primeFuse} request
     * to arm (ties vanilla's {@code primeTime}'s fuse assignment, including
     * {@code PrimedTnt.getRandomShortFuse}'s exact formula for an imminent request), then counts
     * an already-armed fuse down and detonates via {@code Explosions.explode} at 0 — matching
     * real {@code SulfurCube.tickFuse}'s {@code fuse--} / {@code fuse==0} branches exactly, just
     * re-homed onto this mob's own tick thread instead of vanilla's single server thread.
     */
    public static void tickFuse(EntityCreature mob) {
        State s = STATES.get(mob.getEntityId());
        if (s == null || s.explosion.isEmpty()) return;
        if (s.fuse == NOT_PRIMED) {
            int req = s.primeRequest.getAndSet(NO_REQUEST);
            if (req != NO_REQUEST) arm(mob, s, req == REQUEST_IMMINENT);
            return;
        }
        if (s.detonated) return;
        if (s.fuse > 0) s.fuse--;
        // SulfurCube.tickFuse: explosionData can theoretically go empty mid-fuse (equipBody
        // re-swallow); vanilla just skips the explode branch in that case rather than
        // discarding, so a fuse that hits 0 with no explosion data left just sits there —
        // matched here rather than special-cased, same real-vanilla edge case either way.
        if (s.fuse == 0 && s.explosion.isPresent()) detonate(mob, s);
    }

    /** {@code SulfurCube.primeTime}: sets the fuse ({@code PrimedTnt.getRandomShortFuse} ported
     * verbatim for the imminent/explosion case), marks the mob invulnerable, plays the real
     * {@code SoundEvents.TNT_PRIMED} sound. Called ONLY from {@link #tickFuse}'s own thread. */
    private static void arm(EntityCreature mob, State s, boolean imminent) {
        int base = s.explosion.get().fuse();
        int fuseTime = imminent
                ? ThreadLocalRandom.current().nextInt(Math.max(1, base / 4)) + base / 8
                : base;
        s.fuse = fuseTime;
        mob.setInvulnerable(true);
        Instance instance = mob.getInstance();
        if (instance != null) {
            Point p = mob.getPosition();
            instance.playSound(Sound.sound(SoundEvent.ENTITY_TNT_PRIMED, Sound.Source.NEUTRAL, 1f, 1f),
                    p.x(), p.y(), p.z());
        }
    }

    /** {@code SulfurCube.tickFuse}'s {@code fuse==0} branch: {@code dropLeash/dead=true/explode/
     * discard}, with NO split (real {@code getSplitCount()} returns 0 while primed — unlike a
     * normal kill through {@code Combat.death}/{@code maybeSplitSlime}, this bypasses that path
     * entirely by removing the mob directly). Called ONLY from {@link #tickFuse}'s own thread. */
    private static void detonate(EntityCreature mob, State s) {
        Instance instance = mob.getInstance();
        Point at = mob.getPosition();
        ExplosionData data = s.explosion.get();
        mob.remove();
        if (instance != null) {
            // data.causesFire() (a fire-spreading explosion) isn't modeled — Explosions.explode
            // has no fire-spread parameter yet (AUDIT.md); the one real archetype carrying
            // ExplosionData ("explosive"/tnt) has causesFire=false anyway, so nothing currently
            // exercises the gap.
            dev.pointofpressure.minecom.blocks.Explosions.explode(instance, at.add(0, 0.0625, 0),
                    (float) data.power(), 1.0, null);
        }
        // Set LAST, after the explosion (block destruction + entity damage) has actually run —
        // detonated=true is meant as "the detonation, including its blast, fully happened", not
        // merely "started". Setting it earlier let a polling reader (e.g. PlayTest's waitFor)
        // observe "detonated" while Explosions.explode was still executing on this same call
        // stack, race ahead, and spawn something new INTO the still-live blast radius —
        // diagnosed via a real intermittent PlayTest failure, not a hypothetical.
        s.detonated = true;
    }

    // ------------------------------------------------------------------ fuse test hooks

    /** {@code SulfurCube.isPrimed}. Safe to read from any thread — {@code fuse} only ever
     * transitions {@code NOT_PRIMED -> armed value} (monotonically non-increasing from there)
     * on {@link #tickFuse}'s own thread, so any read observes a valid past-or-present state,
     * never a torn one. Do not assert an exact fuse VALUE read this way with no elapsed tick
     * (docs/HANDOFF.md's diagnosed race) — invariants only (armed, monotonic, detonated). */
    public static boolean isPrimed(EntityCreature mob) {
        State s = STATES.get(mob.getEntityId());
        return s != null && s.fuse != NOT_PRIMED;
    }

    /** Current fuse value, or {@code NOT_PRIMED} (-1) if not armed. See {@link #isPrimed}'s note
     * on safe (invariant-only) cross-thread use. */
    public static int fuseOf(EntityCreature mob) {
        State s = STATES.get(mob.getEntityId());
        return s == null ? NOT_PRIMED : s.fuse;
    }

    /** True only once the FULL detonation — including {@code Explosions.explode}'s block
     * destruction and entity damage — has actually finished (set last in {@link #detonate}), not
     * merely once it started. Safe to spawn/act near the blast site once this reads true. */
    public static boolean detonatedOf(EntityCreature mob) {
        State s = STATES.get(mob.getEntityId());
        return s != null && s.detonated;
    }
}
