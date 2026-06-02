package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * 独立地形预览工具（不依赖Minecraft类，直接生成PNG）
 * 使用测试工具的 ImprovedNoise，参数与 mod 的 NoiseEngine 同步
 */
public class StandalonePreview {

    // 地形生成高度256，建筑高度320。后续512/1024档只需改此处
    static final int SEA_LEVEL = 63;
    static final int MIN_Y = -64;
    static final int MAX_Y = 256; // 地形最大生成高度
    static final float SEA_NORM = (float)(SEA_LEVEL - MIN_Y) / (MAX_Y - MIN_Y); // 127/320≈0.397

    // ---- 噪声实例（匹配 NoiseEngine） ----
    final ImprovedNoise warpLargeNoise;
    final ImprovedNoise warpMediumNoise;
    final ImprovedNoise fractalLarge;
    final ImprovedNoise fractalMedium;
    final ImprovedNoise detailNoise;
    final ImprovedNoise continentNoise;
    final ImprovedNoise climateNoise;
    final ImprovedNoise moistureNoise;
    final ImprovedNoise valleyLarge;
    final long seed;

    public StandalonePreview(int seed) {
        this.seed = seed;
        Random rng = new Random(seed);
        warpLargeNoise   = new ImprovedNoise(rng.nextLong());
        warpMediumNoise  = new ImprovedNoise(rng.nextLong());
        fractalLarge     = new ImprovedNoise(rng.nextLong());
        fractalMedium    = new ImprovedNoise(rng.nextLong());
        detailNoise      = new ImprovedNoise(rng.nextLong());
        continentNoise   = new ImprovedNoise(rng.nextLong());
        climateNoise     = new ImprovedNoise(rng.nextLong());
        moistureNoise    = new ImprovedNoise(rng.nextLong());
        valleyLarge      = new ImprovedNoise(rng.nextLong());
    }

    private float[] domainWarp(float x, float z, float strength) {
        float wx = (float) warpLargeNoise.noise(x * 0.0005, 31.7, z * 0.0005) * strength;
        float wz = (float) warpLargeNoise.noise(x * 0.0005 + 500, 97.3, z * 0.0005 + 500) * strength;
        wx += (float) warpMediumNoise.noise(x * 0.002, 13.7, z * 0.002) * strength * 0.4f;
        wz += (float) warpMediumNoise.noise(x * 0.002 + 300, 57.9, z * 0.002 + 300) * strength * 0.4f;
        return new float[]{x + wx, z + wz};
    }

    private float fbm(ImprovedNoise noise, float x, float z) {
        float sum = 0;
        float yOff = (seed % 1000) * 0.001f;
        for (int i = 0; i < 3; i++) {
            float y = yOff + i * 3.7f;
            sum += (float) noise.noise(x, y, z);
            x *= 2f; z *= 2f;
        }
        return sum / 3f;
    }

    private float getYOffset(float x, float z) {
        float o = (float) warpLargeNoise.noise(x * 0.0001, 17.3, z * 0.0001) * 50f;
        o += (float) warpMediumNoise.noise(x * 0.003, 31.7, z * 0.003) * 15f;
        o += (float) warpLargeNoise.noise(x * 0.01 + 300, 53.9, z * 0.01 + 300) * 5f;
        return o;
    }

    float sampleTerrainBase(float wx, float wz) {
        float[] w = domainWarp(wx, wz, 200f);  // 增加扭曲强度
        float px = w[0], pz = w[1];
        float baseY = (seed % 1000) * 0.001f;

        // 8层 FBm 噪声，与原版 SimpleHydrology 一致
        // frequency 倍增, scale 衰减 (factor=0.6)
        float val = 0;
        float frequency = 1.0f / 512f;  // 降低起始频率，增加细节
        float scale = 0.6f;
        float ampSum = 0;
        for (int i = 0; i < 8; i++) {
            float y = baseY + getYOffset(px * frequency, pz * frequency) + i * 1.5f;
            val += (float) fractalLarge.noise(px * frequency, y, pz * frequency) * scale;
            ampSum += scale;
            frequency *= 2.1f;
            scale *= 0.6f;
        }
        val /= ampSum;

        return (val + 1f) * 0.5f;
    }

