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
}
