package dev.pointofpressure.minecom.regions;

/**
 * A cross-region effect expressed as a value delivered to a target region's
 * inbox and applied at the next tick boundary — the message model of the P2
 * multi-core design (docs/MULTICORE.md §2).
 *
 * <p>A message is a pure value: it carries copied/serialized data or stable
 * identifiers (UUID, Point, block state, ItemStack snapshot) and NEVER a live
 * reference into the source region's mutable state. That is what makes the
 * hand-off free of shared mutability (MULTICORE.md §2.0).
 *
 * <p>This interface exists as of M1 but has no implementations yet — the
 * message layer goes live in M3 (MULTICORE.md §6). Concrete messages
 * (EntityHandoff, ItemTransfer, RedstoneUpdate, …) are enumerated in
 * MULTICORE.md §2 and land with M3.
 */
public interface RegionMessage {

    /**
     * Identity of the region this message is applied to, at that region's next
     * tick boundary. Ordering across all messages in one boundary is
     * deterministic (MULTICORE.md §2.7), independent of thread scheduling.
     */
    String targetRegionId();
}
