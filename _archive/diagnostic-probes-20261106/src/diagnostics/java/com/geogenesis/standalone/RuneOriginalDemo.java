package com.geogenesis.standalone;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/*
=====================================================================================
 RuneOriginalDemo —— 作者原版高度流程的【独立纯 Java 最小复现】（独立实验，非生产）
=====================================================================================

 目的：把 Rune Skovbo Johansen 的 "Advanced Terrain Erosion Filter" 里【作者默认静态参数】
 的高度流程，翻译成纯 JDK 的 float32 独立实现，供与 GLSL 对照（不保证逐位相同）。

 严格来源（本工作区）
 - 参考/sources/骨架侵蚀参考项目/Advanced Terrain Erosion Filter/Common.txt
     · 行 137-141  vec2 hash(vec2)           → 本文件 hash()
     · 行 144-166  vec3 noised(vec2)         → 本文件 noised()
 - 参考/sources/骨架侵蚀参考项目/Advanced Terrain Erosion Filter/Buffer A.txt
     · 行 58-129   vec4 PhacelleNoise(...)   → 本文件 phacelleNoise()
     · 行 138-160  pow_inv / ease_out / smooth_start / safe_normalize → 同名工具方法
     · 行 166-246  vec4 ErosionFilter(...)   → 本文件 erosionFilter()
     · 行 254-264  vec3 FractalNoise(...)    → 本文件 fractalNoise()
     · 行 291-493  vec4 Heightmap(vec2 p)    → 本文件 evaluate()
     · 行 301-411  作者默认静态参数表         → 本文件常量区（逐条标注 GLSL 变量名）

 刻意【不】做（用户明确约束，勿擅自补）
 - 不调用本项目任何类；不连接生产地形 / PreviewDisplay / 任何现有 hydrology|erosion 代码。
 - 不加入新的圆化、导向、自定义噪声、seed、域扭曲。噪声只有作者的 hash + noised + FractalNoise。
 - 不做渲染、不做 3D 材质、不打包进模组 jar（本文件在 src/diagnostics 独立 source set，
   未被并入 sourceSets.main，见 build.gradle 113-137）。
 - ANIMATE_PARAMETERS 关闭（Common.txt 行 10 是 #define；此处按【未定义】处理）
   ⇒ 所有 AnimateTo/AnimateWaveTo/AnimateLoHi 均不生效，只用作者写的静态初值。
 - TREES 关闭 ⇒ 明确【不包含】Buffer A.txt 行 472-481 的树形位移（那段会让 eroded 增加
   trees/300，且额外采样一次 noised((p+0.5)*200)）。本文件的 eroded 是无树高度。
   注意也不要把它当成完整 GLSL Heightmap 的返回值 —— 少了树位移这一项。
 - COMPARISON_SLIDER / 键盘开关关闭 ⇒ erosion 恒为 true（作者行 418-428 的默认分支）。

 语义保真（GLSL float32 → Java float32）
 - 全流程 float（每个关键操作显式 float），与 GLSL 的 32 位 float 同一格式；
   Java 17 起浮点恒严格（JEP 306），float 运算逐次舍入到 float。
 - fract 严格取 `x - floor(x)`；floor 用 (float)Math.floor(double)（对 float 输入精确）。
 - hash 的乘法链顺序照抄：`16.0 * k * fract(...)` 求值为 `(16*k) * fract(...)`；
   `x.x*x.y*(x.x+x.y)` 求值为 `((x.x*x.y) * (x.x+x.y))`。乘 16 是精确的，不引入额外舍入。
 - mix 采用 GLSL 规范式 `x*(1-a)+y*a`（不是 `x+a*(y-x)`）；某些驱动的实现是后者，
   最多差几个 ULP —— 这是【已知且接受】的差异，已在 metadata.txt 记录。
 - cos/sin/exp/pow 用 java.lang.StrictMath（fdlibm，跨机器逐位可复现）再 (float) 收窄。
   GPU 的单精度超越函数实现可能与此差几 ULP —— 【已知且接受】，不声称逐位等于 GPU 真值。
 - 采样坐标按 GLSL 语义用 float32 计算（见 sampleX/sampleZ）。

 许可（不要猜；按事实写）
 - Buffer A.txt 的 PhacelleNoise 与 ErosionFilter 各自带【MPL-2.0】版权声明（原文已就近逐字保留）。
   本文件是其翻译（衍生），故【本文件整体按 MPL-2.0 保留】；未声明、也不推测任何其它许可证
   （不猜 MIT/GPL/Apache），项目整体许可与本文件无关。
 - Common.txt 的 noised() 只注明出处 shadertoy XdXBRH（见 Common.txt 行 145），
   【未核实其许可】，本文件不对该函数作任何许可声明。
=====================================================================================
*/

/**
 * 作者原版高度流程的独立纯 Java float32 复现（不依赖任何现有项目类，纯 JDK）。
 *
 * <p>用法：
 * <ul>
 *   <li>{@code java RuneOriginalDemo <outputdir> <size> <minx> <minz> <span>}
 *       —— 生成 {@code base.f32be} / {@code eroded.f32be}（大端 IEEE-754 binary32，
 *       行主序：行 = p.y(z) 递增，列 = p.x 递增）与 {@code metadata.txt}。
 *       目录已存在则【拒绝执行】(exit 4)，防止覆盖上一次产物。</li>
 *   <li>{@code java RuneOriginalDemo --point x z} —— 打印单点中间量（供 GLSL 逐项对拍）。</li>
 *   <li>{@code java RuneOriginalDemo --selftest} —— 确定性 / 零强度 / 语义自检（FAIL 则 exit 1）。
 *       只验证本实现内部自洽，<b>不声称等于 GLSL 真值</b>。</li>
 * </ul>
 */
public final class RuneOriginalDemo {

    private RuneOriginalDemo() {}

    // =========================================================================
    // 常量：Common.txt 行 12 / Buffer A.txt 行 42
    // =========================================================================

    /** Buffer A.txt 行 42：{@code #define TAU 6.28318530717959} */
    private static final float TAU = 6.28318530717959f;
    /** Common.txt 行 138：{@code const vec2 k = vec2(0.3183099, 0.3678794);} */
    private static final float HASH_KX = 0.3183099f;
    private static final float HASH_KY = 0.3678794f;

    // =========================================================================
    // 常量：作者默认静态参数（Buffer A.txt 行 301-411；动画关闭 ⇒ 即初值）
    // =========================================================================

