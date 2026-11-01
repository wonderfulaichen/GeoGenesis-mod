package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** S2反向顺序/精度与窗口边缘之外样点检查。失败必须非零退出。 */
public final class CoherentShallowCheck {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]);
        List<String> report = new ArrayList<>();
        try {
            segments(); report.add("segment controls: parallel239/241/crossing PASS");
            var baseline = new CoherentRegionDistribution(RegionalMorphGallery.SEED);
            List<String> rows = new ArrayList<>(Files.readAllLines(data.resolve("sites.csv"))); rows.remove(0);
            Collections.reverse(rows);
            var reverse = new ShallowRegionMatcher(RegionalMorphGallery.SEED);
            int count = 0;
            for (String row : rows) {
                String[] v = row.split(","); long key = Long.parseLong(v[0]);
                var cell = CoherentShallowGallery.cell(key); var result = reverse.match(cell);
                require(result.before() == Integer.parseInt(v[3]) && result.after() == Integer.parseInt(v[4]), "order/cold cache drift");
                require(result.before() == baseline.choose(cell).recipe(), "base C drift"); count++;
            }
            require(reverse.cachedPatches() <= 128, "patch cache exceeded bound");
            report.add("independent reverse order/cold cache all sites=" + count + " PASS; bounded patch cache=" + reverse.cachedPatches());
            var fine = new ShallowRegionMatcher(RegionalMorphGallery.SEED, .005, 1);
            int probes = 0;
            for (String row : rows) {
                String[] v = row.split(","); double distance = Double.parseDouble(v[5]);
                if (Integer.parseInt(v[3]) < 18 || Integer.parseInt(v[3]) == 22) continue;
                var result = fine.match(CoherentShallowGallery.cell(Long.parseLong(v[0])));
                require(result.after() == Integer.parseInt(v[4]), "finer geometry changed decision");
                report.add("fineGeometry cell=" + v[0] + " coarseDistance=" + distance + " fineDistance=" + result.distance() + " after=" + result.after());
                probes++;
            }
            require(probes >= 4, "empty fine audit");
            Files.write(data.resolve("check.txt"), report); System.out.println(String.join("\n", report));
            System.out.println("COHERENT SHALLOW CHECK PASS");
        } catch (Throwable t) {
            report.add("FAIL " + t); Files.write(data.resolve("check.txt"), report); t.printStackTrace(); System.exit(1);
        }
    }

    private static void segments() {
        var a = new ShallowRegionMatcher.Segment(0,0,100,0);
        require(a.distance(new ShallowRegionMatcher.Segment(0,239,100,239)) == 239, "parallel239");
        require(a.distance(new ShallowRegionMatcher.Segment(0,241,100,241)) == 241, "parallel241");
        require(a.distance(new ShallowRegionMatcher.Segment(50,-20,50,20)) == 0, "intersection");
        require(a.distance(new ShallowRegionMatcher.Segment(100,0,200,0)) == 0, "touch");
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
