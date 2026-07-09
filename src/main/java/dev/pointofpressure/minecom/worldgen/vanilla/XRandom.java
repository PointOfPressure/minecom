package dev.pointofpressure.minecom.worldgen.vanilla;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Exact port of vanilla's Xoroshiro128++ random pipeline: seed upgrading with
 * the golden/silver ratio + Stafford mix, positional forking via Mth.getSeed,
 * and md5-of-string forking (used to seed each noise octave).
 */
public final class XRandom implements VNoise.Rand {
    /** Vanilla's DOUBLE_UNIT: a float constant widened once into a double field. */
    private static final double DOUBLE_UNIT = 1.110223E-16F;
    private long seedLo;
    private long seedHi;

    public XRandom(long seed) {
        // upgradeSeedTo128bit: xor silver ratio, add golden ratio, stafford-mix both
        long lo = seed ^ 0x6A09E667F3BCC909L;
        long hi = lo + 0x9E3779B97F4A7C15L;
        this.seedLo = mixStafford13(lo);
        this.seedHi = mixStafford13(hi);
        normalize();
    }

    public XRandom(long seedLo, long seedHi) {
        this.seedLo = seedLo;
        this.seedHi = seedHi;
        normalize();
    }

    private void normalize() {
        if ((seedLo | seedHi) == 0L) {
            seedLo = 0x9E3779B97F4A7C15L;
            seedHi = 0x6A09E667F3BCC909L;
        }
    }

    public static long mixStafford13(long z) {
        z = (z ^ z >>> 30) * -4658895280553007687L;
        z = (z ^ z >>> 27) * -7723592293110705685L;
        return z ^ z >>> 31;
    }

    public long nextLong() {
        long s0 = seedLo;
        long s1 = seedHi;
        long result = Long.rotateLeft(s0 + s1, 17) + s0;
        s1 ^= s0;
        seedLo = Long.rotateLeft(s0, 49) ^ s1 ^ s1 << 21;
        seedHi = Long.rotateLeft(s1, 28);
        return result;
    }

    private long nextBits(int bits) {
        return nextLong() >>> 64 - bits;
    }

    public int nextInt() {
        return (int) nextLong();
    }

    /** Vanilla's unbiased bounded nextInt (Lemire multiply-shift with rejection). */
    public int nextInt(int bound) {
        long randomBits = Integer.toUnsignedLong(nextInt());
        long multiplied = randomBits * bound;
        long fractional = multiplied & 4294967295L;
        if (fractional < bound) {
            for (int threshold = Integer.remainderUnsigned(~bound + 1, bound);
                 fractional < threshold;
                 fractional = multiplied & 4294967295L) {
                randomBits = Integer.toUnsignedLong(nextInt());
                multiplied = randomBits * bound;
            }
        }
        return (int) (multiplied >> 32);
    }

    public double nextDouble() {
        return nextBits(53) * DOUBLE_UNIT;
    }

    public boolean nextBoolean() {
        return (nextLong() & 1L) != 0L;
    }

    public int nextIntBetweenInclusive(int min, int max) {
        return nextInt(max - min + 1) + min;
    }

    public float nextFloat() {
        return (float) nextBits(24) * 5.9604645E-8F;
    }

    // ------------------------------------------------------------------ forking

    public Positional forkPositional() {
        return new Positional(nextLong(), nextLong());
    }

    /** RandomSource.fork(): a fresh stream seeded from two raw draws off this one. */
    public XRandom fork() {
        return new XRandom(nextLong(), nextLong());
    }

    public record Positional(long seedLo, long seedHi) {
        public XRandom at(int x, int y, int z) {
            long positionalSeed = blockSeed(x, y, z);
            return new XRandom(positionalSeed ^ seedLo, seedHi);
        }

        public XRandom fromHashOf(String name) {
            long[] hash = md5128(name);
            return new XRandom(hash[0] ^ seedLo, hash[1] ^ seedHi);
        }

        public XRandom fromSeed(long seed) {
            return new XRandom(seed ^ seedLo, seed ^ seedHi);
        }
    }

    /** Mth.getSeed. */
    public static long blockSeed(int x, int y, int z) {
        long seed = x * 3129871L ^ z * 116129781L ^ y;
        seed = seed * seed * 42317861L + seed * 11L;
        return seed >> 16;
    }

    static long[] md5128(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return new long[]{bytesToLong(hash, 0), bytesToLong(hash, 8)};
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long bytesToLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = value << 8 | bytes[offset + i] & 0xFF;
        }
        return value;
    }
}
