package dev.pointofpressure.minecom.worldgen.vanilla;

import java.util.ArrayList;
import java.util.List;

/**
 * Woodland mansion room-grid generation (real vanilla `WoodlandMansionPieces.MansionGrid` +
 * `SimpleGrid`). Reimplemented from the algorithm, not copied. This is the FIRST increment of
 * woodland mansion support — real vanilla's mansion layout algorithm is a genuinely different
 * shape again from every other structure ported this session: an 11x11 grid seeded with a fixed
 * entrance/corridor skeleton, then a recursive randomized corridor-carving walk
 * (`recursiveCorridor`, depth-limited, self-avoiding via 2-cell lookahead), an edge-cleanup pass
 * that fills small stray gaps (`cleanEdges`, iterated to a fixpoint), then a room-identification
 * pass (`identifyRooms`) that greedily merges adjacent CLEAR-adjacent-to-two-CORRIDOR-cells cells
 * into 1x1/1x2/2x2 rooms (shuffled visit order) and picks a door cell per room (with a real
 * documented fallback chain when the "random" door choice doesn't actually border a corridor).
 * The third floor is a SEPARATE, smaller generation pass seeded from a single randomly-chosen
 * qualifying 1x2 second-floor room. Rendering (NBT-template piece placement, `MansionPiecePlacer`
 * — the largest single class in the whole vanilla structure codebase) is a follow-up increment.
 */
public final class VMansionGen {

    private VMansionGen() {}

    // ------------------------------------------------------------------ SimpleGrid

    /** WoodlandMansionPieces.SimpleGrid: a 2D int grid with a fixed out-of-bounds sentinel. */
    public static final class SimpleGrid {
        final int[][] grid;
        public final int width, height;
        private final int valueIfOutside;

        public SimpleGrid(int width, int height, int valueIfOutside) {
            this.width = width; this.height = height; this.valueIfOutside = valueIfOutside;
            this.grid = new int[width][height];
        }

        public void set(int x, int y, int value) {
            if (x >= 0 && x < width && y >= 0 && y < height) grid[x][y] = value;
        }

        public void set(int x0, int y0, int x1, int y1, int value) {
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) set(x, y, value);
        }

        public int get(int x, int y) {
            return x >= 0 && x < width && y >= 0 && y < height ? grid[x][y] : valueIfOutside;
        }

        public void setif(int x, int y, int ifValue, int value) {
            if (get(x, y) == ifValue) set(x, y, value);
        }

