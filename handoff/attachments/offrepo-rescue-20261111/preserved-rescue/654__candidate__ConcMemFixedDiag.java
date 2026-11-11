package com.geogenesis.worldgen.terrain.candidate;
import java.util.*;
public final class ConcMemFixedDiag {
  static long rss() throws Exception {
    var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
    var m = os.getClass().getMethod("getCommittedVirtualMemorySize");
    return 0;
  }
  public static void main(String[] a) throws Exception {
    int n = Integer.parseInt(a[0]);
    java.lang.management.MemoryMXBean bean = java.lang.management.ManagementFactory.getMemoryMXBean();
    for (int round = 1; round <= 4; round++) {
      long base = bean.getHeapMemoryUsage().getUsed()/1024;
      List<java.lang.ref.WeakReference<Object>> refs = new ArrayList<>();
      List<DoubleFullMorphLand> owners = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        DoubleFullMorphLand l = ConcHarness.buildLand();
        owners.add(l); refs.add(new java.lang.ref.WeakReference<>(l));
        l.sample(-140 + i * 40.0, 137 - i * 25.0);
      }
      long peak = bean.getHeapMemoryUsage().getUsed()/1024;
      owners.clear(); owners = null;
      int cleared = 0;
      for (int g = 0; g < 10; g++) { System.gc(); Thread.sleep(80);
        cleared = 0; for (var r : refs) if (r.get()==null) cleared++; if (cleared==refs.size()) break; }
      long after = bean.getHeapMemoryUsage().getUsed()/1024;
      System.out.printf(Locale.ROOT, "mem round=%d owners=%d baseUsedKB=%d peakUsedKB=%d afterUsedKB=%d retainedKB=%d cleared=%d/%d entriesAfter=%d%n",
        round, n, base, peak, after, after-base, cleared, refs.size(), DoubleTreeFixed.PS.liveEntries());
    }
  }
}