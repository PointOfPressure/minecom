package dev.pointofpressure.minecom;

import com.google.gson.JsonObject;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Instance;

import java.util.function.BiConsumer;

/**
 * Persistence SPI for chunk-anchored subsystem state (docs/PERSISTENCE.md).
 * Each stateful subsystem contributes one adapter (registered through
 * {@link Persist#register(StateAdapter)} from its own register method), so
 * its static registries survive restarts without exposing their internals.
 * Entries are grouped by chunk into region shards; adapters never touch
 * files. Restore runs entry-by-entry as shards load, then finishRestore once
 * everything is in (for cross-entry links like double-chest shared views).
 */
public interface StateAdapter {

    /** Routing key stored with every entry, e.g. "chest", "hopper". */
    String kind();

    /** Emit every entry this subsystem currently owns in the given instance. */
    void collect(Instance instance, BiConsumer<Point, JsonObject> out);

    /** Restore one previously collected entry. */
    void restore(Instance instance, Point pos, JsonObject data);

    /** Called once after all shards restored; resolve cross-entry references here. */
    default void finishRestore(Instance instance) {}

    /** Drop every in-memory entry this adapter owns (playtest wipe hook). */
    void wipe();
}