        public boolean edgesTo(int x, int y, int ifValue) {
            return get(x - 1, y) == ifValue || get(x + 1, y) == ifValue || get(x, y + 1) == ifValue || get(x, y - 1) == ifValue;
        }
    }

    // ------------------------------------------------------------------ cell value constants

    private static final int CLEAR = 0;
    private static final int CORRIDOR = 1;
    private static final int ROOM = 2;
    private static final int START_ROOM = 3;
    // TEST_ROOM(4) is never actually written by generateMansion's real code path (only read by isHouse).
    private static final int BLOCKED = 5;

    public static final int ROOM_1x1 = 65536;
    public static final int ROOM_1x2 = 131072;
    public static final int ROOM_2x2 = 262144;
    public static final int ROOM_ORIGIN_FLAG = 1048576;
    public static final int ROOM_DOOR_FLAG = 2097152;
    public static final int ROOM_STAIRS_FLAG = 4194304;
    public static final int ROOM_CORRIDOR_FLAG = 8388608;
    private static final int ROOM_TYPE_MASK = 983040;
    private static final int ROOM_ID_MASK = 65535;

    public static boolean isHouse(SimpleGrid grid, int x, int y) {
        int v = grid.get(x, y);
        return v == 1 || v == 2 || v == 3 || v == 4;
    }

    // ------------------------------------------------------------------ MansionGrid

    public static final class MansionGrid {
        public final SimpleGrid baseGrid;
        public final SimpleGrid thirdFloorGrid;
        public final SimpleGrid[] floorRooms = new SimpleGrid[3];
        public final int entranceX = 7, entranceY = 4;
        private final VSurface.LegacyRandom random;

        public MansionGrid(VSurface.LegacyRandom random) {
            this.random = random;
            this.baseGrid = new SimpleGrid(11, 11, 5);
            baseGrid.set(entranceX, entranceY, entranceX + 1, entranceY + 1, START_ROOM);
            baseGrid.set(entranceX - 1, entranceY, entranceX - 1, entranceY + 1, ROOM);
            baseGrid.set(entranceX + 2, entranceY - 2, entranceX + 3, entranceY + 3, BLOCKED);
            baseGrid.set(entranceX + 1, entranceY - 2, entranceX + 1, entranceY - 1, CORRIDOR);
            baseGrid.set(entranceX + 1, entranceY + 2, entranceX + 1, entranceY + 3, CORRIDOR);
            baseGrid.set(entranceX - 1, entranceY - 1, CORRIDOR);
            baseGrid.set(entranceX - 1, entranceY + 2, CORRIDOR);
            baseGrid.set(0, 0, 11, 1, BLOCKED);
            baseGrid.set(0, 9, 11, 11, BLOCKED);
            recursiveCorridor(baseGrid, entranceX, entranceY - 2, VTemplate.Dir.WEST, 6);
            recursiveCorridor(baseGrid, entranceX, entranceY + 3, VTemplate.Dir.WEST, 6);
            recursiveCorridor(baseGrid, entranceX - 2, entranceY - 1, VTemplate.Dir.WEST, 3);
            recursiveCorridor(baseGrid, entranceX - 2, entranceY + 2, VTemplate.Dir.WEST, 3);

            while (cleanEdges(baseGrid)) { /* iterate to fixpoint */ }

            floorRooms[0] = new SimpleGrid(11, 11, 5);
            floorRooms[1] = new SimpleGrid(11, 11, 5);
            floorRooms[2] = new SimpleGrid(11, 11, 5);
            identifyRooms(baseGrid, floorRooms[0]);
            identifyRooms(baseGrid, floorRooms[1]);
            floorRooms[0].set(entranceX + 1, entranceY, entranceX + 1, entranceY + 1, ROOM_CORRIDOR_FLAG);
            floorRooms[1].set(entranceX + 1, entranceY, entranceX + 1, entranceY + 1, ROOM_CORRIDOR_FLAG);
            thirdFloorGrid = new SimpleGrid(baseGrid.width, baseGrid.height, 5);
            setupThirdFloor();
            identifyRooms(thirdFloorGrid, floorRooms[2]);
        }

        public boolean isRoomId(int x, int y, int floor, int roomId) {
            return (floorRooms[floor].get(x, y) & ROOM_ID_MASK) == roomId;
        }

        /** The direction from (x,y) toward the OTHER cell sharing this 1x2 room's roomId, or null. */
        public VTemplate.Dir get1x2RoomDirection(int x, int y, int floorNum, int roomId) {
            for (VTemplate.Dir d : HORIZONTAL) {
                if (isRoomId(x + d.dx, y + d.dz, floorNum, roomId)) return d;
            }
            return null;
        }

        private void recursiveCorridor(SimpleGrid grid, int x, int y, VTemplate.Dir heading, int depth) {
            if (depth <= 0) return;
            grid.set(x, y, CORRIDOR);
            grid.setif(x + heading.dx, y + heading.dz, CLEAR, CORRIDOR);

            for (int attempts = 0; attempts < 8; attempts++) {
                VTemplate.Dir nextDir = horizontalFrom2D(random.nextInt(4));
                if (nextDir != heading.opposite() && (nextDir != VTemplate.Dir.EAST || !random.nextBoolean())) {
                    int nx = x + heading.dx, ny = y + heading.dz;
                    if (grid.get(nx + nextDir.dx, ny + nextDir.dz) == CLEAR
                            && grid.get(nx + nextDir.dx * 2, ny + nextDir.dz * 2) == CLEAR) {
                        recursiveCorridor(grid, x + heading.dx + nextDir.dx, y + heading.dz + nextDir.dz, nextDir, depth - 1);
                        break;
                    }
                }
            }

            VTemplate.Dir cw = heading.clockWiseY(), ccw = heading.counterClockWiseY();
            grid.setif(x + cw.dx, y + cw.dz, CLEAR, ROOM);
            grid.setif(x + ccw.dx, y + ccw.dz, CLEAR, ROOM);
            grid.setif(x + heading.dx + cw.dx, y + heading.dz + cw.dz, CLEAR, ROOM);
            grid.setif(x + heading.dx + ccw.dx, y + heading.dz + ccw.dz, CLEAR, ROOM);
            grid.setif(x + heading.dx * 2, y + heading.dz * 2, CLEAR, ROOM);
            grid.setif(x + cw.dx * 2, y + cw.dz * 2, CLEAR, ROOM);
            grid.setif(x + ccw.dx * 2, y + ccw.dz * 2, CLEAR, ROOM);
        }

        private boolean cleanEdges(SimpleGrid grid) {
            boolean touched = false;
            for (int y = 0; y < grid.height; y++) {
                for (int x = 0; x < grid.width; x++) {
                    if (grid.get(x, y) != CLEAR) continue;
                    int direct = (isHouse(grid, x + 1, y) ? 1 : 0) + (isHouse(grid, x - 1, y) ? 1 : 0)
                            + (isHouse(grid, x, y + 1) ? 1 : 0) + (isHouse(grid, x, y - 1) ? 1 : 0);
                    if (direct >= 3) {
                        grid.set(x, y, ROOM);
                        touched = true;
                    } else if (direct == 2) {
                        int diag = (isHouse(grid, x + 1, y + 1) ? 1 : 0) + (isHouse(grid, x - 1, y + 1) ? 1 : 0)
                                + (isHouse(grid, x + 1, y - 1) ? 1 : 0) + (isHouse(grid, x - 1, y - 1) ? 1 : 0);
                        if (diag <= 1) { grid.set(x, y, ROOM); touched = true; }
                    }
                }
            }
            return touched;
        }

        private void setupThirdFloor() {
            List<int[]> potentialRooms = new ArrayList<>();
            SimpleGrid floor = floorRooms[1];
            for (int y = 0; y < thirdFloorGrid.height; y++) {
                for (int x = 0; x < thirdFloorGrid.width; x++) {
                    int roomData = floor.get(x, y);
                    int roomType = roomData & ROOM_TYPE_MASK;
                    if (roomType == ROOM_1x2 && (roomData & ROOM_DOOR_FLAG) == ROOM_DOOR_FLAG) potentialRooms.add(new int[]{x, y});
                }
            }
            if (potentialRooms.isEmpty()) {
                thirdFloorGrid.set(0, 0, thirdFloorGrid.width, thirdFloorGrid.height, BLOCKED);
                return;
            }
            int[] roomPos = potentialRooms.get(random.nextInt(potentialRooms.size()));
            int roomData = floor.get(roomPos[0], roomPos[1]);
            floor.set(roomPos[0], roomPos[1], roomData | ROOM_STAIRS_FLAG);
            VTemplate.Dir roomDir = get1x2RoomDirection(roomPos[0], roomPos[1], 1, roomData & ROOM_ID_MASK);
            int roomEndX = roomPos[0] + roomDir.dx, roomEndY = roomPos[1] + roomDir.dz;

            for (int y = 0; y < thirdFloorGrid.height; y++) {
                for (int xx = 0; xx < thirdFloorGrid.width; xx++) {
                    if (!isHouse(baseGrid, xx, y)) {
                        thirdFloorGrid.set(xx, y, BLOCKED);
                    } else if (xx == roomPos[0] && y == roomPos[1]) {
                        thirdFloorGrid.set(xx, y, START_ROOM);
                    } else if (xx == roomEndX && y == roomEndY) {
                        thirdFloorGrid.set(xx, y, START_ROOM);
                        floorRooms[2].set(xx, y, ROOM_CORRIDOR_FLAG);
                    }
                }
            }

            List<VTemplate.Dir> potentialCorridors = new ArrayList<>();
            for (VTemplate.Dir d : HORIZONTAL) {
                if (thirdFloorGrid.get(roomEndX + d.dx, roomEndY + d.dz) == CLEAR) potentialCorridors.add(d);
            }
            if (potentialCorridors.isEmpty()) {
                thirdFloorGrid.set(0, 0, thirdFloorGrid.width, thirdFloorGrid.height, BLOCKED);
                floor.set(roomPos[0], roomPos[1], roomData);
                return;
            }
            VTemplate.Dir corridorDir = potentialCorridors.get(random.nextInt(potentialCorridors.size()));
            recursiveCorridor(thirdFloorGrid, roomEndX + corridorDir.dx, roomEndY + corridorDir.dz, corridorDir, 4);
            while (cleanEdges(thirdFloorGrid)) { /* iterate to fixpoint */ }
        }

        private void identifyRooms(SimpleGrid fromGrid, SimpleGrid roomGrid) {
            List<int[]> roomPos = new ArrayList<>();
            for (int y = 0; y < fromGrid.height; y++) {
                for (int x = 0; x < fromGrid.width; x++) {
                    if (fromGrid.get(x, y) == ROOM) roomPos.add(new int[]{x, y});
                }
            }
            shuffle(roomPos, random);
            int roomId = 10;
            for (int[] pos : roomPos) {
                int xx = pos[0], y = pos[1];
                if (roomGrid.get(xx, y) != CLEAR) continue;
                int x0 = xx, x1 = xx, y0 = y, y1 = y, type = ROOM_1x1;
                if (roomGrid.get(xx + 1, y) == CLEAR && roomGrid.get(xx, y + 1) == CLEAR && roomGrid.get(xx + 1, y + 1) == CLEAR
                        && fromGrid.get(xx + 1, y) == ROOM && fromGrid.get(xx, y + 1) == ROOM && fromGrid.get(xx + 1, y + 1) == ROOM) {
                    x1 = xx + 1; y1 = y + 1; type = ROOM_2x2;
                } else if (roomGrid.get(xx - 1, y) == CLEAR && roomGrid.get(xx, y + 1) == CLEAR && roomGrid.get(xx - 1, y + 1) == CLEAR
                        && fromGrid.get(xx - 1, y) == ROOM && fromGrid.get(xx, y + 1) == ROOM && fromGrid.get(xx - 1, y + 1) == ROOM) {
                    x0 = xx - 1; y1 = y + 1; type = ROOM_2x2;
                } else if (roomGrid.get(xx - 1, y) == CLEAR && roomGrid.get(xx, y - 1) == CLEAR && roomGrid.get(xx - 1, y - 1) == CLEAR
                        && fromGrid.get(xx - 1, y) == ROOM && fromGrid.get(xx, y - 1) == ROOM && fromGrid.get(xx - 1, y - 1) == ROOM) {
                    x0 = xx - 1; y0 = y - 1; type = ROOM_2x2;
                } else if (roomGrid.get(xx + 1, y) == CLEAR && fromGrid.get(xx + 1, y) == ROOM) {
                    x1 = xx + 1; type = ROOM_1x2;
                } else if (roomGrid.get(xx, y + 1) == CLEAR && fromGrid.get(xx, y + 1) == ROOM) {
                    y1 = y + 1; type = ROOM_1x2;
                } else if (roomGrid.get(xx - 1, y) == CLEAR && fromGrid.get(xx - 1, y) == ROOM) {
                    x0 = xx - 1; type = ROOM_1x2;
                } else if (roomGrid.get(xx, y - 1) == CLEAR && fromGrid.get(xx, y - 1) == ROOM) {
                    y0 = y - 1; type = ROOM_1x2;
                }

                int doorX = random.nextBoolean() ? x0 : x1;
                int doorY = random.nextBoolean() ? y0 : y1;
                int doorFlag = ROOM_DOOR_FLAG;
                if (!fromGrid.edgesTo(doorX, doorY, CORRIDOR)) {
                    doorX = doorX == x0 ? x1 : x0;
                    doorY = doorY == y0 ? y1 : y0;
                    if (!fromGrid.edgesTo(doorX, doorY, CORRIDOR)) {
                        doorY = doorY == y0 ? y1 : y0;
                        if (!fromGrid.edgesTo(doorX, doorY, CORRIDOR)) {
                            doorX = doorX == x0 ? x1 : x0;
                            doorY = doorY == y0 ? y1 : y0;
                            if (!fromGrid.edgesTo(doorX, doorY, CORRIDOR)) {
                                doorFlag = 0;
                                doorX = x0; doorY = y0;
                            }
                        }
                    }
                }

                for (int ry = y0; ry <= y1; ry++) {
                    for (int rx = x0; rx <= x1; rx++) {
                        if (rx == doorX && ry == doorY) roomGrid.set(rx, ry, ROOM_ORIGIN_FLAG | doorFlag | type | roomId);
                        else roomGrid.set(rx, ry, type | roomId);
                    }
                }
                roomId++;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final VTemplate.Dir[] HORIZONTAL = {VTemplate.Dir.NORTH, VTemplate.Dir.SOUTH, VTemplate.Dir.WEST, VTemplate.Dir.EAST};

    /** Direction.from2DDataValue: order SOUTH,WEST,NORTH,EAST (same mapping used throughout this project). */
    private static VTemplate.Dir horizontalFrom2D(int v) {
        return switch (v) {
            case 0 -> VTemplate.Dir.SOUTH;
            case 1 -> VTemplate.Dir.WEST;
            case 2 -> VTemplate.Dir.NORTH;
            default -> VTemplate.Dir.EAST;
        };
    }

    /** Util.shuffle: real vanilla's in-place Fisher-Yates over a RandomSource. */
    private static void shuffle(List<int[]> list, VSurface.LegacyRandom random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    // ------------------------------------------------------------------ test hooks

    public static MansionGrid testGenerate(long seed, int chunkX, int chunkZ) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, chunkX, chunkZ);
        return new MansionGrid(random);
    }

    // ------------------------------------------------------------------ piece placement (stage 2)

    /** A placed NBT-template piece: real vanilla's WoodlandMansionPiece (position + rotation + mirror). */
    public record Piece(VTemplate template, int baseX, int baseY, int baseZ, VTemplate.Rot rotation, VTemplate.Mirror mirror) {}

    public static final class Pos {
        public int x, y, z;
        public Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        Pos copy() { return new Pos(x, y, z); }
        Pos relative(VTemplate.Dir dir, int n) { return new Pos(x + dir.dx * n, y, z + dir.dz * n); }
        Pos above(int n) { return new Pos(x, y + n, z); }
        Pos offset(int dx, int dy, int dz) { return new Pos(x + dx, y + dy, z + dz); }
    }

    private static final class PlacementData {
        Pos position;
        VTemplate.Rot rotation;
        String wallType;
    }

    private static void add(List<Piece> pieces, String name, Pos p, VTemplate.Rot rot) {
        add(pieces, name, p, rot, VTemplate.Mirror.NONE);
    }

    private static void add(List<Piece> pieces, String name, Pos p, VTemplate.Rot rot, VTemplate.Mirror mirror) {
        pieces.add(new Piece(VTemplate.load("minecraft:woodland_mansion/" + name), p.x, p.y, p.z, rot, mirror));
    }

    // -------- FloorRoomCollection: per-floor template-name pools

    private interface FloorRoomCollection {
        String get1x1(VSurface.LegacyRandom random);
        String get1x1Secret(VSurface.LegacyRandom random);
        String get1x2SideEntrance(VSurface.LegacyRandom random, boolean isStairsRoom);
        String get1x2FrontEntrance(VSurface.LegacyRandom random, boolean isStairsRoom);
        String get1x2Secret(VSurface.LegacyRandom random);
        String get2x2(VSurface.LegacyRandom random);
        String get2x2Secret(VSurface.LegacyRandom random);
    }

    private static final class FirstFloorRoomCollection implements FloorRoomCollection {
        public String get1x1(VSurface.LegacyRandom random) { return "1x1_a" + (random.nextInt(5) + 1); }
        public String get1x1Secret(VSurface.LegacyRandom random) { return "1x1_as" + (random.nextInt(4) + 1); }
        public String get1x2SideEntrance(VSurface.LegacyRandom random, boolean isStairsRoom) { return "1x2_a" + (random.nextInt(9) + 1); }
        public String get1x2FrontEntrance(VSurface.LegacyRandom random, boolean isStairsRoom) { return "1x2_b" + (random.nextInt(5) + 1); }
        public String get1x2Secret(VSurface.LegacyRandom random) { return "1x2_s" + (random.nextInt(2) + 1); }
        public String get2x2(VSurface.LegacyRandom random) { return "2x2_a" + (random.nextInt(4) + 1); }
        public String get2x2Secret(VSurface.LegacyRandom random) { return "2x2_s1"; }
    }

    /** Second AND third floor share this exact pool (real vanilla's ThirdFloorRoomCollection extends this with no overrides). */
    private static final class SecondFloorRoomCollection implements FloorRoomCollection {
        public String get1x1(VSurface.LegacyRandom random) { return "1x1_b" + (random.nextInt(5) + 1); }
        public String get1x1Secret(VSurface.LegacyRandom random) { return "1x1_as" + (random.nextInt(4) + 1); }
        public String get1x2SideEntrance(VSurface.LegacyRandom random, boolean isStairsRoom) {
            return isStairsRoom ? "1x2_c_stairs" : "1x2_c" + (random.nextInt(4) + 1);
        }
        public String get1x2FrontEntrance(VSurface.LegacyRandom random, boolean isStairsRoom) {
            return isStairsRoom ? "1x2_d_stairs" : "1x2_d" + (random.nextInt(5) + 1);
        }
        public String get1x2Secret(VSurface.LegacyRandom random) { return "1x2_se" + (random.nextInt(1) + 1); }
        public String get2x2(VSurface.LegacyRandom random) { return "2x2_b" + (random.nextInt(5) + 1); }
        public String get2x2Secret(VSurface.LegacyRandom random) { return "2x2_s1"; }
    }

    // -------- MansionPiecePlacer

    private static final class MansionPiecePlacer {
        private final VSurface.LegacyRandom random;
        private int startX, startY;

        MansionPiecePlacer(VSurface.LegacyRandom random) { this.random = random; }

        List<Piece> createMansion(Pos origin, VTemplate.Rot rotation, MansionGrid mansion) {
            List<Piece> pieces = new ArrayList<>();
            PlacementData data = new PlacementData();
            data.position = origin.copy();
            data.rotation = rotation;
            data.wallType = "wall_flat";
            PlacementData secondData = new PlacementData();
            entrance(pieces, data);
            secondData.position = data.position.above(8);
            secondData.rotation = data.rotation;
            secondData.wallType = "wall_window";

            SimpleGrid baseGrid = mansion.baseGrid;
            SimpleGrid thirdGrid = mansion.thirdFloorGrid;
            startX = mansion.entranceX + 1;
            startY = mansion.entranceY + 1;
            int endX = mansion.entranceX + 1, endY = mansion.entranceY;
            traverseOuterWalls(pieces, data, baseGrid, VTemplate.Dir.SOUTH, startX, startY, endX, endY);
            traverseOuterWalls(pieces, secondData, baseGrid, VTemplate.Dir.SOUTH, startX, startY, endX, endY);

            PlacementData thirdData = new PlacementData();
            thirdData.position = data.position.above(19);
            thirdData.rotation = data.rotation;
            thirdData.wallType = "wall_window";
            boolean done = false;
            for (int y = 0; y < thirdGrid.height && !done; y++) {
                for (int x = thirdGrid.width - 1; x >= 0 && !done; x--) {
                    if (isHouse(thirdGrid, x, y)) {
                        thirdData.position = thirdData.position.relative(rotation.rotate(VTemplate.Dir.SOUTH), 8 + (y - startY) * 8);
                        thirdData.position = thirdData.position.relative(rotation.rotate(VTemplate.Dir.EAST), (x - startX) * 8);
                        traverseWallPiece(pieces, thirdData);
                        traverseOuterWalls(pieces, thirdData, thirdGrid, VTemplate.Dir.SOUTH, x, y, x, y);
                        done = true;
                    }
                }
            }

            createRoof(pieces, origin.above(16), rotation, baseGrid, thirdGrid);
            createRoof(pieces, origin.above(27), rotation, thirdGrid, null);

            FloorRoomCollection[] roomCollections = {new FirstFloorRoomCollection(), new SecondFloorRoomCollection(), new SecondFloorRoomCollection()};

            for (int floorNum = 0; floorNum < 3; floorNum++) {
                Pos floorOrigin = origin.above(8 * floorNum + (floorNum == 2 ? 3 : 0));
                SimpleGrid rooms = mansion.floorRooms[floorNum];
                SimpleGrid grid = floorNum == 2 ? thirdGrid : baseGrid;
                String southPiece = floorNum == 0 ? "carpet_south_1" : "carpet_south_2";
                String westPiece = floorNum == 0 ? "carpet_west_1" : "carpet_west_2";

                for (int y = 0; y < grid.height; y++) {
                    for (int xx = 0; xx < grid.width; xx++) {
                        if (grid.get(xx, y) != CORRIDOR) continue;
                        Pos pos = floorOrigin.relative(rotation.rotate(VTemplate.Dir.SOUTH), 8 + (y - startY) * 8);
                        pos = pos.relative(rotation.rotate(VTemplate.Dir.EAST), (xx - startX) * 8);
                        add(pieces, "corridor_floor", pos, rotation);
                        if (grid.get(xx, y - 1) == CORRIDOR || (rooms.get(xx, y - 1) & ROOM_CORRIDOR_FLAG) == ROOM_CORRIDOR_FLAG) {
                            add(pieces, "carpet_north", pos.relative(rotation.rotate(VTemplate.Dir.EAST), 1).above(1), rotation);
                        }
                        if (grid.get(xx + 1, y) == CORRIDOR || (rooms.get(xx + 1, y) & ROOM_CORRIDOR_FLAG) == ROOM_CORRIDOR_FLAG) {
                            Pos p2 = pos.relative(rotation.rotate(VTemplate.Dir.SOUTH), 1).relative(rotation.rotate(VTemplate.Dir.EAST), 5).above(1);
                            add(pieces, "carpet_east", p2, rotation);
                        }
                        if (grid.get(xx, y + 1) == CORRIDOR || (rooms.get(xx, y + 1) & ROOM_CORRIDOR_FLAG) == ROOM_CORRIDOR_FLAG) {
                            Pos p2 = pos.relative(rotation.rotate(VTemplate.Dir.SOUTH), 5).relative(rotation.rotate(VTemplate.Dir.WEST), 1);
                            add(pieces, southPiece, p2, rotation);
                        }
                        if (grid.get(xx - 1, y) == CORRIDOR || (rooms.get(xx - 1, y) & ROOM_CORRIDOR_FLAG) == ROOM_CORRIDOR_FLAG) {
                            Pos p2 = pos.relative(rotation.rotate(VTemplate.Dir.WEST), 1).relative(rotation.rotate(VTemplate.Dir.NORTH), 1);
                            add(pieces, westPiece, p2, rotation);
                        }
                    }
                }

                String wallPiece = floorNum == 0 ? "indoors_wall_1" : "indoors_wall_2";
                String doorPiece = floorNum == 0 ? "indoors_door_1" : "indoors_door_2";
                List<VTemplate.Dir> doorDirs = new ArrayList<>();

                for (int y = 0; y < grid.height; y++) {
                    for (int xxx = 0; xxx < grid.width; xxx++) {
                        boolean thirdFloorStartRoom = floorNum == 2 && grid.get(xxx, y) == START_ROOM;
                        if (grid.get(xxx, y) != ROOM && !thirdFloorStartRoom) continue;
                        int roomData = rooms.get(xxx, y);
                        int roomType = roomData & ROOM_TYPE_MASK;
                        int roomId = roomData & ROOM_ID_MASK;
                        thirdFloorStartRoom = thirdFloorStartRoom && (roomData & ROOM_CORRIDOR_FLAG) == ROOM_CORRIDOR_FLAG;
                        doorDirs.clear();
                        if ((roomData & ROOM_DOOR_FLAG) == ROOM_DOOR_FLAG) {
                            for (VTemplate.Dir d : HORIZONTAL) {
                                if (grid.get(xxx + d.dx, y + d.dz) == CORRIDOR) doorDirs.add(d);
                            }
                        }

                        VTemplate.Dir doorDir = null;
                        if (!doorDirs.isEmpty()) doorDir = doorDirs.get(random.nextInt(doorDirs.size()));
                        else if ((roomData & ROOM_ORIGIN_FLAG) == ROOM_ORIGIN_FLAG) doorDir = VTemplate.Dir.UP;

                        Pos roomPos = floorOrigin.relative(rotation.rotate(VTemplate.Dir.SOUTH), 8 + (y - startY) * 8);
                        roomPos = roomPos.relative(rotation.rotate(VTemplate.Dir.EAST), -1 + (xxx - startX) * 8);

                        if (isHouse(grid, xxx - 1, y) && !mansion.isRoomId(xxx - 1, y, floorNum, roomId)) {
                            add(pieces, doorDir == VTemplate.Dir.WEST ? doorPiece : wallPiece, roomPos, rotation);
                        }
                        if (grid.get(xxx + 1, y) == CORRIDOR && !thirdFloorStartRoom) {
                            Pos posx = roomPos.relative(rotation.rotate(VTemplate.Dir.EAST), 8);
                            add(pieces, doorDir == VTemplate.Dir.EAST ? doorPiece : wallPiece, posx, rotation);
                        }
                        if (isHouse(grid, xxx, y + 1) && !mansion.isRoomId(xxx, y + 1, floorNum, roomId)) {
                            Pos posx = roomPos.relative(rotation.rotate(VTemplate.Dir.SOUTH), 7).relative(rotation.rotate(VTemplate.Dir.EAST), 7);
                            add(pieces, doorDir == VTemplate.Dir.SOUTH ? doorPiece : wallPiece, posx, rotation.getRotated(VTemplate.Rot.CLOCKWISE_90));
                        }
                        if (grid.get(xxx, y - 1) == CORRIDOR && !thirdFloorStartRoom) {
                            Pos posx = roomPos.relative(rotation.rotate(VTemplate.Dir.NORTH), 1).relative(rotation.rotate(VTemplate.Dir.EAST), 7);
                            add(pieces, doorDir == VTemplate.Dir.NORTH ? doorPiece : wallPiece, posx, rotation.getRotated(VTemplate.Rot.CLOCKWISE_90));
                        }

                        if (roomType == ROOM_1x1) {
                            addRoom1x1(pieces, roomPos, rotation, doorDir, roomCollections[floorNum]);
                        } else if (roomType == ROOM_1x2 && doorDir != null) {
                            VTemplate.Dir roomDir = mansion.get1x2RoomDirection(xxx, y, floorNum, roomId);
                            boolean isStairsRoom = (roomData & ROOM_STAIRS_FLAG) == ROOM_STAIRS_FLAG;
                            addRoom1x2(pieces, roomPos, rotation, roomDir, doorDir, roomCollections[floorNum], isStairsRoom);
                        } else if (roomType == ROOM_2x2 && doorDir != null && doorDir != VTemplate.Dir.UP) {
                            VTemplate.Dir roomDir = doorDir.clockWiseY();
                            if (!mansion.isRoomId(xxx + roomDir.dx, y + roomDir.dz, floorNum, roomId)) roomDir = roomDir.opposite();
                            addRoom2x2(pieces, roomPos, rotation, roomDir, doorDir, roomCollections[floorNum]);
                        } else if (roomType == ROOM_2x2 && doorDir == VTemplate.Dir.UP) {
                            addRoom2x2Secret(pieces, roomPos, rotation, roomCollections[floorNum]);
                        }
                    }
                }
            }
            return pieces;
        }

        private void traverseOuterWalls(List<Piece> pieces, PlacementData data, SimpleGrid grid, VTemplate.Dir gridDirection,
                                         int startX, int startY, int endX, int endY) {
            int gridX = startX, gridY = startY;
            VTemplate.Dir startDirection = gridDirection;
            do {
                if (!isHouse(grid, gridX + gridDirection.dx, gridY + gridDirection.dz)) {
                    traverseTurn(pieces, data);
                    gridDirection = gridDirection.clockWiseY();
                    if (gridX != endX || gridY != endY || startDirection != gridDirection) traverseWallPiece(pieces, data);
                } else if (isHouse(grid, gridX + gridDirection.dx + gridDirection.counterClockWiseY().dx,
                        gridY + gridDirection.dz + gridDirection.counterClockWiseY().dz)) {
                    traverseInnerTurn(pieces, data);
                    gridX += gridDirection.dx; gridY += gridDirection.dz;
                    gridDirection = gridDirection.counterClockWiseY();
                } else {
                    gridX += gridDirection.dx; gridY += gridDirection.dz;
                    if (gridX != endX || gridY != endY || startDirection != gridDirection) traverseWallPiece(pieces, data);
                }
            } while (gridX != endX || gridY != endY || startDirection != gridDirection);
        }

        private void createRoof(List<Piece> pieces, Pos roofOrigin, VTemplate.Rot rotation, SimpleGrid grid, SimpleGrid aboveGrid) {
            for (int y = 0; y < grid.height; y++) {
                for (int x = 0; x < grid.width; x++) {
                    Pos position = roofOrigin.relative(rotation.rotate(VTemplate.Dir.SOUTH), 8 + (y - startY) * 8);
                    position = position.relative(rotation.rotate(VTemplate.Dir.EAST), (x - startX) * 8);
                    boolean isAbove = aboveGrid != null && isHouse(aboveGrid, x, y);
                    if (!isHouse(grid, x, y) || isAbove) continue;
                    add(pieces, "roof", position.above(3), rotation);
                    if (!isHouse(grid, x + 1, y)) add(pieces, "roof_front", position.relative(rotation.rotate(VTemplate.Dir.EAST), 6), rotation);
                    if (!isHouse(grid, x - 1, y)) {
                        Pos p2 = position.relative(rotation.rotate(VTemplate.Dir.EAST), 0).relative(rotation.rotate(VTemplate.Dir.SOUTH), 7);
                        add(pieces, "roof_front", p2, rotation.getRotated(VTemplate.Rot.CLOCKWISE_180));
                    }
                    if (!isHouse(grid, x, y - 1)) {
                        Pos p2 = position.relative(rotation.rotate(VTemplate.Dir.WEST), 1);
                        add(pieces, "roof_front", p2, rotation.getRotated(VTemplate.Rot.COUNTERCLOCKWISE_90));
                    }
                    if (!isHouse(grid, x, y + 1)) {
                        Pos p2 = position.relative(rotation.rotate(VTemplate.Dir.EAST), 6).relative(rotation.rotate(VTemplate.Dir.SOUTH), 6);
                        add(pieces, "roof_front", p2, rotation.getRotated(VTemplate.Rot.CLOCKWISE_90));
                    }
                }
            }

            if (aboveGrid != null) {
                for (int y = 0; y < grid.height; y++) {
                    for (int xx = 0; xx < grid.width; xx++) {
                        Pos p0 = roofOrigin.relative(rotation.rotate(VTemplate.Dir.SOUTH), 8 + (y - startY) * 8);
                        p0 = p0.relative(rotation.rotate(VTemplate.Dir.EAST), (xx - startX) * 8);
                        boolean isAbove = isHouse(aboveGrid, xx, y);
                        if (!(isHouse(grid, xx, y) && isAbove)) continue;
                        if (!isHouse(grid, xx + 1, y)) add(pieces, "small_wall", p0.relative(rotation.rotate(VTemplate.Dir.EAST), 7), rotation);
                        if (!isHouse(grid, xx - 1, y)) {
                            Pos p2 = p0.relative(rotation.rotate(VTemplate.Dir.WEST), 1).relative(rotation.rotate(VTemplate.Dir.SOUTH), 6);
                            add(pieces, "small_wall", p2, rotation.getRotated(VTemplate.Rot.CLOCKWISE_180));
                        }
                        if (!isHouse(grid, xx, y - 1)) {
                            Pos p2 = p0.relative(rotation.rotate(VTemplate.Dir.WEST), 0).relative(rotation.rotate(VTemplate.Dir.NORTH), 1);
                            add(pieces, "small_wall", p2, rotation.getRotated(VTemplate.Rot.COUNTERCLOCKWISE_90));
                        }
                        if (!isHouse(grid, xx, y + 1)) {
                            Pos p2 = p0.relative(rotation.rotate(VTemplate.Dir.EAST), 6).relative(rotation.rotate(VTemplate.Dir.SOUTH), 7);
                            add(pieces, "small_wall", p2, rotation.getRotated(VTemplate.Rot.CLOCKWISE_90));
                        }
                        if (!isHouse(grid, xx + 1, y)) {
                            if (!isHouse(grid, xx, y - 1)) {
                                Pos p2 = p0.relative(rotation.rotate(VTemplate.Dir.EAST), 7).relative(rotation.rotate(VTemplate.Dir.NORTH), 2);
                                add(pieces, "small_wall_corner", p2, rotation);
                            }
                            if (!isHouse(grid, xx, y + 1)) {
                                Pos p2 = p0.relative(rotation.rotate(VTemplate.Dir.EAST), 8).relative(rotation.rotate(VTemplate.Dir.SOUTH), 7);
                                add(pieces, "small_wall_corner", p2, rotation.getRotated(VTemplate.Rot.CLOCKWISE_90));
                            }
                        }
                        if (!isHouse(grid, xx - 1, y)) {
                            if (!isHouse(grid, xx, y - 1)) {
                                Pos p2 = p0.relative(rotation.rotate(VTemplate.Dir.WEST), 2).relative(rotation.rotate(VTemplate.Dir.NORTH), 1);
                                add(pieces, "small_wall_corner", p2, rotation.getRotated(VTemplate.Rot.COUNTERCLOCKWISE_90));
                            }
                            if (!isHouse(grid, xx, y + 1)) {
                                Pos p2 = p0.relative(rotation.rotate(VTemplate.Dir.WEST), 1).relative(rotation.rotate(VTemplate.Dir.SOUTH), 8);
                                add(pieces, "small_wall_corner", p2, rotation.getRotated(VTemplate.Rot.CLOCKWISE_180));
                            }
                        }
                    }
                }
            }

            for (int y = 0; y < grid.height; y++) {
                for (int xxx = 0; xxx < grid.width; xxx++) {
                    Pos p0 = roofOrigin.relative(rotation.rotate(VTemplate.Dir.SOUTH), 8 + (y - startY) * 8);
                    p0 = p0.relative(rotation.rotate(VTemplate.Dir.EAST), (xxx - startX) * 8);
                    boolean isAbove = aboveGrid != null && isHouse(aboveGrid, xxx, y);
                    if (!isHouse(grid, xxx, y) || isAbove) continue;
                    if (!isHouse(grid, xxx + 1, y)) {
                        Pos p2 = p0.relative(rotation.rotate(VTemplate.Dir.EAST), 6);
                        if (!isHouse(grid, xxx, y + 1)) {
                            add(pieces, "roof_corner", p2.relative(rotation.rotate(VTemplate.Dir.SOUTH), 6), rotation);
                        } else if (isHouse(grid, xxx + 1, y + 1)) {
                            add(pieces, "roof_inner_corner", p2.relative(rotation.rotate(VTemplate.Dir.SOUTH), 5), rotation);
                        }
                        if (!isHouse(grid, xxx, y - 1)) {
                            add(pieces, "roof_corner", p2, rotation.getRotated(VTemplate.Rot.COUNTERCLOCKWISE_90));
                        } else if (isHouse(grid, xxx + 1, y - 1)) {
                            Pos p3 = p0.relative(rotation.rotate(VTemplate.Dir.EAST), 9).relative(rotation.rotate(VTemplate.Dir.NORTH), 2);
                            add(pieces, "roof_inner_corner", p3, rotation.getRotated(VTemplate.Rot.CLOCKWISE_90));
                        }
                    }
                    if (!isHouse(grid, xxx - 1, y)) {
                        Pos p2 = p0.relative(rotation.rotate(VTemplate.Dir.EAST), 0).relative(rotation.rotate(VTemplate.Dir.SOUTH), 0);
                        if (!isHouse(grid, xxx, y + 1)) {
                            add(pieces, "roof_corner", p2.relative(rotation.rotate(VTemplate.Dir.SOUTH), 6), rotation.getRotated(VTemplate.Rot.CLOCKWISE_90));
                        } else if (isHouse(grid, xxx - 1, y + 1)) {
                            Pos p3 = p2.relative(rotation.rotate(VTemplate.Dir.SOUTH), 8).relative(rotation.rotate(VTemplate.Dir.WEST), 3);
                            add(pieces, "roof_inner_corner", p3, rotation.getRotated(VTemplate.Rot.COUNTERCLOCKWISE_90));
                        }
                        if (!isHouse(grid, xxx, y - 1)) {
                            add(pieces, "roof_corner", p2, rotation.getRotated(VTemplate.Rot.CLOCKWISE_180));
                        } else if (isHouse(grid, xxx - 1, y - 1)) {
                            Pos p3 = p2.relative(rotation.rotate(VTemplate.Dir.SOUTH), 1);
                            add(pieces, "roof_inner_corner", p3, rotation.getRotated(VTemplate.Rot.CLOCKWISE_180));
                        }
                    }
                }
            }
        }

        private void entrance(List<Piece> pieces, PlacementData data) {
            VTemplate.Dir west = data.rotation.rotate(VTemplate.Dir.WEST);
            add(pieces, "entrance", data.position.relative(west, 9), data.rotation);
            data.position = data.position.relative(data.rotation.rotate(VTemplate.Dir.SOUTH), 16);
        }

        private void traverseWallPiece(List<Piece> pieces, PlacementData data) {
            add(pieces, data.wallType, data.position.relative(data.rotation.rotate(VTemplate.Dir.EAST), 7), data.rotation);
            data.position = data.position.relative(data.rotation.rotate(VTemplate.Dir.SOUTH), 8);
        }

        private void traverseTurn(List<Piece> pieces, PlacementData data) {
            data.position = data.position.relative(data.rotation.rotate(VTemplate.Dir.SOUTH), -1);
            add(pieces, "wall_corner", data.position, data.rotation);
            data.position = data.position.relative(data.rotation.rotate(VTemplate.Dir.SOUTH), -7);
            data.position = data.position.relative(data.rotation.rotate(VTemplate.Dir.WEST), -6);
            data.rotation = data.rotation.getRotated(VTemplate.Rot.CLOCKWISE_90);
        }

        private void traverseInnerTurn(List<Piece> pieces, PlacementData data) {
            data.position = data.position.relative(data.rotation.rotate(VTemplate.Dir.SOUTH), 6);
            data.position = data.position.relative(data.rotation.rotate(VTemplate.Dir.EAST), 8);
            data.rotation = data.rotation.getRotated(VTemplate.Rot.COUNTERCLOCKWISE_90);
        }

        private void addRoom1x1(List<Piece> pieces, Pos roomPos, VTemplate.Rot rotation, VTemplate.Dir doorDir, FloorRoomCollection rooms) {
            VTemplate.Rot pieceRot = VTemplate.Rot.NONE;
            String roomType = rooms.get1x1(random);
            if (doorDir != VTemplate.Dir.EAST) {
                if (doorDir == VTemplate.Dir.NORTH) pieceRot = pieceRot.getRotated(VTemplate.Rot.COUNTERCLOCKWISE_90);
                else if (doorDir == VTemplate.Dir.WEST) pieceRot = pieceRot.getRotated(VTemplate.Rot.CLOCKWISE_180);
                else if (doorDir == VTemplate.Dir.SOUTH) pieceRot = pieceRot.getRotated(VTemplate.Rot.CLOCKWISE_90);
                else roomType = rooms.get1x1Secret(random);
            }
            // StructureTemplate.getZeroPositionWithTransform(BlockPos(1,0,0), NONE, pieceRot, 7, 7)
            int[] o1 = VTemplate.transform(1, 0, 0, pieceRot, 7, 7);
            VTemplate.Rot finalRot = pieceRot.getRotated(rotation);
            // BlockPos.rotate(rotation): a plain origin-pivoted rotation of the resulting vector
            int[] o2 = VTemplate.transform(o1[0], o1[1], o1[2], rotation, 0, 0);
            Pos pos = roomPos.offset(o2[0], 0, o2[2]);
            add(pieces, roomType, pos, finalRot);
        }

        private void addRoom1x2(List<Piece> pieces, Pos roomPos, VTemplate.Rot rotation, VTemplate.Dir roomDir, VTemplate.Dir doorDir,
                                 FloorRoomCollection rooms, boolean isStairsRoom) {
            VTemplate.Dir E = VTemplate.Dir.EAST, N = VTemplate.Dir.NORTH, S = VTemplate.Dir.SOUTH, W = VTemplate.Dir.WEST, U = VTemplate.Dir.UP;
            if (doorDir == E && roomDir == S) {
                add(pieces, rooms.get1x2SideEntrance(random, isStairsRoom), roomPos.relative(rotation.rotate(E), 1), rotation);
            } else if (doorDir == E && roomDir == N) {
                Pos pos = roomPos.relative(rotation.rotate(E), 1).relative(rotation.rotate(S), 6);
                add(pieces, rooms.get1x2SideEntrance(random, isStairsRoom), pos, rotation, VTemplate.Mirror.LEFT_RIGHT);
            } else if (doorDir == W && roomDir == N) {
                Pos pos = roomPos.relative(rotation.rotate(E), 7).relative(rotation.rotate(S), 6);
                add(pieces, rooms.get1x2SideEntrance(random, isStairsRoom), pos, rotation.getRotated(VTemplate.Rot.CLOCKWISE_180));
            } else if (doorDir == W && roomDir == S) {
                add(pieces, rooms.get1x2SideEntrance(random, isStairsRoom), roomPos.relative(rotation.rotate(E), 7), rotation, VTemplate.Mirror.FRONT_BACK);
            } else if (doorDir == S && roomDir == E) {
                Pos pos = roomPos.relative(rotation.rotate(E), 1);
                add(pieces, rooms.get1x2SideEntrance(random, isStairsRoom), pos, rotation.getRotated(VTemplate.Rot.CLOCKWISE_90), VTemplate.Mirror.LEFT_RIGHT);
            } else if (doorDir == S && roomDir == W) {
                Pos pos = roomPos.relative(rotation.rotate(E), 7);
                add(pieces, rooms.get1x2SideEntrance(random, isStairsRoom), pos, rotation.getRotated(VTemplate.Rot.CLOCKWISE_90));
            } else if (doorDir == N && roomDir == W) {
                Pos pos = roomPos.relative(rotation.rotate(E), 7).relative(rotation.rotate(S), 6);
                add(pieces, rooms.get1x2SideEntrance(random, isStairsRoom), pos, rotation.getRotated(VTemplate.Rot.CLOCKWISE_90), VTemplate.Mirror.FRONT_BACK);
            } else if (doorDir == N && roomDir == E) {
                Pos pos = roomPos.relative(rotation.rotate(E), 1).relative(rotation.rotate(S), 6);
                add(pieces, rooms.get1x2SideEntrance(random, isStairsRoom), pos, rotation.getRotated(VTemplate.Rot.COUNTERCLOCKWISE_90));
            } else if (doorDir == S && roomDir == N) {
                Pos pos = roomPos.relative(rotation.rotate(E), 1).relative(rotation.rotate(N), 8);
                add(pieces, rooms.get1x2FrontEntrance(random, isStairsRoom), pos, rotation);
            } else if (doorDir == N && roomDir == S) {
                Pos pos = roomPos.relative(rotation.rotate(E), 7).relative(rotation.rotate(S), 14);
                add(pieces, rooms.get1x2FrontEntrance(random, isStairsRoom), pos, rotation.getRotated(VTemplate.Rot.CLOCKWISE_180));
            } else if (doorDir == W && roomDir == E) {
                Pos pos = roomPos.relative(rotation.rotate(E), 15);
                add(pieces, rooms.get1x2FrontEntrance(random, isStairsRoom), pos, rotation.getRotated(VTemplate.Rot.CLOCKWISE_90));
            } else if (doorDir == E && roomDir == W) {
                Pos pos = roomPos.relative(rotation.rotate(W), 7).relative(rotation.rotate(S), 6);
                add(pieces, rooms.get1x2FrontEntrance(random, isStairsRoom), pos, rotation.getRotated(VTemplate.Rot.COUNTERCLOCKWISE_90));
            } else if (doorDir == U && roomDir == E) {
                Pos pos = roomPos.relative(rotation.rotate(E), 15);
                add(pieces, rooms.get1x2Secret(random), pos, rotation.getRotated(VTemplate.Rot.CLOCKWISE_90));
            } else if (doorDir == U && roomDir == S) {
                Pos pos = roomPos.relative(rotation.rotate(E), 1).relative(rotation.rotate(N), 0);
                add(pieces, rooms.get1x2Secret(random), pos, rotation);
            }
        }

        private void addRoom2x2(List<Piece> pieces, Pos roomPos, VTemplate.Rot rotation, VTemplate.Dir roomDir, VTemplate.Dir doorDir, FloorRoomCollection rooms) {
            VTemplate.Dir E = VTemplate.Dir.EAST, N = VTemplate.Dir.NORTH, S = VTemplate.Dir.SOUTH, W = VTemplate.Dir.WEST;
            int east = 0, south = 0;
            VTemplate.Rot rot = rotation;
            VTemplate.Mirror mirror = VTemplate.Mirror.NONE;
            if (doorDir == E && roomDir == S) {
                east = -7;
            } else if (doorDir == E && roomDir == N) {
                east = -7; south = 6; mirror = VTemplate.Mirror.LEFT_RIGHT;
            } else if (doorDir == N && roomDir == E) {
                east = 1; south = 14; rot = rotation.getRotated(VTemplate.Rot.COUNTERCLOCKWISE_90);
            } else if (doorDir == N && roomDir == W) {
                east = 7; south = 14; rot = rotation.getRotated(VTemplate.Rot.COUNTERCLOCKWISE_90); mirror = VTemplate.Mirror.LEFT_RIGHT;
            } else if (doorDir == S && roomDir == W) {
                east = 7; south = -8; rot = rotation.getRotated(VTemplate.Rot.CLOCKWISE_90);
            } else if (doorDir == S && roomDir == E) {
                east = 1; south = -8; rot = rotation.getRotated(VTemplate.Rot.CLOCKWISE_90); mirror = VTemplate.Mirror.LEFT_RIGHT;
            } else if (doorDir == W && roomDir == N) {
                east = 15; south = 6; rot = rotation.getRotated(VTemplate.Rot.CLOCKWISE_180);
            } else if (doorDir == W && roomDir == S) {
                east = 15; mirror = VTemplate.Mirror.FRONT_BACK;
            }
            Pos pos = roomPos.relative(rotation.rotate(E), east).relative(rotation.rotate(S), south);
            add(pieces, rooms.get2x2(random), pos, rot, mirror);
        }

        private void addRoom2x2Secret(List<Piece> pieces, Pos roomPos, VTemplate.Rot rotation, FloorRoomCollection rooms) {
            Pos pos = roomPos.relative(rotation.rotate(VTemplate.Dir.EAST), 1);
            add(pieces, rooms.get2x2Secret(random), pos, rotation, VTemplate.Mirror.NONE);
        }
    }

    /** WoodlandMansionPieces.generateMansion: draws the room-graph random stream AFTER the caller's own rotation draw. */
    public static List<Piece> generateMansion(VSurface.LegacyRandom random, Pos origin, VTemplate.Rot rotation) {
        MansionGrid grid = new MansionGrid(random);
        MansionPiecePlacer placer = new MansionPiecePlacer(random);
        return placer.createMansion(origin, rotation, grid);
    }

    // ------------------------------------------------------------------ rendering

    public interface Sink {
        void set(int x, int y, int z, net.minestom.server.instance.block.Block b);
    }

    /**
     * WoodlandMansionPiece's real per-block placement (BlockIgnoreProcessor.STRUCTURE_BLOCK only
     * — air IS placed, blending nothing). handleDataMarker's live entity spawns (Mage/Warrior/
     * Group of Allays) are OMITTED per the established no-worldgen-entity-spawn precedent (no
     * block substitute — same as ocean monument's elder guardian, a pure entity-create call with
     * no spawner block). Chest markers ARE rendered as real chest blocks (facing derived from the
     * marker's "metadata" NBT string rotated by the piece's own rotation, matching real vanilla
     * exactly — no mirror applied to the facing, confirmed from the decompile: real handleDataMarker
     * only calls {@code rotation.rotate(dir)}, never touches mirror, an intentional real-vanilla
     * asymmetry preserved here rather than "fixed"), loot-table CONTENTS skipped per this
     * project's established precedent everywhere else.
     */
    public static void render(List<Piece> pieces, Sink sink, int chunkX, int chunkZ) {
        int cMinX = chunkX << 4, cMinZ = chunkZ << 4, cMaxX = cMinX + 15, cMaxZ = cMinZ + 15;
        for (Piece p : pieces) {
            for (VTemplate.BlockInfo b : p.template.blocks) {
                int[] wp = VTemplate.transformMirrored(b.x, b.y, b.z, p.mirror, p.rotation, 0, 0);
                int wx = wp[0] + p.baseX, wy = wp[1] + p.baseY, wz = wp[2] + p.baseZ;
                if (wx < cMinX || wx > cMaxX || wz < cMinZ || wz > cMaxZ) continue;
                String name = b.state.key().asString();
                if (name.equals("minecraft:structure_void")) continue;
                if (name.equals("minecraft:structure_block")) {
                    String marker = b.nbt != null && b.nbt.keySet().contains("metadata") ? b.nbt.getString("metadata") : null;
                    net.minestom.server.instance.block.Block chest = chestBlockFor(marker, p.rotation);
                    if (chest != null) sink.set(wx, wy, wz, chest);
                    continue;
                }
                net.minestom.server.instance.block.Block state = b.state;
                state = VBlockRotate.mirror(state, p.mirror);
                state = VBlockRotate.rotate(state, p.rotation);
                sink.set(wx, wy, wz, state);
            }
        }
    }

    private static net.minestom.server.instance.block.Block chestBlockFor(String marker, VTemplate.Rot rotation) {
        if (marker == null || !marker.startsWith("Chest")) return null;
        VTemplate.Dir dir = switch (marker) {
            case "ChestWest" -> VTemplate.Dir.WEST;
            case "ChestEast" -> VTemplate.Dir.EAST;
            case "ChestSouth" -> VTemplate.Dir.SOUTH;
            case "ChestNorth" -> VTemplate.Dir.NORTH;
            default -> null;
        };
        if (dir == null) return null;
        VTemplate.Dir facing = rotation.rotate(dir);
        return net.minestom.server.instance.block.Block.CHEST.withProperty("facing", facing.name().toLowerCase());
    }

    // ------------------------------------------------------------------ test hooks (piece placement)

    /** Test hook: the real full RNG sequence (rotation drawn first, THEN the room-graph+placement stream). */
    public static List<Piece> testGenerateMansion(long seed, int chunkX, int chunkZ, Pos origin) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, chunkX, chunkZ);
        VTemplate.Rot rotation = switch (random.nextInt(4)) {
            case 0 -> VTemplate.Rot.NONE;
            case 1 -> VTemplate.Rot.CLOCKWISE_90;
            case 2 -> VTemplate.Rot.CLOCKWISE_180;
            default -> VTemplate.Rot.COUNTERCLOCKWISE_90;
        };
        return generateMansion(random, origin, rotation);
    }
}
