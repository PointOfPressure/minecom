package dev.pointofpressure.minecom.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;

/**
 * Directional placement rules (Minestom ships none): furnaces/chests face the
 * player, logs align to the clicked face, stairs and doors follow the look
 * direction, doors get their upper half, beds get their head block.
 */
public final class Placement {
    private Placement() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockPlaceEvent.class, Placement::place);
    }

    private static void place(PlayerBlockPlaceEvent e) {
        Block block = e.getBlock();
        String key = block.key().value();
        Player player = e.getPlayer();
        String look = horizontalFacing(player.getPosition().yaw());
        String opposite = opposite(look);

        // player-placed leaves never decay
        if (key.endsWith("_leaves")) {
            e.setBlock(block.withProperty("persistent", "true"));
            return;
        }

        // pillars: align axis to the clicked face
        if (block.getProperty("axis") != null) {
            e.setBlock(block.withProperty("axis", switch (e.getBlockFace()) {
                case EAST, WEST -> "x";
                case NORTH, SOUTH -> "z";
                default -> "y";
            }));
            return;
        }

        if (key.endsWith("_door")) {
            Block lower = block.withProperty("facing", look).withProperty("half", "lower");
            e.setBlock(lower);
            Point above = e.getBlockPosition().add(0, 1, 0);
            e.getInstance().scheduler().scheduleNextTick(() -> {
                if (e.getInstance().getBlock(above).isAir()) {
                    e.getInstance().setBlock(above, lower.withProperty("half", "upper"));
                }
            });
            return;
        }

        if (key.endsWith("_bed")) {
            Block foot = block.withProperty("facing", look).withProperty("part", "foot");
            e.setBlock(foot);
            Point head = offset(e.getBlockPosition(), look);
            e.getInstance().scheduler().scheduleNextTick(() -> {
                if (e.getInstance().getBlock(head).isAir()) {
                    e.getInstance().setBlock(head, foot.withProperty("part", "head"));
                }
            });
            return;
        }

        if (key.endsWith("_stairs")) {
            String half = e.getBlockFace() == BlockFace.BOTTOM
                    || (e.getBlockFace() != BlockFace.TOP && e.getCursorPosition().y() > 0.5)
                    ? "top" : "bottom";
            e.setBlock(block.withProperty("facing", look).withProperty("half", half));
            return;
        }

        if (key.endsWith("_slab")) {
            String type = e.getBlockFace() == BlockFace.BOTTOM
                    || (e.getBlockFace() != BlockFace.TOP && e.getCursorPosition().y() > 0.5)
                    ? "top" : "bottom";
            e.setBlock(block.withProperty("type", type));
            return;
        }

        // pistons/dispensers/droppers/observers: 6-directional, face the player
        if (key.equals("piston") || key.equals("sticky_piston") || key.equals("dispenser")
                || key.equals("dropper") || key.equals("observer")) {
            float pitch = player.getPosition().pitch();
            String facing6 = pitch > 60 ? "up" : pitch < -60 ? "down" : opposite;
            e.setBlock(block.withProperty("facing", facing6));
            return;
        }

        // hoppers: output points at the block you clicked
        if (key.equals("hopper")) {
            String facing = switch (e.getBlockFace()) {
                case TOP, BOTTOM -> "down";
                case NORTH -> "north";
                case SOUTH -> "south";
                case EAST -> "east";
                default -> "west";
            };
            // clicking a side face means the hopper spout points back into that block
            if (e.getBlockFace() != BlockFace.TOP && e.getBlockFace() != BlockFace.BOTTOM) {
                facing = switch (e.getBlockFace()) {
                    case NORTH -> "south";
                    case SOUTH -> "north";
                    case EAST -> "west";
                    default -> "east";
                };
            }
            e.setBlock(block.withProperty("facing", facing).withProperty("enabled", "true"));
            return;
        }

        // torches: wall variants when placed on a side face
        if (key.equals("torch") || key.equals("soul_torch") || key.equals("redstone_torch")) {
            if (e.getBlockFace() != BlockFace.TOP && e.getBlockFace() != BlockFace.BOTTOM) {
                Block wall = switch (key) {
                    case "soul_torch" -> Block.SOUL_WALL_TORCH;
                    case "redstone_torch" -> Block.REDSTONE_WALL_TORCH;
                    default -> Block.WALL_TORCH;
                };
                String facing = e.getBlockFace().name().toLowerCase();
                e.setBlock(key.equals("redstone_torch")
                        ? wall.withProperty("facing", facing).withProperty("lit", "true")
                        : wall.withProperty("facing", facing));
            }
            return;
        }

        // levers and buttons: floor/ceiling/wall attachment
        if (key.equals("lever") || key.endsWith("_button")) {
            Block placed = switch (e.getBlockFace()) {
                case TOP -> block.withProperty("face", "floor").withProperty("facing", look);
                case BOTTOM -> block.withProperty("face", "ceiling").withProperty("facing", look);
                default -> block.withProperty("face", "wall")
                        .withProperty("facing", e.getBlockFace().name().toLowerCase());
            };
            e.setBlock(placed);
            return;
        }

        // repeaters/comparators: input side toward the player
        if (key.equals("repeater") || key.equals("comparator")) {
            e.setBlock(block.withProperty("facing", opposite));
            return;
        }

        // chests/trapped chests: face the player, and auto-merge into a double chest with a
        // same-key, same-facing, still-single neighbor immediately to either side (ChestBlock.
        // getChestType, decompile-verified) — clockwise neighbor becomes our RIGHT half (we
        // become LEFT), counter-clockwise neighbor becomes our LEFT half (we become RIGHT).
        if (key.equals("chest") || key.equals("trapped_chest")) {
            placeChest(e, block, opposite, key);
            return;
        }

        // blocks that face the player (furnace, chest, barrel-style front)
        if (block.getProperty("facing") != null) {
            String facing = block.getProperty("facing");
            // only touch horizontal-facing blocks; leave 6-directional defaults alone unless horizontal
            if (facing.equals("north") || facing.equals("south")
                    || facing.equals("east") || facing.equals("west")) {
                e.setBlock(block.withProperty("facing", opposite));
            }
        }
    }

    private static void placeChest(PlayerBlockPlaceEvent e, Block block, String facing, String key) {
        var instance = e.getInstance();
        Point pos = e.getBlockPosition();
        String type = "single";

        Point cwPos = offset(pos, clockwise(facing));
        Block cw = instance.getBlock(cwPos);
        if (cw.key().value().equals(key) && facing.equals(cw.getProperty("facing"))
                && "single".equals(cw.getProperty("type"))) {
            type = "left";
            instance.setBlock(cwPos, cw.withProperty("type", "right"));
        } else {
            Point ccwPos = offset(pos, counterClockwise(facing));
            Block ccw = instance.getBlock(ccwPos);
            if (ccw.key().value().equals(key) && facing.equals(ccw.getProperty("facing"))
                    && "single".equals(ccw.getProperty("type"))) {
                type = "right";
                instance.setBlock(ccwPos, ccw.withProperty("type", "left"));
            }
        }
        e.setBlock(block.withProperty("facing", facing).withProperty("type", type));
    }

    /** The horizontal direction the player is looking. */
    static String horizontalFacing(float yaw) {
        float rot = ((yaw % 360) + 360) % 360;
        if (rot >= 315 || rot < 45) return "south";
        if (rot < 135) return "west";
        if (rot < 225) return "north";
        return "east";
    }

    static String opposite(String facing) {
        return switch (facing) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
    }

    static String clockwise(String facing) {
        return switch (facing) {
            case "north" -> "east";
            case "east" -> "south";
            case "south" -> "west";
            default -> "north";
        };
    }

    static String counterClockwise(String facing) {
        return switch (facing) {
            case "north" -> "west";
            case "west" -> "south";
            case "south" -> "east";
            default -> "north";
        };
    }

    static Point offset(Point pos, String facing) {
        return switch (facing) {
            case "north" -> pos.add(0, 0, -1);
            case "south" -> pos.add(0, 0, 1);
            case "east" -> pos.add(1, 0, 0);
            default -> pos.add(-1, 0, 0);
        };
    }
}
