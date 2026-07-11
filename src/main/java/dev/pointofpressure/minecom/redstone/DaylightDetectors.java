package dev.pointofpressure.minecom.redstone;

import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;

/**
 * DaylightDetectorBlock: POWER follows effective sky brightness, re-evaluated
 * every 20 game ticks (the block-entity ticker cadence in vanilla; the block
 * entity itself is stateless there, so a tracked-position sweep is equivalent).
 *
 * Effective brightness is skyLight - skyDarken, where skyDarken is
 * 15 - SKY_LIGHT_LEVEL: the overworld day timeline (day.json
 * gameplay/sky_light_level) multiplies the 15.0 default down to 4.0 across
 * ticks 11867-13670 and back across 22330-133, and rain/thunder alpha-blend
 * the result toward 4.0 (WeatherAttributes, alpha 5/16 and 135/256).
 *
 * Non-inverted mode scales brightness by cos(sun angle) after a one-shot 20%
 * pull toward noon (updateSignalStrength); the sun angle is the overworld
 * visual/sun_angle timeline track: one full 360deg turn per day, 0deg at noon
 * (tick 6000), eased through cubic-bezier(0.362, 0.241, 0.638, 0.759).
 * Inverted mode (right-click toggle) is a plain 15 - brightness.
 */
final class DaylightDetectors {
    private DaylightDetectors() {}

    /** Recompute POWER for a detector; returns false if the block is gone (untrack it). */
    static boolean recompute(Instance instance, Point pos) {
        Block block = instance.getBlock(pos);
        if (!block.key().value().equals("daylight_detector")) return false;
        int target = signal(instance, pos, block);
        if (!String.valueOf(target).equals(block.getProperty("power"))) {
            instance.setBlock(pos, block.withProperty("power", String.valueOf(target)));
            Redstone.neighborsChanged(pos);
            Redstone.neighborsChanged(pos.add(0, -1, 0));
        }
        return true;
    }

    /** DaylightDetectorBlock.updateSignalStrength. */
    static int signal(Instance instance, Point pos, Block block) {
        int target = effectiveSkyBrightness(instance, pos);
        if ("true".equals(block.getProperty("inverted"))) {
            target = 15 - target;
        } else if (target > 0) {
            // vanilla uses its 65536-entry sin table here; at amplitude <=15 the
            // table's error never moves Math.round, so exact cos is equivalent
            float angle = (float) Math.toRadians(sunAngleDegrees(instance.getTime()));
            float noonward = angle < (float) Math.PI ? 0.0F : (float) (Math.PI * 2);
            angle += (noonward - angle) * 0.2F;
            target = Math.round(target * (float) Math.cos(angle));
        }
        return Math.max(0, Math.min(15, target));
    }

    /** LevelReader.getEffectiveSkyBrightness: getBrightness(SKY, pos) - getSkyDarken(). */
    static int effectiveSkyBrightness(Instance instance, Point pos) {
        // the detector is a 6/16-tall non-occluding block; Minestom's engine may
        // treat the occupied cell as dark, so read the cell above as well
        int sky = Math.max(
                instance.getSkyLight(pos.blockX(), pos.blockY(), pos.blockZ()),
                instance.getSkyLight(pos.blockX(), pos.blockY() + 1, pos.blockZ()));
        return Math.max(0, sky - skyDarken(instance));
    }

    /** Level.updateSkyBrightness: (int) (15 - SKY_LIGHT_LEVEL) with weather layers applied. */
    static int skyDarken(Instance instance) {
        float level = 15.0F * daySkyLightFactor(instance.getTime());
        Weather weather = instance.getWeather();
        float thunder = weather.thunderLevel();
        float rain = Math.max(0, weather.rainLevel() - thunder);
        if (rain > 0) level = lerp(rain, level, lerp(0.3125F, level, 4.0F));
        if (thunder > 0) level = lerp(thunder, level, lerp(0.52734375F, level, 4.0F));
        return (int) (15.0F - level);
    }

    /** day.json gameplay/sky_light_level track: multiplier 1.0 by day, 4/15 at night. */
    private static float daySkyLightFactor(long dayTime) {
        long t = Math.floorMod(dayTime, 24000);
        if (t < 133) t += 24000;
        if (t <= 11867) return 1.0F;
        if (t <= 13670) return lerp((t - 11867) / 1803.0F, 1.0F, 0.26666668F);
        if (t <= 22330) return 0.26666668F;
        return lerp((t - 22330) / 1803.0F, 0.26666668F, 1.0F);
    }

    /** day.json visual/sun_angle track: 360deg/day anchored at noon=0, bezier-eased. */
    static float sunAngleDegrees(long dayTime) {
        float t = Math.floorMod(dayTime - 6000, 24000) / 24000.0F;
        return 360.0F * bezierEase(t);
    }

    /** Cubic-bezier ease (0.362, 0.241, 0.638, 0.759): solve x(u)=t, return y(u). */
    private static float bezierEase(float t) {
        double lo = 0, hi = 1, u = t;
        for (int i = 0; i < 24; i++) {
            if (cubic(u, 0.362, 0.638) < t) lo = u; else hi = u;
            u = (lo + hi) / 2;
        }
        return (float) cubic(u, 0.241, 0.759);
    }

    private static double cubic(double u, double p1, double p2) {
        double v = 1 - u;
        return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u;
    }

    private static float lerp(float t, float from, float to) {
        return from + t * (to - from);
    }
}
