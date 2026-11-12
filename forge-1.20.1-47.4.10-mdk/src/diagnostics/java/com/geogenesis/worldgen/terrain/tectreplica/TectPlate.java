package com.geogenesis.worldgen.terrain.tectreplica;

import java.util.ArrayList;
import java.util.List;

/**
 * 板块刚体（Plate）—— tectonics.h:55-100 的移植，update 四阶段照抄
 * （tectonics.h:102-280）：碰撞俯冲 → 造山扩散 → 生长 → 对流积分与刚体带动。
 *
 * <p>没有速度场：板块是一个平动速度 + 一个角速度的刚体，段随刚体移动。
 * DT=0.025 只用在刚体积分（tectonics.h:260-263）；生长率、热扩散系数都是
 * "每步"直接给系数，与 DT 无关。</p>
 */
final class TectPlate {

    final List<TectSegment> segs = new ArrayList<>();

    double posX, posY;
    double speedX, speedY;
    double rotation, angveloc;

    double mass, area, inertia, height;

    /** tectonics.h:73-74。 */
    private static final double CONVECTION = 10.0;
    private static final double GROWTH = 0.05;
    /** 厚度/质量下限（防 ±∞ 的钳制值，见 orogeny 内的偏差记录）。 */
    private static final double FLOOR = 0.02;

    TectPlate(double x, double y) {
        this.posX = x;
        this.posY = y;
    }

    /**
     * 汇总刚体量（tectonics.h:76-97）。
     * ⚠ 原版怪癖如实保留：inertia 用<b>累积中的</b> pos（未除 N 的运行和）算，
     * 不是对最终质心的（tectonics.h:91）。
     */
    void recenter() {
        double sx = 0, sy = 0;
        mass = 0;
        area = 0;
        inertia = 0;
        height = 0;
        for (TectSegment s : segs) {
            sx += s.x;
            sy += s.y;
            mass += s.mass;
            area += s.area;
            double dx = sx - s.x, dy = sy - s.y;
            inertia += (dx * dx + dy * dy) * s.mass;
            height += s.height;
        }
        double n = segs.size();
        posX = sx / n;
        posY = sy / n;
        height /= n;
    }

    /** 一步 = 四阶段（SimpleTectonics.cpp:180-181 每帧对每板块各调一次）。 */
    void update(TectCluster cluster, double[] heatmap) {
        collide(cluster);
        orogeny(cluster);
        grow(heatmap);
        convect(heatmap);
    }

    // ===== ① 碰撞/俯冲（tectonics.h:107-154）=====

    private void collide(TectCluster cluster) {
        for (TectSegment s : segs) {
            // ivec2 截断；出 [0,SIZE) 即死（原版无环绕）
            int ipx = (int) s.x, ipy = (int) s.y;
            if (ipx >= TectWorld.SIZE || ipx < 0 || ipy >= TectWorld.SIZE || ipy < 0) {
                s.alive = false;
                continue;
            }
            for (int j = 0; j < 12; j++) {
                double ang = (double) j / 12.0 * 2.0 * Math.PI;
                double sx = s.x + TectWorld.SIZE * TectWorld.CR * Math.cos(ang);
                double sy = s.y + TectWorld.SIZE * TectWorld.CR * Math.sin(ang);
                if (sx >= TectWorld.SIZE || sx < 0 || sy >= TectWorld.SIZE || sy < 0) {
                    continue;
                }
                int idx = cluster.sample(sx, sy);
                if (idx < 0) {
                    continue;   // 背景
                }
                TectSegment n = cluster.segs.get(idx);
                if (n.parent == this) {
                    continue;   // 同板块（或同段）
                }
                // 密度大者俯冲：整根柱转给对方后死亡
                if (s.density > n.density && !n.colliding) {
                    double mdiff = s.height * s.density * s.area;
                    double hdiff = s.height;
                    n.thickness += hdiff;
                    n.mass += mdiff;
                    n.buoyancy();
                    n.colliding = true;
                    s.alive = false;
                    // 原版此处不 break（tectonics.h:138-150）：同一根柱可被 12 条射线
                    // 重复投给多个邻居——如实照抄，形态就是在带这个怪癖的动力学下涌现的
                }
            }
        }
    }

