package dev.pointofpressure.minecom.worldgen.vanilla;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.instance.block.Block;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A loaded vanilla structure template (.nbt) plus the exact transform math from
 * StructureTemplate. Reimplemented from the algorithm (not copied): position
 * transform, size/bounding-box under rotation, and jigsaw-block extraction with
 * the same (y,x,z) ordering vanilla's palette caches use. Jigsaw structures never
 * mirror, so only rotation is modelled here.
 */
public final class VTemplate {

    // -------------------------------------------------------------- geometry

    /** Six block faces, indexed like vanilla Direction for step math. */
    public enum Dir {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);
        public final int dx, dy, dz;
        Dir(int dx, int dy, int dz) { this.dx = dx; this.dy = dy; this.dz = dz; }

        public Dir opposite() {
            return switch (this) {
                case DOWN -> UP; case UP -> DOWN;
                case NORTH -> SOUTH; case SOUTH -> NORTH;
                case WEST -> EAST; case EAST -> WEST;
            };
        }
        /** Direction.getClockWise() about the Y axis. */
        public Dir clockWiseY() {
            return switch (this) {
                case NORTH -> EAST; case EAST -> SOUTH; case SOUTH -> WEST; case WEST -> NORTH;
                default -> this;
            };
        }
        public Dir counterClockWiseY() {
            return switch (this) {
                case NORTH -> WEST; case WEST -> SOUTH; case SOUTH -> EAST; case EAST -> NORTH;
                default -> this;
            };
        }
        public boolean horizontal() { return this == NORTH || this == SOUTH || this == WEST || this == EAST; }

