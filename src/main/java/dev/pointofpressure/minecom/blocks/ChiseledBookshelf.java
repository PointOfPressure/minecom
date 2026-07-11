package dev.pointofpressure.minecom.blocks;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Point;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChiseledBookShelfBlock: 6 slots (2 rows x 3 columns), each tracked by its own
 * slot_N_occupied blockstate boolean. SelectableSlotContainer.getHitSlot: the clicked face
 * must equal the block's own FACING — clicking the back/sides/top/bottom never selects a
 * slot at all — then the relative hit position within that face maps to a row/column via
 * 16-pixel sections (row 0 = top, since Y is inverted; column = x-third). Right-click with a
 * bookshelf-tagged item (book/written_book/writable_book/enchanted_book/knowledge_book) on an
 * EMPTY slot inserts it; right-click empty-handed on an OCCUPIED slot removes it and gives it
 * to the player. Comparator output is real vanilla's genuinely odd mechanic here: NOT a count
 * of stored books, but lastInteractedSlot+1 — literally which slot was most recently touched.
 */
public final class ChiseledBookshelf {
    private ChiseledBookshelf() {}

    private static final class State {
        final ItemStack[] items = new ItemStack[6];
        int lastInteractedSlot = -1;
        State() { Arrays.fill(items, ItemStack.AIR); }
    }

    private static final Map<String, State> SHELVES = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, ChiseledBookshelf::insert);
        events.addListener(PlayerBlockInteractEvent.class, ChiseledBookshelf::remove);
    }

    private static boolean isBookshelfItem(Material m) {
        return m == Material.BOOK || m == Material.WRITTEN_BOOK || m == Material.WRITABLE_BOOK
                || m == Material.ENCHANTED_BOOK || m == Material.KNOWLEDGE_BOOK;
    }

    private static void insert(PlayerUseItemOnBlockEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("chiseled_bookshelf")) return;
        ItemStack held = e.getItemStack();
        if (held.isAir() || !isBookshelfItem(held.material())) return;

        int slot = hitSlot(block, e.getBlockFace(), e.getCursorPosition());
        if (slot < 0) return;
        State state = SHELVES.computeIfAbsent(Containers.posKey(pos), k -> new State());
        if (!state.items[slot].isAir()) return; // occupied: real vanilla's insert attempt is a no-op

        state.items[slot] = held.withAmount(1);
        state.lastInteractedSlot = slot;
        instance.setBlock(pos, withSlotState(block, state));
        e.getPlayer().setItemInHand(e.getHand(), held.consume(1));
        boolean enchanted = held.material() == Material.ENCHANTED_BOOK;
        instance.playSound(Sound.sound(enchanted ? SoundEvent.BLOCK_CHISELED_BOOKSHELF_INSERT_ENCHANTED
                        : SoundEvent.BLOCK_CHISELED_BOOKSHELF_INSERT, Sound.Source.BLOCK, 1f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }

    private static void remove(PlayerBlockInteractEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();
        Block block = e.getBlock();
        if (!block.key().value().equals("chiseled_bookshelf")) return;

        int slot = hitSlot(block, e.getBlockFace(), e.getCursorPosition());
        if (slot < 0) return;
        State state = SHELVES.get(Containers.posKey(pos));
        ItemStack item = state == null ? ItemStack.AIR : state.items[slot];
        if (item.isAir()) return; // empty slot: real vanilla just consumes the interaction, nothing to take

        e.setBlockingItemUse(true);
        state.items[slot] = ItemStack.AIR;
        state.lastInteractedSlot = slot;
        instance.setBlock(pos, withSlotState(block, state));
        Containers.giveOrDrop(e.getPlayer(), item);
        boolean enchanted = item.material() == Material.ENCHANTED_BOOK;
        instance.playSound(Sound.sound(enchanted ? SoundEvent.BLOCK_CHISELED_BOOKSHELF_PICKUP_ENCHANTED
                        : SoundEvent.BLOCK_CHISELED_BOOKSHELF_PICKUP, Sound.Source.BLOCK, 1f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }

    private static Block withSlotState(Block block, State state) {
        Block b = block;
        for (int i = 0; i < 6; i++) {
            b = b.withProperty("slot_" + i + "_occupied", String.valueOf(!state.items[i].isAir()));
        }
        return b;
    }

    /** SelectableSlotContainer.getHitSlot, ported directly: -1 if the hit face isn't FACING. */
    private static int hitSlot(Block block, BlockFace face, Point cursor) {
        String facing = block.getProperty("facing");
        if (facing == null || !faceMatches(facing, face)) return -1;

        double rx = cursor.x(), ry = cursor.y(), rz = cursor.z();
        double relX;
        switch (facing) {
            case "north" -> relX = 1.0 - rx;
            case "south" -> relX = rx;
            case "west" -> relX = rz;
            case "east" -> relX = 1.0 - rz;
            default -> { return -1; }
        }
        int row = section(1.0 - ry, 2);
        int column = section(relX, 3);
        return column + row * 3;
    }

    private static boolean faceMatches(String facing, BlockFace face) {
        return facing.toUpperCase(Locale.ROOT).equals(face.name());
    }

    private static int section(double relative, int maxSections) {
        double targetedPixel = relative * 16.0;
        double sectionSize = 16.0 / maxSections;
        int section = (int) Math.floor(targetedPixel / sectionSize);
        return Math.max(0, Math.min(section, maxSections - 1));
    }

    static void onBlockRemoved(Instance instance, Point pos) {
        State state = SHELVES.remove(Containers.posKey(pos));
        if (state == null) return;
        for (ItemStack item : state.items) {
            if (!item.isAir()) BlockRules.dropAt(instance, pos, item);
        }
    }

    /** ChiseledBookShelfBlock.getAnalogOutputSignal: NOT a book count — the last-touched slot+1. */
    public static int comparatorOutput(Point pos) {
        State state = SHELVES.get(Containers.posKey(pos));
        return state == null ? 0 : state.lastInteractedSlot + 1;
    }
}
