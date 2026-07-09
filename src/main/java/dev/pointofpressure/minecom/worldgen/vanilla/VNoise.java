package dev.pointofpressure.minecom.worldgen.vanilla;

import java.util.List;

/**
 * Exact ports of vanilla's noise stack: ImprovedNoise (Perlin with the 16
 * simplex gradients), octaved PerlinNoise (per-octave md5 seeding), and
 * NormalNoise (two offset Perlin stacks averaged with the 1.0181... factor).
 */
public final class VNoise {
    private VNoise() {}

    /** Minimal random surface so Improved can run on Xoroshiro or legacy LCG randoms. */
    public interface Rand {
        double nextDouble();

        int nextInt(int bound);
    }

    private static final int[][] GRADIENT = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
            {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
    };

    // ------------------------------------------------------------------ ImprovedNoise

    public static final class Improved {
        private final byte[] p = new byte[256];
        public final double xo, yo, zo;

        public Improved(Rand random) {
            this.xo = random.nextDouble() * 256.0;
            this.yo = random.nextDouble() * 256.0;
            this.zo = random.nextDouble() * 256.0;
            for (int i = 0; i < 256; i++) p[i] = (byte) i;
            for (int i = 0; i < 256; i++) {
                int offset = random.nextInt(256 - i);
                byte tmp = p[i];
                p[i] = p[i + offset];
                p[i + offset] = tmp;
            }
        }

        private int p(int index) {
            return p[index & 0xFF] & 0xFF;
        }

        public double noise(double inX, double inY, double inZ) {
            return noise(inX, inY, inZ, 0, 0);
        }

        public double noise(double inX, double inY, double inZ, double yScale, double yFudge) {
            double x = inX + xo;
            double y = inY + yo;
            double z = inZ + zo;
            int xf = floor(x);
            int yf = floor(y);
            int zf = floor(z);
            double xr = x - xf;
            double yr = y - yf;
            double zr = z - zf;
            double yrFudge;
            if (yScale != 0.0) {
                double limit = yFudge >= 0.0 && yFudge < yr ? yFudge : yr;
                yrFudge = floor(limit / yScale + 1.0E-7F) * yScale;
            } else {
                yrFudge = 0.0;
            }
            return sampleAndLerp(xf, yf, zf, xr, yr - yrFudge, zr, yr);
        }

        private double sampleAndLerp(int x, int y, int z, double xr, double yr, double zr, double yrOriginal) {
            int x0 = p(x);
            int x1 = p(x + 1);
            int xy00 = p(x0 + y);
            int xy01 = p(x0 + y + 1);
            int xy10 = p(x1 + y);
            int xy11 = p(x1 + y + 1);
            double d000 = gradDot(p(xy00 + z), xr, yr, zr);
            double d100 = gradDot(p(xy10 + z), xr - 1, yr, zr);
            double d010 = gradDot(p(xy01 + z), xr, yr - 1, zr);
            double d110 = gradDot(p(xy11 + z), xr - 1, yr - 1, zr);
            double d001 = gradDot(p(xy00 + z + 1), xr, yr, zr - 1);
            double d101 = gradDot(p(xy10 + z + 1), xr - 1, yr, zr - 1);
            double d011 = gradDot(p(xy01 + z + 1), xr, yr - 1, zr - 1);
            double d111 = gradDot(p(xy11 + z + 1), xr - 1, yr - 1, zr - 1);
            double ax = smoothstep(xr);
            double ay = smoothstep(yrOriginal);
            double az = smoothstep(zr);
            return lerp3(ax, ay, az, d000, d100, d010, d110, d001, d101, d011, d111);
        }
    }

    private static double gradDot(int hash, double x, double y, double z) {
        int[] g = GRADIENT[hash & 15];
        return g[0] * x + g[1] * y + g[2] * z;
    }

    // ------------------------------------------------------------------ PerlinNoise (octaves)

    public static final class Perlin {
        private final Improved[] noiseLevels;
        private final List<Double> amplitudes;
        private final double lowestFreqInputFactor;
        private final double lowestFreqValueFactor;
        public final double maxValue;

