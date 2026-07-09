package dev.pointofpressure.minecom.worldgen.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.worldgen.vanilla.VTemplate.Dir;
import dev.pointofpressure.minecom.worldgen.vanilla.VTemplate.Rot;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Vanilla jigsaw assembly (JigsawPlacement.addPieces + Placer.tryPlacingChildren),
 * reimplemented from the algorithm. Given the world seed and a start chunk it
 * reproduces the exact ordered piece list (template, position, rotation, bbox)
 * that vanilla writes to a structure start — verified bit-for-bit against the
 * reference world's `structures/starts`. Only the RIGID projection path is used
 * by the overworld structures modelled here.
 */
public final class VJigsaw {

    // ------------------------------------------------------------------ pool model

    enum Kind { SINGLE, FEATURE, LIST, EMPTY }

    /** A pool element (single / feature / list / empty). Weight already expanded in the pool list. */
    static final class Element {
        final Kind kind;
        final String location;      // SINGLE: template location
        final boolean rigid;        // projection == rigid
        final String processors;    // SINGLE: processor_list ref, or null
        final String featureId;     // FEATURE: placed-feature reference (for placement/verify)
        final List<Element> subs;   // LIST: sub-elements

        Element(Kind kind, String location, boolean rigid, String processors, String featureId, List<Element> subs) {
            this.kind = kind; this.location = location; this.rigid = rigid;
            this.processors = processors; this.featureId = featureId; this.subs = subs;
        }
        static final Element EMPTY = new Element(Kind.EMPTY, null, true, null, null, null);
        static Element single(String loc, boolean rigid, String procs) {
            return new Element(Kind.SINGLE, loc, rigid, procs, null, null);
        }
        static Element feature(String id, boolean rigid) {
            return new Element(Kind.FEATURE, null, rigid, null, id, null);
        }
        static Element list(List<Element> subs, boolean rigid) {
            return new Element(Kind.LIST, null, rigid, null, null, subs);
        }
        boolean isEmpty() { return kind == Kind.EMPTY; }
        VTemplate template() { return VTemplate.load(location); }

        /** BoundingBox at position under rotation. */
        int[] boundingBox(int px, int py, int pz, Rot rot) {
            switch (kind) {
                case SINGLE -> { return template().boundingBox(px, py, pz, rot); }
                case FEATURE -> { return new int[]{px, py, pz, px, py, pz}; } // getSize ZERO
                case LIST -> {
                    int[] u = null;
                    for (Element s : subs) {
                        if (s.isEmpty()) continue;
                        int[] b = s.boundingBox(px, py, pz, rot);
                        if (u == null) u = b.clone();
                        else { for (int i = 0; i < 3; i++) u[i] = Math.min(u[i], b[i]); for (int i = 3; i < 6; i++) u[i] = Math.max(u[i], b[i]); }
                    }
                    return u;
                }
                default -> throw new IllegalStateException("bbox of empty element");
            }
        }
        int groundLevelDelta() { return 1; }

        /** getShuffledJigsawBlocks: type-specific RNG behaviour. */
        List<VTemplate.RJigsaw> shuffledJigsaws(int px, int py, int pz, Rot rot, VSurface.LegacyRandom r) {
            switch (kind) {
                case SINGLE -> {
                    List<VTemplate.RJigsaw> js = template().getJigsaws(px, py, pz, rot);
                    shuffle(js, r);
                    js.sort(HIGHEST_SELECTION_FIRST);   // stable
                    return js;
                }
                case FEATURE -> {
                    // one fixed connector at position: front DOWN, top SOUTH, name "bottom", pool/target empty, rollable
                    return List.of(new VTemplate.RJigsaw(px, py, pz, Dir.DOWN, Dir.SOUTH, VTemplate.Joint.ROLLABLE,
                            "minecraft:bottom", "minecraft:empty", "minecraft:empty", 0, 0));
                }
                case LIST -> { return subs.get(0).shuffledJigsaws(px, py, pz, rot, r); }
                default -> { return new ArrayList<>(); }
            }
        }

        String verifyName() {
            return switch (kind) {
                case SINGLE -> location;
                case FEATURE -> "FEATURE";
                case LIST -> "LIST";
                case EMPTY -> "EMPTY";
            };
        }
    }

