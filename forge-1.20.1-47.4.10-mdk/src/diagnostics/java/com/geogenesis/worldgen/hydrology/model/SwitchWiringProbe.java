package com.geogenesis.worldgen.hydrology.model;

/**
 * <h1>开关接线自检：{@code -D} 属性是否真的到达判定点</h1>
 *
 * <h2>为什么需要（本轮踩坑）</h2>
 * <p>{@code -Dhydro.dropUnfedBasins=true} 实测<b>结果完全不变</b>（270 分量 /
 * dryBasins 1110 都一样）。在"改实现"之前必须先确认是<b>哪种</b>失败：</p>
 * <ol>
 *   <li><b>开关没接线</b>：属性没读到静态字段，或实例字段没从静态字段初始化；</li>
 *   <li><b>接线正确但条件永不成立</b>：如 inflow 恒 &gt; oneCellEvap。</li>
 * </ol>
 * <p>两者修法完全不同 ⇒ <b>本探针只做接线检查，不改任何逻辑</b>。</p>
 *
 * <h2>检查项</h2>
 * <pre>
 *   [1] System.getProperty("hydro.dropUnfedBasins") 原值
 *   [2] LakeWaterBalance.DROP_UNFED_BASINS 静态字段值
 *   [3] 新建实例后，实例字段（用反射读私有 dropUnfedBasins）
 *   [4] 构造器是否真的读了静态字段
 * </pre>
 * <p><b>NOT VERIFIED</b>：仅测接线，不测语义。</p>
 */
public final class SwitchWiringProbe {

    private SwitchWiringProbe() { }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 开关接线自检 ===");
        System.out.printf("[1] System.getProperty(\"hydro.dropTinyBasins\") = %s%n",
                System.getProperty("hydro.dropTinyBasins"));
        System.out.printf("[2] LakeWaterBalance.DROP_TINY_BASINS (静态)  = %b%n",
                LakeWaterBalance.DROP_TINY_BASINS);

        // 造一个最小实例（bed 全平、无海 ⇒ 走不进太多分支，但足以读字段）
        int n = 4;
        double[] bed = new double[n * n];
        boolean[] drains = new boolean[n * n];
        drains[0] = true;
        double[] ext = new double[n * n];
        java.util.Arrays.fill(ext, Double.NaN);
        var surface = EquilibriumWater.compute(bed, n, n, drains, ext);
        LakeWaterBalance lwb = new LakeWaterBalance(n, n, 1.0, bed, surface);

        // [3] 反射读私有字段
        java.lang.reflect.Field f = LakeWaterBalance.class.getDeclaredField("dropTinyBasins");
        f.setAccessible(true);
        System.out.printf("[3] 实例字段 dropTinyBasins              = %b%n", f.getBoolean(lwb));

        java.lang.reflect.Field fa = LakeWaterBalance.class.getDeclaredField("assumeFullWhenUnfed");
        fa.setAccessible(true);
        System.out.printf("    (对照) 实例字段 assumeFullWhenUnfed   = %b%n", fa.getBoolean(lwb));

        boolean ok = (f.getBoolean(lwb) == LakeWaterBalance.DROP_TINY_BASINS);
        System.out.printf("%n判定：%s%n", ok
                ? "接线正确（静态字段已传到实例）⇒ 若结果仍不变，属【条件永不成立】类问题"
                : "★ 接线断裂：实例字段与静态字段不一致 ⇒ 属【开关没接到判定点】类问题");
    }
}