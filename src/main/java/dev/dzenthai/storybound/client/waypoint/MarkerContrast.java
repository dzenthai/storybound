package dev.dzenthai.storybound.client.waypoint;

/**
 * How a waypoint's colour is shown in the world: no darker than reads against the icons' dark outline and the font's
 * dark shadow, which stay the same for every colour. A colour darker than {@link #MIN_LIGHTNESS} (OKLab lightness,
 * which follows how bright a colour looks) is raised to it with its hue kept and as much of its chroma as the screen
 * can show at that lightness: black shows grey, dark red red, purple a lighter purple. Lighter colours are shown as
 * chosen; the maps always get the colour as chosen (October 29, 2026; a light shadow and rim under dark colours were
 * tried first and broke the markers' one look).
 */
public final class MarkerContrast {
    /** The least lightness a marker's colour is shown with (OKLab, 0 black to 1 white). */
    static final double MIN_LIGHTNESS = 0.65;

    private MarkerContrast() {}

    /** The colour as markers show it (opaque). */
    public static int readable(int rgb) {
        double[] lab = toOklab(rgb);
        if (lab[0] >= MIN_LIGHTNESS) {
            return 0xFF000000 | (rgb & 0xFFFFFF);
        }
        double chroma = Math.hypot(lab[1], lab[2]);
        double hue = Math.atan2(lab[2], lab[1]);
        // The most chroma the screen can show at that lightness, by halving the range (it fits at 0: a grey).
        double low = 0;
        double high = chroma;
        if (!inGamut(fromOklab(MIN_LIGHTNESS, high * Math.cos(hue), high * Math.sin(hue)))) {
            for (int i = 0; i < 24; i++) {
                double middle = (low + high) / 2;
                if (inGamut(fromOklab(MIN_LIGHTNESS, middle * Math.cos(hue), middle * Math.sin(hue)))) {
                    low = middle;
                } else {
                    high = middle;
                }
            }
            high = low;
        }
        return encode(fromOklab(MIN_LIGHTNESS, high * Math.cos(hue), high * Math.sin(hue)));
    }

    /** OKLab lightness of a colour, 0 to 1. */
    static double lightness(int rgb) {
        return toOklab(rgb)[0];
    }

    private static double[] toOklab(int rgb) {
        double r = linear((rgb >> 16) & 0xFF);
        double g = linear((rgb >> 8) & 0xFF);
        double b = linear(rgb & 0xFF);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[] {
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }

    /** Linear sRGB, possibly out of 0..1. */
    private static double[] fromOklab(double lightness, double a, double b) {
        double l = Math.pow(lightness + 0.3963377774 * a + 0.2158037573 * b, 3);
        double m = Math.pow(lightness - 0.1055613458 * a - 0.0638541728 * b, 3);
        double s = Math.pow(lightness - 0.0894841775 * a - 1.2914855480 * b, 3);
        return new double[] {
                4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s};
    }

    private static boolean inGamut(double[] linear) {
        for (double channel : linear) {
            if (channel < -1e-6 || channel > 1 + 1e-6) {
                return false;
            }
        }
        return true;
    }

    private static int encode(double[] linear) {
        return 0xFF000000 | channel(linear[0]) << 16 | channel(linear[1]) << 8 | channel(linear[2]);
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int channel(double linear) {
        double c = Math.max(0, Math.min(1, linear));
        double encoded = c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return (int) Math.round(encoded * 255);
    }
}
