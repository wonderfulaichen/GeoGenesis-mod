package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.NoiseEngine;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class FlowAccumulationSystem {

    private static final int CELL_SPACING = 120;

    private static final int DIR_OFFSET = 20107;
    private static final int SIZE_A_OFFSET = 9803;
    private static final int SIZE_B_OFFSET = 28387;
    private static final int LAKE_CHANCE_OFFSET = 37171;

    private final int seed;
    private final NoiseEngine noiseEngine;
    private final CellGrid cellGrid;

    private final ImprovedNoise warpX;
    private final ImprovedNoise warpZ;
    private final ImprovedNoise erosionNoise;

    private final Map<Long, RiverPieces> cache = new ConcurrentHashMap<>();

    private static final float SEA_THRESHOLD = 0.38f;

    private float valleyWidthMin = 40f, valleyWidthMax = 120f;
    private float bankWidthMin = 3f, bankWidthMax = 12f;
    private float bedWidthMin = 1f, bedWidthMax = 4f;
    private float bedDepthMin = 0.025f, bedDepthMax = 0.12f;
    private float bankDepthMin = 0.008f, bankDepthMax = 0.03f;

    public FlowAccumulationSystem(NoiseEngine noiseEngine, int baseSeed) {
        this.seed = baseSeed;
        this.noiseEngine = noiseEngine;
        this.cellGrid = new CellGrid(baseSeed);
        RandomSource rng = RandomSource.create(baseSeed + 1000);
        this.warpX = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.warpZ = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.erosionNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
    }

    public CellGrid getCellGrid() { return cellGrid; }

    public float getRiverDepthAt(float wx, float wz, float seaNorm) {
        CarverSample sample = queryRiver(wx, wz);
        if (sample == null) return 0f;

        float dist = (float) Math.sqrt(sample.distance);
        float t = sample.projection;
        float bedW = getBedWidth(sample.river, t);
        if (dist <= bedW) return getBedDepth(sample.river, t);

        float bankW = getBankWidth(sample.river, t);
        if (dist <= bankW) {
            float alpha = (dist - bedW) / (bankW - bedW);
            return getBedDepth(sample.river, t) * (1f - alpha * alpha);
        }
        return 0f;
    }

    public float carveAt(float wx, float wz, float height, float seaNorm) {
        CarverSample sample = queryRiver(wx, wz);
        if (sample == null) return height;
        return carve(sample, height, 0f, seaNorm);
    }

    public void carveValleys(float[][] heightField, int size, int startX, int startZ,
                              float seaNorm, float depthMultiplier) {
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int wx = startX + x, wz = startZ + z;
                float original = heightField[z][x];
                float carved = carveAt(wx, wz, original, seaNorm);
                float delta = carved - original;
                heightField[z][x] = original + delta * depthMultiplier;
            }
        }
    }

    private CarverSample queryRiver(float wx, float wz) {
        float px = wx + (float) warpX.noise(wx * 0.003, 71.3, wz * 0.003) * 0.8f;
        float pz = wz + (float) warpZ.noise(wx * 0.003 + 100, 137.9, wz * 0.003 + 100) * 0.8f;

        int cx = Math.floorDiv((int) px, CELL_SPACING);
        int cz = Math.floorDiv((int) pz, CELL_SPACING);

        CarverSample best = null;

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                RiverPieces pieces = getNodes(cx + dx, cz + dz);
                if (pieces == RiverPieces.NONE) continue;

                for (int i = 0; i < pieces.riverCount(); i++) {
                    RiverNode node = pieces.river(i);
                    float t = node.getProjection(px, pz);
                    float d2 = node.getDistance2(px, pz, t);
                    if (best == null || d2 < best.distance) {
                        if (best == null) best = new CarverSample();
                        best.river = node;
                        best.distance = d2;
                        best.projection = t;
                    }
                }

                for (int i = 0; i < pieces.lakeCount(); i++) {
                    RiverNode node = pieces.lake(i);
                    float t = node.getProjection(px, pz);
                    float d2 = node.getDistance2(px, pz, t);
                    if (best == null || d2 < best.distance) {
                        if (best == null) best = new CarverSample();
                        best.river = node;
                        best.distance = d2;
                        best.projection = t;
                        best.isLake = true;
                    }
                }
            }
        }

        return best;
    }

    private RiverPieces getNodes(int cx, int cz) {
        long key = CellGrid.pack(cx, cz);
        return cache.computeIfAbsent(key, k -> computeNodes(cx, cz));
    }

    private RiverPieces computeNodes(int cx, int cz) {
        CellGrid.CellPoint a = cellGrid.getCell(cx, cz);
        float ah = cellHeight(a);
        if (ah <= 0) return RiverPieces.NONE;

        CellGrid.CellPoint min = a;
        float minH = ah;

        float ar = cellRadius(a);
        boolean isSource = true;
        RiverPieces pieces = new RiverPieces();

        int[] dirX = cellGrid.dirsX();
        int[] dirZ = cellGrid.dirsZ();

        for (int d = 0; d < 4; d++) {
            int bx = cx + dirX[d];
            int bz = cz + dirZ[d];
            CellGrid.CellPoint b = cellGrid.getCell(bx, bz);
            float bh = cellHeight(b);
            if (bh <= 0) continue;

            if (bh <= minH) {
                min = b;
                minH = bh;
                continue;
            }

            if (connects(cx, cz, bx, bz, ah, bh)) {
                float br = cellRadius(b);
                int hash = CellGrid.hash(seed + 827614, bx, bz);
                addRiverNodes(a, b, ah, bh, ar, br, hash, pieces);
                isSource = false;
            }
        }

        if (min == a) return pieces;

        if (isSource && pieces.riverCount() == 0) return pieces;

        float br = cellRadius(min);
        int hash = CellGrid.hash(seed + 827614, cx, cz);
        float mh = cellHeight(min);
        addRiverNodes(a, min, ah, mh, ar, br, hash, pieces);

        if (isSource && hasLake(a, hash)) {
            addLakeNodes(a, min, ah, hash, pieces);
        }

        return pieces;
    }

    private boolean connects(int ax, int ay, int bx, int bz, float ah, float bh) {
        if (bh <= 0) return false;
        int bestX = bx, bestZ = bz;
        float minH = bh;
        int[] dirX = cellGrid.dirsX();
        int[] dirZ = cellGrid.dirsZ();

        for (int d = 0; d < 4; d++) {
            int cx = bx + dirX[d];
            int cz = bz + dirZ[d];
            CellGrid.CellPoint c = cellGrid.getCell(cx, cz);
            float ch = cellHeight(c);
            if (ch > 0 && ch < minH) {
                bestX = cx;
                bestZ = cz;
                minH = ch;
            }
        }

        return bestX == ax && bestZ == ay;
    }

    private void addRiverNodes(CellGrid.CellPoint a, CellGrid.CellPoint b,
                                float ah, float bh, float ar, float br,
                                int hash, RiverPieces pieces) {
        float mx = (a.px + b.px) * 0.5f;
        float my = (a.py + b.py) * 0.5f;
        float mr = (ar + br) * 0.5f;
        float mh = (ah + bh) * 0.5f;

        float cx = (a.px + mx) * 0.5f;
        float cy = (a.py + my) * 0.5f;
        float cr = (ar + mr) * 0.5f;
        float ch = (ah + mh) * 0.5f;

        float nx = -(cy - a.py);
        float ny = (cx - a.px);

        float dir = CellGrid.rand(seed + DIR_OFFSET + hash) < 0.5f ? -1f : 1f;
        float amp0 = 0.7f + CellGrid.rand(seed + SIZE_A_OFFSET + hash) * 0.3f;
        float amp1 = 0.7f + CellGrid.rand(seed + SIZE_B_OFFSET + hash) * 0.3f;

        float displacement = 0.35f * dir * amp0;
        cx += nx * displacement * CELL_SPACING;
        cy += ny * displacement * CELL_SPACING;

        float warpStr = 0.275f * -dir * amp1;
        float warp1 = warpStr * map(a.noise(), 0.4f, 0.6f, 0.2f);
        float warp2 = -warpStr * map(b.noise(), 0.4f, 0.6f, 0.2f);

        float seaCheckH = cellHeight(b);
        float seaCheckA = cellHeight(a);

        pieces.addRiver(new RiverNode(
            a.px * CELL_SPACING, a.py * CELL_SPACING,
            cx * CELL_SPACING, cy * CELL_SPACING,
            ah, ch, ar, cr, warp1
        ));
        pieces.addRiver(new RiverNode(
            cx * CELL_SPACING, cy * CELL_SPACING,
            mx * CELL_SPACING, my * CELL_SPACING,
            ch, mh, cr, mr, warp2
        ));

        if (seaCheckH <= SEA_THRESHOLD) {
            float extW = warp1 * 0.5f;
            pieces.addRiver(new RiverNode(
                mx * CELL_SPACING, my * CELL_SPACING,
                b.px * CELL_SPACING, b.py * CELL_SPACING,
                mh, 0.15f, mr, br, extW
            ));
        }
    }

    private void addLakeNodes(CellGrid.CellPoint a, CellGrid.CellPoint b,
                               float ah, int hash, RiverPieces pieces) {
        float size = (0.5f + CellGrid.rand(seed + SIZE_A_OFFSET + hash) * 0.5f) * 0.12f;
        float dx = a.px - b.px;
        float dy = a.py - b.py;
        float cx = a.px + dx * size;
        float cy = a.py + dy * size;

        pieces.addLake(new RiverNode(
            a.px * CELL_SPACING, a.py * CELL_SPACING,
            cx * CELL_SPACING, cy * CELL_SPACING,
            ah, ah, 1f, 1f, 0f
        ));
    }

    private boolean hasLake(CellGrid.CellPoint cell, int hash) {
        return CellGrid.rand(hash + LAKE_CHANCE_OFFSET) <= 0.75f
            || cellHeight(cell) < 0.25f;
    }

    private float cellHeight(CellGrid.CellPoint cell) {
        float wx = cell.px * CELL_SPACING;
        float wz = cell.py * CELL_SPACING;
        float h = noiseEngine.sampleTerrainBase(wx, wz);
        if (h < SEA_THRESHOLD) return 0f;
        return (h - SEA_THRESHOLD) / (1f - SEA_THRESHOLD);
    }

    private float cellRadius(CellGrid.CellPoint cell) {
        float h = cellHeight(cell);
        return Math.max(0.01f, Math.min(1f, 1f - h * 0.6f));
    }

    private float carve(CarverSample sample, float height, float erosionVal, float seaNorm) {
        if (sample == null || sample.river == null) return height;

        RiverNode node = sample.river;
        float dist = (float) Math.sqrt(sample.distance);
        float t = sample.projection;

        float valleyW = getValleyWidth(node, t);
        float bankW = getBankWidth(node, t);
        float bedW = getBedWidth(node, t);
        float bedD = getBedDepth(node, t);
        float bankD = getBankDepth(node, t);

        float bedLevel = height - bedD;
        float bankLevel = height + bankD;

        if (dist < valleyW) {
            float valleyAlpha = getValleyAlpha(dist, bankW, valleyW, height);
            float target = lerp(bedLevel, bankLevel, valleyAlpha);
            height = Math.min(height, target);
        }

        if (dist < bankW) {
            float bedAlpha = dist <= bedW ? 0f : (dist - bedW) / (bankW - bedW);
            bedAlpha = staticSmoothstep(bedAlpha);
            float target = lerp(bedLevel, bankLevel, bedAlpha);
            height = Math.min(height, target);
        }

        return height;
    }

    private float getValleyWidth(RiverNode node, float t) {
        float r = node.getRadius(t);
        return valleyWidthMin + (valleyWidthMax - valleyWidthMin) * (1f - r);
    }

    private float getBankWidth(RiverNode node, float t) {
        float r = node.getRadius(t);
        return bankWidthMin + (bankWidthMax - bankWidthMin) * (1f - r);
    }

    private float getBedWidth(RiverNode node, float t) {
        float r = node.getRadius(t);
        return bedWidthMin + (bedWidthMax - bedWidthMin) * (1f - r);
    }

    private float getBedDepth(RiverNode node, float t) {
        float r = node.getRadius(t);
        return bedDepthMin + (bedDepthMax - bedDepthMin) * (1f - r);
    }

    private float getBankDepth(RiverNode node, float t) {
        float r = node.getRadius(t);
        return bankDepthMin + (bankDepthMax - bankDepthMin) * (1f - r);
    }

    private static float getValleyAlpha(float distance, float bankWidth, float valleyWidth, float baseValue) {
        float alpha = getAlpha(distance, bankWidth, valleyWidth);
        float shapeAlpha = getAlpha(baseValue, 0.4f, 0.6f);
        return lerp(alpha * alpha, alpha, shapeAlpha);
    }

    private static float getAlpha(float value, float min, float max) {
        if (value <= min) return 0f;
        if (value >= max) return 1f;
        return (value - min) / (max - min);
    }

    private static float lerp(float a, float b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return a + t * (b - a);
    }

    private static float staticSmoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private static float map(float value, float min, float max, float range) {
        if (value <= min) return 0f;
        if (value >= max) return range;
        return (value - min) / (max - min) * range;
    }

    private static class CarverSample {
        RiverNode river;
        float distance = Float.MAX_VALUE;
        float projection = 0;
        boolean isLake = false;
    }
}
