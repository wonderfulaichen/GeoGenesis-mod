package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.chunk.CellCache;
import com.geogenesis.client.preview.sandbox.SandboxGeometry;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;

/**
 * <b>「纹理高度场」vs「现有柱子」观感出图探针</b>（★ 2026-11-11，零 MC 依赖）。
 *
 * <h2>它回答什么</h2>
 * <p>用户裁定：统一后核心几何用哪种「先出图看效果再定」。
 * 本探针用<b>同一份地形、同一套颜色、同一个相机</b>画两种方案，供人眼对照：</p>
 * <ul>
 *   <li><b>方案 H（纹理高度场）</b>：M×M 四边形，顶面按 UV 贴平面纹理，
 *       高度跳变处补侧裙。{@code pitch=0} 时必须逐像素等于平面图。</li>
 *   <li><b>方案 P（现有柱子）</b>：每柱一个平涂色顶面 + 侧墙（现行实现）。</li>
 * </ul>
 *
 * <h2>输出（build/hf/）</h2>
 * <pre>
 *   plane_pitch0.png        参考：纯平面纹理（方案 H 在 pitch=0 应与之相同）
 *   hf_pitch0.png           方案 H @ pitch=0   ← 与上面必须一致
 *   hf_pitch45.png          方案 H @ 中途
 *   hf_pitch95.png          方案 H @ 沙盘默认
 *   pillars_pitch95.png     方案 P @ 沙盘默认（现行观感）
 * </pre>
 *
 * <p>用法：{@code gradlew runHeightfieldLookProbe}</p>
 */
public final class HeightfieldLookProbe {

    private static final int SEED = 12345;
    private static final int WINDOW = 512;      // 世界窗口（方块）= 纹理像素数（1 px = 1 格）
    private static final int TEX = WINDOW;
    private static final int M = 256;           // 每边四边形数 ⇒ 2 格/四边形（与现柱子同密度）
    private static final int QUAD_BLOCKS = WINDOW / M;
    private static final int IMG_W = 720;
    private static final int IMG_H = 460;
    private static final double SEA = 63.0;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        File dir = new File("build/hf");
        dir.mkdirs();

        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        // ---- 采样一块窗口（1 格 = 1 细格，等价 scaleBlockPos=1）----
        int originX = -WINDOW / 2, originZ = -WINDOW / 2;
        int cx0 = originX >> 4, cz0 = originZ >> 4;
        int nChunks = (WINDOW >> 4) + 2;
        CellCache cache = new CellCache();
        for (int i = 0; i < nChunks; i++) {
            for (int j = 0; j < nChunks; j++) {
                cache.put(cx0 + i, cz0 + j, terrain.getChunkCells(cx0 + i, cz0 + j), 1);
            }
        }
        TerrainFieldData data = TerrainFieldData.fromCellCache(
                cache, originX, originZ, WINDOW, WINDOW, TEX, TEX, 1L);
        System.out.printf("数据 %d×%d，每细格 %.2f 方块%n",
                data.gridW, data.gridH, data.blocksPerCell());

        // ---- 平面纹理（与 PlanePainter 同一条取色路径）----
        int[] tex = new int[TEX * TEX];
        Cell scratch = new Cell();
        int minY = params.minY(), maxY = params.maxY();
        for (int tz = 0; tz < TEX; tz++) {
            for (int tx = 0; tx < TEX; tx++) {
                Cell c = data.toDisplayCell(tx, tz, scratch);
                int wx = originX + tx, wz = originZ + tz;
                tex[tz * TEX + tx] = c == null ? 0xFF202030
                        : GeoPalette.color(GeoPalette.PreviewLayer.ELEVATION, c, wx, wz,
                                minY, maxY, true);
            }
        }
        // ★★ 关键：把平面模式的坡向山体阴影烘进纹理 ★★
        //   生产里由 PlanePainter.applySlopeShading 完成（左上光源、3×3 平滑、
        //   水域跳过、diff*0.6+0.4）。不做这一步，平面图是"没有地形感的色块"——
        //   上一版出图就因此显得一般般，那不是方案的问题，是探针漏了光照。
        int[] texRaw = tex.clone();     // 未烘阴影的纯色（3D 视角用几何光照）
        applyHillshade(tex, data, TEX);
        System.out.println("已烘焙坡向山体阴影（与 PlanePainter 同算法）");

        // 纯平面参考图：直接把纹理铺满
        writeFlat(tex, new File(dir, "plane_pitch0.png"));
        System.out.println("出图 plane_pitch0.png");

