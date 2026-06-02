package com.erosiontest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V29: 修复入海口“断头河”问题 - 让河流自然汇入大海
 * 
 * 改进点：
 * 1. 物理层 - 河口延伸 (Delta Extension)：水滴进入海洋后不立即消失，而是继续惯性运动最多5步，
 *    水量和含沙量逐步衰减至零，模拟河流入海后的自然扩散过程。
 * 2. 渲染层 - 海岸渐变 (Coastal Gradient)：在浅海区域（SEA_LEVEL 以下 0~0.02 深度），
 *    如果有河流信号，采用河流色到深海色的渐变混合，避免硬性截断的视觉断裂感。
 */
public class SimpleHydrologyV29 {

    static int MAP_SIZE = 1024;
    static int ITERATIONS = 200;
    static int DROPS_PER_ITER = 5000;
    static float LRATE = 0.1f;
    static float GRAVITY = 1.5f;
    static float MOMENTUM = 0.15f; 
    static float EVAP = 0.001f;
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 6.0f; 
    static float MIN_VOL = 0.01f;
    static float MAX_AGE = 500;
    static float MAXDIFF = 0.01f;
    static float SETTLING = 0.6f;
    static float SEA_LEVEL = 0.35f;

    static int VIEW_SIZE_A = 1500;
    static int VIEW_SIZE_B = 4000;

    // 河口延伸参数
    static int DELTA_STEPS = 5;
    static float SHALLOW_DEPTH = 0.02f; // 浅海渐变带的深度范围

    final int w, h;
    final float[][] height;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my, mxTrack, myTrack;
    final StandalonePreview terrain;
    final long seed;
    final Random rng;
    final int viewSize;

