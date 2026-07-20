package dev.pointofpressure.minecom.survival;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.item.PlayerFinishItemUseEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerDeathEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerRespawnEvent;
import net.minestom.server.event.player.PlayerTickEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.Food;

import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanilla survival mechanics: hunger/exhaustion/saturation, natural regeneration,
 * starvation, eating (driven by the items' real FOOD components), fall damage,
 * void damage, and death drops. Values follow the vanilla normal-difficulty model.
 */
public final class Survival {
    private Survival() {}

    private static final class State {
        float exhaustion;
        double highestY = Double.NEGATIVE_INFINITY;
        int regenTicks;
        int starveTicks;
        int voidTicks;
        int lavaTicks;
        int campfireTicks;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();

    private static State state(Player p) {
        return STATES.computeIfAbsent(p.getUuid(), u -> new State());
    }

    public static void addExhaustion(Player player, float amount) {
        if (player.getGameMode() != GameMode.SURVIVAL) return;
        state(player).exhaustion += amount;
    }

    /** Entity.resetFallDistance: called after a teleport (e.g. an ender pearl landing) so the
     *  next ground contact doesn't measure fall damage against the pre-teleport height. */
    public static void resetFallTracking(Player player) {
        state(player).highestY = Double.NEGATIVE_INFINITY;
    }

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerTickEvent.class, e -> tick(e.getPlayer()));
        events.addListener(PlayerMoveEvent.class, Survival::move);
        events.addListener(PlayerUseItemEvent.class, Survival::useItem);
        events.addListener(PlayerFinishItemUseEvent.class, Survival::finishEating);
        events.addListener(PlayerDeathEvent.class, Survival::death);
        events.addListener(PlayerRespawnEvent.class, e -> {
            Player p = e.getPlayer();
            p.scheduler().scheduleNextTick(() -> {
                p.setHealth((float) p.getAttributeValue(net.minestom.server.entity.attribute.Attribute.MAX_HEALTH));
                p.setFood(20);
                p.setFoodSaturation(5f);
                State s = state(p);
                s.exhaustion = 0;
                s.highestY = Double.NEGATIVE_INFINITY;
            });
        });
        events.addListener(PlayerBlockBreakEvent.class, e -> addExhaustion(e.getPlayer(), 0.005f));
        events.addListener(PlayerDisconnectEvent.class, e -> STATES.remove(e.getPlayer().getUuid()));
    }

    // ------------------------------------------------------------------ hunger

    private static void tick(Player p) {
        if (p.getGameMode() != GameMode.SURVIVAL || p.isDead()) return;
        State s = state(p);

        var difficulty = dev.pointofpressure.minecom.Difficulty.current();
        if (s.exhaustion >= 4f) {
            s.exhaustion -= 4f;
            if (p.getFoodSaturation() > 0) {
                p.setFoodSaturation(Math.max(0, p.getFoodSaturation() - 1));
            } else if (difficulty != dev.pointofpressure.minecom.Difficulty.PEACEFUL) {
                // FoodData.tick: the hunger bar never drains on Peaceful
                p.setFood(Math.max(0, p.getFood() - 1));
            }
        }

        float maxHealth = (float) p.getAttributeValue(net.minestom.server.entity.attribute.Attribute.MAX_HEALTH);
        // natural_health_regeneration gamerule: vanilla gates BOTH the Peaceful aiStep
        // heal and both FoodData.tick regen paths on it (hunger drain is unaffected).
        boolean naturalRegen = dev.pointofpressure.minecom.GameRules.getBool("natural_health_regeneration");
        // Player.aiStep: Peaceful regenerates 1 health/second and 1 food/half-second outright
        if (difficulty == dev.pointofpressure.minecom.Difficulty.PEACEFUL) {
            if (naturalRegen && p.getHealth() < maxHealth && p.getAliveTicks() % 20 == 0) {
                p.setHealth(Math.min(maxHealth, p.getHealth() + 1));
            }
            if (p.getFood() < 20 && p.getAliveTicks() % 10 == 0) {
                p.setFood(p.getFood() + 1);
            }
        }
        // FoodData.tick: two mutually-exclusive natural-regen paths sharing one counter —
        // at full food (20) with spare saturation, healing is MUCH faster (every 10 ticks,
        // spending up to 6 saturation for up to 1 HP) than the general food>=18 path (every
        // 80 ticks, flat 1 HP for 6 exhaustion). Previously only the slow path existed, so
        // eating well never sped up healing the way it does in real vanilla.
        if (!naturalRegen) {
            s.regenTicks = 0;
        } else if (p.getFoodSaturation() > 0 && p.getFood() >= 20 && p.getHealth() < maxHealth) {
            if (++s.regenTicks >= 10) {
                s.regenTicks = 0;
                // FoodData.tick's fast path never subtracts saturation directly — it only
                // adds exhaustion, which the block above (fired on a LATER tick once
                // exhaustion clears 4.0) converts into the actual saturation loss. Spending
                // up to 6.0 here naturally drains across several of those later ticks.
                float saturationSpent = Math.min(p.getFoodSaturation(), 6f);
                p.setHealth(Math.min(maxHealth, p.getHealth() + saturationSpent / 6f));
                s.exhaustion += saturationSpent;
            }
        } else if (p.getFood() >= 18 && p.getHealth() < maxHealth) {
            if (++s.regenTicks >= 80) {
                s.regenTicks = 0;
                p.setHealth(Math.min(maxHealth, p.getHealth() + 1));
                s.exhaustion += 6f;
            }
        } else {
            s.regenTicks = 0;
        }

        if (p.getFood() == 0) {
            if (++s.starveTicks >= 80) {
                s.starveTicks = 0;
                // FoodData.tick: starve to death on Hard, down to 1 HP on Normal, 10 on Easy
                boolean starves = switch (difficulty) {
                    case HARD -> true;
                    case NORMAL -> p.getHealth() > 1;
                    case EASY -> p.getHealth() > 10;
                    case PEACEFUL -> false; // unreachable: food never hits 0
                };
                if (starves) p.damage(DamageType.STARVE, 1f);
            }
        } else {
            s.starveTicks = 0;
        }

        if (p.getPosition().y() < -84) {
            if (++s.voidTicks >= 10) {
                s.voidTicks = 0;
                p.damage(DamageType.OUT_OF_WORLD, 4f);
            }
        }

        // lava: 4 damage every 10 ticks unless fire resistant
        Block standing = p.getInstance().getBlock(p.getPosition());
        Block body = p.getInstance().getBlock(p.getPosition().add(0, 0.9, 0));
        if (standing.key().value().equals("lava") || body.key().value().equals("lava")) {
            if (++s.lavaTicks >= 10) {
                s.lavaTicks = 0;
                if (Potions.effectLevel(p, net.minestom.server.potion.PotionEffect.FIRE_RESISTANCE) == 0) {
                    p.damage(DamageType.LAVA, 4f);
                }
            }
        } else {
            s.lavaTicks = 0;
        }

        // CampfireBlock.entityInside: 1 damage (soul_campfire: 2) while lit and standing inside
        String standingKey = standing.key().value();
        boolean inLitCampfire = ("campfire".equals(standingKey) || "soul_campfire".equals(standingKey))
                && "true".equals(standing.getProperty("lit"));
        if (inLitCampfire) {
            if (++s.campfireTicks >= 10) {
                s.campfireTicks = 0;
                p.damage(DamageType.CAMPFIRE, "soul_campfire".equals(standingKey) ? 2f : 1f);
            }
        } else {
            s.campfireTicks = 0;
        }
    }

    // ------------------------------------------------------------------ falling

    private static void move(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL || p.isDead()) return;
        State s = state(p);
        if (p.getVehicle() != null) {
            // A rider's own fall distance isn't tracked independently of its vehicle in
            // real vanilla (a boat/minecart/horse absorbs the landing itself; the entity
            // riding it never takes fall damage from the ride). Reset tracking so
            // dismounting doesn't replay a bogus fall from whatever height the player was
            // at right before boarding. Previously this project tracked the player's own
            // Y position unconditionally, so a player who boarded a boat mid-fall (or flew
            // one off a cliff) still took fall damage the instant they stepped off,
            // matching neither the vehicle's landing nor the player's own actual fall.
            s.highestY = Double.NEGATIVE_INFINITY;
            return;
        }
        Pos from = p.getPosition();
        Pos to = e.getNewPosition();

        if (p.isSprinting()) {
            double dx = to.x() - from.x(), dz = to.z() - from.z();
            addExhaustion(p, (float) (Math.sqrt(dx * dx + dz * dz) * 0.1));
        }

        Block feet = p.getInstance().getBlock(to);
        boolean inFluid = feet.isLiquid() || feet.compare(Block.LADDER) || feet.compare(Block.VINE)
                || feet.compare(Block.COBWEB)
                // bubble columns count as water (Entity.onInsideBubbleColumn resets fall distance)
                || feet.key().value().equals("bubble_column");
        if (inFluid || p.isFlying()) {
            s.highestY = Double.NEGATIVE_INFINITY;
            return;
        }
        if (!p.isOnGround()) {
            if (p.isFlyingWithElytra() && p.getVelocity().y() > -0.5) {
                // LivingEntity.checkFallDistanceAccumulation (decompile-verified): fallDistance
                // is force-capped to <=1 every tick the entity isn't in a fast vertical drop,
                // which is why a normal elytra glide never racks up enough fall distance to hurt
                // on landing. Translated into this project's peak-height tracking as capping the
                // peak to at most 1 block above the current position; a steep dive (vertical
                // speed <= -0.5) skips the cap and accumulates normally, same as real vanilla.
                s.highestY = Math.min(s.highestY, to.y() + 1);
            } else {
                s.highestY = Math.max(s.highestY, to.y());
            }
        } else {
            if (s.highestY > to.y() + 3.001) {
                float damage = (float) Math.floor(s.highestY - to.y() - 3);
                int featherFalling = dev.pointofpressure.minecom.data.Enchants.level(
                        p.getEquipment(net.minestom.server.entity.EquipmentSlot.BOOTS), "feather_falling");
                if (featherFalling > 0) damage *= Math.max(0, 1 - 0.12f * featherFalling);
                if (damage > 0) p.damage(DamageType.FALL, damage);
            }
            s.highestY = Double.NEGATIVE_INFINITY;
        }
    }

    // ------------------------------------------------------------------ eating

    private static void useItem(PlayerUseItemEvent e) {
        Food food = e.getItemStack().get(DataComponents.FOOD);
        if (food == null) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SURVIVAL && p.getFood() >= 20 && !food.canAlwaysEat()) {
            e.setCancelled(true);
        }
    }

    private static void finishEating(PlayerFinishItemUseEvent e) {
        ItemStack item = e.getItemStack();
        Food food = item.get(DataComponents.FOOD);
        if (food == null) return;
        Player p = e.getPlayer();

        p.setFood(Math.min(20, p.getFood() + food.nutrition()));
        p.setFoodSaturation(Math.min(p.getFood(),
                p.getFoodSaturation() + food.nutrition() * food.saturationModifier() * 2f));
        dev.pointofpressure.minecom.redstone.Vibrations.emit("eat", p.getPosition(), p);

        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack rest = item.consume(1);
            String key = item.material().key().value();
            if (key.endsWith("_stew") || key.endsWith("_soup")) {
                rest = rest.isAir() ? ItemStack.of(Material.BOWL) : rest;
            }
            p.setItemInHand(e.getHand(), rest);
            if ((key.endsWith("_stew") || key.endsWith("_soup")) && !rest.material().equals(Material.BOWL)) {
                p.getInventory().addItemStack(ItemStack.of(Material.BOWL));
            }
        }
    }

    // ------------------------------------------------------------------ death

    private static void death(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        // keep_inventory gamerule (Player.dropAllDeathLoot's dropEquipment gate)
        if (!dev.pointofpressure.minecom.GameRules.getBool("keep_inventory")) {
            var inventory = p.getInventory();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack stack = inventory.getItemStack(slot);
                if (stack.isAir()) continue;
                dropItem(p, stack, true);
            }
            inventory.clear();
        }
        // show_death_messages gamerule (ServerPlayer.die's broadcast gate)
        if (dev.pointofpressure.minecom.GameRules.getBool("show_death_messages")) {
            e.setChatMessage(Component.text(p.getUsername() + " died", NamedTextColor.GRAY));
        } else {
            e.setChatMessage(null);
        }
    }

    /** Spawn an item entity at the player's location (scatter=death) or thrown from the eyes (Q-drop). */
    public static void dropItem(Player p, ItemStack stack, boolean scatter) {
        ItemEntity entity = new ItemEntity(stack);
        entity.setPickupDelay(Duration.ofMillis(scatter ? 600 : 2000));
        Pos pos = p.getPosition();
        if (scatter) {
            entity.setInstance(p.getInstance(), pos.add(0, 0.5, 0));
            entity.setVelocity(new Vec(RANDOM.nextDouble() - 0.5, 3 + RANDOM.nextDouble() * 2,
                    RANDOM.nextDouble() - 0.5).mul(2));
        } else {
            entity.setInstance(p.getInstance(), pos.add(0, p.getEyeHeight() - 0.3, 0));
            Vec dir = pos.direction();
            entity.setVelocity(dir.mul(6).add(0, 1.5, 0));
        }
    }
}
