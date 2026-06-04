package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.NoiseEngine;
import com.geogenesis.worldgen.geology.PlateTectonics;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RiverBrushSystem {

    private static final int CELL_SIZE = 64;
    private static final int GRID_SIZE = 32;
    private static final int MAX_TRACE_STEPS = 600;
    private static final float FLOW_ACCUM_MIN = 2;
    private static final float LAKE_CHANCE = 0.75f;
    private static final float MAX_SLOPE = 0.12f;
    private static final int BLEND_COUNT = 3;

    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final float[] DW = {0.707f, 1f, 0.707f, 1f, 0.707f, 1f, 0.707f, 1f};

    private final NoiseEngine noise;
    private final PlateTectonics plates;
    private final ImprovedNoise jitterNoiseX;
    private final ImprovedNoise jitterNoiseZ;
    private final ImprovedNoise meanderNoise;
    private final ImprovedNoise lakeNoise;

    private float seaNorm = 0.4f;
    private float odFactor = 0.12f;

    // 全局细胞缓存：key = cellKey(ci, cj)
    private final Map<Long, CellData> cellCache = new ConcurrentHashMap<>();
    // 空间索引：key = gridKey(gx, gz)，桶内用 volatile 快照保证读安全
    private final Map<Long, GridBucket> grid = new ConcurrentHashMap<>();
    // 已追踪过的源头去重集合
    private final Set<Long> tracedSources = ConcurrentHashMap.newKeySet();

    public RiverBrushSystem(int seed, NoiseEngine noise) {
        this.noise = noise;
        this.plates = new PlateTectonics(seed);
        RandomSource rng = RandomSource.create(seed + 7777);
        this.jitterNoiseX = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.jitterNoiseZ = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.meanderNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.lakeNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
    }

    public void setTerrainParams(float seaNorm, float odFactor, int maxY, int minY) {
        this.seaNorm = seaNorm;
        this.odFactor = odFactor;
    }

    // ============ 对外接口 ============

    public float getRiverDepthAt(float wx, float wz) {
        Sample s = sampleAt(wx, wz);
        if (s == null) return 0f;
        return s.valleyCarveDepth();
    }

    public Sample sampleAt(float wx, float wz) {
        ensureCellsBuilt(wx, wz);

        int gx = Math.floorDiv((int) wx, GRID_SIZE);
        int gz = Math.floorDiv((int) wz, GRID_SIZE);

        List<SegHit> hits = new ArrayList<>(BLEND_COUNT * 2);

        for (int ddx = -4; ddx <= 4; ddx++) {
            for (int ddz = -4; ddz <= 4; ddz++) {
                long gk = ((long)(gx + ddx) << 32) | ((gz + ddz) & 0xFFFFFFFFL);
                GridBucket bucket = grid.get(gk);
                if (bucket == null) continue;
                for (RiverEdge seg : bucket.snapshot()) {
                    float t = project(wx, wz, seg);
                    float d2 = distSq(wx, wz, seg, t);
                    if (d2 < seg.maxRadiusSq()) {
                        hits.add(new SegHit(seg, t, d2));
                    }
                }
            }
        }

        if (hits.isEmpty()) return null;

        hits.sort(Comparator.comparingDouble(h -> h.d2));
        int take = Math.min(BLEND_COUNT, hits.size());

        return blendSamples(hits.subList(0, take));
    }

    public float sampleRiverNoise(float wx, float wz) {
        Sample s = sampleAt(wx, wz);
        if (s == null) return 0f;
        return s.valleyCarveDepth() > 0.003f ? s.valleyCarveDepth() * 3f : 0f;
    }

    // ============ 核心：全局细胞缓存 ============

    private void ensureCellsBuilt(float wx, float wz) {
        // 定期清理缓存，防止无限地图生成导致内存泄漏
        if (cellCache.size() > 100000) {
            cellCache.clear();
            grid.clear();
            tracedSources.clear();
        }

        int ci = Math.floorDiv((int) wx, CELL_SIZE);
        int cj = Math.floorDiv((int) wz, CELL_SIZE);

        // 构建 9x9 区域的所有细胞（匹配 traceAllRiversInRegion，消除边界假源头）
        for (int dci = -4; dci <= 4; dci++) {
            for (int dcj = -4; dcj <= 4; dcj++) {
                long ck = cellKey(ci + dci, cj + dcj);
                if (!cellCache.containsKey(ck)) {
                    buildCell(ci + dci, cj + dcj);
                }
            }
        }

        // 确保所有细胞的流向都计算完成后再追踪河流
        traceAllRiversInRegion(ci, cj);
    }

    private synchronized void buildCell(int ci, int cj) {
        long ck = cellKey(ci, cj);
        if (cellCache.containsKey(ck)) return;

        float wx = cellCenterX(ci, cj);
        float wz = cellCenterZ(ci, cj);
        float height = computeTerrainHeight(wx, wz);

        CellData cell = new CellData(ci, cj, wx, wz, height, -1); // flowDir 先设为 -1
        cellCache.put(ck, cell);
    }

    private void traceAllRiversInRegion(int centerCi, int centerCj) {
        // 先计算区域内所有细胞的流向（展开到 9x9 避免边缘效应）
        for (int dci = -4; dci <= 4; dci++) {
            for (int dcj = -4; dcj <= 4; dcj++) {
                int ci = centerCi + dci;
                int cj = centerCj + dcj;
                long ck = cellKey(ci, cj);
                CellData cell = cellCache.get(ck);
                if (cell != null && cell.flowDir < 0) {
                    int flowDir = computeFlowDir(ci, cj, cell.height);
                    // 更新 flowDir
                    cellCache.put(ck, new CellData(ci, cj, cell.wx, cell.wz, cell.height, flowDir));
                }
            }
        }

        // 再追踪河流（只追踪源头，且每个源头只追踪一次）
        for (int dci = -4; dci <= 4; dci++) {
            for (int dcj = -4; dcj <= 4; dcj++) {
                int ci = centerCi + dci;
                int cj = centerCj + dcj;
                if (isSourceCell(ci, cj) && tracedSources.add(cellKey(ci, cj))) {
                    traceRiverFromSource(ci, cj);
                }
            }
        }
    }

    private int computeFlowDir(int ci, int cj, float height) {
        int bestDir = -1;
        float bestDrop = 0.0001f;

        for (int k = 0; k < 8; k++) {
            long nk = cellKey(ci + DX[k], cj + DZ[k]);
            float nh;
            if (cellCache.containsKey(nk)) {
                nh = cellCache.get(nk).height;
            } else {
                float nwx = cellCenterX(ci + DX[k], cj + DZ[k]);
                float nwz = cellCenterZ(ci + DX[k], cj + DZ[k]);
                nh = computeTerrainHeight(nwx, nwz);
            }
            float drop = (height - nh) / DW[k];
            if (drop > bestDrop && drop < MAX_SLOPE) {
                bestDrop = drop;
                bestDir = k;
            }
        }

        // 回退：找最陡下降
        if (bestDir < 0) {
            for (int k = 0; k < 8; k++) {
                long nk = cellKey(ci + DX[k], cj + DZ[k]);
                float nh;
                if (cellCache.containsKey(nk)) {
                    nh = cellCache.get(nk).height;
                } else {
                    float nwx = cellCenterX(ci + DX[k], cj + DZ[k]);
                    float nwz = cellCenterZ(ci + DX[k], cj + DZ[k]);
                    nh = computeTerrainHeight(nwx, nwz);
                }
                float drop = (height - nh) / DW[k];
                if (drop > bestDrop) {
                    bestDrop = drop;
                    bestDir = k;
                }
            }
        }

        return bestDir;
    }

    private boolean isSourceCell(int ci, int cj) {
        long ck = cellKey(ci, cj);
        CellData cell = cellCache.get(ck);
        if (cell == null || cell.flowDir < 0) return false;

        // 检查是否有上游细胞指向我
        for (int k = 0; k < 8; k++) {
            int ui = ci + DX[k];
            int uj = cj + DZ[k];
            long uk = cellKey(ui, uj);
            CellData upstream = cellCache.get(uk);
            if (upstream != null && upstream.flowDir >= 0) {
                int flowToI = ui + DX[upstream.flowDir];
                int flowToJ = uj + DZ[upstream.flowDir];
                if (flowToI == ci && flowToJ == cj) {
                    return false; // 有上游指向我，我不是源头
                }
            }
        }
        return true;
    }

    // ============ 河流追踪 + 链式高度传递 ============

    private void traceRiverFromSource(int ci, int cj) {
        List<CellData> path = new ArrayList<>();
        int curI = ci, curJ = cj;

        // 第一步：追踪路径
        for (int step = 0; step < MAX_TRACE_STEPS; step++) {
            long ck = cellKey(curI, curJ);
            CellData cell = cellCache.get(ck);
            if (cell == null) break;

            if (cell.height < seaNorm * 0.3f) {
                path.add(cell);
                break;
            }

            path.add(cell);

            if (cell.flowDir < 0) break;

            int ni = curI + DX[cell.flowDir];
            int nj = curJ + DZ[cell.flowDir];

            // 确保下游细胞已构建
            long nk = cellKey(ni, nj);
            if (!cellCache.containsKey(nk)) {
                buildCell(ni, nj);
                // 计算下游细胞的流向
                float nh = cellCache.get(nk).height;
                int nDir = computeFlowDir(ni, nj, nh);
                cellCache.put(nk, new CellData(ni, nj, cellCenterX(ni, nj), cellCenterZ(ni, nj), nh, nDir));
            }

            curI = ni;
            curJ = nj;
        }

        if (path.size() < 2) return;

        // 第二步：计算累积流量（从上游到下游），跨源合并
        float[] accums = new float[path.size()];
        for (int i = 0; i < path.size(); i++) {
            float total = 1f;
            // 加上所有上游的流量（包括已被其他源头追踪的上游细胞）
            CellData cell = path.get(i);
            for (int k = 0; k < 8; k++) {
                int ui = cell.ci + DX[k];
                int uj = cell.cj + DZ[k];
                long uk = cellKey(ui, uj);
                CellData upstream = cellCache.get(uk);
                if (upstream != null && upstream.flowDir >= 0) {
                    int flowToI = ui + DX[upstream.flowDir];
                    int flowToJ = uj + DZ[upstream.flowDir];
                    if (flowToI == cell.ci && flowToJ == cell.cj) {
                        // 优先用 cellCache 中的累积（其他源头可能已贡献）
                        float upAccum = upstream.accum;
                        // 如果在当前路径中，用路径累积（更高，因为包含当前路径的连续）
                        for (int j = 0; j < i; j++) {
                            if (path.get(j).ci == ui && path.get(j).cj == uj) {
                                upAccum = accums[j];
                                break;
                            }
                        }
                        total += upAccum;
                    }
                }
            }
            accums[i] = total;
        }

        // 将累积回写到 cellCache，供后续其他源头的追踪使用
        for (int i = 0; i < path.size(); i++) {
            CellData cell = path.get(i);
            cellCache.put(cellKey(cell.ci, cell.cj), new CellData(cell.ci, cell.cj, cell.wx, cell.wz, cell.height, cell.flowDir, accums[i]));
        }

        // 第三步：从下游向上游传递水面高度（链式）
        int n = path.size();
        float[] waterLevels = new float[n];

        // 河口 = 海平面或更低
        waterLevels[n - 1] = Math.min(path.get(n - 1).height, seaNorm);

        // 向上游传递
        float maxGradient = 0.0003f;
        for (int i = n - 2; i >= 0; i--) {
            CellData cell = path.get(i);
            CellData next = path.get(i + 1);

            float dist = (float) Math.sqrt(
                (cell.wx - next.wx) * (cell.wx - next.wx) +
                (cell.wz - next.wz) * (cell.wz - next.wz)
            );

            float idealWL = waterLevels[i + 1] + maxGradient * dist;
            idealWL = Math.min(idealWL, cell.height - 0.005f);
            idealWL = Math.max(idealWL, seaNorm);

            waterLevels[i] = idealWL;
        }

        // 第四步：平滑水面高度
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 1; i < n; i++) {
                CellData cell = path.get(i);
                CellData prev = path.get(i - 1);
                float dist = (float) Math.sqrt(
                    (cell.wx - prev.wx) * (cell.wx - prev.wx) +
                    (cell.wz - prev.wz) * (cell.wz - prev.wz)
                );
                float maxDrop = maxGradient * dist;
                float actualDrop = waterLevels[i - 1] - waterLevels[i];
                if (actualDrop > maxDrop) {
                    waterLevels[i] = waterLevels[i - 1] - maxDrop;
                    waterLevels[i] = Math.max(waterLevels[i], seaNorm);
                }
            }
        }

        // 第五步：生成 RiverEdge
        generateEdges(path, accums, waterLevels);
    }

    private void generateEdges(List<CellData> path, float[] accums, float[] waterLevels) {
        int n = path.size();

        // 检查源头湖泊
        boolean hasLake = false;
        if (n > 0) {
            CellData src = path.get(0);
            float lVal = (float) lakeNoise.noise(src.wx * 0.004, 0.0, src.wz * 0.004);
            float chance = (lVal + 1f) * 0.5f;
            hasLake = chance > (1f - LAKE_CHANCE);
        }

        // 计算 inflowCount
        int[] inflows = new int[n];
        for (int i = 0; i < n; i++) {
            CellData cell = path.get(i);
            for (int k = 0; k < 8; k++) {
                int ui = cell.ci + DX[k];
                int uj = cell.cj + DZ[k];
                long uk = cellKey(ui, uj);
                CellData upstream = cellCache.get(uk);
                if (upstream != null && upstream.flowDir >= 0) {
                    int flowToI = ui + DX[upstream.flowDir];
                    int flowToJ = uj + DZ[upstream.flowDir];
                    if (flowToI == cell.ci && flowToJ == cell.cj) {
                        inflows[i]++;
                    }
                }
            }
        }

        // 生成源头湖泊（如果存在）
        if (hasLake && n > 0) {
            CellData src = path.get(0);
            float lakeWL = waterLevels[0];
            float lVal = (float) lakeNoise.noise(src.wx * 0.004, 0.0, src.wz * 0.004);
            float sizeFactor = 0.5f + (lVal + 1f) * 0.25f;
            float lakeWidth = (25f + (float) Math.pow(accums[0], 0.3f) * 30f) * sizeFactor;

            float dx = 0f, dz = 0f;
            if (n >= 2) {
                CellData next = path.get(1);
                dx = src.wx - next.wx;
                dz = src.wz - next.wz;
                float dLen = (float) Math.sqrt(dx * dx + dz * dz);
                if (dLen > 0.1f) {
                    dx /= dLen;
                    dz /= dLen;
                } else {
                    dx = 1f; dz = 0f;
                }
            } else {
                dx = 1f; dz = 0f;
            }

            float lakeLen = lakeWidth * 0.8f * sizeFactor;
            float lakeEndX = src.wx + dx * lakeLen;
            float lakeEndZ = src.wz + dz * lakeLen;

            RiverEdge lakeSeg = new RiverEdge(
                src.wx, src.wz, lakeEndX, lakeEndZ,
                lakeWL, lakeWL,
                lakeWidth, lakeWidth * 0.7f,
                lakeWL - 0.001f, lakeWL - 0.001f,
                accums[0],
                false, true, false
            );
            addEdge(lakeSeg);
        }

        // 生成河流段
        for (int i = 0; i < n - 1; i++) {
            CellData c1 = path.get(i);
            CellData c2 = path.get(i + 1);

            float ah = waterLevels[i];
            float bh = waterLevels[i + 1];
            if (bh > ah) bh = ah - 0.002f;

            float acc1 = accums[i];
            float acc2 = accums[i + 1];

            float confluenceW1 = inflows[i] >= 2 ? 1.8f : 1f;
            float confluenceW2 = inflows[i + 1] >= 2 ? 1.8f : 1f;

            float aWidth = riverWidth(acc1) * confluenceW1;
            float bWidth = riverWidth(acc2) * confluenceW2;
            float aDepth = riverDepth(acc1);
            float bDepth = riverDepth(acc2);

            float aBed = ah - aDepth;
            float bBed = bh - bDepth;

            // 弯曲
            float mx = (c1.wx + c2.wx) * 0.5f, mz = (c1.wz + c2.wz) * 0.5f;
            float mVal = (float) meanderNoise.noise(mx * 0.002, i * 5.0, mz * 0.002);
            float ddx = c2.wx - c1.wx, ddz = c2.wz - c1.wz;
            float len = (float) Math.sqrt(ddx * ddx + ddz * ddz);
            if (len > 0.1f) {
                float perpX = -ddz / len, perpZ = ddx / len;
                float offset = mVal * CELL_SIZE * 0.35f;
                mx += perpX * offset;
                mz += perpZ * offset;
            }

            float mh = (ah + bh) * 0.5f;
            float mw = (aWidth + bWidth) * 0.5f;
            float md = (aDepth + bDepth) * 0.5f;
            float mBed = mh - md;

            // 瀑布检测
            float terrainDrop = c1.height - c2.height;
            float segDist = len;
            float terrainGrad = segDist > 1f ? terrainDrop / segDist : 0f;
            boolean hasWaterfall = terrainGrad > 0.004f && terrainDrop > 0.04f;

            boolean isConfluence = inflows[i] >= 2;

            RiverEdge seg1 = new RiverEdge(
                c1.wx, c1.wz, mx, mz,
                ah, mh,
                aWidth, mw,
                aBed, mBed,
                acc1,
                hasWaterfall, false, isConfluence
            );
            addEdge(seg1);

            RiverEdge seg2 = new RiverEdge(
                mx, mz, c2.wx, c2.wz,
                mh, bh,
                mw, bWidth,
                mBed, bBed,
                acc2,
                hasWaterfall, false, inflows[i + 1] >= 2
            );
            addEdge(seg2);
        }
    }

    // ============ 工具方法 ============

    private float cellCenterX(int ci, int cj) {
        float base = ci * CELL_SIZE + CELL_SIZE * 0.5f;
        float jx = (float) jitterNoiseX.noise(ci * 0.23, 0.0, cj * 0.23);
        return base + jx * CELL_SIZE * 0.3f;
    }

    private float cellCenterZ(int ci, int cj) {
        float base = cj * CELL_SIZE + CELL_SIZE * 0.5f;
        float jz = (float) jitterNoiseZ.noise(ci * 0.23, 0.0, cj * 0.23);
        return base + jz * CELL_SIZE * 0.3f;
    }

    private float computeTerrainHeight(float wx, float wz) {
        PlateTectonics.PlateData plate = plates.sample((int)wx, (int)wz);

        float continent = noise.sampleContinentRaw(wx, wz);
        continent += plate.continentBias() * 0.25f;
        continent = Math.max(-1f, Math.min(1f, continent));

        float terrain = noise.sampleTerrainBase(wx, wz);
        float relief = noise.sampleElevation(wx, wz);
        float plateauW = noise.samplePlateauWeight(wx, wz);
        float karstW = noise.sampleKarstWeight(wx, wz);
        float glacierW = noise.sampleGlacierWeight(wx, wz);

        float rf = noise.sampleRidge(wx, wz);
        float cf = noise.sampleCellNoise(wx, wz);
        float hf = noise.sampleTerrainHills(wx, wz);
        float gf = noise.sampleGullyErosion(wx, wz);

        float detail = rf * 0.50f + cf * 0.28f + hf * 0.14f + gf * 0.08f;
        float baseType = terrain * 0.5f + detail * 0.5f;
        baseType = Math.min(1f, baseType);

        float plateauAmount = smoothstep(plateauW);
        float plateauThreshold = 0.4f + relief * 0.2f;
        float plateauLift = 0f;
        if (baseType > plateauThreshold && plateauAmount > 0.01f) {
            float excess = (baseType - plateauThreshold) / (1f - plateauThreshold);
            plateauLift = (plateauThreshold + excess * 0.3f - baseType) * plateauAmount;
        }

        float karstAmount = smoothstep(karstW) * (1f - smoothstep(continent / 0.5f))
                          * smoothstep(relief - 0.3f) * (1f - smoothstep((relief - 0.8f) / 0.2f));
        float karstLift = 0f;
        if (karstAmount > 0.01f) {
            float peak = Math.max(0f, noise.sampleTerrainDetail(wx, wz) * terrain * 0.6f);
            karstLift = peak * karstAmount;
        }

        float glacierAmount = smoothstep(glacierW) * smoothstep(1f - terrain) * smoothstep(relief - 0.6f);
        float glacierMod = 0f;
        if (glacierAmount > 0.01f) {
            float valley = noise.sampleValleyLarge(wx, wz);
            float valleyCenter = 1f - Math.abs(valley * 2f - 1f);
            float uFill = valleyCenter * 0.12f;
            float peakCut = Math.max(0f, baseType - 0.7f) * valleyCenter * 0.3f;
            glacierMod = (uFill - peakCut) * glacierAmount * 0.5f;
        }

        float shaped = baseType + plateauLift + karstLift + glacierMod + plate.uplift();
        shaped = Math.max(0f, Math.min(1f, shaped));

        float crust = plate.crustalThickness();
        float transition = smoothstep(Math.max(0f, (crust - 0.3f) / 0.2f));

        float amp = Math.min(0.06f + relief * relief * 1.6f, 2.0f);
        float tr = 1f - seaNorm;
        float landHeight = seaNorm + Math.min(shaped * tr * amp, tr);

        float oceanHeight = seaNorm - odFactor * (1f - transition);

        return oceanHeight * (1f - transition) + landHeight * transition;
    }

    private Sample blendSamples(List<SegHit> hits) {
        double totalW = 0f;
        float blendedWL = 0f, blendedBL = 0f, blendedVD = 0f;
        float maxValleyR = 0f, maxBankR = 0f, maxBedR = 0f;
        boolean anyWaterfall = false, anyLake = false;
        boolean inAnyValley = false;

        for (SegHit h : hits) {
            RiverEdge seg = h.seg;
            float t = h.t;
            float distance = (float) Math.sqrt(h.d2);

            float waterLevel = seg.waterHeight(t);
            float bedLevel = seg.bedLevel(t);
            float vR = seg.valleyRadius(t);
            float bR = seg.bankRadius(t);
            float bdR = seg.bedRadius(t);

            if (distance > vR) continue;
            inAnyValley = true;

            float carveDepth;
            if (distance <= bdR) {
                carveDepth = waterLevel - bedLevel;
            } else if (distance <= bR) {
                float ft = (distance - bdR) / (bR - bdR);
                carveDepth = (waterLevel - bedLevel) * (1f - ft * 0.25f);
            } else {
                float ft = (distance - bR) / (vR - bR);
                ft = ft * ft * (3f - 2f * ft);
                carveDepth = (waterLevel - bedLevel) * 0.6f * (1f - ft);
            }

            double w = 1.0 / (1.0 + h.d2 * 0.01);
            totalW += w;
            blendedWL += waterLevel * w;
            blendedBL += bedLevel * w;
            blendedVD += carveDepth * w;
            maxValleyR = Math.max(maxValleyR, vR);
            maxBankR = Math.max(maxBankR, bR);
            maxBedR = Math.max(maxBedR, bdR);
            if (seg.isWaterfall) anyWaterfall = true;
            if (seg.isLake) anyLake = true;
        }

        if (!inAnyValley || totalW < 0.001) return null;

        float distance = (float) Math.sqrt(hits.get(0).d2);

        return new Sample(
            (float)(blendedWL / totalW),
            (float)(blendedBL / totalW),
            distance,
            maxValleyR, maxBankR, maxBedR,
            anyWaterfall, anyLake,
            (float)(blendedVD / totalW)
        );
    }

    private void addEdge(RiverEdge edge) {
        float r = edge.maxRadius() + 20f;
        float minX = Math.min(edge.x1, edge.x2) - r;
        float maxX = Math.max(edge.x1, edge.x2) + r;
        float minZ = Math.min(edge.z1, edge.z2) - r;
        float maxZ = Math.max(edge.z1, edge.z2) + r;

        int gxMin = Math.floorDiv((int) Math.floor(minX), GRID_SIZE);
        int gxMax = Math.floorDiv((int) Math.ceil(maxX), GRID_SIZE);
        int gzMin = Math.floorDiv((int) Math.floor(minZ), GRID_SIZE);
        int gzMax = Math.floorDiv((int) Math.ceil(maxZ), GRID_SIZE);

        for (int gx = gxMin; gx <= gxMax; gx++) {
            for (int gz = gzMin; gz <= gzMax; gz++) {
                long gk = ((long) gx << 32) | (gz & 0xFFFFFFFFL);
                grid.computeIfAbsent(gk, k -> new GridBucket()).append(edge);
            }
        }
    }

    private float riverWidth(float accum) {
        float base = 3f;
        float main = (float) Math.pow(accum, 0.35f) * 5f;
        float cap = 40f;
        return Math.min(base + main, cap);
    }

    private float riverDepth(float accum) {
        float base = 0.015f;
        float scale = (float) Math.log1p(accum) * 0.03f;
        float cap = 0.24f;
        return Math.min(base + scale, cap);
    }

    private float project(float px, float pz, RiverEdge s) {
        float dx = s.x2 - s.x1, dz = s.z2 - s.z1;
        float ls = dx * dx + dz * dz;
        if (ls < 0.001f) return 0f;
        return Math.max(0f, Math.min(1f, ((px - s.x1) * dx + (pz - s.z1) * dz) / ls));
    }

    private float distSq(float px, float pz, RiverEdge s, float t) {
        float nx = s.x1 + t * (s.x2 - s.x1);
        float nz = s.z1 + t * (s.z2 - s.z1);
        float ddx = px - nx, ddz = pz - nz;
        return ddx * ddx + ddz * ddz;
    }

    private static float smoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private static long cellKey(int ci, int cj) {
        return ((long) ci << 32) | (cj & 0xFFFFFFFFL);
    }

    // ============ 内部类 ============

    /**
     * 网格桶：内部保存 volatile RiverEdge[] 快照，支持并发安全的读写分离。
     * 写线程通过 synchronized append 复制后发布新数组，
     * 读线程只通过 snapshot() 获取已发布完成的不可变数组引用。
     */
    private static final class GridBucket {
        private volatile RiverEdge[] edges = new RiverEdge[0];
        private final Set<Integer> fingerprints = new HashSet<>();

        synchronized void append(RiverEdge edge) {
            // 坐标指纹去重，防止相邻区块处理重叠区域时导致同一条边被多次添加
            int fp = Objects.hash(edge.x1, edge.z1, edge.x2, edge.z2);
            if (!fingerprints.add(fp)) return;

            RiverEdge[] next = Arrays.copyOf(edges, edges.length + 1);
            next[next.length - 1] = edge;
            edges = next;
        }

        RiverEdge[] snapshot() {
            return edges;
        }
    }

    private static class CellData {
        final int ci, cj;
        final float wx, wz;
        final float height;
        final int flowDir;
        volatile float accum; // 累积流量，由 traceRiverFromSource 计算后写入，用于跨源合并

        CellData(int ci, int cj, float wx, float wz, float height, int flowDir) {
            this(ci, cj, wx, wz, height, flowDir, 0f);
        }

        CellData(int ci, int cj, float wx, float wz, float height, int flowDir, float accum) {
            this.ci = ci;
            this.cj = cj;
            this.wx = wx;
            this.wz = wz;
            this.height = height;
            this.flowDir = flowDir;
            this.accum = accum;
        }
    }

    record SegHit(RiverEdge seg, float t, float d2) {}

    public record Sample(float waterLevel, float bedLevel, float distance,
                         float valleyRadius, float bankRadius, float bedRadius,
                         boolean isWaterfall, boolean isLake, float valleyCarveDepth) {

        public boolean isRiver() {
            return valleyCarveDepth > 0.001f;
        }

        public boolean needsWater() {
            return distance < bankRadius;
        }

        public float computeCarvedHeight(float naturalHeight) {
            if (isLake) {
                if (distance > valleyRadius) return naturalHeight;
                if (naturalHeight <= waterLevel) return naturalHeight;
                float t = (distance - bedRadius) / (valleyRadius - bedRadius);
                t = Math.max(0f, Math.min(1f, t));
                t = t * t * (3f - 2f * t);
                return waterLevel + (naturalHeight - waterLevel) * t;
            }

            if (distance > valleyRadius) return naturalHeight;

            if (distance <= bedRadius) {
                return bedLevel;
            } else if (distance <= bankRadius) {
                float t = (distance - bedRadius) / (bankRadius - bedRadius);
                t = t * t * (3f - 2f * t);
                return bedLevel + (waterLevel - bedLevel) * t;
            } else {
                float t = (distance - bankRadius) / (valleyRadius - bankRadius);
                t = t * t * (3f - 2f * t);
                return waterLevel + (naturalHeight - waterLevel) * t;
            }
        }
    }

    private record RiverEdge(float x1, float z1, float x2, float z2,
                              float ah, float bh,
                              float ar, float br,
                              float bedA, float bedB,
                              float accum, boolean isWaterfall,
                              boolean isLake, boolean isConfluence) {

        float waterHeight(float t) { return ah + t * (bh - ah); }

        float bedLevel(float t) { return bedA + t * (bedB - bedA); }

        float radius(float t) { return ar + t * (br - ar); }

        float valleyRadius(float t) {
            float r = radius(t);
            if (isLake) return r * 2.5f;
            if (isConfluence) return r * 2.5f;
            return r * 3.0f;
        }

        float bankRadius(float t) {
            float r = radius(t);
            if (isLake) return r * 1.2f;
            if (isConfluence) return r * 1.3f;
            return r * 1.4f;
        }

        float bedRadius(float t) {
            float r = radius(t);
            if (isLake) return r * 0.6f;
            return r * 0.4f;
        }

        float maxRadius() {
            float r = Math.max(ar, br);
            if (isLake) return r * 2.5f;
            if (isConfluence) return r * 2.5f;
            return r * 3.0f;
        }

        float maxRadiusSq() { float r = maxRadius(); return r * r; }
    }
}
