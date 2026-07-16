package dev.pointofpressure.minecom.blocks;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.type.BeaconInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientSetBeaconEffectPacket;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Beacon block entity + menu, ported from decompiled BeaconBlockEntity / BeaconMenu
 * (26.2). updateBase walks up to four widening (2*step+1)^2 layers of BEACON_BASE_BLOCKS
 * directly beneath the beacon; the highest fully-solid layer is the level (0-4). The beam
 * only carries — and effects only apply — when the column above the beacon reaches the sky
 * with no light-blocking (lightBlocked>=15, bedrock excepted) block; glass/stained glass
 * (BeaconBeamBlock, lightBlocked 0) pass. On the vanilla gameTime%80 cadence an active
 * beacon reapplies its primary effect to every player within a (level*10+10)-block box
 * (full-height column) for (9+level*2)*20 ticks at amp 0, plus, at level 4, either the same
 * effect at amp 1 (secondary==primary) or a distinct secondary at amp 0. Selecting effects
 * goes through the beacon menu: validateEffects gates the choice against the level (primary
 * must be a level-1..3 effect; a secondary needs level 4 and is either regeneration or a
 * copy of the primary), and a successful selection consumes one BEACON_PAYMENT_ITEMS item
 * from the payment slot.
 *
 * Simplifications (AUDIT): beam/level recompute happens whole on the 80-tick cadence rather
 * than vanilla's 10-blocks-per-tick incremental beamSections walk (identical observable
 * effect timing); beam colour sections and CONSTRUCT_BEACON advancement are not modelled;
 * the payment-selection packet maps Minestom's PotionType to the effect by key.
 */
public final class Beacons {
    private Beacons() {}

    private static final int MAX_LEVELS = 4;
    private static final long EFFECT_CADENCE = 80L;

    // BlockTags.BEACON_BASE_BLOCKS (26.2).
    private static final Set<String> BASE_BLOCKS = Set.of(
            "iron_block", "gold_block", "emerald_block", "diamond_block", "netherite_block");
    // ItemTags.BEACON_PAYMENT_ITEMS (26.2).
    private static final Set<Material> PAYMENT_ITEMS = Set.of(
            Material.IRON_INGOT, Material.GOLD_INGOT, Material.EMERALD, Material.DIAMOND, Material.NETHERITE_INGOT);
    // getRequiredLevelsFor: BEACON_EFFECTS index+1 for the primary/secondary validation.
    private static final Map<PotionEffect, Integer> EFFECT_LEVEL = Map.of(
            PotionEffect.SPEED, 1, PotionEffect.HASTE, 1,
            PotionEffect.RESISTANCE, 2, PotionEffect.JUMP_BOOST, 2,
            PotionEffect.STRENGTH, 3, PotionEffect.REGENERATION, 4);

    private static final class Beacon {
        int levels;
        boolean beamActive;
        boolean wasActive;
        PotionEffect primary;
        PotionEffect secondary;
    }

    private static final Map<Long, Beacon> BEACONS = new ConcurrentHashMap<>();
    /** Beacon position each player currently has open in a beacon menu. thread: main tick only. */
    private static final Map<Player, Point> OPEN = new ConcurrentHashMap<>();
    private static Instance instance;

