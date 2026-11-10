package com.geogenesis.client.reveal;

/**
 * <b>透视相机</b>（★ v37，为零 MC 依赖的纯数学类）。
 *
 * <h2>为什么需要它</h2>
 * <p>沙盘用的是<b>正交</b> 3×3 旋转（{@code SandboxGeometry.rotationRows}）——
 * 没有近大远小，适合"看地形全貌"。但落成动画要把玩家从"空中看沙盘"
 * 一路带进"第一人称站在地上"，必须有<b>透视</b>。</p>
 *
 * <p>参考项目 {@code SiteRevealWorldRenderer} 也是这套：
 * {@code eye/look/right/up + fov}，用 {@code drawProjF = 1/tan(fov/2)} 做透视除法。</p>
 *
 * <h2>投影公式</h2>
 * <pre>
 *   相对相机 = p - eye
 *   cx = dot(rel, right)          // 相机右方向的坐标
 *   cy = dot(rel, up)             // 相机上方向的坐标
 *   cz = dot(rel, look)           // 沿视线方向的深度（>0 在相机前方）
 *
 *   f  = 1 / tan(fov/2)           // 焦距系数
 *   sx = cx / cz * f * (H/2)      // 屏幕 x（相对屏幕中心）
 *   sy = -cy / cz * f * (H/2)     // 屏幕 y（屏幕坐标向下为正，所以取负）
 *   depth = cz                    // 用于远→近排序
 * </pre>
 *
 * <p>★ 用 {@code H/2}（而不是 W/2）做缩放基准：这样<b>竖向视角固定在 fov</b>，
 * 横向随宽高比自动展开 —— 与 MC 的 {@code GameRenderer} 一致，
 * 也保证落机时镜头参数能直接交给真世界。</p>
 */
public final class PerspectiveCamera {

    /** 相机位置（世界坐标）。 */
    public double eyeX, eyeY, eyeZ;
    /** 视线方向（单位向量）。 */
    public double lookX, lookY, lookZ;
    /** 右方向（单位向量）。 */
    public double rightX, rightY, rightZ;
    /** 上方向（单位向量）。 */
    public double upX, upY, upZ;
    /** 竖向视野（度）。 */
    public float fov;

    public PerspectiveCamera() {
        // 默认朝 +Z（与 MC 的 yaw=0 一致），右朝 -X
        lookX = 0; lookY = 0; lookZ = 1;
        rightX = -1; rightY = 0; rightZ = 0;
        upX = 0; upY = 1; upZ = 0;
        fov = 40f;
    }

    /**
     * 由「机位 + yaw/pitch」设定相机。
     *
     * <p>yaw/pitch 与 MC 一致：yaw 是朝向的水平角（度，0 = +Z，顺时针），
     * pitch 是俯仰（度，正 = 往下看）。</p>
     */
    public void set(double eyeX, double eyeY, double eyeZ, float yawDeg, float pitchDeg, float fov) {
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.fov = fov;

        double yr = Math.toRadians(yawDeg);
        double pr = Math.toRadians(pitchDeg);
        // look：与参考项目 SiteRevealWorldRenderer 第 514 行同式
        lookX = -Math.sin(yr) * Math.cos(pr);
        lookY = -Math.sin(pr);
        lookZ = Math.cos(yr) * Math.cos(pr);
        // right：水平面内垂直于 look
        rightX = -Math.cos(yr);
        rightY = 0;
        rightZ = -Math.sin(yr);
        // up = right × look（右手系）
        double ux = rightY * lookZ - rightZ * lookY;
        double uy = rightZ * lookX - rightX * lookZ;
        double uz = rightX * lookY - rightY * lookX;
        double len = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (len < 1e-9) { ux = 0; uy = 1; uz = 0; len = 1; }
        upX = ux / len;
        upY = uy / len;
        upZ = uz / len;
    }

    /**
     * 世界点 → 屏幕坐标。
     *
     * @param out 输出 {@code [sx, sy, depth]}；{@code depth <= 0} 表示在相机后方（应剔除）
     * @return {@code depth > 0}（在相机前方，可画）
     */
    public boolean project(double px, double py, double pz,
                           double centerX, double centerY, double halfH, double[] out) {
        double rx = px - eyeX;
        double ry = py - eyeY;
        double rz = pz - eyeZ;
        double cx = rx * rightX + ry * rightY + rz * rightZ;
        double cy = rx * upX + ry * upY + rz * upZ;
        double cz = rx * lookX + ry * lookY + rz * lookZ;
        out[2] = cz;
        if (cz <= 1e-4) return false;                  // 相机后方 / 太近
        double f = 1.0 / Math.tan(Math.toRadians(fov) * 0.5);
        out[0] = centerX + cx / cz * f * halfH;
        out[1] = centerY - cy / cz * f * halfH;        // 屏幕 y 向下为正
        return true;
    }

