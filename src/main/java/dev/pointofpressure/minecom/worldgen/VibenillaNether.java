package dev.pointofpressure.minecom.worldgen;

import net.minestom.server.instance.generator.Generator;
import rocks.minestom.worldgen.WorldGenerators;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline-only bridge to the adopted vibenilla Nether generator
 * (rocks.minestom:worldgen, Apache-2.0, pinned @ ffaafa1 — see NOTICE,
 * docs/AUDIT.md, docs/TIER4-NETHER-DESIGN.md "ADOPT").
 *
 * <p>Used ONLY by the region-diff harness ({@link GenRegions} "nether_vibenilla"
 * token) to measure an adopted bit-exact Nether against minecom's cached vanilla
 * ground truth. This is NOT wired into the live server (Bootstrap keeps
 * {@link NetherGen}); the cutover is a later, separately-gated step.
 *
 * <p>vibenilla's {@code WorldGenerators(rootPath, seed)} reads a vanilla
 * datapack (the {@code data/} tree from the server jar). Mojang data is never
 * committed (same rule as {@code vanilla-src/}), so this class self-provisions
 * it: on first use it extracts {@code data/**} from
 * {@code ~/mc-26.2/versions/26.2/server-26.2.jar} into a cache OUTSIDE the repo
 * ({@code ~/mc-26.2/datapack}) and reuses it thereafter.
 */
public final class VibenillaNether {
    private VibenillaNether() {}

    private static final Path HOME = Path.of(System.getProperty("user.home"));
    /** Bundler-unpack server jar (same path the harness scripts use). */
    private static final Path SERVER_JAR =
            HOME.resolve("mc-26.2/versions/26.2/server-26.2.jar");
    /** Extracted-datapack cache root (contains {@code data/}); outside the repo. */
    private static final Path DATAPACK_ROOT = HOME.resolve("mc-26.2/datapack");

    /** The adopted vibenilla Nether {@link Generator} keyed off {@code seed}. */
    public static Generator generator(long seed) {
        Path root = ensureDatapack();
        return new WorldGenerators(root, seed).nether();
    }

    /**
     * Extract {@code data/**} from the server jar into {@link #DATAPACK_ROOT}
     * on first use; return the root (the directory that contains {@code data/}).
     */
    private static Path ensureDatapack() {
        Path dataDir = DATAPACK_ROOT.resolve("data");
        if (Files.isDirectory(dataDir)) {
            return DATAPACK_ROOT;
        }
        if (!Files.isRegularFile(SERVER_JAR)) {
            throw new IllegalStateException(
                    "vibenilla nether needs the vanilla datapack, but the server jar is "
                            + "missing: " + SERVER_JAR + " (provision ~/mc-26.2 first)");
        }
        try (ZipFile zip = new ZipFile(SERVER_JAR.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("data/")) {
                    continue;
                }
                Path out = DATAPACK_ROOT.resolve(name).normalize();
                if (!out.startsWith(DATAPACK_ROOT)) {
                    throw new IOException("zip entry escapes datapack root: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (var in = zip.getInputStream(entry)) {
                        Files.copy(in, out);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to extract vanilla datapack from " + SERVER_JAR, e);
        }
        return DATAPACK_ROOT;
    }
}