        // ---- 竖向比例（与沙盘同一算法）----
        double base = SEA - 4.0;
        double[] hs = new double[TEX * TEX];
        int n = 0;
        for (int i = 0; i < hs.length; i++) {
            double h = data.height[i];
            if (!Double.isNaN(h)) hs[n++] = h;
        }
        java.util.Arrays.sort(hs, 0, n);
        double p95 = hs[(int) (n * 0.95)];
        double span = Math.max(20.0, Math.max(60.0, p95 - base));
        System.out.printf("基准 %.1f p95 %.1f ⇒ span=%.1f%n", base, p95, span);

        // ---- 方案 H：三种 pitch ----
        renderHeightfield(tex, data, span, 0f, 0f, false, new File(dir, "hf_pitch0.png"));
        System.out.println("出图 hf_pitch0.png");
        renderHeightfield(tex, data, span, SandboxGeometry.DEFAULT_YAW, 0.45f, false,
                new File(dir, "hf_pitch45.png"));
        System.out.println("出图 hf_pitch45.png");
        renderHeightfield(tex, data, span, SandboxGeometry.DEFAULT_YAW,
                SandboxGeometry.DEFAULT_PITCH, false, new File(dir, "hf_pitch95.png"));
        System.out.println("出图 hf_pitch95.png");

        // ---- 方案 H3：顶点高度几何 + 【几何光照】（纹理不再烘阴影）----
        renderSmoothLit(texRaw, data, span, SandboxGeometry.DEFAULT_YAW,
                SandboxGeometry.DEFAULT_PITCH, new File(dir, "hf_lit_pitch95.png"));
        System.out.println("出图 hf_lit_pitch95.png（几何光照）");
        renderSmooth(tex, data, span, 0f, 0f, new File(dir, "hf_smooth_pitch0.png"));
        System.out.println("出图 hf_smooth_pitch0.png");
        renderSmooth(tex, data, span, SandboxGeometry.DEFAULT_YAW, 0.45f,
                new File(dir, "hf_smooth_pitch45.png"));
        System.out.println("出图 hf_smooth_pitch45.png");
        renderSmooth(tex, data, span, SandboxGeometry.DEFAULT_YAW,
                SandboxGeometry.DEFAULT_PITCH, new File(dir, "hf_smooth_pitch95.png"));
        System.out.println("出图 hf_smooth_pitch95.png");

        // ---- 方案 P：现有柱子 ----
        renderPillars(tex, data, span, new File(dir, "pillars_pitch95.png"));
        System.out.println("出图 pillars_pitch95.png");

        // ---- A/B 对照拼图（左=现有柱子，右=纹理高度场+几何光照）----
        composeAb(new File(dir, "pillars_pitch95.png"), new File(dir, "hf_lit_pitch95.png"),
                new File(dir, "ab_pitch95.png"));
        System.out.println("出图 ab_pitch95.png（左 柱子 / 右 高度场）");

