package dev.pointofpressure.minecom.worldgen.vanilla;

import dev.pointofpressure.minecom.worldgen.vanilla.VTemplate.Dir;
import dev.pointofpressure.minecom.worldgen.vanilla.VTemplate.Rot;
import net.minestom.server.instance.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * Rotates a Minestom block state by a structure {@link Rot}, matching vanilla
 * {@code BlockState.rotate(Rotation)} via generic directional-property remapping:
 * {@code facing}, {@code axis}, {@code rotation} (0-15), and the cardinal
 * multiface booleans (walls/fences/panes/redstone/sculk_vein/glow_lichen…).
 * Structures never mirror, so only rotation is handled.
 */
public final class VBlockRotate {
    private VBlockRotate() {}

    public static Block rotate(Block b, Rot rot) {
        if (rot == Rot.NONE) return b;
        Map<String, String> props = b.properties();
        if (props.isEmpty()) return b;
        Map<String, String> out = null;

        // facing (n/e/s/w rotate; up/down unchanged)
        String facing = props.get("facing");
        if (facing != null) {
            String nf = rotateDir(facing, rot);
            if (!nf.equals(facing)) { out = copy(out, props); out.put("facing", nf); }
        }

        // axis (x<->z on 90° rotations)
        String axis = props.get("axis");
        if (axis != null && (rot == Rot.CLOCKWISE_90 || rot == Rot.COUNTERCLOCKWISE_90)) {
            if (axis.equals("x")) { out = copy(out, props); out.put("axis", "z"); }
            else if (axis.equals("z")) { out = copy(out, props); out.put("axis", "x"); }
        }

        // rotation 0-15 (signs/banners/skulls)
        String rotation = props.get("rotation");
        if (rotation != null) {
            int val = Integer.parseInt(rotation);
            int nv = rotate16(val, rot);
            if (nv != val) { out = copy(out, props); out.put("rotation", Integer.toString(nv)); }
        }

        // cardinal multiface booleans (walls/fences/panes/redstone/vine/sculk_vein/glow_lichen)
        if (props.containsKey("north") || props.containsKey("east") || props.containsKey("south") || props.containsKey("west")) {
            String n = props.get("north"), e = props.get("east"), s = props.get("south"), w = props.get("west");
            String[] src = {n, e, s, w};                     // indexed by Dir order N,E,S,W
            Dir[] cards = {Dir.NORTH, Dir.EAST, Dir.SOUTH, Dir.WEST};
            Map<Dir, String> remapped = new HashMap<>();
            for (int i = 0; i < 4; i++) if (src[i] != null) remapped.put(rot.rotate(cards[i]), src[i]);
            out = copy(out, props);
            for (Dir d : cards) {
                String key = d.name().toLowerCase();
                String v = remapped.get(d);
                if (v != null) out.put(key, v);
            }
        }

        return out == null ? b : b.withProperties(out);
    }

    private static Map<String, String> copy(Map<String, String> out, Map<String, String> props) {
        return out != null ? out : new HashMap<>(props);
    }

    private static String rotateDir(String dir, Rot rot) {
        Dir d = switch (dir) {
            case "north" -> Dir.NORTH; case "south" -> Dir.SOUTH;
            case "east" -> Dir.EAST; case "west" -> Dir.WEST;
            default -> null; // up/down or non-directional value
        };
        if (d == null) return dir;
        return rot.rotate(d).name().toLowerCase();
    }

    /** Rotation.rotate(value, 16). */
    private static int rotate16(int value, Rot rot) {
        int steps = switch (rot) {
            case CLOCKWISE_90 -> 4;
            case CLOCKWISE_180 -> 8;
            case COUNTERCLOCKWISE_90 -> 12;
            default -> 0;
        };
        return (value + steps) % 16;
    }
}
