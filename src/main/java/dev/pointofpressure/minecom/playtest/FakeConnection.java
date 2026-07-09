package dev.pointofpressure.minecom.playtest;

import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.PlayerConnection;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/** Packet sink for headless playtest players. */
public final class FakeConnection extends PlayerConnection {
    public final AtomicInteger packetsSent = new AtomicInteger();

    @Override
    public void sendPacket(SendablePacket packet) {
        packetsSent.incrementAndGet();
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 0);
    }
}
