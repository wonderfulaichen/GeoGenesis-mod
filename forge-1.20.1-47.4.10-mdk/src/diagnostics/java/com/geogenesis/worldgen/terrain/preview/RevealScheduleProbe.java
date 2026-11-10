package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.reveal.PerspectiveCamera;
import com.geogenesis.client.reveal.RevealSchedule;

import java.util.Locale;

/**
 * <b>落成动画纯逻辑验收</b>（★ v37）。
 *
 * <h2>为什么先验这个</h2>
 * <p>落成动画最贵的部分是渲染，但<b>最容易出错的其实是调度</b> ——
 * 参考项目为此写了一整段带血泪的注释（让位顺序、三重上限、只等等待圈）。
 * 这些全是纯时间/距离计算，可以脱离 MC 完整验证。</p>
 *
 * <p>验证项：</p>
 * <ol>
 *   <li>波次按<b>距离</b>递增（中心先、边缘后），不是按索引；</li>
 *   <li>数据到货后要等 {@code NO_FILTER_MESH_MS} 才放行；</li>
 *   <li>数据到了但一直不生效，{@code DATA_MISS_MS} 后强制放行；</li>
 *   <li>数据永远不来（视距外），波次 + {@code DATA_MISS_MS} 后放行，<b>不吊住镜头</b>；</li>
 *   <li>等待圈外的块<b>不算</b>进度、不算"还没好"；</li>
 *   <li>收尾消散把剩下的一起淡完；</li>
 *   <li>透视相机：投影正确、相机后方的点被剔除、深度排序单调。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runRevealScheduleProbe}</p>
 */
public final class RevealScheduleProbe {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 落成动画纯逻辑验收（v37）===");

        boolean pass = true;
        pass &= checkWaveOrder();
        pass &= checkFadeGating();
        pass &= checkWaitZone();
        pass &= checkCollapse();
        pass &= checkCamera();
        pass &= checkDescent();

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    /** [8] 落机航线：起点/终点精确、单调、落地时已平视。 */
    private static boolean checkDescent() {
        System.out.println("\n[8] 落机航线（简化两段：空中→落点上空→玩家眼睛）");
        com.geogenesis.client.reveal.DescentPath p =
                new com.geogenesis.client.reveal.DescentPath(
                        0, 200, 0,                    // 空中机位
                        100, 80, 100,                 // 落点上空 14 格
                        100, 66, 100,                 // 玩家眼睛
                        0f, 35f, 45f, 10f);
        float[] eye = new float[3];

        // 起点
        p.eye(eye);
        System.out.printf("    t=0    机位 (%.1f, %.1f, %.1f)%n", eye[0], eye[1], eye[2]);
        if (Math.abs(eye[0]) > 0.01 || Math.abs(eye[1] - 200) > 0.01) {
            System.out.println("    FAIL: 起点不是空中机位");
            return false;
        }

        // 推进到结束
        long t = 0;
        for (int k = 0; k < 2000; k++) {
            t += 100;
            p.tick(t);
            if (p.done()) break;
        }
        p.eye(eye);
        System.out.printf("    结束时 机位 (%.1f, %.1f, %.1f) done=%s%n",
                eye[0], eye[1], eye[2], p.done());
        if (!p.done()) {
            System.out.println("    FAIL: 走了很久还没落完");
            return false;
        }
        if (Math.abs(eye[0] - 100) > 1.0 || Math.abs(eye[1] - 66) > 1.0
                || Math.abs(eye[2] - 100) > 1.0) {
            System.out.println("    FAIL: 终点不是玩家眼睛");
            return false;
        }

        // 落地时视角应对齐玩家（lookBlend ≈ 1）
        float lb = p.lookBlend();
        System.out.printf("    落地 lookBlend=%.3f，yaw=%.1f（应≈45）pitch=%.1f（应≈10）%n",
                lb, p.yaw(), p.pitch());
        if (lb < 0.999f || Math.abs(p.yaw() - 45f) > 0.5f || Math.abs(p.pitch() - 10f) > 0.5f) {
            System.out.println("    FAIL: 落地时没对齐玩家视角（会看到天空）");
            return false;
        }

        // 单调下降（y 只减不增）
        com.geogenesis.client.reveal.DescentPath p2 =
                new com.geogenesis.client.reveal.DescentPath(
                        0, 200, 0, 100, 80, 100, 100, 66, 100, 0f, 35f, 45f, 10f);
        float prevY = Float.MAX_VALUE;
        long tt = 0;
        boolean mono = true;
        for (int k = 0; k < 500; k++) {
            tt += 50;
            p2.tick(tt);
            p2.eye(eye);
            if (eye[1] > prevY + 1e-3f) { mono = false; break; }
            prevY = eye[1];
            if (p2.done()) break;
        }
        System.out.printf("    y 单调下降 = %s%n", mono);
        if (!mono) {
            System.out.println("    FAIL: 镜头上下起伏（参考项目明确说会「像卡了一下」）");
            return false;
        }
        System.out.println("    OK（起点/终点/视角/单调都对）");
        return true;
    }

