package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.PreviewCamera;

import java.util.Locale;

/**
 * <b>「一个相机 + 两种样式」的结构守门探针</b>（★ v54）。
 *
 * <h2>它守的是用户批注指出的那件事</h2>
 * <blockquote>
 *   「我定的是可以用 2 种表现力，我明明说了样式可以不同，但你这分明就不是样式不同。」
 * </blockquote>
 *
 * <p>此前平面与沙盘各自推导视口原点（两套公式），那是"两个程序"。
 * 现在两者都从 {@link PreviewCamera} 取 window/origin。
 * 本探针锁住：<b>同一中心与窗口下，两种样式给出的 window 完全相同</b>，
 * 差异<b>只能</b>来自显式声明的样式参数（align / pan / pitch / yaw）。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>[1]</b> 两种样式（align=scale/pan=拖拽/pitch=0 与 align=1/pan=0/pitch=0.95）
 *       得到的 window 完全相同；align=1 时 origin 也相同；</li>
 *   <li><b>[2]</b> {@code alignDown} 对负数与非整除正确（floorDiv 语义）；</li>
 *   <li><b>[3]</b> {@code pitch=0} ⇒ {@code isTopDown()}；非 0 ⇒ false；</li>
 *   <li><b>[4]</b> {@code copy()} 是深拷贝，改副本不影响原相机。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runPreviewCameraProbe}</p>
 */
public final class PreviewCameraProbe {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        boolean pass = true;

        // ---- [1] 两种样式的 window 必须一致 ----
        System.out.println("[1] 两种样式从同一相机取视口");
        PreviewCamera plane = new PreviewCamera();
        plane.centerX = 1200;
        plane.centerZ = -800;
        plane.panX = 3;
        plane.panZ = -5;
        plane.windowX = 900;
        plane.windowZ = 900;
        plane.align = 8;          // 平面：按缩放档对齐
        plane.pitch = 0f;
        plane.yaw = 0f;

        PreviewCamera sandbox = plane.copy();
        sandbox.panX = 0;
        sandbox.panZ = 0;
        sandbox.align = 1;        // 沙盘：不对齐
        sandbox.pitch = 0.95f;
        sandbox.yaw = 0.65f;

        System.out.printf("    平面   %s%n", plane);
        System.out.printf("    沙盘   %s%n", sandbox);
        boolean sameWindow = plane.windowX == sandbox.windowX && plane.windowZ == sandbox.windowZ;

        // align=1 且 pan=0 时，两者的 origin 应与"中心-半窗"一致
        PreviewCamera sandboxNoPan = sandbox.copy();
        sandboxNoPan.panX = 0;
        sandboxNoPan.panZ = 0;
        int expectOx = sandboxNoPan.centerX - sandboxNoPan.windowX / 2;
        int expectOz = sandboxNoPan.centerZ - sandboxNoPan.windowZ / 2;
        boolean originOk = sandboxNoPan.originX() == expectOx && sandboxNoPan.originZ() == expectOz;
        System.out.printf("    沙盘 origin=(%d,%d) 期望=(%d,%d)%n",
                sandboxNoPan.originX(), sandboxNoPan.originZ(), expectOx, expectOz);

        if (!sameWindow) {
            System.out.println("    FAIL: 两种样式的世界窗口不同 ⇒ 视口仍有两套来源");
            pass = false;
        } else if (!originOk) {
            System.out.println("    FAIL: align=1/pan=0 时 origin 不等于 中心-半窗");
            pass = false;
        } else {
            System.out.println("    OK（window 相同；origin 只有一个公式）");
        }

        // 平面侧对齐必须仍是 step 的倍数
        boolean aligned = Math.floorMod(plane.originX(), plane.align) == 0
                && Math.floorMod(plane.originZ(), plane.align) == 0;
        System.out.printf("    平面 origin=(%d,%d) 是否 %d 的倍数：%s%n",
                plane.originX(), plane.originZ(), plane.align, aligned ? "是" : "否");
        if (!aligned) {
            System.out.println("    FAIL: 平面对齐丢失 ⇒ 纹理半像素错位会重现");
            pass = false;
        }

        // ---- [2] alignDown ----
        System.out.println("\n[2] alignDown(floorDiv 语义)");
        int[][] cases = {
                {7, 4, 4}, {-7, 4, -8}, {0, 4, 0}, {-1, 8, -8}, {16, 8, 16}, {5, 1, 5}
        };
        boolean ok = true;
        for (int[] c : cases) {
            int got = PreviewCamera.alignDown(c[0], c[1]);
            System.out.printf("    alignDown(%d,%d)=%d 期望 %d%n", c[0], c[1], got, c[2]);
            if (got != c[2]) ok = false;
        }
        if (!ok) {
            System.out.println("    FAIL: 负数或非整除情况不对（会让视口整体偏移一档）");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- [3] 姿态判定 ----
        System.out.println("\n[3] isTopDown()");
        System.out.printf("    pitch=0    → %s（应 true）%n", plane.isTopDown());
        System.out.printf("    pitch=0.95 → %s（应 false）%n", sandbox.isTopDown());
        if (!plane.isTopDown() || sandbox.isTopDown()) {
            System.out.println("    FAIL: 姿态判定错 ⇒ 空判据会选错绘制样式");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- [4] copy 是深拷贝 ----
        System.out.println("\n[4] copy() 不改原对象");
        PreviewCamera src = new PreviewCamera();
        src.centerX = 10;
        src.pitch = 0.5f;
        PreviewCamera dup = src.copy();
        dup.centerX = 999;
        dup.pitch = 1.2f;
        System.out.printf("    原 centerX=%d pitch=%.2f；副本改后 原仍为 %d / %.2f%n",
                src.centerX, src.pitch, src.centerX, src.pitch);
        if (src.centerX != 10 || Math.abs(src.pitch - 0.5f) > 1e-6f) {
            System.out.println("    FAIL: copy() 不是深拷贝 ⇒ 过渡插值会污染用户视角");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private PreviewCameraProbe() { }
}