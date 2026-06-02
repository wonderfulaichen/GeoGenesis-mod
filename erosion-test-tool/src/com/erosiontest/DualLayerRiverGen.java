package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

public class DualLayerRiverGen {

    static int MAP_SIZE = 512;
    static int VIEW_SIZE = 6000;
    static int CELL_SIZE = 64;
    static float FLOW_ACCUM_MIN = 5f;
    static float MAX_GRADIENT = 0.0005f;
    static float WIDTH_EXP = 0.4f;
    static float WIDTH_SCALE = 10f;
    static float WIDTH_CAP = 80f;
    static float DEPTH_SCALE = 0.02f;
    static float DEPTH_CAP = 0.15f;
    static float VALLEY_RATIO = 2.5f;
    static float BANK_RATIO = 1.5f;
    static float MIN_RIVER_WIDTH = 8f;
    static float CORRIDOR_RATIO = 1.6f;

    static int ITERATIONS = 80;
    static int DROPS_PER_ITER = 2500;
    static float LRATE = 0.1f;
    static float GRAVITY = 1.0f;
    static float MOMENTUM = 0.3f;
    static float EVAP = 0.001f;
    static float DEPOSITION = 0.06f;
    static float ENTRAINMENT = 2.5f;
    static float MIN_VOL = 0.01f;
    static float MAX_AGE = 500;
    static float SEA_LEVEL = 0.35f;

    static float BASE_RADIUS_PX = 0.4f;
    static float WIDTH_TO_RADIUS_PX = 0.35f;
    static float DISCHARGE_TO_RADIUS_PX = 0.12f;
    static float MAX_RADIUS_PX = 10.0f;

    static final int SEA_LEVEL_Y = 63;
    static final int MIN_Y = -64;
    static final int MAX_Y = 256;
    static final float SEA_NORM = (float) (SEA_LEVEL_Y - MIN_Y) / (MAX_Y - MIN_Y);

