package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.data.LootTables;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Piglin bartering: hand a piglin a gold ingot and it rolls the real piglin_bartering
 * loot table (decompile-verified: PiglinAi.getBarterResponseItems ->
 * BuiltInLootTables.PIGLIN_BARTERING) and drops the result. Real vanilla instead
 * requires THROWING the ingot so the piglin's own item-pickup AI notices and picks it
 * up (StartAdmiringItemIfSeen sensor), then admires it for ~6s (ADMIRING_ITEM memory,
 * 119-tick expiry) before responding — this project has no general mob item-pickup
 * system at all yet (no mob besides villagers can pick up a dropped item, per
 * docs/AUDIT.md), so the trigger is simplified to a direct hand-off, the same
 * simplification already used by every other interaction-based mechanic here
 * (Breeding, Shearing: real vanilla's throw/feed detection collapsed to a direct
 * right-click). Only the regular Piglin barters in real vanilla — PiglinBrute has no
 * bartering behavior in PiglinBruteAi. The loot table itself — weights, counts, the
 * two enchant_randomly(soul_speed) entries, the two set_potion entries — is the real
 * unmodified vanilla data, evaluated through the same generic LootTables engine used
 * for every other table in this project (LootTables.applyItemFunctions now handles
 * both of those function types).
 */
public final class Bartering {
    private Bartering() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerEntityInteractEvent.class, Bartering::interact);
    }

    private static void interact(PlayerEntityInteractEvent e) {
        if (e.getHand() != PlayerHand.MAIN) return;
        if (!(e.getTarget() instanceof EntityCreature piglin) || piglin.isDead()) return;
        if (piglin.getEntityType() != EntityType.PIGLIN) return;
        Player player = e.getPlayer();
        ItemStack held = player.getItemInHand(PlayerHand.MAIN);
        if (held.material() != Material.GOLD_INGOT) return;

        player.setItemInHand(PlayerHand.MAIN, held.consume(1));
        Instance instance = piglin.getInstance();
        Pos at = piglin.getPosition();
        Random random = ThreadLocalRandom.current();
        for (ItemStack drop : LootTables.gameplay("piglin_bartering", ItemStack.AIR)) {
            ItemEntity item = new ItemEntity(drop);
            item.setInstance(instance, at.add(0, 1.2, 0));
            item.setVelocity(new Vec((random.nextDouble() - 0.5) * 0.3, 0.3, (random.nextDouble() - 0.5) * 0.3));
        }
    }
}