    float sampleContinentRaw(float wx, float wz) {
        // 域扭曲海岸线：在高频加细节使海岸锯齿化
        float[] w = domainWarp(wx, wz, 200f);
        float baseY = (seed % 1000) * 0.001f;
        float y0 = baseY + getYOffset(wx * 0.0012f, wz * 0.0012f) + 3.7f;
        float y1 = baseY + getYOffset(wx * 0.00252f, wz * 0.00252f) + 5.2f;
        float y2 = baseY + getYOffset(wx * 0.005f, wz * 0.005f) + 7.0f;
        float val = 0;
        val += (float) continentNoise.noise(w[0] * 0.0012, y0, w[1] * 0.0012) * 0.5f;
        val += (float) continentNoise.noise(w[0] * 0.00252, y1, w[1] * 0.00252) * 0.3f;
        val += (float) continentNoise.noise(w[0] * 0.005, y2, w[1] * 0.005) * 0.2f; // 高频细节→海岸锯齿
        return Math.max(-1f, Math.min(1f, val));
    }

    float sampleTemperature(float wx, float wz) {
        float baseY = (seed % 1000) * 0.001f;
        float y0 = baseY + getYOffset(wx * 0.0012f, wz * 0.0012f) + 3.7f;
        float y1 = baseY + getYOffset(wx * 0.00252f, wz * 0.00252f) + 5.2f;
        float y2 = baseY + getYOffset(wx * 0.005f, wz * 0.005f) + 7.0f;
        float base = (float)(climateNoise.noise(wx * 0.0012, y0, wz * 0.0012) * 1.0f
                           + climateNoise.noise(wx * 0.00252, y1, wz * 0.00252) * 0.6f
                           + climateNoise.noise(wx * 0.005, y2, wz * 0.005) * 0.3f)
                   / 1.2f;  // 小除数→大振幅→更多冷热极端
        base = (float)Math.max(-1, Math.min(1, base));
        base = (base + 1f) * 0.5f;
        float yw = baseY + getYOffset(wx * 0.0008f, wz * 0.0008f) + 7.0f;
        float wt = (float)(warpLargeNoise.noise(wx * 0.0008, yw, wz * 0.0008) * 800);
        float yw2 = baseY + getYOffset((wx + wt) * 0.0012f, (wz + wt) * 0.0012f) + 7.0f;
        float warped = (float)(climateNoise.noise((wx + wt) * 0.0012, yw2, (wz + wt) * 0.0012) * 1.0f
                             + climateNoise.noise((wx + wt) * 0.00252, yw2 + 1.5f, (wz + wt) * 0.00252) * 0.6f
                             + climateNoise.noise((wx + wt) * 0.005, yw2 + 3.0f, (wz + wt) * 0.005) * 0.3f)
                     / 1.2f;
        warped = (float)Math.max(-1, Math.min(1, warped));
        warped = (warped + 1f) * 0.5f;
        return (float)Math.max(0.05, Math.min(0.95, base * 0.4f + warped * 0.6f));
    }

    // 湿度：大陆性双方向 + 噪声 + 温度，全幅范围[0.05, 0.95]
    float sampleMoisture(float wx, float wz, float continent01, float temp) {
        float baseY = (seed % 1000) * 0.001f;
        float y0 = baseY + getYOffset(wx * 0.0012f, wz * 0.0012f) + 7.0f;
        float n = (float)moistureNoise.noise(wx * 0.0012, y0, wz * 0.0012);
        float moist = 0.40f + n * 0.20f;              // 低基座+强噪声±0.20
        moist += (1f - continent01) * 0.30f;           // 沿海 +0.30
        moist -= continent01 * 0.15f;                  // 内陆 -0.15
        moist += Math.max(0, temp - 0.6f) * 0.10f;     // 暖区微增湿
        moist -= Math.max(0, 0.5f - temp) * 0.15f;     // 冷区减湿
        return (float)Math.max(0.05, Math.min(0.95, moist));
    }

