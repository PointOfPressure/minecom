package dev.pointofpressure.minecom.blocks;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Point;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.sound.SoundEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Note blocks: real per-block-below/above instrument selection (decompiled
 * {@code NoteBlock.setInstrument}/{@code playNote}) and right-click pitch cycling.
 * {@code BLOCK_INSTRUMENT} below is a literal port of every explicit
 * {@code .instrument(NoteBlockInstrument.X)} call in real vanilla's {@code Blocks.java}
 * registrations (484 of ~1144 total blocks; everything absent from the table defaults to
 * HARP, matching {@code BlockBehaviour.Properties}' own default). Redstone-triggered
 * playback (POWERED rising edge) is wired from {@code Redstone.java}'s neighbor-changed
 * dispatch, mirroring the dispenser/dropper edge-trigger pattern already there.
 */
public final class NoteBlocks {
    private NoteBlocks() {}

    public enum Instrument {
        HARP(SoundEvent.BLOCK_NOTE_BLOCK_HARP, Type.BASE_BLOCK),
        BASEDRUM(SoundEvent.BLOCK_NOTE_BLOCK_BASEDRUM, Type.BASE_BLOCK),
        SNARE(SoundEvent.BLOCK_NOTE_BLOCK_SNARE, Type.BASE_BLOCK),
        HAT(SoundEvent.BLOCK_NOTE_BLOCK_HAT, Type.BASE_BLOCK),
        BASS(SoundEvent.BLOCK_NOTE_BLOCK_BASS, Type.BASE_BLOCK),
        FLUTE(SoundEvent.BLOCK_NOTE_BLOCK_FLUTE, Type.BASE_BLOCK),
        BELL(SoundEvent.BLOCK_NOTE_BLOCK_BELL, Type.BASE_BLOCK),
        GUITAR(SoundEvent.BLOCK_NOTE_BLOCK_GUITAR, Type.BASE_BLOCK),
        CHIME(SoundEvent.BLOCK_NOTE_BLOCK_CHIME, Type.BASE_BLOCK),
        XYLOPHONE(SoundEvent.BLOCK_NOTE_BLOCK_XYLOPHONE, Type.BASE_BLOCK),
        IRON_XYLOPHONE(SoundEvent.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, Type.BASE_BLOCK),
        COW_BELL(SoundEvent.BLOCK_NOTE_BLOCK_COW_BELL, Type.BASE_BLOCK),
        DIDGERIDOO(SoundEvent.BLOCK_NOTE_BLOCK_DIDGERIDOO, Type.BASE_BLOCK),
        BIT(SoundEvent.BLOCK_NOTE_BLOCK_BIT, Type.BASE_BLOCK),
        BANJO(SoundEvent.BLOCK_NOTE_BLOCK_BANJO, Type.BASE_BLOCK),
        PLING(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Type.BASE_BLOCK),
        TRUMPET(SoundEvent.BLOCK_NOTE_BLOCK_TRUMPET, Type.BASE_BLOCK),
        TRUMPET_EXPOSED(SoundEvent.BLOCK_NOTE_BLOCK_TRUMPET_EXPOSED, Type.BASE_BLOCK),
        TRUMPET_OXIDIZED(SoundEvent.BLOCK_NOTE_BLOCK_TRUMPET_OXIDIZED, Type.BASE_BLOCK),
        TRUMPET_WEATHERED(SoundEvent.BLOCK_NOTE_BLOCK_TRUMPET_WEATHERED, Type.BASE_BLOCK),
        ZOMBIE(SoundEvent.BLOCK_NOTE_BLOCK_IMITATE_ZOMBIE, Type.MOB_HEAD),
        SKELETON(SoundEvent.BLOCK_NOTE_BLOCK_IMITATE_SKELETON, Type.MOB_HEAD),
        CREEPER(SoundEvent.BLOCK_NOTE_BLOCK_IMITATE_CREEPER, Type.MOB_HEAD),
        DRAGON(SoundEvent.BLOCK_NOTE_BLOCK_IMITATE_ENDER_DRAGON, Type.MOB_HEAD),
        WITHER_SKELETON(SoundEvent.BLOCK_NOTE_BLOCK_IMITATE_WITHER_SKELETON, Type.MOB_HEAD),
        PIGLIN(SoundEvent.BLOCK_NOTE_BLOCK_IMITATE_PIGLIN, Type.MOB_HEAD),
        CUSTOM_HEAD(SoundEvent.UI_BUTTON_CLICK, Type.CUSTOM);

        private enum Type { BASE_BLOCK, MOB_HEAD, CUSTOM }

        final SoundEvent sound;
        private final Type type;

        Instrument(SoundEvent sound, Type type) {
            this.sound = sound;
            this.type = type;
        }

        public boolean isTunable() { return type == Type.BASE_BLOCK; }
        public boolean hasCustomSound() { return type == Type.CUSTOM; }
        /** Skull/head instruments are detected on the block ABOVE; base-material ones BELOW. */
        public boolean worksAboveNoteBlock() { return type != Type.BASE_BLOCK; }
    }

    private static final Map<String, Instrument> BLOCK_INSTRUMENT = new HashMap<>();
    static {
        BLOCK_INSTRUMENT.put("hay_block", Instrument.BANJO);
        BLOCK_INSTRUMENT.put("andesite", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("basalt", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("bedrock", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("black_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("black_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("black_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("blackstone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("blast_furnace", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("blue_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("blue_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("blue_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("brain_coral_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("brick_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("brown_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("brown_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("brown_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("bubble_coral_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("calcite", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("chiseled_nether_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("chiseled_quartz_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("chiseled_red_sandstone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("chiseled_resin_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("chiseled_sandstone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("chiseled_stone_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("coal_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("coal_ore", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("cobblestone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("cobblestone_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("cracked_nether_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("cracked_stone_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("creaking_heart", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("crimson_nylium", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("crying_obsidian", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("cut_red_sandstone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("cut_red_sandstone_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("cut_sandstone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("cut_sandstone_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("cyan_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("cyan_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("cyan_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dark_prismarine", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dark_prismarine_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_brain_coral", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_brain_coral_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_brain_coral_fan", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_brain_coral_wall_fan", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_bubble_coral", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_bubble_coral_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_bubble_coral_fan", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_bubble_coral_wall_fan", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_fire_coral", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_fire_coral_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_fire_coral_fan", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_fire_coral_wall_fan", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_horn_coral", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_horn_coral_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_horn_coral_fan", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_horn_coral_wall_fan", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_tube_coral", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_tube_coral_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_tube_coral_fan", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dead_tube_coral_wall_fan", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("deepslate", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("diamond_ore", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("diorite", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dispenser", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dripstone_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("dropper", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("emerald_ore", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("enchanting_table", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("end_portal_frame", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("end_stone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("end_stone_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("ender_chest", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("fire_coral_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("furnace", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("gold_ore", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("granite", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("gray_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("gray_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("gray_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("green_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("green_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("green_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("horn_coral_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("iron_ore", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("lapis_ore", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("light_blue_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("light_blue_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("light_blue_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("light_gray_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("light_gray_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("light_gray_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("lime_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("lime_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("lime_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("magenta_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("magenta_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("magenta_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("magma_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("mossy_cobblestone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("mossy_stone_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("mud_brick_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("mud_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("nether_brick_fence", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("nether_brick_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("nether_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("nether_gold_ore", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("nether_quartz_ore", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("netherrack", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("observer", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("obsidian", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("orange_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("orange_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("orange_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("petrified_oak_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("pink_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("pink_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("pink_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("pointed_dripstone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("polished_andesite", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("polished_basalt", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("polished_blackstone_pressure_plate", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("polished_diorite", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("polished_granite", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("prismarine", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("prismarine_brick_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("prismarine_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("prismarine_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("purple_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("purple_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("purple_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("purpur_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("purpur_pillar", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("purpur_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("quartz_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("quartz_pillar", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("quartz_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("raw_copper_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("raw_gold_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("raw_iron_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("red_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("red_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("red_nether_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("red_sandstone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("red_sandstone_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("red_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("redstone_ore", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("reinforced_deepslate", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("resin_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("resin_brick_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("resin_brick_wall", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("resin_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("respawn_anchor", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("sandstone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("sandstone_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("smoker", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("smooth_quartz", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("smooth_red_sandstone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("smooth_sandstone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("smooth_stone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("smooth_stone_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("spawner", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("stone", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("stone_brick_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("stone_bricks", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("stone_pressure_plate", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("stone_slab", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("stonecutter", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("trial_spawner", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("tube_coral_block", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("tuff", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("vault", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("warped_nylium", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("white_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("white_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("white_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("yellow_concrete", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("yellow_glazed_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("yellow_terracotta", Instrument.BASEDRUM);
        BLOCK_INSTRUMENT.put("acacia_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("acacia_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_mosaic", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_mosaic_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bamboo_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("barrel", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bee_nest", Instrument.BASS);
        BLOCK_INSTRUMENT.put("beehive", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("birch_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("black_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("black_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("blue_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("blue_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("bookshelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("brown_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("brown_mushroom_block", Instrument.BASS);
        BLOCK_INSTRUMENT.put("brown_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("campfire", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cartography_table", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cherry_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("chest", Instrument.BASS);
        BLOCK_INSTRUMENT.put("chiseled_bookshelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("composter", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crafting_table", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_hyphae", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("crimson_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cyan_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("cyan_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("dark_oak_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("daylight_detector", Instrument.BASS);
        BLOCK_INSTRUMENT.put("fletching_table", Instrument.BASS);
        BLOCK_INSTRUMENT.put("gray_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("gray_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("green_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("green_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jukebox", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("jungle_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("lectern", Instrument.BASS);
        BLOCK_INSTRUMENT.put("light_blue_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("light_blue_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("light_gray_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("light_gray_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("lime_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("lime_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("loom", Instrument.BASS);
        BLOCK_INSTRUMENT.put("magenta_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("magenta_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_roots", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mangrove_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("mushroom_stem", Instrument.BASS);
        BLOCK_INSTRUMENT.put("note_block", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("oak_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("orange_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("orange_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pale_oak_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pink_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("pink_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("purple_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("purple_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("red_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("red_mushroom_block", Instrument.BASS);
        BLOCK_INSTRUMENT.put("red_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("smithing_table", Instrument.BASS);
        BLOCK_INSTRUMENT.put("soul_campfire", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("spruce_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("stripped_acacia_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("stripped_birch_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("stripped_cherry_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("stripped_crimson_hyphae", Instrument.BASS);
        BLOCK_INSTRUMENT.put("stripped_dark_oak_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("stripped_jungle_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("stripped_oak_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("stripped_pale_oak_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("stripped_spruce_wood", Instrument.BASS);
        BLOCK_INSTRUMENT.put("stripped_warped_hyphae", Instrument.BASS);
        BLOCK_INSTRUMENT.put("trapped_chest", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_door", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_fence", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_fence_gate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_hyphae", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_planks", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_pressure_plate", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_shelf", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_slab", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_trapdoor", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_wall_hanging_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("warped_wall_sign", Instrument.BASS);
        BLOCK_INSTRUMENT.put("white_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("white_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("yellow_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("yellow_wall_banner", Instrument.BASS);
        BLOCK_INSTRUMENT.put("gold_block", Instrument.BELL);
        BLOCK_INSTRUMENT.put("emerald_block", Instrument.BIT);
        BLOCK_INSTRUMENT.put("packed_ice", Instrument.CHIME);
        BLOCK_INSTRUMENT.put("soul_sand", Instrument.COW_BELL);
        BLOCK_INSTRUMENT.put("creeper_head", Instrument.CREEPER);
        BLOCK_INSTRUMENT.put("player_head", Instrument.CUSTOM_HEAD);
        BLOCK_INSTRUMENT.put("pumpkin", Instrument.DIDGERIDOO);
        BLOCK_INSTRUMENT.put("dragon_head", Instrument.DRAGON);
        BLOCK_INSTRUMENT.put("clay", Instrument.FLUTE);
        BLOCK_INSTRUMENT.put("black_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("blue_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("brown_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("cyan_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("gray_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("green_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("light_blue_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("light_gray_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("lime_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("magenta_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("orange_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("pink_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("purple_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("red_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("white_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("yellow_wool", Instrument.GUITAR);
        BLOCK_INSTRUMENT.put("beacon", Instrument.HAT);
        BLOCK_INSTRUMENT.put("black_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("blue_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("brown_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("conduit", Instrument.HAT);
        BLOCK_INSTRUMENT.put("cyan_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("glass", Instrument.HAT);
        BLOCK_INSTRUMENT.put("glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("gray_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("green_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("light_blue_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("light_gray_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("lime_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("magenta_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("orange_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("pink_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("purple_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("red_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("sea_lantern", Instrument.HAT);
        BLOCK_INSTRUMENT.put("white_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("yellow_stained_glass_pane", Instrument.HAT);
        BLOCK_INSTRUMENT.put("iron_block", Instrument.IRON_XYLOPHONE);
        BLOCK_INSTRUMENT.put("piglin_head", Instrument.PIGLIN);
        BLOCK_INSTRUMENT.put("glowstone", Instrument.PLING);
        BLOCK_INSTRUMENT.put("skeleton_skull", Instrument.SKELETON);
        BLOCK_INSTRUMENT.put("black_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("blue_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("brown_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("cyan_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("gravel", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("gray_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("green_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("heavy_core", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("light_blue_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("light_gray_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("lime_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("magenta_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("orange_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("pink_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("purple_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("red_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("red_sand", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("sand", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("suspicious_gravel", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("suspicious_sand", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("white_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("yellow_concrete_powder", Instrument.SNARE);
        BLOCK_INSTRUMENT.put("copper_block", Instrument.TRUMPET);
        BLOCK_INSTRUMENT.put("exposed_copper", Instrument.TRUMPET_EXPOSED);
        BLOCK_INSTRUMENT.put("oxidized_copper", Instrument.TRUMPET_OXIDIZED);
        BLOCK_INSTRUMENT.put("weathered_copper", Instrument.TRUMPET_WEATHERED);
        BLOCK_INSTRUMENT.put("wither_skeleton_skull", Instrument.WITHER_SKELETON);
        BLOCK_INSTRUMENT.put("bone_block", Instrument.XYLOPHONE);
        BLOCK_INSTRUMENT.put("zombie_head", Instrument.ZOMBIE);
        // wall-mounted heads inherit their floor variant's instrument (real vanilla:
        // WallSkullBlock registrations use wallVariant(FLOOR_BLOCK, true), copying Properties)
        BLOCK_INSTRUMENT.put("skeleton_wall_skull", Instrument.SKELETON);
        BLOCK_INSTRUMENT.put("wither_skeleton_wall_skull", Instrument.WITHER_SKELETON);
        BLOCK_INSTRUMENT.put("zombie_wall_head", Instrument.ZOMBIE);
        BLOCK_INSTRUMENT.put("player_wall_head", Instrument.CUSTOM_HEAD);
        BLOCK_INSTRUMENT.put("creeper_wall_head", Instrument.CREEPER);
        BLOCK_INSTRUMENT.put("dragon_wall_head", Instrument.DRAGON);
        BLOCK_INSTRUMENT.put("piglin_wall_head", Instrument.PIGLIN);
    }

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerBlockInteractEvent.class, e -> {
            if (!e.getBlock().key().value().equals("note_block")) return;
            e.setBlockingItemUse(true);
            int note = (parseNote(e.getBlock().getProperty("note")) + 1) % 25;
            Block updated = e.getBlock().withProperty("note", String.valueOf(note));
            e.getInstance().setBlock(e.getBlockPosition(), updated);
            playNote(e.getInstance(), e.getBlockPosition(), updated);
        });
    }

    private static int parseNote(String raw) {
        try {
            return raw == null ? 0 : Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** Real vanilla NoteBlock.getPitchFromNote: 2^((note-12)/12), a two-octave range. */
    public static float pitchFromNote(int note) {
        return (float) Math.pow(2.0, (note - 12) / 12.0);
    }

    /** NoteBlock.setInstrument: block above wins if it's a head/skull; else the block below. */
    public static Instrument instrumentFor(Instance instance, Point pos) {
        Instrument above = BLOCK_INSTRUMENT.getOrDefault(
                instance.getBlock(pos.add(0, 1, 0)).key().value(), Instrument.HARP);
        if (above.worksAboveNoteBlock()) return above;
        Instrument below = BLOCK_INSTRUMENT.getOrDefault(
                instance.getBlock(pos.sub(0, 1, 0)).key().value(), Instrument.HARP);
        return below.worksAboveNoteBlock() ? Instrument.HARP : below;
    }

    /**
     * NoteBlock.playNote: only sounds if nothing is stacked on top blocking it (unless the
     * instrument is itself a head, which doesn't block). No custom player-head NBT sound
     * data exists in this project, so CUSTOM_HEAD (plain player heads) plays nothing, matching
     * real vanilla's behavior when {@code getCustomSoundId} returns null.
     */
    public static void playNote(Instance instance, Point pos, Block state) {
        Instrument instrument = instrumentFor(instance, pos);
        boolean blocked = !instrument.worksAboveNoteBlock() && !instance.getBlock(pos.add(0, 1, 0)).isAir();
        if (blocked) return;
        if (instrument.hasCustomSound()) return;
        float pitch = instrument.isTunable() ? pitchFromNote(parseNote(state.getProperty("note"))) : 1.0f;
        instance.playSound(Sound.sound(instrument.sound, Sound.Source.RECORD, 3f, pitch),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }
}
