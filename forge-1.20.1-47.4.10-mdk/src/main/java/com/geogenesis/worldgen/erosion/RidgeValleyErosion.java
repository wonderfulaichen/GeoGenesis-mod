package com.geogenesis.worldgen.erosion;

import com.geogenesis.config.GeoGenesisConfig;

/**
 * 脊-谷条纹侵蚀（骨架层）—— 梯度对齐条纹滤镜，纯局部算子。
 *
 * <p><b>算法溯源</b>：移植自 Rune Skovbo Johansen "Fast and Gorgeous Erosion Filter" (2026-03) 与
 * Luke Mitchell 的 Burst C# 实现（lpmitchell/AdvancedTerrainErosion，MPL-2.0 + MIT）。
 * 本文件为<b>独立重写</b>，仅参考其算法结构（PhacelleNoise / ErosionFilter / Hash2），并标注出处，
 * 未直接拷贝源文件。算法机制详见 {@code docs/05-分析诊断/03-脊谷条纹侵蚀骨架-ridge-valley-erosion.md}。</p>
 *
 * <p><b>作用</b>：在低分辨率地形网格上生成「大山脊基本型」骨架——沿地形梯度挤出脊-谷条纹网络，
 * 把圆包状噪声切成多峰山脊；随后由 {@link ErosionEngine#runErosionOnFlat} 的粒子侵蚀做细节打磨。
 * 本类<b>纯数学工具、不依赖 CellGenerator</b>：输入未平滑的低分辨率高度网格，输出同维度高度增量。</p>
 *
 * <p><b>无缝原理</b>：每点独立 evaluate，条纹方向按全局对齐细胞的确定性 pivot 旋转 + 邻细胞钟形权重混合 →
 * 同一世界坐标的 coarseDelta 与任意 tile 算出的值一致，跨 tile 天然无接缝（复用现有 Tile Context Chain）。</p>
 */
public final class RidgeValleyErosion {

    private RidgeValleyErosion() {}

    private static final float TAU = (float) (2.0 * Math.PI);

    // ===== 骨架层配置（从 GeoGenesisConfig 读取覆盖；stylistic 参数用代码常量初值）=====
    public static class RidgeConfig {
        public boolean enabled = true;
        public float strength = 0.08f;        // 单 octave 侵蚀强度（直接，不乘 scale；≤0.09 避免 maxDeltaPerCell=0.15 截断）
        public float cellWorldSize = 100f;    // 骨架特征尺度（世界块）= 条纹细胞世界尺寸
        public float stripeFreq = 1.2f;       // 细胞内条纹频率（sideDir 幅度，↑密度）
        public int octaves = 4;               // gully 层级（主脊+次级脊+细沟，spacing=2 下不混叠）
        public float gullyWeight = 0.5f;      // 坡度累积权重
        public float detail = 1.0f;           // 堆叠淡出幂次（PowInv power，↓更密小沟）
        public float normalization = 0.5f;    // 条纹幅度归一化度
        public float gain = 0.6f;             // 每 octave 强度衰减
        public float lacunarity = 2.0f;       // 每 octave 频率倍增
        public float[] rounding = {0.1f, 0.0f, 0.1f, 2.0f};
        public float[] onset = {1.25f, 1.25f, 2.8f, 1.5f};
        public float[] assumedSlope = {0.7f, 1.0f};
        public int seed = 1337;

        /** 从 Forge 配置读取骨架参数（字段缺失时回退默认值）。 */
        public static RidgeConfig fromConfig(GeoGenesisConfig cfg) {
            RidgeConfig c = new RidgeConfig();
            if (cfg != null) {
                c.enabled = cfgBool(cfg.erosionRidgeEnabled, true);
                c.strength = (float) cfgDbl(cfg.erosionRidgeStrength, 0.10);
                c.cellWorldSize = (float) cfgDbl(cfg.erosionRidgeScale, 100.0);
                c.stripeFreq = (float) cfgDbl(cfg.erosionRidgeCellScale, 1.2);
                c.octaves = cfgInt(cfg.erosionRidgeOctaves, 4);
                c.gullyWeight = (float) cfgDbl(cfg.erosionRidgeGullyWeight, 0.5);
                // 脊线软硬（0=尖锐 V 形，0.5=默认，1=圆滑 U 形）
                double softness = cfgDbl(cfg.erosionRidgeRounding, 0.5);
                c.rounding[0] = (float) (0.02 + softness * 0.18);  // ridge rounding
                c.rounding[1] = (float) (softness * 0.12);          // crease (valley) rounding
                // 细节密度（high=主脊干净，low=满布小沟）
                c.detail = (float) cfgDbl(cfg.erosionRidgeDetail, 1.0);
            }
            return c;
        }
    }