    /** 行 301：EROSION_SCALE = 0.15（AnimateLoHi 关闭 ⇒ 取初值 0.15） */
    private static final float EROSION_SCALE = 0.15f;
    /** 行 306：EROSION_STRENGTH = 0.22 */
    private static final float EROSION_STRENGTH = 0.22f;
    /** 行 315：EROSION_GULLY_WEIGHT = 0.5 */
    private static final float EROSION_GULLY_WEIGHT = 0.5f;
    /** 行 319：EROSION_DETAIL = 1.5 */
    private static final float EROSION_DETAIL = 1.5f;
    /** 行 322：ridgeRounding = 0.1 */
    private static final float EROSION_RIDGE_ROUNDING = 0.1f;
    /** 行 323：creaseRounding = 0.0 */
    private static final float EROSION_CREASE_ROUNDING = 0.0f;
    /** 行 337：EROSION_ROUNDING = vec4(ridgeRounding, creaseRounding, 0.1, 2.0) */
    private static final float EROSION_ROUNDING_INPUT = 0.1f;
    private static final float EROSION_ROUNDING_OCTAVE = 2.0f;
    /** 行 344：EROSION_ONSET = vec4(1.25, 1.25, 2.8, 1.5) */
    private static final float EROSION_ONSET_INPUT = 1.25f;
    private static final float EROSION_ONSET_OCTAVE = 1.25f;
    private static final float EROSION_ONSET_RIDGE_INPUT = 2.8f;
    private static final float EROSION_ONSET_RIDGE_OCTAVE = 1.5f;
    /** 行 351：EROSION_ASSUMED_SLOPE = vec2(0.7, 1.0) */
    private static final float EROSION_ASSUMED_SLOPE = 0.7f;
    private static final float EROSION_ASSUMED_SLOPE_MIX = 1.0f;
    /** 行 362：EROSION_CELL_SCALE = 0.7 */
    private static final float EROSION_CELL_SCALE = 0.7f;
    /** 行 367：EROSION_NORMALIZATION = 0.5 */
    private static final float EROSION_NORMALIZATION = 0.5f;
    /** 行 371-377：EROSION_OCTAVES = 5 / LACUNARITY = 2.0 / GAIN = 0.5 */
    private static final int EROSION_OCTAVES = 5;
    private static final float EROSION_LACUNARITY = 2.0f;
    private static final float EROSION_GAIN = 0.5f;

    /** 行 395：TERRAIN_HEIGHT_OFFSET = vec2(-0.65, 0.0) */
    private static final float TERRAIN_HEIGHT_OFFSET = -0.65f;
    private static final float TERRAIN_HEIGHT_OFFSET_MIX = 0.0f;

    /** 行 400-411：HEIGHT_FREQUENCY = 3.0 / AMP = 0.125 / OCTAVES = 3 / LACUNARITY = 2.0 / GAIN = 0.1 */
    private static final float HEIGHT_FREQUENCY = 3.0f;
    private static final float HEIGHT_AMP = 0.125f;
    private static final int HEIGHT_OCTAVES = 3;
    private static final float HEIGHT_LACUNARITY = 2.0f;
    private static final float HEIGHT_GAIN = 0.1f;

    /** 行 435：heightFunctionScale = 1.0（pHeight = p / 1.0，故本实现直接用 p） */
    private static final float HEIGHT_FUNCTION_SCALE = 1.0f;

    /**
     * ErosionFilter 的风格/尺度参数组（对应 GLSL 的形参列表，顺序与行 166-177 完全一致）。
     * 只有这些一起才定义一次侵蚀调用；{@code withStrength} 仅供 --selftest 的零强度用例。
     */
    private static final class Params {
        final float strength;          // 行 170
        final float gullyWeight;       // 行 170
        final float detail;            // 行 170
        final float roundingX;         // 行 170 vec4 rounding.x  ridges
        final float roundingY;         //                .y  creases
        final float roundingZ;         //                .z  input mult
        final float roundingW;         //                .w  per-octave mult
        final float onsetX;            // 行 170 vec4 onset.x
        final float onsetY;            //                .y
        final float onsetZ;            //                .z
        final float onsetW;            //                .w
        final float assumedSlopeX;     // 行 170 vec2 assumedSlope.x
        final float assumedSlopeY;     //                .y
        final float scale;             // 行 172
        final int octaves;             // 行 172
        final float lacunarity;        // 行 172
        final float gain;              // 行 174
        final float cellScale;         // 行 174
        final float normalization;     // 行 174

        Params(float strength, float gullyWeight, float detail,
               float roundingX, float roundingY, float roundingZ, float roundingW,
               float onsetX, float onsetY, float onsetZ, float onsetW,
               float assumedSlopeX, float assumedSlopeY,
               float scale, int octaves, float lacunarity,
               float gain, float cellScale, float normalization) {
            this.strength = strength;
            this.gullyWeight = gullyWeight;
            this.detail = detail;
            this.roundingX = roundingX;
            this.roundingY = roundingY;
            this.roundingZ = roundingZ;
            this.roundingW = roundingW;
            this.onsetX = onsetX;
            this.onsetY = onsetY;
            this.onsetZ = onsetZ;
            this.onsetW = onsetW;
            this.assumedSlopeX = assumedSlopeX;
            this.assumedSlopeY = assumedSlopeY;
            this.scale = scale;
            this.octaves = octaves;
            this.lacunarity = lacunarity;
            this.gain = gain;
            this.cellScale = cellScale;
            this.normalization = normalization;
        }

        /** 仅替换 strength（其余逐字不变）；只被 --selftest 的零强度用例使用。 */
        Params withStrength(float newStrength) {
            return new Params(newStrength, gullyWeight, detail,
                    roundingX, roundingY, roundingZ, roundingW,
                    onsetX, onsetY, onsetZ, onsetW, assumedSlopeX, assumedSlopeY,
                    scale, octaves, lacunarity, gain, cellScale, normalization);
        }
    }

    /** 作者默认静态参数（Buffer A.txt 行 454-460 实参顺序）。 */
    private static final Params AUTHOR = new Params(
            EROSION_STRENGTH, EROSION_GULLY_WEIGHT, EROSION_DETAIL,
            EROSION_RIDGE_ROUNDING, EROSION_CREASE_ROUNDING,
            EROSION_ROUNDING_INPUT, EROSION_ROUNDING_OCTAVE,
            EROSION_ONSET_INPUT, EROSION_ONSET_OCTAVE,
            EROSION_ONSET_RIDGE_INPUT, EROSION_ONSET_RIDGE_OCTAVE,
            EROSION_ASSUMED_SLOPE, EROSION_ASSUMED_SLOPE_MIX,
            EROSION_SCALE, EROSION_OCTAVES, EROSION_LACUNARITY,
            EROSION_GAIN, EROSION_CELL_SCALE, EROSION_NORMALIZATION);

    // =========================================================================
    // 单点采样结果（字段名与 --point 输出一致）
    // =========================================================================

