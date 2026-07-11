package dev.pointofpressure.minecom;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentEnum;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;

public final class Commands {
    private Commands() {}

    public static final class Gamemode extends Command {
        public Gamemode() {
            super("gamemode", "gm");
            var mode = ArgumentType.Enum("mode", GameMode.class)
                    .setFormat(ArgumentEnum.Format.LOWER_CASED);
            addSyntax((sender, context) -> {
                if (sender instanceof Player player) {
                    player.setGameMode(context.get(mode));
                    player.sendMessage(Component.text("Game mode set to "
                            + context.get(mode).name().toLowerCase(), NamedTextColor.GRAY));
                }
            }, mode);
        }
    }

    public static final class Tp extends Command {
        public Tp() {
            super("tp");
            var x = ArgumentType.Double("x");
            var y = ArgumentType.Double("y");
            var z = ArgumentType.Double("z");
            addSyntax((sender, context) -> {
                if (sender instanceof Player player) {
                    player.teleport(new Pos(context.get(x), context.get(y), context.get(z)));
                }
            }, x, y, z);

            var target = ArgumentType.Entity("target").singleEntity(true).onlyPlayers(true);
            addSyntax((sender, context) -> {
                if (!(sender instanceof Player player)) return;
                Player destination = context.get(target).findFirstPlayer(sender);
                if (destination == null) return;
                player.teleport(destination.getPosition());
            }, target);
        }
    }

    public static final class Give extends Command {
        public Give() {
            super("give");
            var item = ArgumentType.ItemStack("item");
            var amount = ArgumentType.Integer("amount").between(1, 64).setDefaultValue(1);
            addSyntax((sender, context) -> {
                if (sender instanceof Player player) {
                    ItemStack stack = context.get(item).withAmount(context.get(amount));
                    player.getInventory().addItemStack(stack);
                }
            }, item, amount);
        }
    }

    public static final class DifficultyCmd extends Command {
        public DifficultyCmd() {
            super("difficulty");
            var level = ArgumentType.Enum("level", Difficulty.class)
                    .setFormat(ArgumentEnum.Format.LOWER_CASED);
            addSyntax((sender, context) -> {
                Difficulty picked = context.get(level);
                Difficulty.set(picked);
                Persist.save();
                sender.sendMessage(Component.text("Difficulty set to "
                        + picked.name().toLowerCase(), NamedTextColor.GRAY));
            }, level);
            setDefaultExecutor((sender, context) -> sender.sendMessage(Component.text(
                    "Difficulty is " + Difficulty.current().name().toLowerCase(), NamedTextColor.GRAY)));
        }
    }

    public static final class Time extends Command {
        public Time(Instance instance) {
            super("time");
            var set = ArgumentType.Literal("set");
            var value = ArgumentType.Word("value").from("day", "noon", "night", "midnight");
            addSyntax((sender, context) -> {
                long time = switch (context.get(value)) {
                    case "noon" -> 6000;
                    case "night" -> 13000;
                    case "midnight" -> 18000;
                    default -> 1000;
                };
                long current = instance.getTime();
                long delta = (time - current % 24000 + 24000) % 24000;
                instance.setTime(current + delta);
                sender.sendMessage(Component.text("Time set to " + context.get(value), NamedTextColor.GRAY));
            }, set, value);
        }
    }

    public static final class Spawn extends Command {
        public Spawn(Pos spawn) {
            super("spawn");
            setDefaultExecutor((sender, context) -> {
                if (sender instanceof Player player) player.teleport(spawn);
            });
        }
    }

    public static final class WeatherCmd extends Command {
        public WeatherCmd(Instance instance) {
            super("weather");
            var value = ArgumentType.Word("value").from("clear", "rain");
            addSyntax((sender, context) -> {
                boolean rain = context.get(value).equals("rain");
                dev.pointofpressure.minecom.survival.WeatherCycle.setRaining(instance, rain);
                sender.sendMessage(Component.text("Weather set to " + context.get(value),
                        NamedTextColor.GRAY));
            }, value);
        }
    }

