package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * 体积粒子侵蚀模拟 (Particle Brush Erosion)
 * 
 * 核心创新：
 * 粒子不再是 1x1 的质点，而是带有动态半径的“笔刷”。
 * 粒子的半径 R 与其携带的水量 (volume / discharge) 成正比。
 * 侵蚀和沉积不再只作用于中心点，而是通过高斯笔刷权重分布到周围的网格。
 */
public class ParticleBrushErosion {

    // ============ 模拟参数 ============
    static int MAP_SIZE = 512;
    static int ITERATIONS = 100;
    static int DROPS_PER_ITER = 5000;
    static float LRATE = 0.1f;
    static float GRAVITY = 1.0f;
    static float MOMENTUM = 0.3f;
    static float EVAP = 0.001f;
    static float DEPOSITION = 0.05f;
    static float ENTRAINMENT = 2.0f; // 降低侵蚀系数，因为大面积侵蚀威力大
    static float MIN_VOL = 0.01f;
    static float MAX_AGE = 500;

    // 笔刷相关参数
    static float BASE_RADIUS = 0.5f;   // 基础半径
    static float VOL_TO_RADIUS = 0.8f; // 水量到半径的转换系数
    static float MAX_RADIUS = 8.0f;    // 最大笔刷半径

    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 2000;

    final int w, h;
    final float[][] height;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my;
    final float[][] mxTrack, myTrack;

    final StandalonePreview terrain;
    final long seed;
    final Random rng;

