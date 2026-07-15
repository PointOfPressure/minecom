package dev.pointofpressure.minecom.mobs;

import net.kyori.adventure.text.Component;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Name tags, verified against decompiled NameTagItem (26.2): the rename step is
 * already handled generically by the existing anvil flow (blocks/Anvils.java writes
 * DataComponents.CUSTOM_NAME onto any item, name tags included — no name-tag-specific
 * anvil code was needed). Right-clicking a living entity with a <em>named</em> tag
 * (an unnamed one does nothing, matching NameTagItem.interactLivingEntity's early
 * return when CUSTOM_NAME is absent) sets the entity's custom name, makes it always
 * visible, marks it persistent — this project's equivalent of Mob.setPersistenceRequired,
 * exempting it from VNaturalSpawner.despawnTick's distance sweep — and consumes the
 * tag. Persistence is tracked here (a session-scoped entity-id set, same precedent as
 * TrialChambers/CreakingHearts) rather than as a general Mob field, since nothing
 * else in this codebase needs to ask "is this mob persistence-required" yet.
 */
public final class NameTags {
    private NameTags() {}

    private static final Set<Integer> PERSISTENT = ConcurrentHashMap.newKeySet();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerEntityInteractEvent.class, NameTags::interact);
    }

    private static void interact(PlayerEntityInteractEvent e) {
        if (e.getHand() != PlayerHand.MAIN) return;
        if (!(e.getTarget() instanceof LivingEntity target) || target.isDead()) return;
        Player player = e.getPlayer();
        ItemStack held = player.getItemInMainHand();
        if (held.material() != Material.NAME_TAG) return;
        Component customName = held.get(DataComponents.CUSTOM_NAME);
        if (customName == null) return;

        target.setCustomName(customName);
        target.setCustomNameVisible(true);
        if (target instanceof EntityCreature mob) PERSISTENT.add(mob.getEntityId());
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setItemInMainHand(held.consume(1));
        }
    }

    public static boolean isPersistent(EntityCreature mob) {
        return PERSISTENT.contains(mob.getEntityId());
    }
}