    /** Flood cleanup: strips flowing water and any water above sea level; "all" also removes sources below. */
    public static final class Drain extends Command {
        public Drain() {
            super("drain");
            var radius = ArgumentType.Integer("radius").between(4, 96).setDefaultValue(48);
            var all = ArgumentType.Word("mode").from("all").setDefaultValue("surface");
            addSyntax((sender, context) -> {
                if (!(sender instanceof Player player)) return;
                int r = context.get(radius);
                boolean everything = context.get(all).equals("all");
                var instance = player.getInstance();
                var pos = player.getPosition();
                int removed = 0;
                for (int x = pos.blockX() - r; x <= pos.blockX() + r; x++) {
                    for (int z = pos.blockZ() - r; z <= pos.blockZ() + r; z++) {
                        if (!instance.isChunkLoaded(x >> 4, z >> 4)) continue;
                        for (int y = Math.max(-60, pos.blockY() - 40); y <= Math.min(200, pos.blockY() + 60); y++) {
                            var block = instance.getBlock(x, y, z);
                            if (!block.key().value().equals("water")) continue;
                            String level = block.getProperty("level");
                            boolean flowing = level != null && !level.equals("0");
                            if (flowing || y > 62 || everything) {
                                instance.setBlock(x, y, z, net.minestom.server.instance.block.Block.AIR);
                                removed++;
                            }
                        }
                    }
                }
                player.sendMessage(Component.text("Drained " + removed + " water blocks.",
                        NamedTextColor.GRAY));
            }, radius, all);
        }
    }

    public static final class Summon extends Command {
        public Summon(Instance instance) {
            super("summon");
            var kind = ArgumentType.Word("mob").from("zombie", "spider", "cave_spider", "endermite", "skeleton", "creeper",
                    "cow", "pig", "sheep", "chicken", "zombified_piglin", "magma_cube", "blaze", "illusioner", "piglin_brute", "zoglin", "giant");
            addSyntax((sender, context) -> {
                if (sender instanceof Player player) {
                    dev.pointofpressure.minecom.mobs.Mobs.spawn(context.get(kind), instance,
                            player.getPosition().add(2, 0, 0));
                }
            }, kind);
        }
    }

    public static final class Enchant extends Command {
        public Enchant() {
            super("enchant");
            var name = ArgumentType.Word("enchantment").from("sharpness", "efficiency", "fortune",
                    "silk_touch", "unbreaking", "protection", "looting", "knockback", "power");
            var level = ArgumentType.Integer("level").between(1, 5).setDefaultValue(1);
            addSyntax((sender, context) -> {
                if (!(sender instanceof Player player)) return;
                var key = dev.pointofpressure.minecom.data.Enchants.byName(context.get(name));
                if (key == null) return;
                ItemStack held = player.getItemInMainHand();
                if (held.isAir()) return;
                player.setItemInMainHand(
                        dev.pointofpressure.minecom.data.Enchants.with(held, key, context.get(level)));
                player.sendMessage(Component.text("Enchanted with " + context.get(name)
                        + " " + context.get(level), NamedTextColor.GRAY));
            }, name, level);
        }
    }

    public static final class KillMe extends Command {
        public KillMe() {
            super("kill");
            setDefaultExecutor((sender, context) -> {
                if (sender instanceof Player player) player.kill();
            });
            var target = ArgumentType.Entity("target");
            addSyntax((sender, context) -> {
                for (var entity : context.get(target).find(sender)) {
                    if (entity instanceof net.minestom.server.entity.LivingEntity living) living.kill();
                    else entity.remove();
                }
            }, target);
        }
    }