    static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};

    final int w, h;
    final float mapScale;

    final StandalonePreview terrain;
    final long seed;
    final Random rng;
    final ImprovedNoise jitterX, jitterZ;

    final float[][] height;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my;
    final float[][] mxTrack, myTrack;
    final float[][] targetWidth;
    final float[][] targetWater;
    final float[][] targetBed;
    final boolean[][] inCorridor;
    final boolean[][] centerline;
    final int[] corridorX;
    final int[] corridorY;
    int corridorCount = 0;

    final Map<Long, CellData> cellCache = new HashMap<>();
    final Map<Long, Float> smoothHeightCache = new HashMap<>();
    final Map<Long, Float> accumMap = new HashMap<>();
    final List<RiverPath> allPaths = new ArrayList<>();
    boolean tracingDone = false;

    public DualLayerRiverGen(int seed) {
        this.seed = seed;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 7777);
        this.jitterX = new ImprovedNoise(rng.nextLong());
        this.jitterZ = new ImprovedNoise(rng.nextLong());
        this.w = MAP_SIZE;
        this.h = MAP_SIZE;
        this.mapScale = (float) VIEW_SIZE / MAP_SIZE;
        this.height = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];
        this.targetWidth = new float[h][w];
        this.targetWater = new float[h][w];
        this.targetBed = new float[h][w];
        this.inCorridor = new boolean[h][w];
        this.centerline = new boolean[h][w];
        this.corridorX = new int[w * h / 2];
        this.corridorY = new int[w * h / 2];
    }

    float worldX(int px) {
        return (px - w / 2f) * mapScale;
    }

    float worldZ(int py) {
        return (py - h / 2f) * mapScale;
    }

    int mapX(float wx) {
        return (int) (wx / mapScale + w / 2f);
    }

    int mapY(float wz) {
        return (int) (wz / mapScale + h / 2f);
    }

    void initHeightmap() {
        for (int y = 0; y < h; y++) {
            float wz = worldZ(y);
            for (int x = 0; x < w; x++) {
                float wx = worldX(x);
                height[y][x] = terrain.computeHeightPure(wx, wz);
            }
        }
    }

    float cellCX(int ci, int cj) {
        float base = ci * CELL_SIZE + CELL_SIZE * 0.5f;
        float jx = (float) jitterX.noise(ci * 0.23, 0.0, cj * 0.23);
        return base + jx * CELL_SIZE * 0.25f;
    }

    float cellCZ(int ci, int cj) {
        float base = cj * CELL_SIZE + CELL_SIZE * 0.5f;
        float jz = (float) jitterZ.noise(ci * 0.23, 0.0, cj * 0.23);
        return base + jz * CELL_SIZE * 0.25f;
    }

    static long ck(int ci, int cj) {
        return ((long) ci << 32) | (cj & 0xFFFFFFFFL);
    }

    void buildCell(int ci, int cj) {
        long key = ck(ci, cj);
        if (cellCache.containsKey(key)) return;
        float wx = cellCX(ci, cj);
        float wz = cellCZ(ci, cj);
        cellCache.put(key, new CellData(ci, cj, wx, wz, -1));
    }

    float smoothHeight(int ci, int cj) {
        Long key = ck(ci, cj);
        Float sh = smoothHeightCache.get(key);
        if (sh != null) return sh;
        float wx = cellCX(ci, cj);
        float wz = cellCZ(ci, cj);
        return terrain.computeHeightPure(wx, wz);
    }

    void computeAllFlowDirs() {
        for (CellData cell : cellCache.values()) {
            long key = ck(cell.ci, cell.cj);
            if (smoothHeightCache.containsKey(key)) continue;
            float sum = terrain.computeHeightPure(cell.wx, cell.wz);
            int cnt = 1;
            for (int k = 0; k < 8; k++) {
                long nk = ck(cell.ci + DX[k], cell.cj + DZ[k]);
                CellData nb = cellCache.get(nk);
                if (nb != null) {
                    sum += terrain.computeHeightPure(nb.wx, nb.wz);
                    cnt++;
                }
            }
            smoothHeightCache.put(key, sum / cnt);
        }
        for (CellData cell : cellCache.values()) {
            if (cell.flowDir >= 0) continue;
            cell.flowDir = computeFlowDir(cell);
        }
    }

    int computeFlowDir(CellData cell) {
        float h = smoothHeight(cell.ci, cell.cj);
        int best = -1;
        float bestDrop = 0f;
        for (int k = 0; k < 8; k++) {
            int ni = cell.ci + DX[k], nj = cell.cj + DZ[k];
            if (!cellCache.containsKey(ck(ni, nj))) continue;
            float drop = (h - smoothHeight(ni, nj));
            if (drop > bestDrop) {
                bestDrop = drop;
                best = k;
            }
        }
        return best;
    }

    float computeAccum(int ci, int cj, Set<Long> visited) {
        long key = ck(ci, cj);
        if (visited.contains(key)) return 0;
        visited.add(key);
        float total = 1f;
        for (int k = 0; k < 8; k++) {
            int ui = ci + DX[k], uj = cj + DZ[k];
            long uk = ck(ui, uj);
            CellData up = cellCache.get(uk);
            if (up == null || up.flowDir < 0) continue;
            int fi = ui + DX[up.flowDir], fj = uj + DZ[up.flowDir];
            if (fi == ci && fj == cj) total += computeAccum(ui, uj, visited);
        }
        return total;
    }

    void traceAllRivers() {
        if (tracingDone) return;
        computeAllFlowDirs();

        accumMap.clear();
        for (CellData c : cellCache.values()) {
            if (c.flowDir >= 0) accumMap.put(ck(c.ci, c.cj), computeAccum(c.ci, c.cj, new HashSet<>()));
        }

        List<CellData> starts = new ArrayList<>();
        for (CellData c : cellCache.values()) {
            if (c.flowDir < 0) continue;
            float acc = accumMap.getOrDefault(ck(c.ci, c.cj), 0f);
            if (acc < FLOW_ACCUM_MIN) continue;
            float hh = terrain.computeHeightPure(c.wx, c.wz);
            if (hh < SEA_LEVEL + 0.05f) continue;

            boolean hasUp = false;
            for (int k = 0; k < 8; k++) {
                long uk = ck(c.ci + DX[k], c.cj + DZ[k]);
                CellData up = cellCache.get(uk);
                if (up == null || up.flowDir < 0) continue;
                int fi = up.ci + DX[up.flowDir], fj = up.cj + DZ[up.flowDir];
                if (fi == c.ci && fj == c.cj && accumMap.getOrDefault(uk, 0f) >= FLOW_ACCUM_MIN) {
                    hasUp = true;
                    break;
                }
            }
            if (!hasUp) starts.add(c);
        }

        starts.sort((a, b) -> Float.compare(accumMap.getOrDefault(ck(b.ci, b.cj), 0f),
            accumMap.getOrDefault(ck(a.ci, a.cj), 0f)));

        Set<Long> traced = new HashSet<>();
        for (CellData src : starts) {
            RiverPath path = traceRiverPath(src, traced);
            if (path != null) allPaths.add(path);
        }
        tracingDone = true;
    }

    RiverPath traceRiverPath(CellData src, Set<Long> traced) {
        List<RiverNode> nodes = new ArrayList<>();
        int curI = src.ci, curJ = src.cj;

        for (int step = 0; step < 500; step++) {
            long key = ck(curI, curJ);
            CellData cell = cellCache.get(key);
            if (cell == null) break;

            float hh = terrain.computeHeightPure(cell.wx, cell.wz);

            if (traced.contains(key)) {
                if (!nodes.isEmpty()) nodes.add(new RiverNode(cell.wx, cell.wz, hh, accumMap.getOrDefault(key, 1f)));
                break;
            }

            if (hh < SEA_LEVEL) {
                nodes.add(new RiverNode(cell.wx, cell.wz, hh, accumMap.getOrDefault(key, 1f)));
                traced.add(key);
                break;
            }

            if (!nodes.isEmpty()) {
                RiverNode last = nodes.get(nodes.size() - 1);
                float lastH = terrain.computeHeightPure(last.x, last.z);
                if (hh > lastH + 0.005f) break;
            }

            nodes.add(new RiverNode(cell.wx, cell.wz, hh, accumMap.getOrDefault(key, 1f)));
            traced.add(key);

            if (cell.flowDir < 0) break;
            curI = cell.ci + DX[cell.flowDir];
            curJ = cell.cj + DZ[cell.flowDir];
        }

        if (nodes.size() < 3) return null;

        int n = nodes.size();
        float[] accums = new float[n];
        for (int i = 0; i < n; i++) accums[i] = nodes.get(i).accum;

        float[] wls = new float[n];
        wls[n - 1] = Math.min(nodes.get(n - 1).h, SEA_LEVEL);
        for (int i = n - 2; i >= 0; i--) {
            float dist = (float) Math.hypot(nodes.get(i).x - nodes.get(i + 1).x, nodes.get(i).z - nodes.get(i + 1).z);
            wls[i] = Math.min(wls[i + 1] + MAX_GRADIENT * dist, nodes.get(i).h - 0.003f);
            wls[i] = Math.max(wls[i], SEA_LEVEL);
        }
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 1; i < n; i++) {
                float dist = (float) Math.hypot(nodes.get(i).x - nodes.get(i - 1).x, nodes.get(i).z - nodes.get(i - 1).z);
                float maxDrop = MAX_GRADIENT * dist;
                if (wls[i - 1] - wls[i] > maxDrop) wls[i] = Math.max(wls[i - 1] - maxDrop, SEA_LEVEL);
            }
        }

        List<RiverSeg> segs = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            float ah = wls[i], bh = wls[i + 1];
            if (bh > ah) bh = ah - 0.002f;
            float aw = riverWidth(accums[i]);
            float bw = riverWidth(accums[i + 1]);
            float ad = riverDepth(accums[i]);
            float bd = riverDepth(accums[i + 1]);
            segs.add(new RiverSeg(nodes.get(i).x, nodes.get(i).z, nodes.get(i + 1).x, nodes.get(i + 1).z, ah, bh, aw, bw, ah - ad, bh - bd));
        }
        return new RiverPath(segs);
    }

    float riverWidth(float a) {
        return Math.min(2f + (float) Math.pow(a, WIDTH_EXP) * WIDTH_SCALE, WIDTH_CAP);
    }

    float riverDepth(float a) {
        return Math.min(0.005f + (float) Math.log1p(a) * DEPTH_SCALE, DEPTH_CAP);
    }

    void buildCorridor() {
        traceAllRivers();

        for (int y = 0; y < h; y++) {
            Arrays.fill(targetWidth[y], 0);
            Arrays.fill(targetWater[y], 0);
            Arrays.fill(targetBed[y], 0);
            Arrays.fill(inCorridor[y], false);
            Arrays.fill(centerline[y], false);
        }
        corridorCount = 0;

        float stepWorld = mapScale;
        for (RiverPath path : allPaths) {
            for (RiverSeg s : path.segs) {
                float dx = s.x2 - s.x1, dz = s.z2 - s.z1;
                float len = (float) Math.hypot(dx, dz);
                int steps = Math.max(2, (int) Math.ceil(len / stepWorld));
                for (int i = 0; i <= steps; i++) {
                    float t = i / (float) steps;
                    float wx = s.x1 + t * dx;
                    float wz = s.z1 + t * dz;
                    int px = mapX(wx);
                    int py = mapY(wz);
                    if (px < 0 || px >= w || py < 0 || py >= h) continue;

                    float widthW = s.aw + t * (s.bw - s.aw);
                    if (widthW < MIN_RIVER_WIDTH) continue;
                    float waterH = s.ah + t * (s.bh - s.ah);
                    float bedH = s.abed + t * (s.bbed - s.abed);
                    centerline[py][px] = true;
                    float valleyW = widthW * CORRIDOR_RATIO;
                    int rpx = Math.max(1, (int) Math.ceil(valleyW / mapScale));
                    int rpx2 = rpx * rpx;

                    for (int oy = -rpx; oy <= rpx; oy++) {
                        int yy = py + oy;
                        if (yy < 0 || yy >= h) continue;
                        for (int ox = -rpx; ox <= rpx; ox++) {
                            int xx = px + ox;
                            if (xx < 0 || xx >= w) continue;
                            if (ox * ox + oy * oy > rpx2) continue;
                            if (widthW > targetWidth[yy][xx]) {
                                targetWidth[yy][xx] = widthW;
                                targetWater[yy][xx] = waterH;
                                targetBed[yy][xx] = bedH;
                            }
                            if (!inCorridor[yy][xx]) {
                                inCorridor[yy][xx] = true;
                                if (corridorCount < corridorX.length) {
                                    corridorX[corridorCount] = xx;
                                    corridorY[corridorCount] = yy;
                                    corridorCount++;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    float getH(int x, int y) {
        if (x < 0 || x >= w || y < 0 || y >= h) return 0;
        return height[y][x];
    }

    float[] volumeNormal(float fx, float fy, float radiusPx) {
        int ix = (int) fx, iy = (int) fy;
        int r = Math.max(1, (int) radiusPx);
        float gx = getH(ix + r, iy) - getH(ix - r, iy);
        float gy = getH(ix, iy + r) - getH(ix, iy - r);
        gx /= (2 * r);
        gy /= (2 * r);
        float len = (float) Math.sqrt(gx * gx + gy * gy + 0.0001f);
        return new float[]{-gx / len, -gy / len};
    }

    float sampleVolumeHeight(float fx, float fy, float radiusPx) {
        int cx = (int) fx, cy = (int) fy;
        int r = (int) Math.ceil(radiusPx);
        if (r <= 1) return getH(cx, cy);
        float sum = 0;
        float weightSum = 0;
        float rad2 = radiusPx * radiusPx;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float dist2 = dx * dx + dy * dy;
                if (dist2 > rad2) continue;
                int nx = cx + dx, ny = cy + dy;
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                float wgt = 1.0f - dist2 / rad2;
                sum += height[ny][nx] * wgt;
                weightSum += wgt;
            }
        }
        return weightSum > 0 ? sum / weightSum : getH(cx, cy);
    }

    float brushWeight(float dist, float radius) {
        float t = dist / radius;
        if (t >= 1f) return 0f;
        return 1.0f - t * t * (3 - 2 * t);
    }

    void applyBrushHeightDelta(int cx, int cy, float radiusPx, float delta) {
        int r = (int) Math.ceil(radiusPx);
        if (r <= 1) {
            height[cy][cx] += delta;
            return;
        }

        float weightSum = 0;
        float rad2 = radiusPx * radiusPx;
        float[] wrow = new float[2 * r + 1];
        for (int dy = -r; dy <= r; dy++) {
            int yy = cy + dy;
            if (yy < 0 || yy >= h) continue;
            Arrays.fill(wrow, 0);
            for (int dx = -r; dx <= r; dx++) {
                int xx = cx + dx;
                if (xx < 0 || xx >= w) continue;
                float dist2 = dx * dx + dy * dy;
                if (dist2 > rad2) continue;
                float wgt = brushWeight((float) Math.sqrt(dist2), radiusPx);
                wrow[dx + r] = wgt;
                weightSum += wgt;
            }
        }
        if (weightSum <= 0) return;

        for (int dy = -r; dy <= r; dy++) {
            int yy = cy + dy;
            if (yy < 0 || yy >= h) continue;
            for (int dx = -r; dx <= r; dx++) {
                int xx = cx + dx;
                if (xx < 0 || xx >= w) continue;
                float dist2 = dx * dx + dy * dy;
                if (dist2 > rad2) continue;
                float wgt = brushWeight((float) Math.sqrt(dist2), radiusPx);
                float localDelta = delta * (wgt / weightSum);
                height[yy][xx] += localDelta;
            }
        }
    }

    boolean descend(float[] pos, float[] vel, float[] vsa) {
        int ix = (int) pos[0], iy = (int) pos[1];
        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return false;

        float vol = vsa[0];
        float age = vsa[1];
        float sediment = vsa[2];

        if (age > MAX_AGE || vol < MIN_VOL) {
            if (inCorridor[iy][ix]) {
                float widthW = targetWidth[iy][ix];
                float radPx = Math.min(MAX_RADIUS_PX, BASE_RADIUS_PX + (widthW / mapScale) * WIDTH_TO_RADIUS_PX);
                applyBrushHeightDelta(ix, iy, radPx, sediment);
            } else {
                height[iy][ix] += sediment;
            }
            return false;
        }

        float widthW = targetWidth[iy][ix];
        if (widthW <= 0) {
            vol *= 0.6f;
            vsa[0] = vol;
            vsa[1] = age + 1;
            return vol > MIN_VOL;
        }

        if (height[iy][ix] < SEA_LEVEL) {
            float radPx = Math.min(MAX_RADIUS_PX, BASE_RADIUS_PX + (widthW / mapScale) * WIDTH_TO_RADIUS_PX);
            applyBrushHeightDelta(ix, iy, radPx, sediment);
            return false;
        }

        float localDischarge = discharge[iy][ix];
        float radPx = BASE_RADIUS_PX + (widthW / mapScale) * WIDTH_TO_RADIUS_PX + (float) Math.sqrt(localDischarge + vol) * DISCHARGE_TO_RADIUS_PX;
        radPx = Math.min(radPx, MAX_RADIUS_PX);

        float[] n = volumeNormal(pos[0], pos[1], radPx);
        vel[0] += GRAVITY * n[0] / vol;
        vel[1] += GRAVITY * n[1] / vol;

        float mdx = mx[iy][ix], mdy = my[iy][ix];
        float mlen = (float) Math.sqrt(mdx * mdx + mdy * mdy);
        float vlen = (float) Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1]);
        if (mlen > 0 && vlen > 0) {
            float dot = (vel[0] * mdx + vel[1] * mdy) / (vlen * mlen);
            float factor = MOMENTUM * dot / (vol + localDischarge + 1f);
            vel[0] += factor * mdx;
            vel[1] += factor * mdy;
        }

        vlen = (float) Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1]);
        if (vlen > 0) {
            float step = Math.max(1.414f, radPx * 0.45f);
            vel[0] = (vel[0] / vlen) * step;
            vel[1] = (vel[1] / vlen) * step;
        }

        float nx = pos[0] + vel[0];
        float ny = pos[1] + vel[1];
        if (nx < 0 || nx >= w || ny < 0 || ny >= h) return false;

        pos[0] = nx;
        pos[1] = ny;

        int dix = (int) nx, diy = (int) ny;
        dischargeTrack[diy][dix] += vol;
        mxTrack[diy][dix] += vol * vel[0];
        myTrack[diy][dix] += vol * vel[1];

        float h1 = sampleVolumeHeight(ix, iy, radPx);
        float h2 = sampleVolumeHeight(nx, ny, radPx);
        float nodeDischarge = (float) Math.log1p(localDischarge);
        float cEq = (1.0f + ENTRAINMENT * nodeDischarge) * (h1 - h2);
        if (cEq < 0) cEq = 0;

        float cdiff = cEq - sediment;
        float carriedDelta = DEPOSITION * cdiff;
        sediment += carriedDelta;

        float waterH = targetWater[iy][ix];
        float bedH = targetBed[iy][ix];
        float depth = Math.max(0.001f, waterH - bedH);
        float depthFactor = Math.min(1f, depth * 7f);
        float carve = -carriedDelta * (0.6f + 0.8f * depthFactor);
        applyBrushHeightDelta(ix, iy, radPx, carve);

        sediment = Math.max(-10.0f, Math.min(10.0f, sediment));
        sediment /= (1.0f - EVAP);
        vol *= (1.0f - EVAP);

        vsa[0] = vol;
        vsa[1] = age + 1;
        vsa[2] = sediment;

        return true;
    }

    void simulate() {
        initHeightmap();
        buildCorridor();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            for (int y = 0; y < h; y++) {
                Arrays.fill(dischargeTrack[y], 0);
                Arrays.fill(mxTrack[y], 0);
                Arrays.fill(myTrack[y], 0);
            }

            for (int i = 0; i < DROPS_PER_ITER; i++) {
                int px, py;
                if (corridorCount > 0 && rng.nextFloat() < 0.92f) {
                    int idx = rng.nextInt(corridorCount);
                    px = corridorX[idx];
                    py = corridorY[idx];
                    px += rng.nextInt(5) - 2;
                    py += rng.nextInt(5) - 2;
                    if (px < 0) px = 0;
                    if (px >= w) px = w - 1;
                    if (py < 0) py = 0;
                    if (py >= h) py = h - 1;
                } else {
                    px = rng.nextInt(w);
                    py = rng.nextInt(h);
                }

                if (height[py][px] < SEA_LEVEL) continue;

                float[] pos = {px + rng.nextFloat(), py + rng.nextFloat()};
                float[] vel = {0, 0};
                float[] vsa = {1.0f, 0, 0.0f};
                while (descend(pos, vel, vsa)) {}
            }

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    discharge[y][x] = (1f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                    mx[y][x] = (1f - LRATE) * mx[y][x] + LRATE * mxTrack[y][x];
                    my[y][x] = (1f - LRATE) * my[y][x] + LRATE * myTrack[y][x];
                }
            }
        }
    }

    int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    int getTerrainColor(float hVal) {
        if (hVal < SEA_LEVEL) return rgb(190, 180, 60);
        float hn = (hVal - SEA_LEVEL) / (1f - SEA_LEVEL);
        int v = (int) (hn * 200) + 55;
        return rgb((int) (v * 0.9), (int) (v * 1.0), (int) (v * 0.8));
    }

    void renderAndSave(String filePath) throws Exception {
        simulate();

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        float[] lightDir = {0.7f, 0.7f, 1.0f};
        float lightLen = (float) Math.sqrt(lightDir[0] * lightDir[0] + lightDir[1] * lightDir[1] + lightDir[2] * lightDir[2]);
        lightDir[0] /= lightLen;
        lightDir[1] /= lightLen;
        lightDir[2] /= lightLen;

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                float hVal = height[py][px];
                float gx = (px < w - 1 && px > 0) ? (height[py][px + 1] - height[py][px - 1]) : 0;
                float gy = (py < h - 1 && py > 0) ? (height[py + 1][px] - height[py - 1][px]) : 0;
                float normalZ = 0.05f;
                float nLen = (float) Math.sqrt(gx * gx + gy * gy + normalZ * normalZ);
                float nx = -gx / nLen, ny = -gy / nLen, nz = normalZ / nLen;
                float diffuse = Math.max(0.2f, nx * lightDir[0] + ny * lightDir[1] + nz * lightDir[2]);
                float ao = 0.6f + 0.4f * diffuse;

                int base = getTerrainColor(hVal);
                int r = (int) (((base >> 16) & 0xFF) * ao);
                int g = (int) (((base >> 8) & 0xFF) * ao);
                int b = (int) ((base & 0xFF) * ao);

                float tw = targetWidth[py][px];
                if (tw > 0) {
                    float t = Math.min(1f, tw / WIDTH_CAP);
                    if (centerline[py][px]) {
                        r = (int) (10 * ao);
                        g = (int) ((80 + 20 * t) * ao);
                        b = (int) ((210 + 30 * t) * ao);
                    } else if (hVal < targetWater[py][px] - 0.002f) {
                        r = (int) (15 * ao);
                        g = (int) ((60 + 30 * t) * ao);
                        b = (int) ((160 + 70 * t) * ao);
                    } else if (inCorridor[py][px]) {
                        r = (int) ((80 + 50 * t) * ao);
                        g = (int) ((70 + 30 * t) * ao);
                        b = (int) ((40 + 20 * t) * ao);
                    }
                }

                img.setRGB(px, py, rgb(Math.min(255, r), Math.min(255, g), Math.min(255, b)));
            }
        }

        ImageIO.write(img, "png", new File(filePath));
    }

    static class CellData {
        final int ci, cj;
        final float wx, wz;
        int flowDir;

        CellData(int ci, int cj, float wx, float wz, int flowDir) {
            this.ci = ci;
            this.cj = cj;
            this.wx = wx;
            this.wz = wz;
            this.flowDir = flowDir;
        }
    }

    static class RiverNode {
        final float x, z, h, accum;

        RiverNode(float x, float z, float h, float accum) {
            this.x = x;
            this.z = z;
            this.h = h;
            this.accum = accum;
        }
    }

    static class RiverSeg {
        final float x1, z1, x2, z2;
        final float ah, bh, aw, bw, abed, bbed;

        RiverSeg(float x1, float z1, float x2, float z2, float ah, float bh, float aw, float bw, float abed, float bbed) {
            this.x1 = x1;
            this.z1 = z1;
            this.x2 = x2;
            this.z2 = z2;
            this.ah = ah;
            this.bh = bh;
            this.aw = aw;
            this.bw = bw;
            this.abed = abed;
            this.bbed = bbed;
        }
    }

    static class RiverPath {
        final List<RiverSeg> segs;

        RiverPath(List<RiverSeg> segs) {
            this.segs = segs;
        }
    }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        String tag = "v1";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed":
                    seed = Integer.parseInt(args[++i]);
                    break;
                case "--tag":
                    tag = args[++i];
                    break;
            }
        }
        DualLayerRiverGen gen = new DualLayerRiverGen(seed);
        int halfCells = VIEW_SIZE / CELL_SIZE / 2 + 3;
        for (int ci = -halfCells; ci <= halfCells; ci++) {
            for (int cj = -halfCells; cj <= halfCells; cj++) {
                gen.buildCell(ci, cj);
            }
        }
        gen.renderAndSave("output/dual_layer_s" + seed + "_" + tag + ".png");
    }
}
