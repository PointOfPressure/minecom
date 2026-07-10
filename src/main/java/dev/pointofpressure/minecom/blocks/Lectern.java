package dev.pointofpressure.minecom.blocks;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryButtonClickEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryProperty;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.WritableBookContent;
import net.minestom.server.item.component.WrittenBookContent;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lectern: real page-count-driven comparator output (LecternBlockEntity.getRedstoneSignal),
 * a book placed via right-click (LECTERN_BOOKS tag: written_book/writable_book), and a real
 * paged menu (LecternMenu's button IDs — 1=prev page, 2=next page, 3=take book, 100+=jump to
 * page — Minestom exposes the same button-click packet via InventoryButtonClickEvent). Page
 * changes pulse POWERED for 2 ticks (LecternBlock.signalPageChange), matching the real
 * redstone-pulse-on-page-turn mechanic.
 */
public final class Lectern {
    private Lectern() {}

    private static final class LecternInventory extends Inventory {
        LecternInventory() {
            super(InventoryType.LECTERN, Component.text("Lectern"));
        }

        void property(InventoryProperty property, int value) {
            sendProperty(property, (short) value);
        }
    }

    private static final class State {
        LecternInventory inv;
        ItemStack book = ItemStack.AIR;
        int page;
        int pageCount;
        Instance instance;
        Point pos;
    }

    private static final Map<String, State> LECTERNS = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerUseItemOnBlockEvent.class, Lectern::useOnBlock);
        events.addListener(PlayerBlockInteractEvent.class, Lectern::interact);
        events.addListener(InventoryButtonClickEvent.class, Lectern::onButton);
    }

    private static void useOnBlock(PlayerUseItemOnBlockEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("lectern")) return;
        if ("true".equals(block.getProperty("has_book"))) return;

        ItemStack held = e.getItemStack();
        if (held.isAir()) return;
        Material m = held.material();
        if (m != Material.WRITABLE_BOOK && m != Material.WRITTEN_BOOK) return;

        State state = LECTERNS.computeIfAbsent(Containers.posKey(pos), k -> new State());
        state.instance = instance;
        state.pos = pos;
        state.book = held.withAmount(1);
        state.page = 0;
        state.pageCount = pageCount(state.book);
        instance.setBlock(pos, block.withProperty("has_book", "true"));
        instance.playSound(Sound.sound(SoundEvent.ITEM_BOOK_PUT, Sound.Source.BLOCK, 1f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        e.getPlayer().setItemInHand(e.getHand(), held.consume(1));
    }

    private static int pageCount(ItemStack book) {
        WritableBookContent writable = book.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (writable != null) return writable.pages().size();
        WrittenBookContent written = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return written != null ? written.pages().size() : 0;
    }

    private static void interact(PlayerBlockInteractEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("lectern")) return;
        if (!"true".equals(block.getProperty("has_book"))) return;

        e.setBlockingItemUse(true);
        State state = LECTERNS.get(Containers.posKey(pos));
        if (state == null) return;
        LecternInventory inv = new LecternInventory();
        inv.setItemStack(0, state.book);
        state.inv = inv;
        e.getPlayer().openInventory(inv);
        inv.property(InventoryProperty.LECTERN_PAGE_NUMBER, state.page);
    }

    private static void onButton(InventoryButtonClickEvent e) {
        if (!(e.getInventory() instanceof LecternInventory inv)) return;
        State state = findByInventory(inv);
        if (state == null) return;
        int button = e.getButtonId();
        if (button >= 100) {
            setPage(state, button - 100);
        } else {
            switch (button) {
                case 1 -> setPage(state, state.page - 1);
                case 2 -> setPage(state, state.page + 1);
                case 3 -> takeBook(e.getPlayer(), state);
                default -> { /* unknown button: no-op, matches real vanilla's default case */ }
            }
        }
    }

    private static void setPage(State state, int page) {
        int clamped = Math.max(0, Math.min(page, state.pageCount - 1));
        if (clamped == state.page) return;
        state.page = clamped;
        if (state.inv != null) state.inv.property(InventoryProperty.LECTERN_PAGE_NUMBER, state.page);

        Block block = state.instance.getBlock(state.pos);
        if (!block.key().value().equals("lectern")) return;
        // LecternBlock.signalPageChange: pulse POWERED for 2 ticks on every page change
        state.instance.setBlock(state.pos, block.withProperty("powered", "true"));
        dev.pointofpressure.minecom.redstone.Redstone.neighborsChanged(state.pos);
        state.instance.scheduler().buildTask(() -> {
            Block now = state.instance.getBlock(state.pos);
            if (now.key().value().equals("lectern") && "true".equals(now.getProperty("powered"))) {
                state.instance.setBlock(state.pos, now.withProperty("powered", "false"));
                dev.pointofpressure.minecom.redstone.Redstone.neighborsChanged(state.pos);
            }
        }).delay(TaskSchedule.tick(2)).schedule();
    }

    private static void takeBook(Player player, State state) {
        if (state.book.isAir()) return;
        ItemStack book = state.book;
        state.book = ItemStack.AIR;
        state.page = 0;
        state.pageCount = 0;
        LECTERNS.remove(Containers.posKey(state.pos));

        Block block = state.instance.getBlock(state.pos);
        if (block.key().value().equals("lectern")) {
            state.instance.setBlock(state.pos, block.withProperty("has_book", "false").withProperty("powered", "false"));
        }
        if (!player.getInventory().addItemStack(book)) {
            ItemEntity drop = new ItemEntity(book);
            drop.setInstance(player.getInstance(), player.getPosition());
            drop.setPickupDelay(java.time.Duration.ofMillis(200));
        }
        player.closeInventory();
    }

    private static State findByInventory(LecternInventory inv) {
        for (State s : LECTERNS.values()) if (s.inv == inv) return s;
        return null;
    }

    static void onBlockRemoved(Instance instance, Point pos) {
        State state = LECTERNS.remove(Containers.posKey(pos));
        if (state != null && !state.book.isAir()) {
            BlockRules.dropAt(instance, pos, state.book);
        }
    }

    /** LecternBlockEntity.getRedstoneSignal: page/(pageCount-1) * 14, +1 while a book is present. */
    public static int comparatorOutput(Point pos, Block block) {
        if (!"true".equals(block.getProperty("has_book"))) return 0;
        State s = LECTERNS.get(Containers.posKey(pos));
        if (s == null) return 0;
        float pageProgress = s.pageCount > 1 ? (float) s.page / (s.pageCount - 1f) : 1f;
        return (int) Math.floor(pageProgress * 14f) + 1;
    }
}