    /** 一个采样点的全部中间量。z 轴即 GLSL 的 p.y。 */
    static final class Sample {
        float px;                              // GLSL p.x
        float py;                              // GLSL p.y（高度图网格的 z 方向）
        float rawHeight;                       // FractalNoise().x（未乘 HEIGHT_AMP，约 [-1,1]）
        float rawGradX;                        // FractalNoise().y（同上，未乘 HEIGHT_AMP）
        float rawGradZ;                        // FractalNoise().z
        float height;                          // n.x = rawHeight * HEIGHT_AMP（仍约 [-1,1]）
        float gradX;                           // n.y
        float gradZ;                           // n.z
        float fadeTarget;                      // clamp(n.x / (HEIGHT_AMP*0.6), -1, 1)
        float base;                            // n.x * 0.5 + 0.5  → 写 base.f32be
        float baseGradX;                       // n.y * 0.5（非输出，供核对）
        float baseGradZ;                       // n.z * 0.5
        float delta;                           // h.x
        float slopeDeltaX;                     // h.y
        float slopeDeltaZ;                     // h.z
        float magnitude;                       // h.w
        float ridgeMap;                        // ridgeMap
        float debug;                           // debug = 循环末的 fadeTarget
        float offset;                          // mix(-0.65, -fadeTarget, 0.0) * magnitude
        float eroded;                          // base + delta + offset（【无树位移】）
    }

    // =========================================================================
    // 工具函数（Common.txt 行 133-135 / Buffer A.txt 行 138-160）
    // =========================================================================

    /** GLSL {@code clamp(x, 0.0, 1.0)}（Common.txt 行 134 的 clamp01 宏）。 */
    private static float clamp01(float v) {
        return clamp(v, 0.0f, 1.0f);
    }

    /** GLSL {@code clamp(v, lo, hi)}：规范定义为 {@code min(max(v,lo),hi)}。 */
    private static float clamp(float v, float lo, float hi) {
        return Math.min(Math.max(v, lo), hi);
    }

    /** GLSL {@code mix(a, b, t)}：规范式 {@code a*(1-t) + b*t}。 */
    private static float mix(float a, float b, float t) {
        return a * (1.0f - t) + b * t;
    }

    /** GLSL {@code floor(x)}（对 float 输入精确）。 */
    private static float floorf(float v) {
        return (float) Math.floor(v);
    }

    /** GLSL {@code fract(x)}，规范定义 {@code x - floor(x)}（负数得正小数）。 */
    private static float fract(float v) {
        return v - floorf(v);
    }

    /** Buffer A.txt 行 138-141：{@code 1.0 - pow(1.0 - clamp01(t), power)} */
    private static float powInv(float t, float power) {
        return 1.0f - (float) StrictMath.pow(1.0f - clamp01(t), power);
    }

    /** Buffer A.txt 行 143-148：{@code 1.0 - (1-t)^2} */
    private static float easeOut(float t) {
        float v = 1.0f - clamp01(t);
        return 1.0f - v * v;
    }

    /** Buffer A.txt 行 150-154。注意 smoothing==0 时走 t>=smoothing 分支（与 GLSL 同序）。 */
    private static float smoothStart(float t, float smoothing) {
        if (t >= smoothing) {
            return t - 0.5f * smoothing;
        }
        return 0.5f * t * t / smoothing;
    }

    /** Buffer A.txt 行 156-160。返回 {x,y}。 */
    private static float[] safeNormalize(float x, float y) {
        float l = (float) StrictMath.sqrt(x * x + y * y);
        if (Math.abs(l) > 1e-10f) {
            return new float[] { x / l, y / l };
        }
        return new float[] { x, y };
    }

    // =========================================================================
    // Common.txt 行 137-141：vec2 hash(vec2 x)
    // 出处：本函数在 Common.txt 中【无单独许可声明】。同文件的 noised() 注明来自
    // https://www.shadertoy.com/view/XdXBRH（行 145）；本文件【不声称】其许可已核实。
    // =========================================================================

    /**
     * 逐字翻译：{@code x = x*k + k.yx;} 之后 {@code -1.0 + 2.0*fract(16.0*k*fract(x.x*x.y*(x.x+x.y)))}。
     * 乘法顺序照抄，不重排（float 舍入敏感）。
     */
    private static float[] hash(float xIn, float yIn) {
        float x = xIn * HASH_KX + HASH_KY;   // x.x*k.x + k.y   (k.yx.x == k.y)
        float y = yIn * HASH_KY + HASH_KX;   // x.y*k.y + k.x   (k.yx.y == k.x)
        float f = fract(x * y * (x + y));    // ((x*x*y) * (x+y))，再 fract
        return new float[] {
                -1.0f + 2.0f * fract(16.0f * HASH_KX * f),
                -1.0f + 2.0f * fract(16.0f * HASH_KY * f) };
    }

    // =========================================================================
    // Common.txt 行 144-166：vec3 noised(vec2 p)  —— 梯度噪声 + 解析导数
    // 出处：Common.txt 行 145 注明 shadertoy https://www.shadertoy.com/view/XdXBRH；
    //       【许可未核实】，本文件不作许可声明。返回值 {value, d/dx, d/dy}。
    // =========================================================================

    private static float[] noised(float px, float py) {
        float ix = floorf(px);
        float iy = floorf(py);
        float fx = fract(px);
        float fy = fract(py);

        float ux = fx * fx * fx * (fx * (fx * 6.0f - 15.0f) + 10.0f);
        float uy = fy * fy * fy * (fy * (fy * 6.0f - 15.0f) + 10.0f);
        float dux = 30.0f * fx * fx * (fx * (fx - 2.0f) + 1.0f);
        float duy = 30.0f * fy * fy * (fy * (fy - 2.0f) + 1.0f);

        float[] ga = hash(ix + 0.0f, iy + 0.0f);
        float[] gb = hash(ix + 1.0f, iy + 0.0f);
        float[] gc = hash(ix + 0.0f, iy + 1.0f);
        float[] gd = hash(ix + 1.0f, iy + 1.0f);

        float va = ga[0] * fx + ga[1] * fy;
        float vb = gb[0] * (fx - 1.0f) + gb[1] * fy;
        float vc = gc[0] * fx + gc[1] * (fy - 1.0f);
        float vd = gd[0] * (fx - 1.0f) + gd[1] * (fy - 1.0f);

        float k = va - vb - vc + vd;   // ((va-vb)-vc)+vd

        float value = va + ux * (vb - va) + uy * (vc - va) + ux * uy * k;
        float dx = ga[0] + ux * (gb[0] - ga[0]) + uy * (gc[0] - ga[0])
                + ux * uy * (ga[0] - gb[0] - gc[0] + gd[0])
                + dux * ((uy * k + vb) - va);
        float dy = ga[1] + ux * (gb[1] - ga[1]) + uy * (gc[1] - ga[1])
                + ux * uy * (ga[1] - gb[1] - gc[1] + gd[1])
                + duy * ((ux * k + vc) - va);
        return new float[] { value, dx, dy };
    }

