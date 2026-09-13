package com.geogenesis.worldgen.terrain;

/**
 * 构造场「平行带 / 各向异性波纹」探针（★ 2026-09-12，第三次回归后新增）。
 *
 * <h3>为何需要这个探针（方法论教训）</h3>
 * <p>用户三次反馈"若干条平行带"的地形伪影，而当时<b>已有 3 个探针全部 PASS</b>：
 * <ul>
 *   <li>{@link TerrainGrainProbe}：测"边界处曲率 / 内部曲率"比值 → 波纹是<b>渐变</b>的，曲率不大</li>
 *   <li>{@link LandEConformityProbe}：测"单步跳变 &gt;0.02e" → 波纹每 1wu 只变 ~0.001e，<b>低于阈值</b></li>
 *   <li>{@link TectonicDeformProbe}：测断层崖 / 褶皱过零 → 波纹来自 T2 chain，<b>不在覆盖范围</b></li>
 * </ul>
 * 三者都是<b>幅度阈值型</b>判据。"平行带"的本质特征是<b>各向异性</b>
 * （沿边界法向剧烈起伏、沿切向平缓），而幅度可以很小 → <b>阈值型判据在原理上无法发现它</b>。
 *
 * <h3>判据：法向 vs 切向的各向异性比值</h3>
 * <p>对每个近边界采样点，取该处边界<b>法向 n</b> 与<b>切向 t</b>，以步长 h 各做中心差分：
 * <pre>
 *   dN(h) = |v(p + h*n) - v(p - h*n)|      dT(h) = |v(p + h*t) - v(p - h*t)|
 *   ratio(h) = sum dN(h) / sum dT(h)       （先对全部采样点求和再比，避免小分母放大噪声）
 * </pre>
 * <ul>
 *   <li><b>平行带</b>（值由 dist 决定）→ 沿法向跨过一条条波纹、变化剧烈；沿切向带内近似恒定
 *       → 某些 h 上 ratio <b>&gt;&gt; 1</b></li>
 *   <li><b>各向同性</b>（值由世界坐标噪声决定，与边界无关）→ 两方向统计等价
 *       → 所有 h 上 ratio <b>~= 1</b></li>
 * </ul>
 *
 * <h3>为何对多个步长取最大值</h3>
 * <p>单步长会<b>漏报</b>：波纹有确定周期（如 111wu），若 h 恰好跨过约半个周期，
 * 中心差分会出现"两端同高"的<b>相消</b> → 比值被低估
 * （实测：取 h=20 时 T2 修复前仅 1.465，几乎漏报）。
 * 取一组覆盖多个尺度的 h（{@link #STEPS}）后取最大值，则波纹<b>必然</b>在某个 h 上对齐，
 * 而各向同性噪声不会因 h 改变而系统性偏向某一方向 → 判据既灵敏又稳健。
 *
 * <h3>两个必须在实现中避开的陷阱（都踩过）</h3>
 * <ol>
 *   <li><b>差分不得跨过边界</b>：初版沿法向走 40*6=240wu，会笔直穿过 Voronoi 边界到达
 *       对面板块 → stress 由 +0.75 翻到 -0.75 → 产生<b>假 huge 比值</b>
 *       （实测 5.8~6.1，完全掩盖真信号，并让"修复后"看起来仍 FAIL）。
 *       现取 {@code MIN_D > max(STEPS)}，保证法向 ±h 永远留在同侧。</li>
 *   <li><b>必须归一化掉高斯衰减</b>：{@code boundaryStrength} 是 σ=110 的高斯，
 *       本身就有沿法向的固有梯度（这是"造山带成带"的正常几何，非伪影）。
 *       故取值统一用 {@code boundaryStrengthChained / boundaryStrength} 归一化，
 *       只保留"调制"结构本身；否则任何点都会显出各向异性。</li>
 * </ol>
 *
 * <p><b>有效性验证（变量控制，必做）</b>：判据必须在修复前的代码上 FAIL、修复后 PASS。
 * 否则会得到一个"永远 PASS 的假探针"——此前三次漏报正是这种失败模式。</p>
 *
 * <p><b>历史锚点</b>：087698c 修过一次（T5 的 {@code sin(dist)} → {@code sin(along)}），
 * 8e51a08 回退 → 波纹回归，60e182f 平滑 stress 后由"断续疤痕"变"规整波纹"故<b>更明显</b>。
 * 本探针即为此类回归的守门人。
 *
 * <p>用法：{@code gradlew runTectonicWaveProbe [seed]}
 */
public final class TectonicWaveProbe {

    /**
     * 中心差分步长集合（wu）。
     * <p>覆盖 8~56wu：小步长抓高频波纹，大步长抓中低频波纹（如 111wu 周期的最佳
     * 探测点约为半周期 55wu）。取最大值以保证灵敏度。</p>
     */
    private static final double[] STEPS = {8.0, 16.0, 24.0, 32.0, 40.0, 56.0};
    /** 只考察 dist &gt;= MIN_D 的点：须大于最大步长，保证法向差分不跨越边界。 */
    private static final double MIN_D = 80.0;
    /** 上界：避开 chain 的早退阈值（g&lt;=0.01 约 dist 330），防止"早退归零"制造假跳变。 */
    private static final double MAX_D = 300.0;