        public static Dir byName(String s) {
            return switch (s) {
                case "down" -> DOWN; case "up" -> UP; case "north" -> NORTH;
                case "south" -> SOUTH; case "west" -> WEST; case "east" -> EAST;
                default -> throw new IllegalArgumentException("dir " + s);
            };
        }
    }

    /** Rotation about Y, matching net.minecraft Rotation semantics. */
    public enum Rot {
        NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90;

        /** Rotation.rotate(Direction). */
        public Dir rotate(Dir d) {
            if (!d.horizontal()) return d;
            return switch (this) {
                case CLOCKWISE_90 -> d.clockWiseY();
                case CLOCKWISE_180 -> d.opposite();
                case COUNTERCLOCKWISE_90 -> d.counterClockWiseY();
                default -> d;
            };
        }
        public String vanillaName() {
            return switch (this) {
                case NONE -> "NONE";
                case CLOCKWISE_90 -> "CLOCKWISE_90";
                case CLOCKWISE_180 -> "CLOCKWISE_180";
                case COUNTERCLOCKWISE_90 -> "COUNTERCLOCKWISE_90";
            };
        }
        public static final Rot[] VALUES = values();
    }

    public enum Joint { ROLLABLE, ALIGNED }

    /** StructureTemplate.transform(pos, NONE mirror, rotation, pivot). */
    public static int[] transform(int x, int y, int z, Rot rot, int pivotX, int pivotZ) {
        return switch (rot) {
            case COUNTERCLOCKWISE_90 -> new int[]{pivotX - pivotZ + z, y, pivotX + pivotZ - x};
            case CLOCKWISE_90 -> new int[]{pivotX + pivotZ - z, y, pivotZ - pivotX + x};
            case CLOCKWISE_180 -> new int[]{pivotX + pivotX - x, y, pivotZ + pivotZ - z};
            default -> new int[]{x, y, z};
        };
    }

    /**
     * StructureTemplate.transform(pos, mirror, rotation, pivot): mirror is applied BEFORE
     * rotation, negating the raw (pre-pivot) X coordinate — real vanilla's {@code Mirror.
     * FRONT_BACK} case ({@code Mirror.LEFT_RIGHT} negates Z instead, but no structure in this
     * project ever draws that value, so only FRONT_BACK's X-negation is implemented here).
     * Delegates to {@link #transform} unmirrored, so every existing caller is untouched.
     */
    public static int[] transformMirrored(int x, int y, int z, boolean mirrorFrontBack, Rot rot, int pivotX, int pivotZ) {
        return transform(mirrorFrontBack ? -x : x, y, z, rot, pivotX, pivotZ);
    }

    // -------------------------------------------------------------- records

    /** A placed jigsaw connector after rotation + translation. */
    public static final class RJigsaw {
        public final int x, y, z;                 // absolute connector position
        public final Dir front, top;              // rotated orientation
        public final Joint joint;
        public final String name, target, pool;
        public final int placementPriority, selectionPriority;

        RJigsaw(int x, int y, int z, Dir front, Dir top, Joint joint,
                String name, String target, String pool, int pp, int sp) {
            this.x = x; this.y = y; this.z = z; this.front = front; this.top = top;
            this.joint = joint; this.name = name; this.target = target; this.pool = pool;
            this.placementPriority = pp; this.selectionPriority = sp;
        }
    }

    /** One block cell in the template (base, unrotated). */
    public static final class BlockInfo {
        public final int x, y, z;
        public final Block state;
        public final CompoundBinaryTag nbt;       // block-entity nbt, or null
        public final boolean fullBlock;           // solid full-cube, no dynamic shape (for RNG ordering)
        BlockInfo(int x, int y, int z, Block state, CompoundBinaryTag nbt, boolean fullBlock) {
            this.x = x; this.y = y; this.z = z; this.state = state; this.nbt = nbt; this.fullBlock = fullBlock;
        }
    }

    /** A base (unrotated) jigsaw connector as parsed from the palette. */
    private static final class BaseJigsaw {
        final int x, y, z;
        final Dir front, top;
        final Joint joint;
        final String name, target, pool;
        final int placementPriority, selectionPriority;
        BaseJigsaw(int x, int y, int z, Dir front, Dir top, Joint joint,
                   String name, String target, String pool, int pp, int sp) {
            this.x = x; this.y = y; this.z = z; this.front = front; this.top = top;
            this.joint = joint; this.name = name; this.target = target; this.pool = pool;
            this.placementPriority = pp; this.selectionPriority = sp;
        }
    }

    // -------------------------------------------------------------- fields

    public final int sizeX, sizeY, sizeZ;
    public final List<BlockInfo> blocks;            // ordered: full, other, block-entities (each y,x,z)
    private final List<BaseJigsaw> baseJigsaws;     // sorted by (y,x,z)

    private VTemplate(int sx, int sy, int sz, List<BlockInfo> blocks, List<BaseJigsaw> jig) {
        this.sizeX = sx; this.sizeY = sy; this.sizeZ = sz; this.blocks = blocks; this.baseJigsaws = jig;
    }

    // -------------------------------------------------------------- loading

    private static final Map<String, VTemplate> CACHE = new HashMap<>();

    /** Load "minecraft:ancient_city/city_center/city_center_1" from resources (cached). */
    public static synchronized VTemplate load(String location) {
        VTemplate t = CACHE.get(location);
        if (t == null) { t = read(location); CACHE.put(location, t); }
        return t;
    }

    /** Empty template, returned for any missing resource (matches vanilla getOrCreate). */
    private static final VTemplate EMPTY = new VTemplate(0, 0, 0, new ArrayList<>(), new ArrayList<>());

    private static VTemplate read(String location) {
        String path = "/vanilla/structure/" + location.substring(location.indexOf(':') + 1) + ".nbt";
        CompoundBinaryTag root;
        try (InputStream in = VTemplate.class.getResourceAsStream(path)) {
            if (in == null) return EMPTY;   // vanilla treats a missing template as an empty one
            root = BinaryTagIO.unlimitedReader().read(in, BinaryTagIO.Compression.GZIP);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load " + path, e);
        }
        ListBinaryTag sizeTag = root.getList("size");
        int sx = sizeTag.getInt(0), sy = sizeTag.getInt(1), sz = sizeTag.getInt(2);

        // palette: single "palette" (jigsaw structures don't use random palettes in these families)
        ListBinaryTag paletteList = root.getList("palette");
        if (paletteList.size() == 0) {
            ListBinaryTag palettes = root.getList("palettes");
            if (palettes.size() > 0) paletteList = palettes.getList(0);
        }
        int n = paletteList.size();
        Block[] palette = new Block[n];
        String[] paletteName = new String[n];
        for (int i = 0; i < n; i++) {
            CompoundBinaryTag e = paletteList.getCompound(i);
            String name = e.getString("Name");
            paletteName[i] = name;
            Block b = Block.fromKey(name);
            if (b == null) b = Block.STONE;
            CompoundBinaryTag props = e.getCompound("Properties");
            if (props.keySet().size() > 0) {
                Map<String, String> pm = new HashMap<>();
                for (String k : props.keySet()) pm.put(k, props.getString(k));
                b = b.withProperties(pm);
            }
            palette[i] = b;
        }

        // blocks, split into full/other/blockEntity like buildInfoList
        List<BlockInfo> full = new ArrayList<>(), other = new ArrayList<>(), be = new ArrayList<>();
        List<BaseJigsaw> jigsaws = new ArrayList<>();
        ListBinaryTag blockList = root.getList("blocks");
        for (int i = 0; i < blockList.size(); i++) {
            CompoundBinaryTag blk = blockList.getCompound(i);
            ListBinaryTag posTag = blk.getList("pos");
            int px = posTag.getInt(0), py = posTag.getInt(1), pz = posTag.getInt(2);
            int stateIdx = blk.getInt("state", 0);
            Block state = palette[stateIdx];
            String name = paletteName[stateIdx];
            CompoundBinaryTag nbt = blk.keySet().contains("nbt") ? blk.getCompound("nbt") : null;

            boolean full3 = nbt == null && isFullBlock(name, state);
            BlockInfo info = new BlockInfo(px, py, pz, state, nbt, full3);
            if (nbt != null) {
                be.add(info);
                if (name.equals("minecraft:jigsaw")) jigsaws.add(parseJigsaw(px, py, pz, state, nbt));
            } else if (full3) {
                full.add(info);
            } else {
                other.add(info);
            }
        }
        Comparator<BlockInfo> byYXZ = Comparator.<BlockInfo>comparingInt(o -> o.y)
                .thenComparingInt(o -> o.x).thenComparingInt(o -> o.z);
        full.sort(byYXZ); other.sort(byYXZ); be.sort(byYXZ);
        List<BlockInfo> ordered = new ArrayList<>(full.size() + other.size() + be.size());
        ordered.addAll(full); ordered.addAll(other); ordered.addAll(be);

        // jigsaws cached order = block-entity order (y,x,z)
        jigsaws.sort(Comparator.<BaseJigsaw>comparingInt(o -> o.y)
                .thenComparingInt(o -> o.x).thenComparingInt(o -> o.z));

        return new VTemplate(sx, sy, sz, ordered, jigsaws);
    }

    private static BaseJigsaw parseJigsaw(int x, int y, int z, Block state, CompoundBinaryTag nbt) {
        // orientation is FrontAndTop "front_top" (each a single cardinal/vertical word)
        String orientation = state.getProperty("orientation");
        String[] parts = orientation.split("_");
        Dir front = Dir.byName(parts[0]);
        Dir top = Dir.byName(parts[1]);
        Joint joint;
        if (nbt.keySet().contains("joint")) {
            joint = nbt.getString("joint").equals("aligned") ? Joint.ALIGNED : Joint.ROLLABLE;
        } else {
            joint = front.horizontal() ? Joint.ALIGNED : Joint.ROLLABLE;
        }
        String name = nbt.getString("name", "minecraft:empty");
        String target = nbt.getString("target", "minecraft:empty");
        String pool = nbt.getString("pool", "minecraft:empty");
        int pp = nbt.getInt("placement_priority", 0);
        int sp = nbt.getInt("selection_priority", 0);
        return new BaseJigsaw(x, y, z, front, top, joint, name, target, pool, pp, sp);
    }

    /** A subset of blocks that are solid full cubes with a static shape (RNG-ordering split). */
    private static boolean isFullBlock(String name, Block state) {
        var reg = state.registry();
        if (reg == null) return false;
        // vanilla: !hasDynamicShape() && isCollisionShapeFullBlock. Approximate with the
        // solid + full-occlusion signals Minestom exposes; refined per-name where needed.
        return VBlockShapes.isFullCube(name, state);
    }

    // -------------------------------------------------------------- queries

    public int getGroundLevelDelta() { return 1; }

    public int sizeX(Rot rot) { return (rot == Rot.CLOCKWISE_90 || rot == Rot.COUNTERCLOCKWISE_90) ? sizeZ : sizeX; }
    public int sizeZ(Rot rot) { return (rot == Rot.CLOCKWISE_90 || rot == Rot.COUNTERCLOCKWISE_90) ? sizeX : sizeZ; }

    /** BoundingBox for this template at position with rotation (pivot ZERO). Returns [minX,minY,minZ,maxX,maxY,maxZ]. */
    public int[] boundingBox(int posX, int posY, int posZ, Rot rot) {
        int[] c1 = transform(0, 0, 0, rot, 0, 0);
        int[] c2 = transform(sizeX - 1, sizeY - 1, sizeZ - 1, rot, 0, 0);
        int minX = Math.min(c1[0], c2[0]) + posX, minY = Math.min(c1[1], c2[1]) + posY, minZ = Math.min(c1[2], c2[2]) + posZ;
        int maxX = Math.max(c1[0], c2[0]) + posX, maxY = Math.max(c1[1], c2[1]) + posY, maxZ = Math.max(c1[2], c2[2]) + posZ;
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** StructureTemplate.getJigsaws(position, rotation): rotate + translate each connector. Base order (y,x,z). */
    public List<RJigsaw> getJigsaws(int posX, int posY, int posZ, Rot rot) {
        List<RJigsaw> out = new ArrayList<>(baseJigsaws.size());
        for (BaseJigsaw j : baseJigsaws) {
            int[] p = transform(j.x, j.y, j.z, rot, 0, 0);
            out.add(new RJigsaw(p[0] + posX, p[1] + posY, p[2] + posZ,
                    rot.rotate(j.front), rot.rotate(j.top), j.joint,
                    j.name, j.target, j.pool, j.placementPriority, j.selectionPriority));
        }
        return out;
    }
}