    // =========================================================================
    // Buffer A.txt 行 254-264：vec3 FractalNoise(vec2 p, float freq, int octaves,
    //                                           float lacunarity, float gain)
    //   n += noised(p*nf) * na * vec3(1.0, nf, nf);
    // =========================================================================

    private static float[] fractalNoise(float px, float py, float freqIn, int octaves,
                                        float lacunarity, float gain) {
        float nx = 0.0f;
        float ny = 0.0f;
        float nz = 0.0f;
        float nf = freqIn;
        float na = 1.0f;
        for (int i = 0; i < octaves; i++) {
            float[] t = noised(px * nf, py * nf);
            float ax = t[0] * na;          // noised(...) * na  （vec3 * scalar）
            float ay = t[1] * na;
            float az = t[2] * na;
            nx += ax * 1.0f;               // ... * vec3(1.0, nf, nf)
            ny += ay * nf;
            nz += az * nf;
            na *= gain;                    // 行 260-261
            nf *= lacunarity;
        }
        return new float[] { nx, ny, nz };
    }

    // =========================================================================
    // Buffer A.txt 行 54-129：vec4 PhacelleNoise(vec2 p, vec2 normDir, float freq,
    //                                            float offset, float normalization)
    // 返回值 {cos 分量, sin 分量, sideDir.x, sideDir.y}（即 GLSL 的 .x .y .zw）。
    // =========================================================================

    // Phacelle Noise function copyright (c) 2025 Rune Skovbo Johansen
    // This Source Code Form is subject to the terms of the Mozilla Public
    // License, v. 2.0. If a copy of the MPL was not distributed with this
    // file, You can obtain one at https://mozilla.org/MPL/2.0/.
    private static float[] phacelleNoise(float px, float py, float nx, float ny,
                                         float cellFreq, float offsetIn, float normalization) {
        // vec2 sideDir = normDir.yx * vec2(-1.0, 1.0) * freq * TAU;  （顺序照抄）
        float sideX = (ny * -1.0f) * cellFreq * TAU;
        float sideY = (nx * 1.0f) * cellFreq * TAU;
        float offset = offsetIn * TAU;

        float pIntX = floorf(px);
        float pIntY = floorf(py);
        float pFracX = fract(px);
        float pFracY = fract(py);

        float phaseX = 0.0f;
        float phaseY = 0.0f;
        float weightSum = 0.0f;

        for (int i = -1; i <= 2; i++) {            // 4x4 cells，i 为外层（x）
            for (int j = -1; j <= 2; j++) {
                float[] randomOffset = hash(pIntX + i, pIntY + j);   // hash(gridPoint)
                float rndX = randomOffset[0] * 0.5f;
                float rndY = randomOffset[1] * 0.5f;

                // vectorFromCellPoint = pFrac - gridOffset - randomOffset
                float vx = pFracX - i - rndX;
                float vy = pFracY - j - rndY;

                float sqrDist = vx * vx + vy * vy;                    // dot(v, v)
                float weight = (float) StrictMath.exp((-sqrDist) * 2.0f);
                weight = Math.max(0.0f, weight - 0.01111f);
                weightSum += weight;

                float waveInput = (vx * sideX + vy * sideY) + offset;  // dot + offset
                phaseX += ((float) StrictMath.cos(waveInput)) * weight;
                phaseY += ((float) StrictMath.sin(waveInput)) * weight;
            }
        }

        float interpX = phaseX / weightSum;
        float interpY = phaseY / weightSum;
        float magnitude = (float) StrictMath.sqrt(interpX * interpX + interpY * interpY);
        magnitude = Math.max(1.0f - normalization, magnitude);
        return new float[] { interpX / magnitude, interpY / magnitude, sideX, sideY };
    }

    // =========================================================================
    // Buffer A.txt 行 162-246：vec4 ErosionFilter(...)
    // 返回 {deltaX, deltaY, deltaZ, magnitude, ridgeMap, debug}。
    // =========================================================================

    // Advanced Terrain Erosion Filter copyright (c) 2025 Rune Skovbo Johansen
    // This Source Code Form is subject to the terms of the Mozilla Public
    // License, v. 2.0. If a copy of the MPL was not distributed with this
    // file, You can obtain one at https://mozilla.org/MPL/2.0/.
    private static float[] erosionFilter(float px, float py,
                                         float hx, float hy, float hz, float fadeTargetIn,
                                         Params P) {
        float strength = P.strength * P.scale;                    // 行 178
        float fadeTarget = clamp(fadeTargetIn, -1.0f, 1.0f);      // 行 179

        float inX = hx;
        float inY = hy;
        float inZ = hz;
        float freq = 1.0f / (P.scale * P.cellScale);              // 行 182
        float slopeLength = Math.max((float) StrictMath.sqrt(hy * hy + hz * hz), 1e-10f);
        float magnitude = 0.0f;
        float roundingMult = 1.0f;

        float roundingForInput = mix(P.roundingY, P.roundingX, clamp01(fadeTarget + 0.5f)) * P.roundingZ;
        float combiMask = easeOut(smoothStart(slopeLength * P.onsetX, roundingForInput * P.onsetX));

        float ridgeMapCombiMask = easeOut(slopeLength * P.onsetZ);   // 行 192
        float ridgeMapFadeTarget = fadeTarget;

        // vec2 gullySlope = mix(heightAndSlope.yz, heightAndSlope.yz / slopeLength * assumedSlope.x,
        //                       assumedSlope.y);
        float gullySlopeX = mix(hy, hy / slopeLength * P.assumedSlopeX, P.assumedSlopeY);
        float gullySlopeY = mix(hz, hz / slopeLength * P.assumedSlopeX, P.assumedSlopeY);

        for (int i = 0; i < P.octaves; i++) {
            float[] norm = safeNormalize(gullySlopeX, gullySlopeY);
            float[] ph = phacelleNoise(px * freq, py * freq, norm[0], norm[1],
                    P.cellScale, 0.25f, P.normalization);
            float sideWX = ph[2] * -freq;             // phacelle.zw *= -freq
            float sideWY = ph[3] * -freq;
            float sloping = Math.abs(ph[1]);          // abs(phacelle.y)

            // gullySlope += sign(phacelle.y) * phacelle.zw * strength * gullyWeight;
            float sgn = Math.signum(ph[1]);
            gullySlopeX += sgn * sideWX * strength * P.gullyWeight;
            gullySlopeY += sgn * sideWY * strength * P.gullyWeight;

            // vec3 gullies = vec3(phacelle.x, phacelle.y * phacelle.zw);
            float gullyH = ph[0];
            float gullyDX = ph[1] * sideWX;
            float gullyDZ = ph[1] * sideWY;

            // vec3 fadedGullies = mix(vec3(fadeTarget,0,0), gullies*gullyWeight, combiMask);
            float fadedX = mix(fadeTarget, gullyH * P.gullyWeight, combiMask);
            float fadedY = mix(0.0f, gullyDX * P.gullyWeight, combiMask);
            float fadedZ = mix(0.0f, gullyDZ * P.gullyWeight, combiMask);

            hx += fadedX * strength;                  // heightAndSlope += fadedGullies*strength
            hy += fadedY * strength;
            hz += fadedZ * strength;
            magnitude += strength;
            fadeTarget = fadedX;

            float roundingForOctave = mix(P.roundingY, P.roundingX, clamp01(ph[0] + 0.5f)) * roundingMult;
            float newMask = easeOut(smoothStart(sloping * P.onsetY, roundingForOctave * P.onsetY));
            combiMask = powInv(combiMask, P.detail) * newMask;

            ridgeMapFadeTarget = mix(ridgeMapFadeTarget, gullyH, ridgeMapCombiMask);
            float newRidgeMapMask = easeOut(sloping * P.onsetW);
            ridgeMapCombiMask = ridgeMapCombiMask * newRidgeMapMask;

            strength *= P.gain;
            freq *= P.lacunarity;
            roundingMult *= P.roundingW;
        }

        float ridgeMap = ridgeMapFadeTarget * (1.0f - ridgeMapCombiMask);   // 行 241
        float debug = fadeTarget;
        return new float[] { hx - inX, hy - inY, hz - inZ, magnitude, ridgeMap, debug };
    }

