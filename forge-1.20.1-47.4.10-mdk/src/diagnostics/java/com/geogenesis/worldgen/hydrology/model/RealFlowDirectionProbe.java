package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 真实地形上：流向是被【平局规则】锁定为轴向，还是由真实梯度给出斜向？
 *
 * <p><b>为什么要做这一条</b>：此前在<b>合成平地</b>上证明四邻域会把流向锁到轴向
 * （{@link IsotropyProbe}：对角方位计数为 0）。但真实地形几乎处处有起伏，
 * 关键未知量是：<b>真实地形上，被"水位相同"决定的流向占多少、且是否轴向？</b></p>
 *
 * <p>输出着色：</p>
 * <ul>
 *   <li>橙/亮红 = 平局驱动 + 轴向（最可疑：人工直纹的来源）</li>
 *   <li>暗红 = 平局驱动 + 斜向</li>
 *   <li>绿 = 梯度驱动 + 轴向（正常：真实坡向恰好朝轴向）</li>
 *   <li>暗绿 = 梯度驱动 + 斜向（最自然）</li>
 *   <li>蓝 = 海；灰 = 未决或终端</li>
 * </ul>
 *
 * <p>判读：若"橙"在图上连成线，则直纹确由平局规则产生；若"橙"零散而直纹仍在，
 * 则问题来自地形本身缺少排水结构（需另找方案，如几何河线）。</p>
 */
public final class RealFlowDirectionProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 400;
        int step = 2, cx = -140, cz = 137;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel(), hs = tp.horizontalScale();
        int n = 2 * radius / step + 1;

        double[] bed = new double[n * n];
        boolean[] seaMask = new boolean[n * n], border = new boolean[n * n];
        double[] exterior = new double[n * n];
        java.util.Arrays.fill(exterior, Double.NaN);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                bed[k] = gen.sampleWu((cx - radius + i * step) / hs,
                        (cz - radius + j * step) / hs).height;
                seaMask[k] = bed[k] < sea;
                if (i == 0 || j == 0 || i == n - 1 || j == n - 1) {
                    border[k] = true;
                    int oi = i == 0 ? -1 : i == n - 1 ? n : i;
                    int oj = j == 0 ? -1 : j == n - 1 ? n : j;
                    exterior[k] = Math.max(gen.sampleWu(
                            (cx - radius + oi * step) / hs, (cz - radius + oj * step) / hs).height, sea);
                }
            }
        }
        EquilibriumWater.Surface s = EquilibriumWater.compute(bed, n, n, seaMask, exterior);
        int[] parent = s.parent();

        int tieAxis = 0, tieDiag = 0, gradAxis = 0, gradDiag = 0;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j, p = parent[k];
                if (p < 0 || seaMask[k] || !s.resolved()[k]) {
                    img.setRGB(i, j, seaMask[k] ? 0x1F4E79 : 0x505050);
                    continue;
                }
                int di = p / n - i, dj = p % n - j;
                boolean axis = (di == 0) || (dj == 0);
                boolean tie = Math.abs(s.level()[p] - s.level()[k]) < 1e-9;
                if (tie) { if (axis) tieAxis++; else tieDiag++; }
                else { if (axis) gradAxis++; else gradDiag++; }
                int color;
                if (tie) color = axis ? 0xFF4828 : 0x7A2010;
                else color = axis ? 0x38F038 : 0x0E7A0E;
                img.setRGB(i, j, color);
            }
        }
        Path dir = Path.of("build/hydro-reassessment/flowdir-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        ImageIO.write(img, "png", dir.resolve("flowdir.png").toFile());
        System.out.printf("TIE-driven : axis=%d  diag=%d   -> axis fraction %.1f%%%n",
                tieAxis, tieDiag, 100.0 * tieAxis / Math.max(1, tieAxis + tieDiag));
        System.out.printf("GRADIENT   : axis=%d  diag=%d   -> axis fraction %.1f%%%n",
                gradAxis, gradDiag, 100.0 * gradAxis / Math.max(1, gradAxis + gradDiag));
        System.out.println("READ: 橙(平局+轴向)连成线 ⇒ 直纹来自平局规则；橙零散而直纹仍在 ⇒ 地形本身缺排水结构。");
        System.out.println("IMAGE=" + dir.resolve("flowdir.png").toAbsolutePath());
    }

    private RealFlowDirectionProbe() { }
}