    static final class Pool {
        final List<Element> templates;   // weight-expanded, declaration order
        final String fallback;           // fallback pool name
        final boolean isEmptyPool;       // this is minecraft:empty
        final boolean exists;            // false → not in the registry (missing resource)
        Pool(List<Element> templates, String fallback, boolean isEmptyPool, boolean exists) {
            this.templates = templates; this.fallback = fallback; this.isEmptyPool = isEmptyPool; this.exists = exists;
        }
        static final Pool NONEXISTENT = new Pool(List.of(), "minecraft:empty", false, false);
    }

    // ------------------------------------------------------------------ piece output

    public static final class Piece {
        public final Element element;
        public int posX, posY, posZ;
        public final Rot rotation;
        public int[] bb;                 // {minX,minY,minZ,maxX,maxY,maxZ}
        public int groundLevelDelta;
        public final List<int[]> junctions = new ArrayList<>(); // {sourceX, y, sourceZ, deltaY} for beardifier

        Piece(Element element, int posX, int posY, int posZ, Rot rotation, int[] bb, int gld) {
            this.element = element; this.posX = posX; this.posY = posY; this.posZ = posZ;
            this.rotation = rotation; this.bb = bb; this.groundLevelDelta = gld;
        }
        void move(int dy) { posY += dy; bb[1] += dy; bb[4] += dy; }
        public String location() { return element.verifyName(); }
        public Kind kind() { return element.kind; }
    }

    // ------------------------------------------------------------------ state

    private final long seed;
    private final Gson gson = new Gson();
    private final Map<String, Pool> poolCache = new HashMap<>();

    public VJigsaw(long seed) { this.seed = seed; }

    /** WORLD_SURFACE_WG first-free height provider (for heightmap-projected structures). */
    @FunctionalInterface
    public interface SurfaceHeight { int firstFreeWg(int x, int z); }

    private SurfaceHeight surface;
    public void setSurface(SurfaceHeight surface) { this.surface = surface; }

    // ------------------------------------------------------------------ pool loading

    private Pool pool(String name) {
        Pool p = poolCache.get(name);
        if (p == null) { p = loadPool(name); poolCache.put(name, p); }
        return p;
    }

    private Pool loadPool(String name) {
        String path = "/vanilla/template_pool/" + name.substring(name.indexOf(':') + 1) + ".json";
        try (InputStream in = VJigsaw.class.getResourceAsStream(path)) {
            if (in == null) {
                if (name.equals("minecraft:empty")) return new Pool(List.of(), "minecraft:empty", true, true);
                return Pool.NONEXISTENT;   // vanilla: pools.get(name).isEmpty() → skip connector
            }
            JsonObject root = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            String fallback = root.has("fallback") ? root.get("fallback").getAsString() : "minecraft:empty";
            List<Element> templates = new ArrayList<>();
            JsonArray elements = root.getAsJsonArray("elements");
            for (var el : elements) {
                JsonObject e = el.getAsJsonObject();
                int weight = e.has("weight") ? e.get("weight").getAsInt() : 1;
                Element parsed = parseElement(e.getAsJsonObject("element"));
                for (int i = 0; i < weight; i++) templates.add(parsed);
            }
            boolean isEmpty = name.equals("minecraft:empty");
            return new Pool(templates, fallback, isEmpty, true);
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to load pool " + path, ex);
        }
    }

    private Element parseElement(JsonObject elem) {
        String type = elem.get("element_type").getAsString();
        boolean rigid = !elem.has("projection") || elem.get("projection").getAsString().endsWith("rigid");
        switch (type) {
            case "minecraft:empty_pool_element" -> { return Element.EMPTY; }
            case "minecraft:single_pool_element", "minecraft:legacy_single_pool_element" -> {
                String loc = elem.get("location").getAsString();
                String procs = elem.has("processors") && elem.get("processors").isJsonPrimitive()
                        ? elem.get("processors").getAsString() : null;
                return Element.single(loc, rigid, procs);
            }
            case "minecraft:feature_pool_element" -> {
                String id = elem.has("feature") && elem.get("feature").isJsonPrimitive()
                        ? elem.get("feature").getAsString() : "inline";
                return Element.feature(id, rigid);
            }
            case "minecraft:list_pool_element" -> {
                List<Element> subs = new ArrayList<>();
                for (var s : elem.getAsJsonArray("elements")) subs.add(parseElement(s.getAsJsonObject()));
                return Element.list(subs, rigid);
            }
            default -> throw new IllegalStateException("unsupported pool element " + type);
        }
    }

