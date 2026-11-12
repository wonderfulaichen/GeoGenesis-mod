package com.geogenesis.worldgen.terrain.tectreplica;

import java.util.ArrayList;
import java.util.List;

/**
 * 段聚类 + Voronoi 索引 —— cluster.h 的移植，GPU 深度缓冲换成 CPU 空间哈希。
 *
 * <p><b>原版 GPU 做法</b>（cluster.h:47-76 + voronoi.vs/fs）：每段渲染一个以质心为中心、
 * 半径 R（NDC）的实例化四边形，片元深度 = 到质心的欧氏距离，超出 R discard；
 * 深度测试 LESS ⇒ 每像素留下最近质心；颜色 = 实例 ID 编码；同 pass atomicAdd 统计面积；
 * 清屏白色 = 背景。CPU 等价：每像素（中心 = 像素 +0.5，与 NDC 栅格化一致）在空间哈希
 * 3×3 邻格内找最近质心，距离 &le; R&middot;128px 者拥有该像素；平局取索引小者
 * （深度 LESS，先写者胜）。K=8192 段暴力逐对是 5×10⁸ 次/步，空间哈希后为
 * O(像素 × 常数)。</p>
 *
 * <p>索引约定沿用原版 {@code indexmap[(int)p.y*SIZE+(int)p.x]}（cluster.h:126）——
 * 与热场的 x-major 约定互为转置，两套约定并存是原版现状，如实保留。</p>
 */
final class TectCluster {

    /** 段集合（段的 x/y 即原版 cluster.points，一一对应，无别名）。 */
    final List<TectSegment> segs = new ArrayList<>();

    /** 每像素最近段索引，-1 = 背景（原版白色像素 b==255）。 */
    final int[] indexmap = new int[TectWorld.SIZE * TectWorld.SIZE];

    /** 段半径的像素当量：R(NDC) × 128。 */
    static final double R_PX = TectWorld.R * (TectWorld.SIZE / 2.0);

    /** 空间哈希格边长（= R_PX，保证 3×3 邻格覆盖全部 ≤R 的候选）。 */
    private static final double CELL = R_PX;

    private final List<List<Integer>> buckets = new ArrayList<>();
    private final int gridDim = (int) Math.ceil(TectWorld.SIZE / CELL) + 2;

    void init(TectRng rng) {
        List<double[]> pts = PoissonDisk.sample(rng, TectWorld.K, 0, 0, TectWorld.SIZE, TectWorld.SIZE);
        for (double[] p : pts) {
            segs.add(new TectSegment(p[0], p[1]));
        }
        update();
    }

    /** 每步重算：Voronoi 索引 + 逐段面积 EMA（cluster.h:47-76）。 */
    void update() {
        for (List<Integer> b : buckets) {
            b.clear();
        }
        while (buckets.size() < gridDim * gridDim) {
            buckets.add(new ArrayList<>());
        }
        // 质心可在界外（最多 R 入画），格坐标整体 +1 偏移以容纳负格
        for (int i = 0; i < segs.size(); i++) {
            TectSegment s = segs.get(i);
            int gx = (int) Math.floor(s.x / CELL) + 1;
            int gy = (int) Math.floor(s.y / CELL) + 1;
            if (gx >= 0 && gx < gridDim && gy >= 0 && gy < gridDim) {
                buckets.get(gx * gridDim + gy).add(i);
            }
        }

        int[] counts = new int[segs.size()];
        java.util.Arrays.fill(indexmap, -1);
        double r2 = R_PX * R_PX;
        for (int py = 0; py < TectWorld.SIZE; py++) {
            for (int px = 0; px < TectWorld.SIZE; px++) {
                double fx = px + 0.5, fy = py + 0.5;
                int gx = (int) (fx / CELL) + 1;
                int gy = (int) (fy / CELL) + 1;
                int best = -1;
                double bestD2 = r2;
                for (int i = gx - 1; i <= gx + 1; i++) {
                    if (i < 0 || i >= gridDim) {
                        continue;
                    }
                    for (int j = gy - 1; j <= gy + 1; j++) {
                        if (j < 0 || j >= gridDim) {
                            continue;
                        }
                        for (int idx : buckets.get(i * gridDim + j)) {
                            TectSegment s = segs.get(idx);
                            double dx = s.x - fx, dy = s.y - fy;
                            double d2 = dx * dx + dy * dy;
                            if (d2 < bestD2) {   // 严格小于 ⇒ 平局取索引小者（与深度 LESS 一致）
                                bestD2 = d2;
                                best = idx;
                            }
                        }
                    }
                }
                if (best >= 0) {
                    indexmap[py * TectWorld.SIZE + px] = best;
                    counts[best]++;
                }
            }
        }
        for (int i = 0; i < segs.size(); i++) {
            TectSegment s = segs.get(i);
            // cluster.h:73 原版是 int 字段截断写回：floor(0.99·a + 0.01·count)。
            // ★ 这不是无害怪癖：计数 ~8 时 floor(1.07)=1 ⇒ area 永远停在 1 ⇒
            //   板块质量比"真面积"轻 ~7 倍 ⇒ 对流快 ~7 倍 ⇒ 大量碰撞/裂谷/边缘红壳——
            //   是参考形态的关键组成（曾误改 double area=8，形态退化为均匀厚壳，已回退）。
            // ★ 钳 ≥1 是防炸偏差：count=0 的段（圆盘被近邻全盖）会 EMA 到 0 ⇒
            //   density = mass/(0·t) = ∞（C++ int 同样会炸出 inf 段，实测 nonFinite=2）。
            s.area = Math.max(1.0, Math.floor(s.area * 0.99 + 0.01 * counts[i]));
        }
    }

    /** cluster.h:124-131 的 CPU 版；调用方须保证 (int)x/(int)y 在 [0,SIZE)。 */
    int sample(double x, double y) {
        return indexmap[(int) y * TectWorld.SIZE + (int) x];
    }

    /** 删除死段（cluster.h:94-104；Java 无指针别名，无需 reassign）。 */
    void removeDead() {
        segs.removeIf(s -> !s.alive);
    }
}
