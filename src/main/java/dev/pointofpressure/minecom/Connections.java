package dev.pointofpressure.minecom;

import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.timer.TaskSchedule;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connection admission control — the V1/V2 hardening from
 * docs/SECURITY-HARDENING.md §4.1/§4.2. minecom runs offline mode on a direct
 * bind, so an unauthenticated remote is the primary attacker; Minestom's accept
 * loop caps frame size but imposes no per-IP connection cap, global cap, accept
 * rate limit, or pre-PLAY login deadline. This holder adds all four at the
 * earliest event Minestom exposes (AsyncPlayerPreLoginEvent, LOGIN state) plus a
 * player-cap gate at AsyncPlayerConfigurationEvent.
 *
 * Loopback and operator-configured trusted CIDRs are exempt from the per-IP
 * controls so the bench harness and a fronting proxy are never throttled
 * (§4.1.2). The global player cap applies to everyone, so it stays meaningful
 * behind a proxy. The admission decisions are pure functions ({@link #rateAllow},
 * {@link #perIpConcurrentAllow}, {@link #globalAllow}, {@link #exempt}) so they
 * are covered deterministically in SelfTest without real sockets.
 *
 * KNOWN GAP (docs/HANDOFF.md): a connection that completes the TCP handshake but
 * sends no protocol bytes never fires any Minestom event, so it is unreachable
 * from here — the pure idle-hold case needs an accept-side hook upstream. The
 * login deadline below covers a connection that reaches LOGIN and then stalls.
 */
public final class Connections {
    private Connections() {}

    // thread: all fields read/written on virtual login threads + the scheduler
    // thread; concurrent maps + per-bucket synchronization is the discipline.
    private static volatile int maxPlayers = intEnv("MINECOM_MAX_PLAYERS", 20);
    private static volatile int maxPerIp = intEnv("MINECOM_MAX_CONNECTIONS_PER_IP", 3);
    private static volatile int ratePerIp = intEnv("MINECOM_CONN_RATE_PER_IP", 10);
    private static volatile long rateWindowMs = intEnv("MINECOM_CONN_RATE_WINDOW_MS", 10_000);
    private static volatile long loginDeadlineMs = intEnv("MINECOM_LOGIN_DEADLINE_MS", 30_000);
    private static volatile List<Cidr> trusted = parseCidrs(System.getenv("MINECOM_TRUSTED_CIDRS"));

    /** Per-IP arrival timestamps within the current rate window. */
    private static final ConcurrentHashMap<InetAddress, ArrayDeque<Long>> ARRIVALS = new ConcurrentHashMap<>();

    public static int maxPlayers() { return maxPlayers; }

    /** Wire the admission gates. Called from Main (the real server) only. */
    public static void register(GlobalEventHandler events) {
        events.addListener(AsyncPlayerPreLoginEvent.class, event -> {
            PlayerConnection connection = event.getConnection();
            InetAddress ip = addressOf(connection.getRemoteAddress());
            if (ip != null && !rateAllow(ip, System.currentTimeMillis())) {
                connection.kick(Component.text("Connecting too fast — try again shortly."));
                return;
            }
            scheduleLoginDeadline(connection);
        });

        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            Player player = event.getPlayer();
            InetAddress ip = addressOf(player.getPlayerConnection().getRemoteAddress());
            // Global player cap counts already-online players; this joining player
            // is not yet in that set, so ">=" makes maxPlayers a hard ceiling.
            if (!globalAllow(MinecraftServer.getConnectionManager().getOnlinePlayers().size())) {
                player.kick(Component.text("Server is full."));
                return;
            }
            if (ip != null && !perIpConcurrentAllow(ip, onlineFromIp(ip))) {
                player.kick(Component.text("Too many connections from your address."));
            }
        });
    }

    private static void scheduleLoginDeadline(PlayerConnection connection) {
        if (loginDeadlineMs <= 0) return;
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            if (connection.getConnectionState() != ConnectionState.PLAY && connection.isOnline()) {
                connection.disconnect(); // stalled before reaching PLAY — reap it
            }
        }).delay(TaskSchedule.duration(Duration.ofMillis(loginDeadlineMs))).schedule();
    }

    // ---------------------------------------------------------------- decisions

    /** True if the address is loopback or inside a configured trusted CIDR. */
    public static boolean exempt(InetAddress ip) {
        if (ip == null || ip.isLoopbackAddress()) return true;
        for (Cidr c : trusted) if (c.matches(ip)) return true;
        return false;
    }

    /**
     * Token-bucket accept-rate gate: at most {@code ratePerIp} new connections
     * per {@code rateWindowMs} from one non-exempt address. Records the arrival
     * when it allows it. Release-free (a rate limit only counts arrivals), so it
     * needs no disconnect hook.
     */
    public static boolean rateAllow(InetAddress ip, long nowMs) {
        if (exempt(ip) || ratePerIp <= 0) return true;
        ArrayDeque<Long> window = ARRIVALS.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            long cutoff = nowMs - rateWindowMs;
            while (!window.isEmpty() && window.peekFirst() < cutoff) window.pollFirst();
            if (window.size() >= ratePerIp) return false;
            window.addLast(nowMs);
            return true;
        }
    }

    /** True if a further concurrent connection from this address is allowed. */
    public static boolean perIpConcurrentAllow(InetAddress ip, int currentFromIp) {
        if (exempt(ip) || maxPerIp <= 0) return true;
        return currentFromIp < maxPerIp;
    }

    /** True if another player may join given the current online count. */
    public static boolean globalAllow(int currentOnline) {
        if (maxPlayers <= 0) return true;
        return currentOnline < maxPlayers;
    }

    private static int onlineFromIp(InetAddress ip) {
        int n = 0;
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (ip.equals(addressOf(p.getPlayerConnection().getRemoteAddress()))) n++;
        }
        return n;
    }

    private static InetAddress addressOf(SocketAddress address) {
        return address instanceof InetSocketAddress isa ? isa.getAddress() : null;
    }

    // ---------------------------------------------------------------- config/test

    /** Deterministic config for SelfTest; resets the rate-window state too. */
    public static void configureForTest(int maxPlayers, int maxPerIp, int ratePerIp,
                                        long rateWindowMs, String trustedCidrs) {
        Connections.maxPlayers = maxPlayers;
        Connections.maxPerIp = maxPerIp;
        Connections.ratePerIp = ratePerIp;
        Connections.rateWindowMs = rateWindowMs;
        Connections.trusted = parseCidrs(trustedCidrs);
        ARRIVALS.clear();
    }

    private static int intEnv(String key, long def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return (int) def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return (int) def; }
    }

    private static List<Cidr> parseCidrs(String spec) {
        List<Cidr> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) return out;
        for (String part : spec.split(",")) {
            Cidr c = Cidr.parse(part.trim());
            if (c != null) out.add(c);
        }
        return out;
    }

    /** A parsed IPv4/IPv6 CIDR block for the trusted-address allowlist. */
    private record Cidr(byte[] network, int prefixBits) {
        static Cidr parse(String spec) {
            if (spec.isEmpty()) return null;
            try {
                int slash = spec.indexOf('/');
                String host = slash < 0 ? spec : spec.substring(0, slash);
                byte[] addr = InetAddress.getByName(host).getAddress();
                int prefix = slash < 0 ? addr.length * 8 : Integer.parseInt(spec.substring(slash + 1));
                if (prefix < 0 || prefix > addr.length * 8) return null;
                return new Cidr(addr, prefix);
            } catch (UnknownHostException | NumberFormatException e) {
                return null;
            }
        }

        boolean matches(InetAddress ip) {
            byte[] a = ip.getAddress();
            if (a.length != network.length) return false; // different family
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) if (a[i] != network[i]) return false;
            int rem = prefixBits % 8;
            if (rem == 0) return true;
            int mask = (0xFF << (8 - rem)) & 0xFF;
            return (a[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
