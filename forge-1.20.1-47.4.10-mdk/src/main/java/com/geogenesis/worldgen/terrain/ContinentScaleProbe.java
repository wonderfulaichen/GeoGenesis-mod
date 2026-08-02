package com.geogenesis.worldgen.terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * 大陆性场空间尺度探针（2026-08-02，用户反馈"HS=2 后大陆性感觉没放宽"）。
 *
 * <p>直接测 {@link ContinentField}（特征坐标函数 F）：HS=h 的世界 c(物理 x) = F(x/h)。
 * 沿特征线统计 F 的陆地段长（特征单位），物理段长 = 特征段长 × h——
 * 若 HS 生效，HS=2 的物理段长应为 HS=1 的 2 倍（等比拉伸，海陆比不变）。</p>
 */
public final class ContinentScaleProbe {

    private ContinentScaleProbe() {}

    public static void main(String[] args) {
        long seed = (args.length > 0) ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        ContinentField cf = new ContinentField(p);
        cf.seed(seed);

        System.out.println("=== ContinentScaleProbe ===");
        System.out.println("Seed=" + seed + "  直接测 ContinentField 特征场 F");
        double zFeature = 1500.0;
        int L = 16000;                    // 特征长度（16000 特征 = HS=2 世界 32000 物理块）
        int STEP = 8;                     // 采样步长（特征）
        List<Integer> runs = new ArrayList<>();
        int cur = 0, maxRun = 0, landN = 0;
        for (int x = 0; x < L; x += STEP) {
            double c = cf.sample(x, zFeature);
            if (c > 0.0) {
                cur++;
                landN++;
                maxRun = Math.max(maxRun, cur);
            } else if (cur > 0) {
                runs.add(cur * STEP);
                cur = 0;
            }
        }
        if (cur > 0) runs.add(cur * STEP);
        double avg = runs.isEmpty() ? 0 : runs.stream().mapToInt(i -> i).average().orElse(0);
        System.out.println("特征场 F（z=1500）: 陆地段=" + runs.size()
                + " 平均段长=" + String.format("%.0f", avg) + "特征块"
                + " 最长=" + maxRun * STEP + "特征块  陆地占比=" + String.format("%.1f%%", 100.0 * landN / (L / STEP)));
        System.out.println("物理段长换算: HS=1 → 平均 " + String.format("%.0f", avg) + " 块 | HS=2 → 平均 "
                + String.format("%.0f", avg * 2) + " 块 | HS=4 → 平均 " + String.format("%.0f", avg * 4) + " 块");
        System.out.println("判读: HS 生效 → 物理段长 ×HS、段数 ÷HS、海陆比不变（等比拉伸）。");
    }
}
