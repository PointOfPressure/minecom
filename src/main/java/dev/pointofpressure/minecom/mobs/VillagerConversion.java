package dev.pointofpressure.minecom.mobs;

import net.kyori.adventure.sound.Sound;
import dev.pointofpressure.minecom.TickPipeline;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.metadata.monster.zombie.ZombieVillagerMeta;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Villager -&gt; zombie-villager conversion on a zombie-family kill (difficulty-scaled), and
 * the cure loop back (weakness potion + golden apple, timed conversion, a cured-villager
 * trade discount). Verified against decompiled {@code Zombie.killedEntity} /
 * {@code convertVillagerToZombieVillager}, {@code ZombieVillager} ({@code mobInteract} /
 * {@code startConverting} / {@code getConversionProgress} / {@code finishConversion}), and
 * the reputation slice of {@code Villager.onReputationEventFrom} / {@code updateSpecialPrices}
 * plus {@code GossipType} / {@code GossipContainer} (all 26.2, re-decompiled for this pass —
 * the cached copies predated the 2026-07-13 26.2 bump).
 *
 * <p>Both conversions replace the entity outright (spawn the other type, discard this one)
 * since neither this project nor real vanilla mutates an entity's type in place; only the
 * profession tag (which is all this project's trades key off — see {@link VillagerTrades})
 * and any in-progress cure reputation are carried across.
 *
 * <p>Not modeled: the full vanilla gossip ledger (trade/hurt/killed reputation events, decay,
 * transfer between villagers, hero-of-the-village) — only the zombie-cure discount is ported,
 * since a single cure already saturates both contributing gossip types' caps in real vanilla
 * (see {@link #CURE_REPUTATION}), so a full weighted-decay ledger would converge on the same
 * number for this one path. Session-scoped: conversion timers and cure reputation are not
 * persisted (StateAdapter) — a restart mid-cure resets the timer, and a restart after a cure
 * resets the discount — matching this project's existing precedent for other short/medium
 * -lived session state (breeding's IN_LOVE window, warden anger). The one-off particle/sound
 * bursts real vanilla drives via {@code levelEvent}/entity-event bytes are skipped as
 * client-visual (this codebase's established precedent — see e.g. Warden's sonic-boom trail);
 * the {@code ZombieVillagerMeta.setConverting} synced flag (drives the per-tick shaking
 * particles) and the two named sounds below ARE ported since those are cheap and load-bearing
 * for actually noticing a cure is underway.
 */
public final class VillagerConversion {
    private VillagerConversion() {}

    private static final Set<EntityType> ZOMBIE_FAMILY =
            Set.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED, EntityType.ZOMBIE_VILLAGER);

    // ZombieVillager.VILLAGER_CONVERSION_WAIT_MIN/MAX: random.nextInt(2401) + 3600 (3600-6000t).
    private static final int CONVERSION_WAIT_MIN = 3600;
    private static final int CONVERSION_WAIT_RANGE = 2401;

    // ZombieVillager.getConversionProgress's special-block scan: a 1%-per-tick roll scans up
    // to 14 nearby iron_bars/bed blocks, each independently a 30% chance of an extra +1 tick.
    private static final int SPECIAL_BLOCK_RADIUS = 4;
    private static final int MAX_SPECIAL_BLOCKS = 14;

    // Villager.onReputationEventFrom(ZOMBIE_VILLAGER_CURED): gossips.add(MAJOR_POSITIVE, 20)
    // + gossips.add(MINOR_POSITIVE, 25); GossipContainer.add caps each entry at the type's own
    // max (both event amounts here ARE their type's max), then EntityGossips.weightedValue
    // multiplies by GossipType.weight (5 and 1): 20*5 + 25*1 = 125. A single cure already
    // saturates both types, so this constant IS the steady state, not an approximation of it.
    private static final int CURE_REPUTATION = 125;

    // Villager.updateSpecialPrices / MerchantOffer.getModifiedCostCount: real vanilla's own
    // VillagerTrades bootstrap constructs every basic-tier trade (this project's only tier —
    // see VillagerTrades' class javadoc) with this exact 0.05 price multiplier; only the
    // equipment-tier trades this project doesn't model use the other real constant, 0.2.
    private static final float PRICE_MULTIPLIER = 0.05f;

    private static final Map<Integer, Integer> CONVERT_TICKS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> CONVERT_TOTAL = new ConcurrentHashMap<>();
    private static final Map<Integer, UUID> CONVERT_STARTER = new ConcurrentHashMap<>();
    private static final Map<Integer, Map<UUID, Integer>> CURE_REPUTATIONS = new ConcurrentHashMap<>();

    public static void register(Instance overworld) {
        overworld.eventNode().addListener(PlayerEntityInteractEvent.class, VillagerConversion::interact);
        TickPipeline.register(TickPipeline.ENTITIES, "villagerConversion", () -> tickAll(overworld));
    }

    /**
     * Zombie.killedEntity: Normal is a coinflip (skip on heads), Hard always converts,
     * Easy/Peaceful never do. Any zombie-family kill counts — Husk/Drowned/ZombieVillager all
     * extend Zombie and don't override killedEntity in real vanilla, so their kills convert
     * villagers too, not just the base zombie type.
     */
    public static boolean tryConvert(EntityCreature villager, Instance instance, Pos pos, Entity attacker) {
        if (!(attacker instanceof EntityCreature zombie) || !ZOMBIE_FAMILY.contains(zombie.getEntityType())) {
            return false;
        }
        dev.pointofpressure.minecom.Difficulty d = dev.pointofpressure.minecom.Difficulty.current();
        if (d != dev.pointofpressure.minecom.Difficulty.NORMAL && d != dev.pointofpressure.minecom.Difficulty.HARD) {
            return false;
        }
        if (d == dev.pointofpressure.minecom.Difficulty.NORMAL && ThreadLocalRandom.current().nextBoolean()) {
            return false;
        }

        EntityCreature zv = dev.pointofpressure.minecom.mobs.ai.VanillaMobs.zombieVillager(instance, pos);
        String profession = villager.getTag(VillagerTrades.PROFESSION);
        if (profession != null) zv.setTag(VillagerTrades.PROFESSION, profession);
        carryReputation(villager.getEntityId(), zv.getEntityId());
        instance.playSound(Sound.sound(SoundEvent.ENTITY_ZOMBIE_VILLAGER_CONVERTED, Sound.Source.HOSTILE, 1f, 1f),
                pos.x(), pos.y(), pos.z());
        // A converted entity is replaced, not "died" — real vanilla's convertTo discards the old
        // entity immediately. EntityCreature.kill() (already in flight via this death event)
        // otherwise only SCHEDULES removal after the death-animation delay, which would leave
        // the old villager visibly lingering next to its own replacement for a moment.
        villager.remove();
        return true;
    }

    private static void interact(PlayerEntityInteractEvent e) {
        if (e.getHand() != PlayerHand.MAIN) return;
        if (!(e.getTarget() instanceof EntityCreature zv) || zv.isDead()) return;
        if (zv.getEntityType() != EntityType.ZOMBIE_VILLAGER) return;
        Player player = e.getPlayer();
        ItemStack held = player.getItemInMainHand();
        // ZombieVillager.mobInteract: only the plain golden apple, never the enchanted one.
        if (held.material() != Material.GOLDEN_APPLE) return;
        if (!zv.hasEffect(PotionEffect.WEAKNESS)) return;
        if (CONVERT_TICKS.containsKey(zv.getEntityId())) return;
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setItemInMainHand(held.consume(1));
        }
        startConverting(zv, player.getUuid());
    }

    private static void startConverting(EntityCreature zv, UUID curer) {
        int total = ThreadLocalRandom.current().nextInt(CONVERSION_WAIT_RANGE) + CONVERSION_WAIT_MIN;
        CONVERT_TOTAL.put(zv.getEntityId(), total);
        CONVERT_TICKS.put(zv.getEntityId(), 0);
        CONVERT_STARTER.put(zv.getEntityId(), curer);
        zv.removeEffect(PotionEffect.WEAKNESS);
        zv.addEffect(new Potion(PotionEffect.STRENGTH, (byte) 0, total));
        if (zv.getEntityMeta() instanceof ZombieVillagerMeta meta) meta.setConverting(true);
        Instance instance = zv.getInstance();
        if (instance != null) {
            Pos pos = zv.getPosition();
            instance.playSound(Sound.sound(SoundEvent.ENTITY_ZOMBIE_VILLAGER_CURE, Sound.Source.HOSTILE, 1f, 1f),
                    pos.x(), pos.y(), pos.z());
        }
    }

    private static void tickAll(Instance instance) {
        for (Entity e : instance.getEntities()) {
            if (!(e instanceof EntityCreature zv) || zv.getEntityType() != EntityType.ZOMBIE_VILLAGER) continue;
            Integer ticks = CONVERT_TICKS.get(zv.getEntityId());
            if (ticks == null) continue;
            if (zv.isDead()) {
                clearConversion(zv.getEntityId());
                continue;
            }
            Integer total = CONVERT_TOTAL.get(zv.getEntityId());
            int newTicks = ticks + conversionProgress(zv);
            if (total != null && newTicks >= total) {
                finishConversion(zv, instance);
            } else {
                CONVERT_TICKS.put(zv.getEntityId(), newTicks);
            }
        }
    }

    /**
     * ZombieVillager.getConversionProgress: usually +1/tick; a rare 1% roll scans up to 14
     * nearby iron_bars/bed blocks (a nod to "hospital"-style cure setups), each independently
     * a 30% chance of an extra +1 — never more than 1 (base) + 14 (every scanned block hits).
     */
    private static int conversionProgress(EntityCreature zv) {
        int amount = 1;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (rng.nextFloat() < 0.01f) {
            Instance instance = zv.getInstance();
            if (instance != null) {
                Pos pos = zv.getPosition();
                int cx = pos.blockX(), cy = pos.blockY(), cz = pos.blockZ();
                int found = 0;
                outer:
                for (int x = cx - SPECIAL_BLOCK_RADIUS; x < cx + SPECIAL_BLOCK_RADIUS; x++) {
                    for (int y = cy - SPECIAL_BLOCK_RADIUS; y < cy + SPECIAL_BLOCK_RADIUS; y++) {
                        for (int z = cz - SPECIAL_BLOCK_RADIUS; z < cz + SPECIAL_BLOCK_RADIUS; z++) {
                            if (found >= MAX_SPECIAL_BLOCKS) break outer;
                            Block b = instance.getBlock(x, y, z);
                            if (b.compare(Block.IRON_BARS) || b.key().value().endsWith("_bed")) {
                                if (rng.nextFloat() < 0.3f) amount++;
                                found++;
                            }
                        }
                    }
                }
            }
        }
        return amount;
    }

    private static void finishConversion(EntityCreature zv, Instance instance) {
        Pos pos = zv.getPosition();
        UUID curer = CONVERT_STARTER.get(zv.getEntityId());
        EntityCreature villager = dev.pointofpressure.minecom.mobs.ai.VanillaMobs.villager(instance, pos);
        String profession = zv.getTag(VillagerTrades.PROFESSION);
        if (profession != null) villager.setTag(VillagerTrades.PROFESSION, profession);
        carryReputation(zv.getEntityId(), villager.getEntityId());
        if (curer != null) {
            CURE_REPUTATIONS.computeIfAbsent(villager.getEntityId(), k -> new ConcurrentHashMap<>())
                    .put(curer, CURE_REPUTATION);
        }
        villager.addEffect(new Potion(PotionEffect.NAUSEA, (byte) 0, 200));
        instance.playSound(Sound.sound(SoundEvent.ENTITY_ZOMBIE_VILLAGER_CONVERTED, Sound.Source.HOSTILE, 1f, 1f),
                pos.x(), pos.y(), pos.z());
        clearConversion(zv.getEntityId());
    }

    /**
     * Test hook: drives one sweep of the real per-tick conversion loop directly, instead of
     * waiting on the real 1-tick scheduler for a 3600-6000t (3-5 minute) timer (same idiom as
     * {@code VillagerFood.pickupSweep}/{@code farmerSweep} being called directly by tests).
     */
    public static void testTick(Instance instance) {
        tickAll(instance);
    }

    /**
     * Test hook: collapses an in-progress conversion's remaining timer to effectively zero, so
     * the next {@link #testTick} finishes it — production always randomizes the real
     * 3600-6000t total (same precedent as {@code Steering.testForceBoost}).
     */
    public static void testForceNearComplete(EntityCreature zombieVillager) {
        CONVERT_TOTAL.put(zombieVillager.getEntityId(), 1);
    }

    /**
     * Test hook: the buy-side cost a real trade-open would charge this player for the named
     * profession's first offer, cure discount included — exercises the exact function
     * {@code VillagerTrades.openTrading} calls, without needing to capture the TradeListPacket
     * a real trade-open sends over the network (no such capture exists in this harness).
     */
    public static int testFirstOfferCost(Entity villager, Player player, String profession) {
        List<VillagerTrades.Offer> offers = VillagerTrades.TABLES.getOrDefault(profession, VillagerTrades.TABLES.get("farmer"));
        return discount(offers, villager, player).get(0).input1().amount();
    }

    private static void clearConversion(int zombieVillagerEntityId) {
        CONVERT_TICKS.remove(zombieVillagerEntityId);
        CONVERT_TOTAL.remove(zombieVillagerEntityId);
        CONVERT_STARTER.remove(zombieVillagerEntityId);
    }

    private static void carryReputation(int fromEntityId, int toEntityId) {
        Map<UUID, Integer> rep = CURE_REPUTATIONS.remove(fromEntityId);
        if (rep != null) CURE_REPUTATIONS.put(toEntityId, rep);
    }

    /**
     * Villager.updateSpecialPrices' reputation slice: {@code floor(reputation *
     * priceMultiplier)} off the offer's cost side (this project's {@code Offer.input1}, which
     * always holds whatever the player pays — matching vanilla's {@code baseCostA}), clamped
     * to a minimum of 1. Real vanilla recomputes this fresh every time trading opens (its
     * {@code specialPriceDiff} resets to 0 on close); this project has no persistent per-offer
     * state at all, so the discount is applied directly to a fresh copy at open time instead —
     * same visible result. Returns the input list unchanged if there's no reputation to apply,
     * so callers can pass every open through this uniformly.
     */
    static List<VillagerTrades.Offer> discount(List<VillagerTrades.Offer> offers, Entity villager, Player player) {
        Map<UUID, Integer> rep = CURE_REPUTATIONS.get(villager.getEntityId());
        Integer reputation = rep == null ? null : rep.get(player.getUuid());
        if (reputation == null || reputation == 0) return offers;
        int reduce = (int) Math.floor(reputation * PRICE_MULTIPLIER);
        if (reduce <= 0) return offers;
        List<VillagerTrades.Offer> discounted = new ArrayList<>(offers.size());
        for (VillagerTrades.Offer o : offers) {
            int newAmount = Math.max(1, o.input1().amount() - reduce);
            discounted.add(new VillagerTrades.Offer(o.input1().withAmount(newAmount), o.input2(), o.result()));
        }
        return discounted;
    }
}
