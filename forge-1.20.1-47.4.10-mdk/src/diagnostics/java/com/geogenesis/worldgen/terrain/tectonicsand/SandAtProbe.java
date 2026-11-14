package com.geogenesis.worldgen.terrain.tectonicsand;

import java.util.Locale;

/**
 * 沙箱诊断：在指定位置沿一条线打剖面，逐点打印全部分量。
 *
 * <p>用途：判断某个"看起来不对"的地方到底是哪一项给的——
 * 是地壳映射（{@code base}）、构造抬升、还是细节噪声。</p>
 *
 * <p>用法：{@code SandAtProbe <z> <x0> <x1> <n> [x|z]}
 * （给了 {@code z} 就沿 z 扫）。</p>
 */
public final class SandAtProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) {
        double z = args.length > 0 ? Double.parseDouble(args[0]) : 500;
        double x0 = args.length > 1 ? Double.parseDouble(args[1]) : 250;
        double x1 = args.length > 2 ? Double.parseDouble(args[2]) : 450;
        int n = args.length > 3 ? Integer.parseInt(args[3]) : 41;
        boolean alongZ = args.length > 4 && "z".equals(args[4]);
        System.out.printf(Locale.ROOT,
                "%7s %8s %8s %8s %8s %8s %8s %7s %6s %6s  %s%n",
                alongZ ? "z" : "x", "h(格)", "base", "orogen", "trench", "detail", "islet",
                "dist", "crust", "divN", "plate");
        for (int i = 0; i < n; i++) {
            double c = x0 + (x1 - x0) * i / (n - 1.0);
            double x = alongZ ? z : c;
            double zz = alongZ ? c : z;
            SandPlateField.Sample s = SandPlateField.sample(SEED, x, zz);
            SandTerrain.Parts p = SandTerrain.partsOf(s);
            double divN = SandTerrain.boundary(s).div() / SandTerrain.DIV_SCALE;
            double crust = SandTerrain.crust01(s.seed(), s.warpedX(), s.warpedZ());
            System.out.printf(Locale.ROOT,
                    "%7.1f %8.1f %8.3f %8.3f %8.3f %8.3f %8.3f %7.1f %6.3f %6.2f  (%d,%d)nb=(%d,%d)%n",
                    c, p.sum() * 236, p.base(), p.orogen(), p.trench(), p.detail(), p.islet() * 236,
                    s.dist(), crust, divN,
                    s.plate().cx(), s.plate().cz(), s.neighbor().cx(), s.neighbor().cz());
        }
    }
}