    /**
     * 各向异性比值上限。
     * <p>各向同性理论上 ~= 1（统计波动约 0.9~1.3）；平行带则远大于 1。
     * 取 <b>1.8</b>：留出约 40% 余量容忍噪声与"造山带固有方向感"，
     * 同时远小于实测的波纹值（T2 约 2.0+、T5 约 2.1+）。</p>
     */
    private static final double MAX_ANISOTROPY = 1.8;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        System.out.printf("=== TectonicWaveProbe seed=%d ===%n", seed);
        TectonicField tf = new TectonicField(seed);
        TectonicDeformation td = new TectonicDeformation(seed);

        int used = 0;
        // 每个步长一组累加器：[0]=chain 法向 [1]=chain 切向 [2]=形变法向 [3]=形变切向
        double[][] acc = new double[STEPS.length][4];

        for (double z = -6000; z <= 6000; z += 523.0) {
            for (double x = -6000; x <= 6000; x += 617.0) {
                TectonicField.Sample s = tf.sample(x, z);
                double d0 = s.dist();
                if (d0 < MIN_D || d0 >= MAX_D) continue;

                // 法向 = 切向旋转 90 度（Sample 的 tangentX/Z 为切向单位向量）
                double nx = s.tangentZ(), nz = -s.tangentX();
                double tx = s.tangentX(), tz = s.tangentZ();

                for (int i = 0; i < STEPS.length; i++) {
                    double h = STEPS[i];
                    acc[i][0] += Math.abs(chainNorm(tf, x + h * nx, z + h * nz)
                                        - chainNorm(tf, x - h * nx, z - h * nz));
                    acc[i][1] += Math.abs(chainNorm(tf, x + h * tx, z + h * tz)
                                        - chainNorm(tf, x - h * tx, z - h * tz));
                    acc[i][2] += Math.abs(deformAt(td, tf, x + h * nx, z + h * nz)
                                        - deformAt(td, tf, x - h * nx, z - h * nz));
                    acc[i][3] += Math.abs(deformAt(td, tf, x + h * tx, z + h * tz)
                                        - deformAt(td, tf, x - h * tx, z - h * tz));
                }
                used++;
            }
        }

        if (used == 0) {
            System.out.println("样本不足（检查 MIN_D/MAX_D 与种子）");
            System.exit(1);
        }

        double worstChain = 0, worstDeform = 0;
        System.out.printf("样本 %d 点（dist ∈ [%.0f, %.0f)），步长 %s%n%n", used, MIN_D, MAX_D, java.util.Arrays.toString(STEPS));
        for (int i = 0; i < STEPS.length; i++) {
            double rc = acc[i][1] > 1e-12 ? acc[i][0] / acc[i][1] : Double.POSITIVE_INFINITY;
            double rd = acc[i][3] > 1e-12 ? acc[i][2] / acc[i][3] : Double.POSITIVE_INFINITY;
            worstChain = Math.max(worstChain, rc);
            worstDeform = Math.max(worstDeform, rd);
            System.out.printf("    h=%4.0f  T2 chain ratio=%.3f   T5 deform ratio=%.3f%n", STEPS[i], rc, rd);
        }

        System.out.printf("%n[1] T2 chain  最大各向异性 = %.3f%n", worstChain);
        System.out.printf("[2] T5 形变   最大各向异性 = %.3f%n", worstDeform);
        System.out.printf("    要求两者 < %.2f（~=1 各向同性；&gt;&gt;1 = 平行于边界的波纹带）%n", MAX_ANISOTROPY);

        boolean pass = worstChain < MAX_ANISOTROPY && worstDeform < MAX_ANISOTROPY;
        System.out.printf("    结果: %s%n", pass ? "PASS" : "FAIL");
        if (!pass) {
            System.out.println("""
                -> 存在「平行于边界的带」：构造量的等值线仍与边界对齐。
                   硬约束：dist 只能用于 decay（作用范围），绝不能作噪声坐标。
                   已知历史位置：TectonicField.chainModulation（跨走向项）、
                   TectonicDeformation.foldOffset（sin(dist)）。""");
            System.exit(1);
        }
        System.out.println("=== ALL PASS ===");
    }

    /** 归一化 chain 强度 = boundaryStrengthChained / boundaryStrength（纯调制，剔除高斯衰减）。 */
    private static double chainNorm(TectonicField tf, double x, double z) {
        TectonicField.Sample s = tf.sample(x, z);
        double base = TectonicField.boundaryStrength(s);
        return base > 1e-9 ? tf.boundaryStrengthChained(s, x, z) / base : 0.0;
    }

    /** 形变偏移（e 单位）。 */
    private static double deformAt(TectonicDeformation td, TectonicField tf, double x, double z) {
        return td.offset(tf.sample(x, z), x, z);
    }
}
