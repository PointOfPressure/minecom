package dev.pointofpressure.minecom.redstone;

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

/**
 * Vanilla redstone: wire networks with 15-block signal decay, strong vs weak
 * power, torches (1rt inversion), repeaters (1-4rt delay + locking), comparators
 * (compare/subtract + container reading), lamps, doors, buttons, levers,
 * pressure plates, TNT, dispensers/droppers, and pistons — including
 * quasi-connectivity: pistons/dispensers/droppers also accept power at the
 * block above them and only re-check on a block update (BUD behavior emerges).
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
    private static final int TRIPWIRE_MAX_LENGTH = 41; // TripWireHookBlock: i in 1..41
    private static final Map<Long, java.util.ArrayDeque<Long>> torchFlips = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ lifecycle

    public static void start(Instance overworld) {
        instance = overworld;
        MinecraftServer.getSchedulerManager().buildTask(Redstone::tick)
                .repeat(TaskSchedule.tick(1)).schedule();
    }

    public static void register(GlobalEventHandler events) {
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

        if (tickCount % 5 == 0) { tickPlates(); tickDetectorRails(); tickTripwires(); }
        if (tickCount % 20 == 0) tickDaylightDetectors();
    }

    private static void schedule(int delayTicks, Runnable action) {
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
            case "redstone_wire" -> {
                int power = Integer.parseInt(source.getProperty("power"));
                if (power == 0) return 0;
                if (toTarget.y() < 0) return power;          // powers the block below it
                if (toTarget.y() > 0) return 0;
                return wirePointsToward(source, sourcePos, toTarget) ? power : 0;
            }
            default -> {
                if (key.endsWith("_button")) return "true".equals(source.getProperty("powered")) ? 15 : 0;
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
                default -> {
                    if (key.endsWith("_button") && "true".equals(n.getProperty("powered"))
                            && attachedTo(n, toMe)) max = 15;
                    if (key.endsWith("_pressure_plate") && "true".equals(n.getProperty("powered"))
                            && d.y() > 0) max = 15; // plate on top strongly powers below? vanilla: block underneath
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
                || key.equals("target") || key.equals("daylight_detector")) return true;
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
                    if (signal) dev.pointofpressure.minecom.blocks.NoteBlocks.playNote(instance, pos, block);
                    instance.setBlock(pos, block.withProperty("powered", String.valueOf(signal)));
                }
            }
            case "piston", "sticky_piston" -> Pistons.evaluate(instance, pos, block);
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
                if (key.endsWith("_door") || key.endsWith("_trapdoor") || key.endsWith("_fence_gate")) {
                    boolean powered = activated(pos)
                            || (key.endsWith("_door") && doorOtherHalfPowered(pos, block));
                    boolean wasPowered = "true".equals(block.getProperty("powered"));
                    if (powered != wasPowered) {
                        Block updated = block.withProperty("powered", String.valueOf(powered))
                                .withProperty("open", String.valueOf(powered));
                        instance.setBlock(pos, updated);
                        if (key.endsWith("_door")) syncDoorHalf(pos, updated);
                    }
                }
            }
        }
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
    private static int inputPower(Point pos, Vec facing) {
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
        if (rearSignal < 0) rearSignal = Math.max(emitted(rear, rearPos, facing.mul(-1)),
                isSolid(rear) && blockPowered(rearPos) ? 15 : 0);

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

    /** Vanilla container fullness signal, or -1 if the block isn't a tracked container. */
    private static int containerSignal(Point pos, Block block) {
        String key = block.key().value();
        Inventory inv = null;
        if (key.equals("chest") || key.equals("barrel")) inv = Containers.CHESTS.get(Containers.posKey(pos));
        else if (key.equals("furnace")) {
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

    // ------------------------------------------------------------------ dispensers

    public static final Map<String, Inventory> DISPENSERS = new ConcurrentHashMap<>();

    public static Inventory dispenserInventory(Point pos) {
        return DISPENSERS.computeIfAbsent(Containers.posKey(pos),
                k -> new Inventory(net.minestom.server.inventory.InventoryType.WINDOW_3X3,
                        net.kyori.adventure.text.Component.text("Dispenser")));
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

        if (!dropper && mat == Material.ARROW) {
            EntityProjectile arrow = new EntityProjectile(null, EntityType.ARROW);
            arrow.setInstance(instance, spawnAt);
            arrow.setVelocity(facing.mul(20).add(0, 1, 0));
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
            boolean wooden = !bk.startsWith("stone") && !bk.contains("weighted");
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