    // ------------------------------------------------------------------ RNG helpers (LegacyRandomSource stream)

    private static Rot randomRotation(VSurface.LegacyRandom r) {
        return Rot.VALUES[r.nextInt(4)];
    }

    /** Util.shuffle(list, random) — in place. */
    private static <T> void shuffle(List<T> list, VSurface.LegacyRandom r) {
        for (int i = list.size(); i > 1; i--) {
            int swapTo = r.nextInt(i);
            T tmp = list.get(i - 1);
            list.set(i - 1, list.get(swapTo));
            list.set(swapTo, tmp);
        }
    }

    private static List<Rot> shuffledRotations(VSurface.LegacyRandom r) {
        List<Rot> l = new ArrayList<>(List.of(Rot.VALUES));
        shuffle(l, r);
        return l;
    }

    private static List<Element> shuffledTemplates(Pool p, VSurface.LegacyRandom r) {
        List<Element> l = new ArrayList<>(p.templates);
        shuffle(l, r);
        return l;
    }

    private static final Comparator<VTemplate.RJigsaw> HIGHEST_SELECTION_FIRST =
            Comparator.comparingInt((VTemplate.RJigsaw j) -> j.selectionPriority).reversed();

    // ------------------------------------------------------------------ collision "free" shape

    /** Free space = base AABB minus a set of occupied boxes; integer-inclusive with 0.25-deflate semantics. */
    private static final class Free {
        final int[] base;                       // {minX,minY,minZ,maxX,maxY,maxZ}
        final List<int[]> occupied = new ArrayList<>();
        Free(int[] base) { this.base = base; }
        boolean fits(int[] t) {
            if (t[0] < base[0] || t[1] < base[1] || t[2] < base[2]
                    || t[3] > base[3] || t[4] > base[4] || t[5] > base[5]) return false;
            for (int[] o : occupied) if (overlaps(t, o)) return false;
            return true;
        }
        void subtract(int[] t) { occupied.add(t); }
        static boolean overlaps(int[] a, int[] b) {
            return a[0] <= b[3] && b[0] <= a[3]
                    && a[1] <= b[4] && b[1] <= a[4]
                    && a[2] <= b[5] && b[2] <= a[5];
        }
    }

    // ------------------------------------------------------------------ assembly

    /** Definition of a jigsaw structure to assemble. */
    public static final class Def {
        public final String startPool, startJigsaw;    // startJigsaw null → anchor at position
        public final int maxDepth, maxDistH, maxDistV;
        public final boolean useExpansionHack;
        public final boolean projectStartToHeightmap;  // villages: WORLD_SURFACE_WG
        public final boolean uniformHeight;            // true → uniform [heightMin,heightMax]; else constant heightMin
        public final int heightMin, heightMax;
        public final int paddingBottom, paddingTop;

