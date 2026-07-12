package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.StateAdapter;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Point;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * DecoratedPotBlock: a single-stack item store, filled only by right-clicking with a matching
 * (or empty-slot) item — real vanilla has NO extraction via interaction at all, only breaking
 * the pot (which shatters it, dropping both the contents and its own sherds) empties it; an
 * empty-hand right-click always plays the "insert fail" wobble regardless of pot contents.
 * Comparator output uses the exact same fill-fraction formula as chests/hoppers elsewhere in
 * this project. Bounded: real vanilla pots can be crafted with 1-4 custom pottery sherds that
 * change their drops and appearance (PotDecorations) — this project has no sherd-decoration
 * tracking, so every pot is treated as the plain/default kind, which is real and accurate for
 * the common case (crafted from 4 plain bricks, the bundled decorated_pot_simple recipe
 * already indexes correctly) — it drops 4 real brick items on break, matching what an
 * undecorated pot's sherds actually are in vanilla, not an approximation for that case.
 */
public final class DecoratedPot {
    private DecoratedPot() {}

    private static final Map<String, ItemStack> POT_ITEMS = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) {
        Persist.register(persistence());
        events.addListener(PlayerUseItemOnBlockEvent.class, DecoratedPot::insert);
        events.addListener(PlayerBlockInteractEvent.class, DecoratedPot::failInsert);
    }

    /** Decorated pot persistence (docs/PERSISTENCE.md): the single held item stack. */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "decorated_pot";
            }

            @Override
            public void collect(Instance in, BiConsumer<Point, JsonObject> out) {
                POT_ITEMS.forEach((key, item) -> {
                    if (item == null || item.isAir()) return;
                    JsonObject o = new JsonObject();
                    o.add("item", Persist.writeItem(item));
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                ItemStack item = Persist.readItem(data.getAsJsonObject("item"));
                if (item != null) POT_ITEMS.put(Persist.posKey(pos), item);
            }

            @Override
            public void wipe() {
                POT_ITEMS.clear();
            }
        };
    }

    private static void insert(PlayerUseItemOnBlockEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getPosition();
        if (!instance.getBlock(pos).key().value().equals("decorated_pot")) return;

        ItemStack held = e.getItemStack();
        if (held.isAir()) return;
        String key = Containers.posKey(pos);
        ItemStack current = POT_ITEMS.get(key);
        ItemStack updated;
        if (current == null || current.isAir()) {
            updated = held.withAmount(1);
        } else if (current.material() == held.material() && current.amount() < current.material().maxStackSize()) {
            updated = current.withAmount(current.amount() + 1);
        } else {
            return; // full or mismatched: real vanilla falls through to TRY_WITH_EMPTY_HAND (no-op here)
        }

        POT_ITEMS.put(key, updated);
        e.getPlayer().setItemInHand(e.getHand(), held.consume(1));
        float pitchBend = (float) updated.amount() / updated.material().maxStackSize();
        instance.playSound(Sound.sound(SoundEvent.BLOCK_DECORATED_POT_INSERT, Sound.Source.BLOCK,
                        1f, 0.7f + 0.5f * pitchBend),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }

    private static void failInsert(PlayerBlockInteractEvent e) {
        Instance instance = e.getInstance();
        Point pos = e.getBlockPosition();
        if (!instance.getBlock(pos).key().value().equals("decorated_pot")) return;

        e.setBlockingItemUse(true);
        instance.playSound(Sound.sound(SoundEvent.BLOCK_DECORATED_POT_INSERT_FAIL, Sound.Source.BLOCK, 1f, 1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }

    static void onBlockRemoved(Instance instance, Point pos) {
        ItemStack item = POT_ITEMS.remove(Containers.posKey(pos));
        if (item != null && !item.isAir()) BlockRules.dropAt(instance, pos, item);
        BlockRules.dropAt(instance, pos, ItemStack.of(Material.BRICK, 4));
    }

    /** AbstractContainerMenu.getRedstoneSignalFromBlockEntity: the same fill-fraction formula as chests. */
    public static int comparatorOutput(Point pos) {
        ItemStack item = POT_ITEMS.get(Containers.posKey(pos));
        if (item == null || item.isAir()) return 0;
        return (int) Math.floor(1 + (float) item.amount() / item.material().maxStackSize() * 14f);
    }
}
