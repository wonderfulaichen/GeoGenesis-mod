package com.erosiontest;

/**
 * 模组 ErosionEngine.pyramidErosion 的独立移植版
 * 用于独立测试和对比效果，不依赖 Minecraft
 *
 * 与模组源码同步: ErosionEngine.java
 */
public class ModErosionEngine {

    public void pyramidErosion(float[][] map, int bufSize, int drops, float strength,
                                 int radius, float fallOff, float inertia,
                                 float gravity, float erodeSpeed, float depositSpeed,
                                 int ox, int oz, int pad, int baseSize, int worldScale,
                                 boolean[][] locked) {
        float capFactor = 10f, minCap = 0.005f, evaporate = 0.35f;
        float[] flat = new float[bufSize * bufSize];
        for (int z = 0; z < bufSize; z++)
            for (int x = 0; x < bufSize; x++)
                flat[z*bufSize+x] = map[z][x];

        int r2 = radius * radius;
        int maxB = (2*radius+1)*(2*radius+1);
        int[] bOff = new int[maxB];
        float[] bWgt = new float[maxB];
        int bn = 0;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++) {
                float d2 = dx*dx + dy*dy;
                if (d2 < r2) { bOff[bn]=dy*bufSize+dx; bWgt[bn]=1f-(float)Math.sqrt(d2)/radius; bn++; }
            }
        { float s=0; for(int i=0;i<bn;i++)s+=bWgt[i]; for(int i=0;i<bn;i++)bWgt[i]/=s; }

        int interiorStart = pad;
        int interiorEnd = pad + baseSize;
        int margin = 1;
        float pixelCount = (float)((baseSize - margin*2) * (baseSize - margin*2));
        float density = drops / pixelCount;
        long threshold = (long)(density * (1L << 20));

        for (int py = interiorStart + margin; py < interiorEnd - margin; py++) {
            for (int px = interiorStart + margin; px < interiorEnd - margin; px++) {
                int worldX = ox + (px - pad) * worldScale;
                int worldZ = oz + (py - pad) * worldScale;
                long h = hashCoarse(worldX * 31 + 1, worldZ * 73 + 1);
                if ((h & ((1L << 20) - 1)) >= threshold) continue;
                int idx = py*bufSize + px;
                if (flat[idx] <= 0.02f) continue;
                if (locked != null && locked[py][px]) continue;

                float dirX=0, dirZ=0, sed=0, spd=1f, wat=1f;
                float fpx = px+0.5f, fpz = py+0.5f;
                for (int step=0; step<30; step++) {
                    int ix=(int)fpx, iz=(int)fpz;
                    if (ix<1||ix>=bufSize-2||iz<1||iz>=bufSize-2) break;
                    idx = iz*bufSize+ix;
                    float fx=fpx-ix, fz=fpz-iz;
                    float hNW=flat[idx], hNE=flat[idx+1], hSW=flat[idx+bufSize], hSE=flat[idx+bufSize+1];
                    float h0 = hNW*(1-fx)*(1-fz)+hNE*fx*(1-fz)+hSW*(1-fx)*fz+hSE*fx*fz;
                    if (h0 <= 0.02f) break;
                    float gx = (hNE-hNW)*(1-fz)+(hSE-hSW)*fz;
                    float gz = (hSW-hNW)*(1-fx)+(hSE-hNE)*fx;
                    float glen = (float)Math.sqrt(gx*gx+gz*gz);
                    if (glen < 1e-12f) break;
                    dirX = dirX*inertia - gx*(1-inertia);
                    dirZ = dirZ*inertia - gz*(1-inertia);
                    float dlen = (float)Math.sqrt(dirX*dirX+dirZ*dirZ);
                    if (dlen < 1e-12f) break; dirX/=dlen; dirZ/=dlen;
                    float npx = fpx+dirX, npz = fpz+dirZ;
                    if (npx<1||npx>=bufSize-2||npz<1||npz>=bufSize-2) break;
                    int nix=(int)npx, niz=(int)npz;
                    float fnx=npx-nix, fnz=npz-niz;
                    int nidx = niz*bufSize+nix;
                    float h1 = flat[nidx]*(1-fnx)*(1-fnz)+flat[nidx+1]*fnx*(1-fnz)
                             + flat[nidx+bufSize]*(1-fnx)*fnz+flat[nidx+bufSize+1]*fnx*fnz;
                    float dh = (h1-h0)*Math.min(1, h0/fallOff);
                    float cap = Math.max(-dh*spd*wat*capFactor*strength, minCap);
                    if (sed>cap||dh>0) {
                        float dep = dh>0?Math.min(dh,sed):(sed-cap)*depositSpeed;
                        sed-=dep;
                        if (locked == null || !locked[iz][ix]) flat[idx]+=dep*(1-fx)*(1-fz);
                        if (locked == null || !locked[iz][ix+1]) flat[idx+1]+=dep*fx*(1-fz);
                        if (locked == null || !locked[iz+1][ix]) flat[idx+bufSize]+=dep*(1-fx)*fz;
                        if (locked == null || !locked[iz+1][ix+1]) flat[idx+bufSize+1]+=dep*fx*fz;
                    } else {
                        float eroAmt = Math.min((cap-sed)*erodeSpeed, -dh);
                        for (int b=0; b<bn; b++) {
                            int bi = idx+bOff[b];
                            if (bi>=0 && bi<bufSize*bufSize) {
                                int bz = bi / bufSize, bx = bi % bufSize;
                                if (locked != null && locked[bz][bx]) continue;
                                float delta = Math.min(flat[bi], eroAmt*bWgt[b]);
                                flat[bi]-=delta; sed+=delta;
                            }
                        }
                    }
                    fpx=npx; fpz=npz;
                    spd = (float)Math.sqrt(spd*spd + dh*gravity);
                    if (spd<=0) break; wat*=(1-evaporate);
                }
            }
        }
        for (int z=0;z<bufSize;z++) for (int x=0;x<bufSize;x++)
            map[z][x] = clamp(flat[z*bufSize+x], 0f, 1f);
    }

    private static long hashCoarse(int a, int b) {
        long h = a * 0x9e3779b9L + b * 0x9e3779b9L * 31;
        h = (h ^ (h >>> 16)) * 0x85ebca6bL;
        h = h ^ (h >>> 13);
        h = h * 0xc2b2ae35L;
        h = h ^ (h >>> 16);
        return h;
    }

    private static float clamp(float v, float min, float max) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }
}