        private Def(String startPool, String startJigsaw, int maxDepth, boolean uniformHeight,
                    int heightMin, int heightMax, int maxDistH, int maxDistV, boolean useExpansionHack,
                    int paddingBottom, int paddingTop, boolean projectStartToHeightmap) {
            this.startPool = startPool; this.startJigsaw = startJigsaw; this.maxDepth = maxDepth;
            this.uniformHeight = uniformHeight; this.heightMin = heightMin; this.heightMax = heightMax;
            this.maxDistH = maxDistH; this.maxDistV = maxDistV; this.useExpansionHack = useExpansionHack;
            this.paddingBottom = paddingBottom; this.paddingTop = paddingTop;
            this.projectStartToHeightmap = projectStartToHeightmap;
        }
        /** Constant start height, no dimension padding (e.g. ancient_city). */
        public static Def constant(String startPool, String startJigsaw, int maxDepth, int height,
                                   int maxDistH, int maxDistV, boolean useExpansionHack) {
            return new Def(startPool, startJigsaw, maxDepth, false, height, height, maxDistH, maxDistV, useExpansionHack, 0, 0, false);
        }
        /** Uniform start height + symmetric dimension padding (e.g. trial_chambers). */
        public static Def uniform(String startPool, String startJigsaw, int maxDepth, int heightMin, int heightMax,
                                  int maxDistH, int maxDistV, boolean useExpansionHack, int padding) {
            return new Def(startPool, startJigsaw, maxDepth, true, heightMin, heightMax, maxDistH, maxDistV, useExpansionHack, padding, padding, false);
        }
        /** Surface-projected village (start_height absolute 0, project to WORLD_SURFACE_WG, expansion hack). */
        public static Def village(String startPool, int maxDepth, int maxDist) {
            return new Def(startPool, null, maxDepth, false, 0, 0, maxDist, maxDist, true, 0, 0, true);
        }
        /** Surface-projected, constant start height, no expansion hack (e.g. trail_ruins: absolute -15). */
        public static Def projected(String startPool, int maxDepth, int startHeight, int maxDist) {
            return new Def(startPool, null, maxDepth, false, startHeight, startHeight, maxDist, maxDist, false, 0, 0, true);
        }
    }

    private static final int MIN_Y = -64, MAX_Y_PLUS_1 = 320;

    /**
     * Assemble the structure at the given start chunk. Returns the ordered piece list,
     * or null if the start element resolves to empty. This mirrors the RNG-exact path
     * with a constant start height (rigid overworld structures).
     */
    /** Assembled structure: ordered pieces + the generation center (for the biome check). */
    public static final class Assembly {
        public final List<Piece> pieces;
        public final int centerX, centerY, centerZ;
        Assembly(List<Piece> pieces, int cx, int cy, int cz) {
            this.pieces = pieces; this.centerX = cx; this.centerY = cy; this.centerZ = cz;
        }
    }

    public List<Piece> assemble(Def def, int chunkX, int chunkZ) {
        return assemble(def, chunkX, chunkZ, java.util.Map.of());
    }

    public List<Piece> assemble(Def def, int chunkX, int chunkZ, Map<String, String> aliases) {
        Assembly a = assembleFull(def, chunkX, chunkZ, aliases);
        return a == null ? null : a.pieces;
    }