    // ===== ② 造山/聚合扩散（tectonics.h:156-198）=====

    /**
     * ⚠ 原版这里迭代的是<b>全局</b> cluster.segs（不是本板块的段），且本方法在
     * 每个板块的 update 里都会执行一遍——第一个板块会消费掉全部 colliding 标记
     * （每个被处理的段末尾清 flag），后续板块的 collide 阶段再产生新标记。
     * 如实照抄这个结构。
     */
    private void orogeny(TectCluster cluster) {
        for (TectSegment s : cluster.segs) {
            if (!s.colliding) {
                continue;
            }
            for (int j = 0; j < 24; j++) {
                double ang = (double) j / 24.0 * 2.0 * Math.PI;
                double sx = s.x + TectWorld.SIZE * TectWorld.R * Math.cos(ang);
                double sy = s.y + TectWorld.SIZE * TectWorld.R * Math.sin(ang);
                if (sx >= TectWorld.SIZE || sx < 0 || sy >= TectWorld.SIZE || sy < 0) {
                    continue;
                }
                int idx = cluster.sample(sx, sy);
                if (idx < 0) {
                    continue;
                }
                TectSegment n = cluster.segs.get(idx);
                if (n == s) {
                    continue;
                }
                double hdiff = s.height - n.height - 0.01;
                if (hdiff < 0) {
                    continue;
                }
                double mdiff = hdiff * s.density * s.area;
                double trate = 0.2;
                // ★ 偏差记录（唯一改动）：原版允许透支——24 个邻居各取 0.1·hdiff，
                //   总量可超过捐助者自身 ⇒ thickness/mass 变负 ⇒ density = mass/(area·thickness)
                //   爆 ±∞（20000 步实测 max=4.4e36，C++ float 同样会炸）。这里把每次捐赠
                //   钳到"捐后不低于 FLOOR"，正常动态远低于该钳制（单次 0.1·hdiff ~ 0.1）。
                double giveT = Math.min(0.5 * trate * hdiff, s.thickness - FLOOR);
                double giveM = Math.min(0.5 * trate * mdiff, s.mass - FLOOR);
                if (giveT <= 0 || giveM <= 0) {
                    s.colliding = false;
                    break;
                }
                n.thickness += giveT;
                s.thickness -= giveT;
                n.mass += giveM;
                s.mass -= giveM;
                n.buoyancy();
                s.buoyancy();
            }
            s.colliding = false;
        }
    }

    // ===== ③ 生长（tectonics.h:200-227）=====

    private void grow(double[] heatmap) {
        for (TectSegment s : segs) {
            if (!s.alive) {
                continue;
            }
            int ipx = (int) s.x, ipy = (int) s.y;
            // 热场 x-major 约定：hm[ip.y + ip.x*SIZE]（tectonics.h:211）
            double nd = heatmap[ipy + ipx * TectWorld.SIZE];
            // 线性生长率：柱质量向 1-nd 饱和；G<0（过热或过厚）→ 缓慢溶解
            double g = GROWTH * (1.0 - nd) * (1.0 - nd - s.density * s.thickness);
            if (g < 0.0) {
                g *= 0.05;
                // ★ 偏差记录：溶解钳到 FLOOR（0.02），防止数万步后 thickness→0 ⇒
                //   density = mass/(area·thickness) 爆 ±∞（同 orogeny 的钳制理由）
                if (s.thickness + g < FLOOR) {
                    g = FLOOR - s.thickness;
                }
            }
            // 平衡密度：langmuir(k=3, 1-nd)（tectonics.h:202-204,219）
            double d = langmuir(3.0, 1.0 - nd);
            s.mass += s.area * g * d;
            s.thickness += g;
            s.density = s.mass / (s.area * s.thickness);
            s.height = s.thickness * (1.0 - s.density);
        }
    }

