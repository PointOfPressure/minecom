package dev.pointofpressure.minecom.worldgen.vanilla;

import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.pointofpressure.minecom.worldgen.vanilla.VTemplate.Dir;
import dev.pointofpressure.minecom.worldgen.vanilla.VFeature.XWorldgenRandom;

/**
 * Faithful reimplementation of vanilla's sculk spread used at world generation:
 * SculkSpreader (charge cursors), SculkBlock / SculkVeinBlock behaviours,
 * MultifaceSpreader (vein spread), and SculkPatchFeature.place. Reference:
 * net.minecraft.world.level.block.{SculkSpreader,SculkBlock,SculkVeinBlock,
 * MultifaceSpreader,MultifaceBlock} + levelgen.feature.SculkPatchFeature.
 *
 * Operates on a {@link World} adapter (world-coord reads/writes over the feature
 * canvas). Collision/support-face queries use full-cube approximation via
 * {@link VBlockShapes}; sculk-replaceable tags are resolved from tags_block.json.
 */
public final class VSculk {

    // -------------------------------------------------------------- world adapter

    /** Writable world view in absolute coords; air is represented by Block.AIR. */
    public interface World {
        Block get(int x, int y, int z);            // Block.AIR when empty
        void set(int x, int y, int z, Block block);
    }

    private VSculk() {}

    // Vanilla Direction.values() order == Dir order: DOWN,UP,NORTH,SOUTH,WEST,EAST
    private static final Dir[] DIRECTIONS = Dir.values();

    // -------------------------------------------------------------- block helpers

    private static boolean isAir(Block b) { return b == null || b.isAir(); }

    private static boolean is(Block b, Block other) {
        return b != null && b.id() == other.id();
    }

    private static String name(Block b) { return b == null ? "minecraft:air" : b.key().asString(); }

    /** collision shape is a full cube (isCollisionShapeFullBlock approximation). */
    private static boolean isCollisionFull(Block b) {
        if (isAir(b)) return false;
        return VBlockShapes.isFullCube(name(b), b);
    }

    /** support-shape face full toward `face` (isFaceSturdy approximation). */
    private static boolean isFaceSturdy(Block b, Dir face) {
        return isCollisionFull(b);
    }

    // Sculk behaviour dispatch: SculkBehaviour instances are SCULK, SCULK_VEIN, SCULK_CATALYST.
    private static boolean isSculkBlock(Block b) { return is(b, Block.SCULK); }
    private static boolean isSculkVein(Block b) { return b != null && b.id() == Block.SCULK_VEIN.id(); }
    private static boolean isSculkBehaviour(Block b) {
        return isSculkBlock(b) || isSculkVein(b) || is(b, Block.SCULK_CATALYST);
    }

    private enum Beh { SCULK_BLOCK, SCULK_VEIN, DEFAULT }
    private static Beh behaviourOf(Block b) {
        if (isSculkBlock(b)) return Beh.SCULK_BLOCK;
        if (isSculkVein(b)) return Beh.SCULK_VEIN;
        return Beh.DEFAULT;   // catalyst/sensor/shrieker/non-sculk (catalyst rare during spread)
    }

    // -------------------------------------------------------------- tags

    private static Set<String> SCULK_REPLACEABLE_WG;
    private static Set<String> SCULK_REPLACEABLE;

    private static Set<String> replaceableWG() {
        if (SCULK_REPLACEABLE_WG == null) SCULK_REPLACEABLE_WG = VProcessors.resolveTag("#minecraft:sculk_replaceable_world_gen");
        return SCULK_REPLACEABLE_WG;
    }
    private static Set<String> replaceable() {
        if (SCULK_REPLACEABLE == null) SCULK_REPLACEABLE = VProcessors.resolveTag("#minecraft:sculk_replaceable");
        return SCULK_REPLACEABLE;
    }

    // -------------------------------------------------------------- vein state

    /** sculk_vein block with the given face booleans (+waterlogged). */
    private static Block vein(boolean[] faces, boolean waterlogged) {
        Block b = Block.SCULK_VEIN;
        Map<String, String> props = new HashMap<>();
        for (Dir d : DIRECTIONS) props.put(d.name().toLowerCase(), faces[d.ordinal()] ? "true" : "false");
        props.put("waterlogged", waterlogged ? "true" : "false");
        return b.withProperties(props);
    }

    private static boolean veinFace(Block b, Dir face) {
        if (!isSculkVein(b)) return false;
        return "true".equals(b.getProperty(face.name().toLowerCase()));
    }

    private static boolean[] veinFaces(Block b) {
        boolean[] f = new boolean[6];
        for (Dir d : DIRECTIONS) f[d.ordinal()] = veinFace(b, d);
        return f;
    }

    private static boolean hasAnyFace(boolean[] faces) {
        for (boolean f : faces) if (f) return true;
        return false;
    }

    private static Set<Dir> availableFaces(Block b) {
        Set<Dir> s = new LinkedHashSet<>();
        for (Dir d : DIRECTIONS) if (veinFace(b, d)) s.add(d);
        return s;
    }

