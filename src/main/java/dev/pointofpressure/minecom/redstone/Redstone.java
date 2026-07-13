package dev.pointofpressure.minecom.redstone;

import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.StateAdapter;
import dev.pointofpressure.minecom.blocks.Containers;
import dev.pointofpressure.minecom.blocks.Explosions;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityProjectile;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Vanilla redstone: wire networks with 15-block signal decay, strong vs weak
 * power, torches (1rt inversion + burnout), repeaters (1-4rt delay + locking),
 * comparators (compare/subtract + container/item-frame reading), lamps, doors,
 * buttons, levers, pressure plates (weighted plates emit analog counts), TNT,
 * dispensers/droppers (behavior table: projectiles, spawn eggs, minecarts,
 * bone meal, flint&amp;steel, buckets, boats, shulker placement), pistons with
 * full slime/honey chains, targets, tripwire, trapped chests, copper bulbs,
 * lightning rods, crafters ({@link Crafters}) and sculk sensors
 * ({@link Vibrations}) — including quasi-connectivity: pistons/dispensers/
 * droppers also accept power at the block above them and only re-check on a
 * block update (BUD behavior emerges).
 */
public final class Redstone {
    private Redstone() {}

    private static final int[][] HORIZ = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final Vec[] ALL = {new Vec(1, 0, 0), new Vec(-1, 0, 0), new Vec(0, 1, 0),
            new Vec(0, -1, 0), new Vec(0, 0, 1), new Vec(0, 0, -1)};

    private static Instance instance;
    private static volatile long tickCount;
    private static final Set<Long> dirty = ConcurrentHashMap.newKeySet();
    /** Depth-2 updates: QC components (pistons/dispensers/droppers) ignore these — BUD semantics. */
    private static final Set<Long> dirtySoft = ConcurrentHashMap.newKeySet();
    private static final Map<Long, List<Runnable>> scheduled = new ConcurrentHashMap<>();
    private static final Set<Long> platePositions = ConcurrentHashMap.newKeySet();
    private static final Set<Long> detectorPositions = ConcurrentHashMap.newKeySet();
    private static final Set<Long> daylightDetectors = ConcurrentHashMap.newKeySet();
    private static final Set<Long> tripwireHooks = ConcurrentHashMap.newKeySet();
    private static final Set<Long> lightningRods = ConcurrentHashMap.newKeySet();
    private static final int TRIPWIRE_MAX_LENGTH = 41; // TripWireHookBlock: i in 1..41
    private static final Map<Long, java.util.ArrayDeque<Long>> torchFlips = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ lifecycle

    public static void start(Instance overworld) {
        instance = overworld;
        MinecraftServer.getSchedulerManager().buildTask(Redstone::tick)
                .repeat(TaskSchedule.tick(1)).schedule();
    }

