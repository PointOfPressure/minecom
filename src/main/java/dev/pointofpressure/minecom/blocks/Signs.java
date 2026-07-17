package dev.pointofpressure.minecom.blocks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.pointofpressure.minecom.Persist;
import dev.pointofpressure.minecom.StateAdapter;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.color.DyeColor;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerEditSignEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.OpenSignEditorPacket;
import net.minestom.server.sound.SoundEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs, ported from decompiled SignBlockEntity/SignBlock/SignText + the SignApplicator
 * items (DyeItem/GlowInkSacItem/InkSacItem/HoneycombItem, all freshly decompiled for 26.2).
 * Front and back faces are tracked independently (4 lines/color/glow each, matching
 * {@code SignText}); which face a click hits is {@code SignBlock.isFacingFrontText}'s exact
 * formula (the sign's own yaw — {@code rotation*22.5} for a standing sign,
 * {@code Direction.toYRot()} for a wall sign — vs. the clicking player's angle to it, front
 * within 90 degrees). Right-clicking bare hand opens the real client text-editor protocol
 * round trip (server sends {@code OpenSignEditorPacket}, client replies with
 * {@code ClientUpdateSignPacket} -> Minestom's own {@code PlayerEditSignEvent}, both wired
 * in already) — same auto-open on fresh placement as real vanilla. A
 * {@code SignApplicator} item only works on a face already carrying a message (real
 * vanilla's {@code canApplyToSign} default, honeycomb overrides it to always-true): dye sets
 * that face's color, glow ink sac sets glowing, plain ink sac clears it, honeycomb waxes the
 * whole sign (blocks every further edit/dye/glow/wax action — {@code isWaxed()} gates all of
 * them, matching {@code SignBlock.useItemOn}/{@code useWithoutItem}).
 *
 * Simplifications (AUDIT): hanging signs (chain-suspended, a distinct block family) aren't
 * modeled, only standing/wall signs; click-command execution (RunCommand/ShowDialog/Custom
 * style click events on sign text) isn't modeled — this project has no rich-text click-event
 * authoring path for players to create one in the first place; the vanilla-shaped block NBT
 * this project attaches for client rendering ({@link #syncNbt}) is a best-effort compound
 * (plain-string text components) not independently verified against a live vanilla client in
 * this headless-playtest environment — the state machine itself (text/color/glow/wax,
 * persistence, front-vs-back routing) is what PlayTest verifies.
 */
public final class Signs {
    private Signs() {}

    private static final class Face {
        String[] lines = {"", "", "", ""};
        String color = "black"; // DyeColor name, default matches SignText's DyeColor.BLACK
        boolean glowing;
    }

    private static final class SignData {
        final Face front = new Face();
        final Face back = new Face();
        boolean waxed;
        UUID allowedEditor;
    }

    private static final Map<String, SignData> SIGNS = new ConcurrentHashMap<>();
    private static final Map<Material, DyeColor> DYES = Map.ofEntries(
            Map.entry(Material.WHITE_DYE, DyeColor.WHITE), Map.entry(Material.ORANGE_DYE, DyeColor.ORANGE),
            Map.entry(Material.MAGENTA_DYE, DyeColor.MAGENTA), Map.entry(Material.LIGHT_BLUE_DYE, DyeColor.LIGHT_BLUE),
            Map.entry(Material.YELLOW_DYE, DyeColor.YELLOW), Map.entry(Material.LIME_DYE, DyeColor.LIME),
            Map.entry(Material.PINK_DYE, DyeColor.PINK), Map.entry(Material.GRAY_DYE, DyeColor.GRAY),
            Map.entry(Material.LIGHT_GRAY_DYE, DyeColor.LIGHT_GRAY), Map.entry(Material.CYAN_DYE, DyeColor.CYAN),
            Map.entry(Material.PURPLE_DYE, DyeColor.PURPLE), Map.entry(Material.BLUE_DYE, DyeColor.BLUE),
            Map.entry(Material.BROWN_DYE, DyeColor.BROWN), Map.entry(Material.GREEN_DYE, DyeColor.GREEN),
            Map.entry(Material.RED_DYE, DyeColor.RED), Map.entry(Material.BLACK_DYE, DyeColor.BLACK));

    private static Instance instance;

    public static void register(GlobalEventHandler events, Instance overworld) {
        instance = overworld;
        Persist.register(persistence());
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (!isSign(e.getBlock())) return;
            track(e.getBlockPosition());
            openEditor(e.getPlayer(), e.getBlockPosition(), true);
        });
        events.addListener(PlayerEditSignEvent.class, Signs::onEdit);
        events.addListener(PlayerUseItemOnBlockEvent.class, Signs::applyItem);
        events.addListener(net.minestom.server.event.player.PlayerBlockInteractEvent.class, e -> {
            if (!isSign(e.getBlock())) return;
            // SignBlock.useItemOn returning TRY_WITH_EMPTY_HAND (a SignApplicator that can't
            // apply right now) falls through to useWithoutItem's editor-open in real vanilla;
            // applyItem already no-ops for exactly those same cases, so mirror its own gate
            // here rather than re-deriving it, and only skip the editor when applyItem is
            // actually about to change something this same click.
            ItemStack held = e.getPlayer().getItemInHand(e.getHand());
            if (willApply(e.getBlockPosition(), held)) return;
            tryOpenOnInteract(e.getPlayer(), e.getBlockPosition());
        });
        events.addListener(PlayerBlockBreakEvent.class, e -> {
            if (isSign(e.getBlock())) SIGNS.remove(Persist.posKey(e.getBlockPosition()));
        });
    }

    /** Whether {@link #applyItem} would actually change this sign for this held item right now. */
    private static boolean willApply(Point pos, ItemStack held) {
        Material mat = held.material();
        if (mat == Material.HONEYCOMB) return !dataOf(pos).waxed;
        if (mat != Material.GLOW_INK_SAC && mat != Material.INK_SAC && !DYES.containsKey(mat)) return false;
        SignData d = dataOf(pos);
        if (d.waxed) return false;
        // face selection needs the player position, which this helper doesn't have — approximate
        // with "either face has a message", matching applyItem's own hasMessage gate closely
        // enough for this narrow "should the editor open instead" decision.
        return hasMessage(d.front) || hasMessage(d.back);
    }

    public static boolean isSign(Block block) {
        String key = block.key().value();
        return key.endsWith("_sign");
    }

    public static void track(Point pos) {
        SIGNS.computeIfAbsent(Persist.posKey(pos), k -> new SignData());
    }

    private static SignData dataOf(Point pos) {
        return SIGNS.computeIfAbsent(Persist.posKey(pos), k -> new SignData());
    }

    public static boolean isWaxed(Point pos) {
        return dataOf(pos).waxed;
    }

    public static String[] lines(Point pos, boolean front) {
        SignData d = dataOf(pos);
        return (front ? d.front : d.back).lines.clone();
    }

    public static String color(Point pos, boolean front) {
        SignData d = dataOf(pos);
        return (front ? d.front : d.back).color;
    }

    public static boolean glowing(Point pos, boolean front) {
        SignData d = dataOf(pos);
        return (front ? d.front : d.back).glowing;
    }

    /** SignBlock.getYRotationDegrees + isFacingFrontText: standing = rotation*22.5, wall =
     * facing's yaw (SOUTH=0, WEST=90, NORTH=180, EAST=270); front if the ANGLE FROM THE SIGN
     * TO THE PLAYER (position only — real vanilla never looks at the player's own facing) is
     * within 90 degrees of the sign's own yaw. */
    public static boolean isFacingFrontText(Point pos, double playerX, double playerZ) {
        Block block = instance.getBlock(pos);
        float signYaw;
        String rotation = block.getProperty("rotation");
        if (rotation != null) {
            signYaw = Integer.parseInt(rotation) * 22.5f;
        } else {
            String facing = block.getProperty("facing");
            signYaw = switch (facing == null ? "north" : facing) {
                case "south" -> 0f;
                case "west" -> 90f;
                case "north" -> 180f;
                case "east" -> 270f;
                default -> 0f;
            };
        }
        double dx = playerX - (pos.blockX() + 0.5);
        double dz = playerZ - (pos.blockZ() + 0.5);
        float playerAngleToSign = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float diff = degreesDifferenceAbs(signYaw, playerAngleToSign);
        return diff <= 90.0f;
    }

    private static float degreesDifferenceAbs(float a, float b) {
        float diff = Math.abs(a - b) % 360.0f;
        return diff > 180.0f ? 360.0f - diff : diff;
    }

    private static void openEditor(Player player, Point pos, boolean isFrontText) {
        SignData d = dataOf(pos);
        if (d.waxed) return;
        d.allowedEditor = player.getUuid();
        player.sendPacket(new OpenSignEditorPacket(pos, isFrontText));
    }

    /** SignBlockEntity.updateSignText: only the allowed editor, only while not waxed. */
    private static void onEdit(PlayerEditSignEvent e) {
        if (!isSign(e.getBlock())) return;
        Point pos = e.getBlockPosition();
        SignData d = dataOf(pos);
        if (d.waxed || !e.getPlayer().getUuid().equals(d.allowedEditor)) return;
        Face face = e.isFrontText() ? d.front : d.back;
        List<String> lines = e.getLines();
        for (int i = 0; i < 4 && i < lines.size(); i++) {
            face.lines[i] = lines.get(i) == null ? "" : lines.get(i);
        }
        d.allowedEditor = null;
        syncNbt(pos, d);
    }

    /** SignBlock.useWithoutItem's edit-open path (bare-hand click, no SignApplicator item). */
    static boolean tryOpenOnInteract(Player player, Point pos) {
        if (!isSign(instance.getBlock(pos))) return false;
        SignData d = dataOf(pos);
        if (d.waxed) return true; // consumes the interaction, matches SUCCESS_SERVER (fail sound)
        boolean front = isFacingFrontText(pos, player.getPosition().x(), player.getPosition().z());
        openEditor(player, pos, front);
        return true;
    }

    /** SignBlock.useItemOn: DyeItem/GlowInkSacItem/InkSacItem/HoneycombItem application. */
    private static void applyItem(PlayerUseItemOnBlockEvent e) {
        Point pos = e.getPosition();
        Block block = instance.getBlock(pos);
        if (!isSign(block)) return;
        SignData d = dataOf(pos);
        if (d.waxed) return;
        Player player = e.getPlayer();
        boolean front = isFacingFrontText(pos, player.getPosition().x(), player.getPosition().z());
        Face face = front ? d.front : d.back;
        ItemStack held = e.getItemStack();
        Material mat = held.material();
        boolean hasMessage = hasMessage(face);
        SoundEvent sound;
        if (mat == Material.HONEYCOMB) {
            // HoneycombItem.canApplyToSign is always true (unlike dye/glow/ink); levelEvent
            // 3003 (the wax particle burst) has no direct sound analogue, so this is silent.
            d.waxed = true;
            sound = null;
        } else if (mat == Material.GLOW_INK_SAC && hasMessage) {
            face.glowing = true;
            sound = SoundEvent.ITEM_GLOW_INK_SAC_USE;
        } else if (mat == Material.INK_SAC && hasMessage) {
            face.glowing = false;
            sound = SoundEvent.ITEM_INK_SAC_USE;
        } else if (DYES.containsKey(mat) && hasMessage) {
            face.color = DYES.get(mat).name();
            sound = SoundEvent.ITEM_DYE_USE;
        } else {
            return;
        }
        player.setItemInHand(e.getHand(), held.consume(1));
        if (sound != null) {
            instance.playSound(Sound.sound(sound, Sound.Source.BLOCK, 1f, 1f),
                    pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
        }
        syncNbt(pos, d);
    }

    private static boolean hasMessage(Face face) {
        for (String line : face.lines) if (line != null && !line.isEmpty()) return true;
        return false;
    }

    /** Best-effort vanilla-shaped block NBT so a live client renders the text (class doc). */
    private static void syncNbt(Point pos, SignData d) {
        CompoundBinaryTag tag = CompoundBinaryTag.builder()
                .put("front_text", faceTag(d.front))
                .put("back_text", faceTag(d.back))
                .putBoolean("is_waxed", d.waxed)
                .build();
        instance.setBlock(pos, instance.getBlock(pos).withNbt(tag));
    }

    private static CompoundBinaryTag faceTag(Face face) {
        ListBinaryTag.Builder<BinaryTag> messages = ListBinaryTag.builder();
        for (String line : face.lines) messages.add(StringBinaryTag.stringBinaryTag(line == null ? "" : line));
        return CompoundBinaryTag.builder()
                .put("messages", messages.build())
                .putString("color", face.color)
                .putBoolean("has_glowing_text", face.glowing)
                .build();
    }

    /** Persistence (docs/PERSISTENCE.md): both faces' text/color/glow + waxed. */
    private static StateAdapter persistence() {
        return new StateAdapter() {
            @Override
            public String kind() {
                return "sign";
            }

            @Override
            public void collect(Instance in, java.util.function.BiConsumer<Point, JsonObject> out) {
                SIGNS.forEach((key, d) -> {
                    JsonObject o = new JsonObject();
                    o.add("front", faceJson(d.front));
                    o.add("back", faceJson(d.back));
                    o.addProperty("waxed", d.waxed);
                    out.accept(Persist.parsePos(key), o);
                });
            }

            @Override
            public void restore(Instance in, Point pos, JsonObject data) {
                SignData d = new SignData();
                readFace(d.front, data.getAsJsonObject("front"));
                readFace(d.back, data.getAsJsonObject("back"));
                d.waxed = data.get("waxed").getAsBoolean();
                SIGNS.put(Persist.posKey(pos), d);
                syncNbt(pos, d);
            }

            @Override
            public void wipe() {
                SIGNS.clear();
            }
        };
    }

    private static JsonObject faceJson(Face face) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String line : face.lines) arr.add(line == null ? "" : line);
        o.add("lines", arr);
        o.addProperty("color", face.color);
        o.addProperty("glowing", face.glowing);
        return o;
    }

    private static void readFace(Face face, JsonObject o) {
        JsonArray arr = o.getAsJsonArray("lines");
        for (int i = 0; i < 4 && i < arr.size(); i++) face.lines[i] = arr.get(i).getAsString();
        face.color = o.get("color").getAsString();
        face.glowing = o.get("glowing").getAsBoolean();
    }
}