    public SimpleHydrologyV29(int seed, int viewSize) {
        this.seed = seed;
        this.viewSize = viewSize;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 9999);
        this.w = MAP_SIZE;
        this.h = MAP_SIZE;
        this.height = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];
        initHeightmap();
    }

    void initHeightmap() {
        float scale = (float)viewSize / Math.max(w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float wx = (x - w/2f) * scale;
                float wz = (y - h/2f) * scale;
                height[y][x] = terrain.computeHeightWithOcean(wx, wz, terrain.computeHeightPure(wx, wz));
            }
    }

    float getH(int x, int y) {
        if (x < 0 || x >= w || y < 0 || y >= h) return 0;
        return height[y][x];
    }

    float getSlope(int ix, int iy) {
        float gx = getH(ix + 1, iy) - getH(ix - 1, iy);
        float gy = getH(ix, iy + 1) - getH(ix, iy - 1);
        return (float)Math.sqrt(gx*gx + gy*gy);
    }

    float[] normal(int ix, int iy) {
        float gx = getH(ix + 1, iy) - getH(ix - 1, iy);
        float gy = getH(ix, iy + 1) - getH(ix, iy - 1);
        
        float localSlope = (float)Math.sqrt(gx*gx + gy*gy);
        
        float dRight = ix + 1 < w ? erf(0.4f * discharge[iy][ix + 1]) : 0;
        float dLeft  = ix - 1 >= 0 ? erf(0.4f * discharge[iy][ix - 1]) : 0;
        float dDown  = iy + 1 < h ? erf(0.4f * discharge[iy + 1][ix]) : 0;
        float dUp    = iy - 1 >= 0 ? erf(0.4f * discharge[iy - 1][ix]) : 0;
        
        float attractionBase = 0.005f;
        float attractionFactor = attractionBase * (1.0f + 5.0f * Math.max(0, 1.0f - localSlope / 0.02f));
        
        gx -= (dRight - dLeft) * attractionFactor;
        gy -= (dDown - dUp) * attractionFactor;

        float len = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
        return new float[]{-gx/len, -gy/len};
    }

    void cascade(int ix, int iy) {
        if (ix < 1 || ix >= w - 1 || iy < 1 || iy >= h - 1) return;
        float h0 = height[iy][ix];
        float totalOut = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                float h1 = height[iy + dy][ix + dx];
                float diff = h0 - h1;
                if (Math.abs(diff) <= MAXDIFF) continue;
                float maxdiff = SETTLING * diff;
                height[iy + dy][ix + dx] += maxdiff;
                totalOut += maxdiff;
            }
        height[iy][ix] -= totalOut;
    }

    boolean descend(float[] pos, float[] vel, float[] volSedAge) {
        int ix = (int)pos[0], iy = (int)pos[1];
        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return false;

        float vol = volSedAge[0], age = volSedAge[1], sediment = volSedAge[2];
        
        if (age > MAX_AGE || vol < MIN_VOL) {
            height[iy][ix] += sediment;
            return false;
        }

        // --- V29 修复：河口延伸 (Delta Extension) ---
        // 当水滴进入海洋后，不立即消失，而是继续惯性运动，
        // 水量和含沙量逐步衰减至零。这模拟了河流入海后的能量扩散过程。
        if (height[iy][ix] < SEA_LEVEL) {
            // 记录入海第一步
            int seaStep = (int)(age - volSedAge[1] + 1); // 入海步数计数器
            
            for (int step = 0; step < DELTA_STEPS; step++) {
                if (vol < MIN_VOL) break;
                
                // 逐步衰减：每步剩余 60%，经过5步后几乎完全消散
                float decay = 0.6f;
                vol *= decay;
                sediment *= decay;
                
                // 惯性继续前进（只靠动量，不靠重力）
                float vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
                if (vlen > 0) {
                    vel[0] = (vel[0]/vlen) * 1.5f;
                    vel[1] = (vel[1]/vlen) * 1.5f;
                }
                
                pos[0] += vel[0];
                pos[1] += vel[1];
                
                int nx = (int)pos[0], ny = (int)pos[1];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) break;
                
                // 在海里也会标记流量（浅海过渡区的染色依据）
                dischargeTrack[ny][nx] += vol;
                
                // 沉积物分散到海底，但不允许造陆
                float sedPerCell = sediment / 9.0f;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int sx = nx + dx;
                        int sy = ny + dy;
                        if (sx >= 0 && sx < w && sy >= 0 && sy < h) {
                            if (height[sy][sx] < SEA_LEVEL) {
                                height[sy][sx] = Math.min(SEA_LEVEL - 0.001f, height[sy][sx] + sedPerCell);
                            }
                        }
                    }
                }
            }
            return false; // 水滴消失
        }

        float[] n = normal(ix, iy);
        vel[0] += GRAVITY * n[0] / vol;
        vel[1] += GRAVITY * n[1] / vol;

        float mdx = mx[iy][ix], mdy = my[iy][ix];
        float mlen = (float)Math.sqrt(mdx*mdx + mdy*mdy);
        float vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (mlen > 0 && vlen > 0) {
            float dot = (vel[0]*mdx + vel[1]*mdy) / (vlen * mlen);
            if (dot > 0) {
                float factor = MOMENTUM * dot / (vol + discharge[iy][ix] + 1f);
                vel[0] += factor * mdx; 
                vel[1] += factor * mdy;
            }
        }

        vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (vlen > 0) {
            vel[0] = (vel[0] / vlen) * (float)Math.sqrt(2.0f);
            vel[1] = (vel[1] / vlen) * (float)Math.sqrt(2.0f);
        }

        pos[0] += vel[0]; pos[1] += vel[1];
        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) return false;

        dischargeTrack[iy][ix] += vol;
        mxTrack[iy][ix] += vol * vel[0]; myTrack[iy][ix] += vol * vel[1];

        float h2;
        int nx = (int)pos[0], ny = (int)pos[1];
        if (nx >= w-1 || ny >= h-1) h2 = height[iy][ix] - 0.002f;
        else {
            float dx = pos[0] - nx, dy = pos[1] - ny;
            h2 = height[ny][nx]*(1-dx)*(1-dy) + height[ny][nx+1]*dx*(1-dy)
               + height[ny+1][nx]*(1-dx)*dy + height[ny+1][nx+1]*dx*dy;
        }

        float slope = Math.max(0, height[iy][ix] - h2);
        float nodeDischarge = erf(0.4f * discharge[iy][ix]);
        float c_eq = (1.0f + ENTRAINMENT * nodeDischarge) * slope;
        
        float cdiff = c_eq - sediment;
        float currentDeposition = DEPOSITION;
        
        if (cdiff < 0) {
            if (slope < 0.015f) { 
                float flatFactor = 1.0f - slope / 0.015f; 
                float lowFlowFactor = Math.max(0, 1.0f - nodeDischarge / 0.5f);
                float reduction = 1.0f - 0.95f * (flatFactor * lowFlowFactor); 
                currentDeposition *= reduction;
            }
        }

        sediment += currentDeposition * cdiff;
        height[iy][ix] -= currentDeposition * cdiff;
        
        sediment = Math.max(-10.0f, Math.min(10.0f, sediment));
        sediment /= (1.0f - EVAP); vol *= (1.0f - EVAP);
        
        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) { vol = 0; return false; }
        cascade(ix, iy);
        volSedAge[0] = vol; volSedAge[1] = age + 1; volSedAge[2] = sediment;
        return true;
    }

    public void simulate() {
        for (int iter = 0; iter < ITERATIONS; iter++) {
            for (int y = 0; y < h; y++) {
                Arrays.fill(dischargeTrack[y], 0);
                Arrays.fill(mxTrack[y], 0); Arrays.fill(myTrack[y], 0);
            }
            
            int activeDrops = 0;
            while (activeDrops < DROPS_PER_ITER) {
                float px = rng.nextFloat() * w, py = rng.nextFloat() * h;
                float hVal = getH((int)px, (int)py);
                if (hVal < SEA_LEVEL) continue;
                
                float localSlope = getSlope((int)px, (int)py);
                float spawnChance = 0.1f + 0.9f * Math.min(1.0f, localSlope / 0.02f);
                if (rng.nextFloat() > spawnChance) continue; 
                
                activeDrops++;
                float[] pos = {px, py}, vel = {0, 0}, vsa = {1.0f, 0, 0};
                while (descend(pos, vel, vsa)) {}
            }
            
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    discharge[y][x] = (1f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                    mx[y][x] = (1f - LRATE) * mx[y][x] + LRATE * mxTrack[y][x];
                    my[y][x] = (1f - LRATE) * my[y][x] + LRATE * myTrack[y][x];
                }
        }
    }

    void renderOnto(BufferedImage img, int offsetX, int offsetY) {
        List<Float> allD = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (discharge[y][x] > 0) allD.add(discharge[y][x]);
        Collections.sort(allD);
        int n = allD.size();
        
        float p88 = n > 0 ? allD.get((int)(n * 0.88f)) : 0;
        float p95 = n > 0 ? allD.get((int)(n * 0.95f)) : 0;
        float p99 = n > 0 ? allD.get((int)(n * 0.99f)) : 0;
        float maxD = n > 0 ? allD.get(n - 1) : 0;

        System.out.println("  VIEW_SIZE="+viewSize+" maxD="+fmt1(maxD)+" p88="+fmt1(p88)+" p95="+fmt1(p95)+" p99="+fmt1(p99));

        float[][] blurD = new float[h][w];
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                float sum = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        sum += discharge[y+dy][x+dx];
                blurD[y][x] = sum / 9f;
            }

        // 上半部分：地形 + 河流
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                float hVal = height[py][px];
                float d = blurD[py][px];
                int col1;
                
                if (hVal < SEA_LEVEL) {
                    // --- V29 改进：海岸渐变渲染 ---
                    float oceanDepth = SEA_LEVEL - hVal;
                    if (oceanDepth < SHALLOW_DEPTH && d > p88) {
                        // 浅海地带且有河流信号：绘制河口混合渐变
                        float riverStrength = (float)Math.log1p(d - p88) / (float)Math.log1p(maxD - p88 + 1);
                        riverStrength = Math.max(0, Math.min(1, riverStrength));
                        // 越靠近海岸线（深度越浅），河流色占比越高
                        float blendFactor = riverStrength * (1.0f - oceanDepth / SHALLOW_DEPTH);
                        
                        // 河流色（浅蓝）
                        int rR, gR, bR;
                        if (d > p99) { rR=30; gR=100; bR=200; }
                        else { rR=20; gR=60; bR=140; }
                        
                        // 海洋色（深海蓝）
                        int rO=0x1a, gO=0x3a, bO=0x7a;
                        
                        int r = (int)(rO * (1-blendFactor) + rR * blendFactor);
                        int g = (int)(gO * (1-blendFactor) + gR * blendFactor);
                        int b = (int)(bO * (1-blendFactor) + bR * blendFactor);
                        col1 = rgb(r, g, b);
                    } else {
                        col1 = 0x1a3a7a; // 深海纯净蓝色
                    }
                } else {
                    col1 = terrainColor(hVal);
                    if (d > p88) {
                        float t = (float)Math.log1p(d - p88) / (float)Math.log1p(maxD - p88 + 1);
                        t = Math.max(0, Math.min(1, t));
                        if (d > p99) col1 = rgb((int)(10+t*20), (int)(40+t*60), (int)(150+t*105)); 
                        else col1 = rgb((int)(5+t*15), (int)(25+t*35), (int)(80+t*80)); 
                    }
                }
                img.setRGB(offsetX + px, offsetY + py, col1);
            }
        }

        // 下半部分：骨架图
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                float hVal = height[py][px];
                float rawD = discharge[py][px];
                int col2 = 0x050508; 
                
                if (hVal < SEA_LEVEL) {
                    // 骨架图也做浅海渐变
                    float oceanDepth = SEA_LEVEL - hVal;
                    if (oceanDepth < SHALLOW_DEPTH && rawD > p88) {
                        float riverStrength = (float)Math.log1p(rawD - p88) / (float)Math.log1p(maxD - p88 + 1);
                        riverStrength = Math.max(0, Math.min(1, riverStrength));
                        float blendFactor = riverStrength * (1.0f - oceanDepth / SHALLOW_DEPTH);
                        int r = (int)(10 * (1-blendFactor) + 30 * blendFactor);
                        int g = (int)(21 * (1-blendFactor) + 80 * blendFactor);
                        int b = (int)(32 * (1-blendFactor) + 180 * blendFactor);
                        col2 = rgb(r, g, b);
                    } else {
                        col2 = 0x0a1520;
                    }
                } else if (rawD > p88) {
                    float t = (float)Math.log1p(rawD - p88) / (float)Math.log1p(maxD - p88 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (rawD > p99) col2 = rgb((int)(20+t*40), (int)(80+t*100), (int)(200+t*55));
                    else col2 = rgb((int)(10+t*20), (int)(30+t*50), (int)(100+t*80));
                }
                img.setRGB(offsetX + px, offsetY + h + py, col2);
            }
        }

        // 标签
        java.awt.Graphics g = img.getGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("Monospaced", java.awt.Font.BOLD, 18));
        g.drawString("VIEW_SIZE = " + viewSize, offsetX + 15, offsetY + 25);
        g.drawString("地形 + 河流全貌", offsetX + 15, offsetY + h - 15);
        g.drawString("水系骨架结构", offsetX + 15, offsetY + h*2 - 15);
        g.dispose();
    }

    int terrainColor(float h) {
        if (h < 0.35f) return 0x55AA55;
        if (h < 0.45f) return 0x66BB66;
        if (h < 0.55f) return 0x449944;
        if (h < 0.65f) return 0x88AA66;
        if (h < 0.75f) return 0xAA9966;
        if (h < 0.85f) return 0xBBAA88;
        return 0xDDDDCC;
    }

    static float erf(float x) {
        float a = Math.abs(x);
        float t = 1.0f / (1.0f + 0.47047f * a);
        float result = 1.0f - t*(0.3480242f + t*(-0.0958798f + t*0.7478556f))*(float)Math.exp(-a*a);
        return x >= 0 ? result : -result;
    }

    static int rgb(int r, int g, int b) { return (r<<16)|(g<<8)|b; }
    static String fmt1(float v) { return String.format("%.1f", v); }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        System.out.println("=== V29 修复入海口断头河：河口延伸 + 海岸渐变 ===");

        BufferedImage img = new BufferedImage(MAP_SIZE * 2, MAP_SIZE * 2, BufferedImage.TYPE_INT_RGB);

        System.out.println("渲染左侧 VIEW_SIZE=" + VIEW_SIZE_A);
        SimpleHydrologyV29 simA = new SimpleHydrologyV29(seed, VIEW_SIZE_A);
        simA.simulate();
        simA.renderOnto(img, 0, 0);

        System.out.println("渲染右侧 VIEW_SIZE=" + VIEW_SIZE_B);
        SimpleHydrologyV29 simB = new SimpleHydrologyV29(seed, VIEW_SIZE_B);
        simB.simulate();
        simB.renderOnto(img, MAP_SIZE, 0);

        // 分隔线
        java.awt.Graphics g = img.getGraphics();
        g.setColor(new Color(100, 100, 100));
        g.drawLine(MAP_SIZE, 0, MAP_SIZE, MAP_SIZE * 2);
        g.drawLine(0, MAP_SIZE, MAP_SIZE * 2, MAP_SIZE);
        g.dispose();

        String outPath = "output/v29_s" + seed + ".png";
        ImageIO.write(img, "png", new File(outPath));
        System.out.println("Saved: " + outPath);
    }
}
