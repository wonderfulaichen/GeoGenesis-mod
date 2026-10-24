package com.geogenesis.worldgen.hydrology.model;

/**
 * ★ v2：<b>typed 接力上下文</b>（接收方视角的一条端口承诺）。
 *
 * <h3>要治的旧缺陷（v1 缺陷 ①）</h3>
 * <p>v1 的接力接口是 {@code List<Integer>}：只有<b>锚点格索引</b>。方向与"真实下一步"
 * 全部丢在传递过程中 ⇒ 接收方只能拿<b>自己</b>的地形重猜第一步。两端猜法不同，
 * 缝上就出现「锚点有、下一步没有」的假接力（真实区实测 8 个端口因此闭环失败）。</p>
 *
 * <h3>本类型携带什么</h3>
 * <ul>
 *   <li>{@code anchorCell} / {@code nextCell}：<b>接收方窗口</b>的格索引（已换算，非世界坐标）；</li>
 *   <li>{@code dirX,dirZ}：发送端声明的<b>来向</b>（锚点→next 的单位向量）；</li>
 *   <li>{@code anchorX,anchorZ,nextX,nextZ}：世界坐标（逐位，供跨区断言与日志）。</li>
 * </ul>
 *
 * <h3>语义边界（不夸大）</h3>
 * <p>接收方的<b>初始惯性</b>取的是「锚点→next」这条<b>真实出边方向</b>，而不是发送端
 * 内部合成向量的复现 —— 后者含发送端的历史状态，跨区无法也不应传递。{@code dirX/dirZ}
 * 只用来做<b>一致性校验</b>：被清空或与网格边不符 ⇒ 拒收（{@code WIRE_DIR_*}），
 * 绝不拿零向量冒充惯性。</p>
 *
 * <p>不带区域身份：身份已在 {@link BoundaryPort} 上盖过，这里只保留"本区格索引"这一层。</p>
 */
public record WireIn(int anchorCell, int nextCell, double dirX, double dirZ,
                     int anchorX, int anchorZ, int nextX, int nextZ) {

    /** 兼容视图：只取锚点格（旧 {@code List<Integer>} 调用方用）。 */
    public int compatCell() {
        return anchorCell;
    }

    @Override
    public String toString() {
        return "wire[cell=" + anchorCell + "->" + nextCell + " world(" + anchorX + "," + anchorZ
                + ")->(" + nextX + "," + nextZ + ") dir("
                + String.format(java.util.Locale.ROOT, "%.3f,%.3f", dirX, dirZ) + ")]";
    }
}
