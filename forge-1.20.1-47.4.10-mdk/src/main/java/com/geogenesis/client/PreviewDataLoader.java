package com.geogenesis.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class PreviewDataLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("geogenesis/PreviewDataLoader");
    private static final Gson GSON = new Gson();

    private static final Map<String, Integer> biomeColors = new HashMap<>();
    private static int[] elevationColormap = null;
    private static int elevationMin = -64;
    private static int elevationMax = 320;

    public static void load() {
        loadBiomeColors();
        loadElevationColormap();
    }

    private static void loadBiomeColors() {
        biomeColors.clear();
        try (InputStream is = PreviewDataLoader.class.getResourceAsStream("/data/geogenesis/worldgen/biome_colors.json")) {
            if (is == null) {
                LOGGER.warn("biome_colors.json not found, using fallback colors");
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject colorObj = entry.getValue().getAsJsonObject();
                int r = colorObj.get("r").getAsInt();
                int g = colorObj.get("g").getAsInt();
                int b = colorObj.get("b").getAsInt();
                biomeColors.put(entry.getKey(), 0xFF000000 | (r << 16) | (g << 8) | b);
            }
            LOGGER.info("Loaded {} biome colors", biomeColors.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load biome_colors.json", e);
        }
    }

    private static void loadElevationColormap() {
        try (InputStream is = PreviewDataLoader.class.getResourceAsStream("/data/geogenesis/colormap/elevation.json")) {
            if (is == null) {
                LOGGER.warn("elevation.json not found");
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            int min = root.has("min") ? root.get("min").getAsInt() : -64;
            int max = root.has("max") ? root.get("max").getAsInt() : 320;
            elevationMin = min;
            elevationMax = max;
            int range = max - min;

            var stops = root.getAsJsonArray("stops");
            float[] positions = new float[stops.size()];
            int[] colors = new int[stops.size()];
            for (int i = 0; i < stops.size(); i++) {
                JsonObject stop = stops.get(i).getAsJsonObject();
                positions[i] = stop.get("pos").getAsFloat();
                String hex = stop.get("color").getAsString();
                colors[i] = parseHexColor(hex);
            }

            elevationColormap = new int[range];
            for (int y = 0; y < range; y++) {
                float t = (float) y / (float) range;
                elevationColormap[y] = sampleGradient(t, positions, colors);
            }
            LOGGER.info("Loaded elevation colormap: {} entries ({} to {})", range, min, max);
        } catch (Exception e) {
            LOGGER.error("Failed to load elevation colormap", e);
        }
    }

    public static int getBiomeColor(String biomeId) {
        Integer color = biomeColors.get(biomeId);
        if (color != null) return color;
        int hash = biomeId.hashCode();
        return 0x808080 | ((hash & 0x3F) << 16) | ((hash & 0x7E) << 8) | (hash & 0x7F);
    }

    public static int getElevationColor(float hNorm) {
        if (elevationColormap == null) return 0x808080;
        hNorm = Math.max(0f, Math.min(1f, hNorm));
        int idx = (int) (hNorm * (elevationColormap.length - 1));
        idx = Math.max(0, Math.min(elevationColormap.length - 1, idx));
        return elevationColormap[idx];
    }

    private static int sampleGradient(float t, float[] positions, int[] colors) {
        if (t <= positions[0]) return colors[0];
        if (t >= positions[positions.length - 1]) return colors[colors.length - 1];
        for (int i = 0; i < positions.length - 1; i++) {
            if (t >= positions[i] && t < positions[i + 1]) {
                float local = (t - positions[i]) / (positions[i + 1] - positions[i]);
                return lerpColor(colors[i], colors[i + 1], local);
            }
        }
        return colors[colors.length - 1];
    }

    private static int lerpColor(int c1, int c2, float t) {
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int r = (int)(r1 + (r2 - r1) * t);
        int g = (int)(g1 + (g2 - g1) * t);
        int b = (int)(b1 + (b2 - b1) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int parseHexColor(String hex) {
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() == 6) {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return 0xFF808080;
    }
}
