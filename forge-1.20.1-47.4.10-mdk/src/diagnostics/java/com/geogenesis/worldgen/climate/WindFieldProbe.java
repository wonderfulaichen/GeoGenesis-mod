package com.geogenesis.worldgen.climate;

/**
 * {@link WindField}（Phase A）验证探针（2026-09-11）。
 *
 * <p>验证"气压驱动风场"是否产出正确的<b>涌现</b>风带：
 * <ol>
 *   <li>纬向气压廓线极值位置（0 / 1/3 / 2/3 / 1）；</li>
 *   <li><b>风带方向</b>：信风（lat≈1/6）平均 vx&lt;0、西风（0.5）&gt;0、极地东风（0.83）&lt;0；</li>
 *   <li><b>ITCZ 风速最低</b>（与信风/西风峰值比较）；</li>
 *   <li>确定性（同 seed 两次逐位一致）+ 单位向量归一化 + 无 NaN。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runWindFieldProbe [seed]}</p>
 */
public final class WindFieldProbe {

    /** 与 {@code Latitude.DEFAULT_SCALE} 一致（纬度轴尺度：|z|=5000 → lat01=1）。 */
    private static final double LAT_SCALE = 5000.0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        WindField.Params p = WindField.Params.defaults();
        System.out.printf("=== WindField probe seed=%d (latScale=%.0f) ===%n", seed, LAT_SCALE);

        // ---- [1] 气压廓线极值位置
        boolean pass1 = true;
        StringBuilder sb = new StringBuilder("[1] latPressure 极值: ");
        double[] extremes = {0.0, 1.0 / 3, 2.0 / 3, 1.0};
        for (double lat : extremes) {
            double v = WindField.latPressure(lat);
            boolean low = Math.abs(lat) < 1e-9 || Math.abs(lat - 2.0 / 3) < 1e-9;
            double expect = low ? -1.0 : 1.0;
            boolean ok = Math.abs(v - expect) < 1e-9;
            pass1 &= ok;
            sb.append(String.format("lat=%.3f→%+.3f(%s) ", lat, v, low ? "低压" : "高压"));
        }
        System.out.println(sb + (pass1 ? "PASS" : "FAIL"));

        // ---- [2] 风带方向（沿 x 求平均，压制噪声扰动）
        double tradeVx = meanVx(seed, 1.0 / 6, p);
        double westVx = meanVx(seed, 0.5, p);
        double polarVx = meanVx(seed, 0.83, p);
        boolean pass2 = tradeVx < 0 && westVx > 0 && polarVx < 0;
        System.out.printf("[2] 风带方向(平均vx): 信风(1/6)=%+.4f 西风(0.5)=%+.4f 极地东风(0.83)=%+.4f %s%n",
            tradeVx, westVx, polarVx, pass2 ? "PASS" : "FAIL");
        System.out.println("    要求: 信风<0(东→西) 西风>0(西→东) 极地东风<0");

        // ---- [3] ITCZ 风速最低
        double sItcz = meanSpeed(seed, 0.0, p);
        double sSubHigh = meanSpeed(seed, 1.0 / 3, p);
        double sTrade = meanSpeed(seed, 1.0 / 6, p);
        double sWest = meanSpeed(seed, 0.5, p);
        boolean pass3 = sItcz < sTrade * 0.5 && sItcz < sWest * 0.5 && sItcz <= sSubHigh + 1e-9;
        System.out.printf("[3] 风速: ITCZ(0)=%.4f 副高(1/3)=%.4f 信风峰(1/6)=%.4f 西风峰(0.5)=%.4f %s%n",
            sItcz, sSubHigh, sTrade, sWest, pass3 ? "PASS" : "FAIL");

