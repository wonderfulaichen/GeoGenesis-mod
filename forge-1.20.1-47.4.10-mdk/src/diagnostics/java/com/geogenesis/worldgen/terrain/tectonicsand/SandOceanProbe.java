package com.geogenesis.worldgen.terrain.tectonicsand;

import java.util.Locale;

/**
 * 沙箱诊断：<b>洋底剖面体检</b>——沿一条线打出"深度 + 各构造分量"，用来对照地质学。
 *
 * <p>地质学的洋盆横剖面（从洋中脊到大陆架）应当依次是：</p>
 * <pre>
 *   洋中脊轴（−2500 m，慢速脊有 1~2 km 深的轴部裂谷）
 *   → 脊翼（随洋壳年龄按 √t 加深）
 *   → 深海平原（−5000 m 上下，极平：沉积覆盖）
 *   → 海沟（−6000~−11000 m，窄、向陆侧陡）
 *   → 岛弧 / 弧后盆地
 *   → 大陆隆起（−4000→−3000 m 的缓肩）
 *   → 大陆坡（−3000→−200 m，坡度 3~6°）
 *   → 大陆架（−200→0 m，坡度 0.1°）
 * </pre>
 * <p>本探针只报数，不做判定——判定写进 audit。</p>
 *
 * <p>用法：{@code SandOceanProbe <z> <x0> <x1> <step>}</p>
 */
public final class SandOceanProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) {
        double z = args.length > 0 ? Double.parseDouble(args[0]) : 0;
        double x0 = args.length > 1 ? Double.parseDouble(args[1]) : -1750;
        double x1 = args.length > 2 ? Double.parseDouble(args[2]) : 1750;
        double step = args.length > 3 ? Double.parseDouble(args[3]) : 20;
        System.out.printf(Locale.ROOT,
                "%8s %9s %8s %8s %8s %8s %8s  %s%n",
                "x", "depth(m)", "base", "orogen", "trench", "islet", "crust", "regime");
        for (double x = x0; x <= x1 + 1e-9; x += step) {
            SandPlateField.Sample s = SandPlateField.sample(SEED, x, z);
            SandTerrain.Parts p = SandTerrain.partsOf(s);
            double crust = SandTerrain.crust01(s.seed(), s.warpedX(), s.warpedZ());
            double e = p.sum();
            String regime;
            if (e > 0) {
                regime = "陆地";
            } else if (p.trench() < -0.02) {
                regime = "海沟";
            } else if (p.orogen() > 0.02) {
                regime = "隆起(脊/弧)";
            } else if (crust < 0.20) {
                regime = "深海平原";
            } else if (crust < 0.42) {
                regime = "陆坡";
            } else {
                regime = "陆架";
            }
            System.out.printf(Locale.ROOT,
                    "%8.0f %9.0f %8.3f %8.3f %8.3f %8.3f %8.3f  %s%n",
                    x, e * SandTerrain.METERS_PER_E, p.base(), p.orogen(), p.trench(),
                    p.islet(), crust, regime);
        }
    }
}