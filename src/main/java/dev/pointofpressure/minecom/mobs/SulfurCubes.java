package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.data.VanillaData;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.attribute.AttributeModifier;
import net.minestom.server.entity.attribute.AttributeOperation;
import net.minestom.server.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
 * equip, and the resulting attribute modifiers are real and tested here. NOT yet wired: the
 * actual swallow/give/bucket/shear interactions that would call {@link #equipBody} from
 * gameplay (still nothing right-clicks a sulfur cube), and the physics/gameplay CONSUMPTION of
 * the buoyant/explosion/contactDamage/knockback state this pass stores (floating, priming,
 * touch damage, knockback-scale hit reaction) — those read real per-archetype sound data too,
 * deliberately not ported yet since nothing consumes it. {@link #equipBody} is exposed for
 * whichever of those lands next to call. Unlike {@link Allays}, this class has no per-mob
 * scheduled task of its own to notice a despawned mob and evict its {@code STATES} entry (that
 * task lives in {@code VanillaMobs.slimeLike}, shared with slime/magma cube) — a swallowed cube
 * that dies leaves a small State object behind for the process lifetime. Bounded by the total
 * count of cubes ever fed something in a run, session-scoped anyway (see above), not wired to
 * avoid reaching into a shared cross-package tick loop for this pass; worth a one-line
 * {@code slimeLike} hook whenever that file is next touched for cube mobs.
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
    }

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
}
