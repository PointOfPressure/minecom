package dev.pointofpressure.minecom.bench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus-text {@code /metrics} endpoint for the P0 benchmark harness
 * (MASTERPLAN §4, scripts/bench/). MSPT comes from Minestom's own
 * ServerTickMonitorEvent (fired every tick by ServerProcessImpl, 26.2, no
 * opt-in required); GC/heap/uptime come from java.lang.management. {@code
 * POST /metrics/reset} clears the tick-sample window so a bench run's
 * numbers cover exactly its own scenario, not whatever ran before it.
 * Deliberately coarse: a per-system tick breakdown is P1's consolidation
 * pass (the 38-task collapse), not this one — see MASTERPLAN §4 P0/P1.
 */
public final class Metrics {
    private Metrics() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(Metrics.class);

    /** Bounds memory if a scenario forgets to reset; 20 min of ticks at 20 TPS. */
    private static final int SAMPLE_CAP = 24_000;

    // thread: MSPT_SAMPLES/TICK_COUNT/windowStartNanos are written by the tick
    // thread (ServerTickMonitorEvent) and read by the HTTP server's handler
    // thread(s); ConcurrentLinkedDeque + AtomicLong need no external lock for
    // this append-only/snapshot-read shape.
    private static final ConcurrentLinkedDeque<Double> MSPT_SAMPLES = new ConcurrentLinkedDeque<>();
    private static final AtomicLong TICK_COUNT = new AtomicLong();
    private static volatile long windowStartNanos = System.nanoTime();

    public static void register(GlobalEventHandler events, int port) {
        events.addListener(ServerTickMonitorEvent.class, event -> {
            MSPT_SAMPLES.addLast(event.getTickMonitor().getTickTime());
            while (MSPT_SAMPLES.size() > SAMPLE_CAP) MSPT_SAMPLES.pollFirst();
            TICK_COUNT.incrementAndGet();
        });

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/metrics", Metrics::handleMetrics);
            server.createContext("/metrics/reset", Metrics::handleReset);
            server.setExecutor(null);
            server.start();
            LOGGER.info("Bench /metrics endpoint on :{}", port);
        } catch (IOException e) {
            LOGGER.warn("Could not start /metrics endpoint on :{} ({}) — bench scraping unavailable this run",
                    port, e.toString());
        }
    }

    private static void handleReset(HttpExchange exchange) throws IOException {
        MSPT_SAMPLES.clear();
        TICK_COUNT.set(0);
        windowStartNanos = System.nanoTime();
        respond(exchange, 200, "reset\n");
    }

    private static void handleMetrics(HttpExchange exchange) throws IOException {
        respond(exchange, 200, render());
    }

    private static String render() {
        List<Double> snapshot = new ArrayList<>(MSPT_SAMPLES);
        double[] sorted = snapshot.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double sum = 0.0;
        for (double v : sorted) sum += v;
        double avgMspt = sorted.length > 0 ? sum / sorted.length : 0.0;

        StringBuilder sb = new StringBuilder();

        sb.append("# TYPE minecom_tick_mspt summary\n");
        if (sorted.length > 0) {
            sb.append("minecom_tick_mspt{quantile=\"0.5\"} ").append(quantile(sorted, 0.50)).append('\n');
            sb.append("minecom_tick_mspt{quantile=\"0.95\"} ").append(quantile(sorted, 0.95)).append('\n');
            sb.append("minecom_tick_mspt{quantile=\"0.99\"} ").append(quantile(sorted, 0.99)).append('\n');
        }
        sb.append("minecom_tick_mspt_sum ").append(sum).append('\n');
        sb.append("minecom_tick_mspt_count ").append(sorted.length).append('\n');

        double tps = sorted.length > 0 ? Math.min(20.0, 1000.0 / avgMspt) : 20.0;
        sb.append("# TYPE minecom_tps gauge\nminecom_tps ").append(tps).append('\n');

        sb.append("# TYPE minecom_tick_total counter\nminecom_tick_total ").append(TICK_COUNT.get()).append('\n');

        sb.append("# TYPE minecom_players_online gauge\nminecom_players_online ")
                .append(MinecraftServer.getConnectionManager().getOnlinePlayers().size()).append('\n');

        Runtime rt = Runtime.getRuntime();
        sb.append("# TYPE minecom_heap_used_bytes gauge\nminecom_heap_used_bytes ")
                .append(rt.totalMemory() - rt.freeMemory()).append('\n');
        sb.append("# TYPE minecom_heap_max_bytes gauge\nminecom_heap_max_bytes ")
                .append(rt.maxMemory()).append('\n');

        sb.append("# TYPE minecom_gc_collections_total counter\n");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append("minecom_gc_collections_total{gc=\"").append(sanitize(gc.getName())).append("\"} ")
                    .append(gc.getCollectionCount()).append('\n');
        }
        sb.append("# TYPE minecom_gc_time_ms_total counter\n");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append("minecom_gc_time_ms_total{gc=\"").append(sanitize(gc.getName())).append("\"} ")
                    .append(gc.getCollectionTime()).append('\n');
        }

        sb.append("# TYPE minecom_uptime_seconds gauge\nminecom_uptime_seconds ")
                .append(ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0).append('\n');

        sb.append("# TYPE minecom_window_seconds gauge\nminecom_window_seconds ")
                .append((System.nanoTime() - windowStartNanos) / 1e9).append('\n');

        return sb.toString();
    }

    private static String sanitize(String gcName) {
        return gcName.replace('"', '\'');
    }

    private static double quantile(double[] sorted, double q) {
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
