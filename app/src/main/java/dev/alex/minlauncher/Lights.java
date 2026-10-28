package dev.alex.minlauncher;

import java.util.Collection;
import java.util.List;

/** Pure logic behind {@link LightPanel}: no Android or JSON types, so it runs in unit tests. */
final class Lights {

    private Lights() { }

    /** What a set of lights can do, from their supported_color_modes. */
    static final class Support {
        final boolean brightness, temp, color;
        Support(boolean brightness, boolean temp, boolean color) {
            this.brightness = brightness; this.temp = temp; this.color = color;
        }
    }

    /** One swatch: a colour temperature (kelvin > 0) or an RGB colour, and how it is drawn. */
    static final class Swatch {
        final int kelvin, rgb, shown;
        private Swatch(int kelvin, int rgb, int shown) { this.kelvin = kelvin; this.rgb = rgb; this.shown = shown; }
        static Swatch white(int kelvin, int shown) { return new Swatch(kelvin, 0, shown); }
        static Swatch color(int rgb) { return new Swatch(0, rgb, rgb); }
    }

    static final Swatch[] WHITES = {
            Swatch.white(2700, 0xFFFFB46B), Swatch.white(4000, 0xFFFFE4C4), Swatch.white(6500, 0xFFF2F5FF),
    };
    static final Swatch[] COLORS = {
            Swatch.color(0xFFFF0000), Swatch.color(0xFFFF8000), Swatch.color(0xFFFFDC00),
            Swatch.color(0xFF00FF00), Swatch.color(0xFF00FFFF), Swatch.color(0xFF0000FF),
            Swatch.color(0xFFA000FF), Swatch.color(0xFFFF00A0),
    };

    /** True if there is at least one entity and every one is a light (light groups are too). */
    static boolean allLights(List<String> entityIds) {
        if (entityIds.isEmpty()) return false;
        for (String id : entityIds) if (!id.startsWith("light.")) return false;
        return true;
    }

    /**
     * The union of the lights' color modes. None reported (say every light is
     * unavailable) offers everything, and Home Assistant says what it refuses.
     */
    static Support support(Collection<String> modes) {
        if (modes.isEmpty()) return new Support(true, true, true);
        boolean brightness = false, temp = false, color = false;
        for (String m : modes) {
            switch (m) {
                case "onoff":
                    break;
                case "color_temp":
                    temp = true;
                    brightness = true;
                    break;
                case "hs": case "xy": case "rgb": case "rgbw": case "rgbww":
                    color = true;
                    brightness = true;
                    break;
                default:   // "brightness", "white"
                    brightness = true;
            }
        }
        return new Support(brightness, temp, color);
    }

    /** Home Assistant's 0–255 brightness as a percentage; off is 0. */
    static int percent(boolean on, int brightness) {
        if (!on) return 0;
        return Math.max(1, Math.min(100, Math.round(brightness * 100f / 255)));
    }

    /** A step of the brightness bar, kept within 0–100. */
    static int step(int percent, int delta) {
        return Math.max(0, Math.min(100, percent + delta));
    }
}
