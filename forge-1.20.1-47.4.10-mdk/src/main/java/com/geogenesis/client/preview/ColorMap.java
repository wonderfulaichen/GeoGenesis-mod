package com.geogenesis.client.preview;

/**
 * 零依赖连续色带（移植自 World-Preview-TFC 的 ColorMap 思路）。
 * 停靠点 + CIE-Lab 空间插值 + 烘焙 LUT。
 * 返回 RGB 整数（0xRRGGBB），调用方按需转 ABGR（MC NativeImage）或直接使用（Swing）。
 * 不 import net.minecraft，Swing 与 MC 共用。
 */
public final class ColorMap {

    private final String name;
    private final float[][] stops;   // 每个停靠点 [pos, r, g, b]，pos/r/g/b 均∈[0,1]
    private int[] lut;               // 烘焙后的 RGB(0xRRGGBB) LUT

    public ColorMap(String name, float[][] stops) {
        if (stops == null || stops.length < 2) {
            throw new IllegalArgumentException("ColorMap needs >= 2 stops: " + name);
        }
        this.name = name;
        this.stops = stops;
    }

    public String getName() {
        return name;
    }

    /** 单点查询：position∈[0,1] → RGB(0xRRGGBB)。 */
    public int getRGB(float position) {
        float p = Math.max(0f, Math.min(1f, position));
        int n = stops.length;
        if (p <= stops[0][0]) return toRGB(stops[0][1], stops[0][2], stops[0][3]);
        if (p >= stops[n - 1][0]) return toRGB(stops[n - 1][1], stops[n - 1][2], stops[n - 1][3]);
        int i = 0;
        while (i < n - 1 && p > stops[i + 1][0]) i++;
        float[] a = stops[i], b = stops[i + 1];
        float span = b[0] - a[0];
        float t = span <= 0f ? 0f : (p - a[0]) / span;
        float[] la = rgbToLab(a[1], a[2], a[3]);
        float[] lb = rgbToLab(b[1], b[2], b[3]);
        return labToRgbInt(lerp(la[0], lb[0], t), lerp(la[1], lb[1], t), lerp(la[2], lb[2], t));
    }

    /** 烘焙整条 LUT（numValues 个采样），供渲染热路径查表、零运行期分配。 */
    public int[] bake(int numValues) {
        numValues = Math.max(2, numValues);
        lut = new int[numValues];
        for (int i = 0; i < numValues; i++) {
            lut[i] = getRGB((float) i / (numValues - 1));
        }
        return lut;
    }

    /** 查表（未烘焙则先按 256 烘焙）。 */
    public int sampleBaked(int index) {
        if (lut == null) bake(256);
        if (index < 0) return lut[0];
        if (index >= lut.length) return lut[lut.length - 1];
        return lut[index];
    }

    public int[] getLut() {
        if (lut == null) bake(256);
        return lut;
    }

    // —— 内部工具 ——

    private static int toRGB(float r, float g, float b) {
        int ri = clamp255(r), gi = clamp255(g), bi = clamp255(b);
        return (ri << 16) | (gi << 8) | bi;
    }

    private static int clamp255(float v) {
        int i = Math.round(v * 255f);
        return Math.max(0, Math.min(255, i));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float[] rgbToLab(float r, float g, float b) {
        float rr = srgbToLinear(r), gg = srgbToLinear(g), bb = srgbToLinear(b);
        float X = rr * 0.4124f + gg * 0.3576f + bb * 0.1805f;
        float Y = rr * 0.2126f + gg * 0.7152f + bb * 0.0722f;
        float Z = rr * 0.0193f + gg * 0.1192f + bb * 0.9505f;
        X /= 0.95047f; Y /= 1.0f; Z /= 1.08883f;
        return new float[]{116f * fLab(Y) - 16f, 500f * (fLab(X) - fLab(Y)), 200f * (fLab(Y) - fLab(Z))};
    }

    private static float srgbToLinear(float c) {
        return c > 0.04045f ? (float) Math.pow((c + 0.055f) / 1.055f, 2.4) : c / 12.92f;
    }

    private static float fLab(float t) {
        return t > 0.008856f ? (float) Math.cbrt(t) : 7.787f * t + 16f / 116f;
    }

    private static int labToRgbInt(float L, float A, float B) {
        float fy = (L + 16f) / 116f, fx = fy + A / 500f, fz = fy - B / 200f;
        float X = invFLab(fx) * 0.95047f, Y = invFLab(fy), Z = invFLab(fz) * 1.08883f;
        float rr = X * 3.2406f + Y * (-1.5372f) + Z * (-0.4986f);
        float gg = X * (-0.9689f) + Y * 1.8758f + Z * 0.0415f;
        float bb = X * 0.0557f + Y * (-0.2040f) + Z * 1.0570f;
        rr = rr > 0.0031308f ? 1.055f * (float) Math.pow(rr, 1.0 / 2.4) - 0.055f : 12.92f * rr;
        gg = gg > 0.0031308f ? 1.055f * (float) Math.pow(gg, 1.0 / 2.4) - 0.055f : 12.92f * gg;
        bb = bb > 0.0031308f ? 1.055f * (float) Math.pow(bb, 1.0 / 2.4) - 0.055f : 12.92f * bb;
        return toRGB(rr, gg, bb);
    }

    private static float invFLab(float t) {
        return t > 0.206897f ? t * t * t : (t - 16f / 116f) / 7.787f;
    }
}