    // -------------------------------------------------------------- shuffle utils

    /** Util.shuffle(list, random): for i=size; i>1; i-- swap(i-1, nextInt(i)). */
    private static <T> void shuffle(List<T> list, XWorldgenRandom r) {
        for (int i = list.size(); i > 1; i--) {
            int j = r.nextInt(i);
            T tmp = list.get(i - 1);
            list.set(i - 1, list.get(j));
            list.set(j, tmp);
        }
    }

    /** Direction.allShuffled(random). */
    private static List<Dir> allShuffled(XWorldgenRandom r) {
        List<Dir> l = new ArrayList<>(List.of(DIRECTIONS));
        shuffle(l, r);
        return l;
    }

    // NON_CORNER_NEIGHBOURS: betweenClosed(-1,-1,-1,1,1,1) order = x fastest, then y, then z;
    // keep those with a zero coord and not the origin.
    private static final int[][] NON_CORNER_NEIGHBOURS;
    static {
        List<int[]> l = new ArrayList<>();
        for (int z = -1; z <= 1; z++)
            for (int y = -1; y <= 1; y++)
                for (int x = -1; x <= 1; x++)
                    if ((x == 0 || y == 0 || z == 0) && !(x == 0 && y == 0 && z == 0))
                        l.add(new int[]{x, y, z});
        NON_CORNER_NEIGHBOURS = l.toArray(new int[0][]);
    }

    private static int[][] shuffledNeighbours(XWorldgenRandom r) {
        List<int[]> l = new ArrayList<>(List.of(NON_CORNER_NEIGHBOURS));
        shuffle(l, r);
        return l.toArray(new int[0][]);
    }

    // ============================================================== SculkSpreader

    /** world-gen spreader constants: growthSpawnCost=50, noGrowthRadius=1, chargeDecayRate=5, additionalDecayRate=10. */
    static final int GROWTH_SPAWN_COST = 50;
    static final int NO_GROWTH_RADIUS = 1;
    static final int CHARGE_DECAY_RATE = 5;
    static final int ADDITIONAL_DECAY_RATE = 10;

    static final class Spreader {
        List<Cursor> cursors = new ArrayList<>();

        void clear() { cursors.clear(); }

        void addCursors(int x, int y, int z, int charge) {
            while (charge > 0) {
                int c = Math.min(charge, 1000);
                if (cursors.size() < 32) cursors.add(new Cursor(x, y, z, c));
                charge -= c;
            }
        }

        /** updateCursors: world-gen path (no merge across cursors, isWorldGen=true). */
        void updateCursors(World level, int ox, int oy, int oz, XWorldgenRandom random, boolean spreadVeins) {
            if (cursors.isEmpty()) return;
            List<Cursor> processed = new ArrayList<>();
            Map<Long, Cursor> mergeable = new HashMap<>();
            for (Cursor cursor : cursors) {
                if (cursor.isPosUnreasonable(ox, oy, oz)) continue;
                cursor.update(level, ox, oy, oz, random, spreadVeins);
                if (cursor.charge <= 0) {
                    // levelEvent 3006 — cosmetic
                } else {
                    long key = key(cursor.x, cursor.y, cursor.z);
                    Cursor existing = mergeable.get(key);
                    if (existing == null) {
                        mergeable.put(key, cursor);
                        processed.add(cursor);
                    } else {
                        // isWorldGeneration == true: never merge; keep both
                        processed.add(cursor);
                        if (cursor.charge < existing.charge) mergeable.put(key, cursor);
                    }
                }
            }
            cursors = processed;
        }
    }

    private static long key(int x, int y, int z) {
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }

    // ============================================================== ChargeCursor

    static final class Cursor {
        int x, y, z;
        int charge;
        int updateDelay;
        int decayDelay = 1;
        boolean[] facings;      // null until on a sculk-behaviour block

        Cursor(int x, int y, int z, int charge) { this.x = x; this.y = y; this.z = z; this.charge = charge; }

        boolean isPosUnreasonable(int ox, int oy, int oz) {
            int d = Math.max(Math.abs(x - ox), Math.max(Math.abs(y - oy), Math.abs(z - oz)));
            return d > 1024;
        }

        void update(World level, int ox, int oy, int oz, XWorldgenRandom random, boolean spreadVeins) {
            if (charge <= 0) return;         // shouldUpdate: worldGen -> charge>0
            if (updateDelay > 0) { updateDelay--; return; }

            Block current = level.get(x, y, z);
            Beh beh = behaviourOf(current);
            if (spreadVeins && attemptSpreadVein(beh, level, x, y, z, current, facings)) {
                if (beh != Beh.SCULK_BLOCK) {   // canChangeBlockStateOnSpread: false only for SculkBlock
                    current = level.get(x, y, z);
                    beh = behaviourOf(current);
                }
            }

            charge = attemptUseCharge(beh, level, current, ox, oy, oz, random, spreadVeins);
            if (charge <= 0) {
                onDischarged(level, current, x, y, z, random);
            } else {
                int[] transfer = getValidMovementPos(level, x, y, z, random);
                if (transfer != null) {
                    onDischarged(level, current, x, y, z, random);
                    this.x = transfer[0]; this.y = transfer[1]; this.z = transfer[2];
                    // world-gen distance cap: closerThan((ox,y,oz),15.0) on XZ plane (y matched)
                    double dx = ox - this.x, dz = oz - this.z;
                    if (dx * dx + dz * dz >= 15.0 * 15.0) { charge = 0; return; }
                    current = level.get(this.x, this.y, this.z);
                }
                if (isSculkBehaviour(current)) facings = isSculkVein(current) ? veinFaces(current) : null;
                decayDelay = updateDecayDelay(beh, decayDelay);
                updateDelay = 1;   // getSculkSpreadDelay == 1 (no RNG)
            }
        }