    float sampleElevation(float wx, float wz) {
        float e = 0;
        for (int i = 0; i < 4; i++) {
            float freq = (float)Math.pow(2.1, i);
            e += (float) detailNoise.noise(wx * 0.004 * freq, 7f + i * 3f, wz * 0.004 * freq) * (float)Math.pow(0.5, i);
        }
        return (float) ((e + 1.0) * 0.5);
    }

    float sampleTerrainDetail(float wx, float wz) {
        return fbm(detailNoise, wx * 0.015f, wz * 0.015f);
    }

    float sampleValleyLarge(float wx, float wz) {
        float[] w = domainWarp(wx, wz, 20f);
        float n = 0;
        n += (float) valleyLarge.noise(w[0] * 0.08, 1f, w[1] * 0.08) * 1f;
        n += (float) valleyLarge.noise(w[0] * 0.16, 3f, w[1] * 0.16) * 0.5f;
        return Math.abs(n / 1.5f);
    }

    // 权重函数（与NoiseEngine对应）
    float samplePlateauWeight(float wx, float wz) {
        float n = fbm(fractalLarge, wx * 0.002f, wz * 0.002f);
        return (n + 1f) * 0.5f;
    }
    float sampleKarstWeight(float wx, float wz) {
        float n = fbm(fractalMedium, wx * 0.003f + 777, wz * 0.003f + 777);
        return (n + 1f) * 0.5f;
    }
    float sampleDanxiaWeight(float wx, float wz) {
        float n = fbm(fractalMedium, wx * 0.004f + 111, wz * 0.004f + 222);
        return (n + 1f) * 0.5f;
    }
    float sampleGlacierWeight(float wx, float wz) {
        float n = fbm(fractalMedium, wx * 0.003f + 333, wz * 0.004f + 444);
        return (n + 1f) * 0.5f;
    }

