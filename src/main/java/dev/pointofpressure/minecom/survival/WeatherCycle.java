package dev.pointofpressure.minecom.survival;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;
import net.minestom.server.timer.TaskSchedule;

import java.util.Random;

/** Vanilla-ish weather cycle: long clear stretches, occasional rain for 3-8 minutes. */
public final class WeatherCycle {
    private WeatherCycle() {}

    private static final Random RANDOM = new Random();
    private static int rainTicksLeft;

    public static void start(Instance instance) {
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            if (rainTicksLeft > 0) {
                rainTicksLeft -= 100;
                if (rainTicksLeft <= 0 && instance.getWeather().isRaining()) {
                    instance.setWeather(Weather.CLEAR);
                }
            } else if (!instance.getWeather().isRaining() && RANDOM.nextDouble() < 0.01) {
                rainTicksLeft = 3600 + RANDOM.nextInt(6000);
                instance.setWeather(Weather.RAIN);
            }
        }).repeat(TaskSchedule.tick(100)).schedule();
    }

    /** Force state (for /weather and persistence). */
    public static void setRaining(Instance instance, boolean raining) {
        rainTicksLeft = raining ? 3600 + RANDOM.nextInt(6000) : 0;
        instance.setWeather(raining ? Weather.RAIN : Weather.CLEAR);
    }

    public static boolean isRaining(Instance instance) {
        return instance.getWeather().isRaining();
    }
}
