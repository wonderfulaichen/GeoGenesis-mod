package com.geogenesis.worldgen.terrain.tectreplica;

/**
 * 板块段（Litho）—— tectonics.h:28-53 的移植。
 *
 * <p>洋/陆分化在原版里<b>没有任何显式字段</b>：新壳（间隙增生）薄而轻，
 * 热区生长慢、冷区快长，厚→密→高由 {@link #buoyancy()} 涌现
 * （tectonics.h 全文无 crust type / age / 离脊距离，已 grep 确认）。</p>
 *
 * <p>移植取舍：
 * <ul>
 * <li>原版 {@code Segment::area} 是 int 且 EMA 每步截断（cluster.h:14,73）——
 *     复刻用 double 存储但 EMA 写回时 Math.floor 截断（见 TectCluster.update），
 *     语义与原版一致：<b>area 实际永远停在 1</b>，这是参考动力学的关键组成；</li>
 * <li>原版死字段 speed/growth/plateheight 不移植；</li>
 * <li>新建段不调 {@link #buoyancy()}：density 停留在默认 0.5、height=0
 *     （与原版一致——真实值应为 1.0/0.0，这个短暂失真会在下一步生长时被重算覆盖，
 *     tectonics.h:224-225）。</li>
 * </ul></p>
 */
final class TectSegment {

    /** 段质心（像素单位，0~256 域）。原版是 vec2* 别名到 cluster.points，Java 直接存值。 */
    double x;
    double y;

    /** Voronoi 像素面积（CPU 统计 + int 截断 EMA，实际恒为 1——见 TectCluster.update）。 */
    double area = 1.0;

    boolean alive = true;
    /** 本步刚接收过一次俯冲（限制每段每步只被俯冲一次）；也作造山扩散触发标记。 */
    boolean colliding = false;

    /** 段柱体总质量（相对单位）。 */
    double mass = 0.1;
    /** 段岩石圈厚度。 */
    double thickness = 0.1;

    /** 派生：mass/(area·thickness)。 */
    double density = 0.5;
    /** 派生：thickness·(1−density)，浮力均衡后露出"地幔"的高度。 */
    double height = 0.0;

    TectPlate parent;

    TectSegment(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /** Airy 均衡一阶近似：height = thickness − mass/area（tectonics.h:48-51）。 */
    void buoyancy() {
        density = mass / (area * thickness);
        height = thickness * (1.0 - density);
    }
}