    // =========================================================================
    // Buffer A.txt 行 291-493：Heightmap(vec2 p) —— 只保留到 eroded 为止
    // =========================================================================

    /** 采样一个点（GLSL p）。TREES 关闭 ⇒ eroded 不含树形位移。 */
    private static Sample evaluate(float px, float py) {
        Sample s = new Sample();
        s.px = px;
        s.py = py;

        // heightFunctionScale = 1.0 ⇒ pHeight = p / 1.0（行 435-436）
        float[] fr = fractalNoise(px / HEIGHT_FUNCTION_SCALE, py / HEIGHT_FUNCTION_SCALE,
                HEIGHT_FREQUENCY, HEIGHT_OCTAVES, HEIGHT_LACUNARITY, HEIGHT_GAIN);
        s.rawHeight = fr[0];
        s.rawGradX = fr[1];
        s.rawGradZ = fr[2];

        // n = FractalNoise(...) * HEIGHT_AMP * vec3(heightFunctionScale, 1.0, 1.0)  （行 440-441）
        float nx = fr[0] * HEIGHT_AMP * HEIGHT_FUNCTION_SCALE;
        float ny = fr[1] * HEIGHT_AMP * 1.0f;
        float nz = fr[2] * HEIGHT_AMP * 1.0f;
        s.height = nx;
        s.gradX = ny;
        s.gradZ = nz;

        // float fadeTarget = clamp(n.x / (HEIGHT_AMP * 0.6), -1.0, 1.0);  （行 445）
        float fadeTarget = clamp(nx / (HEIGHT_AMP * 0.6f), -1.0f, 1.0f);
        s.fadeTarget = fadeTarget;

        // n = n * 0.5 + vec3(0.5, 0, 0);  （行 448）
        nx = nx * 0.5f + 0.5f;
        ny = ny * 0.5f + 0.0f;
        nz = nz * 0.5f + 0.0f;
        s.base = nx;
        s.baseGradX = ny;
        s.baseGradZ = nz;

        float[] h = erosionFilter(px, py, nx, ny, nz, fadeTarget, AUTHOR);
        s.delta = h[0];
        s.slopeDeltaX = h[1];
        s.slopeDeltaZ = h[2];
        s.magnitude = h[3];
        s.ridgeMap = h[4];
        s.debug = h[5];

        // float offset = mix(TERRAIN_HEIGHT_OFFSET.x, -fadeTarget, TERRAIN_HEIGHT_OFFSET.y) * h.w;
        s.offset = mix(TERRAIN_HEIGHT_OFFSET, -fadeTarget, TERRAIN_HEIGHT_OFFSET_MIX) * s.magnitude;
        // float eroded = n.x + h.x + offset;  （行 469；TREES 关闭，行 471-481 不执行）
        s.eroded = s.base + s.delta + s.offset;
        return s;
    }

    /** GLSL 语义的采样坐标：p.x = minx + ((x + 0.5) * span / size)（float32 运算）。 */
    private static float sampleCoord(float min, int index, float span, int size) {
        return min + ((index + 0.5f) * span / (float) size);
    }