    public static void register(GlobalEventHandler events, Instance overworld) {
        instance = overworld;
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (e.getBlock().key().value().equals("beacon")) track(e.getBlockPosition());
        });
        events.addListener(PlayerBlockBreakEvent.class, e -> {
            if (e.getBlock().key().value().equals("beacon")) BEACONS.remove(pack(e.getBlockPosition()));
        });
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().equals("beacon")) return;
            Point pos = e.getBlockPosition();
            track(pos);
            recompute(pos);
            BeaconInventory menu = new BeaconInventory(net.kyori.adventure.text.Component.text("Beacon"));
            menu.setPowerLevel((short) levels(pos));
            OPEN.put(e.getPlayer(), pos);
            e.getPlayer().openInventory(menu);
        });

        MinecraftServer.getPacketListenerManager().setListener(ClientSetBeaconEffectPacket.class, (packet, player) -> {
            Point pos = OPEN.get(player);
            if (pos == null || !(player.getOpenInventory() instanceof BeaconInventory menu)) return;
            PotionEffect primary = packet.primaryEffect() == null ? null
                    : PotionEffect.fromKey(packet.primaryEffect().key());
            PotionEffect secondary = packet.secondaryEffect() == null ? null
                    : PotionEffect.fromKey(packet.secondaryEffect().key());
            selectEffects(pos, primary, secondary, menu);
        });

        MinecraftServer.getSchedulerManager().buildTask(Beacons::tick)
                .repeat(TaskSchedule.tick(1)).schedule();
    }

    /** Register a beacon placed by tests or world code. */
    public static void track(Point pos) {
        BEACONS.computeIfAbsent(pack(pos), k -> new Beacon());
    }

    public static int levels(Point pos) {
        Beacon b = BEACONS.get(pack(pos));
        return b == null ? 0 : b.levels;
    }

    public static boolean beamActive(Point pos) {
        Beacon b = BEACONS.get(pack(pos));
        return b != null && b.beamActive;
    }

    public static PotionEffect primary(Point pos) {
        Beacon b = BEACONS.get(pack(pos));
        return b == null ? null : b.primary;
    }

    public static PotionEffect secondary(Point pos) {
        Beacon b = BEACONS.get(pack(pos));
        return b == null ? null : b.secondary;
    }

    /** Recompute pyramid level + beam sky-access. Test hook and per-cadence driver. */
    public static void recompute(Point pos) {
        Beacon b = BEACONS.get(pack(pos));
        if (b == null || instance == null) return;
        b.levels = updateBase(pos);
        b.beamActive = beamReachesSky(pos);
    }

    /**
     * BeaconMenu.updateEffects: validate the requested primary/secondary against the current
     * level, and on success consume one payment item from the menu's payment slot and store
     * the selection. Returns whether the selection was accepted.
     */
    public static boolean selectEffects(Point pos, PotionEffect primary, PotionEffect secondary, Inventory menu) {
        Beacon b = BEACONS.get(pack(pos));
        if (b == null) return false;
        ItemStack payment = menu.getItemStack(0);
        if (payment.isAir() || !PAYMENT_ITEMS.contains(payment.material())) return false;
        if (!validateEffects(primary, secondary, b.levels)) return false;
        b.primary = primary;
        b.secondary = secondary;
        menu.setItemStack(0, payment.consume(1));
        return true;
    }

    /** BeaconBlockEntity.validateEffects — the exact level-gating rules. */
    public static boolean validateEffects(PotionEffect primary, PotionEffect secondary, int levels) {
        if (secondary != null && levels < 4) return false;
        int primaryLevel = requiredLevel(primary);
        int secondaryLevel = requiredLevel(secondary);
        if (primaryLevel > levels || secondaryLevel > levels) return false;
        if (primaryLevel >= 4) return false;
        return secondaryLevel == 0 || secondaryLevel >= 4 || primary.equals(secondary);
    }

    private static int requiredLevel(PotionEffect effect) {
        if (effect == null) return 0;
        return EFFECT_LEVEL.getOrDefault(effect, Integer.MAX_VALUE);
    }

    /**
     * BeaconBlockEntity.applyEffects: reapply the beacon's stored effects to every player in a
     * (level*10+10)-block full-height box. Test hook (drives one cadence tick directly).
     */
    public static void applyEffects(Point pos) {
        Beacon b = BEACONS.get(pack(pos));
        if (b == null || instance == null || b.levels <= 0 || !b.beamActive || b.primary == null) return;
        int range = b.levels * 10 + 10;
        int amp = (b.levels >= MAX_LEVELS && b.primary.equals(b.secondary)) ? 1 : 0;
        int duration = (9 + b.levels * 2) * 20;
        for (Player player : instance.getPlayers()) {
            double dx = Math.abs(player.getPosition().x() - (pos.blockX() + 0.5));
            double dz = Math.abs(player.getPosition().z() - (pos.blockZ() + 0.5));
            if (dx > range || dz > range) continue;
            player.addEffect(new Potion(b.primary, (byte) amp, duration));
            if (b.levels >= MAX_LEVELS && b.secondary != null && !b.secondary.equals(b.primary)) {
                player.addEffect(new Potion(b.secondary, (byte) 0, duration));
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    /** BeaconBlockEntity.updateBase: highest fully-BEACON_BASE_BLOCKS pyramid layer, 0-4. */
    private static int updateBase(Point pos) {
        int x = pos.blockX(), y = pos.blockY(), z = pos.blockZ();
        int levels = 0;
        for (int step = 1; step <= MAX_LEVELS; step++) {
            int ly = y - step;
            if (ly < instance.getCachedDimensionType().minY()) break;
            boolean ok = true;
            for (int lx = x - step; lx <= x + step && ok; lx++) {
                for (int lz = z - step; lz <= z + step; lz++) {
                    if (!BASE_BLOCKS.contains(instance.getBlock(lx, ly, lz).key().value())) {
                        ok = false;
                        break;
                    }
                }
            }
            if (!ok) break;
            levels = step;
        }
        return levels;
    }

    /** The beam carries when nothing light-blocking (bedrock excepted) sits above the beacon. */
    private static boolean beamReachesSky(Point pos) {
        int x = pos.blockX(), z = pos.blockZ();
        int top = instance.getCachedDimensionType().minY() + instance.getCachedDimensionType().height();
        for (int y = pos.blockY() + 1; y < top; y++) {
            if (!instance.isChunkLoaded(x >> 4, z >> 4)) return false;
            Block block = instance.getBlock(x, y, z);
            if (block.isAir()) continue;
            if (block.key().value().equals("bedrock")) continue;
            if (block.registry().lightBlocked() >= 15) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ tick

    private static void tick() {
        if (instance == null || BEACONS.isEmpty()) return;
        long gameTime = instance.getWorldAge();
        if (gameTime % EFFECT_CADENCE != 0L) return;
        for (Map.Entry<Long, Beacon> entry : BEACONS.entrySet()) {
            Point pos = unpack(entry.getKey());
            if (!instance.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) continue;
            if (!instance.getBlock(pos).key().value().equals("beacon")) {
                BEACONS.remove(entry.getKey());
                continue;
            }
            Beacon b = entry.getValue();
            recompute(pos);
            boolean active = b.levels > 0 && b.beamActive;
            if (active) applyEffects(pos);
            if (active != b.wasActive) {
                SoundEvent event = active ? SoundEvent.BLOCK_BEACON_ACTIVATE : SoundEvent.BLOCK_BEACON_DEACTIVATE;
                instance.playSound(net.kyori.adventure.sound.Sound.sound(
                        event, net.kyori.adventure.sound.Sound.Source.BLOCK, 1.0f, 1.0f),
                        pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
                b.wasActive = active;
            }
        }
    }

    private static long pack(Point p) {
        return ((long) (p.blockX() & 0x3FFFFFF) << 38) | ((long) (p.blockY() & 0xFFF) << 26)
                | (p.blockZ() & 0x3FFFFFF);
    }

    private static Point unpack(long k) {
        return new net.minestom.server.coordinate.Vec((int) (k >> 38), (int) (k << 26 >> 52), (int) (k << 38 >> 38));
    }
}