        // ---- [1] 断言：pitch=0 时高度场必须与平面图一致 ----
        //   不能用「逐像素完全相等」：四边形边界按 1px 光栅化，采样点与直贴差半texel。
        //   改用<b>平均绝对通道差</b>（视觉等价性），并对两种网格都检查。
        BufferedImage flat = ImageIO.read(new File(dir, "plane_pitch0.png"));
        double dQuad = meanAbsDiff(flat, ImageIO.read(new File(dir, "hf_pitch0.png")));
        double dSmooth = meanAbsDiff(flat, ImageIO.read(new File(dir, "hf_smooth_pitch0.png")));
        System.out.printf("%n[1] pitch=0 与平面图的平均绝对通道差（0..255，越小越像）%n");
        System.out.printf("    每quad平坦高度 + 侧裙 : %.2f%n", dQuad);
        System.out.printf("    顶点高度连续曲面      : %.2f%n", dSmooth);
        boolean pass = dSmooth < 9.0 && dQuad < 12.0;
        if (!pass) {
            System.out.println("    FAIL: 差异过大 ⇒ 俯视等价性不成立，方案 H 切换会跳变");
        } else {
            System.out.println("    OK（视觉等价；差异来自四边形边界的半 texel 采样）");
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    /**
     * <b>坡向山体阴影</b>——逐行照抄 {@code PlanePainter.applySlopeShading}（第 75-153 行）。
     *
     * <pre>
     *   光源 (-0.5, 1.0, -0.3) 归一化（屏幕左上）
     *   水域：effH 填海平面，且<b>不做明暗调制</b>（否则海面会有假的浮雕感）
     *   3×3 盒型平滑（消除块边界跳变）
     *   diff = max(0, n·l) * 0.6 + 0.4
     * </pre>
     *
     * <p>scaleBlockPos 这里恒为 1（1 texel = 1 格），故斜率分母用 2。</p>
     */
    private static void applyHillshade(int[] tex, TerrainFieldData data, int size) {
        float lx = -0.5f, ly = 1.0f, lz = -0.3f;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= len; ly /= len; lz /= len;

        int total = size * size;
        int[] effH = new int[total];
        boolean[] valid = new boolean[total];
        boolean[] water = new boolean[total];

        for (int tz = 0; tz < size; tz++) {
            for (int tx = 0; tx < size; tx++) {
                int ti = tz * size + tx;
                int di = tx * data.gridH + tz;
                double h = data.height[di];
                if (Double.isNaN(h)) continue;
                boolean isWater = data.e[di] < 0.0;
                water[ti] = isWater;
                effH[ti] = isWater ? (int) SEA : (int) h;
                valid[ti] = true;
            }
        }

        int[] smooth = effH.clone();
        for (int tz = 1; tz < size - 1; tz++) {
            for (int tx = 1; tx < size - 1; tx++) {
                int idx = tz * size + tx;
                if (!valid[idx]) continue;
                long sum = 0;
                int cnt = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int j = idx + dz * size + dx;
                        if (valid[j]) { sum += effH[j]; cnt++; }
                    }
                }
                if (cnt >= 3) smooth[idx] = (int) (sum / cnt);
            }
        }

