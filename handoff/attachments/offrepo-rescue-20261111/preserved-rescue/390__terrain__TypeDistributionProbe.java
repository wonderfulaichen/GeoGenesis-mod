package com.geogenesis.worldgen.terrain;

/** 临时诊断：纯加法方案（无 mask/fade/clamp，仅 cAffinity β=5 分化类型） */
public final class TypeDistributionProbe {
    private TypeDistributionProbe() {}
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        ContinentField cf = new ContinentField(p);
        HeightCurve hc = new HeightCurve(p, -64, 320);
        TypeLandShape tls = new TypeLandShape(p);
        cf.seed(seed); tls.seed(seed);

        double bias = p.continentBias();

        // Quick calibration with different biases
        for (double tb : new double[]{0.20, 0.50, 1.00, 1.50}) {
            int l = 0, t = 0;
            for (int wx = -600; wx < 600; wx += 10) {
                for (int wz = -600; wz < 600; wz += 10) {
                    double c = cf.sample(wx, wz);
                    double cB = c - tb;
                    double eBase = hc.eFromC(cB);
                    var blend = tls.sampleBlend(wx, wz);
                    double eL = tls.sample(blend, wx, wz);
                    eL += cB < 0 ? 0 : (cB * 0.08 > 0.15 ? 0.15 : cB * 0.08);
                    if (eBase + eL > 0) l++;
                    t++;
                }
            }
            System.out.printf("bias=%.2f: %d/%d = %.1f%% (pure-add)%n", tb, l, t, l*100.0/t);
        }

        System.out.println("\n--- Full scan ---");
        bias = p.continentBias();
        int scanSize = 4000, step = 10;
        int landCount = 0, total = 0;
        int[] typeCounts = new int[TerrainClass.COUNT];
        for (int wx = -scanSize/2; wx < scanSize/2; wx += step) {
            for (int wz = -scanSize/2; wz < scanSize/2; wz += step) {
                double c = cf.sample(wx, wz);
                double cB = c - bias;
                double eBase = hc.eFromC(cB);
                var blend = tls.sampleBlend(wx, wz);
                double eL = tls.sample(blend, wx, wz);
                eL += cB < 0 ? 0 : (cB * 0.08 > 0.15 ? 0.15 : cB * 0.08);
                double e = eBase + eL;
                if (e > 0) {
                    landCount++;
                    var dt = TypeLandShape.dominantFromWeights(blend.typeWeights);
                    typeCounts[dt.ordinal()]++;
                }
                total++;
            }
        }
        System.out.printf("bias=%.2f: Ocean=%d (%.1f%%) Land=%d (%.1f%%) [pure-add, β=5]%n",
            bias, total-landCount, (total-landCount)*100.0/total, landCount, landCount*100.0/total);
        System.out.println("Land types:");
        int[] ids = {TerrainClass.PLAIN.ordinal(), TerrainClass.HILLS.ordinal(),
            TerrainClass.MOUNTAINS.ordinal(), TerrainClass.PLATEAU.ordinal()};
        String[] names = {"PLAIN","HILLS","MOUNTAINS","PLATEAU"};
        for (int i = 0; i < ids.length; i++) {
            int n = typeCounts[ids[i]];
            if (n > 0) System.out.printf("  %-10s: %5d (%.1f%% of land)%n", names[i], n, n*100.0/landCount);
        }
    }
}