    public static void register(GlobalEventHandler events) {
        Persist.register(positionsPersistence());
        Persist.register(dispenserPersistence());
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (e.getBlock().key().value().endsWith("_pressure_plate")) {
                platePositions.add(pack(e.getBlockPosition()));
            }
            if (e.getBlock().key().value().equals("detector_rail")) {
                detectorPositions.add(pack(e.getBlockPosition()));
            }
            if (e.getBlock().key().value().equals("tripwire_hook")) {
                tripwireHooks.add(pack(e.getBlockPosition()));
            }
            if (e.getBlock().key().value().equals("daylight_detector")) {
                daylightDetectors.add(pack(e.getBlockPosition()));
            }
            if (e.getBlock().key().value().equals("lightning_rod")) {
                lightningRods.add(pack(e.getBlockPosition()));
            }
            neighborsChanged(e.getBlockPosition());
        });
        events.addListener(PlayerBlockBreakEvent.class, e -> {
            // TripWireBlock.playerWillDestroy: shears disarm a wire in place instead of breaking it
            if (e.getBlock().key().value().equals("tripwire")
                    && e.getPlayer().getItemInMainHand().material() == Material.SHEARS) {
                e.setCancelled(true);
                instance.setBlock(e.getBlockPosition(), e.getBlock().withProperty("disarmed", "true"));
                neighborsChanged(e.getBlockPosition());
                return;
            }
            neighborsChanged(e.getBlockPosition());
        });
        events.addListener(net.minestom.server.event.player.PlayerUseItemOnBlockEvent.class, e -> {
            if (e.getItemStack().material() != Material.FLINT_AND_STEEL) return;
            Point clicked = e.getPosition();
            if (instance.getBlock(clicked).key().value().equals("tnt")) {
                instance.setBlock(clicked, Block.AIR);
                Explosions.primeTnt(instance, clicked, 80, e.getPlayer());
                e.getPlayer().setItemInHand(e.getHand(),
                        dev.pointofpressure.minecom.data.Items.damageItem(e.getPlayer(), e.getItemStack(), 1));
            } else {
                // FlintAndSteelItem.useOn: a valid obsidian frame around the clicked face takes
                // priority (PortalShape.createPortalBlocks) — only falls through to lighting the
                // bare air space, same as the dispenser path above, feeding FireSpread's own
                // scheduled-tick propagation, when no portal shape is found there.
                var dir = e.getBlockFace().toDirection();
                Point firePos = clicked.add(dir.normalX(), dir.normalY(), dir.normalZ());
                if (dev.pointofpressure.minecom.blocks.Portals.tryLight(instance, firePos, "x")
                        || dev.pointofpressure.minecom.blocks.Portals.tryLight(instance, firePos, "z")) {
                    e.getPlayer().setItemInHand(e.getHand(),
                            dev.pointofpressure.minecom.data.Items.damageItem(e.getPlayer(), e.getItemStack(), 1));
                } else if (instance.getBlock(firePos).isAir()) {
                    instance.setBlock(firePos, Block.FIRE);
                    dev.pointofpressure.minecom.blocks.FireSpread.track(firePos);
                    e.getPlayer().setItemInHand(e.getHand(),
                            dev.pointofpressure.minecom.data.Items.damageItem(e.getPlayer(), e.getItemStack(), 1));
                }
            }
        });
        events.addListener(PlayerBlockInteractEvent.class, Redstone::interact);
    }

    /** Mark a changed position, its neighbors, and their neighbors (vanilla update depth). */
    public static void neighborsChanged(Point pos) {
        if (instance == null) return;
        dirty.add(pack(pos));
        for (Vec d : ALL) {
            Point np = pos.add(d);
            dirty.add(pack(np));
            for (Vec d2 : ALL) dirtySoft.add(pack(np.add(d2)));
            // observers facing the changed position pulse for 2 ticks
            if (!instance.isChunkLoaded(np.blockX() >> 4, np.blockZ() >> 4)) continue;
            Block n = instance.getBlock(np);
            if (n.key().value().equals("observer")
                    && sameDir(facingVec(n.getProperty("facing")), opp(d))
                    && !"true".equals(n.getProperty("powered"))) {
                pulseObserver(np, n);
            }
        }
    }

    private static void pulseObserver(Point pos, Block observer) {
        schedule(2, () -> {
            Block now = instance.getBlock(pos);
            if (!now.key().value().equals("observer")) return;
            instance.setBlock(pos, now.withProperty("powered", "true"));
            Point out = pos.add(facingVec(now.getProperty("facing")).mul(-1));
            neighborsChanged(out);
            dirty.add(pack(out));
            schedule(2, () -> {
                Block later = instance.getBlock(pos);
                if (!later.key().value().equals("observer")) return;
                instance.setBlock(pos, later.withProperty("powered", "false"));
                neighborsChanged(out);
            });
        });
    }

    private static void tick() {
        if (instance == null) return;
        tickCount++;
        // drain everything due now or overdue (cross-thread schedules can key slightly in the past)
        var dueKeys = scheduled.keySet().stream().filter(t -> t <= tickCount).toList();
        for (long key : dueKeys) {
            List<Runnable> due = scheduled.remove(key);
            if (due != null) due.forEach(Runnable::run);
        }

        if (!dirty.isEmpty() || !dirtySoft.isEmpty()) {
            List<Long> batch = new ArrayList<>(dirty);
            dirty.clear();
            List<Long> softBatch = new ArrayList<>(dirtySoft);
            dirtySoft.clear();
            Set<Long> wiresDone = new HashSet<>();
            for (long key : batch) {
                evaluate(unpackVec(key), wiresDone, false);
            }
            for (long key : softBatch) {
                evaluate(unpackVec(key), wiresDone, true);
            }
        }

        if (tickCount % 5 == 0) { tickPlates(); tickDetectorRails(); tickTripwires(); Vibrations.tickSteps(); }
        if (tickCount % 20 == 0) tickDaylightDetectors();
    }

    static void schedule(int delayTicks, Runnable action) {
        scheduled.computeIfAbsent(tickCount + Math.max(1, delayTicks), t -> new ArrayList<>()).add(action);
    }

    // ------------------------------------------------------------------ power model

    private static boolean isSolid(Block b) {
        return b.isSolid() && !b.registry().isBlockEntity();
    }

    /** Power a source block emits toward `dir` (direction from source to target). */
    private static int emitted(Block source, Point sourcePos, Vec toTarget) {
        String key = source.key().value();
        switch (key) {
            case "redstone_block" -> { return 15; }
            case "redstone_torch" -> {
                if (!"true".equals(source.getProperty("lit"))) return 0;
                return toTarget.y() < 0 ? 0 : 15; // never powers its attachment (below)
            }
            case "redstone_wall_torch" -> {
                if (!"true".equals(source.getProperty("lit"))) return 0;
                Vec attach = facingVec(source.getProperty("facing")).mul(-1);
                return sameDir(toTarget, attach) ? 0 : 15;
            }
            case "lever" -> { return "true".equals(source.getProperty("powered")) ? 15 : 0; }
            case "repeater" -> {
                if (!"true".equals(source.getProperty("powered"))) return 0;
                return sameDir(opp(facingVec(source.getProperty("facing"))), toTarget) ? 15 : 0;
            }
            case "target" -> {
                // TargetBlock.getSignal: the accuracy-based "power" value, all directions
                return Integer.parseInt(source.getProperty("power"));
            }
            case "daylight_detector" -> {
                // DaylightDetectorBlock.getSignal: the POWER state, all directions
                return Integer.parseInt(source.getProperty("power"));
            }
            case "comparator" -> {
                if (!"true".equals(source.getProperty("powered"))) return 0;
                return sameDir(opp(facingVec(source.getProperty("facing"))), toTarget)
                        ? comparatorOutput(source, sourcePos) : 0;
            }
            case "observer" -> {
                if (!"true".equals(source.getProperty("powered"))) return 0;
                return sameDir(opp(facingVec(source.getProperty("facing"))), toTarget) ? 15 : 0;
            }
            case "jukebox" -> {
                // JukeboxBlock.isSignalSource/getSignal: 15 in every direction while a song plays
                return dev.pointofpressure.minecom.blocks.Jukebox.isPlaying(sourcePos) ? 15 : 0;
            }
            case "lectern" -> {
                // LecternBlock.getSignal: 15 in every direction during the 2-tick page-turn pulse
                return "true".equals(source.getProperty("powered")) ? 15 : 0;
            }
            case "tripwire_hook" -> {
                // TripWireHookBlock.getDirectSignal: only out the back, opposite its FACING
                if (!"true".equals(source.getProperty("powered"))) return 0;
                return sameDir(opp(facingVec(source.getProperty("facing"))), toTarget) ? 15 : 0;
            }
            case "lightning_rod" -> {
                // LightningRodBlock.getSignal: 15 in every direction for 8gt after a strike
                return "true".equals(source.getProperty("powered")) ? 15 : 0;
            }
            case "sculk_sensor" -> {
                return Integer.parseInt(source.getProperty("power"));
            }
            case "calibrated_sculk_sensor" -> {
                // CalibratedSculkSensorBlock.getSignal: silent toward its FACING side
                if (sameDir(facingVec(source.getProperty("facing")), toTarget)) return 0;
                return Integer.parseInt(source.getProperty("power"));
            }
            case "trapped_chest" -> {
                // TrappedChestBlock.isSignalSource/getSignal: unlike a plain chest (not a
                // signal source at all), a trapped chest powers redstone in every direction
                // equal to its current player-viewer count, clamped 0-15 — no comparator needed.
                Inventory inv = Containers.CHESTS.get(Containers.posKey(sourcePos));
                return inv == null ? 0 : Math.min(15, inv.getViewers().size());
            }
            case "redstone_wire" -> {
                int power = Integer.parseInt(source.getProperty("power"));
                if (power == 0) return 0;
                if (toTarget.y() < 0) return power;          // powers the block below it
                if (toTarget.y() > 0) return 0;
                return wirePointsToward(source, sourcePos, toTarget) ? power : 0;
            }
            default -> {
                if (key.endsWith("_button")) return "true".equals(source.getProperty("powered")) ? 15 : 0;
                // weighted plates carry an analog POWER value, not the boolean POWERED
                if (key.endsWith("weighted_pressure_plate")) return Integer.parseInt(source.getProperty("power"));
                if (key.endsWith("_pressure_plate")) return "true".equals(source.getProperty("powered")) ? 15 : 0;
                if (key.equals("detector_rail")) return "true".equals(source.getProperty("powered")) ? 15 : 0;
                return 0;
            }
        }
    }

    /** Strong power into a solid block (what wires can read through it). */
    private static int strongPowerOf(Point pos) {
        int max = 0;
        for (Vec d : ALL) {
            Point np = pos.add(d);
            Block n = instance.getBlock(np);
            String key = n.key().value();
            Vec toMe = d.mul(-1);
            switch (key) {
                case "repeater" -> {
                    if ("true".equals(n.getProperty("powered"))
                            && sameDir(opp(facingVec(n.getProperty("facing"))), toMe)) max = 15;
                }
                case "comparator" -> {
                    if ("true".equals(n.getProperty("powered"))
                            && sameDir(opp(facingVec(n.getProperty("facing"))), toMe)) {
                        max = Math.max(max, comparatorOutput(n, np));
                    }
                }
                case "redstone_torch" -> {
                    if (d.y() < 0 && "true".equals(n.getProperty("lit"))) max = 15; // torch below
                }
                case "lever" -> {
                    if ("true".equals(n.getProperty("powered")) && attachedTo(n, toMe)) max = 15;
                }
                case "lightning_rod" -> {
                    // getDirectSignal: strongly powers only its attachment block (opposite FACING)
                    if ("true".equals(n.getProperty("powered"))
                            && sameDir(facingVec(n.getProperty("facing")).mul(-1), toMe)) max = 15;
                }
                default -> {
                    if (key.endsWith("_button") && "true".equals(n.getProperty("powered"))
                            && attachedTo(n, toMe)) max = 15;
                    if (key.endsWith("weighted_pressure_plate") && d.y() > 0) {
                        max = Math.max(max, Integer.parseInt(n.getProperty("power")));
                    } else if (key.endsWith("_pressure_plate") && "true".equals(n.getProperty("powered"))
                            && d.y() > 0) max = 15; // plate on top strongly powers the block underneath
                    if (key.equals("detector_rail") && "true".equals(n.getProperty("powered"))
                            && d.y() > 0) max = 15; // detector rail on top strongly powers the block below
                }
            }
        }
        return max;
    }

    private static boolean attachedTo(Block attachable, Vec toAttachment) {
        String face = attachable.getProperty("face");
        if ("floor".equals(face)) return toAttachment.y() < 0;
        if ("ceiling".equals(face)) return toAttachment.y() > 0;
        return sameDir(opp(facingVec(attachable.getProperty("facing"))), toAttachment);
    }

    /** Weak activation for components: direct sources plus powered solid neighbors. */
    static boolean activated(Point pos) {
        for (Vec d : ALL) {
            Point np = pos.add(d);
            if (!instance.isChunkLoaded(np.blockX() >> 4, np.blockZ() >> 4)) continue;
            Block n = instance.getBlock(np);
            if (emitted(n, np, d.mul(-1)) > 0) return true;
            if (isSolid(n) && blockPowered(np)) return true;
        }
        return false;
    }

    /** A solid block is "powered" if anything emits into it (weak or strong). */
    private static boolean blockPowered(Point pos) {
        for (Vec d : ALL) {
            Point np = pos.add(d);
            if (!instance.isChunkLoaded(np.blockX() >> 4, np.blockZ() >> 4)) continue;
            Block n = instance.getBlock(np);
            if (emitted(n, np, d.mul(-1)) > 0) return true;
        }
        return false;
    }

    /** Signal a wire cell picks up from non-wire neighbors. */
    private static int wireInput(Point pos) {
        int max = 0;
        for (Vec d : ALL) {
            Point np = pos.add(d);
            Block n = instance.getBlock(np);
            if (n.key().value().equals("redstone_wire")) continue;
            max = Math.max(max, emitted(n, np, d.mul(-1)));
            if (isSolid(n)) max = Math.max(max, strongPowerOf(np));
        }
        return max;
    }

    // ------------------------------------------------------------------ wire networks

    private static boolean wirePointsToward(Block wire, Point pos, Vec dir) {
        List<Vec> connected = new ArrayList<>(4);
        for (int[] h : HORIZ) {
            Vec d = new Vec(h[0], 0, h[1]);
            String s = wire.getProperty(dirName(d));
            if (s != null && !s.equals("none")) connected.add(d);
        }
        if (connected.isEmpty()) return true;                       // dot: powers all four sides
        for (Vec c : connected) if (sameDir(c, dir)) return true;   // explicit connection
        // single connection renders as a line through both ends (vanilla 1.16+)
        return connected.size() == 1 && sameDir(connected.get(0), opp(dir));
    }

    private static void recomputeWireNetwork(Point start, Set<Long> wiresDone) {
        if (wiresDone.contains(pack(start))) return;
        // collect connected wires
        Set<Long> network = new LinkedHashSet<>();
        ArrayDeque<Point> queue = new ArrayDeque<>();
        queue.add(start);
        network.add(pack(start));
        while (!queue.isEmpty() && network.size() < 4096) {
            Point at = queue.poll();
            for (Point next : wireNeighbors(at)) {
                long key = pack(next);
                if (network.add(key)) queue.add(next);
            }
        }
        wiresDone.addAll(network);

        // multi-source relaxation
        Map<Long, Integer> power = new HashMap<>();
        ArrayDeque<Long> relax = new ArrayDeque<>();
        for (long key : network) {
            int input = wireInput(unpackVec(key));
            power.put(key, input);
            if (input > 0) relax.add(key);
        }
        while (!relax.isEmpty()) {
            long key = relax.poll();
            int p = power.get(key);
            if (p <= 1) continue;
            for (Point next : wireNeighbors(unpackVec(key))) {
                long nk = pack(next);
                Integer cur = power.get(nk);
                if (cur != null && cur < p - 1) {
                    power.put(nk, p - 1);
                    relax.add(nk);
                }
            }
        }

        // write back states + wake neighbors whose input may have changed
        for (long key : network) {
            Point at = unpackVec(key);
            Block existing = instance.getBlock(at);
            if (!existing.key().value().equals("redstone_wire")) continue;
            int p = power.get(key);
            Block updated = wireShape(at).withProperty("power", String.valueOf(p));
            if (!updated.properties().equals(existing.properties())) {
                instance.setBlock(at, updated);
                // vanilla: wire state changes update neighbors AND their neighbors
                for (Vec d : ALL) {
                    Point n = at.add(d);
                    dirty.add(pack(n));
                    for (Vec d2 : ALL) dirty.add(pack(n.add(d2)));
                }
            }
        }
    }

    private static List<Point> wireNeighbors(Point at) {
        List<Point> result = new ArrayList<>(4);
        boolean solidAbove = isSolid(instance.getBlock(at.add(0, 1, 0)));
        for (int[] h : HORIZ) {
            Point side = at.add(h[0], 0, h[1]);
            Block sideBlock = instance.getBlock(side);
            if (sideBlock.key().value().equals("redstone_wire")) {
                result.add(side);
            } else if (isSolid(sideBlock)) {
                if (!solidAbove && instance.getBlock(side.add(0, 1, 0)).key().value().equals("redstone_wire")) {
                    result.add(side.add(0, 1, 0)); // wire climbing a block
                }
            } else {
                if (instance.getBlock(side.add(0, -1, 0)).key().value().equals("redstone_wire")) {
                    result.add(side.add(0, -1, 0)); // wire dropping down
                }
            }
        }
        return result;
    }

    /** Visual connection properties toward wires and consuming components. */
    private static Block wireShape(Point at) {
        Block wire = Block.REDSTONE_WIRE;
        boolean solidAbove = isSolid(instance.getBlock(at.add(0, 1, 0)));
        for (int[] h : HORIZ) {
            Vec d = new Vec(h[0], 0, h[1]);
            Point side = at.add(d);
            Block sideBlock = instance.getBlock(side);
            String value = "none";
            String key = sideBlock.key().value();
            if (key.equals("redstone_wire") || connectsToWire(sideBlock, d)) {
                value = "side";
            } else if (isSolid(sideBlock)) {
                if (!solidAbove && instance.getBlock(side.add(0, 1, 0)).key().value().equals("redstone_wire")) {
                    value = "up";
                }
            } else if (instance.getBlock(side.add(0, -1, 0)).key().value().equals("redstone_wire")) {
                value = "side";
            }
            wire = wire.withProperty(dirName(d), value);
        }
        return wire;
    }

    private static boolean connectsToWire(Block block, Vec dir) {
        String key = block.key().value();
        if (key.equals("redstone_torch") || key.equals("redstone_wall_torch")
                || key.equals("lever") || key.equals("redstone_block")
                || key.endsWith("_button") || key.endsWith("_pressure_plate")
                || key.equals("target") || key.equals("daylight_detector")
                || key.equals("lightning_rod") || key.equals("tripwire_hook")
                || key.endsWith("sculk_sensor")) return true;
        if (key.equals("repeater") || key.equals("comparator")) {
            Vec facing = facingVec(block.getProperty("facing"));
            return sameDir(facing, dir) || sameDir(opp(facing), dir);
        }
        return false;
    }

    // ------------------------------------------------------------------ component evaluation

    private static void evaluate(Point pos, Set<Long> wiresDone, boolean soft) {
        // neighborsChanged queues a position's neighbors unconditionally (its own chunk-loaded
        // check only gates the observer-pulse look-ahead, not the dirty/dirtySoft add) — a
        // position from another instance entirely (Explosions.explode and friends don't check
        // which instance they're running in before calling this) or genuinely out past the
        // loaded radius would otherwise NPE inside instance.getBlock and PERMANENTLY kill this
        // repeating tick task, taking every redstone feature down for the rest of the server's
        // life. Real vanilla simply doesn't simulate unloaded chunks; this is the same thing.
        if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) return;
        Block block = instance.getBlock(pos);
        String key = block.key().value();
        // quasi-connectivity: QC components only react to direct neighbor updates
        if (soft && (key.equals("piston") || key.equals("sticky_piston")
                || key.equals("dispenser") || key.equals("dropper"))) return;
        switch (key) {
            case "redstone_wire" -> recomputeWireNetwork(pos, wiresDone);
            case "redstone_torch", "redstone_wall_torch" -> {
                Point attachment = key.equals("redstone_torch")
                        ? pos.add(0, -1, 0)
                        : pos.add(facingVec(block.getProperty("facing")).mul(-1));
                boolean shouldBeLit = !blockPowered(attachment);
                if (shouldBeLit != "true".equals(block.getProperty("lit"))) {
                    schedule(2, () -> {
                        Block now = instance.getBlock(pos);
                        if (!now.key().value().equals(key)) return;
                        boolean stillShould = !blockPowered(key.equals("redstone_torch")
                                ? pos.add(0, -1, 0)
                                : pos.add(facingVec(now.getProperty("facing")).mul(-1)));
                        if (stillShould != "true".equals(now.getProperty("lit"))) {
                            // vanilla burnout: 8 flips within 60 ticks locks the torch off
                            var flips = torchFlips.computeIfAbsent(pack(pos), k -> new java.util.ArrayDeque<>());
                            flips.addLast(tickCount);
                            while (!flips.isEmpty() && flips.peekFirst() < tickCount - 60) flips.removeFirst();
                            if (flips.size() >= 8 && stillShould) return; // burned out: refuses to relight
                            instance.setBlock(pos, now.withProperty("lit", String.valueOf(stillShould)));
                            neighborsChanged(pos);
                            dirty.add(pack(pos.add(0, 1, 0))); // block above gains/loses strong power
                            for (Vec d : ALL) dirty.add(pack(pos.add(0, 1, 0).add(d)));
                        }
                    });
                }
            }
            case "repeater" -> {
                Vec facing = facingVec(block.getProperty("facing"));
                // locking: a powered repeater/comparator facing into either side locks the state
                boolean locked = sideLocks(pos, facing);
                Block withLock = block.withProperty("locked", String.valueOf(locked));
                if (!withLock.properties().equals(block.properties())) instance.setBlock(pos, withLock);
                if (locked) return;
                boolean input = inputPower(pos, facing) > 0;
                boolean powered = "true".equals(block.getProperty("powered"));
                if (input != powered) {
                    int delay = Integer.parseInt(block.getProperty("delay")) * 2;
                    schedule(delay, () -> {
                        Block now = instance.getBlock(pos);
                        if (!now.key().value().equals("repeater")) return;
                        if ("true".equals(now.getProperty("locked"))) return;
                        boolean in = inputPower(pos, facingVec(now.getProperty("facing"))) > 0;
                        if (in != "true".equals(now.getProperty("powered"))) {
                            instance.setBlock(pos, now.withProperty("powered", String.valueOf(in)));
                            neighborsChanged(pos);
                            Point out = pos.add(facingVec(now.getProperty("facing")).mul(-1));
                            neighborsChanged(out);
                        }
                    });
                }
            }
            case "comparator" -> {
                boolean shouldPower = comparatorOutput(block, pos) > 0;
                if (shouldPower != "true".equals(block.getProperty("powered"))) {
                    schedule(2, () -> {
                        Block now = instance.getBlock(pos);
                        if (!now.key().value().equals("comparator")) return;
                        boolean out = comparatorOutput(now, pos) > 0;
                        if (out != "true".equals(now.getProperty("powered"))) {
                            instance.setBlock(pos, now.withProperty("powered", String.valueOf(out)));
                            neighborsChanged(pos);
                            neighborsChanged(pos.add(facingVec(now.getProperty("facing")).mul(-1)));
                        }
                    });
                }
            }
            case "redstone_lamp" -> {
                boolean on = activated(pos);
                boolean lit = "true".equals(block.getProperty("lit"));
                if (on && !lit) {
                    instance.setBlock(pos, block.withProperty("lit", "true"));
                } else if (!on && lit) {
                    schedule(4, () -> {
                        Block now = instance.getBlock(pos);
                        if (now.key().value().equals("redstone_lamp") && !activated(pos)) {
                            instance.setBlock(pos, now.withProperty("lit", "false"));
                        }
                    });
                }
            }
            case "note_block" -> {
                // NoteBlock.neighborChanged: no scheduling delay, unlike lamp/repeater/comparator
                boolean signal = activated(pos);
                if (signal != "true".equals(block.getProperty("powered"))) {
                    if (signal) {
                        dev.pointofpressure.minecom.blocks.NoteBlocks.playNote(instance, pos, block);
                        Vibrations.emit("note_block_play", pos, null);
                    }
                    instance.setBlock(pos, block.withProperty("powered", String.valueOf(signal)));
                }
            }
            case "bell" -> {
                // BellBlock.neighborChanged: rings only on the rising edge of an incoming signal.
                boolean signal = activated(pos);
                if (signal != "true".equals(block.getProperty("powered"))) {
                    if (signal) dev.pointofpressure.minecom.blocks.Bells.ring(instance, pos);
                    instance.setBlock(pos, block.withProperty("powered", String.valueOf(signal)));
                }
            }
            case "piston", "sticky_piston" -> Pistons.evaluate(instance, pos, block);
            case "crafter" -> Crafters.evaluate(instance, pos, block);
            case "dispenser", "dropper" -> {
                boolean powered = activated(pos) || activated(pos.add(0, 1, 0)); // quasi-connectivity
                boolean triggered = "true".equals(block.getProperty("triggered"));
                if (powered && !triggered) {
                    instance.setBlock(pos, block.withProperty("triggered", "true"));
                    dispense(pos, block);
                } else if (!powered && triggered) {
                    instance.setBlock(pos, block.withProperty("triggered", "false"));
                }
            }
            case "hopper" -> {
                boolean enabled = !activated(pos);
                if (enabled != "true".equals(block.getProperty("enabled"))) {
                    instance.setBlock(pos, block.withProperty("enabled", String.valueOf(enabled)));
                }
            }
            case "tnt" -> {
                if (activated(pos)) {
                    instance.setBlock(pos, Block.AIR);
                    Explosions.primeTnt(instance, pos, 80, null);
                    neighborsChanged(pos);
                }
            }
            default -> {
                if (key.equals("powered_rail") || key.equals("activator_rail")) {
                    boolean powered = railPowered(pos, block, key);
                    if (powered != "true".equals(block.getProperty("powered"))) {
                        instance.setBlock(pos, block.withProperty("powered", String.valueOf(powered)));
                        neighborsChanged(pos);
                    }
                    return;
                }
                if (key.endsWith("copper_bulb")) {
                    // CopperBulbBlock.checkAndFlip: LIT toggles only on the RISING edge of
                    // POWERED; falling edge just records the new powered state. No delay.
                    boolean signal = activated(pos);
                    boolean powered = "true".equals(block.getProperty("powered"));
                    if (signal != powered) {
                        Block updated = block;
                        if (!powered) {
                            updated = updated.withProperty("lit",
                                    String.valueOf(!"true".equals(block.getProperty("lit"))));
                        }
                        instance.setBlock(pos, updated.withProperty("powered", String.valueOf(signal)));
                        dirty.add(pack(pos)); // comparators reading LIT re-evaluate
                    }
                    return;
                }
                if (key.endsWith("_door") || key.endsWith("_trapdoor") || key.endsWith("_fence_gate")) {
                    boolean powered = activated(pos)
                            || (key.endsWith("_door") && doorOtherHalfPowered(pos, block));
                    boolean wasPowered = "true".equals(block.getProperty("powered"));
                    if (powered != wasPowered) {
                        Block updated = block.withProperty("powered", String.valueOf(powered))
                                .withProperty("open", String.valueOf(powered));
                        instance.setBlock(pos, updated);
                        if (key.endsWith("_door")) syncDoorHalf(pos, updated);
                        Vibrations.emit(powered ? "block_open" : "block_close", pos, null);
                    }
                }
            }
        }
    }

    /**
     * PoweredRailBlock.findPoweredRailSignal: a powered/activator rail is on if
     * directly powered or within 8 same-type rails (along its axis, allowing the
     * one-block rises of ascending shapes) of a directly powered one.
     */
    private static boolean railPowered(Point pos, Block rail, String key) {
        if (activated(pos)) return true;
        String shape = rail.getProperty("shape");
        boolean zAxis = shape == null || shape.contains("north") || shape.contains("south");
        Vec dir = zAxis ? new Vec(0, 0, 1) : new Vec(1, 0, 0);
        for (int sign : new int[]{1, -1}) {
            Point at = pos;
            for (int i = 0; i < 8; i++) {
                Point step = at.add(dir.x() * sign, 0, dir.z() * sign);
                Point foundAt = null;
                for (int dy : new int[]{0, 1, -1}) {
                    Point p = step.add(0, dy, 0);
                    if (instance.getBlock(p).key().value().equals(key)) {
                        foundAt = p;
                        break;
                    }
                }
                if (foundAt == null) break;
                if (activated(foundAt)) return true;
                at = foundAt;
            }
        }
        return false;
    }

    private static boolean doorOtherHalfPowered(Point pos, Block door) {
        boolean upper = "upper".equals(door.getProperty("half"));
        return activated(pos.add(0, upper ? -1 : 1, 0));
    }

    private static void syncDoorHalf(Point pos, Block updated) {
        boolean upper = "upper".equals(updated.getProperty("half"));
        Point other = pos.add(0, upper ? -1 : 1, 0);
        Block otherBlock = instance.getBlock(other);
        if (otherBlock.key().value().equals(updated.key().value())) {
            instance.setBlock(other, otherBlock
                    .withProperty("open", updated.getProperty("open"))
                    .withProperty("powered", updated.getProperty("powered")));
        }
    }

    /** Power into a repeater/comparator's input face (the side it faces). */
    static int inputPower(Point pos, Vec facing) {
        Point inputPos = pos.add(facing);
        Block input = instance.getBlock(inputPos);
        int direct = emitted(input, inputPos, facing.mul(-1));
        int viaBlock = isSolid(input) && blockPowered(inputPos) ? 15 : 0;
        int strong = isSolid(input) ? strongPowerOf(inputPos) : 0;
        return Math.max(direct, Math.max(viaBlock, strong));
    }

    private static boolean sideLocks(Point pos, Vec facing) {
        for (int[] h : HORIZ) {
            Vec d = new Vec(h[0], 0, h[1]);
            if (sameDir(d, facing) || sameDir(opp(d), facing)) continue;
            Point np = pos.add(d);
            Block n = instance.getBlock(np);
            String key = n.key().value();
            if ((key.equals("repeater") || key.equals("comparator"))
                    && "true".equals(n.getProperty("powered"))
                    && sameDir(opp(facingVec(n.getProperty("facing"))), opp(d))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ comparator

    private static int comparatorOutput(Block comparator, Point pos) {
        Vec facing = facingVec(comparator.getProperty("facing"));
        Point rearPos = pos.add(facing);
        Block rear = instance.getBlock(rearPos);
        int rearSignal = containerSignal(rearPos, rear);
        if (rearSignal < 0) {
            Integer frameSignal = itemFrameSignal(rearPos);
            rearSignal = frameSignal != null ? frameSignal : Math.max(emitted(rear, rearPos, facing.mul(-1)),
                    isSolid(rear) && blockPowered(rearPos) ? 15 : 0);
        }

        int side = 0;
        for (int[] h : HORIZ) {
            Vec d = new Vec(h[0], 0, h[1]);
            if (sameDir(d, facing) || sameDir(opp(d), facing)) continue;
            Point np = pos.add(d);
            Block n = instance.getBlock(np);
            String key = n.key().value();
            if (key.equals("redstone_wire") || key.equals("repeater") || key.equals("comparator")
                    || key.equals("redstone_block")) {
                side = Math.max(side, emitted(n, np, d.mul(-1)));
            }
        }
        boolean subtract = "subtract".equals(comparator.getProperty("mode"));
        return subtract ? Math.max(0, rearSignal - side) : (rearSignal >= side ? rearSignal : 0);
    }

    /**
     * ItemFrame.getAnalogOutput (decompile-verified): a comparator can read an item frame
     * entity sitting in its input cell the same way it reads a container — 0 empty, otherwise
     * rotation%8+1 — even though the frame isn't a block at all. Returns null if no item frame
     * occupies that exact cell, so the caller can fall through to normal block-based reading.
     */
    private static Integer itemFrameSignal(Point pos) {
        for (Entity e : instance.getEntities()) {
            EntityType t = e.getEntityType();
            if (t != EntityType.ITEM_FRAME && t != EntityType.GLOW_ITEM_FRAME) continue;
            Point p = e.getPosition();
            if (p.blockX() != pos.blockX() || p.blockY() != pos.blockY() || p.blockZ() != pos.blockZ()) continue;
            var meta = (net.minestom.server.entity.metadata.other.ItemFrameMeta) e.getEntityMeta();
            return meta.getItem().isAir() ? 0 : meta.getRotation().ordinal() % 8 + 1;
        }
        return null;
    }

    /** Vanilla container fullness signal, or -1 if the block isn't a tracked container. */
    private static int containerSignal(Point pos, Block block) {
        String key = block.key().value();
        Inventory inv = null;
        if (key.equals("chest") || key.equals("barrel") || key.equals("trapped_chest")) {
            inv = Containers.CHESTS.get(Containers.posKey(pos));
        } else if (key.endsWith("shulker_box")) {
            inv = dev.pointofpressure.minecom.blocks.ShulkerBoxes.inventoryAt(pos);
        }
        else if (key.equals("furnace") || key.equals("blast_furnace") || key.equals("smoker")) {
            var state = dev.pointofpressure.minecom.blocks.Furnaces.FURNACES.get(Containers.posKey(pos));
            if (state != null) inv = state.inv;
        } else if (key.equals("dispenser") || key.equals("dropper")) {
            inv = DISPENSERS.get(Containers.posKey(pos));
        } else if (key.equals("hopper")) {
            inv = Hoppers.HOPPERS.get(Containers.posKey(pos));
        } else if (key.equals("brewing_stand")) {
            // BrewingStandBlock.getAnalogOutputSignal: delegates straight to
            // AbstractContainerMenu.getRedstoneSignalFromBlockEntity — the exact same
            // generic slot-fill-fraction formula as chest, not something brew-progress-based.
            var stand = dev.pointofpressure.minecom.blocks.Brewing.STANDS.get(Containers.posKey(pos));
            if (stand != null) inv = stand.inv;
        } else if (key.equals("composter")) {
            return Integer.parseInt(block.getProperty("level"));
        } else if (key.equals("water_cauldron") || key.equals("powder_snow_cauldron")) {
            // LayeredCauldronBlock.getAnalogOutputSignal: the raw 1-3 level, unscaled.
            return Integer.parseInt(block.getProperty("level"));
        } else if (key.equals("lava_cauldron")) {
            return 3; // LavaCauldronBlock.getAnalogOutputSignal: always "full"
        } else if (key.equals("jukebox")) {
            return dev.pointofpressure.minecom.blocks.Jukebox.comparatorOutput(pos, block);
        } else if (key.equals("lectern")) {
            return dev.pointofpressure.minecom.blocks.Lectern.comparatorOutput(pos, block);
        } else if (key.equals("respawn_anchor")) {
            // RespawnAnchorBlock.getScaledChargeLevel(state, 15): floor(charges/4 * 15)
            int charges = Integer.parseInt(block.getProperty("charges"));
            return (int) Math.floor(charges / 4.0 * 15);
        } else if (key.equals("cake")) {
            return dev.pointofpressure.minecom.blocks.Cake.comparatorOutput(block);
        } else if (key.equals("decorated_pot")) {
            return dev.pointofpressure.minecom.blocks.DecoratedPot.comparatorOutput(pos);
        } else if (key.equals("chiseled_bookshelf")) {
            return dev.pointofpressure.minecom.blocks.ChiseledBookshelf.comparatorOutput(pos);
        } else if (key.equals("end_portal_frame")) {
            // EndPortalFrameBlock.getAnalogOutputSignal: HAS_EYE ? 15 : 0, nothing fancier.
            return "true".equals(block.getProperty("eye")) ? 15 : 0;
        } else if (key.endsWith("copper_bulb")) {
            // CopperBulbBlock.getAnalogOutputSignal: LIT ? 15 : 0
            return "true".equals(block.getProperty("lit")) ? 15 : 0;
        } else if (key.equals("crafter")) {
            return Crafters.comparatorOutput(pos);
        } else if (key.endsWith("sculk_sensor")) {
            return Vibrations.comparatorOutput(pos, block);
        } else if (key.equals("creaking_heart")) {
            // CreakingHeartBlockEntity.computeAnalogOutputSignal: distance-scaled
            return dev.pointofpressure.minecom.blocks.CreakingHearts.comparatorSignal(pos);
        } else {
            return -1;
        }
        if (inv == null) return 0;
        double fill = 0;
        int occupied = 0;
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack s = inv.getItemStack(slot);
            if (s.isAir()) continue;
            occupied++;
            fill += (double) s.amount() / s.material().maxStackSize();
        }
        if (occupied == 0) return 0;
        return (int) Math.floor(1 + (fill / inv.getSize()) * 14);
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Tracked-position persistence (docs/PERSISTENCE.md): the five position
     * registries the tick sweeps run over (plates, detector rails, daylight
     * detectors, tripwire hooks, lightning rods), typed by "t". Dispenser
     * inventories ride the second adapter below.
     */
    private static StateAdapter positionsPersistence() {
        return new StateAdapter() {
            private final Map<String, Set<Long>> sets = Map.of(
                    "plate", platePositions, "detector_rail", detectorPositions,
                    "daylight", daylightDetectors, "tripwire_hook", tripwireHooks,
                    "rod", lightningRods);

            @Override
            public String kind() {
                return "redstone_pos";
            }

            @Override
            public void collect(Instance in, BiConsumer<Point, JsonObject> out) {
                sets.forEach((type, set) -> {
                    for (long packed : set) {
                        JsonObject o = new JsonObject();
                        o.addProperty("t", type);
                        out.accept(unpackVec(packed), o);
                    }
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                Set<Long> set = sets.get(data.get("t").getAsString());
                if (set != null) set.add(pack(pos));
            }

            @Override
            public void wipe() {
                sets.values().forEach(Set::clear);
            }
        };
    }

    /** Dispenser/dropper inventory persistence (docs/PERSISTENCE.md). */
    private static StateAdapter dispenserPersistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "dispenser";
            }

            @Override
            public void collect(Instance in, BiConsumer<Point, JsonObject> out) {
                DISPENSERS.forEach((key, inv) -> {
                    JsonObject o = new JsonObject();
                    o.add("items", Persist.writeItems(inv));
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                Persist.readItems(data.getAsJsonArray("items"), dispenserInventory(pos));
            }

            @Override
            public void wipe() {
                DISPENSERS.clear();
            }
        };
    }

    // ------------------------------------------------------------------ dispensers

    public static final Map<String, Inventory> DISPENSERS = new ConcurrentHashMap<>();

    public static Inventory dispenserInventory(Point pos) {
        String key = Containers.posKey(pos);
        boolean isNew = !DISPENSERS.containsKey(key);
        Inventory inv = DISPENSERS.computeIfAbsent(key,
                k -> new Inventory(net.minestom.server.inventory.InventoryType.WINDOW_3X3,
                        net.kyori.adventure.text.Component.text("Dispenser")));
        // structure-placed loot dispensers (jungle temple trap) roll on first access, same as chests
        if (isNew) Containers.rollPendingLoot(key, inv);
        return inv;
    }

    private static void dispense(Point pos, Block block) {
        Inventory inv = DISPENSERS.get(Containers.posKey(pos));
        if (inv == null) return;
        List<Integer> filled = new ArrayList<>();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (!inv.getItemStack(slot).isAir()) filled.add(slot);
        }
        if (filled.isEmpty()) return;
        int slot = filled.get((int) (Math.random() * filled.size()));
        ItemStack stack = inv.getItemStack(slot);
        Material mat = stack.material();
        Vec facing = facingVec(block.getProperty("facing")).mul(-1); // output direction
        Point front = pos.add(facing);
        Vec spawnAt = new Vec(front.blockX() + 0.5, front.blockY() + 0.5, front.blockZ() + 0.5);
        boolean dropper = block.key().value().equals("dropper");

        if (!dropper && (mat == Material.ARROW || mat == Material.TIPPED_ARROW || mat == Material.SPECTRAL_ARROW
                || mat == Material.SNOWBALL || mat == Material.EGG || mat == Material.WIND_CHARGE)) {
            // DispenserBlock.registerProjectileBehavior family: shot, not dropped
            EntityType type = switch (mat.key().value()) {
                case "snowball" -> EntityType.SNOWBALL;
                case "egg" -> EntityType.EGG;
                case "wind_charge" -> EntityType.BREEZE_WIND_CHARGE; // reuses the breeze burst-on-hit
                case "spectral_arrow" -> EntityType.SPECTRAL_ARROW;
                default -> EntityType.ARROW;
            };
            EntityProjectile projectile = new EntityProjectile(null, type);
            projectile.setInstance(instance, spawnAt);
            projectile.setVelocity(facing.mul(20).add(0, 1, 0));
        } else if (!dropper && (mat == Material.SPLASH_POTION || mat == Material.LINGERING_POTION)) {
            // ProjectileDispenseBehavior for potions: power 1.375 shoot units
            dev.pointofpressure.minecom.survival.ThrownPotions.launch(
                    instance, null, spawnAt, facing.mul(1.375f * (32f / 3.0f)).add(0, 1, 0), stack);
        } else if (!dropper && mat == Material.EXPERIENCE_BOTTLE) {
            EntityProjectile bottle = new EntityProjectile(null, EntityType.EXPERIENCE_BOTTLE);
            bottle.setInstance(instance, spawnAt);
            bottle.setVelocity(facing.mul(10).add(0, 1, 0));
        } else if (!dropper && mat == Material.GLASS_BOTTLE) {
            // fills into a water bottle from a water source in front (block kept)
            if (!instance.getBlock(front).key().value().equals("water")) return;
            inv.setItemStack(slot, ItemStack.of(Material.POTION).with(b ->
                    b.set(net.minestom.server.component.DataComponents.POTION_CONTENTS,
                            new net.minestom.server.item.component.PotionContents(
                                    net.minestom.server.potion.PotionType.WATER))));
            return;
        } else if (!dropper && mat == Material.SHEARS) {
            // ShearsDispenseItemBehavior: shear the first shearable in front (no durability, AUDIT)
            for (var entity : instance.getNearbyEntities(spawnAt, 1.0)) {
                if (dev.pointofpressure.minecom.mobs.Shearing.shear(entity)) return;
            }
            return; // nothing shearable: fizzle without consuming
        } else if (!dropper && mat == Material.FIREWORK_ROCKET) {
            // cosmetic flight only — no explosion/boost model (AUDIT)
            var rocket = new net.minestom.server.entity.Entity(EntityType.FIREWORK_ROCKET);
            rocket.setInstance(instance, spawnAt);
            rocket.setVelocity(facing.mul(10).add(0, 4, 0));
            rocket.scheduler().buildTask(rocket::remove)
                    .delay(net.minestom.server.timer.TaskSchedule.tick(30)).schedule();
        } else if (!dropper && (mat.key().value().endsWith("_helmet")
                || mat.key().value().endsWith("_chestplate")
                || mat.key().value().endsWith("_leggings")
                || mat.key().value().endsWith("_boots"))) {
            // EquipmentDispenseItemBehavior: equip a living entity in front with an empty slot
            var eqSlot = mat.key().value().endsWith("_helmet")
                    ? net.minestom.server.entity.EquipmentSlot.HELMET
                    : mat.key().value().endsWith("_chestplate")
                    ? net.minestom.server.entity.EquipmentSlot.CHESTPLATE
                    : mat.key().value().endsWith("_leggings")
                    ? net.minestom.server.entity.EquipmentSlot.LEGGINGS
                    : net.minestom.server.entity.EquipmentSlot.BOOTS;
            for (var entity : instance.getNearbyEntities(spawnAt, 1.0)) {
                if (entity instanceof LivingEntity le && !le.isDead()
                        && le.getEquipment(eqSlot).isAir()) {
                    le.setEquipment(eqSlot, stack.withAmount(1));
                    inv.setItemStack(slot, stack.consume(1));
                    Vibrations.emit("equip", le.getPosition(), le);
                    return;
                }
            }
            return; // no wearer: fizzle without consuming
        } else if (!dropper && mat == Material.FIRE_CHARGE) {
            // fire charge shoots a small fireball that ignites what it hits
            EntityProjectile fireball = new EntityProjectile(null, EntityType.SMALL_FIREBALL);
            fireball.setInstance(instance, spawnAt);
            fireball.setVelocity(facing.mul(20));
        } else if (!dropper && mat.key().value().endsWith("_spawn_egg")) {
            // SpawnEggItemBehavior: spawn the mob directly in front
            String kind = mat.key().value().replace("_spawn_egg", "");
            try {
                dev.pointofpressure.minecom.mobs.Mobs.spawn(kind, instance,
                        new net.minestom.server.coordinate.Pos(spawnAt.x(), front.blockY(), spawnAt.z()));
            } catch (Exception unknownKind) {
                return; // unknown mob: fizzle without consuming, like a failed optional behavior
            }
        } else if (!dropper && (mat == Material.MINECART || mat == Material.CHEST_MINECART
                || mat == Material.HOPPER_MINECART || mat == Material.FURNACE_MINECART
                || mat == Material.TNT_MINECART)
                && instance.getBlock(front).key().value().endsWith("rail")) {
            // MinecartDispenseItemBehavior: place carts only onto rails
            var cartPos = new net.minestom.server.coordinate.Pos(spawnAt.x(), front.blockY() + 0.1, spawnAt.z());
            if (mat == Material.MINECART) {
                dev.pointofpressure.minecom.blocks.Minecarts.spawn(instance, cartPos);
            } else {
                EntityType cartType = switch (mat.key().value()) {
                    case "chest_minecart" -> EntityType.CHEST_MINECART;
                    case "hopper_minecart" -> EntityType.HOPPER_MINECART;
                    case "furnace_minecart" -> EntityType.FURNACE_MINECART;
                    default -> EntityType.TNT_MINECART;
                };
                dev.pointofpressure.minecom.blocks.Minecarts.spawn(instance, cartPos, cartType);
            }
        } else if (!dropper && mat == Material.BONE_MEAL) {
            // OptionalDispenseItemBehavior: failure fizzles without consuming
            if (!dev.pointofpressure.minecom.blocks.Farming.boneMeal(instance, front)) return;
        } else if (!dropper && mat == Material.FLINT_AND_STEEL) {
            Block target = instance.getBlock(front);
            String tk = target.key().value();
            if (target.isAir() && dev.pointofpressure.minecom.blocks.Portals.tryLight(instance, front, "x")) {
                // portal shape found and lit — no fire block to place
            } else if (target.isAir() && dev.pointofpressure.minecom.blocks.Portals.tryLight(instance, front, "z")) {
                // portal shape found and lit — no fire block to place
            } else if (target.isAir()) {
                instance.setBlock(front, Block.FIRE);
                dev.pointofpressure.minecom.blocks.FireSpread.track(front);
                neighborsChanged(front);
            } else if ((tk.endsWith("campfire") || tk.endsWith("candle") || tk.endsWith("candle_cake"))
                    && "false".equals(target.getProperty("lit"))) {
                instance.setBlock(front, target.withProperty("lit", "true"));
            } else if (tk.equals("tnt")) {
                instance.setBlock(front, Block.AIR);
                Explosions.primeTnt(instance, front, 80, null);
                neighborsChanged(front);
            }
            return; // tool: never consumed (durability not modeled for container items, see AUDIT)
        } else if (!dropper && mat == Material.POWDER_SNOW_BUCKET) {
            if (!instance.getBlock(front).isAir()) return;
            instance.setBlock(front, Block.POWDER_SNOW);
            inv.setItemStack(slot, ItemStack.of(Material.BUCKET));
            return;
        } else if (!dropper && mat.key().value().endsWith("shulker_box")
                && instance.getBlock(front).isAir()) {
            // ShulkerBoxDispenseBehavior: place the box as a block
            instance.setBlock(front, Block.fromKey(mat.key()));
        } else if (!dropper && mat == Material.WATER_BUCKET) {
            instance.setBlock(front, Block.WATER);
            dev.pointofpressure.minecom.blocks.Fluids.notifyAround(front);
            inv.setItemStack(slot, ItemStack.of(Material.BUCKET));
            return;
        } else if (!dropper && mat == Material.LAVA_BUCKET) {
            instance.setBlock(front, Block.LAVA);
            dev.pointofpressure.minecom.blocks.Fluids.notifyAround(front);
            inv.setItemStack(slot, ItemStack.of(Material.BUCKET));
            return;
        } else if (!dropper && mat == Material.TNT) {
            Explosions.primeTnt(instance, front, 80, null);
        } else if (!dropper && mat == Material.BUCKET
                && instance.getBlock(front).isLiquid()
                && (instance.getBlock(front).getProperty("level") == null
                        || "0".equals(instance.getBlock(front).getProperty("level")))) {
            // DispenserBehavior for the empty bucket: scoop a SOURCE fluid block in
            // front into a filled bucket (real vanilla also refuses a flowing/
            // non-source block; a freshly-placed block with no explicit "level"
            // property is itself a source, matching Fluids.java's own level() helper
            // and its player-driven bucket-fill logic exactly).
            boolean water = instance.getBlock(front).key().value().equals("water");
            instance.setBlock(front, Block.AIR);
            dev.pointofpressure.minecom.blocks.Fluids.notifyAround(front);
            inv.setItemStack(slot, ItemStack.of(water ? Material.WATER_BUCKET : Material.LAVA_BUCKET));
            return;
        } else if (!dropper && dev.pointofpressure.minecom.blocks.Boats.BOATS.containsKey(mat)
                && instance.getBlock(front).key().value().equals("water")) {
            // DispenserBehavior for boat items: place a boat directly on adjacent water.
            dev.pointofpressure.minecom.blocks.Boats.spawn(instance,
                    dev.pointofpressure.minecom.blocks.Boats.BOATS.get(mat),
                    new net.minestom.server.coordinate.Pos(spawnAt.x(), front.blockY() + 0.1, spawnAt.z()));
        } else {
            var item = new net.minestom.server.entity.ItemEntity(stack.withAmount(1));
            item.setPickupDelay(java.time.Duration.ofMillis(500));
            item.setInstance(instance, spawnAt);
            item.setVelocity(facing.mul(4).add(0, 1, 0));
        }
        inv.setItemStack(slot, stack.consume(1));
    }

    // ------------------------------------------------------------------ interactions

    private static void interact(PlayerBlockInteractEvent e) {
        Block block = e.getBlock();
        String key = block.key().value();
        Point pos = e.getBlockPosition();
        switch (key) {
            case "lever" -> {
                e.setBlockingItemUse(true);
                boolean on = !"true".equals(block.getProperty("powered"));
                instance.setBlock(pos, block.withProperty("powered", String.valueOf(on)));
                wakeAroundAttachable(pos, block);
            }
            case "repeater" -> {
                e.setBlockingItemUse(true);
                int delay = Integer.parseInt(block.getProperty("delay")) % 4 + 1;
                instance.setBlock(pos, block.withProperty("delay", String.valueOf(delay)));
            }
            case "comparator" -> {
                e.setBlockingItemUse(true);
                boolean subtract = "subtract".equals(block.getProperty("mode"));
                instance.setBlock(pos, block.withProperty("mode", subtract ? "compare" : "subtract"));
                dirty.add(pack(pos));
            }
            case "dispenser", "dropper" -> {
                e.setBlockingItemUse(true);
                e.getPlayer().openInventory(dispenserInventory(pos));
                Vibrations.emit("container_open", pos, e.getPlayer());
            }
            case "crafter" -> {
                e.setBlockingItemUse(true);
                e.getPlayer().openInventory(Crafters.inventory(pos));
                Vibrations.emit("container_open", pos, e.getPlayer());
            }
            case "daylight_detector" -> {
                // DaylightDetectorBlock.useWithoutItem: cycle INVERTED + immediate re-read
                e.setBlockingItemUse(true);
                boolean inverted = !"true".equals(block.getProperty("inverted"));
                instance.setBlock(pos, block.withProperty("inverted", String.valueOf(inverted)));
                daylightDetectors.add(pack(pos));
                DaylightDetectors.recompute(instance, pos);
            }
            default -> {
                if (key.endsWith("_button")) {
                    e.setBlockingItemUse(true);
                    if ("true".equals(block.getProperty("powered"))) return;
                    instance.setBlock(pos, block.withProperty("powered", "true"));
                    wakeAroundAttachable(pos, block);
                    int duration = key.startsWith("stone") || key.contains("polished") ? 20 : 30;
                    schedule(duration, () -> {
                        Block now = instance.getBlock(pos);
                        if (now.key().value().endsWith("_button")) {
                            instance.setBlock(pos, now.withProperty("powered", "false"));
                            wakeAroundAttachable(pos, now);
                        }
                    });
                }
            }
        }
    }

    /** Wake the component's neighbors plus the attachment block's neighbors (strong power path). */
    private static void wakeAroundAttachable(Point pos, Block block) {
        neighborsChanged(pos);
        Vec toAttachment;
        String face = block.getProperty("face");
        if ("floor".equals(face)) toAttachment = new Vec(0, -1, 0);
        else if ("ceiling".equals(face)) toAttachment = new Vec(0, 1, 0);
        else toAttachment = facingVec(block.getProperty("facing")).mul(-1);
        neighborsChanged(pos.add(toAttachment));
    }

    // ------------------------------------------------------------------ pressure plates

    private static void tickPlates() {
        for (long key : platePositions) {
            Point pos = unpackVec(key);
            if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
            Block block = instance.getBlock(pos);
            String bk = block.key().value();
            if (!bk.endsWith("_pressure_plate")) {
                platePositions.remove(key);
                continue;
            }
            if (bk.endsWith("weighted_pressure_plate")) {
                // WeightedPressurePlateBlock.getSignalStrength: count ALL entities on the
                // plate, signal = ceil(min(count,maxWeight)/maxWeight * 15). Gold ("light")
                // maxWeight 15, iron ("heavy") 150 — from the block registry definitions.
                int maxWeight = bk.startsWith("light") ? 15 : 150;
                long count = instance.getEntities().stream().filter(entity -> {
                    if (entity.isRemoved()) return false;
                    Point ep = entity.getPosition();
                    return ep.blockX() == pos.blockX() && ep.blockZ() == pos.blockZ()
                            && Math.abs(ep.y() - pos.blockY()) < 0.6;
                }).count();
                int signal = count == 0 ? 0
                        : (int) Math.ceil(Math.min(count, maxWeight) / (double) maxWeight * 15.0);
                if (signal != Integer.parseInt(block.getProperty("power"))) {
                    instance.setBlock(pos, block.withProperty("power", String.valueOf(signal)));
                    neighborsChanged(pos);
                    neighborsChanged(pos.add(0, -1, 0));
                }
                continue;
            }
            boolean wooden = !bk.startsWith("stone") && !bk.startsWith("polished");
            boolean pressed = instance.getEntities().stream().anyMatch(entity -> {
                if (!(entity instanceof LivingEntity) && !wooden) return false;
                if (entity instanceof Entity en && en.isRemoved()) return false;
                Point ep = entity.getPosition();
                return ep.blockX() == pos.blockX() && ep.blockZ() == pos.blockZ()
                        && Math.abs(ep.y() - pos.blockY()) < 0.6;
            });
            if (pressed != "true".equals(block.getProperty("powered"))) {
                instance.setBlock(pos, block.withProperty("powered", String.valueOf(pressed)));
                neighborsChanged(pos);
                neighborsChanged(pos.add(0, -1, 0));
            }
        }
    }

    /** Track plates loaded from a saved world when someone steps near them. */
    public static void trackPlate(Point pos) {
        platePositions.add(pack(pos));
    }

    // ------------------------------------------------------------------ detector rails

    private static void tickDetectorRails() {
        for (long key : detectorPositions) {
            Point pos = unpackVec(key);
            if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
            Block block = instance.getBlock(pos);
            if (!block.key().value().equals("detector_rail")) {
                detectorPositions.remove(key);
                continue;
            }
            boolean cartPresent = instance.getEntities().stream().anyMatch(entity ->
                    entity.getEntityType().key().value().contains("minecart")
                            && entity.getPosition().blockX() == pos.blockX()
                            && entity.getPosition().blockZ() == pos.blockZ()
                            && Math.abs(entity.getPosition().y() - pos.blockY()) < 0.6);
            if (cartPresent != "true".equals(block.getProperty("powered"))) {
                instance.setBlock(pos, block.withProperty("powered", String.valueOf(cartPresent)));
                neighborsChanged(pos);
                neighborsChanged(pos.add(0, -1, 0));
            }
        }
    }

    /** Track detector rails loaded from a saved world when someone approaches them. */
    public static void trackDetector(Point pos) {
        detectorPositions.add(pack(pos));
    }

    // ------------------------------------------------------------------ daylight detectors

    /** Track daylight detectors loaded from a saved world (or placed by tests). */
    public static void trackDaylightDetector(Point pos) {
        daylightDetectors.add(pack(pos));
    }

    private static void tickDaylightDetectors() {
        for (long key : daylightDetectors) {
            Point pos = unpackVec(key);
            if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
            if (!DaylightDetectors.recompute(instance, pos)) daylightDetectors.remove(key);
        }
    }

    // ------------------------------------------------------------------ lightning rod

    /** Track lightning rods loaded from a saved world (or placed by tests). */
    public static void trackLightningRod(Point pos) {
        lightningRods.add(pack(pos));
    }

    /**
     * ServerLevel.findLightningRod: nearest tracked rod within 128 blocks of the
     * strike point (vanilla uses a POI sphere search of radius 128). Returns null
     * if this isn't the redstone instance or no rod is in range.
     */
    public static Point nearestLightningRod(Instance in, Point ground) {
        if (in != instance) return null;
        Point best = null;
        double bestSq = 128.0 * 128.0;
        for (long key : lightningRods) {
            Point pos = unpackVec(key);
            if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
            if (!instance.getBlock(pos).key().value().equals("lightning_rod")) {
                lightningRods.remove(key);
                continue;
            }
            double dsq = pos.distanceSquared(ground);
            if (dsq <= bestSq) {
                bestSq = dsq;
                best = pos;
            }
        }
        return best;
    }

    /** LightningRodBlock.onLightningStrike: POWERED for 8gt, strong power out the base. */
    public static void lightningRodStruck(Point pos) {
        Block rod = instance.getBlock(pos);
        if (!rod.key().value().equals("lightning_rod")) return;
        instance.setBlock(pos, rod.withProperty("powered", "true"));
        neighborsChanged(pos);
        neighborsChanged(pos.add(facingVec(rod.getProperty("facing")).mul(-1))); // attachment
        schedule(8, () -> {
            Block now = instance.getBlock(pos);
            if (now.key().value().equals("lightning_rod")) {
                instance.setBlock(pos, now.withProperty("powered", "false"));
                neighborsChanged(pos);
                neighborsChanged(pos.add(facingVec(now.getProperty("facing")).mul(-1)));
            }
        });
    }

    // ------------------------------------------------------------------ tripwire

    /** Track tripwire hooks loaded from a saved world when someone approaches them. */
    public static void trackTripwireHook(Point pos) {
        tripwireHooks.add(pack(pos));
    }

    private static void tickTripwires() {
        for (long key : tripwireHooks) {
            Point pos = unpackVec(key);
            if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
            if (!instance.getBlock(pos).key().value().equals("tripwire_hook")) {
                tripwireHooks.remove(key);
                continue;
            }
            recomputeTripwireHook(pos);
        }
    }

    /**
     * TripWireHookBlock.calculateState: scan up to 41 blocks along FACING; a run of unbroken,
     * undisarmed tripwire blocks ending in a hook facing back (and at least 1 wire block
     * between them — adjacent hooks never attach) makes both ends ATTACHED. POWERED follows
     * whether any entity currently overlaps any connected wire block.
     */
    private static void recomputeTripwireHook(Point hookPos) {
        Block hook = instance.getBlock(hookPos);
        Vec facing = facingVec(hook.getProperty("facing"));
        List<Point> wire = new ArrayList<>();
        boolean attached = false;
        boolean disarmedAlong = false;
        for (int i = 1; i <= TRIPWIRE_MAX_LENGTH; i++) {
            Point p = hookPos.add(facing.mul(i));
            Block b = instance.getBlock(p);
            String key = b.key().value();
            if (key.equals("tripwire_hook")) {
                if (i > 1 && sameDir(facingVec(b.getProperty("facing")).mul(-1), facing)) attached = true;
                break;
            } else if (key.equals("tripwire")) {
                wire.add(p);
                if ("true".equals(b.getProperty("disarmed"))) disarmedAlong = true;
            } else {
                break;
            }
        }
        if (disarmedAlong) attached = false;

        boolean powered = attached && wire.stream().anyMatch(Redstone::entityOnTripwire);

        if (attached != "true".equals(hook.getProperty("attached"))
                || powered != "true".equals(hook.getProperty("powered"))) {
            instance.setBlock(hookPos, hook.withProperty("attached", String.valueOf(attached))
                    .withProperty("powered", String.valueOf(powered)));
            neighborsChanged(hookPos);
        }
        for (Point wp : wire) {
            Block w = instance.getBlock(wp);
            if (!w.key().value().equals("tripwire")) continue;
            if (attached != "true".equals(w.getProperty("attached"))
                    || powered != "true".equals(w.getProperty("powered"))) {
                instance.setBlock(wp, w.withProperty("attached", String.valueOf(attached))
                        .withProperty("powered", String.valueOf(powered)));
            }
        }
    }

    /** TripWireBlock.checkPressed: any non-item entity whose feet overlap the wire tile. */
    private static boolean entityOnTripwire(Point wp) {
        return instance.getEntities().stream().anyMatch(entity -> {
            if (entity.isRemoved() || entity instanceof net.minestom.server.entity.ItemEntity) return false;
            Point ep = entity.getPosition();
            return ep.blockX() == wp.blockX() && ep.blockZ() == wp.blockZ() && Math.abs(ep.y() - wp.blockY()) < 1.0;
        });
    }

    // ------------------------------------------------------------------ util

    /** Total reorder-at-collision fires in the piston resolver — for tests, which
     *  otherwise can't tell from final layouts whether a rig reached that path. */
    public static int pistonReorderFires() {
        return Pistons.REORDER_FIRES.get();
    }

    /** Direction equality safe against -0.0 from Vec.mul(-1). */
    static boolean sameDir(Vec a, Vec b) {
        return (int) a.x() == (int) b.x() && (int) a.y() == (int) b.y() && (int) a.z() == (int) b.z();
    }

    static Vec opp(Vec d) {
        return new Vec(0 - (int) d.x(), 0 - (int) d.y(), 0 - (int) d.z());
    }

    static Vec facingVec(String facing) {
        return switch (facing == null ? "north" : facing) {
            case "south" -> new Vec(0, 0, 1);
            case "east" -> new Vec(1, 0, 0);
            case "west" -> new Vec(-1, 0, 0);
            case "up" -> new Vec(0, 1, 0);
            case "down" -> new Vec(0, -1, 0);
            default -> new Vec(0, 0, -1);
        };
    }

    static String dirName(Vec d) {
        if (d.x() > 0) return "east";
        if (d.x() < 0) return "west";
        if (d.z() > 0) return "south";
        return "north";
    }

    private static long pack(Point p) {
        return ((long) (p.blockX() & 0x3FFFFFF) << 38) | ((long) (p.blockY() & 0xFFF) << 26)
                | (p.blockZ() & 0x3FFFFFF);
    }

    private static Vec unpackVec(long k) {
        return new Vec((int) (k >> 38), (int) (k << 26 >> 52), (int) (k << 38 >> 38));
    }

    static Instance instance() {
        return instance;
    }

    static void markDirty(Point pos) {
        dirty.add(pack(pos));
    }
}
