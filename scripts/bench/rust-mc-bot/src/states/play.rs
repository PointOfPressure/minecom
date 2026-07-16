use crate::packet_utils::Buf;
use crate::{Bot, Compression};

/// Clientbound Keep Alive (play)
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Clientbound_Keep_Alive_(play)
pub fn process_keep_alive_packet(buffer: &mut Buf, bot: &mut Bot, compression: &mut Compression) {
    bot.send_packet(write_keep_alive_packet(buffer.read_u64()), compression);
}

/// Disconnect (login/config/play)
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Disconnect_(login)
pub fn process_kick(buffer: &mut Buf, bot: &mut Bot, _compression: &mut Compression) {
    // the reason is an NBT text component, not a sized string — dump readable
    // ASCII from the raw payload so the actual reason text is visible
    let start = buffer.get_reader_index() as usize;
    let end = buffer.get_writer_index() as usize;
    let ascii: String = buffer.buffer[start..end].iter()
        .map(|&b| if (32..127).contains(&b) { b as char } else { '.' })
        .collect();
    println!("bot was kicked, reason payload: \"{}\"", ascii);
    bot.kicked = true;
}

/// Login (play)
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Login_(play)
pub fn process_join_game(buffer: &mut Buf, bot: &mut Bot, _compression: &mut Compression) {
    bot.entity_id = buffer.read_u32();
}

/// Synchronize Player Position
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Synchronize_Player_Position
pub fn process_teleport(buffer: &mut Buf, bot: &mut Bot, compression: &mut Compression) {
    // 26.2 layout (Minestom PlayerPositionAndLookPacket.SERIALIZER):
    // teleportId VAR_INT, position 3xf64, delta 3xf64, yaw f32, pitch f32,
    // flags INT. The old code skipped delta and read flags as one byte —
    // benign only while delta is Vec.ZERO (flags byte read as 0/absolute).
    let id = buffer.read_var_u32().0;
    let x = buffer.read_f64();
    let y = buffer.read_f64();
    let z = buffer.read_f64();
    let _delta_x = buffer.read_f64();
    let _delta_y = buffer.read_f64();
    let _delta_z = buffer.read_f64();
    let _yaw = buffer.read_f32();
    let _pitch = buffer.read_f32();
    let flags = buffer.read_u32();
    if flags & 0b10000 == 0 {
        bot.x = x;
    } else {
        bot.x += x;
    }
    if flags & 0b01000 == 0 {
        bot.y = y;
    } else {
        bot.y += y;
    }
    if flags & 0b00100 == 0 {
        bot.z = z;
    } else {
        bot.z += z;
    }
    bot.send_packet(write_tele_confirm(id), compression);
    bot.teleported = true;
    println!("{x}, {y}, {z}");
}

/// Chat Message
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Chat_Message
pub fn write_chat_message(message: &str) -> Buf {
    // ClientChatMessagePacket
    let mut buf = Buf::new();
    buf.write_packet_id(0x09);

    buf.write_sized_str(message);

    // 1.19 signing fields
    buf.write_u64(0); // timestamp
    buf.write_u64(0); // salt
    buf.write_bool(false); // has signature
    buf.write_var_u32(0); // count
    buf.write_bytes(&[0; 3]); // bitset
    buf.write_var_u32(0); // signature count

    buf
}

/// Swing Arm
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Swing_Arm
pub fn write_animation(off_hand: bool) -> Buf {
    // ClientAnimationPacket
    let mut buf = Buf::new();
    buf.write_packet_id(0x3F);
    buf.write_var_u32(if off_hand { 1 } else { 0 });

    buf
}

/// Player Action (serverbound)
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Player_Command
pub fn write_entity_action(entity_id: u32, action_id: u32, jump_boost: u32) -> Buf {
    // ClientEntityActionPacket
    let mut buf = Buf::new();
    buf.write_packet_id(0x2A);

    buf.write_var_u32(entity_id);
    buf.write_var_u32(action_id);
    buf.write_var_u32(jump_boost);

    buf
}

/// Set Held Item (serverbound)
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Set_Held_Item_(serverbound)
pub fn write_held_slot(slot: u16) -> Buf {
    // ClientHeldItemChangePacket
    let mut buf = Buf::new();
    buf.write_packet_id(0x35);

    buf.write_u16(slot);

    buf
}

/// Confirm Teleportation
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Confirm_Teleportation
pub fn write_tele_confirm(id: u32) -> Buf {
    // ClientTeleportConfirmPacket
    let mut buf = Buf::new();
    buf.write_packet_id(0x00);

    buf.write_var_u32(id);

    buf
}

/// Client Tick End — vanilla clients (1.21.2+) send this EVERY tick from the
/// moment they enter play state. Minestom 26.2's per-connection processing is
/// paced by client traffic; a bot that sends nothing until teleport-confirm
/// starves the connection and the server's queued writes (position sync,
/// chunks, keep-alive, even the kick) never flush — the silent-drop bug.
pub fn write_tick_end() -> Buf {
    // ClientTickEndPacket
    let mut buf = Buf::new();
    buf.write_packet_id(0x0D);
    buf
}

/// Serverbound Keep Alive (play)
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Serverbound_Keep_Alive_(play)
pub fn write_keep_alive_packet(id: u64) -> Buf {
    // ClientKeepAlivePacket
    let mut buf = Buf::new();
    buf.write_packet_id(0x1C);

    buf.write_u64(id);

    buf
}

/// Set Player Position
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Set_Player_Position
/// Position-only, no yaw/pitch fields — 0x1E is ClientPlayerPositionPacket in
/// minecom/Minestom's registry (confirmed by counting PacketVanilla.CLIENT_PLAY
/// entries), NOT ClientPlayerPositionAndRotationPacket (that's 0x1F). This used
/// to reuse write_pos's position+rotation layout under this ID, appending two
/// unwanted f32s (8 bytes) that minecom's packet reader logged as "not fully
/// read" on every single tick from every bot — benign per Minestom's own
/// length-prefixed framing, but real noise, and the bot never actually turns
/// (yaw/pitch were always 0.0 dummies), so there was never a reason to send
/// the rotation variant here at all.
pub fn write_current_pos(bot: &Bot) -> Buf {
    let mut buf = Buf::new();
    buf.write_packet_id(0x1E);

    buf.write_f64(bot.x);
    buf.write_f64(bot.y);
    buf.write_f64(bot.z);

    buf.write_bool(false);

    buf
}

/// Set Player Position and Rotation
/// https://minecraft.wiki/w/Java_Edition_protocol/Packets#Set_Player_Position_and_Rotation
pub fn write_pos(x: f64, y: f64, z: f64, yaw: f32, pitch: f32) -> Buf {
    // ClientPlayerPositionAndRotationPacket
    let mut buf = Buf::new();
    buf.write_packet_id(0x1F);

    buf.write_f64(x);
    buf.write_f64(y);
    buf.write_f64(z);

    buf.write_f32(yaw);
    buf.write_f32(pitch);

    buf.write_bool(false);

    buf
}