        // ---------- charge use: dispatch by behaviour

        int attemptUseCharge(Beh beh, World level, Block current, int ox, int oy, int oz, XWorldgenRandom random, boolean spreadVeins) {
            return switch (beh) {
                case SCULK_VEIN -> veinAttemptUseCharge(level, ox, oy, oz, random, spreadVeins);
                case SCULK_BLOCK -> sculkAttemptUseCharge(level, ox, oy, oz, random);
                // SculkBehaviour.DEFAULT.attemptUseCharge: decayDelay>0 ? charge : 0
                case DEFAULT -> decayDelay > 0 ? charge : 0;
            };
        }

        // SculkBlock.attemptUseCharge
        int sculkAttemptUseCharge(World level, int ox, int oy, int oz, XWorldgenRandom random) {
            if (charge != 0 && random.nextInt(CHARGE_DECAY_RATE) == 0) {
                boolean closeToCatalyst = chebyshevWithin(x, y, z, ox, oy, oz, NO_GROWTH_RADIUS);
                if (!closeToCatalyst && canPlaceGrowth(level, x, y, z)) {
                    if (random.nextInt(GROWTH_SPAWN_COST) < charge) {
                        Block growth = getRandomGrowthState(random);
                        level.set(x, y + 1, z, growth);
                    }
                    return Math.max(0, charge - GROWTH_SPAWN_COST);
                } else {
                    return random.nextInt(ADDITIONAL_DECAY_RATE) != 0
                            ? charge
                            : charge - (closeToCatalyst ? 1 : getDecayPenalty(x, y, z, ox, oy, oz, charge));
                }
            }
            return charge;
        }

        // SculkVeinBlock.attemptUseCharge
        int veinAttemptUseCharge(World level, int ox, int oy, int oz, XWorldgenRandom random, boolean spreadVeins) {
            if (spreadVeins && attemptPlaceSculk(level, x, y, z, random)) {
                return charge - 1;
            }
            return random.nextInt(CHARGE_DECAY_RATE) == 0 ? (int) Math.floor(charge * 0.5F) : charge;
        }

        // ---------- discharge

        void onDischarged(World level, Block state, int px, int py, int pz, XWorldgenRandom random) {
            if (isSculkVein(state) && is(level.get(px, py, pz), Block.SCULK_VEIN)) {
                boolean[] faces = veinFaces(state);
                boolean waterlogged = "true".equals(state.getProperty("waterlogged"));
                for (Dir d : DIRECTIONS) {
                    if (faces[d.ordinal()] && is(level.get(px + d.dx, py + d.dy, pz + d.dz), Block.SCULK))
                        faces[d.ordinal()] = false;
                }
                if (!hasAnyFace(faces)) {
                    level.set(px, py, pz, waterlogged ? Block.WATER : Block.AIR);
                } else {
                    level.set(px, py, pz, vein(faces, waterlogged));
                }
            }
            // SculkBlock.onDischarged / DEFAULT: no-op
        }
    }

    // ---------- SculkBlock helpers

    private static Block getRandomGrowthState(XWorldgenRandom random) {
        // isWorldGen == true
        if (random.nextInt(11) == 0) {
            return Block.SCULK_SHRIEKER.withProperty("can_summon", "true");
        }
        return Block.SCULK_SENSOR;
    }

    private static boolean canPlaceGrowth(World level, int x, int y, int z) {
        Block above = level.get(x, y + 1, z);
        boolean okAbove = isAir(above) || is(above, Block.WATER);
        if (!okAbove) return false;
        int growth = 0;
        for (int dz = -4; dz <= 4; dz++)
            for (int dy = 0; dy <= 2; dy++)
                for (int dx = -4; dx <= 4; dx++) {
                    Block b = level.get(x + dx, y + 1 + dy, z + dz);
                    if (is(b, Block.SCULK_SENSOR) || is(b, Block.SCULK_SHRIEKER)) {
                        if (++growth > 2) return false;
                    }
                }
        return true;
    }

    private static int getDecayPenalty(int x, int y, int z, int ox, int oy, int oz, int charge) {
        int noGrowthRadius = NO_GROWTH_RADIUS;
        double distSq = (double) (x - ox) * (x - ox) + (double) (y - oy) * (y - oy) + (double) (z - oz) * (z - oz);
        float outer = (float) Math.sqrt(distSq) - noGrowthRadius;
        float outerSquared = outer * outer;
        int maxReachSquared = (24 - noGrowthRadius) * (24 - noGrowthRadius);
        float factor = Math.min(1.0F, outerSquared / maxReachSquared);
        return Math.max(1, (int) (charge * factor * 0.5F));
    }