    /**
     * 在未平滑的低分辨率网格 rawLowRes 上计算粗侵蚀骨架 delta（同维度返回）。
     *
     * @param rawLowRes 未平滑地形高度（e 场），维度 extLR×extLR
     * @param spacing   粗采世界间距（块）
     * @param startX/startZ 网格 (0,0) 对应的世界坐标
     * @param seaE      海平面 e 值（海洋侧乘 landMask 抑制，避免海床造假山脊）
     * @param cfg       骨架配置
     * @return 与 rawLowRes 同维度的高度增量 delta
     */
    public static float[][] computeCoarseDelta(float[][] rawLowRes, int extLR,
                                                int spacing, int startX, int startZ,
                                                float seaE, RidgeConfig cfg) {
        float[][] delta = new float[extLR][extLR];
        float cellGridFreq = 1.0f / Math.max(1f, cfg.cellWorldSize); // 条纹细胞世界尺寸 = cellWorldSize
        // 【全局对称参考面】源码 fadeTarget = inverse_lerp(valleyAlt, peakAlt, h)*2-1，
        // 跨真实高程对称映射（谷=-1、峰=+1），脊-谷下切/抬升幅度对称。
        // 本项目 h=terrainE∈[0,0.9]，用**全局固定中点 LAND_REF=0.30** + 半幅 LAND_HALF=0.30：
        //   (h-0.30)/0.30 → e=0(谷底)→-1, e=0.30→0, e=0.6+(峰)→+1，陆地全覆盖且对称。
        //   - 全局常数 → 所有 tile 的 fadeTarget 一致 → 无 tile 边界跳变 → 无缝。
        //   - 低地 fadeTarget<0 → valley rounding 生效（尖谷）；高地>0 → ridge rounding（圆脊），山形更自然。
        final float LAND_REF = 0.25f;
        final float LAND_HALF = 0.25f;
        for (int tz = 0; tz < extLR; tz++) {
            for (int tx = 0; tx < extLR; tx++) {
                float h = rawLowRes[tz][tx];
                float gx = gradX(rawLowRes, tx, tz, extLR, spacing);
                float gz = gradZ(rawLowRes, tx, tz, extLR, spacing);
                // 峰侧衰减：fadeTarget 正侧（抬升）在峰尖衰减——否则 h>0.5 全部饱和 +1，
                // 峰顶被每 octave 均匀抬升（4 oct × 0.08 ≈ +0.17e）→ 触 delta 限幅/softCap → 平顶。
                // 谷侧（负值）不受影响，山谷照常下切；山体中下部仍抬升成脊线。
                // 窗口收窄到 0.72~0.92（只作用峰尖）：减少 flat 场形态改变，控制液滴 tile 边界差异。
                float fadeTarget = clamp((h - LAND_REF) / LAND_HALF, -1f, 1f);
                if (fadeTarget > 0f) fadeTarget *= 1f - smoothstep(0.72f, 0.92f, h);
                float hs = (h - LAND_REF) * 0.5f + 0.5f;
                float gxs = gx * 0.5f, gzs = gz * 0.5f;
                float d = erosionFilter(startX + tx * spacing, startZ + tz * spacing,
                        hs, gxs, gzs, fadeTarget, cellGridFreq, cfg);
                // land mask：仅陆地施加骨架（seaE→seaE+0.10 平滑），保护海洋深度一致性
                float land = smoothstep(seaE, seaE + 0.10f, h);
                delta[tz][tx] = d * land;
            }
        }
        return delta;
    }