    private static double langmuir(double k, double x) {
        return k * x / (1.0 + k * x);
    }

    // ===== ④ 对流 + 刚体积分 + 带动段（tectonics.h:229-280）=====

    private void convect(double[] heatmap) {
        double accX = 0, accY = 0, torque = 0;
        for (TectSegment s : segs) {
            double[] f = force((int) s.x, (int) s.y, heatmap);
            double dirX = s.x - posX, dirY = s.y - posY;
            accX -= CONVECTION * f[0];
            accY -= CONVECTION * f[1];
            double lf = Math.sqrt(f[0] * f[0] + f[1] * f[1]);
            double ld = Math.sqrt(dirX * dirX + dirY * dirY);
            torque -= CONVECTION * ld * lf * Math.sin(ang(f[0], f[1]) - ang(dirX, dirY));
        }
        speedX += TectWorld.DT * accX / mass;
        speedY += TectWorld.DT * accY / mass;
        angveloc += 1E4 * TectWorld.DT * torque / inertia;   // 1E4 扭矩放大（tectonics.h:261）
        posX += TectWorld.DT * speedX;
        posY += TectWorld.DT * speedY;
        rotation += TectWorld.DT * angveloc;
        if (rotation > 2.0 * Math.PI) {
            rotation -= 2.0 * Math.PI;
        }
        if (rotation < 0) {
            rotation += 2.0 * Math.PI;
        }
        // 刚体带动：dir 用"移动前质心"与"转动前角度"算角偏移（tectonics.h:268-276）
        double oldX = posX - TectWorld.DT * speedX;
        double oldY = posY - TectWorld.DT * speedY;
        double oldRot = rotation - TectWorld.DT * angveloc;
        for (TectSegment s : segs) {
            double dirX = s.x - oldX, dirY = s.y - oldY;
            double len = Math.sqrt(dirX * dirX + dirY * dirY);
            double a = ang(dirX, dirY) - oldRot;
            s.x = posX + len * Math.cos(rotation + a);
            s.y = posY + len * Math.sin(rotation + a);
        }
    }

    /**
     * 热场中心差分力（tectonics.h:231-248）。
     * <p>★ 偏差记录：原版 {@code float fx, fy = 0.0f;} 只初始化了 fy——凡中心差分被
     * 跳过且 1&le;x&le;254 的位置（如 y=0、y&ge;254、x=254），fx 读的是<b>未初始化栈值</b>。
     * Java 无 UB，统一置 0。边界 else-if 分支（&le;0、&ge;SIZE-1 时置 &plusmn;0.0）与本实现等价。</p>
     */
    private static double[] force(int ix, int iy, double[] hm) {
        double fx = 0, fy = 0;
        if (ix > 0 && ix < TectWorld.SIZE - 2 && iy > 0 && iy < TectWorld.SIZE - 2) {
            fx = (hm[(ix + 1) * TectWorld.SIZE + iy] - hm[(ix - 1) * TectWorld.SIZE + iy]) / 2.0;
            fy = -(hm[ix * TectWorld.SIZE + iy + 1] - hm[ix * TectWorld.SIZE + iy - 1]) / 2.0;
        }
        return new double[] {fx, fy};
    }

    /**
     * 原版 angle()（tectonics.h:6-18）≡ atan2(y,x) mod 2π（其 2π 偏移量在
     * {@code sin(angle(f)-angle(dir))} 中不变）；直接用 atan2，逐象限等价。
     */
    private static double ang(double dx, double dy) {
        if (dx == 0 && dy == 0) {
            return 0.0;
        }
        return Math.atan2(dy, dx);
    }
}