    float computeHeight(float wx, float wz) {
        float c = sampleContinentRaw(wx, wz);
        float t = sampleTerrainBase(wx, wz);     // [0,1]
        float r = sampleElevation(wx, wz);        // [0,1]

        // 简化的直接地形：t为基础轮廓，r为起伏加剧
        // 使用t^0.6让低值更平、高值成峰
        float terrainPow = (float)Math.pow(t, 0.6);
        float mountainFactor = 1f + r * 1.2f;    // 烈度放大山脉
        float bt = terrainPow * mountainFactor * 0.5f;
        bt = Math.min(1f, bt);

        // 气候
        float temp = sampleTemperature(wx, wz);
        float moist = sampleMoisture(wx, wz, (c + 1f) * 0.5f, temp);

        // 高原
        float pw = samplePlateauWeight(wx, wz);
        float pa = ss(pw);
        float pl = 0;
        if (bt > 0.4f + r * 0.2f && pa > 0.01f) {
            float ex = (bt - (0.4f + r * 0.2f)) / (1f - (0.4f + r * 0.2f));
            pl = ((0.4f + r * 0.2f) + ex * 0.3f - bt) * pa;
        }

        // 喀斯特
        float kw = sampleKarstWeight(wx, wz);
        float ka = ss(kw) * (1f - ss(c / 0.5f)) * ss(r - 0.3f) * (1f - ss((r - 0.8f) / 0.2f));
        float kl = 0;
        if (ka > 0.01f) {
            float pk = Math.max(0, sampleTerrainDetail(wx, wz)) * t * 0.6f;
            kl = pk * ka;
        }

        // 丹霞
        float dw = sampleDanxiaWeight(wx, wz);
        float da = ss(dw) * ss(temp - 0.55f) * ss(1f - moist) * ss(bt - 0.2f);
        float ds = 0;
        if (da > 0.01f) {
            float lh = 0.025f;
            float st = Math.round(bt / lh) * lh;
            float sl = Math.abs(t - sampleTerrainBase(wx + 2, wz));
            ds = (st - bt) * da * ss(sl * 10f);
        }

        // 冰川
        float gw = sampleGlacierWeight(wx, wz);
        float ga = ss(gw) * ss(1f - temp) * ss(r - 0.6f);
        float gm = 0;
        if (ga > 0.01f) {
            float v = sampleValleyLarge(wx, wz);
            float vc = 1f - Math.abs(v * 2f - 1f);
            float uf = vc * 0.12f;
            float pc = Math.max(0, bt - 0.7f) * vc * 0.3f;
            gm = (uf - pc) * ga * 0.5f;
        }

        float h = bt + pl + kl + ds + gm;
        h = Math.max(0, Math.min(1, h));

        // 原版 SimpleHydrology 风格：地形乘以大陆轮廓因子
        // 大陆轮廓因子 d = 0.1 + 0.5*(1 + erf(2*scale))
        // 海洋区域 d->0，地形被压平；陆地区域 d->1，地形保留
        float continentScale = fbm(fractalMedium, wx * 0.001f + 999, wz * 0.001f + 999);
        float d = 0.1f + 0.5f * (1.0f + erf(2f * continentScale));

        // 归一化到 [0,1]
        float ls = Math.max(0, (c - 0.05f) / 0.95f);
        float aboveSea = 1f - SEA_NORM;
        float baseLift = ls * aboveSea * 0.25f;

        // 地形高度乘以大陆因子，海洋区域变平
        float shapeHeight = h * d * aboveSea * 0.55f;
        float lh = SEA_NORM + baseLift + shapeHeight;

        float oceanDepthFactor = 32f * SEA_NORM / (SEA_LEVEL - (float)MIN_Y);
        float od = c < 0.05f ? oceanDepthFactor * (1f - ss((c + 1f) / 1.05f)) : 0;
        float oh = SEA_NORM - od;

        float lm;
        if (c <= 0.0f) lm = 0;
        else if (c >= 0.05f) lm = 1;
        else { float tt = c / 0.05f; lm = tt * tt * (3 - 2 * tt); }

        return Math.max(0, Math.min(1, oh * (1 - lm) + lh * lm));
    }

    // 纯地形高度（无海洋凹陷）- 用于第一轮生成
    // 使用完整 [0,1] 范围，低谷汇聚河流，山脊分隔流域
    // 参考 SimpleHydrology：FBM 噪声归一化后直接作为地形
    float computeHeightPure(float wx, float wz) {
        float t = sampleTerrainBase(wx, wz);     // [0,1] FBM噪声（8层）
        float r = sampleElevation(wx, wz);        // [0,1] 细节起伏

        float terrainPow = (float)Math.pow(t, 0.6);
        float mountainFactor = 1f + r * 1.2f;
        float h = terrainPow * mountainFactor * 0.5f;

        return Math.max(0f, Math.min(1f, h));
    }

    // 添加海洋凹陷 - 用于第二轮生成
    float computeHeightWithOcean(float wx, float wz, float pureHeight) {
        float c = sampleContinentRaw(wx, wz);

        // 增加海洋深度，让海洋区域明显低于海平面
        // 原公式：oceanDepthFactor = 32 * SEA_NORM / (SEA_LEVEL - MIN_Y) ≈ 0.1
        // 修改为更大的深度
        float oceanDepthFactor = 0.3f;  // 直接设置深度因子
        float od = c < 0.05f ? oceanDepthFactor * (1f - ss((c + 1f) / 1.05f)) : 0;
        float oh = SEA_NORM - od;

        float lm;
        if (c <= 0.0f) lm = 0;
        else if (c >= 0.05f) lm = 1;
        else { float tt = c / 0.05f; lm = tt * tt * (3 - 2 * tt); }

        return Math.max(0, Math.min(1, oh * (1 - lm) + pureHeight * lm));
    }