    /** Travel to/from The End (spawns an obsidian arrival platform near the central island). */
    public static final class End extends Command {
        public End(net.minestom.server.instance.InstanceContainer overworld, Pos spawn) {
            super("end");
            setDefaultExecutor((sender, context) -> {
                if (!(sender instanceof Player player)) return;
                var end = dev.pointofpressure.minecom.Bootstrap.endOf(overworld);
                if (end == null) return;
                if (player.getInstance() == end) {
                    player.setInstance(overworld, spawn);
                    return;
                }
                end.loadChunk(6, 0).thenRun(() -> {
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            end.setBlock(100 + dx, 48, dz, net.minestom.server.instance.block.Block.OBSIDIAN);
                            for (int dy = 1; dy <= 3; dy++) {
                                end.setBlock(100 + dx, 48 + dy, dz, net.minestom.server.instance.block.Block.AIR);
                            }
                        }
                    }
                    player.setInstance(end, new Pos(100.5, 49, 0.5));
                    dev.pointofpressure.minecom.mobs.EnderDragonFight.startIfNeeded(end);
                });
            });
        }
    }

    /** Starts a bounded raid (3 waves of pillagers/vindicators/evokers) at the nearest village bell, or the player. */
    public static final class RaidCmd extends Command {
        public RaidCmd() {
            super("raid");
            setDefaultExecutor((sender, context) -> {
                if (!(sender instanceof Player player)) return;
                var instance = player.getInstance();
                Pos center = findNearbyBell(instance, player.getPosition(), 48);
                if (center == null) center = player.getPosition();
                boolean started = dev.pointofpressure.minecom.mobs.Raid.start(instance, center);
                sender.sendMessage(Component.text(started ? "Raid started!" : "A raid is already active here.",
                        started ? NamedTextColor.RED : NamedTextColor.GRAY));
            });
        }

        private static Pos findNearbyBell(Instance instance, Pos near, int radius) {
            int bx = near.blockX(), by = near.blockY(), bz = near.blockZ();
            for (int x = bx - radius; x <= bx + radius; x++) {
                for (int z = bz - radius; z <= bz + radius; z++) {
                    if (!instance.isChunkLoaded(x >> 4, z >> 4)) continue;
                    for (int y = by - 12; y <= by + 12; y++) {
                        if (instance.getBlock(x, y, z).compare(net.minestom.server.instance.block.Block.BELL)) {
                            return new Pos(x + 0.5, y, z + 0.5);
                        }
                    }
                }
            }
            return null;
        }
    }

    /** Reports the nearest stronghold's chunk position, like vanilla's /locate structure stronghold. */
    public static final class LocateStrongholdCmd extends Command {
        public LocateStrongholdCmd() {
            super("locatestronghold");
            setDefaultExecutor((sender, context) -> {
                if (!(sender instanceof Player player)) return;
                int[] pos = dev.pointofpressure.minecom.worldgen.Strongholds.nearestTo(
                        player.getPosition().blockX() >> 4, player.getPosition().blockZ() >> 4);
                if (pos == null) {
                    sender.sendMessage(Component.text("No stronghold found (flat world?).", NamedTextColor.GRAY));
                    return;
                }
                int bx = (pos[0] << 4) + 8, bz = (pos[1] << 4) + 8;
                sender.sendMessage(Component.text("Nearest stronghold: " + bx + ", ~20, " + bz,
                        NamedTextColor.LIGHT_PURPLE));
            });
        }
    }

    /** Reports the world seed, like vanilla's /seed. */
    public static final class SeedCmd extends Command {
        public SeedCmd() {
            super("seed");
            setDefaultExecutor((sender, context) -> {
                var gen = dev.pointofpressure.minecom.Bootstrap.vanillaGen();
                long seed = gen == null ? 0L : gen.seed();
                sender.sendMessage(Component.text("Seed: " + seed, NamedTextColor.LIGHT_PURPLE));
            });
        }
    }

    /** Applies a potion effect to the target(s), like vanilla's /effect give. */
    public static final class EffectCmd extends Command {
        public EffectCmd() {
            super("effect");
            var target = ArgumentType.Entity("target");
            var effect = ArgumentType.Word("effect");
            var duration = ArgumentType.Integer("seconds").between(1, 1000000).setDefaultValue(30);
            var amplifier = ArgumentType.Integer("amplifier").between(0, 255).setDefaultValue(0);
            addSyntax((sender, context) -> {
                String effectKey = context.get(effect);
                var potionEffect = net.minestom.server.potion.PotionEffect.fromKey(
                        effectKey.contains(":") ? effectKey : "minecraft:" + effectKey);
                if (potionEffect == null) {
                    sender.sendMessage(Component.text("Unknown effect: " + context.get(effect), NamedTextColor.RED));
                    return;
                }
                for (var entity : context.get(target).find(sender)) {
                    if (!(entity instanceof net.minestom.server.entity.LivingEntity living)) continue;
                    living.addEffect(new net.minestom.server.potion.Potion(potionEffect,
                            context.get(amplifier), context.get(duration) * 20));
                }
            }, target, effect, duration, amplifier);
        }
    }

    /** Places a single block, like vanilla's /setblock. */
    public static final class SetBlockCmd extends Command {
        public SetBlockCmd(Instance instance) {
            super("setblock");
            var x = ArgumentType.Integer("x");
            var y = ArgumentType.Integer("y");
            var z = ArgumentType.Integer("z");
            var block = ArgumentType.BlockState("block");
            addSyntax((sender, context) -> instance.setBlock(context.get(x), context.get(y), context.get(z),
                    context.get(block)), x, y, z, block);
        }
    }

    /** Fills a bounded cuboid region, like vanilla's /fill (capped at 32768 blocks, same as vanilla). */
    public static final class FillCmd extends Command {
        private static final int MAX_BLOCKS = 32768;

        public FillCmd(Instance instance) {
            super("fill");
            var x1 = ArgumentType.Integer("x1");
            var y1 = ArgumentType.Integer("y1");
            var z1 = ArgumentType.Integer("z1");
            var x2 = ArgumentType.Integer("x2");
            var y2 = ArgumentType.Integer("y2");
            var z2 = ArgumentType.Integer("z2");
            var block = ArgumentType.BlockState("block");
            addSyntax((sender, context) -> {
                int minX = Math.min(context.get(x1), context.get(x2)), maxX = Math.max(context.get(x1), context.get(x2));
                int minY = Math.min(context.get(y1), context.get(y2)), maxY = Math.max(context.get(y1), context.get(y2));
                int minZ = Math.min(context.get(z1), context.get(z2)), maxZ = Math.max(context.get(z1), context.get(z2));
                long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
                if (volume > MAX_BLOCKS) {
                    sender.sendMessage(Component.text("Too many blocks (" + volume + " > " + MAX_BLOCKS + ").",
                            NamedTextColor.RED));
                    return;
                }
                var b = context.get(block);
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) instance.setBlock(x, y, z, b);
                    }
                }
                sender.sendMessage(Component.text("Filled " + volume + " blocks.", NamedTextColor.GRAY));
            }, x1, y1, z1, x2, y2, z2, block);
        }
    }

    /** Grants raw XP points to the target(s), like vanilla's /xp add <target> <amount> points. */
    public static final class XpCmd extends Command {
        public XpCmd() {
            super("xp");
            var target = ArgumentType.Entity("target").onlyPlayers(true);
            var amount = ArgumentType.Integer("amount");
            addSyntax((sender, context) -> {
                for (var entity : context.get(target).find(sender)) {
                    if (entity instanceof Player player) {
                        dev.pointofpressure.minecom.survival.Experience.add(player, context.get(amount));
                    }
                }
            }, target, amount);
        }
    }

    /** Clears the target's (or, with no target, the sender's own) inventory, like vanilla's /clear. */
    public static final class ClearCmd extends Command {
        public ClearCmd() {
            super("clear");
            setDefaultExecutor((sender, context) -> {
                if (sender instanceof Player player) player.getInventory().clear();
            });
            var target = ArgumentType.Entity("target").onlyPlayers(true);
            addSyntax((sender, context) -> {
                for (var entity : context.get(target).find(sender)) {
                    if (entity instanceof Player player) player.getInventory().clear();
                }
            }, target);
        }
    }
}
