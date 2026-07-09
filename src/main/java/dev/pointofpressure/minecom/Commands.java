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
            var kind = ArgumentType.Word("mob").from("zombie", "spider", "skeleton", "creeper",
                    "cow", "pig", "sheep", "chicken", "zombified_piglin", "magma_cube", "blaze");
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
}
