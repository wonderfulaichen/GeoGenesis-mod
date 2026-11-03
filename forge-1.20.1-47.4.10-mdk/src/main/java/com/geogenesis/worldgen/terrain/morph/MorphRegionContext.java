package com.geogenesis.worldgen.terrain.morph;

/**
 * <b>区域上下文</b>：把一个世界种子的"分区 / 票槽 / 山形遮罩 / 台地覆盖"四件套打成一份显式数据。
 *
 * <h3>★ 一个种子，一条路</h3>
 * <p>历史实现把正典种子接到一条"用反射调用写死 SEED 的构建方法"的路上，
 * 其它种子走另一个分支。两条分支其实<b>数值等价</b>：</p>
 * <pre>
 *   partition : seed=b+23, warpX=b+21, warpZ=b+22        （两条分支同值）
 *   mask      : WorleyEdgeAdd(b+4241) @ warpPerlin(b+24, 333, 2, 250) @ curve3 @ clamp(0,0.9)
 *               （两条分支同图：一条是"游标走到 b+24"，一条直接写 b+24）
 *   slotCats  : 18 条目权重 {2,1,1.5,2,2 | 均值 | 5,3,9} → 45 票 → shuffle(Random(b+20))
 *   coverage  : 共享台地（Terrace 值域 1.05）seed=b+44
 * </pre>
 * <p>既然如此，就没有理由留两条路。本类由 {@link MorphAssembly} 一次性导出这四件套，
 * 正典种子与其它种子走<b>完全相同的代码</b>。{@link #slotCategories()} 还会断言
 * "目录按条目算出的类别"与"组装骨架按权重展开的类别"逐槽相同 —— 若哪天有人改了权重或复合阵容，
 * 这条断言会立刻炸，而不是让两套分类悄悄分叉。</p>
 *
 * <h3>★ 覆盖族为什么是"共享台地"而不是"修正台地"</h3>
 * <p>覆盖层用的是参考的 {@code Blender(unmixable[0])}，而 {@code unmixable[0]} 就是共享台地
 * —— 它的 {@code Terrace} 值域上界是 <b>1.05</b>（本项目早期遗留的本地偏差）。
 * 目录对外的一律口径是 0.9，但对覆盖层必须<b>照实际对象</b>取 1.05，否则覆盖后高度整体偏低。
 * 两个值都在 {@link MorphCatalog} 里显式命名，误用会被对照看出。</p>
 */
final class MorphRegionContext {

    /** 条目 → 类别的标签集合（只用于分类查询/类型图着色，不参与高度）。 */
    enum SlotCategory { PLAIN, HILLS, MOUNTAINS, PLATEAU, BADLANDS }

    private final MorphCatalog catalog;
    private final MorphAssembly assembly;
    private final float scale;
    private final MorphRegionPartition partition;
    private final SlotCategory[] slotCats;
    private final MorphNoise.Node mask;
    private final MorphAssembly.Family oldCoverage;
    private final float blendLow, blendHigh;

    /**
     * @param catalog  18 条目目录（族种子由它派生）
     * @param assembly 同一种子的组装骨架
     * @param scale    区域尺度（块）
     */
    MorphRegionContext(MorphCatalog catalog, MorphAssembly assembly, float scale) {
        if (!(scale > 0) || !Float.isFinite(scale)) throw new IllegalArgumentException("region scale");
        this.scale = scale;
        this.catalog = java.util.Objects.requireNonNull(catalog);
        this.assembly = java.util.Objects.requireNonNull(assembly);
        if (assembly.seed() != catalog.seed()) {
            throw new IllegalArgumentException("assembly seed " + assembly.seed()
                    + " != catalog seed " + catalog.seed());
        }
        if (assembly.slotCount() != catalog.slotCount()) {
            throw new IllegalStateException("assembly slot count " + assembly.slotCount()
                    + " != catalog slot count " + catalog.slotCount());
        }
        this.partition = assembly.partition().withScale(scale);
        this.mask = assembly.mask();
        this.oldCoverage = assembly.coverage();
        this.blendLow = assembly.blendLow();
        this.blendHigh = assembly.blendHigh();

        this.slotCats = new SlotCategory[catalog.slotCount()];
        for (int i = 0; i < slotCats.length; i++) {
            slotCats[i] = category(catalog.entry(catalog.slotEntry(i)));
        }
    }

    /** 条目 → 类别（复合取成员中 ordinal 更大者）。 */
    private static SlotCategory category(MorphCatalog.Entry e) {
        if (e.isComposite()) {
            SlotCategory a = category(e.left());
            SlotCategory b = category(e.right());
            return a.ordinal() > b.ordinal() ? a : b;
        }
        return switch (e.kind()) {
            case LOW -> e.index() < 2 ? SlotCategory.PLAIN : SlotCategory.HILLS;
            case PLATEAU -> SlotCategory.PLATEAU;
            case BADLANDS -> SlotCategory.BADLANDS;
            case MOUNTAIN -> SlotCategory.MOUNTAINS;
        };
    }

    /**
     * 按<b>条目下标</b>算类别（{@link MorphAssembly#slotCategories()} 用）。
     *
     * <p>必须先有目录才能按条目算，所以这条路径只在装配骨架里走；
     * 它与本类构造期独立算出的那一份逐槽比对，二者分叉会立刻炸。</p>
     */
    static SlotCategory categoryOfEntry(int entryIndex) {
        return switch (entryIndex) {
            case 0, 1 -> SlotCategory.PLAIN;
            case MorphCatalog.IDX_PLATEAU -> SlotCategory.PLATEAU;
            case MorphCatalog.IDX_BADLANDS -> SlotCategory.BADLANDS;
            case MorphCatalog.IDX_MOUNTAIN -> SlotCategory.MOUNTAINS;
            default -> SlotCategory.HILLS;
        };
    }

    long seed() { return catalog.seed(); }
    MorphCatalog catalog() { return catalog; }
    MorphAssembly assembly() { return assembly; }
    float scale() { return scale; }
    MorphRegionPartition partition() { return partition; }
    SlotCategory[] slotCategories() { return slotCats.clone(); }
    MorphNoise.Node mask() { return mask; }
    MorphAssembly.Family oldCoverage() { return oldCoverage; }
    float blendLow() { return blendLow; }
    float blendHigh() { return blendHigh; }
}