        // ---- [4] 确定性 + 归一化 + 无 NaN
        int bad = 0, nonUnit = 0, nan = 0, n4 = 0;
        for (int i = 0; i < 240; i++) {
            double x = i * 73 - 7000, z = i * 51 - 5000;
            WindField.Wind a = WindField.sample(seed, x, z, LAT_SCALE, p);
            WindField.Wind b = WindField.sample(seed, x, z, LAT_SCALE, p);
            n4++;
            if (Math.abs(a.x() - b.x()) > 1e-12 || Math.abs(a.z() - b.z()) > 1e-12
                    || Math.abs(a.speed() - b.speed()) > 1e-12) bad++;
            if (!Double.isFinite(a.x()) || !Double.isFinite(a.z()) || !Double.isFinite(a.speed())) nan++;
            double mag = Math.hypot(a.x(), a.z());
            if (a.speed() > 0 && Math.abs(mag - 1.0) > 1e-9) nonUnit++;
            if (a.speed() == 0 && mag != 0.0) nonUnit++;
        }
        boolean pass4 = bad == 0 && nonUnit == 0 && nan == 0 && n4 > 0;
        System.out.printf("[4] 确定性/归一化/NaN: n=%d 不一致=%d 非单位=%d NaN=%d %s%n",
            n4, bad, nonUnit, nan, pass4 ? "PASS" : "FAIL");

        // ---- [5] 风带剖面（目视）
        System.out.println("[5] 风带剖面（各纬度沿 x 平均）:");
        System.out.println("    lat01   带                风速     vx       vz");
        for (int i = 0; i <= 12; i++) {
            double lat = i / 12.0;
            double z = lat * LAT_SCALE;
            double sv = 0, sx = 0, sz = 0;
            int n = 0;
            for (double x = -4000; x <= 4000; x += 137) {
                WindField.Wind w = WindField.sample(seed, x, z, LAT_SCALE, p);
                sv += w.speed(); sx += w.vx(); sz += w.vz(); n++;
            }
            System.out.printf("    %.3f   %-16s %.4f  %+.4f  %+.4f%n",
                lat, beltName(lat), sv / n, sx / n, sz / n);
        }

        // ---- [6] ITCZ 边界方向连续性（2026-09-11 修正后新增）
        //   初版在 lat01=itczHalfWidth 硬切换分支 → 该纬度圈上出现风向突变，
        //   会在 Phase B 地形雨里刻出【假的降水线】。改为 smoothstep 过渡后应连续。
        double maxJump = 0;
        double prevX = Double.NaN, prevZ = Double.NaN;
        for (double z = (0.12 - 0.10) * LAT_SCALE; z <= (0.12 + 0.10) * LAT_SCALE; z += 2.0) {
            WindField.Wind w = WindField.sample(seed, 1500, z, LAT_SCALE, p);
            if (!Double.isNaN(prevX) && w.speed() > 0.02) {
                double dot = Math.max(-1.0, Math.min(1.0, prevX * w.x() + prevZ * w.z()));
                maxJump = Math.max(maxJump, Math.toDegrees(Math.acos(dot)));
            }
            prevX = w.x();
            prevZ = w.z();
        }
        boolean pass6 = maxJump < 15.0;
        System.out.printf("[6] ITCZ 边界方向连续性: 2wu 步长内最大转角=%.2f° (<15°) %s%n",
            maxJump, pass6 ? "PASS" : "FAIL");

        boolean all = pass1 && pass2 && pass3 && pass4 && pass6;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
    }

    private static String beltName(double lat01) {
        if (lat01 < 0.12) return "ITCZ 赤道低压";
        if (lat01 < 0.25) return "信风带";
        if (lat01 < 0.42) return "副热带高压(马纬度)";
        if (lat01 < 0.62) return "西风带";
        if (lat01 < 0.75) return "副极地低压";
        return "极地东风";
    }

    private static double meanVx(long seed, double lat01, WindField.Params p) {
        double z = lat01 * LAT_SCALE;
        double sum = 0;
        int n = 0;
        for (double x = -4000; x <= 4000; x += 137) {
            sum += WindField.sample(seed, x, z, LAT_SCALE, p).vx();
            n++;
        }
        return sum / n;
    }

    private static double meanSpeed(long seed, double lat01, WindField.Params p) {
        double z = lat01 * LAT_SCALE;
        double sum = 0;
        int n = 0;
        for (double x = -4000; x <= 4000; x += 137) {
            sum += WindField.sample(seed, x, z, LAT_SCALE, p).speed();
            n++;
        }
        return sum / n;
    }
}
