package dev.pointofpressure.minecom.mobs;

import dev.pointofpressure.minecom.survival.Experience;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.animal.AnimalMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Animal breeding: feed two nearby animals their food (wheat for cows/sheep,
 * carrots for pigs, seeds for chickens) to enter love mode; they produce a baby,
 * grant 1-7 XP, and go on a 5-minute cooldown. Babies grow up in 20 minutes.
 */
public final class Breeding {
    private Breeding() {}

    private static final Map<Integer, Long> IN_LOVE = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> COOLDOWN = new ConcurrentHashMap<>();
    private static final Set<String> BREEDABLE = Set.of("cow", "pig", "sheep", "chicken");
    private static long tick;
    private static Instance instance;

    private static final Map<String, Set<Material>> FOODS = Map.of(
            "cow", Set.of(Material.WHEAT),
            "sheep", Set.of(Material.WHEAT),
            "pig", Set.of(Material.CARROT, Material.POTATO, Material.BEETROOT),
            "chicken", Set.of(Material.WHEAT_SEEDS, Material.BEETROOT_SEEDS,
                    Material.MELON_SEEDS, Material.PUMPKIN_SEEDS));

    public static void start(Instance overworld) {
        instance = overworld;
        MinecraftServer.getSchedulerManager().buildTask(Breeding::pairTick)
                .repeat(TaskSchedule.tick(20)).schedule();
    }

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerEntityInteractEvent.class, Breeding::feed);
    }

    private static void feed(PlayerEntityInteractEvent e) {
        if (e.getHand() != PlayerHand.MAIN) return;
        if (!(e.getTarget() instanceof EntityCreature animal) || animal.isDead()) return;
        String kind = animal.getEntityType().key().value();
        if (!BREEDABLE.contains(kind)) return;
        Player player = e.getPlayer();
        Material held = player.getItemInMainHand().material();
        if (!FOODS.get(kind).contains(held)) return;
        if (animal.getEntityMeta() instanceof AnimalMeta meta && meta.isBaby()) return;
        if (COOLDOWN.getOrDefault(animal.getEntityId(), 0L) > tick) return;
        if (IN_LOVE.containsKey(animal.getEntityId())) return;

        IN_LOVE.put(animal.getEntityId(), tick + 600); // 30s of love
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setItemInMainHand(player.getItemInMainHand().consume(1));
        }
    }

    private static void pairTick() {
        tick += 20;
        IN_LOVE.entrySet().removeIf(entry -> entry.getValue() < tick);
        if (IN_LOVE.size() < 2 || instance == null) return;

        var lovers = instance.getEntities().stream()
                .filter(en -> en instanceof EntityCreature c && !c.isDead()
                        && IN_LOVE.containsKey(c.getEntityId()))
                .map(en -> (EntityCreature) en)
                .toList();
        for (int i = 0; i < lovers.size(); i++) {
            for (int j = i + 1; j < lovers.size(); j++) {
                EntityCreature a = lovers.get(i), b = lovers.get(j);
                if (a.getEntityType() != b.getEntityType()) continue;
                if (a.getPosition().distance(b.getPosition()) > 8) continue;
                IN_LOVE.remove(a.getEntityId());
                IN_LOVE.remove(b.getEntityId());
                COOLDOWN.put(a.getEntityId(), tick + 6000);
                COOLDOWN.put(b.getEntityId(), tick + 6000);
                EntityCreature baby = Mobs.spawn(a.getEntityType().key().value(),
                        instance, a.getPosition());
                if (baby != null && baby.getEntityMeta() instanceof AnimalMeta meta) {
                    meta.setBaby(true);
                    baby.scheduler().buildTask(() -> {
                        if (!baby.isDead() && baby.getEntityMeta() instanceof AnimalMeta m) {
                            m.setBaby(false);
                        }
                    }).delay(TaskSchedule.tick(24000)).schedule(); // grows up in 20 min
                }
                Experience.orb(instance, a.getPosition(), 1 + (int) (Math.random() * 7));
                return; // one pair per cycle
            }
        }
    }
}