    // ===== ErosionFilter（octave 循环 + 堆叠掩码）=====
    private static float erosionFilter(float px, float pz, float h0, float gx, float gz,
                                        float fadeTarget, float cellGridFreq, RidgeConfig cfg) {
        fadeTarget = clamp(fadeTarget, -1f, 1f);
        float inputH = h0;
        float slopeLen = (float) Math.max(Math.hypot(gx, gz), 1e-10);
        float roundingForInput = lerp(cfg.rounding[1], cfg.rounding[0], clamp01(fadeTarget + 0.5f)) * cfg.rounding[2];
        float combiMask = easeOut(smoothStart(slopeLen * cfg.onset[0], roundingForInput * cfg.onset[0]));
        float ridgeMask = easeOut(slopeLen * cfg.onset[2]);
        float ridgeFade = fadeTarget;
        // gullySlope：实际坡度 ↔ 假定坡度（assumedSlope.y=1 → 全用假定方向，幅度 assumedSlope.x）
        float glen = (float) Math.hypot(gx, gz);
        float ngsx = glen < 1e-8f ? 0f : gx / glen;
        float ngsz = glen < 1e-8f ? 0f : gz / glen;
        float gsx = lerp(gx, ngsx * cfg.assumedSlope[0], cfg.assumedSlope[1]);
        float gsz = lerp(gz, ngsz * cfg.assumedSlope[0], cfg.assumedSlope[1]);

        float strength = cfg.strength;
        float freq = cellGridFreq;
        float roundingMult = 1f;
        for (int i = 0; i < cfg.octaves; i++) {
            float[] ph = phacelleNoise(px * freq, pz * freq, norm(gsx, gsz), cfg.stripeFreq, 0.25f, cfg.normalization, cfg.seed);
            float phx = ph[0], phy = ph[1];
            float sdx = ph[2] * -freq;
            float sdz = ph[3] * -freq;
            float sloping = Math.abs(phy);
            // 沿累积斜率分叉（供下一 octave）
            gsx += Math.signum(phy) * sdx * strength * cfg.gullyWeight;
            gsz += Math.signum(phy) * sdz * strength * cfg.gullyWeight;
            // gullies：x=高度偏移(-1..1)，yz=导数（sin × sideDir）
            float gullX = phx;
            float gullYx = phy * sdx;
            float gullYz = phy * sdz;
            // fadedGullies = lerp((fadeTarget,0,0), gullies*gullyWeight, combiMask)
            float fGx = lerp(fadeTarget, gullX * cfg.gullyWeight, combiMask);
            float fGyX = lerp(0f, gullYx * cfg.gullyWeight, combiMask);
            float fGyZ = lerp(0f, gullYz * cfg.gullyWeight, combiMask);
            h0 += fGx * strength;
            gx += fGyX * strength;
            gz += fGyZ * strength;
            fadeTarget = fGx;
            // 堆叠淡出（防覆盖已有脊线）
            float roundingOct = lerp(cfg.rounding[1], cfg.rounding[0], clamp01(phx + 0.5f)) * roundingMult;
            float newMask = easeOut(smoothStart(sloping * cfg.onset[1], roundingOct * cfg.onset[1]));
            combiMask = powInv(combiMask, cfg.detail) * newMask;
            ridgeFade = lerp(ridgeFade, gullX, ridgeMask);
            float newRidgeMask = easeOut(sloping * cfg.onset[3]);
            ridgeMask *= newRidgeMask;
            strength *= cfg.gain;
            freq *= cfg.lacunarity;
            roundingMult *= cfg.rounding[3];
        }
        return h0 - inputH; // 高度增量
    }

