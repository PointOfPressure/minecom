package dev.pointofpressure.minecom.worldgen.vanilla;

import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The full stronghold branching corridor/room maze (real vanilla StrongholdPieces: ~11
 * hand-coded procedural piece classes, not NBT templates like every other structure in this
 * project). Reimplemented from the algorithm (StrongholdPieces/StrongholdStructure/
 * StructurePiece/BoundingBox decompiles), not copied.
 *
 * <p>Architecture: {@link #assemble} runs the whole real generatePieces() retry loop (regenerate
 * from scratch with an incrementing feature-seed salt until a portal room actually got placed
 * somewhere in the tree — real vanilla guarantees this) and returns the full piece list, cached
 * per stronghold position by the caller (same cache-full-assembly-then-clip-per-chunk pattern
 * already established for end_city/bastion). {@link #render} then clips that list to a single
 * loading chunk's 16-wide column (Y unbounded), exactly mirroring real vanilla's postProcess
 * {@code chunkBB} parameter — a piece whose footprint crosses a chunk boundary gets rendered
 * once per intersecting chunk, each call independently clipped.
 *
 * <p>RNG note: real vanilla's postProcess random stream is shared and continuously-advancing
 * across every piece placed while iterating ONE chunk's intersecting pieces (in list order), fed
 * by a RandomSource threaded in from the caller. That per-chunk-call ordering is not
 * reproducible bit-exactly under this project's per-structure (not per-chunk-multi-structure)
 * architecture — same documented chunk-render-order-independence tradeoff already established
 * for ruined portal's spreadNetherrack/vines and mineshaft's corridor decoration. Each piece's
 * decoration RNG here is instead freshly seeded from that piece's own identity (world position +
 * kind) before every render call, so repeated renders of the same piece (from different
 * intersecting chunks) stay internally self-consistent.
 *
 * <p>Established project-wide precedents followed: chest LOOT-TABLE CONTENTS are skipped (the
 * chest block itself is placed) and the portal room's silverfish SPAWNER's mob-type NBT is
 * skipped (the spawner block itself is placed) — matching every other structure's container/
 * spawner handling (see VStructureManager's ocean_ruin/mineshaft javadocs).
 */
public final class VStrongholdGen {

    private VStrongholdGen() {}

    // ------------------------------------------------------------------ public geometry types

    public enum DoorType { OPENING, WOOD_DOOR, GRATES, IRON_DOOR }

    public enum Kind {
        STRAIGHT, PRISON_HALL, LEFT_TURN, RIGHT_TURN, ROOM_CROSSING, STRAIGHT_STAIRS_DOWN,
        STAIRS_DOWN, FIVE_CROSSING, CHEST_CORRIDOR, LIBRARY, PORTAL_ROOM, FILLER_CORRIDOR
    }

    /** A single placed piece: absolute world-space bounding box + orientation + decoded fields. */
    public static final class Piece {
        public Kind kind;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public VTemplate.Dir orientation;   // NORTH/SOUTH/WEST/EAST
        boolean mirrorLR;
        VTemplate.Rot rotation = VTemplate.Rot.NONE;
        public int genDepth;
        DoorType entryDoor = DoorType.OPENING;
        // variant fields
        boolean leftChild, rightChild;                         // STRAIGHT
        boolean leftLow, leftHigh, rightLow, rightHigh;         // FIVE_CROSSING
        int roomType;                                            // ROOM_CROSSING (0-4)
        boolean isTall;                                          // LIBRARY
        boolean isSource;                                        // STAIRS_DOWN (the start piece)
        int steps;                                               // FILLER_CORRIDOR
        // once-only mutable placement state (persists across repeated chunk renders)
        boolean placedSpawner;

        boolean inside(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
        boolean intersectsColumn(int cMinX, int cMinZ, int cMaxX, int cMaxZ) {
            return maxX >= cMinX && minX <= cMaxX && maxZ >= cMinZ && minZ <= cMaxZ;
        }
    }

    /** Real terrain read (for skipAir carving) + world block write. */
    public interface Sink {
        Block get(int x, int y, int z);
        void set(int x, int y, int z, Block b);
    }

    // ------------------------------------------------------------------ assembly (generatePieces)

    private static final class Weight {
        final Kind kind;
        final int weight, maxPlaceCount;
        int placeCount;
        Weight(Kind kind, int weight, int maxPlaceCount) { this.kind = kind; this.weight = weight; this.maxPlaceCount = maxPlaceCount; }
        boolean doPlace(int depth) {
            if (maxPlaceCount != 0 && placeCount >= maxPlaceCount) return false;
            if (kind == Kind.LIBRARY) return depth > 4;
            if (kind == Kind.PORTAL_ROOM) return depth > 5;
            return true;
        }
        boolean isValid() { return maxPlaceCount == 0 || placeCount < maxPlaceCount; }
    }

    private static List<Weight> freshWeights() {
        List<Weight> w = new ArrayList<>();
        w.add(new Weight(Kind.STRAIGHT, 40, 0));
        w.add(new Weight(Kind.PRISON_HALL, 5, 5));
        w.add(new Weight(Kind.LEFT_TURN, 20, 0));
        w.add(new Weight(Kind.RIGHT_TURN, 20, 0));
        w.add(new Weight(Kind.ROOM_CROSSING, 10, 6));
        w.add(new Weight(Kind.STRAIGHT_STAIRS_DOWN, 5, 5));
        w.add(new Weight(Kind.STAIRS_DOWN, 5, 5));
        w.add(new Weight(Kind.FIVE_CROSSING, 5, 4));
        w.add(new Weight(Kind.CHEST_CORRIDOR, 5, 4));
        w.add(new Weight(Kind.LIBRARY, 10, 2));
        w.add(new Weight(Kind.PORTAL_ROOM, 20, 1));
        return w;
    }

    private static final class Assembly {
        final List<Piece> pieces = new ArrayList<>();
        final List<Piece> pendingChildren = new ArrayList<>();
        List<Weight> currentWeights = freshWeights();
        int totalWeight;
        Weight previousPiece;
        Kind imposedPiece;
        Piece portalRoomPiece;
        int startMinX, startMinZ;
    }

    /**
     * StrongholdStructure.generatePieces: retry-until-portal-room-placed. footChunkX/Z are the
     * stronghold's target chunk (real vanilla: {@code chunkPos.getBlockX(2)}/{@code getBlockZ(2)},
     * i.e. the chunk's block origin + 2).
     */
    public static List<Piece> assemble(long seed, int chunkX, int chunkZ) {
        int startFootX = (chunkX << 4) + 2, startFootZ = (chunkZ << 4) + 2;
        int tries = 0;
        while (true) {
            VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
            random.setLargeFeatureSeed(seed + tries, chunkX, chunkZ);
            tries++;

            Assembly a = new Assembly();
            VTemplate.Dir startDir = randomHorizontalDirection(random);
            Piece start = new Piece();
            start.kind = Kind.STAIRS_DOWN;
            int[] bb = makeBoundingBox(startFootX, 64, startFootZ, startDir, 5, 11, 5);
            applyBB(start, bb);
            setOrientation(start, startDir);
            start.isSource = true;
            start.entryDoor = DoorType.OPENING;
            start.genDepth = 0;
            a.pieces.add(start);
            a.startMinX = start.minX;
            a.startMinZ = start.minZ;

            addChildrenFor(start, start, a, random);
            while (!a.pendingChildren.isEmpty()) {
                int idx = random.nextInt(a.pendingChildren.size());
                Piece p = a.pendingChildren.remove(idx);
                addChildrenFor(p, start, a, random);
            }

            moveBelowSeaLevel(a.pieces, random);

            if (!a.pieces.isEmpty() && a.portalRoomPiece != null) return a.pieces;
        }
    }

    /** StructurePiecesBuilder.moveBelowSeaLevel(seaLevel=63, minY=-64, random, offset=10). */
    private static void moveBelowSeaLevel(List<Piece> pieces, VSurface.LegacyRandom random) {
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Piece p : pieces) { minY = Math.min(minY, p.minY); maxY = Math.max(maxY, p.maxY); }
        int ySpan = maxY - minY + 1;
        int worldMinY = -64, seaLevel = 63;
        int maxYLimit = seaLevel - 10;
        int y1Pos = ySpan + worldMinY + 1;
        if (y1Pos < maxYLimit) y1Pos += random.nextInt(maxYLimit - y1Pos);
        int dy = y1Pos - maxY;
        for (Piece p : pieces) { p.minY += dy; p.maxY += dy; }
    }

    /** Plane.HORIZONTAL.getRandomDirection: Direction.from2DDataValue(nextInt(4)), order SOUTH,WEST,NORTH,EAST. */
    private static VTemplate.Dir randomHorizontalDirection(VSurface.LegacyRandom random) {
        return switch (random.nextInt(4)) {
            case 0 -> VTemplate.Dir.SOUTH;
            case 1 -> VTemplate.Dir.WEST;
            case 2 -> VTemplate.Dir.NORTH;
            default -> VTemplate.Dir.EAST;
        };
    }

    private static DoorType randomSmallDoor(VSurface.LegacyRandom random) {
        return switch (random.nextInt(5)) {
            case 2 -> DoorType.WOOD_DOOR;
            case 3 -> DoorType.GRATES;
            case 4 -> DoorType.IRON_DOOR;
            default -> DoorType.OPENING;
        };
    }

    // ------------------------------------------------------------------ addChildren dispatch

    private static void addChildrenFor(Piece self, Piece startRoom, Assembly a, VSurface.LegacyRandom random) {
        switch (self.kind) {
            case STRAIGHT -> {
                genForward(self, startRoom, a, random, 1, 1);
                if (self.leftChild) genLeft(self, startRoom, a, random, 1, 2);
                if (self.rightChild) genRight(self, startRoom, a, random, 1, 2);
            }
            case PRISON_HALL, CHEST_CORRIDOR, STRAIGHT_STAIRS_DOWN ->
                    genForward(self, startRoom, a, random, 1, 1);
            case LEFT_TURN -> {
                if (self.orientation != VTemplate.Dir.NORTH && self.orientation != VTemplate.Dir.EAST) {
                    genRight(self, startRoom, a, random, 1, 1);
                } else {
                    genLeft(self, startRoom, a, random, 1, 1);
                }
            }
            case RIGHT_TURN -> {
                if (self.orientation != VTemplate.Dir.NORTH && self.orientation != VTemplate.Dir.EAST) {
                    genLeft(self, startRoom, a, random, 1, 1);
                } else {
                    genRight(self, startRoom, a, random, 1, 1);
                }
            }
            case ROOM_CROSSING -> {
                genForward(self, startRoom, a, random, 4, 1);
                genLeft(self, startRoom, a, random, 1, 4);
                genRight(self, startRoom, a, random, 1, 4);
            }
            case STAIRS_DOWN -> {
                if (self.isSource) a.imposedPiece = Kind.FIVE_CROSSING;
                genForward(self, startRoom, a, random, 1, 1);
            }
            case FIVE_CROSSING -> {
                int zOffA = 3, zOffB = 5;
                if (self.orientation == VTemplate.Dir.WEST || self.orientation == VTemplate.Dir.NORTH) {
                    zOffA = 8 - zOffA; zOffB = 8 - zOffB;
                }
                genForward(self, startRoom, a, random, 5, 1);
                if (self.leftLow) genLeft(self, startRoom, a, random, zOffA, 1);
                if (self.leftHigh) genLeft(self, startRoom, a, random, zOffB, 7);
                if (self.rightLow) genRight(self, startRoom, a, random, zOffA, 1);
                if (self.rightHigh) genRight(self, startRoom, a, random, zOffB, 7);
            }
            case PORTAL_ROOM -> a.portalRoomPiece = self;
            case LIBRARY, FILLER_CORRIDOR -> { /* no children */ }
        }
    }

    private static Piece genForward(Piece self, Piece startRoom, Assembly a, VSurface.LegacyRandom random, int xOff, int yOff) {
        VTemplate.Dir o = self.orientation;
        if (o == null) return null;
        return switch (o) {
            case NORTH -> generateAndAddPiece(a, random, self.minX + xOff, self.minY + yOff, self.minZ - 1, o, self.genDepth);
            case SOUTH -> generateAndAddPiece(a, random, self.minX + xOff, self.minY + yOff, self.maxZ + 1, o, self.genDepth);
            case WEST -> generateAndAddPiece(a, random, self.minX - 1, self.minY + yOff, self.minZ + xOff, o, self.genDepth);
            case EAST -> generateAndAddPiece(a, random, self.maxX + 1, self.minY + yOff, self.minZ + xOff, o, self.genDepth);
            default -> null;
        };
    }

    private static Piece genLeft(Piece self, Piece startRoom, Assembly a, VSurface.LegacyRandom random, int yOff, int zOff) {
        VTemplate.Dir o = self.orientation;
        if (o == null) return null;
        return switch (o) {
            case NORTH, SOUTH -> generateAndAddPiece(a, random, self.minX - 1, self.minY + yOff, self.minZ + zOff, VTemplate.Dir.WEST, self.genDepth);
            case WEST, EAST -> generateAndAddPiece(a, random, self.minX + zOff, self.minY + yOff, self.minZ - 1, VTemplate.Dir.NORTH, self.genDepth);
            default -> null;
        };
    }

    private static Piece genRight(Piece self, Piece startRoom, Assembly a, VSurface.LegacyRandom random, int yOff, int zOff) {
        VTemplate.Dir o = self.orientation;
        if (o == null) return null;
        return switch (o) {
            case NORTH, SOUTH -> generateAndAddPiece(a, random, self.maxX + 1, self.minY + yOff, self.minZ + zOff, VTemplate.Dir.EAST, self.genDepth);
            case WEST, EAST -> generateAndAddPiece(a, random, self.minX + zOff, self.minY + yOff, self.maxZ + 1, VTemplate.Dir.SOUTH, self.genDepth);
            default -> null;
        };
    }

    private static Piece generateAndAddPiece(Assembly a, VSurface.LegacyRandom random, int footX, int footY, int footZ, VTemplate.Dir dir, int depth) {
        if (depth > 50) return null;
        if (Math.abs(footX - a.startMinX) <= 112 && Math.abs(footZ - a.startMinZ) <= 112) {
            Piece p = generatePieceFromSmallDoor(a, random, footX, footY, footZ, dir, depth + 1);
            if (p != null) { a.pieces.add(p); a.pendingChildren.add(p); }
            return p;
        }
        return null;
    }

    private static boolean updatePieceWeight(Assembly a) {
        boolean has = false;
        a.totalWeight = 0;
        for (Weight w : a.currentWeights) {
            if (w.maxPlaceCount > 0 && w.placeCount < w.maxPlaceCount) has = true;
            a.totalWeight += w.weight;
        }
        return has;
    }

    private static Piece generatePieceFromSmallDoor(Assembly a, VSurface.LegacyRandom random, int footX, int footY, int footZ, VTemplate.Dir dir, int depth) {
        if (!updatePieceWeight(a)) return null;
        if (a.imposedPiece != null) {
            Piece p = createPiece(a.imposedPiece, a.pieces, random, footX, footY, footZ, dir, depth);
            a.imposedPiece = null;
            if (p != null) return p;
        }
        int attempts = 0;
        while (attempts < 5) {
            attempts++;
            int sel = random.nextInt(a.totalWeight);
            for (Weight w : a.currentWeights) {
                sel -= w.weight;
                if (sel < 0) {
                    if (!w.doPlace(depth) || w == a.previousPiece) break;
                    Piece p = createPiece(w.kind, a.pieces, random, footX, footY, footZ, dir, depth);
                    if (p != null) {
                        w.placeCount++;
                        a.previousPiece = w;
                        if (!w.isValid()) a.currentWeights.remove(w);
                        return p;
                    }
                }
            }
        }
        int[] box = fillerCorridorFindBox(a.pieces, footX, footY, footZ, dir);
        if (box != null && box[1] > 1) {
            Piece p = new Piece();
            p.kind = Kind.FILLER_CORRIDOR;
            applyBB(p, box);
            setOrientation(p, dir);
            p.genDepth = depth;
            p.steps = (dir != VTemplate.Dir.NORTH && dir != VTemplate.Dir.SOUTH) ? (p.maxX - p.minX + 1) : (p.maxZ - p.minZ + 1);
            return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ piece creation (geometry only, no decoration)

    private static Piece findCollision(List<Piece> pieces, int[] bb) {
        for (Piece p : pieces) {
            if (bb[3] >= p.minX && bb[0] <= p.maxX && bb[5] >= p.minZ && bb[2] <= p.maxZ && bb[4] >= p.minY && bb[1] <= p.maxY) return p;
        }
        return null;
    }

    private static boolean isOkBox(int[] bb) { return bb[1] > 10; }

    private static Piece createPiece(Kind kind, List<Piece> pieces, VSurface.LegacyRandom random, int fx, int fy, int fz, VTemplate.Dir dir, int genDepth) {
        return switch (kind) {
            case STRAIGHT -> {
                int[] bb = orientBox(fx, fy, fz, -1, -1, 0, 5, 5, 7, dir);
                if (!isOkBox(bb) || findCollision(pieces, bb) != null) yield null;
                Piece p = base(Kind.STRAIGHT, bb, dir, genDepth);
                p.entryDoor = randomSmallDoor(random);
                p.leftChild = random.nextInt(2) == 0;
                p.rightChild = random.nextInt(2) == 0;
                yield p;
            }
            case PRISON_HALL -> {
                int[] bb = orientBox(fx, fy, fz, -1, -1, 0, 9, 5, 11, dir);
                if (!isOkBox(bb) || findCollision(pieces, bb) != null) yield null;
                Piece p = base(Kind.PRISON_HALL, bb, dir, genDepth);
                p.entryDoor = randomSmallDoor(random);
                yield p;
            }
            case LEFT_TURN -> {
                int[] bb = orientBox(fx, fy, fz, -1, -1, 0, 5, 5, 5, dir);
                if (!isOkBox(bb) || findCollision(pieces, bb) != null) yield null;
                Piece p = base(Kind.LEFT_TURN, bb, dir, genDepth);
                p.entryDoor = randomSmallDoor(random);
                yield p;
            }
            case RIGHT_TURN -> {
                int[] bb = orientBox(fx, fy, fz, -1, -1, 0, 5, 5, 5, dir);
                if (!isOkBox(bb) || findCollision(pieces, bb) != null) yield null;
                Piece p = base(Kind.RIGHT_TURN, bb, dir, genDepth);
                p.entryDoor = randomSmallDoor(random);
                yield p;
            }
            case ROOM_CROSSING -> {
                int[] bb = orientBox(fx, fy, fz, -4, -1, 0, 11, 7, 11, dir);
                if (!isOkBox(bb) || findCollision(pieces, bb) != null) yield null;
                Piece p = base(Kind.ROOM_CROSSING, bb, dir, genDepth);
                p.entryDoor = randomSmallDoor(random);
                p.roomType = random.nextInt(5);
                yield p;
            }
            case STRAIGHT_STAIRS_DOWN -> {
                int[] bb = orientBox(fx, fy, fz, -1, -7, 0, 5, 11, 8, dir);
                if (!isOkBox(bb) || findCollision(pieces, bb) != null) yield null;
                Piece p = base(Kind.STRAIGHT_STAIRS_DOWN, bb, dir, genDepth);
                p.entryDoor = randomSmallDoor(random);
                yield p;
            }
            case STAIRS_DOWN -> {
                int[] bb = orientBox(fx, fy, fz, -1, -7, 0, 5, 11, 5, dir);
                if (!isOkBox(bb) || findCollision(pieces, bb) != null) yield null;
                Piece p = base(Kind.STAIRS_DOWN, bb, dir, genDepth);
                p.entryDoor = randomSmallDoor(random);
                yield p;
            }
            case FIVE_CROSSING -> {
                int[] bb = orientBox(fx, fy, fz, -4, -3, 0, 10, 9, 11, dir);
                if (!isOkBox(bb) || findCollision(pieces, bb) != null) yield null;
                Piece p = base(Kind.FIVE_CROSSING, bb, dir, genDepth);
                p.entryDoor = randomSmallDoor(random);
                p.leftLow = random.nextBoolean();
                p.leftHigh = random.nextBoolean();
                p.rightLow = random.nextBoolean();
                p.rightHigh = random.nextInt(3) > 0;
                yield p;
            }
            case CHEST_CORRIDOR -> {
                int[] bb = orientBox(fx, fy, fz, -1, -1, 0, 5, 5, 7, dir);
                if (!isOkBox(bb) || findCollision(pieces, bb) != null) yield null;
                Piece p = base(Kind.CHEST_CORRIDOR, bb, dir, genDepth);
                p.entryDoor = randomSmallDoor(random);
                yield p;
            }
            case LIBRARY -> {
                int[] bb = orientBox(fx, fy, fz, -4, -1, 0, 14, 11, 15, dir);
                if (!isOkBox(bb) || findCollision(pieces, bb) != null) {
                    bb = orientBox(fx, fy, fz, -4, -1, 0, 14, 6, 15, dir);
                    if (!isOkBox(bb) || findCollision(pieces, bb) != null) yield null;
                }
                Piece p = base(Kind.LIBRARY, bb, dir, genDepth);
                p.entryDoor = randomSmallDoor(random);
                p.isTall = (p.maxY - p.minY + 1) > 6;
                yield p;
            }
            case PORTAL_ROOM -> {
                int[] bb = orientBox(fx, fy, fz, -4, -1, 0, 11, 8, 16, dir);
                if (!isOkBox(bb) || findCollision(pieces, bb) != null) yield null;
                yield base(Kind.PORTAL_ROOM, bb, dir, genDepth);
            }
            case FILLER_CORRIDOR -> null; // never created via the weighted table
        };
    }

    private static int[] fillerCorridorFindBox(List<Piece> pieces, int fx, int fy, int fz, VTemplate.Dir dir) {
        int[] box = orientBox(fx, fy, fz, -1, -1, 0, 5, 5, 4, dir);
        Piece collision = findCollision(pieces, box);
        if (collision == null) return null;
        if (collision.minY == box[1]) {
            for (int depth = 2; depth >= 1; depth--) {
                int[] box2 = orientBox(fx, fy, fz, -1, -1, 0, 5, 5, depth, dir);
                boolean intersects = box2[3] >= collision.minX && box2[0] <= collision.maxX
                        && box2[5] >= collision.minZ && box2[2] <= collision.maxZ
                        && box2[4] >= collision.minY && box2[1] <= collision.maxY;
                if (!intersects) return orientBox(fx, fy, fz, -1, -1, 0, 5, 5, depth + 1, dir);
            }
        }
        return null;
    }

    private static Piece base(Kind kind, int[] bb, VTemplate.Dir dir, int genDepth) {
        Piece p = new Piece();
        p.kind = kind;
        applyBB(p, bb);
        setOrientation(p, dir);
        p.genDepth = genDepth;
        return p;
    }

    private static void applyBB(Piece p, int[] bb) {
        p.minX = bb[0]; p.minY = bb[1]; p.minZ = bb[2]; p.maxX = bb[3]; p.maxY = bb[4]; p.maxZ = bb[5];
    }

    /** StructurePiece.setOrientation: derives the blockstate mirror+rotation from the piece orientation. */
    private static void setOrientation(Piece p, VTemplate.Dir dir) {
        p.orientation = dir;
        switch (dir) {
            case SOUTH -> { p.mirrorLR = true; p.rotation = VTemplate.Rot.NONE; }
            case WEST -> { p.mirrorLR = true; p.rotation = VTemplate.Rot.CLOCKWISE_90; }
            case EAST -> { p.mirrorLR = false; p.rotation = VTemplate.Rot.CLOCKWISE_90; }
            default -> { p.mirrorLR = false; p.rotation = VTemplate.Rot.NONE; } // NORTH
        }
    }

    // ------------------------------------------------------------------ BoundingBox.orientBox / makeBoundingBox

    /** BoundingBox.orientBox: SOUTH is the "identity" forward axis (+Z), others rotate around it. */
    private static int[] orientBox(int footX, int footY, int footZ, int offX, int offY, int offZ, int width, int height, int depth, VTemplate.Dir dir) {
        return switch (dir) {
            case NORTH -> new int[]{footX + offX, footY + offY, footZ - depth + 1 + offZ,
                    footX + width - 1 + offX, footY + height - 1 + offY, footZ + offZ};
            case WEST -> new int[]{footX - depth + 1 + offZ, footY + offY, footZ + offX,
                    footX + offZ, footY + height - 1 + offY, footZ + width - 1 + offX};
            case EAST -> new int[]{footX + offZ, footY + offY, footZ + offX,
                    footX + depth - 1 + offZ, footY + height - 1 + offY, footZ + width - 1 + offX};
            default -> new int[]{footX + offX, footY + offY, footZ + offZ,
                    footX + width - 1 + offX, footY + height - 1 + offY, footZ + depth - 1 + offZ}; // SOUTH
        };
    }

    /** StructurePiece.makeBoundingBox (StairsDown "isSource" constructor only). */
    private static int[] makeBoundingBox(int x, int y, int z, VTemplate.Dir dir, int width, int height, int depth) {
        boolean axisZ = dir == VTemplate.Dir.NORTH || dir == VTemplate.Dir.SOUTH;
        return axisZ
                ? new int[]{x, y, z, x + width - 1, y + height - 1, z + depth - 1}
                : new int[]{x, y, z, x + depth - 1, y + height - 1, z + width - 1};
    }

    // ------------------------------------------------------------------ world-space coordinate transform

    private static int worldX(Piece p, int x, int z) {
        return switch (p.orientation) {
            case NORTH, SOUTH -> p.minX + x;
            case WEST -> p.maxX - z;
            case EAST -> p.minX + z;
            default -> x;
        };
    }
    private static int worldY(Piece p, int y) { return y + p.minY; }
    private static int worldZ(Piece p, int x, int z) {
        return switch (p.orientation) {
            case NORTH -> p.maxZ - z;
            case SOUTH -> p.minZ + z;
            case WEST, EAST -> p.minZ + x;
            default -> z;
        };
    }

    // ------------------------------------------------------------------ rendering (postProcess)

    /** Render every piece intersecting the given chunk's 16-wide column (Y unbounded), clipped like real vanilla's chunkBB. */
    public static void render(List<Piece> pieces, long seed, Sink sink, int chunkX, int chunkZ) {
        int cMinX = chunkX << 4, cMinZ = chunkZ << 4, cMaxX = cMinX + 15, cMaxZ = cMinZ + 15;
        int[] clip = {cMinX, cMinZ, cMaxX, cMaxZ};
        for (Piece p : pieces) {
            if (!p.intersectsColumn(cMinX, cMinZ, cMaxX, cMaxZ)) continue;
            long pieceSeed = seed ^ (p.minX * 341873128712L) ^ (p.minY * 132897987541L) ^ (p.minZ * 2971215073L) ^ p.kind.ordinal();
            VSurface.LegacyRandom random = new VSurface.LegacyRandom(pieceSeed);
            postProcess(p, sink, clip, random);
        }
    }

    private static void postProcess(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random) {
        switch (p.kind) {
            case STRAIGHT -> ppStraight(p, sink, clip, random);
            case PRISON_HALL -> ppPrisonHall(p, sink, clip, random);
            case LEFT_TURN -> ppTurn(p, sink, clip, random, true);
            case RIGHT_TURN -> ppTurn(p, sink, clip, random, false);
            case ROOM_CROSSING -> ppRoomCrossing(p, sink, clip, random);
            case STRAIGHT_STAIRS_DOWN -> ppStraightStairsDown(p, sink, clip, random);
            case STAIRS_DOWN -> ppStairsDown(p, sink, clip, random);
            case FIVE_CROSSING -> ppFiveCrossing(p, sink, clip, random);
            case CHEST_CORRIDOR -> ppChestCorridor(p, sink, clip, random);
            case LIBRARY -> ppLibrary(p, sink, clip, random);
            case PORTAL_ROOM -> ppPortalRoom(p, sink, clip, random);
            case FILLER_CORRIDOR -> ppFillerCorridor(p, sink, clip);
        }
    }

    // -------- block-property transform (mirror then rotate, matching real placeBlock)

    private static VTemplate.Dir mirrorLeftRight(VTemplate.Dir d) {
        return (d == VTemplate.Dir.NORTH || d == VTemplate.Dir.SOUTH) ? d.opposite() : d;
    }

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

    private static void place(Piece p, Sink sink, int[] clip, Block block, int x, int y, int z) {
        int wx = worldX(p, x, z), wy = worldY(p, y), wz = worldZ(p, x, z);
        if (wx < clip[0] || wx > clip[2] || wz < clip[1] || wz > clip[3]) return;
        sink.set(wx, wy, wz, transformBlock(block, p.mirrorLR, p.rotation));
    }

    private static boolean insideClip(Piece p, int[] clip, int x, int y, int z) {
        int wx = worldX(p, x, z), wz = worldZ(p, x, z);
        return wx >= clip[0] && wx <= clip[2] && wz >= clip[1] && wz <= clip[3];
    }

    private static Block readLocal(Piece p, Sink sink, int[] clip, int x, int y, int z) {
        int wx = worldX(p, x, z), wy = worldY(p, y), wz = worldZ(p, x, z);
        if (wx < clip[0] || wx > clip[2] || wz < clip[1] || wz > clip[3]) return Block.AIR;
        Block b = sink.get(wx, wy, wz);
        return b == null ? Block.AIR : b;
    }

    private static void genBox(Piece p, Sink sink, int[] clip, int x0, int y0, int z0, int x1, int y1, int z1, Block edge, Block fill, boolean skipAir) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (skipAir && readLocal(p, sink, clip, x, y, z).isAir()) continue;
            boolean edgePos = y == y0 || y == y1 || x == x0 || x == x1 || z == z0 || z == z1;
            place(p, sink, clip, edgePos ? edge : fill, x, y, z);
        }
    }

    /** SmoothStoneSelector: edge -> weighted variant, interior -> CAVE_AIR (a carved passage). */
    private static void genBoxSmoothSelector(Piece p, Sink sink, int[] clip, int x0, int y0, int z0, int x1, int y1, int z1, boolean skipAir, VSurface.LegacyRandom random) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (skipAir && readLocal(p, sink, clip, x, y, z).isAir()) continue;
            boolean edgePos = y == y0 || y == y1 || x == x0 || x == x1 || z == z0 || z == z1;
            Block b;
            if (edgePos) {
                float sel = random.nextFloat();
                b = sel < 0.2F ? Block.CRACKED_STONE_BRICKS : sel < 0.5F ? Block.MOSSY_STONE_BRICKS
                        : sel < 0.55F ? Block.INFESTED_STONE_BRICKS : Block.STONE_BRICKS;
            } else {
                b = Block.CAVE_AIR;
            }
            place(p, sink, clip, b, x, y, z);
        }
    }

    private static void maybeBlock(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random, float prob, int x, int y, int z, Block block) {
        if (random.nextFloat() < prob) place(p, sink, clip, block, x, y, z);
    }

    private static void maybeBox(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random, float prob,
                                  int x0, int y0, int z0, int x1, int y1, int z1, Block edge, Block fill, boolean skipAir) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (random.nextFloat() > prob) continue;
            if (skipAir && readLocal(p, sink, clip, x, y, z).isAir()) continue;
            boolean edgePos = y == y0 || y == y1 || x == x0 || x == x1 || z == z0 || z == z1;
            place(p, sink, clip, edgePos ? edge : fill, x, y, z);
        }
    }

    /** real createChest is self-guarding (chunkBB.isInside + not-already-a-chest) so it's safe to
     *  call unconditionally on every render, matching Library's un-flagged repeat calls. Loot
     *  rolls on first open (Containers.registerLoot/rollPendingLoot), same as every other
     *  structure's containers. */
    private static void createChest(Piece p, Sink sink, int[] clip, int x, int y, int z, String lootTable) {
        if (!insideClip(p, clip, x, y, z)) return;
        place(p, sink, clip, Block.CHEST, x, y, z);
        int wx = worldX(p, x, z), wy = worldY(p, y), wz = worldZ(p, x, z);
        dev.pointofpressure.minecom.blocks.Containers.registerLoot(
                new net.minestom.server.coordinate.Vec(wx, wy, wz), lootTable);
    }

    private static void generateSmallDoor(Piece p, Sink sink, int[] clip, DoorType type, int fx, int fy, int fz) {
        switch (type) {
            case OPENING -> genBox(p, sink, clip, fx, fy, fz, fx + 2, fy + 2, fz, Block.CAVE_AIR, Block.CAVE_AIR, false);
            case WOOD_DOOR -> {
                place(p, sink, clip, Block.STONE_BRICKS, fx, fy, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx, fy + 1, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx, fy + 2, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx + 1, fy + 2, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx + 2, fy + 2, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx + 2, fy + 1, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx + 2, fy, fz);
                place(p, sink, clip, Block.OAK_DOOR, fx + 1, fy, fz);
                place(p, sink, clip, Block.OAK_DOOR.withProperty("half", "upper"), fx + 1, fy + 1, fz);
            }
            case GRATES -> {
                place(p, sink, clip, Block.CAVE_AIR, fx + 1, fy, fz);
                place(p, sink, clip, Block.CAVE_AIR, fx + 1, fy + 1, fz);
                place(p, sink, clip, Block.IRON_BARS.withProperty("west", "true"), fx, fy, fz);
                place(p, sink, clip, Block.IRON_BARS.withProperty("west", "true"), fx, fy + 1, fz);
                place(p, sink, clip, Block.IRON_BARS.withProperty("east", "true").withProperty("west", "true"), fx, fy + 2, fz);
                place(p, sink, clip, Block.IRON_BARS.withProperty("east", "true").withProperty("west", "true"), fx + 1, fy + 2, fz);
                place(p, sink, clip, Block.IRON_BARS.withProperty("east", "true").withProperty("west", "true"), fx + 2, fy + 2, fz);
                place(p, sink, clip, Block.IRON_BARS.withProperty("east", "true"), fx + 2, fy + 1, fz);
                place(p, sink, clip, Block.IRON_BARS.withProperty("east", "true"), fx + 2, fy, fz);
            }
            case IRON_DOOR -> {
                place(p, sink, clip, Block.STONE_BRICKS, fx, fy, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx, fy + 1, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx, fy + 2, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx + 1, fy + 2, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx + 2, fy + 2, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx + 2, fy + 1, fz);
                place(p, sink, clip, Block.STONE_BRICKS, fx + 2, fy, fz);
                place(p, sink, clip, Block.IRON_DOOR, fx + 1, fy, fz);
                place(p, sink, clip, Block.IRON_DOOR.withProperty("half", "upper"), fx + 1, fy + 1, fz);
                place(p, sink, clip, Block.STONE_BUTTON.withProperty("facing", "north"), fx + 2, fy + 1, fz + 1);
                place(p, sink, clip, Block.STONE_BUTTON.withProperty("facing", "south"), fx + 2, fy + 1, fz - 1);
            }
        }
    }

    // -------- individual piece postProcess

    private static void ppStraight(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random) {
        genBoxSmoothSelector(p, sink, clip, 0, 0, 0, 4, 4, 6, true, random);
        generateSmallDoor(p, sink, clip, p.entryDoor, 1, 1, 0);
        generateSmallDoor(p, sink, clip, DoorType.OPENING, 1, 1, 6);
        Block eastTorch = Block.WALL_TORCH.withProperty("facing", "east");
        Block westTorch = Block.WALL_TORCH.withProperty("facing", "west");
        maybeBlock(p, sink, clip, random, 0.1F, 1, 2, 1, eastTorch);
        maybeBlock(p, sink, clip, random, 0.1F, 3, 2, 1, westTorch);
        maybeBlock(p, sink, clip, random, 0.1F, 1, 2, 5, eastTorch);
        maybeBlock(p, sink, clip, random, 0.1F, 3, 2, 5, westTorch);
        if (p.leftChild) genBox(p, sink, clip, 0, 1, 2, 0, 3, 4, Block.CAVE_AIR, Block.CAVE_AIR, false);
        if (p.rightChild) genBox(p, sink, clip, 4, 1, 2, 4, 3, 4, Block.CAVE_AIR, Block.CAVE_AIR, false);
    }

    private static void ppPrisonHall(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random) {
        genBoxSmoothSelector(p, sink, clip, 0, 0, 0, 8, 4, 10, true, random);
        generateSmallDoor(p, sink, clip, p.entryDoor, 1, 1, 0);
        genBox(p, sink, clip, 1, 1, 10, 3, 3, 10, Block.CAVE_AIR, Block.CAVE_AIR, false);
        genBoxSmoothSelector(p, sink, clip, 4, 1, 1, 4, 3, 1, false, random);
        genBoxSmoothSelector(p, sink, clip, 4, 1, 3, 4, 3, 3, false, random);
        genBoxSmoothSelector(p, sink, clip, 4, 1, 7, 4, 3, 7, false, random);
        genBoxSmoothSelector(p, sink, clip, 4, 1, 9, 4, 3, 9, false, random);
        Block barsNS = Block.IRON_BARS.withProperty("north", "true").withProperty("south", "true");
        Block barsNSE = barsNS.withProperty("east", "true");
        Block barsWE = Block.IRON_BARS.withProperty("west", "true").withProperty("east", "true");
        for (int y = 1; y <= 3; y++) {
            place(p, sink, clip, barsNS, 4, y, 4);
            place(p, sink, clip, barsNSE, 4, y, 5);
            place(p, sink, clip, barsNS, 4, y, 6);
            place(p, sink, clip, barsWE, 5, y, 5);
            place(p, sink, clip, barsWE, 6, y, 5);
            place(p, sink, clip, barsWE, 7, y, 5);
        }
        place(p, sink, clip, barsNS, 4, 3, 2);
        place(p, sink, clip, barsNS, 4, 3, 8);
        Block doorBottom = Block.IRON_DOOR.withProperty("facing", "west");
        Block doorTop = doorBottom.withProperty("half", "upper");
        place(p, sink, clip, doorBottom, 4, 1, 2);
        place(p, sink, clip, doorTop, 4, 2, 2);
        place(p, sink, clip, doorBottom, 4, 1, 8);
        place(p, sink, clip, doorTop, 4, 2, 8);
    }

    private static void ppTurn(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random, boolean left) {
        genBoxSmoothSelector(p, sink, clip, 0, 0, 0, 4, 4, 4, true, random);
        generateSmallDoor(p, sink, clip, p.entryDoor, 1, 1, 0);
        boolean nOrE = p.orientation == VTemplate.Dir.NORTH || p.orientation == VTemplate.Dir.EAST;
        // LeftTurn: !nOrE -> gap at x=4 (right side); nOrE -> gap at x=0 (left side). RightTurn: mirrored.
        boolean gapAtMax = left == !nOrE;
        if (gapAtMax) genBox(p, sink, clip, 4, 1, 1, 4, 3, 3, Block.CAVE_AIR, Block.CAVE_AIR, false);
        else genBox(p, sink, clip, 0, 1, 1, 0, 3, 3, Block.CAVE_AIR, Block.CAVE_AIR, false);
    }

    private static void ppRoomCrossing(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random) {
        genBoxSmoothSelector(p, sink, clip, 0, 0, 0, 10, 6, 10, true, random);
        generateSmallDoor(p, sink, clip, p.entryDoor, 4, 1, 0);
        genBox(p, sink, clip, 4, 1, 10, 6, 3, 10, Block.CAVE_AIR, Block.CAVE_AIR, false);
        genBox(p, sink, clip, 0, 1, 4, 0, 3, 6, Block.CAVE_AIR, Block.CAVE_AIR, false);
        genBox(p, sink, clip, 10, 1, 4, 10, 3, 6, Block.CAVE_AIR, Block.CAVE_AIR, false);
        switch (p.roomType) {
            case 0 -> {
                place(p, sink, clip, Block.STONE_BRICKS, 5, 1, 5);
                place(p, sink, clip, Block.STONE_BRICKS, 5, 2, 5);
                place(p, sink, clip, Block.STONE_BRICKS, 5, 3, 5);
                place(p, sink, clip, Block.WALL_TORCH.withProperty("facing", "west"), 4, 3, 5);
                place(p, sink, clip, Block.WALL_TORCH.withProperty("facing", "east"), 6, 3, 5);
                place(p, sink, clip, Block.WALL_TORCH.withProperty("facing", "south"), 5, 3, 4);
                place(p, sink, clip, Block.WALL_TORCH.withProperty("facing", "north"), 5, 3, 6);
                place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 4, 1, 4);
                place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 4, 1, 5);
                place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 4, 1, 6);
                place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 6, 1, 4);
                place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 6, 1, 5);
                place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 6, 1, 6);
                place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 5, 1, 4);
                place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 5, 1, 6);
            }
            case 1 -> {
                for (int i = 0; i < 5; i++) {
                    place(p, sink, clip, Block.STONE_BRICKS, 3, 1, 3 + i);
                    place(p, sink, clip, Block.STONE_BRICKS, 7, 1, 3 + i);
                    place(p, sink, clip, Block.STONE_BRICKS, 3 + i, 1, 3);
                    place(p, sink, clip, Block.STONE_BRICKS, 3 + i, 1, 7);
                }
                place(p, sink, clip, Block.STONE_BRICKS, 5, 1, 5);
                place(p, sink, clip, Block.STONE_BRICKS, 5, 2, 5);
                place(p, sink, clip, Block.STONE_BRICKS, 5, 3, 5);
                place(p, sink, clip, Block.WATER, 5, 4, 5);
            }
            case 2 -> {
                for (int z = 1; z <= 9; z++) {
                    place(p, sink, clip, Block.COBBLESTONE, 1, 3, z);
                    place(p, sink, clip, Block.COBBLESTONE, 9, 3, z);
                }
                for (int x = 1; x <= 9; x++) {
                    place(p, sink, clip, Block.COBBLESTONE, x, 3, 1);
                    place(p, sink, clip, Block.COBBLESTONE, x, 3, 9);
                }
                place(p, sink, clip, Block.COBBLESTONE, 5, 1, 4);
                place(p, sink, clip, Block.COBBLESTONE, 5, 1, 6);
                place(p, sink, clip, Block.COBBLESTONE, 5, 3, 4);
                place(p, sink, clip, Block.COBBLESTONE, 5, 3, 6);
                place(p, sink, clip, Block.COBBLESTONE, 4, 1, 5);
                place(p, sink, clip, Block.COBBLESTONE, 6, 1, 5);
                place(p, sink, clip, Block.COBBLESTONE, 4, 3, 5);
                place(p, sink, clip, Block.COBBLESTONE, 6, 3, 5);
                for (int y = 1; y <= 3; y++) {
                    place(p, sink, clip, Block.COBBLESTONE, 4, y, 4);
                    place(p, sink, clip, Block.COBBLESTONE, 6, y, 4);
                    place(p, sink, clip, Block.COBBLESTONE, 4, y, 6);
                    place(p, sink, clip, Block.COBBLESTONE, 6, y, 6);
                }
                place(p, sink, clip, Block.WALL_TORCH, 5, 3, 5);
                for (int z = 2; z <= 8; z++) {
                    place(p, sink, clip, Block.OAK_PLANKS, 2, 3, z);
                    place(p, sink, clip, Block.OAK_PLANKS, 3, 3, z);
                    if (z <= 3 || z >= 7) {
                        place(p, sink, clip, Block.OAK_PLANKS, 4, 3, z);
                        place(p, sink, clip, Block.OAK_PLANKS, 5, 3, z);
                        place(p, sink, clip, Block.OAK_PLANKS, 6, 3, z);
                    }
                    place(p, sink, clip, Block.OAK_PLANKS, 7, 3, z);
                    place(p, sink, clip, Block.OAK_PLANKS, 8, 3, z);
                }
                Block ladder = Block.LADDER.withProperty("facing", "west");
                place(p, sink, clip, ladder, 9, 1, 3);
                place(p, sink, clip, ladder, 9, 2, 3);
                place(p, sink, clip, ladder, 9, 3, 3);
                createChest(p, sink, clip, 3, 4, 8, "minecraft:chests/stronghold_crossing");
            }
            default -> { /* real vanilla has no case 3/4 body (switch falls through with no default) */ }
        }
    }

    private static void ppStraightStairsDown(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random) {
        genBoxSmoothSelector(p, sink, clip, 0, 0, 0, 4, 10, 7, true, random);
        generateSmallDoor(p, sink, clip, p.entryDoor, 1, 7, 0);
        generateSmallDoor(p, sink, clip, DoorType.OPENING, 1, 1, 7);
        Block stairs = Block.COBBLESTONE_STAIRS.withProperty("facing", "south");
        for (int i = 0; i < 6; i++) {
            place(p, sink, clip, stairs, 1, 6 - i, 1 + i);
            place(p, sink, clip, stairs, 2, 6 - i, 1 + i);
            place(p, sink, clip, stairs, 3, 6 - i, 1 + i);
            if (i < 5) {
                place(p, sink, clip, Block.STONE_BRICKS, 1, 5 - i, 1 + i);
                place(p, sink, clip, Block.STONE_BRICKS, 2, 5 - i, 1 + i);
                place(p, sink, clip, Block.STONE_BRICKS, 3, 5 - i, 1 + i);
            }
        }
    }

    private static void ppStairsDown(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random) {
        genBoxSmoothSelector(p, sink, clip, 0, 0, 0, 4, 10, 4, true, random);
        generateSmallDoor(p, sink, clip, p.entryDoor, 1, 7, 0);
        generateSmallDoor(p, sink, clip, DoorType.OPENING, 1, 1, 4);
        place(p, sink, clip, Block.STONE_BRICKS, 2, 6, 1);
        place(p, sink, clip, Block.STONE_BRICKS, 1, 5, 1);
        place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 1, 6, 1);
        place(p, sink, clip, Block.STONE_BRICKS, 1, 5, 2);
        place(p, sink, clip, Block.STONE_BRICKS, 1, 4, 3);
        place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 1, 5, 3);
        place(p, sink, clip, Block.STONE_BRICKS, 2, 4, 3);
        place(p, sink, clip, Block.STONE_BRICKS, 3, 3, 3);
        place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 3, 4, 3);
        place(p, sink, clip, Block.STONE_BRICKS, 3, 3, 2);
        place(p, sink, clip, Block.STONE_BRICKS, 3, 2, 1);
        place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 3, 3, 1);
        place(p, sink, clip, Block.STONE_BRICKS, 2, 2, 1);
        place(p, sink, clip, Block.STONE_BRICKS, 1, 1, 1);
        place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 1, 2, 1);
        place(p, sink, clip, Block.STONE_BRICKS, 1, 1, 2);
        place(p, sink, clip, Block.SMOOTH_STONE_SLAB, 1, 1, 3);
    }

    private static void ppFiveCrossing(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random) {
        genBoxSmoothSelector(p, sink, clip, 0, 0, 0, 9, 8, 10, true, random);
        generateSmallDoor(p, sink, clip, p.entryDoor, 4, 3, 0);
        if (p.leftLow) genBox(p, sink, clip, 0, 3, 1, 0, 5, 3, Block.CAVE_AIR, Block.CAVE_AIR, false);
        if (p.rightLow) genBox(p, sink, clip, 9, 3, 1, 9, 5, 3, Block.CAVE_AIR, Block.CAVE_AIR, false);
        if (p.leftHigh) genBox(p, sink, clip, 0, 5, 7, 0, 7, 9, Block.CAVE_AIR, Block.CAVE_AIR, false);
        if (p.rightHigh) genBox(p, sink, clip, 9, 5, 7, 9, 7, 9, Block.CAVE_AIR, Block.CAVE_AIR, false);
        genBox(p, sink, clip, 5, 1, 10, 7, 3, 10, Block.CAVE_AIR, Block.CAVE_AIR, false);
        genBoxSmoothSelector(p, sink, clip, 1, 2, 1, 8, 2, 6, false, random);
        genBoxSmoothSelector(p, sink, clip, 4, 1, 5, 4, 4, 9, false, random);
        genBoxSmoothSelector(p, sink, clip, 8, 1, 5, 8, 4, 9, false, random);
        genBoxSmoothSelector(p, sink, clip, 1, 4, 7, 3, 4, 9, false, random);
        genBoxSmoothSelector(p, sink, clip, 1, 3, 5, 3, 3, 6, false, random);
        genBox(p, sink, clip, 1, 3, 4, 3, 3, 4, Block.SMOOTH_STONE_SLAB, Block.SMOOTH_STONE_SLAB, false);
        genBox(p, sink, clip, 1, 4, 6, 3, 4, 6, Block.SMOOTH_STONE_SLAB, Block.SMOOTH_STONE_SLAB, false);
        genBoxSmoothSelector(p, sink, clip, 5, 1, 7, 7, 1, 8, false, random);
        genBox(p, sink, clip, 5, 1, 9, 7, 1, 9, Block.SMOOTH_STONE_SLAB, Block.SMOOTH_STONE_SLAB, false);
        genBox(p, sink, clip, 5, 2, 7, 7, 2, 7, Block.SMOOTH_STONE_SLAB, Block.SMOOTH_STONE_SLAB, false);
        genBox(p, sink, clip, 4, 5, 7, 4, 5, 9, Block.SMOOTH_STONE_SLAB, Block.SMOOTH_STONE_SLAB, false);
        genBox(p, sink, clip, 8, 5, 7, 8, 5, 9, Block.SMOOTH_STONE_SLAB, Block.SMOOTH_STONE_SLAB, false);
        Block doubleSlab = Block.SMOOTH_STONE_SLAB.withProperty("type", "double");
        genBox(p, sink, clip, 5, 5, 7, 7, 5, 9, doubleSlab, doubleSlab, false);
        place(p, sink, clip, Block.WALL_TORCH.withProperty("facing", "south"), 6, 5, 6);
    }

    private static void ppChestCorridor(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random) {
        genBoxSmoothSelector(p, sink, clip, 0, 0, 0, 4, 4, 6, true, random);
        generateSmallDoor(p, sink, clip, p.entryDoor, 1, 1, 0);
        generateSmallDoor(p, sink, clip, DoorType.OPENING, 1, 1, 6);
        genBox(p, sink, clip, 3, 1, 2, 3, 1, 4, Block.STONE_BRICKS, Block.STONE_BRICKS, false);
        place(p, sink, clip, Block.STONE_BRICK_SLAB, 3, 1, 1);
        place(p, sink, clip, Block.STONE_BRICK_SLAB, 3, 1, 5);
        place(p, sink, clip, Block.STONE_BRICK_SLAB, 3, 2, 2);
        place(p, sink, clip, Block.STONE_BRICK_SLAB, 3, 2, 4);
        for (int z = 2; z <= 4; z++) place(p, sink, clip, Block.STONE_BRICK_SLAB, 2, 1, z);
        createChest(p, sink, clip, 3, 2, 3, "minecraft:chests/stronghold_corridor");
    }

    private static void ppLibrary(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random) {
        int height = p.isTall ? 11 : 6;
        genBoxSmoothSelector(p, sink, clip, 0, 0, 0, 13, height - 1, 14, true, random);
        generateSmallDoor(p, sink, clip, p.entryDoor, 4, 1, 0);
        maybeBox(p, sink, clip, random, 0.07F, 2, 1, 1, 11, 4, 13, Block.COBWEB, Block.COBWEB, false);
        for (int d = 1; d <= 13; d++) {
            if ((d - 1) % 4 == 0) {
                genBox(p, sink, clip, 1, 1, d, 1, 4, d, Block.OAK_PLANKS, Block.OAK_PLANKS, false);
                genBox(p, sink, clip, 12, 1, d, 12, 4, d, Block.OAK_PLANKS, Block.OAK_PLANKS, false);
                place(p, sink, clip, Block.WALL_TORCH.withProperty("facing", "east"), 2, 3, d);
                place(p, sink, clip, Block.WALL_TORCH.withProperty("facing", "west"), 11, 3, d);
                if (p.isTall) {
                    genBox(p, sink, clip, 1, 6, d, 1, 9, d, Block.OAK_PLANKS, Block.OAK_PLANKS, false);
                    genBox(p, sink, clip, 12, 6, d, 12, 9, d, Block.OAK_PLANKS, Block.OAK_PLANKS, false);
                }
            } else {
                genBox(p, sink, clip, 1, 1, d, 1, 4, d, Block.BOOKSHELF, Block.BOOKSHELF, false);
                genBox(p, sink, clip, 12, 1, d, 12, 4, d, Block.BOOKSHELF, Block.BOOKSHELF, false);
                if (p.isTall) {
                    genBox(p, sink, clip, 1, 6, d, 1, 9, d, Block.BOOKSHELF, Block.BOOKSHELF, false);
                    genBox(p, sink, clip, 12, 6, d, 12, 9, d, Block.BOOKSHELF, Block.BOOKSHELF, false);
                }
            }
        }
        for (int dx = 3; dx < 12; dx += 2) {
            genBox(p, sink, clip, 3, 1, dx, 4, 3, dx, Block.BOOKSHELF, Block.BOOKSHELF, false);
            genBox(p, sink, clip, 6, 1, dx, 7, 3, dx, Block.BOOKSHELF, Block.BOOKSHELF, false);
            genBox(p, sink, clip, 9, 1, dx, 10, 3, dx, Block.BOOKSHELF, Block.BOOKSHELF, false);
        }
        if (p.isTall) {
            genBox(p, sink, clip, 1, 5, 1, 3, 5, 13, Block.OAK_PLANKS, Block.OAK_PLANKS, false);
            genBox(p, sink, clip, 10, 5, 1, 12, 5, 13, Block.OAK_PLANKS, Block.OAK_PLANKS, false);
            genBox(p, sink, clip, 4, 5, 1, 9, 5, 2, Block.OAK_PLANKS, Block.OAK_PLANKS, false);
            genBox(p, sink, clip, 4, 5, 12, 9, 5, 13, Block.OAK_PLANKS, Block.OAK_PLANKS, false);
            place(p, sink, clip, Block.OAK_PLANKS, 9, 5, 11);
            place(p, sink, clip, Block.OAK_PLANKS, 8, 5, 11);
            place(p, sink, clip, Block.OAK_PLANKS, 9, 5, 10);
            Block weFence = Block.OAK_FENCE.withProperty("west", "true").withProperty("east", "true");
            Block nsFence = Block.OAK_FENCE.withProperty("north", "true").withProperty("south", "true");
            genBox(p, sink, clip, 3, 6, 3, 3, 6, 11, nsFence, nsFence, false);
            genBox(p, sink, clip, 10, 6, 3, 10, 6, 9, nsFence, nsFence, false);
            genBox(p, sink, clip, 4, 6, 2, 9, 6, 2, weFence, weFence, false);
            genBox(p, sink, clip, 4, 6, 12, 7, 6, 12, weFence, weFence, false);
            place(p, sink, clip, Block.OAK_FENCE.withProperty("north", "true").withProperty("east", "true"), 3, 6, 2);
            place(p, sink, clip, Block.OAK_FENCE.withProperty("south", "true").withProperty("east", "true"), 3, 6, 12);
            place(p, sink, clip, Block.OAK_FENCE.withProperty("north", "true").withProperty("west", "true"), 10, 6, 2);
            for (int i = 0; i <= 2; i++) {
                place(p, sink, clip, Block.OAK_FENCE.withProperty("south", "true").withProperty("west", "true"), 8 + i, 6, 12 - i);
                if (i != 2) {
                    place(p, sink, clip, Block.OAK_FENCE.withProperty("north", "true").withProperty("east", "true"), 8 + i, 6, 11 - i);
                }
            }
            Block ladder = Block.LADDER.withProperty("facing", "south");
            for (int y = 1; y <= 7; y++) place(p, sink, clip, ladder, 10, y, 13);
            Block eFence = Block.OAK_FENCE.withProperty("east", "true");
            Block wFence = Block.OAK_FENCE.withProperty("west", "true");
            place(p, sink, clip, eFence, 6, 9, 7);
            place(p, sink, clip, wFence, 7, 9, 7);
            place(p, sink, clip, eFence, 6, 8, 7);
            place(p, sink, clip, wFence, 7, 8, 7);
            Block nsweFence = nsFence.withProperty("west", "true").withProperty("east", "true");
            place(p, sink, clip, nsweFence, 6, 7, 7);
            place(p, sink, clip, nsweFence, 7, 7, 7);
            place(p, sink, clip, eFence, 5, 7, 7);
            place(p, sink, clip, wFence, 8, 7, 7);
            place(p, sink, clip, eFence.withProperty("north", "true"), 6, 7, 6);
            place(p, sink, clip, eFence.withProperty("south", "true"), 6, 7, 8);
            place(p, sink, clip, wFence.withProperty("north", "true"), 7, 7, 6);
            place(p, sink, clip, wFence.withProperty("south", "true"), 7, 7, 8);
            place(p, sink, clip, Block.TORCH, 5, 8, 7);
            place(p, sink, clip, Block.TORCH, 8, 8, 7);
            place(p, sink, clip, Block.TORCH, 6, 8, 6);
            place(p, sink, clip, Block.TORCH, 6, 8, 8);
            place(p, sink, clip, Block.TORCH, 7, 8, 6);
            place(p, sink, clip, Block.TORCH, 7, 8, 8);
        }
        createChest(p, sink, clip, 3, 3, 5, "minecraft:chests/stronghold_library");
        if (p.isTall) {
            place(p, sink, clip, Block.CAVE_AIR, 12, 9, 1);
            createChest(p, sink, clip, 12, 8, 1, "minecraft:chests/stronghold_library");
        }
    }

    private static void ppPortalRoom(Piece p, Sink sink, int[] clip, VSurface.LegacyRandom random) {
        genBoxSmoothSelector(p, sink, clip, 0, 0, 0, 10, 7, 15, false, random);
        generateSmallDoor(p, sink, clip, DoorType.GRATES, 4, 1, 0);
        genBoxSmoothSelector(p, sink, clip, 1, 6, 1, 1, 6, 14, false, random);
        genBoxSmoothSelector(p, sink, clip, 9, 6, 1, 9, 6, 14, false, random);
        genBoxSmoothSelector(p, sink, clip, 2, 6, 1, 8, 6, 2, false, random);
        genBoxSmoothSelector(p, sink, clip, 2, 6, 14, 8, 6, 14, false, random);
        genBoxSmoothSelector(p, sink, clip, 1, 1, 1, 2, 1, 4, false, random);
        genBoxSmoothSelector(p, sink, clip, 8, 1, 1, 9, 1, 4, false, random);
        genBox(p, sink, clip, 1, 1, 1, 1, 1, 3, Block.LAVA, Block.LAVA, false);
        genBox(p, sink, clip, 9, 1, 1, 9, 1, 3, Block.LAVA, Block.LAVA, false);
        genBoxSmoothSelector(p, sink, clip, 3, 1, 8, 7, 1, 12, false, random);
        genBox(p, sink, clip, 4, 1, 9, 6, 1, 11, Block.LAVA, Block.LAVA, false);
        Block nsBars = Block.IRON_BARS.withProperty("north", "true").withProperty("south", "true");
        Block weBars = Block.IRON_BARS.withProperty("west", "true").withProperty("east", "true");
        for (int z = 3; z < 14; z += 2) {
            genBox(p, sink, clip, 0, 3, z, 0, 4, z, nsBars, nsBars, false);
            genBox(p, sink, clip, 10, 3, z, 10, 4, z, nsBars, nsBars, false);
        }
        for (int x = 2; x < 9; x += 2) genBox(p, sink, clip, x, 3, 15, x, 4, 15, weBars, weBars, false);
        Block stairNorth = Block.STONE_BRICK_STAIRS.withProperty("facing", "north");
        genBoxSmoothSelector(p, sink, clip, 4, 1, 5, 6, 1, 7, false, random);
        genBoxSmoothSelector(p, sink, clip, 4, 2, 6, 6, 2, 7, false, random);
        genBoxSmoothSelector(p, sink, clip, 4, 3, 7, 6, 3, 7, false, random);
        for (int x = 4; x <= 6; x++) {
            place(p, sink, clip, stairNorth, x, 1, 4);
            place(p, sink, clip, stairNorth, x, 2, 5);
            place(p, sink, clip, stairNorth, x, 3, 6);
        }
        Block northFrame = Block.END_PORTAL_FRAME.withProperty("facing", "north");
        Block southFrame = Block.END_PORTAL_FRAME.withProperty("facing", "south");
        Block eastFrame = Block.END_PORTAL_FRAME.withProperty("facing", "east");
        Block westFrame = Block.END_PORTAL_FRAME.withProperty("facing", "west");
        boolean allEyes = true;
        boolean[] eyes = new boolean[12];
        for (int i = 0; i < eyes.length; i++) {
            eyes[i] = random.nextFloat() > 0.9F;
            allEyes &= eyes[i];
        }
        place(p, sink, clip, northFrame.withProperty("eye", String.valueOf(eyes[0])), 4, 3, 8);
        place(p, sink, clip, northFrame.withProperty("eye", String.valueOf(eyes[1])), 5, 3, 8);
        place(p, sink, clip, northFrame.withProperty("eye", String.valueOf(eyes[2])), 6, 3, 8);
        place(p, sink, clip, southFrame.withProperty("eye", String.valueOf(eyes[3])), 4, 3, 12);
        place(p, sink, clip, southFrame.withProperty("eye", String.valueOf(eyes[4])), 5, 3, 12);
        place(p, sink, clip, southFrame.withProperty("eye", String.valueOf(eyes[5])), 6, 3, 12);
        place(p, sink, clip, eastFrame.withProperty("eye", String.valueOf(eyes[6])), 3, 3, 9);
        place(p, sink, clip, eastFrame.withProperty("eye", String.valueOf(eyes[7])), 3, 3, 10);
        place(p, sink, clip, eastFrame.withProperty("eye", String.valueOf(eyes[8])), 3, 3, 11);
        place(p, sink, clip, westFrame.withProperty("eye", String.valueOf(eyes[9])), 7, 3, 9);
        place(p, sink, clip, westFrame.withProperty("eye", String.valueOf(eyes[10])), 7, 3, 10);
        place(p, sink, clip, westFrame.withProperty("eye", String.valueOf(eyes[11])), 7, 3, 11);
        if (allEyes) {
            for (int x = 4; x <= 6; x++) for (int z = 9; z <= 11; z++) place(p, sink, clip, Block.END_PORTAL, x, 3, z);
        }
        if (!p.placedSpawner && insideClip(p, clip, 5, 3, 6)) {
            p.placedSpawner = true;
            // established project-wide precedent: place the SPAWNER block, skip the mob-type NBT
            // (no live block-entity mob-type API in this project; see javadoc).
            place(p, sink, clip, Block.SPAWNER, 5, 3, 6);
        }
    }

    private static void ppFillerCorridor(Piece p, Sink sink, int[] clip) {
        for (int i = 0; i < p.steps; i++) {
            place(p, sink, clip, Block.STONE_BRICKS, 0, 0, i);
            place(p, sink, clip, Block.STONE_BRICKS, 1, 0, i);
            place(p, sink, clip, Block.STONE_BRICKS, 2, 0, i);
            place(p, sink, clip, Block.STONE_BRICKS, 3, 0, i);
            place(p, sink, clip, Block.STONE_BRICKS, 4, 0, i);
            for (int y = 1; y <= 3; y++) {
                place(p, sink, clip, Block.STONE_BRICKS, 0, y, i);
                place(p, sink, clip, Block.CAVE_AIR, 1, y, i);
                place(p, sink, clip, Block.CAVE_AIR, 2, y, i);
                place(p, sink, clip, Block.CAVE_AIR, 3, y, i);
                place(p, sink, clip, Block.STONE_BRICKS, 4, y, i);
            }
            place(p, sink, clip, Block.STONE_BRICKS, 0, 4, i);
            place(p, sink, clip, Block.STONE_BRICKS, 1, 4, i);
            place(p, sink, clip, Block.STONE_BRICKS, 2, 4, i);
            place(p, sink, clip, Block.STONE_BRICKS, 3, 4, i);
            place(p, sink, clip, Block.STONE_BRICKS, 4, 4, i);
        }
    }

    // ------------------------------------------------------------------ live eye-of-ender wiring

    private static final int[][] PORTAL_FRAME_LOCAL = {
            {4, 3, 8}, {5, 3, 8}, {6, 3, 8}, {4, 3, 12}, {5, 3, 12}, {6, 3, 12},
            {3, 3, 9}, {3, 3, 10}, {3, 3, 11}, {7, 3, 9}, {7, 3, 10}, {7, 3, 11}
    };

    /** World positions of a PORTAL_ROOM piece's 12 end_portal_frame cells (real vanilla layout). */
    public static int[][] portalFramePositions(Piece p) {
        int[][] out = new int[12][3];
        for (int i = 0; i < 12; i++) {
            int[] l = PORTAL_FRAME_LOCAL[i];
            out[i] = new int[]{worldX(p, l[0], l[2]), worldY(p, l[1]), worldZ(p, l[0], l[2])};
        }
        return out;
    }

    /** World positions of the 3x3 end_portal core, filled once all 12 frames hold an eye. */
    public static int[][] portalCorePositions(Piece p) {
        List<int[]> out = new ArrayList<>();
        for (int x = 4; x <= 6; x++) for (int z = 9; z <= 11; z++) out.add(new int[]{worldX(p, x, z), worldY(p, 3), worldZ(p, x, z)});
        return out.toArray(new int[0][]);
    }

    // ------------------------------------------------------------------ test hooks

    /** Test hook: piece count + kind histogram + portal room presence for a stronghold. */
    public static Map<String, Integer> testAssembleAt(long seed, int chunkX, int chunkZ) {
        List<Piece> pieces = assemble(seed, chunkX, chunkZ);
        Map<String, Integer> hist = new java.util.HashMap<>();
        for (Piece p : pieces) hist.merge(p.kind.name(), 1, Integer::sum);
        hist.put("__total", pieces.size());
        return hist;
    }
}