    // =========================================================================
    // main / 模式分发
    // =========================================================================

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && "--point".equals(args[0])) {
            point(args);
            return;
        }
        if (args.length == 1 && "--selftest".equals(args[0])) {
            System.exit(selfTest() ? 0 : 1);
            return;
        }
        if (args.length == 5) {
            render(args);
            return;
        }
        usage();
        System.exit(2);
    }

    private static void usage() {
        System.out.println("用法:");
        System.out.println("  <outputdir> <size> <minx> <minz> <span>  出 base.f32be / eroded.f32be / metadata.txt");
        System.out.println("  --point <x> <z>                        打印单点中间量（GLSL p.x p.y）");
        System.out.println("  --selftest                             确定性/零强度/语义自检（FAIL 则 exit 1）");
        System.out.println();
        System.out.println("坐标为 GLSL p（p.y 即高度图网格的 z 方向），每格采样");
        System.out.println("  p.x = minx + ((x + 0.5) * span / size),  p.y = minz + ((z + 0.5) * span / size)");
        System.out.println("输出为 big-endian IEEE-754 binary32，行主序（行 = z 递增，列 = x 递增）。");
    }

    // =========================================================================
    // --point：逐项打印，供 GLSL 实际对拍
    // =========================================================================

    private static void point(String[] args) {
        float px = Float.parseFloat(args[1]);
        float py = Float.parseFloat(args[2]);
        Sample s = evaluate(px, py);
        System.out.println("== RuneOriginalDemo --point ==");
        printF("p.x", px);
        printF("p.y", py);
        printF("rawHeight", s.rawHeight);
        printF("rawGradX", s.rawGradX);
        printF("rawGradZ", s.rawGradZ);
        printF("fadeTarget", s.fadeTarget);
        printF("base", s.base);
        printF("delta", s.delta);
        printF("magnitude", s.magnitude);
        printF("ridgemap", s.ridgeMap);
        printF("eroded", s.eroded);
        System.out.println("-- 附加（非必须对拍项）--");
        printF("height(amp applied)", s.height);
        printF("gradX(amp applied)", s.gradX);
        printF("gradZ(amp applied)", s.gradZ);
        printF("baseGradX", s.baseGradX);
        printF("baseGradZ", s.baseGradZ);
        printF("slopeDeltaX", s.slopeDeltaX);
        printF("slopeDeltaZ", s.slopeDeltaZ);
        printF("debug(fadeTarget末值)", s.debug);
        printF("offset", s.offset);
        System.out.println("注: eroded = base + delta + offset，且【不含树形位移】(TREES 关闭)。");
    }

    private static void printF(String name, float v) {
        System.out.println(String.format(Locale.ROOT, "%-22s %-18s  bits=0x%08x",
                name, Float.toString(v), Float.floatToRawIntBits(v)));
    }

    // =========================================================================
    // 出图模式
    // =========================================================================

    private static void render(String[] args) throws IOException {
        Path outDir = Paths.get(args[0]);
        int size = Integer.parseInt(args[1]);
        double minXd = Double.parseDouble(args[2]);
        double minZd = Double.parseDouble(args[3]);
        double spanD = Double.parseDouble(args[4]);
        if (size <= 0 || !(spanD > 0.0)) {
            System.err.println("参数非法: size 必须 >0 且 span 必须 >0（size=" + size + " span=" + spanD + "）");
            System.exit(3);
            return;
        }
        if (Files.exists(outDir)) {
            System.err.println("拒绝执行: 输出目录已存在，为避免覆盖不写入: " + outDir.toAbsolutePath());
            System.exit(4);
            return;
        }
        float minX = (float) minXd;
        float minZ = (float) minZd;
        float span = (float) spanD;
        Files.createDirectories(outDir);

        float baseMin = Float.POSITIVE_INFINITY;
        float baseMax = Float.NEGATIVE_INFINITY;
        float erodedMin = Float.POSITIVE_INFINITY;
        float erodedMax = Float.NEGATIVE_INFINITY;
        long t0 = System.nanoTime();
        try (DataOutputStream baseOut = open(outDir.resolve("base.f32be"));
             DataOutputStream erodedOut = open(outDir.resolve("eroded.f32be"))) {
            for (int z = 0; z < size; z++) {
                float py = sampleCoord(minZ, z, span, size);
                for (int x = 0; x < size; x++) {
                    float px = sampleCoord(minX, x, span, size);
                    Sample s = evaluate(px, py);
                    baseOut.writeFloat(s.base);      // DataOutputStream 恒为大端
                    erodedOut.writeFloat(s.eroded);
                    baseMin = Math.min(baseMin, s.base);
                    baseMax = Math.max(baseMax, s.base);
                    erodedMin = Math.min(erodedMin, s.eroded);
                    erodedMax = Math.max(erodedMax, s.eroded);
                }
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        String meta = metadata(size, minXd, minZd, spanD, minX, minZ, span,
                baseMin, baseMax, erodedMin, erodedMax, elapsedMs);
        try (OutputStream os = Files.newOutputStream(outDir.resolve("metadata.txt"))) {
            os.write(meta.getBytes(StandardCharsets.UTF_8));
        }

        System.out.println("done: " + outDir.toAbsolutePath());
        System.out.println("samples=" + ((long) size * size) + " elapsedMs=" + elapsedMs);
        System.out.println("base   [" + Float.toString(baseMin) + ", " + Float.toString(baseMax) + "]");
        System.out.println("eroded [" + Float.toString(erodedMin) + ", " + Float.toString(erodedMax) + "]");
    }

    private static DataOutputStream open(Path file) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
    }

    // =========================================================================
    // metadata.txt
    // =========================================================================

    private static String metadata(int size, double minXd, double minZd, double spanD,
                                   float minX, float minZ, float span,
                                   float baseMin, float baseMax, float erodedMin, float erodedMax,
                                   long elapsedMs) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("=== RuneOriginalDemo / Advanced Terrain Erosion Filter (作者默认静态参数) ===\n");
        sb.append("generator        : RuneOriginalDemo.evaluate()  (纯 JDK float32，无 MC/无本项目类)\n");
        sb.append("source           : 参考/sources/骨架侵蚀参考项目/Advanced Terrain Erosion Filter/\n");
        sb.append("                   Buffer A.txt + Common.txt (逐行翻译，见文件头注释的行号映射)\n");
        sb.append('\n');
        sb.append("[output format]\n");
        sb.append("base.f32be       : IEEE-754 binary32, big-endian, 行主序（行=z 递增，列=x 递增）\n");
        sb.append("eroded.f32be     : 同上\n");
        sb.append("size             : ").append(size).append(" x ").append(size).append('\n');
        sb.append("floats per file  : ").append((long) size * size).append('\n');
        sb.append("bytes per file   : ").append(4L * size * size).append('\n');
        sb.append('\n');
        sb.append("[coordinates]  (GLSL p；p.y 即高度图网格的 z 方向)\n");
        sb.append("sample p.x       : minx + ((x + 0.5) * span / size)   [float32 运算]\n");
        sb.append("sample p.y       : minz + ((z + 0.5) * span / size)   [float32 运算]\n");
        sb.append("minx   (arg)     : ").append(minXd).append('\n');
        sb.append("minz   (arg)     : ").append(minZd).append('\n');
        sb.append("span   (arg)     : ").append(spanD).append('\n');
        sb.append("minx   (float32) : ").append(fhex(minX)).append('\n');
        sb.append("minz   (float32) : ").append(fhex(minZ)).append('\n');
        sb.append("span   (float32) : ").append(fhex(span)).append('\n');
        sb.append("p.x range        : ").append(fhex(minX + 0.5f * span / size)).append(" .. ")
                .append(fhex(minX + ((size - 1 + 0.5f) * span / size))).append('\n');
        sb.append("p.y range        : ").append(fhex(minZ + 0.5f * span / size)).append(" .. ")
                .append(fhex(minZ + ((size - 1 + 0.5f) * span / size))).append('\n');
        sb.append("p 步长           : ").append(fhex(span / size)).append('\n');
        sb.append('\n');
        sb.append("[value ranges]  (实测，float32)\n");
        sb.append("base   min/max   : ").append(fhex(baseMin)).append(" / ").append(fhex(baseMax)).append('\n');
        sb.append("eroded min/max   : ").append(fhex(erodedMin)).append(" / ").append(fhex(erodedMax)).append('\n');
        sb.append("elapsedMs        : ").append(elapsedMs).append('\n');
        sb.append('\n');
        sb.append(paramsText());
        return sb.toString();
    }

    private static String fhex(float v) {
        return Float.toString(v) + " [0x" + Integer.toHexString(Float.floatToRawIntBits(v)) + "]";
    }

    private static String paramsText() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("[params] 作者默认静态值（Buffer A.txt 行 301-411；ANIMATE_PARAMETERS 关闭 ⇒ 即初值）\n");
        sb.append("EROSION_SCALE           = ").append(f(EROSION_SCALE)).append("   (行 301)\n");
        sb.append("EROSION_STRENGTH        = ").append(f(EROSION_STRENGTH)).append("   (行 306)\n");
        sb.append("EROSION_GULLY_WEIGHT    = ").append(f(EROSION_GULLY_WEIGHT)).append("   (行 315)\n");
        sb.append("EROSION_DETAIL          = ").append(f(EROSION_DETAIL)).append("   (行 319)\n");
        sb.append("EROSION_ROUNDING        = vec4(").append(f(EROSION_RIDGE_ROUNDING)).append(", ")
                .append(f(EROSION_CREASE_ROUNDING)).append(", ").append(f(EROSION_ROUNDING_INPUT))
                .append(", ").append(f(EROSION_ROUNDING_OCTAVE)).append(")   (行 337)\n");
        sb.append("EROSION_ONSET           = vec4(").append(f(EROSION_ONSET_INPUT)).append(", ")
                .append(f(EROSION_ONSET_OCTAVE)).append(", ").append(f(EROSION_ONSET_RIDGE_INPUT))
                .append(", ").append(f(EROSION_ONSET_RIDGE_OCTAVE)).append(")   (行 344)\n");
        sb.append("EROSION_ASSUMED_SLOPE   = vec2(").append(f(EROSION_ASSUMED_SLOPE)).append(", ")
                .append(f(EROSION_ASSUMED_SLOPE_MIX)).append(")   (行 351)\n");
        sb.append("EROSION_CELL_SCALE      = ").append(f(EROSION_CELL_SCALE)).append("   (行 362)\n");
        sb.append("EROSION_NORMALIZATION   = ").append(f(EROSION_NORMALIZATION)).append("   (行 367)\n");
        sb.append("EROSION_OCTAVES         = ").append(EROSION_OCTAVES).append("   (行 371)\n");
        sb.append("EROSION_LACUNARITY      = ").append(f(EROSION_LACUNARITY)).append("   (行 374)\n");
        sb.append("EROSION_GAIN            = ").append(f(EROSION_GAIN)).append("   (行 377)\n");
        sb.append("TERRAIN_HEIGHT_OFFSET   = vec2(").append(f(TERRAIN_HEIGHT_OFFSET)).append(", ")
                .append(f(TERRAIN_HEIGHT_OFFSET_MIX)).append(")   (行 395)\n");
        sb.append("HEIGHT_FREQUENCY        = ").append(f(HEIGHT_FREQUENCY)).append("   (行 400)\n");
        sb.append("HEIGHT_AMP              = ").append(f(HEIGHT_AMP)).append("   (行 401)\n");
        sb.append("HEIGHT_OCTAVES          = ").append(HEIGHT_OCTAVES).append("   (行 405)\n");
        sb.append("HEIGHT_LACUNARITY       = ").append(f(HEIGHT_LACUNARITY)).append("   (行 408)\n");
        sb.append("HEIGHT_GAIN             = ").append(f(HEIGHT_GAIN)).append("   (行 411)\n");
        sb.append("heightFunctionScale     = ").append(f(HEIGHT_FUNCTION_SCALE)).append("   (行 435)\n");
        sb.append("Phacelle offset 实参    = 0.25   (行 201)\n");
        sb.append("TAU                     = ").append(f(TAU)).append("   (Buffer A 行 42)\n");
        sb.append("hash k                  = vec2(").append(f(HASH_KX)).append(", ")
                .append(f(HASH_KY)).append(")   (Common 行 138)\n");
        sb.append('\n');
        sb.append("[switches] 固定参数且关闭树位移；与作者演示中启用的动画和树不同\n");
        sb.append("ANIMATE_PARAMETERS = OFF   ⇒ AnimateTo/WaveTo/LoHi 全部不生效，只用初值\n");
        sb.append("TREES              = OFF   ⇒ eroded【不含】树形位移（Buffer A 行 471-481 未执行）\n");
        sb.append("COMPARISON_SLIDER  = OFF   ⇒ erosion 恒 true（行 418-428 默认分支）\n");
        sb.append("erosion            = ON    ⇒ h = ErosionFilter(...)，未走 h=vec4(0) 清零分支\n");
        sb.append('\n');
        sb.append("[known deviations / 不可伪称]\n");
        sb.append("- 只复现 2D 高度场；原版 3D 材质/法线/水体/树/DETAIL_TEXTURE 一概没有，\n");
        sb.append("  因此本输出【不能】当作原版画面或完整 Heightmap 等价物。\n");
        sb.append("- mix 用 GLSL 规范式 x*(1-a)+y*a；部分驱动实现为 x+a*(y-x)，最多差几 ULP。\n");
        sb.append("- cos/sin/exp/pow 用 StrictMath(fdlibm) 后收窄到 float；GPU 单精度实现可能差几 ULP。\n");
        sb.append("- 采样坐标用 float32 计算；若 GLSL 侧用 double/更高精度算 uv，最低位可能不同。\n");
        sb.append("- 这些差异均【未】声称已消除，对拍时请用容差判读，不要期待逐位相等。\n");
        sb.append('\n');
        sb.append("[license]\n");
        sb.append("- PhacelleNoise / ErosionFilter 版权 (c) 2025 Rune Skovbo Johansen，MPL-2.0。\n");
        sb.append("  本文件是其翻译（衍生），故本文件整体按 MPL-2.0 保留；未声明其它任何许可证。\n");
        sb.append("- hash/noised 出处为 Common.txt 行 145 所述 shadertoy XdXBRH，许可【未核实】。\n");
        sb.append("- 独立实验产物：源码在 src/diagnostics 独立 source set，不并入 sourceSets.main，\n");
        sb.append("  不进模组 jar；本文件不参与生产世界生成。\n");
        return sb.toString();
    }

    private static String f(float v) {
        return Float.toString(v) + " [0x" + Integer.toHexString(Float.floatToRawIntBits(v)) + "]";
    }

    // =========================================================================
    // --selftest：只验证本实现内部自洽，不声称等于 GLSL 真值
    // =========================================================================

    private static boolean selfTest() {
        boolean ok = true;
        System.out.println("== RuneOriginalDemo --selftest (仅内部自洽，非 GLSL 真值) ==");
        ok &= check("fract(-0.25) == 0.75", fract(-0.25f) == 0.75f);
        ok &= check("fract(1.0) == 0.0", fract(1.0f) == 0.0f);
        ok &= check("fract(-1.5) == 0.5", fract(-1.5f) == 0.5f);
        ok &= check("fract(2.75) == 0.75", fract(2.75f) == 0.75f);
        ok &= check("floorf(-0.5) == -1", floorf(-0.5f) == -1.0f);
        ok &= check("smoothStart(0.3, 0.0) 走 t>=smoothing 分支",
                smoothStart(0.3f, 0.0f) == 0.3f);
        ok &= check("powInv(0.5, 1.0) == 0.5", powInv(0.5f, 1.0f) == 0.5f);
        ok &= checkHashRange();
        ok &= checkNoisedGradient();
        ok &= checkDeterminism();
        ok &= checkZeroStrength();
        ok &= checkErodedComposition();
        System.out.println(ok ? "SELFTEST: PASS" : "SELFTEST: FAIL");
        return ok;
    }

    private static boolean check(String name, boolean pass) {
        System.out.println((pass ? "  [PASS] " : "  [FAIL] ") + name);
        return pass;
    }

    /** hash 输出必须在 [-1,1]，且不得出现 NaN/Inf。 */
    private static boolean checkHashRange() {
        boolean inRange = true;
        for (int i = -40; i <= 40 && inRange; i++) {
            for (int j = -40; j <= 40 && inRange; j++) {
                float[] h = hash(i * 0.37f, j * 0.11f);
                inRange = h[0] >= -1.0f && h[0] <= 1.0f && h[1] >= -1.0f && h[1] <= 1.0f;
            }
        }
        boolean finite = true;
        for (int i = 0; i < 32; i++) {
            float[] h = hash(i * 1.7f, -i * 2.3f);
            finite &= !Float.isNaN(h[0]) && !Float.isInfinite(h[0]);
            finite &= !Float.isNaN(h[1]) && !Float.isInfinite(h[1]);
        }
        return check("hash 值域 [-1,1] 且有限", inRange && finite);
    }

    /**
     * noised 的解析导数 vs 中心差分（宽松容差）。这【只是】结构自检：
     * 容差放宽的原因是两个 float 值相减的抵消误差，不代表 GLSL 真值。
     */
    private static boolean checkNoisedGradient() {
        float step = 1.0f / 512.0f;
        float worst = 0.0f;
        boolean ok = true;
        for (int i = 0; i < 8; i++) {
            float px = 0.13f + i * 1.37f;
            float py = -0.29f + i * 0.91f;
            float[] n0 = noised(px, py);
            float[] nx = noised(px + step, py);
            float[] nz = noised(px, py + step);
            float[] nx2 = noised(px - step, py);
            float[] nz2 = noised(px, py - step);
            float fdx = (nx[0] - nx2[0]) / (2.0f * step);
            float fdz = (nz[0] - nz2[0]) / (2.0f * step);
            worst = Math.max(worst, Math.abs(fdx - n0[1]));
            worst = Math.max(worst, Math.abs(fdz - n0[2]));
            ok &= Math.abs(fdx - n0[1]) <= 5.0e-3f + 0.02f * Math.abs(n0[1]);
            ok &= Math.abs(fdz - n0[2]) <= 5.0e-3f + 0.02f * Math.abs(n0[2]);
        }
        System.out.println("         noised 梯度最大偏差 = " + Float.toString(worst));
        return check("noised 解析梯度 ≈ 中心差分（宽松容差，非 GLSL 真值）", ok);
    }

    /** 同一输入两次求值必须逐位相等（StrictMath + float32 纯函数 ⇒ 确定性）。 */
    private static boolean checkDeterminism() {
        boolean same = true;
        float[][] probes = { { 0.0f, 0.0f }, { 0.5f, 0.5f }, { 0.0731f, 0.9127f },
                { 12.5f, -7.25f }, { 1000.125f, 999.875f }, { -0.0001f, 0.0001f } };
        for (float[] pr : probes) {
            Sample a = evaluate(pr[0], pr[1]);
            Sample b = evaluate(pr[0], pr[1]);
            same &= Float.floatToRawIntBits(a.base) == Float.floatToRawIntBits(b.base);
            same &= Float.floatToRawIntBits(a.eroded) == Float.floatToRawIntBits(b.eroded);
            same &= Float.floatToRawIntBits(a.delta) == Float.floatToRawIntBits(b.delta);
            same &= Float.floatToRawIntBits(a.ridgeMap) == Float.floatToRawIntBits(b.ridgeMap);
        }
        return check("同一 p 两次求值逐位相等", same);
    }

    /** strength=0 时 delta 与 magnitude 恒为 0，eroded == base（逐位）。 */
    private static boolean checkZeroStrength() {
        Params zero = AUTHOR.withStrength(0.0f);
        boolean ok = true;
        for (int i = 0; i < 5; i++) {
            float px = 0.11f + i * 0.77f;
            float py = 0.33f - i * 0.41f;
            float[] fr = fractalNoise(px, py, HEIGHT_FREQUENCY, HEIGHT_OCTAVES,
                    HEIGHT_LACUNARITY, HEIGHT_GAIN);
            float nx = fr[0] * HEIGHT_AMP;
            float ny = fr[1] * HEIGHT_AMP;
            float nz = fr[2] * HEIGHT_AMP;
            float ft = clamp(nx / (HEIGHT_AMP * 0.6f), -1.0f, 1.0f);
            float base = nx * 0.5f + 0.5f;
            float[] h = erosionFilter(px, py, base, ny * 0.5f, nz * 0.5f, ft, zero);
            float offset = mix(TERRAIN_HEIGHT_OFFSET, -ft, TERRAIN_HEIGHT_OFFSET_MIX) * h[3];
            float eroded = base + h[0] + offset;
            ok &= h[0] == 0.0f && h[1] == 0.0f && h[2] == 0.0f && h[3] == 0.0f;
            ok &= eroded == base;
        }
        return check("strength=0 ⇒ delta=0, magnitude=0, eroded==base（逐位）", ok);
    }

    /** eroded 必须逐位等于 (base + delta) + offset，即 evaluate() 的组合式无额外项。 */
    private static boolean checkErodedComposition() {
        boolean ok = true;
        for (int i = 0; i < 16; i++) {
            float px = i * 0.213f - 1.0f;
            float py = i * 0.157f + 0.5f;
            Sample s = evaluate(px, py);
            float recomputed = (s.base + s.delta) + s.offset;
            ok &= Float.floatToRawIntBits(recomputed) == Float.floatToRawIntBits(s.eroded);
            ok &= !Float.isNaN(s.eroded) && !Float.isInfinite(s.eroded);
            ok &= s.ridgeMap >= -1.0f && s.ridgeMap <= 1.0f;
        }
        return check("eroded == (base+delta)+offset 且有限、ridgeMap∈[-1,1]", ok);
    }
}
