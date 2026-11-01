package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** S0/S1有限候选、真实seed分区与宏分布检查；不运行高度或S2/S3。 */
public final class CoherentLayoutCheck {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]); Files.createDirectories(out);
        List<String> report = new ArrayList<>();
        try {
            bounds(report);
            partitionParity(report);
            windowIdentity(report);
            Files.write(out.resolve("check.txt"), report);
            System.out.println(String.join("\n", report));
            System.out.println("COHERENT LAYOUT CHECK PASS");
        } catch (Throwable t) {
            report.add("FAIL " + t); Files.write(out.resolve("check.txt"), report);
            t.printStackTrace(); System.exit(1);
        }
    }

    private static void bounds(List<String> report) {
        int queries = 0, maxCandidates = 0, differentSeed = 0;
        var first = new CoherentRegionDistribution(RegionalMorphGallery.SEED);
        for (long seed : new long[]{RegionalMorphGallery.SEED, -419, 0, 12345}) {
            var d = new CoherentRegionDistribution(seed);
            var r = new Random(715);
            for (int i = 0; i < 1000; i++) {
                double x = (r.nextDouble() * 2 - 1) * 30_000_000;
                double z = (r.nextDouble() * 2 - 1) * 30_000_000;
                var a = d.anchor(x, z, 0); var b = d.anchor(x, z, 8);
                require(a.cellX() == b.cellX() && a.cellZ() == b.cellZ() && a.x() == b.x() && a.z() == b.z(), "candidate omission");
                require(d.choice(a).recipe() >= 0 && d.choice(a).recipe() < 23, "recipe range");
                if (seed != RegionalMorphGallery.SEED && !d.choice(a).equals(first.choice(first.anchor(x, z, 0)))) differentSeed++;
                maxCandidates = Math.max(maxCandidates, a.candidates()); queries++;
            }
        }
        require(differentSeed > 0, "seed ignored");
        report.add("macro finite bound vs expanded brute force: queries=" + queries + ", maxCandidates=" + maxCandidates
                + ", seedDifferent=" + differentSeed + "; query domain +/-30000000");
    }

    private static void partitionParity(List<String> report) {
        var cat = new RecipeMorphCatalog(RegionalMorphGallery.SEED);
        int points = 0;
        for (float scale : new float[]{1200,600}) {
            var old = LocalBoundaryGeometry.of(cat.original(), -512, -512, 728, 4, .02, scale);
            var fresh = LocalBoundaryGeometry.of(CoherentRegionDistribution.microPartition(cat.seed(), scale), -512, -512, 728, 4, .02);
            for (int z = -900; z < -100; z += 40) for (int x = -900; x < -100; x += 40) {
                var a = old.exactWinner(x, z); var b = fresh.exactWinner(x, z);
                require(a.key() == b.key() && a.vx() == b.vx() && a.vz() == b.vz(), "explicit seed partition drift");
                points++;
            }
            require(old.edges().size() == fresh.edges().size(), "geometry edge topology drift");
        }
        report.add("explicit seed context vs original Gallery geometry points=" + points + ": bitwise winner/site parity");
    }

    private static void windowIdentity(List<String> report) {
        var d = new CoherentRegionDistribution(RegionalMorphGallery.SEED);
        var a = LocalBoundaryGeometry.of(CoherentRegionDistribution.microPartition(d.seed(), 600), 512, 512, 728, 4, .02);
        var b = LocalBoundaryGeometry.of(CoherentRegionDistribution.microPartition(d.seed(), 600), 1536, 512, 728, 4, .02);
        List<LocalBoundaryGeometry.Site> reverse = new ArrayList<>(b.sites()); Collections.reverse(reverse);
        int overlap = 0;
        var independent = new CoherentRegionDistribution(d.seed());
        for (var sa : a.sites()) for (var sb : reverse) if (sa.key() == sb.key()) {
            require(d.choose(sa).equals(independent.choose(sb)), "window or traversal influences identity"); overlap++;
        }
        require(overlap > 0, "no identity overlap");
        int clustered = 0, total = 0;
        for (int z = -12; z <= 12; z++) for (int x = -12; x <= 12; x++) {
            double[] p = a.siteXZ(x, z), q = a.siteXZ(x + 1, z);
            var sa = new LocalBoundaryGeometry.Cell(x, z, p[0], p[1], 0);
            var sb = new LocalBoundaryGeometry.Cell(x + 1, z, q[0], q[1], 0);
            var ca = d.choose(sa); var cb = d.choose(sb);
            if (ca.anchor().cellX() == cb.anchor().cellX() && ca.anchor().cellZ() == cb.anchor().cellZ()) clustered++;
            total++;
        }
        require(clustered > 0 && clustered < total, "missing shared or distinct macro anchors");
        report.add("independent reverse cross-window site identities=" + overlap + "; neighboring pairs sameAnchor=" + clustered + "/" + total);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
