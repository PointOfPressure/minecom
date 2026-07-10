package dev.pointofpressure.minecom.worldgen.vanilla;

import java.util.ArrayList;
import java.util.List;

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

    // ------------------------------------------------------------------ test hooks

    /** Test hook: assemble the real room graph + shape assignment for a given seed/chunk. */
    public static Graph testGenerate(long seed, int chunkX, int chunkZ) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, chunkX, chunkZ);
        Graph graph = generateRoomGraph(random);
        fitRoomShapes(graph);
        return graph;
    }
}
