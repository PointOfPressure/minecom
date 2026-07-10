package dev.pointofpressure.minecom.worldgen.vanilla;

import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Ocean monument room-graph generation (real vanilla `OceanMonumentPieces.MonumentBuilding.
 * generateRoomGraph` + its `MonumentRoomFitter` chain). Reimplemented from the algorithm, not
 * copied. This is the FIRST increment of ocean monument support — it produces the real room
 * layout (a 5x3x5 grid maze-CLOSING algorithm, the opposite of a maze-carving walk: it starts
 * fully connected and randomly severs doorways while a connectivity check keeps every room
 * reachable from the source) and assigns each room its real shape ({@link Shape}), but does NOT
 * yet render any blocks — {@link dev.pointofpressure.minecom.worldgen.NetherGen fortress} and
 * {@link VStrongholdGen stronghold} both preceded their own geometry ports with a verified
 * algorithm-only stage; this follows the same precedent. Geometry/decoration is a follow-up
 * increment (see the project memory for the full room-type list and outer-shell constants
 * already extracted from the decompile).
 */
public final class VMonumentGen {

    private VMonumentGen() {}

    /** Real vanilla Direction.get3DDataValue() order: DOWN,UP,NORTH,SOUTH,WEST,EAST (=ordinal). */
    public enum Dir3 {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);
        public final int dx, dy, dz;
        Dir3(int dx, int dy, int dz) { this.dx = dx; this.dy = dy; this.dz = dz; }
        public Dir3 opposite() {
            return switch (this) {
                case DOWN -> UP; case UP -> DOWN; case NORTH -> SOUTH; case SOUTH -> NORTH; case WEST -> EAST; case EAST -> WEST;
            };
        }
        public static final Dir3[] VALUES = values();
    }

    public enum Shape { CORE, ENTRY, SIMPLE, SIMPLE_TOP, DOUBLE_X, DOUBLE_Y, DOUBLE_Z, DOUBLE_XY, DOUBLE_YZ, ROOF, LEFT_WING, RIGHT_WING }

    public static final class RoomDefinition {
        public final int index;
        public final RoomDefinition[] connections = new RoomDefinition[6];
        public final boolean[] hasOpening = new boolean[6];
        public boolean claimed;
        public boolean isSource;
        public Shape shape;
        private int scanIndex;

        RoomDefinition(int index) { this.index = index; }

        void setConnection(Dir3 dir, RoomDefinition other) {
            connections[dir.ordinal()] = other;
            other.connections[dir.opposite().ordinal()] = this;
        }

        void updateOpenings() {
            for (int i = 0; i < 6; i++) hasOpening[i] = connections[i] != null;
        }

        /** Real findSource: a scanIndex-marked flood-fill back to the source room. */
        boolean findSource(int scanIndex) {
            if (isSource) return true;
            this.scanIndex = scanIndex;
            for (int i = 0; i < 6; i++) {
                if (connections[i] != null && hasOpening[i] && connections[i].scanIndex != scanIndex && connections[i].findSource(scanIndex)) {
                    return true;
                }
            }
            return false;
        }

        public boolean isSpecial() { return index >= 75; }

        public int countOpenings() {
            int c = 0;
            for (boolean b : hasOpening) if (b) c++;
            return c;
        }
    }

    private static final int GRIDROOM_SOURCE_INDEX = roomIndex(2, 0, 0);
    private static final int GRIDROOM_TOP_CONNECT_INDEX = roomIndex(2, 2, 0);
    private static final int GRIDROOM_LEFTWING_CONNECT_INDEX = roomIndex(0, 1, 0);
    private static final int GRIDROOM_RIGHTWING_CONNECT_INDEX = roomIndex(4, 1, 0);

    public static int roomIndex(int x, int y, int z) { return y * 25 + z * 5 + x; }

    public static final class Graph {
        public final List<RoomDefinition> rooms;   // all real (non-null) grid rooms + roof/leftWing/rightWing, shuffled order
        public final RoomDefinition sourceRoom;
        public final RoomDefinition coreRoom;
        Graph(List<RoomDefinition> rooms, RoomDefinition sourceRoom, RoomDefinition coreRoom) {
            this.rooms = rooms; this.sourceRoom = sourceRoom; this.coreRoom = coreRoom;
        }
    }

    /** MonumentBuilding.generateRoomGraph, ported 1:1 from the decompile. */
    public static Graph generateRoomGraph(VSurface.LegacyRandom random) {
        RoomDefinition[] grid = new RoomDefinition[75];
        for (int x = 0; x < 5; x++) for (int z = 0; z < 4; z++) {
            int pos = roomIndex(x, 0, z);
            grid[pos] = new RoomDefinition(pos);
        }
        for (int x = 0; x < 5; x++) for (int z = 0; z < 4; z++) {
            int pos = roomIndex(x, 1, z);
            grid[pos] = new RoomDefinition(pos);
        }
        for (int x = 1; x < 4; x++) for (int z = 0; z < 2; z++) {
            int pos = roomIndex(x, 2, z);
            grid[pos] = new RoomDefinition(pos);
        }

        RoomDefinition sourceRoom = grid[GRIDROOM_SOURCE_INDEX];

        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                for (int y = 0; y < 3; y++) {
                    int pos = roomIndex(x, y, z);
                    if (grid[pos] == null) continue;
                    for (Dir3 dir : Dir3.VALUES) {
                        int nx = x + dir.dx, ny = y + dir.dy, nz = z + dir.dz;
                        if (nx < 0 || nx >= 5 || nz < 0 || nz >= 5 || ny < 0 || ny >= 3) continue;
                        int npos = roomIndex(nx, ny, nz);
                        if (grid[npos] == null) continue;
                        if (nz == z) grid[pos].setConnection(dir, grid[npos]);
                        else grid[pos].setConnection(dir.opposite(), grid[npos]);
                    }
                }
            }
        }

        RoomDefinition roofRoom = new RoomDefinition(1003);
        RoomDefinition leftWing = new RoomDefinition(1001);
        RoomDefinition rightWing = new RoomDefinition(1002);
        grid[GRIDROOM_TOP_CONNECT_INDEX].setConnection(Dir3.UP, roofRoom);
        grid[GRIDROOM_LEFTWING_CONNECT_INDEX].setConnection(Dir3.SOUTH, leftWing);
        grid[GRIDROOM_RIGHTWING_CONNECT_INDEX].setConnection(Dir3.SOUTH, rightWing);
        roofRoom.claimed = true;
        leftWing.claimed = true;
        rightWing.claimed = true;
        sourceRoom.isSource = true;

        RoomDefinition coreRoom = grid[roomIndex(random.nextInt(4), 0, 2)];
        coreRoom.claimed = true;
        coreRoom.connections[Dir3.EAST.ordinal()].claimed = true;
        coreRoom.connections[Dir3.NORTH.ordinal()].claimed = true;
        coreRoom.connections[Dir3.EAST.ordinal()].connections[Dir3.NORTH.ordinal()].claimed = true;
        coreRoom.connections[Dir3.UP.ordinal()].claimed = true;
        coreRoom.connections[Dir3.EAST.ordinal()].connections[Dir3.UP.ordinal()].claimed = true;
        coreRoom.connections[Dir3.NORTH.ordinal()].connections[Dir3.UP.ordinal()].claimed = true;
        coreRoom.connections[Dir3.EAST.ordinal()].connections[Dir3.NORTH.ordinal()].connections[Dir3.UP.ordinal()].claimed = true;

        List<RoomDefinition> roomDefs = new ArrayList<>();
        for (RoomDefinition def : grid) {
            if (def != null) { def.updateOpenings(); roomDefs.add(def); }
        }
        roofRoom.updateOpenings();

        shuffle(roomDefs, random);
        int scanIndex = 1;
        for (RoomDefinition def : roomDefs) {
            int closeCount = 0, attempts = 0;
            while (closeCount < 2 && attempts < 5) {
                attempts++;
                int f = random.nextInt(6);
                if (!def.hasOpening[f]) continue;
                int of = Dir3.VALUES[f].opposite().ordinal();
                def.hasOpening[f] = false;
                def.connections[f].hasOpening[of] = false;
                if (def.findSource(scanIndex++) && def.connections[f].findSource(scanIndex++)) {
                    closeCount++;
                } else {
                    def.hasOpening[f] = true;
                    def.connections[f].hasOpening[of] = true;
                }
            }
        }

        roomDefs.add(roofRoom);
        roomDefs.add(leftWing);
        roomDefs.add(rightWing);
        return new Graph(roomDefs, sourceRoom, coreRoom);
    }

    /** Util.shuffle: real vanilla's in-place Fisher-Yates over a RandomSource. */
    private static void shuffle(List<RoomDefinition> list, VSurface.LegacyRandom random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            RoomDefinition tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    // -------- room-shape fitting (MonumentRoomFitter chain, priority order matters)

    private interface Fitter {
        boolean fits(RoomDefinition def);
        void apply(RoomDefinition def);
    }

    private static final Fitter FIT_DOUBLE_XY = new Fitter() {
        public boolean fits(RoomDefinition def) {
            if (!def.hasOpening[Dir3.EAST.ordinal()] || def.connections[Dir3.EAST.ordinal()].claimed
                    || !def.hasOpening[Dir3.UP.ordinal()] || def.connections[Dir3.UP.ordinal()].claimed) return false;
            RoomDefinition east = def.connections[Dir3.EAST.ordinal()];
            return east.hasOpening[Dir3.UP.ordinal()] && !east.connections[Dir3.UP.ordinal()].claimed;
        }
        public void apply(RoomDefinition def) {
            def.claimed = true;
            def.connections[Dir3.EAST.ordinal()].claimed = true;
            def.connections[Dir3.UP.ordinal()].claimed = true;
            def.connections[Dir3.EAST.ordinal()].connections[Dir3.UP.ordinal()].claimed = true;
            def.shape = Shape.DOUBLE_XY;
        }
    };
    private static final Fitter FIT_DOUBLE_YZ = new Fitter() {
        public boolean fits(RoomDefinition def) {
            if (!def.hasOpening[Dir3.NORTH.ordinal()] || def.connections[Dir3.NORTH.ordinal()].claimed
                    || !def.hasOpening[Dir3.UP.ordinal()] || def.connections[Dir3.UP.ordinal()].claimed) return false;
            RoomDefinition north = def.connections[Dir3.NORTH.ordinal()];
            return north.hasOpening[Dir3.UP.ordinal()] && !north.connections[Dir3.UP.ordinal()].claimed;
        }
        public void apply(RoomDefinition def) {
            def.claimed = true;
            def.connections[Dir3.NORTH.ordinal()].claimed = true;
            def.connections[Dir3.UP.ordinal()].claimed = true;
            def.connections[Dir3.NORTH.ordinal()].connections[Dir3.UP.ordinal()].claimed = true;
            def.shape = Shape.DOUBLE_YZ;
        }
    };
    private static final Fitter FIT_DOUBLE_Z = new Fitter() {
        public boolean fits(RoomDefinition def) {
            return def.hasOpening[Dir3.NORTH.ordinal()] && !def.connections[Dir3.NORTH.ordinal()].claimed;
        }
        public void apply(RoomDefinition def) {
            RoomDefinition source = def;
            if (!def.hasOpening[Dir3.NORTH.ordinal()] || def.connections[Dir3.NORTH.ordinal()].claimed) {
                source = def.connections[Dir3.SOUTH.ordinal()];
            }
            source.claimed = true;
            source.connections[Dir3.NORTH.ordinal()].claimed = true;
            source.shape = Shape.DOUBLE_Z;
        }
    };
    private static final Fitter FIT_DOUBLE_X = new Fitter() {
        public boolean fits(RoomDefinition def) {
            return def.hasOpening[Dir3.EAST.ordinal()] && !def.connections[Dir3.EAST.ordinal()].claimed;
        }
        public void apply(RoomDefinition def) {
            def.claimed = true;
            def.connections[Dir3.EAST.ordinal()].claimed = true;
            def.shape = Shape.DOUBLE_X;
        }
    };
    private static final Fitter FIT_DOUBLE_Y = new Fitter() {
        public boolean fits(RoomDefinition def) {
            return def.hasOpening[Dir3.UP.ordinal()] && !def.connections[Dir3.UP.ordinal()].claimed;
        }
        public void apply(RoomDefinition def) {
            def.claimed = true;
            def.connections[Dir3.UP.ordinal()].claimed = true;
            def.shape = Shape.DOUBLE_Y;
        }
    };
    private static final Fitter FIT_SIMPLE_TOP = new Fitter() {
        public boolean fits(RoomDefinition def) {
            return !def.hasOpening[Dir3.WEST.ordinal()] && !def.hasOpening[Dir3.EAST.ordinal()]
                    && !def.hasOpening[Dir3.NORTH.ordinal()] && !def.hasOpening[Dir3.SOUTH.ordinal()] && !def.hasOpening[Dir3.UP.ordinal()];
        }
        public void apply(RoomDefinition def) { def.claimed = true; def.shape = Shape.SIMPLE_TOP; }
    };
    private static final Fitter FIT_SIMPLE = new Fitter() {
        public boolean fits(RoomDefinition def) { return true; }
        public void apply(RoomDefinition def) { def.claimed = true; def.shape = Shape.SIMPLE; }
    };
    private static final Fitter[] FITTERS = {FIT_DOUBLE_XY, FIT_DOUBLE_YZ, FIT_DOUBLE_Z, FIT_DOUBLE_X, FIT_DOUBLE_Y, FIT_SIMPLE_TOP, FIT_SIMPLE};

    /** MonumentBuilding constructor's fitter-assignment loop, in the real fixed priority order. */
    public static void fitRoomShapes(Graph graph) {
        graph.sourceRoom.claimed = true;
        graph.sourceRoom.shape = Shape.ENTRY;
        graph.coreRoom.shape = Shape.CORE;
        for (RoomDefinition def : graph.rooms) {
            if (def.claimed || def.isSpecial()) continue;
            for (Fitter fitter : FITTERS) {
                if (fitter.fits(def)) { fitter.apply(def); break; }
            }
        }
    }

    // ------------------------------------------------------------------ shell rendering (stage 2)

    /**
     * The building's own outer-shell geometry ({@code MonumentBuilding.postProcess}'s direct
     * body — wings, entrance archs/wall, roof, lower/middle/upper walls, corner pillars, and the
     * 5-layer stepped water moat). Room-content dispatch (the ~15 room-type piece classes) is a
     * follow-up increment; none of these shell methods draw any RNG-dependent geometry (`random`
     * is threaded through but unused in every one of them, confirmed from the decompile), so
     * there's no RNG-stream-order concern here unlike stronghold/ruined portal.
     */
    public interface Sink {
        Block get(int x, int y, int z);
        void set(int x, int y, int z, Block b);
    }

    public static final class Building {
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public VTemplate.Dir orientation;
        boolean mirrorLR;
        VTemplate.Rot rotation = VTemplate.Rot.NONE;
    }

    private static final Block BASE_GRAY = Block.PRISMARINE;
    private static final Block BASE_LIGHT = Block.PRISMARINE_BRICKS;
    private static final Block BASE_BLACK = Block.DARK_PRISMARINE;
    private static final Block DOT_DECO = BASE_LIGHT;
    private static final Block LAMP_BLOCK = Block.SEA_LANTERN;
    private static final Block FILL_BLOCK = Block.WATER;
    private static final Set<Block> FILL_KEEP = Set.of(Block.ICE, Block.PACKED_ICE, Block.BLUE_ICE, Block.WATER);

    /** StructurePiece.makeBoundingBox, identical formula to VStrongholdGen's (own copy — see project convention note). */
    private static int[] makeBoundingBox(int x, int y, int z, VTemplate.Dir dir, int width, int height, int depth) {
        boolean axisZ = dir == VTemplate.Dir.NORTH || dir == VTemplate.Dir.SOUTH;
        return axisZ
                ? new int[]{x, y, z, x + width - 1, y + height - 1, z + depth - 1}
                : new int[]{x, y, z, x + depth - 1, y + height - 1, z + width - 1};
    }

    /** MonumentBuilding(random, west, north, direction): the 58x23x58 top-level piece. */
    public static Building makeBuilding(int west, int minY, int north, VTemplate.Dir orientation) {
        Building b = new Building();
        int[] bb = makeBoundingBox(west, minY, north, orientation, 58, 23, 58);
        b.minX = bb[0]; b.minY = bb[1]; b.minZ = bb[2]; b.maxX = bb[3]; b.maxY = bb[4]; b.maxZ = bb[5];
        b.orientation = orientation;
        switch (orientation) {
            case SOUTH -> { b.mirrorLR = true; b.rotation = VTemplate.Rot.NONE; }
            case WEST -> { b.mirrorLR = true; b.rotation = VTemplate.Rot.CLOCKWISE_90; }
            case EAST -> { b.mirrorLR = false; b.rotation = VTemplate.Rot.CLOCKWISE_90; }
            default -> { b.mirrorLR = false; b.rotation = VTemplate.Rot.NONE; } // NORTH
        }
        return b;
    }

    private static int worldX(Building b, int x, int z) {
        return switch (b.orientation) {
            case NORTH, SOUTH -> b.minX + x;
            case WEST -> b.maxX - z;
            case EAST -> b.minX + z;
            default -> x;
        };
    }
    private static int worldY(Building b, int y) { return y + b.minY; }
    private static int worldZ(Building b, int x, int z) {
        return switch (b.orientation) {
            case NORTH -> b.maxZ - z;
            case SOUTH -> b.minZ + z;
            case WEST, EAST -> b.minZ + x;
            default -> z;
        };
    }

    private static VTemplate.Dir mirrorLeftRight(VTemplate.Dir d) {
        return (d == VTemplate.Dir.NORTH || d == VTemplate.Dir.SOUTH) ? d.opposite() : d;
    }

    /** Same generic block-property mirror+rotate transform as VStrongholdGen (own copy). */
    private static Block transformBlock(Block b, boolean mirrorLR, VTemplate.Rot rot) {
        String facing = b.getProperty("facing");
        if (facing != null) {
            VTemplate.Dir d = VTemplate.Dir.byName(facing);
            if (d.horizontal()) {
                VTemplate.Dir d2 = mirrorLR ? mirrorLeftRight(d) : d;
                d2 = rot.rotate(d2);
                b = b.withProperty("facing", d2.name().toLowerCase());
            }
        }
        String n = b.getProperty("north"), s = b.getProperty("south"), e = b.getProperty("east"), w = b.getProperty("west");
        if (n != null && s != null && e != null && w != null) {
            boolean[] has = {"true".equals(n), "true".equals(s), "true".equals(e), "true".equals(w)};
            VTemplate.Dir[] base = {VTemplate.Dir.NORTH, VTemplate.Dir.SOUTH, VTemplate.Dir.EAST, VTemplate.Dir.WEST};
            boolean newN = false, newS = false, newE = false, newW = false;
            for (int i = 0; i < 4; i++) {
                if (!has[i]) continue;
                VTemplate.Dir d2 = mirrorLR ? mirrorLeftRight(base[i]) : base[i];
                d2 = rot.rotate(d2);
                switch (d2) {
                    case NORTH -> newN = true; case SOUTH -> newS = true;
                    case EAST -> newE = true; case WEST -> newW = true;
                    default -> {}
                }
            }
            b = b.withProperty("north", newN ? "true" : "false").withProperty("south", newS ? "true" : "false")
                    .withProperty("east", newE ? "true" : "false").withProperty("west", newW ? "true" : "false");
        }
        return b;
    }

    private static boolean insideClip(int wx, int wz, int[] clip) {
        return wx >= clip[0] && wx <= clip[2] && wz >= clip[1] && wz <= clip[3];
    }

    private static void place(Building b, Sink sink, int[] clip, Block block, int x, int y, int z) {
        int wx = worldX(b, x, z), wz = worldZ(b, x, z);
        if (!insideClip(wx, wz, clip)) return;
        sink.set(wx, worldY(b, y), wz, transformBlock(block, b.mirrorLR, b.rotation));
    }

    private static Block readLocal(Building b, Sink sink, int[] clip, int x, int y, int z) {
        int wx = worldX(b, x, z), wz = worldZ(b, x, z);
        if (!insideClip(wx, wz, clip)) return Block.AIR;
        Block bl = sink.get(wx, worldY(b, y), wz);
        return bl == null ? Block.AIR : bl;
    }

    private static void genBox(Building b, Sink sink, int[] clip, int x0, int y0, int z0, int x1, int y1, int z1, Block edge, Block fill, boolean skipAir) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (skipAir && readLocal(b, sink, clip, x, y, z).isAir()) continue;
            boolean edgePos = y == y0 || y == y1 || x == x0 || x == x1 || z == z0 || z == z1;
            place(b, sink, clip, edgePos ? edge : fill, x, y, z);
        }
    }

    /** OceanMonumentPiece.generateWaterBox: flood with water below sea level, clear to air above (skips existing ice/water). */
    private static void genWaterBox(Building b, Sink sink, int[] clip, int seaLevel, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            Block cur = readLocal(b, sink, clip, x, y, z);
            if (FILL_KEEP.contains(cur)) continue;
            if (worldY(b, y) >= seaLevel && !cur.compare(FILL_BLOCK)) place(b, sink, clip, Block.AIR, x, y, z);
            else place(b, sink, clip, FILL_BLOCK, x, y, z);
        }
    }

    /** Pure perf pre-check (no behavioral effect — individual place() calls already clip). */
    private static boolean chunkIntersects(Building b, int[] clip, int x0, int z0, int x1, int z1) {
        int wx0 = worldX(b, x0, z0), wz0 = worldZ(b, x0, z0);
        int wx1 = worldX(b, x1, z1), wz1 = worldZ(b, x1, z1);
        int lo_x = Math.min(wx0, wx1), hi_x = Math.max(wx0, wx1);
        int lo_z = Math.min(wz0, wz1), hi_z = Math.max(wz0, wz1);
        return hi_x >= clip[0] && lo_x <= clip[2] && hi_z >= clip[1] && lo_z <= clip[3];
    }

    /** MonumentBuilding.postProcess's own direct body (shell only — room-content dispatch is a follow-up increment). */
    public static void renderShell(Building b, Sink sink, int seaLevel, int chunkX, int chunkZ) {
        int cMinX = chunkX << 4, cMinZ = chunkZ << 4;
        int[] clip = {cMinX, cMinZ, cMinX + 15, cMinZ + 15};

        int waterHeight = Math.max(seaLevel, 64) - b.minY;
        genWaterBox(b, sink, clip, seaLevel, 0, 0, 0, 58, waterHeight, 58);
        generateWing(b, sink, clip, seaLevel, false, 0);
        generateWing(b, sink, clip, seaLevel, true, 33);
        generateEntranceArchs(b, sink, clip, seaLevel);
        generateEntranceWall(b, sink, clip, seaLevel);
        generateRoofPiece(b, sink, clip, seaLevel);
        generateLowerWall(b, sink, clip, seaLevel);
        generateMiddleWall(b, sink, clip, seaLevel);
        generateUpperWall(b, sink, clip, seaLevel);

        for (int pillarX = 0; pillarX < 7; pillarX++) {
            int pillarZ = 0;
            while (pillarZ < 7) {
                if (pillarZ == 0 && pillarX == 3) pillarZ = 6;
                int bx = pillarX * 9, bz = pillarZ * 9;
                for (int w = 0; w < 4; w++) {
                    for (int d = 0; d < 4; d++) {
                        place(b, sink, clip, BASE_LIGHT, bx + w, 0, bz + d);
                        fillColumnDown(b, sink, clip, BASE_LIGHT, bx + w, -1, bz + d);
                    }
                }
                if (pillarX != 0 && pillarX != 6) pillarZ += 6; else pillarZ++;
            }
        }

        for (int i = 0; i < 5; i++) {
            genWaterBox(b, sink, clip, seaLevel, -1 - i, i * 2, -1 - i, -1 - i, 23, 58 + i);
            genWaterBox(b, sink, clip, seaLevel, 58 + i, i * 2, -1 - i, 58 + i, 23, 58 + i);
            genWaterBox(b, sink, clip, seaLevel, -i, i * 2, -1 - i, 57 + i, 23, -1 - i);
            genWaterBox(b, sink, clip, seaLevel, -i, i * 2, 58 + i, 57 + i, 23, 58 + i);
        }
    }

    /** StructurePiece.fillColumnDown, restricted to air/liquid replacement (real vanilla's isReplaceableByStructures gate). */
    private static void fillColumnDown(Building b, Sink sink, int[] clip, Block block, int x, int startY, int z) {
        int wx = worldX(b, x, z), wz = worldZ(b, x, z);
        if (!insideClip(wx, wz, clip)) return;
        int y = startY;
        while (true) {
            Block cur = sink.get(wx, worldY(b, y), wz);
            boolean replaceable = cur == null || cur.isAir() || cur.isLiquid();
            if (!replaceable) break;
            sink.set(wx, worldY(b, y), wz, transformBlock(block, b.mirrorLR, b.rotation));
            y--;
            if (worldY(b, y) <= -63) break; // world floor guard (real: level.getMinY()+1)
        }
    }

    private static void generateWing(Building b, Sink sink, int[] clip, int seaLevel, boolean isFlipped, int xoff) {
        if (!chunkIntersects(b, clip, xoff, 0, xoff + 23, 20)) return;
        genBox(b, sink, clip, xoff, 0, 0, xoff + 24, 0, 20, BASE_GRAY, BASE_GRAY, false);
        genWaterBox(b, sink, clip, seaLevel, xoff, 1, 0, xoff + 24, 10, 20);
        for (int i = 0; i < 4; i++) {
            genBox(b, sink, clip, xoff + i, i + 1, i, xoff + i, i + 1, 20, BASE_LIGHT, BASE_LIGHT, false);
            genBox(b, sink, clip, xoff + i + 7, i + 5, i + 7, xoff + i + 7, i + 5, 20, BASE_LIGHT, BASE_LIGHT, false);
            genBox(b, sink, clip, xoff + 17 - i, i + 5, i + 7, xoff + 17 - i, i + 5, 20, BASE_LIGHT, BASE_LIGHT, false);
            genBox(b, sink, clip, xoff + 24 - i, i + 1, i, xoff + 24 - i, i + 1, 20, BASE_LIGHT, BASE_LIGHT, false);
            genBox(b, sink, clip, xoff + i + 1, i + 1, i, xoff + 23 - i, i + 1, i, BASE_LIGHT, BASE_LIGHT, false);
            genBox(b, sink, clip, xoff + i + 8, i + 5, i + 7, xoff + 16 - i, i + 5, i + 7, BASE_LIGHT, BASE_LIGHT, false);
        }
        genBox(b, sink, clip, xoff + 4, 4, 4, xoff + 6, 4, 20, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, xoff + 7, 4, 4, xoff + 17, 4, 6, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, xoff + 18, 4, 4, xoff + 20, 4, 20, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, xoff + 11, 8, 11, xoff + 13, 8, 20, BASE_GRAY, BASE_GRAY, false);
        place(b, sink, clip, DOT_DECO, xoff + 12, 9, 12);
        place(b, sink, clip, DOT_DECO, xoff + 12, 9, 15);
        place(b, sink, clip, DOT_DECO, xoff + 12, 9, 18);
        int leftPos = xoff + (isFlipped ? 19 : 5);
        int rightPos = xoff + (isFlipped ? 5 : 19);
        for (int z = 20; z >= 5; z -= 3) place(b, sink, clip, DOT_DECO, leftPos, 5, z);
        for (int z = 19; z >= 7; z -= 3) place(b, sink, clip, DOT_DECO, rightPos, 5, z);
        for (int i = 0; i < 4; i++) {
            int pos = isFlipped ? xoff + 24 - (17 - i * 3) : xoff + 17 - i * 3;
            place(b, sink, clip, DOT_DECO, pos, 5, 5);
        }
        place(b, sink, clip, DOT_DECO, rightPos, 5, 5);
        genBox(b, sink, clip, xoff + 11, 1, 12, xoff + 13, 7, 12, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, xoff + 12, 1, 11, xoff + 12, 7, 13, BASE_GRAY, BASE_GRAY, false);
    }

    private static void generateEntranceArchs(Building b, Sink sink, int[] clip, int seaLevel) {
        if (!chunkIntersects(b, clip, 22, 5, 35, 17)) return;
        genWaterBox(b, sink, clip, seaLevel, 25, 0, 0, 32, 8, 20);
        for (int i = 0; i < 4; i++) {
            genBox(b, sink, clip, 24, 2, 5 + i * 4, 24, 4, 5 + i * 4, BASE_LIGHT, BASE_LIGHT, false);
            genBox(b, sink, clip, 22, 4, 5 + i * 4, 23, 4, 5 + i * 4, BASE_LIGHT, BASE_LIGHT, false);
            place(b, sink, clip, BASE_LIGHT, 25, 5, 5 + i * 4);
            place(b, sink, clip, BASE_LIGHT, 26, 6, 5 + i * 4);
            place(b, sink, clip, LAMP_BLOCK, 26, 5, 5 + i * 4);
            genBox(b, sink, clip, 33, 2, 5 + i * 4, 33, 4, 5 + i * 4, BASE_LIGHT, BASE_LIGHT, false);
            genBox(b, sink, clip, 34, 4, 5 + i * 4, 35, 4, 5 + i * 4, BASE_LIGHT, BASE_LIGHT, false);
            place(b, sink, clip, BASE_LIGHT, 32, 5, 5 + i * 4);
            place(b, sink, clip, BASE_LIGHT, 31, 6, 5 + i * 4);
            place(b, sink, clip, LAMP_BLOCK, 31, 5, 5 + i * 4);
            genBox(b, sink, clip, 27, 6, 5 + i * 4, 30, 6, 5 + i * 4, BASE_GRAY, BASE_GRAY, false);
        }
    }

    private static void generateEntranceWall(Building b, Sink sink, int[] clip, int seaLevel) {
        if (!chunkIntersects(b, clip, 15, 20, 42, 21)) return;
        genBox(b, sink, clip, 15, 0, 21, 42, 0, 21, BASE_GRAY, BASE_GRAY, false);
        genWaterBox(b, sink, clip, seaLevel, 26, 1, 21, 31, 3, 21);
        genBox(b, sink, clip, 21, 12, 21, 36, 12, 21, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 17, 11, 21, 40, 11, 21, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 16, 10, 21, 41, 10, 21, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 15, 7, 21, 42, 9, 21, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 16, 6, 21, 41, 6, 21, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 17, 5, 21, 40, 5, 21, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 21, 4, 21, 36, 4, 21, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 22, 3, 21, 26, 3, 21, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 31, 3, 21, 35, 3, 21, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 23, 2, 21, 25, 2, 21, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 32, 2, 21, 34, 2, 21, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 28, 4, 20, 29, 4, 21, BASE_LIGHT, BASE_LIGHT, false);
        place(b, sink, clip, BASE_LIGHT, 27, 3, 21);
        place(b, sink, clip, BASE_LIGHT, 30, 3, 21);
        place(b, sink, clip, BASE_LIGHT, 26, 2, 21);
        place(b, sink, clip, BASE_LIGHT, 31, 2, 21);
        place(b, sink, clip, BASE_LIGHT, 25, 1, 21);
        place(b, sink, clip, BASE_LIGHT, 32, 1, 21);
        for (int i = 0; i < 7; i++) {
            place(b, sink, clip, BASE_BLACK, 28 - i, 6 + i, 21);
            place(b, sink, clip, BASE_BLACK, 29 + i, 6 + i, 21);
        }
        for (int i = 0; i < 4; i++) {
            place(b, sink, clip, BASE_BLACK, 28 - i, 9 + i, 21);
            place(b, sink, clip, BASE_BLACK, 29 + i, 9 + i, 21);
        }
        place(b, sink, clip, BASE_BLACK, 28, 12, 21);
        place(b, sink, clip, BASE_BLACK, 29, 12, 21);
        for (int i = 0; i < 3; i++) {
            place(b, sink, clip, BASE_BLACK, 22 - i * 2, 8, 21);
            place(b, sink, clip, BASE_BLACK, 22 - i * 2, 9, 21);
            place(b, sink, clip, BASE_BLACK, 35 + i * 2, 8, 21);
            place(b, sink, clip, BASE_BLACK, 35 + i * 2, 9, 21);
        }
        genWaterBox(b, sink, clip, seaLevel, 15, 13, 21, 42, 15, 21);
        genWaterBox(b, sink, clip, seaLevel, 15, 1, 21, 15, 6, 21);
        genWaterBox(b, sink, clip, seaLevel, 16, 1, 21, 16, 5, 21);
        genWaterBox(b, sink, clip, seaLevel, 17, 1, 21, 20, 4, 21);
        genWaterBox(b, sink, clip, seaLevel, 21, 1, 21, 21, 3, 21);
        genWaterBox(b, sink, clip, seaLevel, 22, 1, 21, 22, 2, 21);
        genWaterBox(b, sink, clip, seaLevel, 23, 1, 21, 24, 1, 21);
        genWaterBox(b, sink, clip, seaLevel, 42, 1, 21, 42, 6, 21);
        genWaterBox(b, sink, clip, seaLevel, 41, 1, 21, 41, 5, 21);
        genWaterBox(b, sink, clip, seaLevel, 37, 1, 21, 40, 4, 21);
        genWaterBox(b, sink, clip, seaLevel, 36, 1, 21, 36, 3, 21);
        genWaterBox(b, sink, clip, seaLevel, 33, 1, 21, 34, 1, 21);
        genWaterBox(b, sink, clip, seaLevel, 35, 1, 21, 35, 2, 21);
    }

    private static void generateRoofPiece(Building b, Sink sink, int[] clip, int seaLevel) {
        if (!chunkIntersects(b, clip, 21, 21, 36, 36)) return;
        genBox(b, sink, clip, 21, 0, 22, 36, 0, 36, BASE_GRAY, BASE_GRAY, false);
        genWaterBox(b, sink, clip, seaLevel, 21, 1, 22, 36, 23, 36);
        for (int i = 0; i < 4; i++) {
            genBox(b, sink, clip, 21 + i, 13 + i, 21 + i, 36 - i, 13 + i, 21 + i, BASE_LIGHT, BASE_LIGHT, false);
            genBox(b, sink, clip, 21 + i, 13 + i, 36 - i, 36 - i, 13 + i, 36 - i, BASE_LIGHT, BASE_LIGHT, false);
            genBox(b, sink, clip, 21 + i, 13 + i, 22 + i, 21 + i, 13 + i, 35 - i, BASE_LIGHT, BASE_LIGHT, false);
            genBox(b, sink, clip, 36 - i, 13 + i, 22 + i, 36 - i, 13 + i, 35 - i, BASE_LIGHT, BASE_LIGHT, false);
        }
        genBox(b, sink, clip, 25, 16, 25, 32, 16, 32, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 25, 17, 25, 25, 19, 25, BASE_LIGHT, BASE_LIGHT, false);
        genBox(b, sink, clip, 32, 17, 25, 32, 19, 25, BASE_LIGHT, BASE_LIGHT, false);
        genBox(b, sink, clip, 25, 17, 32, 25, 19, 32, BASE_LIGHT, BASE_LIGHT, false);
        genBox(b, sink, clip, 32, 17, 32, 32, 19, 32, BASE_LIGHT, BASE_LIGHT, false);
        place(b, sink, clip, BASE_LIGHT, 26, 20, 26);
        place(b, sink, clip, BASE_LIGHT, 27, 21, 27);
        place(b, sink, clip, LAMP_BLOCK, 27, 20, 27);
        place(b, sink, clip, BASE_LIGHT, 26, 20, 31);
        place(b, sink, clip, BASE_LIGHT, 27, 21, 30);
        place(b, sink, clip, LAMP_BLOCK, 27, 20, 30);
        place(b, sink, clip, BASE_LIGHT, 31, 20, 31);
        place(b, sink, clip, BASE_LIGHT, 30, 21, 30);
        place(b, sink, clip, LAMP_BLOCK, 30, 20, 30);
        place(b, sink, clip, BASE_LIGHT, 31, 20, 26);
        place(b, sink, clip, BASE_LIGHT, 30, 21, 27);
        place(b, sink, clip, LAMP_BLOCK, 30, 20, 27);
        genBox(b, sink, clip, 28, 21, 27, 29, 21, 27, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 27, 21, 28, 27, 21, 29, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 28, 21, 30, 29, 21, 30, BASE_GRAY, BASE_GRAY, false);
        genBox(b, sink, clip, 30, 21, 28, 30, 21, 29, BASE_GRAY, BASE_GRAY, false);
    }

    private static void generateLowerWall(Building b, Sink sink, int[] clip, int seaLevel) {
        if (chunkIntersects(b, clip, 0, 21, 6, 58)) {
            genBox(b, sink, clip, 0, 0, 21, 6, 0, 57, BASE_GRAY, BASE_GRAY, false);
            genWaterBox(b, sink, clip, seaLevel, 0, 1, 21, 6, 7, 57);
            genBox(b, sink, clip, 4, 4, 21, 6, 4, 53, BASE_GRAY, BASE_GRAY, false);
            for (int i = 0; i < 4; i++) genBox(b, sink, clip, i, i + 1, 21, i, i + 1, 57 - i, BASE_LIGHT, BASE_LIGHT, false);
            for (int z = 23; z < 53; z += 3) place(b, sink, clip, DOT_DECO, 5, 5, z);
            place(b, sink, clip, DOT_DECO, 5, 5, 52);
            genBox(b, sink, clip, 4, 1, 52, 6, 3, 52, BASE_GRAY, BASE_GRAY, false);
            genBox(b, sink, clip, 5, 1, 51, 5, 3, 53, BASE_GRAY, BASE_GRAY, false);
        }
        if (chunkIntersects(b, clip, 51, 21, 58, 58)) {
            genBox(b, sink, clip, 51, 0, 21, 57, 0, 57, BASE_GRAY, BASE_GRAY, false);
            genWaterBox(b, sink, clip, seaLevel, 51, 1, 21, 57, 7, 57);
            genBox(b, sink, clip, 51, 4, 21, 53, 4, 53, BASE_GRAY, BASE_GRAY, false);
            for (int i = 0; i < 4; i++) genBox(b, sink, clip, 57 - i, i + 1, 21, 57 - i, i + 1, 57 - i, BASE_LIGHT, BASE_LIGHT, false);
            for (int z = 23; z < 53; z += 3) place(b, sink, clip, DOT_DECO, 52, 5, z);
            place(b, sink, clip, DOT_DECO, 52, 5, 52);
            genBox(b, sink, clip, 51, 1, 52, 53, 3, 52, BASE_GRAY, BASE_GRAY, false);
            genBox(b, sink, clip, 52, 1, 51, 52, 3, 53, BASE_GRAY, BASE_GRAY, false);
        }
        if (chunkIntersects(b, clip, 0, 51, 57, 57)) {
            genBox(b, sink, clip, 7, 0, 51, 50, 0, 57, BASE_GRAY, BASE_GRAY, false);
            genWaterBox(b, sink, clip, seaLevel, 7, 1, 51, 50, 10, 57);
            for (int i = 0; i < 4; i++) genBox(b, sink, clip, i + 1, i + 1, 57 - i, 56 - i, i + 1, 57 - i, BASE_LIGHT, BASE_LIGHT, false);
        }
    }

    private static void generateMiddleWall(Building b, Sink sink, int[] clip, int seaLevel) {
        if (chunkIntersects(b, clip, 7, 21, 13, 50)) {
            genBox(b, sink, clip, 7, 0, 21, 13, 0, 50, BASE_GRAY, BASE_GRAY, false);
            genWaterBox(b, sink, clip, seaLevel, 7, 1, 21, 13, 10, 50);
            genBox(b, sink, clip, 11, 8, 21, 13, 8, 53, BASE_GRAY, BASE_GRAY, false);
            for (int i = 0; i < 4; i++) genBox(b, sink, clip, i + 7, i + 5, 21, i + 7, i + 5, 54, BASE_LIGHT, BASE_LIGHT, false);
            for (int z = 21; z <= 45; z += 3) place(b, sink, clip, DOT_DECO, 12, 9, z);
        }
        if (chunkIntersects(b, clip, 44, 21, 50, 54)) {
            genBox(b, sink, clip, 44, 0, 21, 50, 0, 50, BASE_GRAY, BASE_GRAY, false);
            genWaterBox(b, sink, clip, seaLevel, 44, 1, 21, 50, 10, 50);
            genBox(b, sink, clip, 44, 8, 21, 46, 8, 53, BASE_GRAY, BASE_GRAY, false);
            for (int i = 0; i < 4; i++) genBox(b, sink, clip, 50 - i, i + 5, 21, 50 - i, i + 5, 54, BASE_LIGHT, BASE_LIGHT, false);
            for (int z = 21; z <= 45; z += 3) place(b, sink, clip, DOT_DECO, 45, 9, z);
        }
        if (chunkIntersects(b, clip, 8, 44, 49, 54)) {
            genBox(b, sink, clip, 14, 0, 44, 43, 0, 50, BASE_GRAY, BASE_GRAY, false);
            genWaterBox(b, sink, clip, seaLevel, 14, 1, 44, 43, 10, 50);
            for (int x = 12; x <= 45; x += 3) {
                place(b, sink, clip, DOT_DECO, x, 9, 45);
                place(b, sink, clip, DOT_DECO, x, 9, 52);
                if (x == 12 || x == 18 || x == 24 || x == 33 || x == 39 || x == 45) {
                    place(b, sink, clip, DOT_DECO, x, 9, 47);
                    place(b, sink, clip, DOT_DECO, x, 9, 50);
                    place(b, sink, clip, DOT_DECO, x, 10, 45);
                    place(b, sink, clip, DOT_DECO, x, 10, 46);
                    place(b, sink, clip, DOT_DECO, x, 10, 51);
                    place(b, sink, clip, DOT_DECO, x, 10, 52);
                    place(b, sink, clip, DOT_DECO, x, 11, 47);
                    place(b, sink, clip, DOT_DECO, x, 11, 50);
                    place(b, sink, clip, DOT_DECO, x, 12, 48);
                    place(b, sink, clip, DOT_DECO, x, 12, 49);
                }
            }
            for (int i = 0; i < 3; i++) genBox(b, sink, clip, 8 + i, 5 + i, 54, 49 - i, 5 + i, 54, BASE_GRAY, BASE_GRAY, false);
            genBox(b, sink, clip, 11, 8, 54, 46, 8, 54, BASE_LIGHT, BASE_LIGHT, false);
            genBox(b, sink, clip, 14, 8, 44, 43, 8, 53, BASE_GRAY, BASE_GRAY, false);
        }
    }

    private static void generateUpperWall(Building b, Sink sink, int[] clip, int seaLevel) {
        if (chunkIntersects(b, clip, 14, 21, 20, 43)) {
            genBox(b, sink, clip, 14, 0, 21, 20, 0, 43, BASE_GRAY, BASE_GRAY, false);
            genWaterBox(b, sink, clip, seaLevel, 14, 1, 22, 20, 14, 43);
            genBox(b, sink, clip, 18, 12, 22, 20, 12, 39, BASE_GRAY, BASE_GRAY, false);
            genBox(b, sink, clip, 18, 12, 21, 20, 12, 21, BASE_LIGHT, BASE_LIGHT, false);
            for (int i = 0; i < 4; i++) genBox(b, sink, clip, i + 14, i + 9, 21, i + 14, i + 9, 43 - i, BASE_LIGHT, BASE_LIGHT, false);
            for (int z = 23; z <= 39; z += 3) place(b, sink, clip, DOT_DECO, 19, 13, z);
        }
        if (chunkIntersects(b, clip, 37, 21, 43, 43)) {
            genBox(b, sink, clip, 37, 0, 21, 43, 0, 43, BASE_GRAY, BASE_GRAY, false);
            genWaterBox(b, sink, clip, seaLevel, 37, 1, 22, 43, 14, 43);
            genBox(b, sink, clip, 37, 12, 22, 39, 12, 39, BASE_GRAY, BASE_GRAY, false);
            genBox(b, sink, clip, 37, 12, 21, 39, 12, 21, BASE_LIGHT, BASE_LIGHT, false);
            for (int i = 0; i < 4; i++) genBox(b, sink, clip, 43 - i, i + 9, 21, 43 - i, i + 9, 43 - i, BASE_LIGHT, BASE_LIGHT, false);
            for (int z = 23; z <= 39; z += 3) place(b, sink, clip, DOT_DECO, 38, 13, z);
        }
        if (chunkIntersects(b, clip, 15, 37, 42, 43)) {
            genBox(b, sink, clip, 21, 0, 37, 36, 0, 43, BASE_GRAY, BASE_GRAY, false);
            genWaterBox(b, sink, clip, seaLevel, 21, 1, 37, 36, 14, 43);
            genBox(b, sink, clip, 21, 12, 37, 36, 12, 39, BASE_GRAY, BASE_GRAY, false);
            for (int i = 0; i < 4; i++) genBox(b, sink, clip, 15 + i, i + 9, 43 - i, 42 - i, i + 9, 43 - i, BASE_LIGHT, BASE_LIGHT, false);
            for (int x = 21; x <= 36; x += 3) place(b, sink, clip, DOT_DECO, x, 13, 38);
        }
    }

    // ------------------------------------------------------------------ test hooks

    /** Test hook: assemble the real room graph + shape assignment for a given seed/chunk. */
    public static Graph testGenerate(long seed, int chunkX, int chunkZ) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, chunkX, chunkZ);
        Graph graph = generateRoomGraph(random);
        fitRoomShapes(graph);
        return graph;
    }

    /** Test hook: renders the shell across every chunk its 58x58 footprint touches into sink. */
    public static void testRenderShellFull(Building b, Sink sink, int seaLevel) {
        int minCX = b.minX >> 4, maxCX = b.maxX >> 4, minCZ = b.minZ >> 4, maxCZ = b.maxZ >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                renderShell(b, sink, seaLevel, cx, cz);
            }
        }
    }
}