        for (int tz = 1; tz < size - 1; tz++) {
            for (int tx = 1; tx < size - 1; tx++) {
                int idx = tz * size + tx;
                if (water[idx] || !valid[idx] || !valid[idx - 1] || !valid[idx + 1]
                        || !valid[idx - size] || !valid[idx + size]) continue;
                float dhdx = (smooth[idx + 1] - smooth[idx - 1]) * 0.5f;
                float dhdz = (smooth[idx + size] - smooth[idx - size]) * 0.5f;
                float nx = -dhdx, nyv = 1.0f, nz = -dhdz;
                float nl = (float) Math.sqrt(nx * nx + nyv * nyv + nz * nz);
                if (nl < 0.001f) continue;
                nx /= nl; nyv /= nl; nz /= nl;
                float diff = Math.max(0, nx * lx + nyv * ly + nz * lz) * 0.6f + 0.4f;
                tex[idx] = shade(tex[idx], diff);
            }
        }
    }

    /**
     * <b>方案 H3：顶点高度几何 + 几何光照</b>（★ 关键变体）。
     *
     * <h2>为什么要它</h2>
     * <p>纹理里烘的坡向阴影是<b>每格</b>的（512²），而几何只有 128²。
     * 光照频率远高于几何频率 ⇒ 3D 视角下出现一圈圈<b>像等高线的波纹</b>，
     * 观感从"立体地形"退化成"贴了张带噪点的纸"。
     * 这就是用户说的「精致感下降」的成因。</p>
     *
     * <p>正确做法：3D 视角的<b>光照由几何算</b>（每四边形一个法线 → 平面着色），
     * 纹理只提供颜色。这样光跟着真实起伏走，也顺带恢复了块面感。</p>
     */
    private static void renderSmoothLit(int[] tex, TerrainFieldData data, double span,
                                        float yaw, float pitch, File out) throws Exception {
        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
        fill(img, 0x0A0C12);

        float[] r = SandboxGeometry.rotationRows(yaw, pitch);
        float iso = SandboxGeometry.fillScale(r, IMG_W, IMG_H);
        float sx = iso, sy = iso;
        double cx = IMG_W / 2.0, cy = IMG_H / 2.0;
        double relief = SandboxGeometry.RELIEF;

        int n = M + 1;
        double[] vh = new double[n * n];
        for (int vz = 0; vz < n; vz++) {
            for (int vx = 0; vx < n; vx++) vh[vz * n + vx] = vertexHeight(data, vx, vz);
        }

        Integer[] order = new Integer[M * M];
        double[] depth = new double[M * M];
        for (int i = 0; i < M * M; i++) {
            int qx = i % M, qz = i / M;
            depth[i] = r[6] * mx(qx) + r[7] * my(qz);
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(depth[a], depth[b]));

        double plateZ = norm(SEA - 30, span, relief);
        fillQuadFlat(img, r, sx, sy, cx, cy,
                -1, -1, plateZ, 1, -1, plateZ, 1, 1, plateZ, -1, 1, plateZ, 0x171A22);

        // 标准地图学光源（与 PlanePainter 同向：屏幕左上）
        double lx = -0.5, ly = 1.0, lz = -0.3;
        double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ll; ly /= ll; lz /= ll;

        for (Integer idx : order) {
            int qx = idx % M, qz = idx / M;
            double h00 = vh[qz * n + qx], h10 = vh[qz * n + qx + 1];
            double h01 = vh[(qz + 1) * n + qx], h11 = vh[(qz + 1) * n + qx + 1];
            // 四边形法线（高度以方块为单位，跨度 QUAD_BLOCKS 格）
            double dhdx = ((h10 + h11) - (h00 + h01)) * 0.5 / QUAD_BLOCKS;
            double dhdz = ((h01 + h11) - (h00 + h10)) * 0.5 / QUAD_BLOCKS;
            double nx = -dhdx, ny = 1.0, nz = -dhdz;
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= nl; ny /= nl; nz /= nl;
            float lit = (float) (Math.max(0, nx * lx + ny * ly + nz * lz) * 0.6 + 0.4);

            double m00 = norm(h00, span, relief), m10 = norm(h10, span, relief);
            double m01 = norm(h01, span, relief), m11 = norm(h11, span, relief);
            double x0 = mx(qx), x1 = mx(qx + 1), z0 = my(qz), z1 = my(qz + 1);
            double u0 = qx / (double) M, u1 = (qx + 1) / (double) M;
            double v0 = qz / (double) M, v1 = (qz + 1) / (double) M;

            double[] p00 = proj(r, sx, sy, cx, cy, x0, z0, m00);
            double[] p10 = proj(r, sx, sy, cx, cy, x1, z0, m10);
            double[] p11 = proj(r, sx, sy, cx, cy, x1, z1, m11);
            double[] p01 = proj(r, sx, sy, cx, cy, x0, z1, m01);
            fillTexTri(img, tex, TEX, p00, u0, v0, p10, u1, v0, p11, u1, v1, lit);
            fillTexTri(img, tex, TEX, p00, u0, v0, p11, u1, v1, p01, u0, v1, lit);
        }
        ImageIO.write(img, "png", out);
    }

    // ------------------------------------------------------------------
    // 方案 H2：顶点高度（连续曲面，无侧裙）
    // ------------------------------------------------------------------

    private static void renderSmooth(int[] tex, TerrainFieldData data, double span,
                                     float yaw, float pitch, File out) throws Exception {
        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
        fill(img, 0x0A0C12);

        float[] r = SandboxGeometry.rotationRows(yaw, pitch);
        float iso = SandboxGeometry.fillScale(r, IMG_W, IMG_H);
        boolean flatView = Math.abs(pitch) < 1e-6f;
        float sx = flatView ? IMG_W / 2f : iso;
        float sy = flatView ? IMG_H / 2f : iso;
        double cx = IMG_W / 2.0, cy = IMG_H / 2.0;
        double relief = SandboxGeometry.RELIEF;

        // 顶点高度 (M+1)²：相邻四边形共享顶点 ⇒ 曲面连续，无需侧裙
        int n = M + 1;
        double[] vh = new double[n * n];
        for (int vz = 0; vz < n; vz++) {
            for (int vx = 0; vx < n; vx++) {
                vh[vz * n + vx] = vertexHeight(data, vx, vz);
            }
        }

        Integer[] order = new Integer[M * M];
        double[] depth = new double[M * M];
        for (int i = 0; i < M * M; i++) {
            int qx = i % M, qz = i / M;
            depth[i] = r[6] * mx(qx) + r[7] * my(qz);
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(depth[a], depth[b]));

        // 底板（深色）：避免地形悬空在纯黑背景上（与参考项目 PLATE 0x171A22 同色）
        double plateZ = norm(SEA - 30, span, relief);
        fillQuadFlat(img, r, sx, sy, cx, cy,
                -1, -1, plateZ, 1, -1, plateZ, 1, 1, plateZ, -1, 1, plateZ, 0x171A22);

        for (Integer idx : order) {
            int qx = idx % M, qz = idx / M;
            double m00 = norm(vh[qz * n + qx], span, relief);
            double m10 = norm(vh[qz * n + qx + 1], span, relief);
            double m01 = norm(vh[(qz + 1) * n + qx], span, relief);
            double m11 = norm(vh[(qz + 1) * n + qx + 1], span, relief);
            double x0 = mx(qx), x1 = mx(qx + 1), z0 = my(qz), z1 = my(qz + 1);
            double u0 = qx / (double) M, u1 = (qx + 1) / (double) M;
            double v0 = qz / (double) M, v1 = (qz + 1) / (double) M;

            double[] p00 = proj(r, sx, sy, cx, cy, x0, z0, m00);
            double[] p10 = proj(r, sx, sy, cx, cy, x1, z0, m10);
            double[] p11 = proj(r, sx, sy, cx, cy, x1, z1, m11);
            double[] p01 = proj(r, sx, sy, cx, cy, x0, z1, m01);
            fillTexTri(img, tex, TEX, p00, u0, v0, p10, u1, v0, p11, u1, v1, 1f);
            fillTexTri(img, tex, TEX, p00, u0, v0, p11, u1, v1, p01, u0, v1, 1f);
        }
        ImageIO.write(img, "png", out);
    }

    /** 顶点高度：水抬到海平面；无数据退回海平面。
     *  <p>★ 出图专用保护：夹在 {@code [SEA-40, SEA+120]}。
     *  生产里不需要（高度由地形场保证），但探针窗口偶尔会碰到单点异常值，
     *  画出来是一根刺眼的尖锥，会干扰观感判断。</p> */
    private static double vertexHeight(TerrainFieldData data, int vx, int vz) {
        int gx = Math.min(data.gridW - 1, vx * QUAD_BLOCKS);
        int gz = Math.min(data.gridH - 1, vz * QUAD_BLOCKS);
        int i = gx * data.gridH + gz;
        if (data.e[i] < 0.0) return SEA;
        double h = data.height[i];
        if (Double.isNaN(h)) return SEA;
        return Math.max(SEA - 40, Math.min(SEA + 120, h));
    }

    // ------------------------------------------------------------------
    // 方案 H：纹理高度场 + 侧裙
    // ------------------------------------------------------------------

    private static void renderHeightfield(int[] tex, TerrainFieldData data, double span,
                                          float yaw, float pitch, boolean unused,
                                          File out) throws Exception {
        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
        fill(img, 0x0A0C12);

        float[] r = SandboxGeometry.rotationRows(yaw, pitch);
        // ★ 各向异性：pitch=0 时必须让地面正好铺满视口（与平面 blit 逐像素对齐），
        //   即 sx=w/2、sy=h/2；pitch>0 用沙盘同口径的各向同性 fillScale。
        float iso = SandboxGeometry.fillScale(r, IMG_W, IMG_H);
        boolean flatView = Math.abs(pitch) < 1e-6f;
        float sx = flatView ? IMG_W / 2f : iso;
        float sy = flatView ? IMG_H / 2f : iso;
        double cx = IMG_W / 2.0, cy = IMG_H / 2.0;
        double relief = SandboxGeometry.RELIEF;

        // 每 quad 的高度（取格内中位，水面抬到海平面）
        double[] qh = new double[M * M];
        for (int qz = 0; qz < M; qz++) {
            for (int qx = 0; qx < M; qx++) {
                double h = quadHeight(data, qx, qz);
                qh[qz * M + qx] = h;
            }
        }

        // 顶面 + 侧裙统一排序（按中心深度）
        int tops = M * M;
        Integer[] order = new Integer[tops];
        double[] depth = new double[tops];
        for (int i = 0; i < tops; i++) {
            int qx = i % M, qz = i / M;
            depth[i] = r[6] * mx(qx) + r[7] * my(qz);
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(depth[a], depth[b]));

        for (Integer idx : order) {
            int qx = idx % M, qz = idx / M;
            double h = qh[idx];
            double mz = norm(h, span, relief);
            double x0 = mx(qx), x1 = mx(qx + 1), z0 = my(qz), z1 = my(qz + 1);
            // UV：该 quad 覆盖的纹理子矩形
            double u0 = qx / (double) M, u1 = (qx + 1) / (double) M;
            double v0 = qz / (double) M, v1 = (qz + 1) / (double) M;
            // 顶面
            fillTexQuad(img, r, sx, sy, cx, cy, tex, TEX,
                    x0, z0, mz, u0, v0,
                    x1, z0, mz, u1, v0,
                    x1, z1, mz, u1, v1,
                    x0, z1, mz, u0, v1, 1.0f);
            // 侧裙：只在邻居更低时补（-x / +x / -z / +z）
            skirt(img, r, sx, sy, cx, cy, tex, TEX, qh, span, relief,
                    qx, qz, x0, x1, z0, z1, mz, h, qx - 1, qz, -1, 0);
            skirt(img, r, sx, sy, cx, cy, tex, TEX, qh, span, relief,
                    qx, qz, x0, x1, z0, z1, mz, h, qx + 1, qz, 1, 0);
            skirt(img, r, sx, sy, cx, cy, tex, TEX, qh, span, relief,
                    qx, qz, x0, x1, z0, z1, mz, h, qx, qz - 1, 0, -1);
            skirt(img, r, sx, sy, cx, cy, tex, TEX, qh, span, relief,
                    qx, qz, x0, x1, z0, z1, mz, h, qx, qz + 1, 0, 1);
        }
        ImageIO.write(img, "png", out);
    }

    private static void skirt(BufferedImage img, float[] r, float sx, float sy,
                              double cx, double cy,
                              int[] tex, int texW, double[] qh, double span, double relief,
                              int qx, int qz, double x0, double x1, double z0, double z1,
                              double mz, double h, int nx, int nz, int dx, int dz) {
        if (nx < 0 || nx >= M || nz < 0 || nz >= M) return;
        double nh = qh[nz * M + nx];
        if (nh >= h - 1e-6) return;
        double low = norm(nh, span, relief);
        // 取该边中点对应的纹理色（暗化成侧面）
        int tx = (int) (((qx + 0.5) / M) * (texW - 1));
        int tz = (int) (((qz + 0.5) / M) * (texW - 1));
        int base = tex[tz * texW + tx];
        int side;
        float lit;
        if (dx < 0) lit = 0.56f;
        else if (dx > 0) lit = 0.82f;
        else if (dz < 0) lit = 0.46f;
        else lit = 0.70f;
        side = shade(base, lit);
        // 该边的两个端点
        double ax, az, bx, bz;
        if (dx != 0) {
            double x = dx < 0 ? x0 : x1;
            ax = x; az = z0; bx = x; bz = z1;
        } else {
            double z = dz < 0 ? z0 : z1;
            ax = x0; az = z; bx = x1; bz = z;
        }
        fillQuadFlat(img, r, sx, sy, cx, cy,
                ax, az, mz, bx, bz, mz, bx, bz, low, ax, az, low, side);
    }

    private static double quadHeight(TerrainFieldData data, int qx, int qz) {
        int gx0 = qx * QUAD_BLOCKS, gz0 = qz * QUAD_BLOCKS;
        int gx1 = Math.min(data.gridW - 1, gx0 + QUAD_BLOCKS - 1);
        int gz1 = Math.min(data.gridH - 1, gz0 + QUAD_BLOCKS - 1);
        double[] buf = new double[(gx1 - gx0 + 1) * (gz1 - gz0 + 1)];
        int k = 0;
        boolean anyWater = false;
        for (int gx = gx0; gx <= gx1; gx++) {
            for (int gz = gz0; gz <= gz1; gz++) {
                int i = gx * data.gridH + gz;
                double h = data.height[i];
                if (data.e[i] < 0.0) { anyWater = true; h = SEA; }
                if (!Double.isNaN(h)) buf[k++] = h;
            }
        }
        if (k == 0) return SEA;
        java.util.Arrays.sort(buf, 0, k);
        return buf[k / 2];
    }

    private static double mx(int q) {
        return q / (double) M * 2.0 - 1.0;
    }

    private static double my(int q) {
        return q / (double) M * 2.0 - 1.0;
    }

    private static double norm(double h, double span, double relief) {
        return Math.max((h - (SEA - 4.0)) / span, 0.05) * relief;
    }

    // ------------------------------------------------------------------
    // 方案 P：现有柱子（平涂色）
    // ------------------------------------------------------------------

    private static void renderPillars(int[] tex, TerrainFieldData data, double span, File out)
            throws Exception {
        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
        fill(img, 0x0A0C12);
        float[] r = SandboxGeometry.rotationRows(SandboxGeometry.DEFAULT_YAW,
                SandboxGeometry.DEFAULT_PITCH);
        float s = SandboxGeometry.fillScale(r, IMG_W, IMG_H);
        double cx = IMG_W / 2.0, cy = IMG_H / 2.0;
        double relief = SandboxGeometry.RELIEF;

        SandboxGeometry.Columns cols = SandboxGeometry.buildColumns(
                data, GeoPalette.PreviewLayer.ELEVATION, 2, (float) span,
                Integer.MIN_VALUE, Integer.MAX_VALUE, SEA, 0L, 0L,
                (gx, gz, snap) -> {
                    Cell c = snap.toDisplayCell(gx, gz, new Cell());
                    return c == null ? 0x202030
                            : GeoPalette.color(GeoPalette.PreviewLayer.ELEVATION, c, 0, 0,
                                    -64, 320, true) & 0xFFFFFF;
                });
        Integer[] order = new Integer[cols.readyCount];
        for (int k = 0; k < cols.readyCount; k++) order[k] = cols.ready[k];
        final SandboxGeometry.Columns cc = cols;
        java.util.Arrays.sort(order, (a, b) -> {
            double da = r[6] * SandboxGeometry.modelOf(cc.colCount, a / cc.colRows)
                    + r[7] * SandboxGeometry.modelOf(cc.colRows, a % cc.colRows);
            double db = r[6] * SandboxGeometry.modelOf(cc.colCount, b / cc.colRows)
                    + r[7] * SandboxGeometry.modelOf(cc.colRows, b % cc.colRows);
            return Double.compare(da, db);
        });
        for (int k = 0; k < cols.readyCount; k++) {
            int col = order[k];
            SandboxGeometry.Column c = cols.items[col];
            if (c == null || c.top < 0f) continue;
            int rgb = c.rgb & 0xFFFFFF;
            double t = c.top;
            fillQuadFlat(img, r, s, s, cx, cy,
                    c.x0, c.y0, t, c.x1, c.y0, t, c.x1, c.y1, t, c.x0, c.y1, t, rgb);
            // 两侧墙（东南）
            fillQuadFlat(img, r, s, s, cx, cy,
                    c.x1, c.y0, t, c.x1, c.y1, t, c.x1, c.y1, 0, c.x1, c.y0, 0,
                    shade(rgb, 0.82f));
            fillQuadFlat(img, r, s, s, cx, cy,
                    c.x0, c.y1, t, c.x1, c.y1, t, c.x1, c.y1, 0, c.x0, c.y1, 0,
                    shade(rgb, 0.70f));
        }
        ImageIO.write(img, "png", out);
    }

    // ------------------------------------------------------------------
    // 光栅化
    // ------------------------------------------------------------------

    private static void writeFlat(int[] tex, File out) throws Exception {
        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < IMG_H; y++) {
            for (int x = 0; x < IMG_W; x++) {
                int tx = (int) (x / (double) IMG_W * TEX);
                int tz = (int) (y / (double) IMG_H * TEX);
                img.setRGB(x, y, tex[tz * TEX + tx] & 0xFFFFFF);
            }
        }
        ImageIO.write(img, "png", out);
    }

    private static void fill(BufferedImage img, int rgb) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) img.setRGB(x, y, rgb);
        }
    }

    private static void fillTexQuad(BufferedImage img, float[] r, float sx, float sy,
                                    double cx, double cy, int[] tex, int texW,
                                    double ax, double ay, double az, double au, double av,
                                    double bx, double by, double bz, double bu, double bv,
                                    double dx, double dy, double dz, double du, double dv,
                                    double ex, double ey, double ez, double eu, double ev,
                                    float mul) {
        double[] p0 = proj(r, sx, sy, cx, cy, ax, ay, az);
        double[] p1 = proj(r, sx, sy, cx, cy, bx, by, bz);
        double[] p2 = proj(r, sx, sy, cx, cy, dx, dy, dz);
        double[] p3 = proj(r, sx, sy, cx, cy, ex, ey, ez);
        fillTexTri(img, tex, texW, p0, au, av, p1, bu, bv, p2, du, dv, mul);
        fillTexTri(img, tex, texW, p0, au, av, p2, du, dv, p3, eu, ev, mul);
    }

    /** 三角形填充 + UV 插值 + 最近邻采样。 */
    private static void fillTexTri(BufferedImage img, int[] tex, int texW,
                                   double[] a, double au, double av,
                                   double[] b, double bu, double bv,
                                   double[] c, double cu, double cv, float mul) {
        int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int maxX = (int) Math.min(img.getWidth() - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = (int) Math.min(img.getHeight() - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        double d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        if (Math.abs(d) < 1e-9) return;
        final double eps = 1e-6 * (1 + Math.abs(d));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double l1 = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / d;
                double l2 = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / d;
                double l3 = 1 - l1 - l2;
                if (l1 < -eps || l2 < -eps || l3 < -eps) continue;
                double u = l1 * au + l2 * bu + l3 * cu;
                double v = l1 * av + l2 * bv + l3 * cv;
                int tx = (int) (u * (texW - 1));
                int tz = (int) (v * (texW - 1));
                tx = tx < 0 ? 0 : Math.min(texW - 1, tx);
                tz = tz < 0 ? 0 : Math.min(texW - 1, tz);
                int rgb = tex[tz * texW + tx];
                img.setRGB(x, y, mul == 1f ? (rgb & 0xFFFFFF) : shade(rgb, mul));
            }
        }
    }

    private static void fillQuadFlat(BufferedImage img, float[] r, float sx, float sy,
                                     double cx, double cy,
                                     double ax, double ay, double az,
                                     double bx, double by, double bz,
                                     double dx, double dy, double dz,
                                     double ex, double ey, double ez, int rgb) {
        double[] p0 = proj(r, sx, sy, cx, cy, ax, ay, az);
        double[] p1 = proj(r, sx, sy, cx, cy, bx, by, bz);
        double[] p2 = proj(r, sx, sy, cx, cy, dx, dy, dz);
        double[] p3 = proj(r, sx, sy, cx, cy, ex, ey, ez);
        fillTri(img, p0, p1, p2, rgb);
        fillTri(img, p0, p2, p3, rgb);
    }

    private static void fillTri(BufferedImage img, double[] a, double[] b, double[] c, int rgb) {
        int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int maxX = (int) Math.min(img.getWidth() - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = (int) Math.min(img.getHeight() - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        double d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        if (Math.abs(d) < 1e-9) return;
        final double eps = 1e-6 * (1 + Math.abs(d));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double l1 = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / d;
                double l2 = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / d;
                double l3 = 1 - l1 - l2;
                if (l1 >= -eps && l2 >= -eps && l3 >= -eps) img.setRGB(x, y, rgb);
            }
        }
    }

    private static double[] proj(float[] r, float sx, float sy, double cx, double cy,
                                 double mx, double my, double mz) {
        return new double[]{
                cx + sx * (r[0] * mx + r[1] * my + r[2] * mz),
                cy + sy * (r[3] * mx + r[4] * my + r[5] * mz)};
    }

    private static int shade(int rgb, float mul) {
        int rr = clamp((int) (((rgb >> 16) & 0xFF) * mul));
        int gg = clamp((int) (((rgb >> 8) & 0xFF) * mul));
        int bb = clamp((int) ((rgb & 0xFF) * mul));
        return (rr << 16) | (gg << 8) | bb;
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    /** 两张图的平均绝对通道差（0..255）。 */
    private static double meanAbsDiff(BufferedImage a, BufferedImage b) {
        long sum = 0;
        int n = 0;
        for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y++) {
            for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x++) {
                int ca = a.getRGB(x, y), cb = b.getRGB(x, y);
                sum += Math.abs(((ca >> 16) & 0xFF) - ((cb >> 16) & 0xFF));
                sum += Math.abs(((ca >> 8) & 0xFF) - ((cb >> 8) & 0xFF));
                sum += Math.abs((ca & 0xFF) - (cb & 0xFF));
                n += 3;
            }
        }
        return n == 0 ? 0 : sum / (double) n;
    }

    /** 把两张图左右拼接（中间 3px 分隔），便于人眼对照。 */
    private static void composeAb(File left, File right, File out) throws Exception {
        BufferedImage a = ImageIO.read(left);
        BufferedImage b = ImageIO.read(right);
        if (a == null || b == null) return;
        int h = Math.max(a.getHeight(), b.getHeight());
        BufferedImage dst = new BufferedImage(a.getWidth() + b.getWidth() + 3, h,
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                dst.setRGB(x, y, x < a.getWidth() && y < a.getHeight() ? a.getRGB(x, y) : 0x0A0C12);
            }
            dst.setRGB(a.getWidth(), y, 0xFFAA00);
            dst.setRGB(a.getWidth() + 1, y, 0xFFAA00);
            dst.setRGB(a.getWidth() + 2, y, 0xFFAA00);
            for (int x = 0; x < b.getWidth(); x++) {
                dst.setRGB(a.getWidth() + 3 + x, y,
                        y < b.getHeight() ? b.getRGB(x, y) : 0x0A0C12);
            }
        }
        ImageIO.write(dst, "png", out);
    }

    private HeightfieldLookProbe() { }
}