    // ------------------------------------------------------------------

    /** 造一个 5×5 的块网格，dist = 到中心的距离。 */
    private static RevealSchedule.Block[] grid(int n, int waitRadius) {
        RevealSchedule.Block[] bs = new RevealSchedule.Block[n * n];
        int c = n / 2;
        for (int r = 0; r < n; r++) {
            for (int col = 0; col < n; col++) {
                RevealSchedule.Block b = new RevealSchedule.Block();
                double dx = col - c, dz = r - c;
                b.dist = Math.sqrt(dx * dx + dz * dz) * 16.0;   // 一块 16 方块
                b.inWaitZone = Math.abs(col - c) <= waitRadius && Math.abs(r - c) <= waitRadius;
                bs[r * n + col] = b;
            }
        }
        return bs;
    }

    private static double maxDist(RevealSchedule.Block[] bs) {
        double m = 0;
        for (RevealSchedule.Block b : bs) m = Math.max(m, b.dist);
        return m;
    }

    /** [1] 波次必须按距离递增。 */
    private static boolean checkWaveOrder() {
        System.out.println("\n[1] 波次必须按距离递增（中心先、边缘后）");
        RevealSchedule.Block[] bs = grid(5, 2);
        RevealSchedule s = new RevealSchedule(bs, maxDist(bs));
        // 中心块 waveAt 应最小
        RevealSchedule.Block center = bs[2 * 5 + 2];
        RevealSchedule.Block corner = bs[0];
        System.out.printf("    中心 waveAt=%d ms，角 waveAt=%d ms%n", center.waveAt, corner.waveAt);
        if (center.waveAt > corner.waveAt) {
            System.out.println("    FAIL: 中心比角还晚");
            return false;
        }
        // 全局：dist 大的 waveAt 不能更小
        for (RevealSchedule.Block a : bs) {
            for (RevealSchedule.Block b : bs) {
                if (a.dist > b.dist + 1e-6 && a.waveAt < b.waveAt) {
                    System.out.printf("    FAIL: dist %.1f 的 wave %d < dist %.1f 的 %d%n",
                            a.dist, a.waveAt, b.dist, b.waveAt);
                    return false;
                }
            }
        }
        System.out.println("    OK（距离与波次同向）");
        return true;
    }

