package com.geogenesis.client.preview;

/**
 * 旧算法（{@code LEGACY_ONLY}）键的<b>小节分组</b>。
 *
 * <p>这 70 个键里绝大多数是内层样条的 {@code {类型}{Lo|Hi}{Loc|Val|Deriv}{0|1}}，
 * 按 5 个陆地类型分组比平铺 70 行可读得多；剩下 15 个零散键归「其它」。</p>
 *
 * <p>⚠ 本类只做<b>分组</b>，不做取值。分组结果与 {@code KeyText} 的命名解析必须一致
 * （{@code ConfigPanelCoverageProbe} 会交叉校验：凡 {@code LEGACY_ONLY} 键都必须能分组）。</p>
 */
final class LegacyGrouping {

    static String typeLabel(String key) {
        if (key.startsWith("plain")) return "平原（旧样条）";
        if (key.startsWith("hills")) return "丘陵（旧样条）";
        if (key.startsWith("mount")) return "山脉（旧样条）";
        if (key.startsWith("plat")) return "高原（旧样条）";
        if (key.startsWith("basin")) return "盆地（旧样条）";
        return "其它旧参数（海陆/海岸线/语义亲和）";
    }

    private LegacyGrouping() { }
}
