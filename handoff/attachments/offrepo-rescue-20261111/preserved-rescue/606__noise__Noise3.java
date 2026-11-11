package com.geogenesis.worldgen.noise;

/**
 * 三维噪声接口（★ 2026-09-15 新增）—— 供<b>洞穴隧道</b>使用。
 *
 * <h3>为何必须新增 3D 噪声（而非复用现有 2D）</h3>
 * <p>洞穴初版移植了 TerraForged 的"<b>2D 场驱动竖直柱体切挖</b>"：每 (x,z) 用 2D 噪声
 * 算出 {@code [bottom, top]} 再整柱挖空，靠相邻列重叠形成"网络"。移植时看似合理
 * （TF 如此、成本极低），但<b>用户实测"洞穴完全不成洞穴的样子"</b>。</p>
 *
 * <p>根因是<b>几何性</b>的，不是参数没调好：柱体切挖产出的空洞本质是<b>竖直柱</b>，
 * 其水平截面是<b>孤立点/小团</b> —— 无论怎样调参都<b>不可能</b>得到蜿蜒、有分支、
 * 可上下起伏的隧道。水平切片图（{@code build/cave/horiz_y*.png}）一眼可见：
 * 只有几个孤立大块，毫无隧道网络。</p>
 *
 * <p>真正的隧道需要 <b>3D 噪声的等值面交集</b>：两个独立 3D 噪声的零等值面
 * 各自是 2D 曲面，<b>两张曲面相交得到的是 1D 曲线</b> —— 那就是隧道。</p>
 *
 * <h3>与 2D {@link Noise} 的关系</h3>
 * <p>{@link Noise} 是纯 2D（{@code compute(x,z)}），且其 {@code mapAll}/{@code seedAll}
 * 递归体系是为 2D 噪声图设计的。三维噪声是<b>独立体系</b>（不参与数据包 2D 噪声图），
 * 故单独立接口，避免污染现有 2D 链路。</p>
 */
public interface Noise3 {

    /**
     * 输入三维坐标，返回约 {@code [-1,1]} 的噪声值。
     */
    double compute(double x, double y, double z);
}
