package com.geogenesis.worldgen.hydrology.sim;

/**
 * tile 边界端口（不可变）。
 *
 * <h2>规范化（跨 tile 可匹配的唯一关键）</h2>
 * <p>端口用<b>外侧格</b>表示：若本 tile 的边界格 {@code c} 的水沿方向 {@code d} 流出窗外，
 * 则外侧格 {@code o = c + d}，规范 id = {@code (o.x, o.z, opposite(d))}（方向指回本 tile）。</p>
 *
 * <p>邻 tile 计算同一物理边时，会把 {@code o} 当作自己的<b>内边界格</b>、方向取
 * {@code opposite(d)}（指向本 tile）⇒ <b>两侧得到同一个规范 id</b>，无需协商即可配对。
 * 这就是"端口是坐标稳定的"这一契约的实现。</p>
 *
 * @param gx        外侧格的全球格 X
 * @param gz        外侧格的全球格 Z
 * @param dir       从外侧格指回本 tile 的方向 ordinal（见 {@link HydroContract#DIR_DX}）
 * @param kind      端口种类
 * @param innerGx   本 tile 侧边界格的全球格 X
 * @param innerGz   本 tile 侧边界格的全球格 Z
 * @param spillLevel 水越过该端口时的水位（方块 Y）
 * @param height    边界处地形高度（方块 Y）
 * @param source    该端口聚合的上游侧向补给（block³/单位时间，已含衰减）
 * @param flow      稳态通过的流量（block³/单位时间）
 * @param basinId   若该端口由某个盆地的溢口产生，则为该盆地的全局 id；否则 -1
 * @param ownerTile 拥有内边界格的 tile 的稳定键（诊断/配对用）
 */
public record HydroPort(int gx, int gz, int dir, PortKind kind,
                        int innerGx, int innerGz,
                        double spillLevel, double height,
                        double source, double flow, long basinId, long ownerTile) {

    /** 外侧格取反方向，得到规范方向。 */
    public static int opposite(int dir) {
        return (dir + 4) & 7;
    }

    /** 规范 id：由"内边界格 + 向外方向"构造跨 tile 可匹配的身份。 */
    public static HydroTileKey.PortId canonicalId(int innerGx, int innerGz, int dir) {
        return new HydroTileKey.PortId(innerGx + HydroContract.DIR_DX[dir],
                innerGz + HydroContract.DIR_DZ[dir], opposite(dir));
    }

    public HydroTileKey.PortId id() {
        return new HydroTileKey.PortId(gx, gz, dir);
    }

    /** 该端口是否已确定归宿（可继续下泄），还是保守未决。 */
    public boolean resolved() {
        return kind != PortKind.PENDING;
    }

    public HydroPort withFlow(double newFlow) {
        return new HydroPort(gx, gz, dir, kind, innerGx, innerGz, spillLevel, height,
                source, newFlow, basinId, ownerTile);
    }

    public HydroPort withSourceAndFlow(double newSource, double newFlow) {
        return new HydroPort(gx, gz, dir, kind, innerGx, innerGz, spillLevel, height,
                newSource, newFlow, basinId, ownerTile);
    }

    public HydroPort withKind(PortKind newKind) {
        return new HydroPort(gx, gz, dir, newKind, innerGx, innerGz, spillLevel, height,
                source, flow, basinId, ownerTile);
    }

    @Override
    public String toString() {
        return "HydroPort[" + gx + "," + gz + " " + HydroContract.DIR_NAME[dir] + " " + kind
                + " spill=" + String.format(java.util.Locale.ROOT, "%.3f", spillLevel)
                + " src=" + String.format(java.util.Locale.ROOT, "%.3f", source)
                + " flow=" + String.format(java.util.Locale.ROOT, "%.3f", flow)
                + "]";
    }
}
