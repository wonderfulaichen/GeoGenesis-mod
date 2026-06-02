package com.erosiontest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V28: 修复海岸线“锯齿状伪大陆”与“入海口溢出”光晕问题
 * 
 * 改进点：
 * 1. 阻止造陆运动：当水流将泥沙带入海洋时，不再允许泥沙将海洋像素垫高到海平面（SEA_LEVEL）之上。之前泥沙在入海口单点堆积，意外造出了锯齿状的半岛，导致水流在海面上继续延伸。
 * 2. 严格的海洋遮罩：在渲染阶段，对低于 SEA_LEVEL 的像素严格应用海洋底色，不再让河流的 blur（模糊）效果“溢出”污染海洋颜色，确保海岸线平滑清晰。
 */
public class SimpleHydrologyV28 {

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

    public SimpleHydrologyV28(int seed, int viewSize) {
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
        
        // 如果水量太小或者寿命到了，就地沉积
        if (age > MAX_AGE || vol < MIN_VOL) {
            height[iy][ix] += sediment;
            return false;
        }

        // --- V28 关键修复 1：阻止造陆运动 ---
        // 如果水滴进入了海洋，让泥沙平滑分散到周围的海里，并且绝对不允许泥沙把海底垫高超过海平面！
        // 这彻底消灭了因为“单点堆沙”而造出的锯齿状伪大陆。
        if (height[iy][ix] < SEA_LEVEL) {
            float sedPerCell = sediment / 9.0f;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = ix + dx;
                    int ny = iy + dy;
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        if (height[ny][nx] < SEA_LEVEL) {
                            // 海底可以升高，但永远不能突破海平面 (SEA_LEVEL - 0.001f)
                            height[ny][nx] = Math.min(SEA_LEVEL - 0.001f, height[ny][nx] + sedPerCell);
                        }
                    }
                }
            }
            return false; // 水滴在海里消失
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
                
                // --- V28 关键修复 2：严格的海洋遮罩 ---
                if (hVal < SEA_LEVEL) {
                    // 如果是海洋，强制渲染纯净的海洋蓝，绝不允许河流的光晕溢出进来
                    col1 = 0x1a3a7a; 
                } else {
                    // 只有陆地才允许渲染河流
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
                    // 海洋区域保持干净
                    col2 = 0x0a1520;
                } else if (rawD > p88) {
                    // 只有陆地区域渲染骨架
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
        System.out.println("=== V28 修复海岸线锯齿造陆与渲染溢出 ===");

        BufferedImage img = new BufferedImage(MAP_SIZE * 2, MAP_SIZE * 2, BufferedImage.TYPE_INT_RGB);

        System.out.println("渲染左侧 VIEW_SIZE=" + VIEW_SIZE_A);
        SimpleHydrologyV28 simA = new SimpleHydrologyV28(seed, VIEW_SIZE_A);
        simA.simulate();
        simA.renderOnto(img, 0, 0);

        System.out.println("渲染右侧 VIEW_SIZE=" + VIEW_SIZE_B);
        SimpleHydrologyV28 simB = new SimpleHydrologyV28(seed, VIEW_SIZE_B);
        simB.simulate();
        simB.renderOnto(img, MAP_SIZE, 0);

        // 分隔线
        java.awt.Graphics g = img.getGraphics();
        g.setColor(new Color(100, 100, 100));
        g.drawLine(MAP_SIZE, 0, MAP_SIZE, MAP_SIZE * 2);
        g.drawLine(0, MAP_SIZE, MAP_SIZE * 2, MAP_SIZE);
        g.dispose();

        String outPath = "output/v28_s" + seed + ".png";
        ImageIO.write(img, "png", new File(outPath));
        System.out.println("Saved: " + outPath);
    }
}
