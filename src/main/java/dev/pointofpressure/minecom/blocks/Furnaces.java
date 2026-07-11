package dev.pointofpressure.minecom.blocks;

import dev.pointofpressure.minecom.data.Recipes;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryProperty;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Furnaces (+ blast furnace + smoker): real smelting/blasting/smoking recipes and
 * fuel burn times, per-position state, lit block states, and progress-bar window
 * properties. Slots: 0 input, 1 fuel, 2 output. Blast furnace/smoker previously
 * didn't exist as functional blocks at all in this codebase — only the literal
 * "furnace" block key was ever recognized anywhere (interact routing, tick, hopper
 * push/pull, comparator signal). Real vanilla's blasting/smoking recipe JSON already
 * bakes in the correct halved cookingtime per recipe (confirmed via Recipes.java),
 * so getting them working is just recognizing the block types and picking the right
 * recipe map — no separate "2x speed" multiplier needed anywhere.
 */
public final class Furnaces {
    private Furnaces() {}

    /** "furnace" | "blast_furnace" | "smoker" — which recipe map/UI/block-state to use. */
    private static InventoryType invTypeFor(String kind) {
        return switch (kind) {
            case "blast_furnace" -> InventoryType.BLAST_FURNACE;
            case "smoker" -> InventoryType.SMOKER;
            default -> InventoryType.FURNACE;
        };
    }

    private static String titleFor(String kind) {
        return switch (kind) {
            case "blast_furnace" -> "Blast Furnace";
            case "smoker" -> "Smoker";
            default -> "Furnace";
        };
    }

    public static final class FurnaceInventory extends Inventory {
        FurnaceInventory(String kind) {
            super(invTypeFor(kind), Component.text(titleFor(kind)));
        }

        void property(InventoryProperty property, int value) {
            sendProperty(property, (short) value);
        }
    }

    /** Furnace state; fields public for persistence. */
    public static final class State {
        public final String kind;
        public final FurnaceInventory inv;
        public int burnTicks;
        public int burnTotal = 1;
        public int cookTicks;
        public float xpBank;
        public Instance instance;
        public Point pos;
        boolean lit;

        public State() { this("furnace"); }

        public State(String kind) {
            this.kind = kind;
            this.inv = new FurnaceInventory(kind);
        }

        private Recipes.Smelt recipeFor(Material input) {
            return switch (kind) {
                case "blast_furnace" -> Recipes.blast(input);
                case "smoker" -> Recipes.smoke(input);
                default -> Recipes.smelt(input);
            };
        }
    }

    /** Keyed by "x,y,z"; exposed for persistence. */
    public static final Map<String, State> FURNACES = new ConcurrentHashMap<>();

    static void register(GlobalEventHandler events) {
        MinecraftServer.getSchedulerManager().buildTask(Furnaces::tickAll)
                .repeat(TaskSchedule.tick(1))
                .schedule();
        // grant banked smelting XP when the output slot is clicked
        events.addListener(net.minestom.server.event.inventory.InventoryClickEvent.class, e -> {
            if (!(e.getInventory() instanceof FurnaceInventory inv)) return;
            if (e.getSlot() != 2) return;
            for (State s : FURNACES.values()) {
                if (s.inv == inv && s.xpBank > 0f) {
                    // vanilla: floor the banked xp, fractional part rounds up probabilistically
                    int grant = (int) s.xpBank;
                    if (Math.random() < s.xpBank - grant) grant++;
                    s.xpBank = 0f;
                    if (grant > 0) dev.pointofpressure.minecom.survival.Experience.add(e.getPlayer(), grant);
                    return;
                }
            }
        });
    }

    static void open(Player player, Instance instance, Point pos, Block block) {
        State state = FURNACES.computeIfAbsent(Containers.posKey(pos), k -> new State(block.key().value()));
        state.instance = instance;
        state.pos = pos;
        player.openInventory(state.inv);
    }

    static void remove(Instance instance, Point pos) {
        State state = FURNACES.remove(Containers.posKey(pos));
        if (state != null) Containers.spill(instance, pos, state.inv);
    }

    private static void tickAll() {
        for (State state : FURNACES.values()) {
            tick(state);
        }
    }

    private static void tick(State s) {
        ItemStack input = s.inv.getItemStack(0);
        ItemStack fuel = s.inv.getItemStack(1);
        ItemStack output = s.inv.getItemStack(2);
        Recipes.Smelt smelt = input.isAir() ? null : s.recipeFor(input.material());
        boolean outputFits = smelt != null && (output.isAir()
                || (output.material() == smelt.result().material()
                    && output.amount() + smelt.result().amount() <= output.material().maxStackSize()));

        if (s.burnTicks > 0) s.burnTicks--;

        if (s.burnTicks == 0 && smelt != null && outputFits && !fuel.isAir() && Recipes.isFuel(fuel.material())) {
            s.burnTotal = Recipes.fuelTicks(fuel.material());
            s.burnTicks = s.burnTotal;
            ItemStack remainder = fuel.material() == Material.LAVA_BUCKET
                    ? ItemStack.of(Material.BUCKET) : fuel.consume(1);
            s.inv.setItemStack(1, remainder);
        }

        if (s.burnTicks > 0 && smelt != null && outputFits) {
            if (++s.cookTicks >= smelt.cookTicks()) {
                s.cookTicks = 0;
                s.inv.setItemStack(0, input.consume(1));
                s.inv.setItemStack(2, output.isAir() ? smelt.result()
                        : output.withAmount(output.amount() + smelt.result().amount()));
                s.xpBank += smelt.xp();
            }
        } else {
            s.cookTicks = Math.max(0, s.cookTicks - 2);
        }

        boolean lit = s.burnTicks > 0;
        if (lit != s.lit && s.instance != null) {
            s.lit = lit;
            Block current = s.instance.getBlock(s.pos);
            if (current.key().value().equals(s.kind)) {
                s.instance.setBlock(s.pos, current.withProperty("lit", lit ? "true" : "false"));
            }
        }

        if (!s.inv.getViewers().isEmpty()) {
            s.inv.property(InventoryProperty.FURNACE_FIRE_ICON,
                    s.burnTotal > 0 ? s.burnTicks * 13 / Math.max(1, s.burnTotal) : 0);
            s.inv.property(InventoryProperty.FURNACE_MAXIMUM_FUEL_BURN_TIME, 13);
            s.inv.property(InventoryProperty.FURNACE_PROGRESS_ARROW,
                    smelt != null ? s.cookTicks * 24 / Math.max(1, smelt.cookTicks()) : 0);
            s.inv.property(InventoryProperty.FURNACE_MAXIMUM_PROGRESS, 24);
        }
    }
}