    public ParticleBrushErosion(int seed, int mapSize) {
        this.seed = seed;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 8888);
        this.w = mapSize;
        this.h = mapSize;
        this.height = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];
    }

    void initHeightmap() {
        float scale = (float)VIEW_SIZE / Math.max(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float wx = (x - w/2f) * scale;
                float wz = (y - h/2f) * scale;
                // 为了明显看出河流侵蚀，我们暂时不叠加海洋凹陷，用纯地形
                height[y][x] = terrain.computeHeightPure(wx, wz);
            }
        }
    }

    float getH(int x, int y) {
        if (x < 0 || x >= w || y < 0 || y >= h) return 0;
        return height[y][x];
    }

    // 体积法线采样（采样范围等于粒子的半径）
    float[] volumeNormal(float fx, float fy, float radius) {
        int ix = (int)fx, iy = (int)fy;
        int r = Math.max(1, (int)radius);
        
        float gx = getH(ix + r, iy) - getH(ix - r, iy);
        float gy = getH(ix, iy + r) - getH(ix, iy - r);
        // 按采样距离归一化梯度
        gx /= (2 * r);
        gy /= (2 * r);
        
        float len = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
        return new float[]{-gx/len, -gy/len};
    }

    // 在指定位置获取周围范围的平均高度
    float sampleVolumeHeight(float fx, float fy, float radius) {
        int cx = (int)fx, cy = (int)fy;
        int r = (int)Math.ceil(radius);
        if (r <= 1) return getH(cx, cy);

        float sum = 0;
        float weightSum = 0;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float dist2 = dx*dx + dy*dy;
                if (dist2 > radius*radius) continue;
                
                int nx = cx + dx, ny = cy + dy;
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                
                float wgt = 1.0f - dist2 / (radius*radius);
                sum += height[ny][nx] * wgt;
                weightSum += wgt;
            }
        }
        return weightSum > 0 ? sum / weightSum : getH(cx, cy);
    }

    // 核心下降逻辑
    boolean descend(float[] pos, float[] vel, float[] volSedAge) {
        int ix = (int)pos[0], iy = (int)pos[1];
        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return false;

        float vol = volSedAge[0];
        float sediment = volSedAge[2];
        float age = volSedAge[1];

        // 计算当前位置的粒子半径 (基于累积流量 discharge + 当前体积)
        float localDischarge = discharge[iy][ix];
        float radius = BASE_RADIUS + (float)Math.sqrt(localDischarge + vol) * VOL_TO_RADIUS;
        radius = Math.min(radius, MAX_RADIUS);

        if (age > MAX_AGE || vol < MIN_VOL || height[iy][ix] < SEA_LEVEL) {
            applyBrushErosion(ix, iy, radius, -sediment); // 停止时完全沉积
            return false;
        }

        // 获取带体积的法线
        float[] n = volumeNormal(pos[0], pos[1], radius);
        
        vel[0] += GRAVITY * n[0] / vol;
        vel[1] += GRAVITY * n[1] / vol;

        // 动量影响
        float mdx = mx[iy][ix], mdy = my[iy][ix];
        float mlen = (float)Math.sqrt(mdx*mdx + mdy*mdy);
        float vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (mlen > 0 && vlen > 0) {
            float dot = (vel[0]*mdx + vel[1]*mdy) / (vlen * mlen);
            float factor = MOMENTUM * dot / (vol + localDischarge + 1f);
            vel[0] += factor * mdx;
            vel[1] += factor * mdy;
        }

        // 规范化速度步长，步长与半径相关，避免大粒子跳跃穿模
        vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (vlen > 0) {
            float step = Math.max(1.414f, radius * 0.5f); // 半径大，步长也可以适当加大
            vel[0] = (vel[0] / vlen) * step;
            vel[1] = (vel[1] / vlen) * step;
        }

        pos[0] += vel[0];
        pos[1] += vel[1];

        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) return false;

        int nx = (int)pos[0], ny = (int)pos[1];
        
        // 更新 discharge 和动量
        dischargeTrack[ny][nx] += vol;
        mxTrack[ny][nx] += vol * vel[0];
        myTrack[ny][nx] += vol * vel[1];

        // 高度差计算（使用体积采样）
        float h1 = sampleVolumeHeight(ix, iy, radius);
        float h2 = sampleVolumeHeight(pos[0], pos[1], radius);

        float nodeDischarge = (float)Math.log1p(localDischarge);
        float c_eq = (1.0f + ENTRAINMENT * nodeDischarge) * (h1 - h2);
        if (c_eq < 0) c_eq = 0;

        float cdiff = c_eq - sediment;
        float amount = DEPOSITION * cdiff;

        sediment += amount;
        
        // === 核心创新：使用笔刷分布侵蚀/沉积 ===
        applyBrushErosion(ix, iy, radius, amount);

        sediment = Math.max(-10.0f, Math.min(10.0f, sediment));
        sediment /= (1.0f - EVAP);
        vol *= (1.0f - EVAP);

        volSedAge[0] = vol;
        volSedAge[1] = age + 1;
        volSedAge[2] = sediment;

        return true;
    }

    // 笔刷应用侵蚀或沉积
    // amount > 0 表示侵蚀（带走泥沙，高度下降）
    // amount < 0 表示沉积（释放泥沙，高度上升）
    void applyBrushErosion(int cx, int cy, float radius, float amount) {
        int r = (int)Math.ceil(radius);
        if (r <= 1) {
            height[cy][cx] -= amount;
            return;
        }

        float weightSum = 0;
        float[][] weights = new float[2*r+1][2*r+1];

        // 计算高斯分布权重
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float dist2 = dx*dx + dy*dy;
                if (dist2 > radius*radius) continue;
                
                int nx = cx + dx, ny = cy + dy;
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;

                // 余弦/平滑钟形曲线权重
                float t = (float)Math.sqrt(dist2) / radius;
                float wgt = 1.0f - t * t * (3 - 2 * t); // smoothstep
                
                weights[dy+r][dx+r] = wgt;
                weightSum += wgt;
            }
        }

        if (weightSum <= 0) return;

        // 按权重分配侵蚀量
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                if (weights[dy+r][dx+r] == 0) continue;
                
                int nx = cx + dx, ny = cy + dy;
                float localAmount = amount * (weights[dy+r][dx+r] / weightSum);
                height[ny][nx] -= localAmount;
            }
        }
    }

    void simulate() {
        System.out.println("=== Particle Brush Erosion ===");
        System.out.println("Radius: " + BASE_RADIUS + " ~ " + MAX_RADIUS);
        initHeightmap();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    dischargeTrack[y][x] = 0;
                    mxTrack[y][x] = 0;
                    myTrack[y][x] = 0;
                }
            }

            for (int i = 0; i < DROPS_PER_ITER; i++) {
                float px = rng.nextFloat() * w;
                float py = rng.nextFloat() * h;
                if (getH((int)px, (int)py) < SEA_LEVEL) continue;

                float[] pos = {px, py};
                float[] vel = {0, 0};
                float[] vsa = {1.0f, 0, 0.0f}; // vol, age, sediment

                while (descend(pos, vel, vsa)) {}
            }

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    discharge[y][x] = (1f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                    mx[y][x] = (1f - LRATE) * mx[y][x] + LRATE * mxTrack[y][x];
                    my[y][x] = (1f - LRATE) * my[y][x] + LRATE * myTrack[y][x];
                }
            }

            if ((iter + 1) % 10 == 0) {
                System.out.println("Iter " + (iter+1) + "/" + ITERATIONS);
            }
        }
    }

    // ============ 渲染带光照的地形 ============
    void renderAndSave(String filePath) throws Exception {
        simulate();
        
        int sz = w;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);

        System.out.println("Rendering shaded terrain...");
        // 简单的定向光 (左上角打光)
        float[] lightDir = {0.7f, 0.7f, 1.0f};
        float lightLen = (float)Math.sqrt(lightDir[0]*lightDir[0] + lightDir[1]*lightDir[1] + lightDir[2]*lightDir[2]);
        lightDir[0]/=lightLen; lightDir[1]/=lightLen; lightDir[2]/=lightLen;

        float maxD = 0;
        for (int y=0; y<h; y++) for(int x=0; x<w; x++) maxD = Math.max(maxD, discharge[y][x]);

        for (int py = 0; py < sz; py++) {
            for (int px = 0; px < sz; px++) {
                float h_val = height[py][px];
                
                // 计算法线
                float gx = (px<w-1 && px>0) ? (height[py][px+1] - height[py][px-1]) : 0;
                float gy = (py<h-1 && py>0) ? (height[py+1][px] - height[py-1][px]) : 0;
                
                // 高度图梯度的放大系数，越大阴影越明显
                float normalZ = 0.05f; 
                float nLen = (float)Math.sqrt(gx*gx + gy*gy + normalZ*normalZ);
                float nx = -gx/nLen, ny = -gy/nLen, nz = normalZ/nLen;

                // 光照计算 (Diffuse)
                float diffuse = Math.max(0.2f, nx*lightDir[0] + ny*lightDir[1] + nz*lightDir[2]);
                // 环境光遮蔽近似
                float ao = 0.6f + 0.4f * diffuse;

                int color = getTerrainColor(h_val);
                int r = (int)(((color>>16)&0xFF) * ao);
                int g = (int)(((color>>8)&0xFF) * ao);
                int b = (int)((color&0xFF) * ao);

                // 叠加河流
                float d = discharge[py][px];
                if (d > 0.5f) {
                    float t = (float)Math.log1p(d) / (float)Math.log1p(maxD);
                    t = Math.max(0, Math.min(1, t));
                    if (t > 0.5f) { // 主河道
                        r = (int)(20 * ao);
                        g = (int)(80 * ao);
                        b = (int)(180 * ao);
                    }
                }

                img.setRGB(px, py, rgb(Math.min(255, r), Math.min(255, g), Math.min(255, b)));
            }
        }

        ImageIO.write(img, "png", new File(filePath));
        System.out.println("Saved: " + filePath);
    }

    int getTerrainColor(float h) {
        if (h < SEA_LEVEL) return rgb(190, 180, 60); // 沙滩
        float hn = (h - SEA_LEVEL) / (1f - SEA_LEVEL);
        int v = (int)(hn * 200) + 55;
        // 偏棕绿色的土地
        return rgb((int)(v*0.9), (int)(v*1.0), (int)(v*0.8));
    }

    static int rgb(int r, int g, int b) { return (r<<16) | (g<<8) | b; }

    public static void main(String[] args) throws Exception {
        ParticleBrushErosion sim = new ParticleBrushErosion(12345, 512);
        sim.renderAndSave("output/particle_brush_v1.png");
    }
}