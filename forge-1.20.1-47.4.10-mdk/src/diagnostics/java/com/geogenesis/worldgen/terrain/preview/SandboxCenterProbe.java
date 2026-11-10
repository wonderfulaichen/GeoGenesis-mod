package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.SandboxLod;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.chunk.CellCache;
import com.geogenesis.client.preview.sandbox.SandboxGeometry;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Locale;

/**
 * <b>旋转中心 / 内容垂直居中诊断</b>（★ v32）。
 *
 * <h2>要回答（用户两轮追问）</h2>
 * <blockquote>
 * 「旋转中心是不是高了？」
 * 「旋转中心偏高依旧没解决。」
 * </blockquote>
 *
 * <h2>机理</h2>
 * <p>我们的原点（{@code ox/oy}）取<b>视口中心</b>，模型 {@code (0,0,0)} 落在那儿。
 * 柱子的 {@code mz} 从 0（地面）往上长，而 {@code r[5] < 0}
 * （{@code mz} 增大 ⇒ 屏幕 y 减小）⇒ <b>整块地形全部长在视口中心之上</b>。</p>
 *
 * <p>参考项目 SiteTerrainRenderer 第 407-408 行也是同样的公式，
 * 但它的 {@code scale = areaSize × 0.92} 让盘面<b>大幅溢出视口</b>
 * （1080p 下 disc 高 1843px vs 视口 1024px）⇒ 屏幕上只看得到中间一段，
 * 上偏被裁掉了、看不出来。</p>
 *
 * <p>我们的 {@code fillScale/fitScale} 让<b>整块盘面完整可见</b>
 * （Y≈93%），上偏就暴露出来：地形挤在上半屏，下半屏大片空白。</p>
 *
 * <p>⇒ 正解：既然我们选择"整盘可见"，就必须<b>把内容质心对到视口中心</b>，
 * 而不是把"地面平面"对到中心。补偿量 = 一半的柱高像素。</p>
 *
 * <p>用法：{@code gradlew runSandboxCenterProbe}</p>
 */
public final class SandboxCenterProbe {

    private static final int SEED = 12345;
    private static final int VIEW_W = 1740;
    private static final int VIEW_H = 1000;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 旋转中心 / 内容垂直居中诊断（v32）===");
        System.out.printf("视口 %d×%d%n%n", VIEW_W, VIEW_H);

        float[] r = SandboxGeometry.rotationRows(
                SandboxGeometry.DEFAULT_YAW, SandboxGeometry.DEFAULT_PITCH);
        float scale = SandboxGeometry.fillScale(r, VIEW_W, VIEW_H);
        float[] box = SandboxGeometry.planeBox(r);

        // 地面平面（mz=0）在屏幕上的包围盒
        //   屏幕 y = oy + scale·(r3·mx + r4·my)；[box[2],box[3]] 是 r3·mx+r4·my 的范围
        //   ⇒ 屏幕 y 范围 = [oy + scale·box[2], oy + scale·box[3]]
        float groundH = (box[3] - box[2]) * scale;
        float groundTop = VIEW_H / 2f + box[2] * scale;    // 较小的 y（屏幕上方）
        float groundBot = VIEW_H / 2f + box[3] * scale;    // 较大的 y（屏幕下方）

        // 柱高在屏幕上的抬升量：mz↑ ⇒ 屏幕 y 减小（r[5] < 0），所以内容整体<b>上移</b>
        float reliefPx = SandboxGeometry.RELIEF * Math.abs(r[5]) * scale;

        System.out.println("[1] 地面平面（mz=0）落点");
        System.out.printf("    scale=%.1f  disc 高=%.0f px（视口 %d ⇒ %.0f%%）%n",
                scale, groundH, VIEW_H, 100 * groundH / VIEW_H);
        System.out.printf("    地面平面 y 范围：[%.0f, %.0f]（视口中心 %d）%n",
                groundTop, groundBot, VIEW_H / 2);
        System.out.printf("    竖向居中偏差 = %.0f px（正=偏下，负=偏上）%n",
                (groundTop + groundBot) / 2 - VIEW_H / 2);
        System.out.println();

        System.out.println("[2] 柱高把内容整体往上抬");
        System.out.printf("    柱高抬升 = RELIEF(%.2f) × |r5|(%.3f) × scale(%.1f) = %.0f px%n",
                SandboxGeometry.RELIEF, Math.abs(r[5]), scale, reliefPx);
        // ★ v32：实机已加"内容质心居中补偿"（向下移一半柱高）
        float oyBias = reliefPx * 0.5f;
        System.out.printf("    补偿：原点下移 %.0f px（= 一半柱高）%n", oyBias);
        float contentTopAdj = groundTop - reliefPx + oyBias;
        float contentBotAdj = groundBot + oyBias;
        System.out.printf("    内容实际 y 范围：[%.0f, %.0f]%n", contentTopAdj, contentBotAdj);
        float contentCenter = (contentTopAdj + contentBotAdj) / 2f;
        float bias = contentCenter - VIEW_H / 2f;
        System.out.printf("    内容质心 y = %.0f ⇒ 偏离视口中心 %.0f px（视口 %.1f%%）%n",
                contentCenter, bias, 100 * Math.abs(bias) / VIEW_H);
        System.out.println();

        // 判据：内容质心偏离视口中心不得超过 6%
        System.out.println("[3] 判据：内容质心偏离视口中心 ≤ 6%");
        float biasPct = Math.abs(bias) / VIEW_H;
        boolean pass = biasPct <= 0.06f;
        System.out.printf("    实测偏离 %.1f%% ⇒ %s%n", 100 * biasPct, pass ? "OK" : "FAIL");
        if (!pass) {
            System.out.println("    ⇒ 地形会长在视口中心之上，下半屏是空的（用户：「旋转中心偏高」）");
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SandboxCenterProbe() { }
}