    // ===== PhacelleNoise（4×4 细胞钟形权重混合的脊-谷条纹）=====
    private static float[] phacelleNoise(float pxf, float pzf, float[] nd, float freq, float offset, float normalization, int seed) {
        float ndx = nd[0], ndy = nd[1];
        float sdx = ndy * (-1f) * freq * TAU;   // sideDir.x = -normDir.y * freq * TAU
        float sdz = ndx * (1f) * freq * TAU;    // sideDir.z =  normDir.x * freq * TAU
        float off = offset * TAU;
        int pxi = (int) Math.floor(pxf);
        int pzi = (int) Math.floor(pzf);
        float pxfr = pxf - pxi;
        float pzfr = pzf - pzi;
        float phaseX = 0f, phaseY = 0f, weightSum = 0f;
        for (int i = -1; i <= 2; i++) {
            for (int j = -1; j <= 2; j++) {
                float gpx = pxi + i, gpz = pzi + j;
                float[] rnd = hash2(gpx, gpz, seed);
                float rx = rnd[0] * 0.5f, rz = rnd[1] * 0.5f;
                float vx = pxfr - i - rx;
                float vz = pzfr - j - rz;
                float sqr = vx * vx + vz * vz;
                float w = (float) Math.exp(-sqr * 2f);
                w = Math.max(0f, w - 0.01111f); // 距离 1.5 处归零，消除细网格线伪影
                weightSum += w;
                float wave = vx * sdx + vz * sdz + off;
                phaseX += (float) Math.cos(wave) * w;
                phaseY += (float) Math.sin(wave) * w;
            }
        }
        if (weightSum < 1e-8f) weightSum = 1e-8f;
        float ix = phaseX / weightSum, iy = phaseY / weightSum;
        float mag = (float) Math.hypot(ix, iy);
        mag = Math.max(1f - normalization, mag);
        return new float[]{ix / mag, iy / mag, sdx, sdz};
    }

    // ===== Hash2（确定性细胞 pivot，返回 [-1,1]²）=====
    private static float[] hash2(float x, float y, int seed) {
        float k1 = 1f / (float) Math.PI;       // 1/PI
        float k2 = (float) Math.exp(-1.0);     // e^-1
        float sx = seed * 0.06711056f, sy = seed * 0.00583715f;
        float xx = (x + sx) * k1 + k2;         // (x + seedOffset) * k + (k.y, k.x)
        float yy = (y + sy) * k1 + k1;
        float t = frac(xx * yy * (xx + yy));
        float inner1 = 16f * k1 * t;
        float inner2 = 16f * k2 * t;
        return new float[]{-1f + 2f * frac(inner1), -1f + 2f * frac(inner2)};
    }

    // ===== 工具 =====

    private static float gradX(float[][] g, int tx, int tz, int n, int spacing) {
        int xm = Math.max(0, tx - 1), xp = Math.min(n - 1, tx + 1);
        float d = (g[tz][xp] - g[tz][xm]);
        int denom = (xp - xm) * spacing;
        return denom > 0 ? d / denom : 0f;
    }

    private static float gradZ(float[][] g, int tx, int tz, int n, int spacing) {
        int zm = Math.max(0, tz - 1), zp = Math.min(n - 1, tz + 1);
        float d = (g[zp][tx] - g[zm][tx]);
        int denom = (zp - zm) * spacing;
        return denom > 0 ? d / denom : 0f;
    }

    private static float[] norm(float x, float y) {
        float l = (float) Math.hypot(x, y);
        if (l < 1e-8f) return new float[]{1f, 0f}; // 零向量回退固定方向
        return new float[]{x / l, y / l};
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float clamp(float v, float mn, float mx) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return mn;
        return Math.max(mn, Math.min(mx, v));
    }

    private static float clamp01(float v) { return clamp(v, 0f, 1f); }

    private static float frac(float v) { return v - (float) Math.floor(v); }

    private static float smoothstep(float e0, float e1, float x) {
        if (e1 - e0 < 1e-8f) return x < e0 ? 0f : 1f;
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    private static float easeOut(float t) {
        float v = 1f - clamp01(t);
        return 1f - v * v;
    }

    private static float smoothStart(float t, float s) {
        if (t >= s) return t - 0.5f * s;
        return 0.5f * t * t / s;
    }

    private static float powInv(float t, float p) {
        return 1f - (float) Math.pow(1f - clamp01(t), p);
    }

    // ===== 配置读取辅助 =====

    private static boolean cfgBool(net.minecraftforge.common.ForgeConfigSpec.BooleanValue v, boolean fb) {
        try { return v != null && v.get(); } catch (Exception e) { return fb; }
    }

    private static double cfgDbl(net.minecraftforge.common.ForgeConfigSpec.DoubleValue v, double fb) {
        try { return v != null ? v.get() : fb; } catch (Exception e) { return fb; }
    }

    private static int cfgInt(net.minecraftforge.common.ForgeConfigSpec.IntValue v, int fb) {
        try { return v != null ? v.get() : fb; } catch (Exception e) { return fb; }
    }
}
