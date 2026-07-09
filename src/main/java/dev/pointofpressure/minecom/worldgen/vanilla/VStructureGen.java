package dev.pointofpressure.minecom.worldgen.vanilla;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.block.Block;

import java.util.List;

/**
 * Places assembled jigsaw pieces into the world: for each piece, iterate its
 * template blocks in vanilla order, apply the built-in block_ignore +
 * jigsaw_replacement processors, then the piece's processor list, then rotate the
 * final state — matching StructureTemplate.placeInWorld + processBlockInfos. Pieces
 * are placed in assembly order so later pieces overwrite earlier ones.
 */
public final class VStructureGen {

    /** Writable world view: reads for protected/location predicates, writes placed blocks. */
    public interface Canvas extends VProcessors.Sink {
        void set(int x, int y, int z, Block block);
    }

    private VStructureGen() {}

    private static final int NO_CLIP = Integer.MIN_VALUE;

    /** Place every piece's blocks into the canvas (SINGLE + LIST elements; features handled elsewhere). */
    public static void place(List<VJigsaw.Piece> pieces, Canvas canvas) {
        for (VJigsaw.Piece piece : pieces) placePiece(piece, canvas, NO_CLIP, NO_CLIP, NO_CLIP, NO_CLIP);
    }

    /** Place one piece's blocks, keeping only those within the XZ clip window. */
    public static void placePieceClipped(VJigsaw.Piece piece, int minX, int minZ, int maxX, int maxZ, Canvas canvas) {
        placePiece(piece, canvas, minX, minZ, maxX, maxZ);
    }

    private static void placePiece(VJigsaw.Piece piece, Canvas canvas, int minX, int minZ, int maxX, int maxZ) {
        switch (piece.kind()) {
            case SINGLE -> placeTemplate(piece.location(), piece.posX, piece.posY, piece.posZ,
                    piece.rotation, piece.element.processors, canvas, minX, minZ, maxX, maxZ);
            case LIST -> {
                // list element: place each sub-element's template at the same origin/rotation
                for (VJigsaw.Element sub : piece.element.subs) {
                    if (sub.kind == VJigsaw.Kind.SINGLE) {
                        placeTemplate(sub.location, piece.posX, piece.posY, piece.posZ,
                                piece.rotation, sub.processors, canvas, minX, minZ, maxX, maxZ);
                    }
                }
            }
            default -> { /* FEATURE / EMPTY: no template blocks */ }
        }
    }

    private static void placeTemplate(String location, int px, int py, int pz, VTemplate.Rot rot,
                                      String processorList, Canvas canvas,
                                      int minX, int minZ, int maxX, int maxZ) {
        VTemplate t = VTemplate.load(location);
        VProcessors procs = processorList != null ? VProcessors.load(processorList) : null;

        boolean clip = minX != NO_CLIP;
        for (VTemplate.BlockInfo b : t.blocks) {
            int[] wp = VTemplate.transform(b.x, b.y, b.z, rot, 0, 0);
            int wx = wp[0] + px, wy = wp[1] + py, wz = wp[2] + pz;
            if (clip && (wx < minX || wx > maxX || wz < minZ || wz > maxZ)) continue;

            String name = b.state.key().asString();
            // built-in block_ignore: structure_block / structure_void never place
            if (name.equals("minecraft:structure_block") || name.equals("minecraft:structure_void")) continue;

            Block state = b.state;
            // built-in jigsaw_replacement: jigsaw → final_state (or drop if structure_void)
            if (name.equals("minecraft:jigsaw")) {
                Block fs = jigsawFinalState(b.nbt);
                if (fs == null) continue;
                state = fs;
            }

            // piece processor list (rule / protected_blocks / block_rot)
            if (procs != null) {
                state = procs.apply(state, wx, wy, wz, canvas);
                if (state == null) continue;
            }

            // rotate the final state (StructureTemplate placeInWorld: state.mirror().rotate())
            state = VBlockRotate.rotate(state, rot);
            canvas.set(wx, wy, wz, state);
        }
    }

    private static Block jigsawFinalState(CompoundBinaryTag nbt) {
        String fs = nbt != null ? nbt.getString("final_state", "minecraft:air") : "minecraft:air";
        // final_state is a block-state string, e.g. "minecraft:air" or "minecraft:deepslate[axis=y]"
        Block b;
        try {
            b = Block.fromState(fs);
        } catch (Exception e) {
            int br = fs.indexOf('[');
            b = Block.fromKey(br < 0 ? fs : fs.substring(0, br));
        }
        if (b == null) b = Block.AIR;
        if (b.key().asString().equals("minecraft:structure_void")) return null;
        return b;
    }
}
