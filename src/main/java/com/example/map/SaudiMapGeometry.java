package com.example.map;

import java.util.Locale;

/**
 * Saudi Arabia map projection used by Mapview (lat/lon → SVG coordinates).
 */
public final class SaudiMapGeometry {
    public static final double MAP_WIDTH = 860;
    public static final double MAP_HEIGHT = 660;
    public static final double MAP_PAD_X = 130;
    public static final double MAP_PAD_Y = 80;

    private static final String[][] CITY_ALIASES = {
            {"riyadh", "riyad", "ar riyadh"},
            {"jeddah", "jiddah", "jedda", "jiddha"},
            {"makkah", "mecca", "mecca city"},
            {"madinah", "medina", "al madinah"},
            {"dammam", "ad dammam"},
            {"khobar", "al khobar"},
            {"jubail", "al jubail"},
            {"tabuk"},
            {"abha"},
            {"taif", "at taif"},
            {"yanbu"},
            {"najran"},
            {"jazan", "jizan", "gizan"},
            {"hail", "ha'il"},
            {"buraidah", "buraydah", "qassim", "al qassim"},
            {"khamis mushait", "khamis mushayt"},
            {"neom"}
    };

    private static final double[][] CITY_COORDS = {
            {24.7136, 46.6753}, // riyadh
            {21.4858, 39.1925}, // jeddah
            {21.3891, 39.8579}, // makkah
            {24.5247, 39.5692}, // madinah
            {26.4207, 50.0888}, // dammam
            {26.2172, 50.1971}, // khobar
            {27.0174, 49.6225}, // jubail
            {28.3838, 36.5550}, // tabuk
            {18.2164, 42.5053}, // abha
            {21.2703, 40.4158}, // taif
            {24.0895, 38.0618}, // yanbu
            {17.5651, 44.2289}, // najran
            {16.8892, 42.5511}, // jazan
            {27.5114, 41.7208}, // hail
            {26.3260, 43.9750}, // buraidah/qassim
            {18.3000, 42.7333}, // khamis mushait
            {28.1120, 35.0760}  // neom
    };

    private SaudiMapGeometry() {
    }

    public static String viewBox() {
        return "0 0 " + ((int) MAP_WIDTH) + " " + ((int) MAP_HEIGHT);
    }

    /** Returns city index in CITY_COORDS, or -1 if no known city is found in the venue name. */
    public static int resolveCityIndex(String label) {
        if (label == null || label.isBlank()) {
            return -1;
        }
        String key = normalizeVenueLabel(label);

        int bestIndex = -1;
        int bestAliasLength = -1;
        for (int i = 0; i < CITY_ALIASES.length; i++) {
            for (String alias : CITY_ALIASES[i]) {
                if (key.equals(alias) || containsWholePhrase(key, alias)) {
                    if (alias.length() > bestAliasLength) {
                        bestAliasLength = alias.length();
                        bestIndex = i;
                    }
                }
            }
        }
        return bestIndex;
    }

    public static double[] projectCity(int cityIndex) {
        double[] latLon = CITY_COORDS[cityIndex];
        return projectSaudiMap(latLon[0], latLon[1]);
    }

    public static double[] projectSaudiMap(double lat, double lon) {
        double minLon = 33.8;
        double maxLon = 56.0;
        double minLat = 15.8;
        double maxLat = 32.4;
        double innerW = MAP_WIDTH - (2 * MAP_PAD_X);
        double innerH = MAP_HEIGHT - (2 * MAP_PAD_Y);
        double x = MAP_PAD_X + ((lon - minLon) / (maxLon - minLon)) * innerW;
        double y = MAP_PAD_Y + ((maxLat - lat) / (maxLat - minLat)) * innerH;
        return new double[]{x, y};
    }

    public static double[] offsetMapPoint(double x, double y, int indexAtCity) {
        if (indexAtCity <= 0) {
            return new double[]{x, y};
        }
        double angle = (indexAtCity * 1.15) + 0.4;
        double radius = 34.0 + (indexAtCity - 1) * 18.0;
        return new double[]{x + Math.cos(angle) * radius, y + Math.sin(angle) * radius};
    }

    public static String saudiOutlinePath() {
        double[][] points = {
                {29.35, 34.95}, {28.10, 34.60}, {26.20, 36.40}, {24.10, 37.80}, {22.20, 38.90},
                {20.00, 40.40}, {18.20, 41.50}, {16.90, 42.55}, {16.40, 42.80}, {17.20, 44.40},
                {17.80, 47.20}, {18.90, 50.20}, {19.80, 52.20}, {22.00, 55.20}, {24.50, 51.60},
                {26.40, 50.20}, {27.50, 49.20}, {28.50, 48.40}, {29.10, 46.60}, {30.00, 44.00},
                {31.20, 41.50}, {32.15, 39.20}, {31.80, 37.20}, {30.50, 36.00}
        };
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < points.length; i++) {
            double[] xy = projectSaudiMap(points[i][0], points[i][1]);
            path.append(i == 0 ? "M" : " L")
                    .append(String.format(Locale.US, "%.1f,%.1f", xy[0], xy[1]));
        }
        path.append(" Z");
        return path.toString();
    }

    private static String normalizeVenueLabel(String label) {
        return label.trim().toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsWholePhrase(String text, String phrase) {
        int idx = 0;
        while ((idx = text.indexOf(phrase, idx)) >= 0) {
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int end = idx + phrase.length();
            boolean endOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            idx++;
        }
        return false;
    }
}