    /**
     * 用于<b>深度排序</b>的键：距离相机越远值越大（先画）。
     *
     * <p>透视下用<b>到相机的距离</b>，不能用视空间 z 的简单线性量 ——
     * 近距离的柱子跨度大，用 z 排序会出现"近处被远处盖住"。</p>
     */
    public double depthKey(double px, double py, double pz) {
        double rx = px - eyeX;
        double ry = py - eyeY;
        double rz = pz - eyeZ;
        return rx * rx + ry * ry + rz * rz;
    }

    /** 相机到某点的距离（落机与让位调度用）。 */
    public double distanceTo(double px, double py, double pz) {
        double rx = px - eyeX;
        double ry = py - eyeY;
        double rz = pz - eyeZ;
        return Math.sqrt(rx * rx + ry * ry + rz * rz);
    }

    // =================================================================
    // 与正交侧的混合（开场 morph 用）
    // =================================================================

    /**
     * <b>正交 ↔ 透视混合投影</b>（★ 落成动画开场）。
     *
     * <p>参考项目开场那 3000ms 是"同一张图在两种投影之间移动"，
     * 不是两张图 dissolve。做法：把同一个世界点，分别用正交和透视投影，
     * 再按 {@code orthoWeight} 线性混合屏幕坐标。</p>
     *
     * @param orthoW 1 = 纯正交（开场），0 = 纯透视（落定）
     * @param out 输出 {@code [sx, sy, depth]}
     * @return 是否可画（两种投影任一在前方即可）
     */
    public boolean projectMixed(double px, double py, double pz,
                                double orthoW, float[] orthoRows,
                                double orthoOriginX, double orthoOriginY, double orthoScale,
                                double centerX, double centerY, double halfH, double[] out) {
        // 默认不做世界→模型归一化（调用方自己给模型坐标）
        return projectMixed(px, py, pz, orthoW, orthoRows,
                orthoOriginX, orthoOriginY, orthoScale,
                0.0, 0.0, 0.0, 0.0,
                centerX, centerY, halfH, out);
    }

    /**
     * <b>带「世界坐标 → 模型空间」归一化的混合投影</b>（★ v45）。
     *
     * <h2>为什么必须归一化</h2>
     * <p>正交侧的 {@code orthoScale} 是按沙盘的<b>模型坐标 [-1,1]</b> 标定的
     * （{@code SandboxGeometry.scaleOf(areaSize, zoom)} 的语义）。
     * 而落成动画手里是<b>世界坐标</b>（几百格）⇒ 直接代进去会跑到屏幕外几万像素，
     * 表现为"开场什么都没有"。</p>
     *
     * <p>⇒ 先 {@code model = (world - worldCenter) / worldHalf} 落到 [-1,1]，
     * 再用 {@code orthoRows} 旋转、{@code orthoScale} 缩放。
     * 这与沙盘 {@code draw()} 的 {@code modelEdge} 语义一致。</p>
     *
     * @param worldHalf      模型 ±1 对应的方块数（= 沙盘窗口半宽）
     * @param worldCenterX   模型原点对应的世界 X（沙盘窗口中心）
     * @param worldCenterZ   模型原点对应的世界 Z
     * @param worldBaseY     模型 z=0 对应的世界 Y（沙盘基准面 = seaLevel-4）
     */
    public boolean projectMixed(double px, double py, double pz,
                                double orthoW, float[] orthoRows,
                                double orthoOriginX, double orthoOriginY, double orthoScale,
                                double worldHalf, double worldCenterX, double worldCenterZ,
                                double worldBaseY,
                                double centerX, double centerY, double halfH, double[] out) {
        // ---- 透视侧（世界坐标）----
        boolean okP = project(px, py, pz, centerX, centerY, halfH, out);
        double prjX = out[0];
        double prjY = out[1];
        double depth = out[2];
        if (!okP) {
            // 透视侧不可见（相机后方）
            prjX = 0; prjY = 0;
        }
        // ---- 正交侧：世界 → 模型 [-1,1] → 旋转/缩放 ----
        double invH = worldHalf > 1e-6 ? 1.0 / worldHalf : 1.0;
        double mx = (px - worldCenterX) * invH;
        double my = (pz - worldCenterZ) * invH;      // 模型 y = 世界 Z
        double mz = (py - worldBaseY) * invH;        // 模型 z = 世界 Y 相对基准面
        double ox = orthoOriginX + orthoScale * (orthoRows[0] * mx + orthoRows[1] * my + orthoRows[2] * mz);
        double oy = orthoOriginY + orthoScale * (orthoRows[3] * mx + orthoRows[4] * my + orthoRows[5] * mz);
        double od = orthoRows[6] * mx + orthoRows[7] * my + orthoRows[8] * mz;

        if (!okP && orthoW >= 0.999) {
            out[0] = ox; out[1] = oy; out[2] = od;
            return true;
        }
        if (!okP) return false;

        out[0] = ox * orthoW + prjX * (1 - orthoW);
        out[1] = oy * orthoW + prjY * (1 - orthoW);
        out[2] = od * orthoW + depth * (1 - orthoW);
        return true;
    }
}