    /** aliases: resolved pool-alias map (alias pool name → concrete pool name). Empty for no aliases. */
    public Assembly assembleFull(Def def, int chunkX, int chunkZ, Map<String, String> aliases) {
        VSurface.LegacyRandom random = new VSurface.LegacyRandom(0L);
        random.setLargeFeatureSeed(seed, chunkX, chunkZ);

        // JigsawStructure.findGenerationPoint: sample start height (uniform draws one nextInt)
        int posX = chunkX << 4, posZ = chunkZ << 4;
        int height = def.uniformHeight
                ? def.heightMin + random.nextInt(def.heightMax - def.heightMin + 1)   // Mth.randomBetweenInclusive
                : def.heightMin;

        Rot centerRotation = randomRotation(random);
        Pool centerPool = pool(aliases.getOrDefault(def.startPool, def.startPool));
        if (centerPool.templates.isEmpty()) return null;
        Element centerElement = centerPool.templates.get(random.nextInt(centerPool.templates.size()));
        if (centerElement.isEmpty()) return null;

        // anchor: start jigsaw (if named) else the position itself
        int localAnchorX = 0, localAnchorY = 0, localAnchorZ = 0;
        if (def.startJigsaw != null) {
            int[] anchor = null;
            for (VTemplate.RJigsaw j : centerElement.shuffledJigsaws(posX, height, posZ, centerRotation, random)) {
                if (j.name.equals(def.startJigsaw)) { anchor = new int[]{j.x, j.y, j.z}; break; }
            }
            if (anchor == null) return null;
            localAnchorX = anchor[0] - posX; localAnchorY = anchor[1] - height; localAnchorZ = anchor[2] - posZ;
        }
        int adjX = posX - localAnchorX, adjY = height - localAnchorY, adjZ = posZ - localAnchorZ;

        int[] centerBB = centerElement.boundingBox(adjX, adjY, adjZ, centerRotation);
        Piece centerPiece = new Piece(centerElement, adjX, adjY, adjZ, centerRotation, centerBB, centerElement.groundLevelDelta());

        int centerX = (centerBB[3] + centerBB[0]) / 2;
        int centerZ = (centerBB[5] + centerBB[2]) / 2;
        // JigsawPlacement: bottomY = startHeight + getFirstFreeHeight(centerX, centerZ, WORLD_SURFACE_WG)
        int bottomY = (def.projectStartToHeightmap && surface != null)
                ? adjY + surface.firstFreeWg(centerX, centerZ)
                : adjY;
        int oldAbsoluteGroundY = centerBB[1] + centerPiece.groundLevelDelta;
        centerPiece.move(bottomY - oldAbsoluteGroundY);
        int centerY = bottomY + localAnchorY;

        // isStartTooCloseToWorldHeightLimits (padding != 0)
        if (def.paddingBottom != 0 || def.paddingTop != 0) {
            int minYPad = MIN_Y + def.paddingBottom, maxYPad = (MAX_Y_PLUS_1 - 1) - def.paddingTop;
            if (centerPiece.bb[1] < minYPad || centerPiece.bb[4] > maxYPad) return null;
        }

        List<Piece> pieces = new ArrayList<>();
        pieces.add(centerPiece);
        if (def.maxDepth <= 0) return new Assembly(pieces, centerX, centerY, centerZ);

        int[] aabb = new int[]{
                centerX - def.maxDistH, Math.max(centerY - def.maxDistV, MIN_Y + def.paddingBottom), centerZ - def.maxDistH,
                centerX + def.maxDistH, Math.min(centerY + def.maxDistV, (MAX_Y_PLUS_1 - def.paddingTop) - 1), centerZ + def.maxDistH
        };
        Free rootFree = new Free(aabb);
        rootFree.subtract(centerPiece.bb.clone());

        Placer placer = new Placer(pieces, random, def, aliases);
        placer.tryPlacingChildren(centerPiece, rootFree, 0);
        while (!placer.queue.isEmpty()) {
            PieceState st = placer.poll();
            placer.tryPlacingChildren(st.piece, st.free, st.depth);
        }
        return new Assembly(pieces, centerX, centerY, centerZ);
    }

    private record PieceState(Piece piece, Free free, int depth) {}

    private final class Placer {
        final List<Piece> pieces;
        final VSurface.LegacyRandom random;
        final Def def;
        final Map<String, String> aliases;
        // priority queue: highest placementPriority first, FIFO within a priority
        final TreeMap<Integer, java.util.ArrayDeque<PieceState>> queue =
                new TreeMap<>(Comparator.reverseOrder());

        Placer(List<Piece> pieces, VSurface.LegacyRandom random, Def def, Map<String, String> aliases) {
            this.pieces = pieces; this.random = random; this.def = def; this.aliases = aliases;
        }

        void add(PieceState s, int priority) {
            queue.computeIfAbsent(priority, k -> new java.util.ArrayDeque<>()).addLast(s);
        }
        PieceState poll() {
            var e = queue.firstEntry();
            PieceState s = e.getValue().removeFirst();
            if (e.getValue().isEmpty()) queue.remove(e.getKey());
            return s;
        }

