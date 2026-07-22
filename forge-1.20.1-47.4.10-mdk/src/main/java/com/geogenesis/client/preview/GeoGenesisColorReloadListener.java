package com.geogenesis.client.preview;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * MC 资源重载监听器：从资源包覆盖 GeoPalette 默认配色（对齐 TFC 的数据驱动范式）。
 * - assets/geogenesis/colormap_preview/geogenesis.json：多色带停靠点
 * - assets/geogenesis/biome_colors.json：离散类→颜色
 * 无资源包时 GeoPalette 使用内置默认，行为不变。
 */
public class GeoGenesisColorReloadListener implements ResourceManagerReloadListener {

    private static final Logger LOGGER = LogManager.getLogger("GeoGenesisColor");
    private static final Gson GSON = new Gson();

    @Override
    public void onResourceManagerReload(ResourceManager rm) {
        loadColormaps(rm);
        loadDiscrete(rm);
        loadBiomeReal();
    }

    /** 从 MC Biome Registry 读取真实群系颜色 → 填充 BIOME_REAL 图层 */
    private void loadBiomeReal() {
        try {
            var biomeRegistry = net.minecraftforge.registries.ForgeRegistries.BIOMES;
            if (biomeRegistry == null) return;
            Map<String, int[]> colors = new HashMap<>();
            for (var entry : biomeRegistry.getEntries()) {
                ResourceLocation key = entry.getKey().location();
                var biome = entry.getValue();
                // 1.20.1 API: getWaterColor, getFoliageColorOverride (OptionalInt), getGrassColorOverride (OptionalInt)
                var effects = biome.getSpecialEffects();
                int waterColor = effects.getWaterColor();
                int foliageOpt = effects.getFoliageColorOverride().orElse(-1);
                int grassOpt = effects.getGrassColorOverride().orElse(-1);
                int color;
                if (grassOpt != -1) color = grassOpt;
                else if (foliageOpt != -1) color = foliageOpt;
                else if (key.getPath().contains("ocean") || key.getPath().contains("river")) color = waterColor;
                else color = 0x5B8731; // 默认绿
                colors.put(key.getPath(), new int[]{(color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF});
            }
            if (!colors.isEmpty()) {
                GeoPalette.setDiscreteColorsByName(GeoPalette.PreviewLayer.BIOME_REAL, colors);
                LOGGER.info("Loaded {} real biome colors from MC registry", colors.size());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load real biome colors: {}", e.getMessage());
        }
    }

    private void loadColormaps(ResourceManager rm) {
        ResourceLocation rl = new ResourceLocation("geogenesis:colormap_preview/geogenesis.json");
        rm.getResource(rl).ifPresent(res -> {
            try (InputStreamReader reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                JsonObject obj = GSON.fromJson(reader, JsonObject.class);
                if (obj == null || !obj.has("colormaps")) return;
                JsonObject cms = obj.getAsJsonObject("colormaps");
                for (Map.Entry<String, JsonElement> e : cms.entrySet()) {
                    float[][] stops = parseStops(e.getValue().getAsJsonArray());
                    if (stops.length >= 2) {
                        GeoPalette.registerColormap(e.getKey(), new ColorMap(e.getKey(), stops));
                    }
                }
                LOGGER.info("GeoGenesis colormaps reloaded from resource pack");
            } catch (Exception ex) {
                LOGGER.warn("Failed to load geogenesis colormaps: {}", ex.getMessage());
            }
        });
    }

    private void loadDiscrete(ResourceManager rm) {
        ResourceLocation rl = new ResourceLocation("geogenesis:biome_colors.json");
        rm.getResource(rl).ifPresent(res -> {
            try (InputStreamReader reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                JsonObject obj = GSON.fromJson(reader, JsonObject.class);
                if (obj == null || !obj.has("discrete")) return;
                JsonObject disc = obj.getAsJsonObject("discrete");
                for (Map.Entry<String, JsonElement> e : disc.entrySet()) {
                    GeoPalette.PreviewLayer layer = layerFromKey(e.getKey());
                    if (layer == null || layer.kind != GeoPalette.Kind.DISCRETE) continue;
                    JsonObject mapObj = e.getValue().getAsJsonObject();
                    Map<String, int[]> m = new HashMap<>();
                    for (Map.Entry<String, JsonElement> e2 : mapObj.entrySet()) {
                        JsonArray a = e2.getValue().getAsJsonArray();
                        m.put(e2.getKey(), new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                    }
                    GeoPalette.setDiscreteColorsByName(layer, m);
                }
                LOGGER.info("GeoGenesis discrete colors reloaded from resource pack");
            } catch (Exception ex) {
                LOGGER.warn("Failed to load geogenesis biome colors: {}", ex.getMessage());
            }
        });
    }

    private static float[][] parseStops(JsonArray arr) {
        float[][] out = new float[arr.size()][4];
        for (int i = 0; i < arr.size(); i++) {
            JsonArray s = arr.get(i).getAsJsonArray();
            out[i][0] = s.get(0).getAsFloat();
            out[i][1] = s.get(1).getAsFloat();
            out[i][2] = s.get(2).getAsFloat();
            out[i][3] = s.get(3).getAsFloat();
        }
        return out;
    }

    private static GeoPalette.PreviewLayer layerFromKey(String key) {
        return switch (key.toLowerCase()) {
            case "climatezone" -> GeoPalette.PreviewLayer.CLIMATE_ZONE;
            case "biome" -> GeoPalette.PreviewLayer.BIOME;
            case "terraintype" -> GeoPalette.PreviewLayer.TERRAIN_TYPE;
            default -> null;
        };
    }
}
