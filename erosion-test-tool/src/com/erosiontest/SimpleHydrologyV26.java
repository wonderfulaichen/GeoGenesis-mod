package com.erosiontest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V26: 解决平缓地形水流分散（辫状河/毛细血管）问题
 * 
 * 改进点：
 * 1. 汇聚引力 (Channel Attraction)：在计算地形法线时，引入相邻网格的 discharge 差异，使得水滴倾向于流向已有水流的地方，从而促使支流合并。
 * 2. 抑制沉积 (Deposition Reduction)：在平缓地形（坡度极小）且水流较弱时，大幅降低沉积率，防止泥沙堆积阻挡自身河道而被迫分叉。
 */
public class SimpleHydrologyV26 {

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

    final int w, h;
    final float[][] height;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my, mxTrack, myTrack;
    final StandalonePreview terrain;
    final long seed;
    final Random rng;
    final int viewSize;

    public SimpleHydrologyV26(int seed, int viewSize) {
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

    float[] normal(int ix, int iy) {
        float gx = getH(ix + 1, iy) - getH(ix - 1, iy);
        float gy = getH(ix, iy + 1) - getH(ix, iy - 1);
        
        // 汇聚引力机制 (Channel Attraction)
        // 计算相邻网格的水流量梯度，使得水滴倾向于流向已有水流的地方
        float dRight = ix + 1 < w ? erf(0.4f * discharge[iy][ix + 1]) : 0;
        float dLeft  = ix - 1 >= 0 ? erf(0.4f * discharge[iy][ix - 1]) : 0;
        float dDown  = iy + 1 < h ? erf(0.4f * discharge[iy + 1][ix]) : 0;
        float dUp    = iy - 1 >= 0 ? erf(0.4f * discharge[iy - 1][ix]) : 0;
        
        // 引力系数：使得水流在平缓地形能被附近的干流吸引
        float ATTRACTION = 0.005f; 
        gx -= (dRight - dLeft) * ATTRACTION;
        gy -= (dDown - dUp) * ATTRACTION;

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
        if (age > MAX_AGE || vol < MIN_VOL || height[iy][ix] < SEA_LEVEL) {
            height[iy][ix] += sediment;
            return false;
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
        
        // 辫状河（分散水线）抑制机制：
        // 在平缓地形（slope小）且水流较小（nodeDischarge小）时，如果发生沉积（cdiff < 0），
        // 大幅降低沉积率。防止泥沙在平地堆积阻挡河道，导致水流被迫分叉。
        if (cdiff < 0) {
            if (slope >= 0 && slope < 0.01f) {
                float flatFactor = 1.0f - slope / 0.01f; // 0.01 是平缓阈值
                float lowFlowFactor = Math.max(0, 1.0f - nodeDischarge / 0.4f); // 0.4 是小水流阈值
                float reduction = 1.0f - 0.9f * (flatFactor * lowFlowFactor); // 最多降低 90% 沉积率
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
            for (int i = 0; i < DROPS_PER_ITER; i++) {
                float px = rng.nextFloat() * w, py = rng.nextFloat() * h;
                if (getH((int)px, (int)py) < SEA_LEVEL) continue;
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
        
        float p85 = n > 0 ? allD.get((int)(n * 0.85f)) : 0;
        float p95 = n > 0 ? allD.get((int)(n * 0.95f)) : 0;
        float p99 = n > 0 ? allD.get((int)(n * 0.99f)) : 0;
        float p995 = n > 0 ? allD.get((int)(n * 0.995f)) : 0;
        float maxD = n > 0 ? allD.get(n - 1) : 0;

        System.out.println("  VIEW_SIZE="+viewSize+" maxD="+fmt1(maxD)+" p85="+fmt1(p85)+" p95="+fmt1(p95)+" p99="+fmt1(p99));

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
                if (hVal < SEA_LEVEL) col1 = 0x1a3a7a;
                else col1 = terrainColor(hVal);
                
                if (d > p85) {
                    float t = (float)Math.log1p(d - p85) / (float)Math.log1p(maxD - p85 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (d > p99) col1 = rgb((int)(10+t*20), (int)(40+t*60), (int)(150+t*105)); 
                    else col1 = rgb((int)(5+t*15), (int)(25+t*35), (int)(80+t*80)); 
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
                
                if (rawD > p85) {
                    float t = (float)Math.log1p(rawD - p85) / (float)Math.log1p(maxD - p85 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (rawD > p99) col2 = rgb((int)(20+t*40), (int)(80+t*100), (int)(200+t*55));
                    else col2 = rgb((int)(10+t*20), (int)(30+t*50), (int)(100+t*80));
                } else if (hVal < SEA_LEVEL) col2 = 0x0a1520;
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
        System.out.println("=== V26 解决平缓地形水流分散（辫状河） ===");

        BufferedImage img = new BufferedImage(MAP_SIZE * 2, MAP_SIZE * 2, BufferedImage.TYPE_INT_RGB);

        System.out.println("渲染左侧 VIEW_SIZE=" + VIEW_SIZE_A);
        SimpleHydrologyV26 simA = new SimpleHydrologyV26(seed, VIEW_SIZE_A);
        simA.simulate();
        simA.renderOnto(img, 0, 0);

        System.out.println("渲染右侧 VIEW_SIZE=" + VIEW_SIZE_B);
        SimpleHydrologyV26 simB = new SimpleHydrologyV26(seed, VIEW_SIZE_B);
        simB.simulate();
        simB.renderOnto(img, MAP_SIZE, 0);

        // 分隔线
        java.awt.Graphics g = img.getGraphics();
        g.setColor(new Color(100, 100, 100));
        g.drawLine(MAP_SIZE, 0, MAP_SIZE, MAP_SIZE * 2);
        g.drawLine(0, MAP_SIZE, MAP_SIZE * 2, MAP_SIZE);
        g.dispose();

        String outPath = "output/v26_s" + seed + ".png";
        ImageIO.write(img, "png", new File(outPath));
        System.out.println("Saved: " + outPath);
    }
}
