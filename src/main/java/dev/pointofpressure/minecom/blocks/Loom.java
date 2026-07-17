package dev.pointofpressure.minecom.blocks;

import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.color.DyeColor;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryButtonClickEvent;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryItemChangeEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.block.banner.BannerPattern;
import net.minestom.server.instance.block.banner.BannerPatternTags;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.component.BannerPatterns;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.registry.RegistryTag;
import net.minestom.server.registry.TagKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loom: ported from decompiled LoomMenu (26.2, cached under vanilla-src/) — the only way real
 * vanilla lets a player ORIGINATE a pattern layer onto a banner, previously entirely unmodeled
 * (Banners.java's own class doc named this exact gap). Slots: 0 banner, 1 dye, 2 pattern (a
 * stencil item like {@code creeper_banner_pattern} — optional; empty means "one of the base
 * patterns that need no item"), 3 result.
 *
 * <p>The selectable-pattern list comes straight from Minestom's own built-in banner-pattern
 * registry/tags ({@link MinecraftServer#getBannerPatternRegistry()}, {@link BannerPatternTags}) —
 * the same data the real client ships, so button indices need no server-side declaration the
 * way {@link Stonecutter}'s data-pack-defined recipes do (LoomMenu.clickMenuButton is a plain
 * index into whatever list {@code getSelectablePatterns} last computed).
 *
 * <p>Recompute ({@link #recompute}, LoomMenu.slotsChanged) fires whenever ANY of the 3 input
 * slots changes (real vanilla: all three share one {@code SimpleContainer}). Its trickiest bit,
 * ported faithfully: swapping the pattern item tries to CARRY the previous selection forward by
 * VALUE, not index — if the exact same {@code BannerPattern} the player had selected is still
 * among the new candidates (just possibly at a different list position), it stays selected;
 * otherwise the selection resets. A result exceeding the real 6-layer cap
 * (LoomMenu.slotsChanged's inline {@code >= 6}, distinct from the network cap
 * {@link BannerPatterns#MAX_LAYERS}) is refused everywhere a preview or button click could
 * otherwise build one — a small hardening beyond the exact decompile (which only checks it in
 * {@code slotsChanged}, not {@code clickMenuButton}) rather than risk a >6-layer result via
 * click ordering.
 *
 * <p>Taking the result consumes exactly 1 banner and 1 dye — NOT the pattern item
 * (LoomMenu's result-slot {@code onTake} only calls {@code bannerSlot.remove(1)}/
 * {@code dyeSlot.remove(1)}): pattern items are reusable stencils, dipped in a fresh dye each
 * time, not single-use.
 */
public final class Loom {
    private Loom() {}

    private static final int MAX_LAYERS = 6; // LoomMenu.slotsChanged's inline literal

    private record Session(List<RegistryKey<BannerPattern>> patterns, int selected) {
        static final Session EMPTY = new Session(List.of(), -1);
    }

    private static final Set<Inventory> OPEN = ConcurrentHashMap.newKeySet();
    private static final Map<Inventory, Session> SESSIONS = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().equals("loom")) return;
            e.setBlockingItemUse(true);
            Inventory inv = new Inventory(InventoryType.LOOM, Component.text("Loom"));
            OPEN.add(inv);
            SESSIONS.put(inv, Session.EMPTY);
            e.getPlayer().openInventory(inv);
        });
        events.addListener(InventoryItemChangeEvent.class, e -> {
            if (e.getSlot() < 0 || e.getSlot() > 2) return;
            if (e.getInventory() instanceof Inventory inv && OPEN.contains(inv)) recompute(inv);
        });
        events.addListener(InventoryButtonClickEvent.class, e -> {
            if (!(e.getInventory() instanceof Inventory inv) || !OPEN.contains(inv)) return;
            Session session = SESSIONS.getOrDefault(inv, Session.EMPTY);
            int buttonId = e.getButtonId();
            if (buttonId < 0 || buttonId >= session.patterns().size()) return;
            SESSIONS.put(inv, new Session(session.patterns(), buttonId));
            inv.setItemStack(3, buildResult(inv, session.patterns().get(buttonId)));
        });
        events.addListener(InventoryPreClickEvent.class, e -> {
            if (!(e.getInventory() instanceof Inventory inv) || !OPEN.contains(inv)) return;
            if (e.getSlot() != 3) return;
            e.setCancelled(true);
            ItemStack result = inv.getItemStack(3);
            if (result.isAir()) return;
            Player player = e.getPlayer();
            if (!inv.getCursorItem(player).isAir()) return;
            inv.setCursorItem(player, result);
            inv.setItemStack(0, inv.getItemStack(0).consume(1));
            inv.setItemStack(1, inv.getItemStack(1).consume(1));
            // the pattern slot (2) is deliberately untouched — see the class doc.
        });
        events.addListener(InventoryCloseEvent.class, e -> {
            if (e.getInventory() instanceof Inventory inv && OPEN.remove(inv)) {
                SESSIONS.remove(inv);
                for (int slot = 0; slot <= 2; slot++) {
                    ItemStack stack = inv.getItemStack(slot);
                    if (!stack.isAir()) Containers.giveOrDrop(e.getPlayer(), stack);
                }
            }
        });
    }

    /** LoomMenu.slotsChanged, decompile-verified. */
    private static void recompute(Inventory inv) {
        ItemStack bannerStack = inv.getItemStack(0);
        ItemStack dyeStack = inv.getItemStack(1);
        ItemStack patternStack = inv.getItemStack(2);
        if (bannerStack.isAir() || dyeStack.isAir()) {
            SESSIONS.put(inv, Session.EMPTY);
            inv.setItemStack(3, ItemStack.AIR);
            return;
        }
        Session prev = SESSIONS.getOrDefault(inv, Session.EMPTY);
        boolean prevValid = prev.selected() >= 0 && prev.selected() < prev.patterns().size();
        List<RegistryKey<BannerPattern>> patterns = selectablePatterns(patternStack);

        RegistryKey<BannerPattern> toDisplay;
        int selected;
        if (patterns.size() == 1) {
            selected = 0;
            toDisplay = patterns.get(0);
        } else if (!prevValid) {
            selected = -1;
            toDisplay = null;
        } else {
            RegistryKey<BannerPattern> prevValue = prev.patterns().get(prev.selected());
            int idx = patterns.indexOf(prevValue);
            if (idx != -1) {
                selected = idx;
                toDisplay = prevValue;
            } else {
                selected = -1;
                toDisplay = null;
            }
        }
        SESSIONS.put(inv, new Session(patterns, selected));
        inv.setItemStack(3, toDisplay == null ? ItemStack.AIR : buildResult(inv, toDisplay));
    }

    /** LoomMenu.getSelectablePatterns: empty pattern slot -> the base "no item required" set,
     * otherwise the item's own {@code PROVIDES_BANNER_PATTERNS} tag (real pattern items resolve
     * to exactly one entry; a direct tag could carry more, handled the same either way). */
    private static List<RegistryKey<BannerPattern>> selectablePatterns(ItemStack patternStack) {
        TagKey<BannerPattern> tagKey = patternStack.isAir()
                ? BannerPatternTags.NO_ITEM_REQUIRED
                : patternStack.get(DataComponents.PROVIDES_BANNER_PATTERNS);
        if (tagKey == null) return List.of();
        RegistryTag<BannerPattern> tag = MinecraftServer.getBannerPatternRegistry().getTag(tagKey);
        if (tag == null) return List.of();
        List<RegistryKey<BannerPattern>> list = new ArrayList<>();
        for (RegistryKey<BannerPattern> key : tag) list.add(key);
        return list;
    }

    /** LoomMenu.setupResultSlot: appends one layer (pattern, dye color) onto the banner's
     * existing layers, refusing past the real 6-layer cap (see the class doc's hardening note)
     * or if the dye slot somehow lacks a real dye color. */
    private static ItemStack buildResult(Inventory inv, RegistryKey<BannerPattern> pattern) {
        ItemStack bannerStack = inv.getItemStack(0);
        ItemStack dyeStack = inv.getItemStack(1);
        DyeColor color = dyeStack.get(DataComponents.DYE);
        if (color == null) return ItemStack.AIR;
        BannerPatterns existing = bannerStack.get(DataComponents.BANNER_PATTERNS);
        if (existing == null) existing = new BannerPatterns(List.of());
        if (existing.layers().size() >= MAX_LAYERS) return ItemStack.AIR;
        BannerPatterns updated = existing.with(new BannerPatterns.Layer(pattern, color));
        return bannerStack.withAmount(1).with(DataComponents.BANNER_PATTERNS, updated);
    }
}
