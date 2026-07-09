package dev.pointofpressure.minecom.playtest;

import net.minestom.server.entity.Player;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;

/** Player with test-controllable physics state. */
public final class TestPlayer extends Player {
    public TestPlayer(PlayerConnection connection, GameProfile profile) {
        super(connection, profile);
    }

    public void setOnGroundState(boolean value) {
        this.onGround = value;
    }
}
