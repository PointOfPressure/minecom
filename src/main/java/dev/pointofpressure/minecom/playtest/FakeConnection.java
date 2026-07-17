package dev.pointofpressure.minecom.playtest;

import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.PlayerConnection;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/** Packet sink for headless playtest players. */
public final class FakeConnection extends PlayerConnection {
    public final AtomicInteger packetsSent = new AtomicInteger();
    // Last packet of each concrete type sent, keyed by class — lets a scenario inspect what
    // was actually sent (e.g. a MapDataPacket's decoded pixel buffer) without needing a real
    // client, matching this harness's "drive real events, assert real state" philosophy.
    private final java.util.Map<Class<?>, SendablePacket> lastByType = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void sendPacket(SendablePacket packet) {
        packetsSent.incrementAndGet();
        lastByType.put(packet.getClass(), packet);
    }

    @SuppressWarnings("unchecked")
    public <T extends SendablePacket> T lastOfType(Class<T> type) {
        return (T) lastByType.get(type);
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 0);
    }
}