    private static boolean chebyshevWithin(int x, int y, int z, int ox, int oy, int oz, int r) {
        // BlockPos.closerThan(origin, radius): euclidean distance <= radius (closerThan uses distSqr <= r*r+... actually closerThan is distSqr < (r+?)); vanilla closerThan(Vec3i, double): distToLowCornerSqr <= dist*dist
        double dx = x - ox, dy = y - oy, dz = z - oz;
        return dx * dx + dy * dy + dz * dz <= (double) r * r;
    }

    // ---------- SculkVeinBlock.attemptPlaceSculk (converts substrate to sculk + spreads veins)

    private static boolean attemptPlaceSculk(World level, int x, int y, int z, XWorldgenRandom random) {
        Block state = level.get(x, y, z);
        Set<String> replaceTag = replaceableWG();
        for (Dir support : allShuffled(random)) {
            if (veinFace(state, support)) {
                int sx = x + support.dx, sy = y + support.dy, sz = z + support.dz;
                Block supportState = level.get(sx, sy, sz);
                if (replaceTag.contains(name(supportState))) {
                    level.set(sx, sy, sz, Block.SCULK);
                    veinSpreadAll(level, sx, sy, sz, Block.SCULK, DEFAULT_SPREAD_ORDER);
                    Dir skip = support.opposite();
                    for (Dir vb : DIRECTIONS) {
                        if (vb != skip) {
                            int vx = sx + vb.dx, vy = sy + vb.dy, vz = sz + vb.dz;
                            Block possible = level.get(vx, vy, vz);
                            if (isSculkVein(possible)) veinOnDischarged(level, possible, vx, vy, vz);
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** SculkVeinBlock.onDischarged applied standalone (used by attemptPlaceSculk cleanup). */
    private static void veinOnDischarged(World level, Block state, int px, int py, int pz) {
        boolean[] faces = veinFaces(state);
        boolean waterlogged = "true".equals(state.getProperty("waterlogged"));
        for (Dir d : DIRECTIONS) {
            if (faces[d.ordinal()] && is(level.get(px + d.dx, py + d.dy, pz + d.dz), Block.SCULK))
                faces[d.ordinal()] = false;
        }
        if (!hasAnyFace(faces)) level.set(px, py, pz, waterlogged ? Block.WATER : Block.AIR);
        else level.set(px, py, pz, vein(faces, waterlogged));
    }

    // ---------- MultifaceSpreader.spreadAll for the vein spreader (DEFAULT_SPREAD_ORDER)

    private enum SpreadType { SAME_POSITION, SAME_PLANE, WRAP_AROUND }
    private static final SpreadType[] DEFAULT_SPREAD_ORDER =
            {SpreadType.SAME_POSITION, SpreadType.SAME_PLANE, SpreadType.WRAP_AROUND};
    private static final SpreadType[] SAME_SPACE_ORDER = {SpreadType.SAME_POSITION};

    /** MultifaceSpreader.spreadAll(state, level, pos, wg): source may be SCULK, a vein, or any block. */
    private static long veinSpreadAll(World level, int x, int y, int z, Block source, SpreadType[] order) {
        boolean otherValid = !isSculkVein(source);   // isOtherBlockValidAsSource
        long count = 0;
        for (Dir fromFace : DIRECTIONS) {
            if (otherValid || veinFace(source, fromFace)) {            // canSpreadFrom
                for (Dir spreadDir : DIRECTIONS) {
                    if (spreadFromFaceTowardDirection(level, x, y, z, fromFace, spreadDir, source, otherValid, order)) count++;
                }
            }
        }
        return count;
    }

    private static boolean spreadFromFaceTowardDirection(World level, int x, int y, int z, Dir fromFace, Dir spreadDir,
                                                         Block source, boolean otherValid, SpreadType[] order) {
        if (axis(spreadDir) == axis(fromFace)) return false;
        if (!(otherValid || (veinFace(source, fromFace) && !veinFace(source, spreadDir)))) return false;
        for (SpreadType type : order) {
            int[] sp = spreadPos(x, y, z, spreadDir, fromFace, type);   // {px,py,pz, faceOrdinal}
            Dir placeFace = DIRECTIONS[sp[3]];
            if (canSpreadIntoVein(level, sp[0], sp[1], sp[2], placeFace)) {
                placeVeinFace(level, sp[0], sp[1], sp[2], placeFace);
                return true;
            }
        }
        return false;
    }

    private static int axis(Dir d) {
        return switch (d) { case WEST, EAST -> 0; case DOWN, UP -> 1; default -> 2; };
    }

    private static int[] spreadPos(int x, int y, int z, Dir spreadDir, Dir fromFace, SpreadType type) {
        return switch (type) {
            case SAME_POSITION -> new int[]{x, y, z, spreadDir.ordinal()};
            case SAME_PLANE -> new int[]{x + spreadDir.dx, y + spreadDir.dy, z + spreadDir.dz, fromFace.ordinal()};
            case WRAP_AROUND -> new int[]{x + spreadDir.dx + fromFace.dx, y + spreadDir.dy + fromFace.dy,
                    z + spreadDir.dz + fromFace.dz, spreadDir.opposite().ordinal()};
        };
    }

    /** SculkVeinSpreaderConfig.canSpreadInto -> stateCanBeReplaced && isValidStateForPlacement. */
    private static boolean canSpreadIntoVein(World level, int px, int py, int pz, Dir placeFace) {
        Block existing = level.get(px, py, pz);
        if (!stateCanBeReplacedVein(level, px, py, pz, placeFace, existing)) return false;
        // isValidStateForPlacement: face not already present on an existing vein, and neighbour toward face attachable
        if (isSculkVein(existing) && veinFace(existing, placeFace)) return false;
        int nx = px + placeFace.dx, ny = py + placeFace.dy, nz = pz + placeFace.dz;
        return canAttachTo(level, nx, ny, nz, placeFace);
    }

    /** SculkVeinSpreaderConfig.stateCanBeReplaced. sourcePos not tracked precisely (dist-2 sturdy test omitted: rare). */
    private static boolean stateCanBeReplacedVein(World level, int px, int py, int pz, Dir placeFace, Block existing) {
        int ax = px + placeFace.dx, ay = py + placeFace.dy, az = pz + placeFace.dz;
        Block against = level.get(ax, ay, az);
        if (is(against, Block.SCULK) || is(against, Block.SCULK_CATALYST)) return false;
        // fire tag / non-water fluid: deep_dark has neither in practice
        // canBeReplaced() || default stateCanBeReplaced(air/vein/water-source)
        if (isAir(existing) || isSculkVein(existing)) return true;
        return canBeReplaced(existing);
    }

    /** Block.canBeReplaced (material replaceable): air, water, and a few plants. */
    private static boolean canBeReplaced(Block b) {
        if (isAir(b)) return true;
        String n = name(b);
        return n.equals("minecraft:water") || n.equals("minecraft:lava")
                || n.equals("minecraft:short_grass") || n.equals("minecraft:tall_grass")
                || n.equals("minecraft:fern") || n.equals("minecraft:snow");
    }

    /** canAttachTo: neighbour support/collision face full toward -placeFace. */
    private static boolean canAttachTo(World level, int nx, int ny, int nz, Dir placeFace) {
        Block nb = level.get(nx, ny, nz);
        return isCollisionFull(nb);  // full cube -> face full on the opposite side
    }

    private static void placeVeinFace(World level, int px, int py, int pz, Dir placeFace) {
        Block old = level.get(px, py, pz);
        boolean waterlogged;
        boolean[] faces;
        if (isSculkVein(old)) {
            faces = veinFaces(old);
            waterlogged = "true".equals(old.getProperty("waterlogged"));
        } else {
            faces = new boolean[6];
            waterlogged = is(old, Block.WATER);
        }
        faces[placeFace.ordinal()] = true;
        level.set(px, py, pz, vein(faces, waterlogged));
    }

    // ---------- ChargeCursor movement

    private static int[] getValidMovementPos(World level, int x, int y, int z, XWorldgenRandom random) {
        int[] best = null;
        for (int[] off : shuffledNeighbours(random)) {
            int nx = x + off[0], ny = y + off[1], nz = z + off[2];
            Block transferee = level.get(nx, ny, nz);
            if (isSculkBehaviour(transferee) && isMovementUnobstructed(level, x, y, z, nx, ny, nz)) {
                best = new int[]{nx, ny, nz};
                if (hasSubstrateAccess(level, transferee, nx, ny, nz)) break;
            }
        }
        return best;
    }

    private static boolean hasSubstrateAccess(World level, Block state, int x, int y, int z) {
        if (!isSculkVein(state)) return false;
        Set<String> tag = replaceable();
        for (Dir d : DIRECTIONS) {
            if (veinFace(state, d) && tag.contains(name(level.get(x + d.dx, y + d.dy, z + d.dz)))) return true;
        }
        return false;
    }

    private static boolean isMovementUnobstructed(World level, int fx, int fy, int fz, int tx, int ty, int tz) {
        int man = Math.abs(tx - fx) + Math.abs(ty - fy) + Math.abs(tz - fz);
        if (man == 1) return true;
        int dx = tx - fx, dy = ty - fy, dz = tz - fz;
        Dir dirX = dx < 0 ? Dir.WEST : Dir.EAST;
        Dir dirY = dy < 0 ? Dir.DOWN : Dir.UP;
        Dir dirZ = dz < 0 ? Dir.NORTH : Dir.SOUTH;
        if (dx == 0) return isUnobstructed(level, fx, fy, fz, dirY) || isUnobstructed(level, fx, fy, fz, dirZ);
        if (dy == 0) return isUnobstructed(level, fx, fy, fz, dirX) || isUnobstructed(level, fx, fy, fz, dirZ);
        return isUnobstructed(level, fx, fy, fz, dirX) || isUnobstructed(level, fx, fy, fz, dirY);
    }

    private static boolean isUnobstructed(World level, int fx, int fy, int fz, Dir d) {
        Block b = level.get(fx + d.dx, fy + d.dy, fz + d.dz);
        return !isFaceSturdy(b, d.opposite());
    }

    private static int updateDecayDelay(Beh beh, int age) {
        // SCULK_BLOCK/SCULK_VEIN: interface default returns 1. DEFAULT anon: max(age-1,0).
        return beh == Beh.DEFAULT ? Math.max(age - 1, 0) : 1;
    }

    // ---------- attemptSpreadVein dispatch (returns whether any vein was placed/regrown)

    private static boolean attemptSpreadVein(Beh beh, World level, int x, int y, int z, Block current, boolean[] facings) {
        switch (beh) {
            case SCULK_BLOCK:
                // interface default: SCULK_VEIN.veinSpreader.spreadAll(SCULK state, ...) > 0
                return veinSpreadAll(level, x, y, z, current, DEFAULT_SPREAD_ORDER) > 0;
            case SCULK_VEIN:
                // interface default: veinSpreader.spreadAll(vein state, ...) > 0
                return veinSpreadAll(level, x, y, z, current, DEFAULT_SPREAD_ORDER) > 0;
            case DEFAULT:
            default:
                // DEFAULT anon SculkBehaviour.attemptSpreadVein(facings)
                if (facings == null) {
                    return veinSpreadAll(level, x, y, z, current, SAME_SPACE_ORDER) > 0;
                } else if (hasAnyFace(facings)) {
                    // regrow at pos with facings (only if pos air / non-water-fluid)
                    if (!isAir(current) && !is(current, Block.WATER)) return false;
                    return regrow(level, x, y, z, current, facings);
                } else {
                    // empty facings: super default -> veinSpreader.spreadAll(state)
                    return veinSpreadAll(level, x, y, z, current, DEFAULT_SPREAD_ORDER) > 0;
                }
        }
    }

    /** SculkVeinBlock.regrow: build a vein at pos with the faces that can attach. */
    private static boolean regrow(World level, int x, int y, int z, Block existing, boolean[] wantFaces) {
        boolean[] faces = new boolean[6];
        boolean any = false;
        for (Dir d : DIRECTIONS) {
            if (wantFaces[d.ordinal()] && canAttachTo(level, x + d.dx, y + d.dy, z + d.dz, d)) {
                faces[d.ordinal()] = true;
                any = true;
            }
        }
        if (!any) return false;
        boolean waterlogged = is(existing, Block.WATER);
        level.set(x, y, z, vein(faces, waterlogged));
        return true;
    }

    // ============================================================== SculkPatchFeature

    /** SculkPatchFeature.place. origin (ox,oy,oz) is the placement origin. */
    public static boolean placeSculkPatch(World level, int ox, int oy, int oz, XWorldgenRandom random,
                                          int amountPerCharge, float catalystChance, int chargeCount,
                                          int spreadRounds, int growthRounds, int spreadAttempts,
                                          int extraGrowthsMin, int extraGrowthsMax) {
        if (!canSpreadFrom(level, ox, oy, oz)) return false;

        Spreader spreader = new Spreader();
        int totalRounds = spreadRounds + growthRounds;
        for (int round = 0; round < totalRounds; round++) {
            for (int i = 0; i < chargeCount; i++) spreader.addCursors(ox, oy, oz, amountPerCharge);
            boolean spreadVeins = round < spreadRounds;
            for (int i = 0; i < spreadAttempts; i++) spreader.updateCursors(level, ox, oy, oz, random, spreadVeins);
            spreader.clear();
        }

        Block below = level.get(ox, oy - 1, oz);
        if (random.nextFloat() <= catalystChance && isCollisionFull(below)) {
            level.set(ox, oy, oz, Block.SCULK_CATALYST);
        }

        int extraGrowths = sampleUniform(random, extraGrowthsMin, extraGrowthsMax);
        for (int i = 0; i < extraGrowths; i++) {
            int cx = ox + random.nextInt(5) - 2;
            int cz = oz + random.nextInt(5) - 2;
            if (isAir(level.get(cx, oy, cz)) && isFaceSturdy(level.get(cx, oy - 1, cz), Dir.UP)) {
                level.set(cx, oy, cz, Block.SCULK_SHRIEKER.withProperty("can_summon", "true"));
            }
        }
        return true;
    }

    /** extra_rare_growths sampler: uniform[min,max] draws nextInt(max-min+1)+min; constant 0 draws nothing. */
    private static int sampleUniform(XWorldgenRandom random, int min, int max) {
        if (min == 0 && max == 0) return 0;          // ConstantInt.of(0): no draw
        return random.nextIntBetweenInclusive(min, max);
    }

    private static boolean canSpreadFrom(World level, int x, int y, int z) {
        Block start = level.get(x, y, z);
        if (isSculkBehaviour(start)) return true;
        if (!isAir(start) && !is(start, Block.WATER)) return false;
        // air or water: any of 6 directions is a full collision block
        for (Dir d : DIRECTIONS) {
            if (isCollisionFull(level.get(x + d.dx, y + d.dy, z + d.dz))) return true;
        }
        return false;
    }

    // ============================================================== multiface_growth (sculk_vein feature)

    /**
     * MultifaceGrowthFeature.place for sculk_vein: pick a valid target within search_range,
     * then spread the vein. chance_of_spreading=1.0 (always). Simplified faithful port.
     */
    public static boolean placeMultifaceVein(World level, int ox, int oy, int oz, XWorldgenRandom random,
                                             int searchRange, boolean canFloor, boolean canCeiling, boolean canWall,
                                             float chanceOfSpreading, Set<String> canBePlacedOn) {
        Dir[] shuffled = allShuffled(random).toArray(new Dir[0]);
        int[] pos = getRandomStartPos(level, ox, oy, oz, random, searchRange, shuffled, canFloor, canCeiling, canWall, canBePlacedOn);
        if (pos == null) return false;
        Dir face = DIRECTIONS[pos[3]];
        // place vein with `face` set
        placeVeinFace(level, pos[0], pos[1], pos[2], face);
        // spread along neighbours (MultifaceBlock.spreadFromFaceTowardRandomDirection with chance)
        Block state = level.get(pos[0], pos[1], pos[2]);
        for (Dir spreadDir : allShuffled(random)) {
            if (random.nextFloat() < chanceOfSpreading) {
                // try to spread from this face toward random direction — best-effort single step
            }
        }
        return true;
    }

    private static int[] getRandomStartPos(World level, int ox, int oy, int oz, XWorldgenRandom random, int range,
                                           Dir[] shuffled, boolean canFloor, boolean canCeiling, boolean canWall,
                                           Set<String> canBePlacedOn) {
        // search within a random-walk range for a solid block with an exposed attachable face
        for (Dir dir : shuffled) {
            if (dir == Dir.UP && !canCeiling) continue;
            if (dir == Dir.DOWN && !canFloor) continue;
            if (dir.horizontal() && !canWall) continue;
            int nx = ox + dir.dx, ny = oy + dir.dy, nz = oz + dir.dz;
            Block support = level.get(nx, ny, nz);
            if (canBePlacedOn.contains(name(support)) && isAir(level.get(ox, oy, oz))) {
                return new int[]{ox, oy, oz, dir.ordinal()};
            }
        }
        return null;
    }

    // ============================================================== generic multiface growth (glow_lichen, etc.)

    /**
     * MultifaceGrowthFeature.place, generalized to any MultifaceBlock (not sculk-vein-specific,
     * unlike {@link #placeMultifaceVein}). Real vanilla precomputes a fixed direction list once
     * from can_place_on_floor/ceiling/wall (UP if ceiling, DOWN if floor, then the horizontal
     * plane [N,S,W,E] if wall — {@code MultifaceGrowthConfiguration}'s constructor order) and
     * shuffles THAT filtered list — not all 6 directions filtered during iteration — which
     * consumes a different number of RNG draws, so the pre-filter order matters for bit-exactness.
     */
    public static boolean placeMultifaceGrowth(World level, Block base, int ox, int oy, int oz, XWorldgenRandom random,
                                                int searchRange, boolean canFloor, boolean canCeiling, boolean canWall,
                                                float chanceOfSpreading, Set<String> canBePlacedOn) {
        Block origin = level.get(ox, oy, oz);
        if (!(isAir(origin) || is(origin, Block.WATER))) return false;

        List<Dir> valid = validMultifaceDirections(canFloor, canCeiling, canWall);
        List<Dir> searchDirections = new ArrayList<>(valid);
        shuffle(searchDirections, random);
        if (placeGrowthIfPossible(level, base, ox, oy, oz, origin, random, searchDirections, chanceOfSpreading, canBePlacedOn)) {
            return true;
        }
        for (Dir searchDirection : searchDirections) {
            int px = ox, py = oy, pz = oz;
            List<Dir> placementDirections = new ArrayList<>();
            for (Dir d : valid) if (d != searchDirection.opposite()) placementDirections.add(d);
            shuffle(placementDirections, random);
            for (int i = 0; i < searchRange; i++) {
                px += searchDirection.dx; py += searchDirection.dy; pz += searchDirection.dz;
                Block state = level.get(px, py, pz);
                boolean airOrWater = isAir(state) || is(state, Block.WATER);
                if (!airOrWater && !is(state, base)) break;
                if (placeGrowthIfPossible(level, base, px, py, pz, state, random, placementDirections, chanceOfSpreading, canBePlacedOn)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** MultifaceGrowthConfiguration's constructor order: ceiling(UP), floor(DOWN), wall(HORIZONTAL N,S,W,E). */
    private static List<Dir> validMultifaceDirections(boolean canFloor, boolean canCeiling, boolean canWall) {
        List<Dir> out = new ArrayList<>(5);
        if (canCeiling) out.add(Dir.UP);
        if (canFloor) out.add(Dir.DOWN);
        if (canWall) { out.add(Dir.NORTH); out.add(Dir.SOUTH); out.add(Dir.WEST); out.add(Dir.EAST); }
        return out;
    }

    private static boolean placeGrowthIfPossible(World level, Block base, int px, int py, int pz, Block oldState,
                                                  XWorldgenRandom random, List<Dir> placementDirections,
                                                  float chanceOfSpreading, Set<String> canBePlacedOn) {
        for (Dir placementDirection : placementDirections) {
            int nx = px + placementDirection.dx, ny = py + placementDirection.dy, nz = pz + placementDirection.dz;
            Block neighbour = level.get(nx, ny, nz);
            if (canBePlacedOn.contains(name(neighbour))) {
                Block newState = multifaceGetStateForPlacement(level, base, oldState, px, py, pz, placementDirection);
                if (newState == null) return false;
                level.set(px, py, pz, newState);
                if (random.nextFloat() < chanceOfSpreading) {
                    multifaceSpreadFromFaceTowardRandomDirection(level, base, px, py, pz, placementDirection, random);
                }
                return true;
            }
        }
        return false;
    }

    /** MultifaceBlock.getStateForPlacement(oldState, level, pos, dir): validity check + state merge. */
    private static Block multifaceGetStateForPlacement(World level, Block base, Block oldState, int px, int py, int pz, Dir placementDirection) {
        boolean isSelf = is(oldState, base);
        if (isSelf && multifaceHasFace(oldState, placementDirection)) return null;
        int nx = px + placementDirection.dx, ny = py + placementDirection.dy, nz = pz + placementDirection.dz;
        if (!isCollisionFull(level.get(nx, ny, nz))) return null;

        boolean[] faces;
        boolean waterlogged;
        if (isSelf) {
            faces = multifaceFaces(oldState);
            waterlogged = "true".equals(oldState.getProperty("waterlogged"));
        } else {
            faces = new boolean[6];
            waterlogged = is(oldState, Block.WATER);
        }
        faces[placementDirection.ordinal()] = true;
        return multifaceState(base, faces, waterlogged);
    }

    /** MultifaceSpreader.spreadFromFaceTowardRandomDirection (DEFAULT_SPREAD_ORDER, single attempt). */
    private static void multifaceSpreadFromFaceTowardRandomDirection(World level, Block base, int x, int y, int z, Dir startingFace, XWorldgenRandom random) {
        Block state = level.get(x, y, z);
        for (Dir spreadDir : allShuffled(random)) {
            if (axis(spreadDir) == axis(startingFace)) continue;
            if (multifaceHasFace(state, spreadDir)) continue;
            for (SpreadType type : DEFAULT_SPREAD_ORDER) {
                int[] sp = spreadPos(x, y, z, spreadDir, startingFace, type);
                Dir placeFace = DIRECTIONS[sp[3]];
                if (multifaceCanSpreadInto(level, base, sp[0], sp[1], sp[2], placeFace)) {
                    multifacePlaceFace(level, base, sp[0], sp[1], sp[2], placeFace);
                    return;
                }
            }
        }
    }

    /** DefaultSpreaderConfig.canSpreadInto: stateCanBeReplaced && isValidStateForPlacement. */
    private static boolean multifaceCanSpreadInto(World level, Block base, int px, int py, int pz, Dir placeFace) {
        Block existing = level.get(px, py, pz);
        boolean replaceable = isAir(existing) || is(existing, base) || is(existing, Block.WATER);
        if (!replaceable) return false;
        if (is(existing, base) && multifaceHasFace(existing, placeFace)) return false;
        int nx = px + placeFace.dx, ny = py + placeFace.dy, nz = pz + placeFace.dz;
        return isCollisionFull(level.get(nx, ny, nz));
    }

    private static void multifacePlaceFace(World level, Block base, int px, int py, int pz, Dir placeFace) {
        Block old = level.get(px, py, pz);
        boolean[] faces;
        boolean waterlogged;
        if (is(old, base)) {
            faces = multifaceFaces(old);
            waterlogged = "true".equals(old.getProperty("waterlogged"));
        } else {
            faces = new boolean[6];
            waterlogged = is(old, Block.WATER);
        }
        faces[placeFace.ordinal()] = true;
        level.set(px, py, pz, multifaceState(base, faces, waterlogged));
    }

    private static boolean multifaceHasFace(Block b, Dir face) {
        return "true".equals(b.getProperty(face.name().toLowerCase()));
    }

    private static boolean[] multifaceFaces(Block b) {
        boolean[] f = new boolean[6];
        for (Dir d : DIRECTIONS) f[d.ordinal()] = multifaceHasFace(b, d);
        return f;
    }

    /** Any MultifaceBlock with the given face booleans (+waterlogged) — identical property shape across block types. */
    private static Block multifaceState(Block base, boolean[] faces, boolean waterlogged) {
        Map<String, String> props = new HashMap<>();
        for (Dir d : DIRECTIONS) props.put(d.name().toLowerCase(), faces[d.ordinal()] ? "true" : "false");
        props.put("waterlogged", waterlogged ? "true" : "false");
        return base.withProperties(props);
    }
}