    private static float ss(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3f - 2f * t);
    }

    // 误差函数 erf(x) = 2/sqrt(pi) * integral(0 to x of e^(-t^2) dt)
    static float erf(float x) {
        float a1 =  0.254829592f;
        float a2 = -0.284496736f;
        float a3 =  1.421413741f;
        float a4 = -1.453152027f;
        float a5 =  1.061405429f;
        float p  =  0.3275911f;

        int sign = (x >= 0) ? 1 : -1;
        x = Math.abs(x);

        float t = 1.0f / (1.0f + p * x);
        float y = 1.0f - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * (float)Math.exp(-x * x);

        return sign * y;
    }

    public void generateAndSave(String filePath, int worldSize, int scale) throws Exception {
        int mapSize = worldSize / scale;
        System.out.println("Generating " + mapSize + "x" + mapSize + " preview...");
        long t0 = System.nanoTime();

        BufferedImage img = new BufferedImage(mapSize, mapSize, BufferedImage.TYPE_INT_RGB);

        float minH = 1, maxH = 0;
        float[][] hm = new float[mapSize][mapSize];
        for (int z = 0; z < mapSize; z++) {
            for (int x = 0; x < mapSize; x++) {
                hm[z][x] = computeHeight(x * scale, z * scale);
                if (hm[z][x] < minH) minH = hm[z][x];
                if (hm[z][x] > maxH) maxH = hm[z][x];
            }
        }
        float range = Math.max(maxH - minH, 0.01f);

        for (int z = 0; z < mapSize; z++)
            for (int x = 0; x < mapSize; x++) {
                int wx = x * scale, wz = z * scale;
                float c = sampleContinentRaw(wx, wz);
                float temp = sampleTemperature(wx, wz);
                float moist = sampleMoisture(wx, wz, (c+1f)*0.5f, temp);
                img.setRGB(x, z, colorFor(c, temp, moist, hm[z][x]));
            }

        File f = new File(filePath);
        ImageIO.write(img, "png", f);
        float ms = (System.nanoTime() - t0) / 1e6f;
        System.out.println("Saved: " + f.getAbsolutePath());
        System.out.println("  Time: " + String.format("%.0f", ms) + "ms");
        System.out.println("  Seed: " + seed + "  Scale: " + scale + "  Range: " + String.format("%.3f", minH) + " ~ " + String.format("%.3f", maxH));
    }

    // TerraForged风格：离散群系类型+固定颜色（不插值）
    static final int BIOME_DEEP_OCEAN  = -3;
    static final int BIOME_SHALLOW     = -2;
    static final int BIOME_BEACH       = -1;
    static final int BIOME_TUNDRA      = 0;
    static final int BIOME_TAIGA       = 1;
    static final int BIOME_GRASSLAND   = 2;
    static final int BIOME_FOREST      = 3;
    static final int BIOME_RAINFOREST  = 4;
    static final int BIOME_SAVANNA     = 5;
    static final int BIOME_DESERT      = 6;
    static final int BIOME_ALPINE      = 7;

    static final int[] BIOME_COLORS = {
        rgb(200,210,210),   // TUNDRA   冻原灰白
        rgb(80, 130,90),    // TAIGA    寒带深绿
        rgb(170,190,110),   // GRASSLAND草原黄绿
        rgb(90, 165,80),    // FOREST   森林翠绿
        rgb(40, 130,65),    // RAINFOREST雨林墨绿
        rgb(195,165,100),   // SAVANNA  稀树草原棕
        rgb(220,195,90),    // DESERT   沙漠金黄
        rgb(190,185,195),   // ALPINE   高山灰
    };

    int classifyBiome(float continent, float temp, float moist, float height) {
        // 海洋
        if (continent < -0.20f) return BIOME_DEEP_OCEAN;
        if (continent < 0.0f)   return BIOME_SHALLOW;
        if (continent < 0.05f)  return BIOME_BEACH;
        // 高山
        if (height > 0.65f) return BIOME_ALPINE;
        // 陆地：温度+湿度→离散群系
        if (temp < 0.3f) return moist < 0.3f ? BIOME_TUNDRA : BIOME_TAIGA;
        if (temp < 0.55f) {
            if (moist < 0.3f) return BIOME_GRASSLAND;
            if (moist < 0.6f) return BIOME_FOREST;
            return BIOME_RAINFOREST;
        }
        if (moist < 0.3f) return BIOME_DESERT;
        if (moist < 0.6f) return BIOME_SAVANNA;
        return BIOME_RAINFOREST;
    }

    // 原版MC群系图（放宽沙漠/丛林阈值，配合新湿度范围）
    int minecraftBiomeColor(float continent, float temp, float moist, float height, float relief) {
        if (continent < -0.20f) return rgb(20, 60, 120);
        if (continent < 0.0f)   return rgb(40, 100, 160);
        if (continent < 0.05f)  return rgb(190, 180, 60);

        float elevCool = Math.max(0, height - 0.45f) * 0.4f;
        float et = Math.max(0.01f, temp - elevCool);

        // 雪峰/高山
        if (height > 0.70f) {
            if (et < 0.35f) return rgb(220, 220, 235);
            return rgb(150, 155, 160);
        }

        // === 按有效温度+湿度分群系 ===
        if (et < 0.25f) {
            if (moist < 0.35f) return rgb(195, 200, 210);  // 雪原
            return rgb(130, 160, 150);                       // 雪针叶林
        }
        if (et < 0.50f) {
            if (moist < 0.30f) return rgb(150, 170, 100);   // 平原
            if (moist < 0.55f) return rgb(115, 150, 135);   // 针叶林
            return rgb(80, 150, 70);                          // 森林
        }
        if (et < 0.70f) {
            if (moist < 0.30f) return rgb(150, 170, 100);   // 平原
            if (moist < 0.55f) return rgb(120, 170, 85);    // 森林
            return rgb(95, 150, 115);                         // 沼泽
        }
        // 热带/亚热带 (et >= 0.70)
        if (moist < 0.30f) return rgb(220, 190, 80);         // 沙漠
        if (moist < 0.50f) return rgb(195, 165, 95);         // 稀树草原
        if (moist < 0.65f) return rgb(140, 170, 85);         // 热带森林
        return rgb(50, 130, 70);                               // 丛林
    }

    // 保留备用（气候分类着色）
    int colorFor(float continent, float temp, float moist, float height) {
        int biome = classifyBiome(continent, temp, moist, height);
        if (biome < 0) {
            if (biome == BIOME_DEEP_OCEAN) return rgb(8, 45, 95);
            if (biome == BIOME_SHALLOW) return rgb(25, 100, 170);
            return rgb(195, 180, 70);
        }
        int c = BIOME_COLORS[biome];
        float bright = 0.85f + height * 0.2f;
        int r = (int)Math.min(255, ((c>>16)&0xFF) * bright);
        int g = (int)Math.min(255, ((c>>8)&0xFF) * bright);
        int b = (int)Math.min(255, (c&0xFF) * bright);
        return (r<<16) | (g<<8) | b;
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    public static void main(String[] args) throws Exception {
        int seed = args.length > 0 ? Integer.parseInt(args[0]) : 273651;
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int worldSize = 1024;
        String path = args.length > 2 ? args[2] : "standalone_preview.png";
        new StandalonePreview(seed).generateAndSave(path, worldSize, scale);
    }
}