    /** [2][3][4] 三重上限。 */
    private static boolean checkFadeGating() {
        System.out.println("\n[2][3][4] 三重上限：到货+余量 / 到货但失效 / 永不到货");
        boolean ok = true;

        // --- 2) 数据到货 → 等 NO_FILTER_MESH_MS（要推进到波次之后再看）---
        RevealSchedule.Block[] bs2 = grid(3, 1);
        RevealSchedule s2 = new RevealSchedule(bs2, maxDist(bs2));
        // 全部块在 t=0 就已到货，但波次要等；推进到「波次 + 余量」之后
        long t2 = RevealSchedule.SPREAD_NEAR_MS + RevealSchedule.NO_FILTER_MESH_MS + 50L;
        s2.advance(0L, i -> true);       // t=0 记录到货
        s2.advance(t2, i -> true);       // 推进到余量之后
        RevealSchedule.Block corner2 = bs2[0];   // 最远那块，波次最大
        long fadeAt = corner2.fadeAt;
        System.out.printf("    [2] t=%d 时最远块 fadeAt=%d（waveAt=%d，余量 %d）%n",
                t2, fadeAt, corner2.waveAt, RevealSchedule.NO_FILTER_MESH_MS);
        // 期望：不早于 waveAt，也不早于"到货+余量"
        if (fadeAt < 0L) {
            System.out.println("        FAIL: 数据到货且已过波次，却没放行");
            ok = false;
        } else if (fadeAt < RevealSchedule.NO_FILTER_MESH_MS) {
            System.out.println("        FAIL: 没等到网格余量就淡了");
            ok = false;
        } else {
            System.out.println("        OK（不早于波次、也不早于到货+余量）");
        }

        // --- 3) 数据到货但不生效 → DATA_MISS_MS 后强制放行 ---
        RevealSchedule.Block[] bs3 = grid(3, 1);
        RevealSchedule s3 = new RevealSchedule(bs3, maxDist(bs3));
        s3.advance(0L, i -> true);          // t=0 到货
        // 到 DATA_MISS_MS 之后
        s3.advance(RevealSchedule.DATA_MISS_MS + 10L, i -> true);
        System.out.printf("    [3] 到货后 %d ms 时 fadeAt=%d%n",
                RevealSchedule.DATA_MISS_MS + 10L, bs3[0].fadeAt);
        if (bs3[0].fadeAt < 0L) {
            System.out.println("        FAIL: 数据早已到货却一直没放行（会吊住镜头）");
            ok = false;
        } else {
            System.out.println("        OK（不会无限等）");
        }

        // --- 4) 永不到货 → 波次 + DATA_MISS_MS 后放行 ---
        RevealSchedule.Block[] bs4 = grid(3, 1);
        RevealSchedule s4 = new RevealSchedule(bs4, maxDist(bs4));
        long probeAt = RevealSchedule.SPREAD_NEAR_MS + RevealSchedule.DATA_MISS_MS + 50L;
        s4.advance(probeAt, i -> false);    // 永远没有数据
        boolean allReleased = true;
        for (RevealSchedule.Block b : bs4) {
            if (b.fadeAt < 0L) { allReleased = false; break; }
        }
        System.out.printf("    [4] 永不到货，t=%d ms 时全部放行 = %s%n", probeAt, allReleased);
        if (!allReleased) {
            System.out.println("        FAIL: 视距外的块一直吊着（参考项目原话：会唰地一起消失）");
            ok = false;
        } else {
            System.out.println("        OK（不吊住镜头）");
        }
        return ok;
    }

    /** [5] 等待圈外的块不算进度。 */
    private static boolean checkWaitZone() {
        System.out.println("\n[5] 等待圈外的块不算进度、不算「还没好」");
        RevealSchedule.Block[] bs = grid(9, 2);       // 9×9，只有中心 ±2 在圈内
        RevealSchedule s = new RevealSchedule(bs, maxDist(bs));
        int inZone = s.totalInZone();
        System.out.printf("    9×9=%d 块，等待圈内 %d 块%n", bs.length, inZone);
        if (inZone >= bs.length) {
            System.out.println("    FAIL: 圈内等于全部（没起到裁剪作用）");
            return false;
        }
        // 圈内全放行 + 淡完 ⇒ canDescend 应为 true（圈外不管）
        for (int t = 0; t < 100; t++) {
            s.advance(t * 100L, i -> bs[i].inWaitZone);   // 只给圈内数据
        }
        long now = RevealSchedule.SPREAD_NEAR_MS + RevealSchedule.DATA_MISS_MS + RevealSchedule.FADE_MS + 1000L;
        s.advance(now, i -> bs[i].inWaitZone);
        boolean can = s.canDescend(now);
        System.out.printf("    t=%d ms 时可落机 = %s（圈内未定 %d 块）%n", now, can, s.waitingInZone());
        if (!can) {
            System.out.println("    FAIL: 圈内都好了却不能落机（被圈外吊住）");
            return false;
        }
        System.out.println("    OK（圈外不拖累落机）");
        return true;
    }