        public Perlin(XRandom random, int firstOctave, List<Double> amplitudes) {
            this.amplitudes = amplitudes;
            int octaves = amplitudes.size();
            int zeroOctaveIndex = -firstOctave;
            this.noiseLevels = new Improved[octaves];
            XRandom.Positional positional = random.forkPositional();
            for (int i = 0; i < octaves; i++) {
                if (amplitudes.get(i) != 0.0) {
                    int octave = firstOctave + i;
                    noiseLevels[i] = new Improved(positional.fromHashOf("octave_" + octave));
                }
            }
            this.lowestFreqInputFactor = Math.pow(2.0, -zeroOctaveIndex);
            this.lowestFreqValueFactor = Math.pow(2.0, octaves - 1) / (Math.pow(2.0, octaves) - 1.0);
            this.maxValue = edgeValue(2.0);
        }

        private double edgeValue(double noiseValue) {
            double value = 0;
            double valueFactor = lowestFreqValueFactor;
            for (int i = 0; i < noiseLevels.length; i++) {
                if (noiseLevels[i] != null) value += amplitudes.get(i) * noiseValue * valueFactor;
                valueFactor /= 2.0;
            }
            return value;
        }

        public double getValue(double x, double y, double z) {
            return getValue(x, y, z, 0, 0);
        }

        public double getValue(double x, double y, double z, double yScale, double yFudge) {
            double value = 0;
            double factor = lowestFreqInputFactor;
            double valueFactor = lowestFreqValueFactor;
            for (int i = 0; i < noiseLevels.length; i++) {
                Improved noise = noiseLevels[i];
                if (noise != null) {
                    double v = noise.noise(wrap(x * factor), wrap(y * factor), wrap(z * factor),
                            yScale * factor, yFudge * factor);
                    value += amplitudes.get(i) * v * valueFactor;
                }
                factor *= 2.0;
                valueFactor /= 2.0;
            }
            return value;
        }
    }

    public static double wrap(double x) {
        return x - lfloor(x / 3.3554432E7 + 0.5) * 3.3554432E7;
    }

    // ------------------------------------------------------------------ NormalNoise

    public static final class Normal {
        private static final double INPUT_FACTOR = 1.0181268882175227;
        private final Perlin first;
        private final Perlin second;
        public final double valueFactor;
        public final double maxValue;

        public Normal(XRandom random, int firstOctave, List<Double> amplitudes) {
            this.first = new Perlin(random, firstOctave, amplitudes);
            this.second = new Perlin(random, firstOctave, amplitudes);
            int minOctave = Integer.MAX_VALUE;
            int maxOctave = Integer.MIN_VALUE;
            for (int i = 0; i < amplitudes.size(); i++) {
                if (amplitudes.get(i) != 0.0) {
                    minOctave = Math.min(minOctave, i);
                    maxOctave = Math.max(maxOctave, i);
                }
            }
            this.valueFactor = 0.16666666666666666 / expectedDeviation(maxOctave - minOctave);
            this.maxValue = (first.maxValue + second.maxValue) * valueFactor;
        }

        private static double expectedDeviation(int octaveSpan) {
            return 0.1 * (1.0 + 1.0 / (octaveSpan + 1));
        }

        public double getValue(double x, double y, double z) {
            double x2 = x * INPUT_FACTOR;
            double y2 = y * INPUT_FACTOR;
            double z2 = z * INPUT_FACTOR;
            return (first.getValue(x, y, z) + second.getValue(x2, y2, z2)) * valueFactor;
        }
    }

    // ------------------------------------------------------------------ math (Mth ports)

    static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    static long lfloor(double v) {
        long l = (long) v;
        return v < l ? l - 1 : l;
    }

    static double smoothstep(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    static double lerp(double a, double from, double to) {
        return from + a * (to - from);
    }

    static double lerp2(double ax, double ay, double v00, double v10, double v01, double v11) {
        return lerp(ay, lerp(ax, v00, v10), lerp(ax, v01, v11));
    }

    static double lerp3(double ax, double ay, double az,
                        double d000, double d100, double d010, double d110,
                        double d001, double d101, double d011, double d111) {
        return lerp(az, lerp2(ax, ay, d000, d100, d010, d110), lerp2(ax, ay, d001, d101, d011, d111));
    }
}