        void tryPlacingChildren(Piece sourcePiece, Free contextFree, int depth) {
            Element sourceElement = sourcePiece.element;
            Rot sourceRotation = sourcePiece.rotation;
            boolean sourceRigid = sourceElement.rigid;
            int[] sourceBB = sourcePiece.bb;
            int sourceBoxY = sourceBB[1];
            Free sourceFree = null; // lazily created

            for (VTemplate.RJigsaw sourceJig : sourceElement.shuffledJigsaws(sourcePiece.posX, sourcePiece.posY, sourcePiece.posZ, sourceRotation, random)) {
                Dir sourceDir = sourceJig.front;
                int sjx = sourceJig.x, sjy = sourceJig.y, sjz = sourceJig.z;
                int tjx = sjx + sourceDir.dx, tjy = sjy + sourceDir.dy, tjz = sjz + sourceDir.dz;
                int sourceJigsawLocalY = sjy - sourceBoxY;

                String poolName = aliases.getOrDefault(sourceJig.pool, sourceJig.pool);
                Pool targetPool = pool(poolName);
                if (!targetPool.exists) continue;                                    // pool not in registry
                if (targetPool.templates.isEmpty() && !targetPool.isEmptyPool) continue;  // size 0 && !EMPTY
                Pool fallback = pool(targetPool.fallback);
                if (fallback.templates.isEmpty() && !fallback.isEmptyPool) continue;      // fallback size 0 && !EMPTY

                boolean attachInside = inside(sourceBB, tjx, tjy, tjz);
                Free childrenFree;
                if (attachInside) {
                    if (sourceFree == null) sourceFree = new Free(sourceBB.clone());
                    childrenFree = sourceFree;
                } else {
                    childrenFree = contextFree;
                }

                List<Element> targetPieces = new ArrayList<>();
                if (depth != def.maxDepth) targetPieces.addAll(shuffledTemplates(targetPool, random));
                targetPieces.addAll(shuffledTemplates(fallback, random));
                int placementPriority = sourceJig.placementPriority;

                outer:
                for (Element targetElement : targetPieces) {
                    if (targetElement.isEmpty()) break;
                    for (Rot targetRotation : shuffledRotations(random)) {
                        List<VTemplate.RJigsaw> targetJigsaws = targetElement.shuffledJigsaws(0, 0, 0, targetRotation, random);
                        // use_expansion_hack is false for the modelled structures → expandTo = 0
                        for (VTemplate.RJigsaw targetJig : targetJigsaws) {
                            if (!canAttach(sourceJig, targetJig)) continue;
                            int tjLocalX = targetJig.x, tjLocalY = targetJig.y, tjLocalZ = targetJig.z;
                            int rawBoxX = tjx - tjLocalX, rawBoxY = tjy - tjLocalY, rawBoxZ = tjz - tjLocalZ;
                            int[] rawBB = targetElement.boundingBox(rawBoxX, rawBoxY, rawBoxZ, targetRotation);
                            int rawTargetY = rawBB[1];
                            boolean targetRigid = targetElement.rigid;
                            int deltaY = sourceJigsawLocalY - tjLocalY + sourceDir.dy;
                            // NOTE: the terrain-matching (expansion-hack) Y projection needs an
                            // accurate 3D VoxelShape collision; the box-based Free here lets the
                            // Y-varying street pieces over-generate, so villages use the rigid
                            // path (coherent, if flat-only) until Free is upgraded.
                            int targetBoxY = sourceBoxY + deltaY; // rigid-rigid path
                            int yOffset = targetBoxY - rawTargetY;
                            int[] targetBB = new int[]{rawBB[0], rawBB[1] + yOffset, rawBB[2], rawBB[3], rawBB[4] + yOffset, rawBB[5]};
                            int targetBoxPosY = rawBoxY + yOffset;

                            if (childrenFree.fits(targetBB)) {
                                childrenFree.subtract(targetBB.clone());
                                int sourceGLD = sourcePiece.groundLevelDelta;
                                int targetGLD = targetRigid ? (sourceGLD - deltaY) : targetElement.groundLevelDelta();
                                Piece targetPiece = new Piece(targetElement, rawBoxX, targetBoxPosY, rawBoxZ,
                                        targetRotation, targetBB, targetGLD);

                                int junctionY = sourceBoxY + sourceJigsawLocalY; // sourceRigid path
                                sourcePiece.junctions.add(new int[]{tjx, junctionY - sourceJigsawLocalY + sourceGLD, tjz, deltaY});
                                targetPiece.junctions.add(new int[]{sjx, junctionY - tjLocalY + targetGLD, sjz, -deltaY});

                                pieces.add(targetPiece);
                                if (depth + 1 <= def.maxDepth) add(new PieceState(targetPiece, childrenFree, depth + 1), placementPriority);
                                break outer;
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean inside(int[] bb, int x, int y, int z) {
        return x >= bb[0] && x <= bb[3] && y >= bb[1] && y <= bb[4] && z >= bb[2] && z <= bb[5];
    }

    /** JigsawBlock.canAttach. */
    private static boolean canAttach(VTemplate.RJigsaw source, VTemplate.RJigsaw target) {
        boolean rollable = source.joint == VTemplate.Joint.ROLLABLE;
        return source.front == target.front.opposite()
                && (rollable || source.top == target.top)
                && source.target.equals(target.name);
    }
}