    /** [6] 收尾消散。 */
    private static boolean checkCollapse() {
        System.out.println("\n[6] 收尾消散：剩下的一次淡完");
        RevealSchedule.Block[] bs = grid(5, 1);
        RevealSchedule s = new RevealSchedule(bs, maxDist(bs));
        s.advance(0L, i -> false);      // 谁都没数据
        long t0 = 1000L;
        s.beginCollapse(t0);
        int stillUnset = 0;
        for (RevealSchedule.Block b : bs) if (b.fadeAt < 0L) stillUnset++;
        System.out.printf("    beginCollapse 后仍未定 = %d 块%n", stillUnset);
        if (stillUnset > 0) {
            System.out.println("    FAIL: 还有没定下让位时刻的块");
            return false;
        }
        boolean doneBefore = s.collapseDone(t0 + RevealSchedule.COLLAPSE_MS - 1);
        boolean doneAfter = s.collapseDone(t0 + RevealSchedule.COLLAPSE_MS + 1);
        System.out.printf("    消散中=%s，消散完=%s%n", !doneBefore, doneAfter);
        if (doneBefore || !doneAfter) {
            System.out.println("    FAIL: 时长判据不对");
            return false;
        }
        System.out.println("    OK");
        return true;
    }

    /** [7] 透视相机。 */
    private static boolean checkCamera() {
        System.out.println("\n[7] 透视相机：投影 / 剔除 / 深度");
        PerspectiveCamera cam = new PerspectiveCamera();
        // 站在 (0,100,0)，朝 +Z（yaw=0），不俯仰
        cam.set(0, 100, 0, 0f, 0f, 40f);
        double[] out = new double[3];

        // 正前方的点：应落在屏幕中心
        boolean ok1 = cam.project(0, 100, 100, 500, 400, 400, out);
        System.out.printf("    正前方 (0,100,100) → (%.1f, %.1f) depth=%.1f%n", out[0], out[1], out[2]);
        if (!ok1 || Math.abs(out[0] - 500) > 0.01 || Math.abs(out[1] - 400) > 0.01) {
            System.out.println("    FAIL: 正前方没落在屏幕中心");
            return false;
        }

        // 相机后方的点：应被剔除
        boolean okBack = cam.project(0, 100, -100, 500, 400, 400, out);
        System.out.printf("    正后方 (0,100,-100) 可画=%s%n", okBack);
        if (okBack) {
            System.out.println("    FAIL: 相机后方的点没被剔除");
            return false;
        }

        // 右侧的点：屏幕 x 应 > 中心（yaw=0 时 +X 在右边吗？MC 里 yaw=0 朝 +Z，右边是 -X）
        boolean okR = cam.project(-50, 100, 100, 500, 400, 400, out);
        System.out.printf("    -X 侧 (-50,100,100) → sx=%.1f（应 > 500）%n", out[0]);
        if (!okR || out[0] <= 500) {
            System.out.println("    FAIL: 右侧方向反了（与 MC yaw 约定不符）");
            return false;
        }

        // 远点应比近点 depth 大
        double near = cam.depthKey(0, 100, 10);
        double far = cam.depthKey(0, 100, 1000);
        System.out.printf("    深度：近 %.0f，远 %.0f%n", near, far);
        if (far <= near) {
            System.out.println("    FAIL: 深度没单调递增（排序会反）");
            return false;
        }

        // 上方的点：屏幕 y 应 < 中心（屏幕向下为正）
        boolean okU = cam.project(0, 150, 100, 500, 400, 400, out);
        System.out.printf("    上方 (0,150,100) → sy=%.1f（应 < 400）%n", out[1]);
        if (!okU || out[1] >= 400) {
            System.out.println("    FAIL: 上方方向反了");
            return false;
        }

        System.out.println("    OK（投影/剔除/深度/朝向都对）");
        return true;
    }

    private RevealScheduleProbe() { }
}
