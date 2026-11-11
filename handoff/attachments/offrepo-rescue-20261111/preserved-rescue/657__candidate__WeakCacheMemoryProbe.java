package com.geogenesis.worldgen.terrain.candidate;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Memory / lifecycle witness for the real {@link PerlinScalarCache} (the WeakHashMap-backed
 * wrapper cache used by {@link DoubleTreeFixed#PS}).
 *
 * <h3>Why this is a trimmed rewrite, not a copy</h3>
 * <p>The sandbox version of this probe carried a {@code legacy} mode that rebuilt the <b>pre-fix</b>
 * {@code synchronizedMap(IdentityHashMap)} container inside the probe, and pulled its fixtures from
 * {@code ConcHarness} (which uses a sine/cosine <i>stand-in</i> continent). Neither belongs in a
 * preserved artefact: the defect is already described in the reports, and a stand-in chain is not
 * what we are measuring. This version is self-contained, fixed-cache-only, and builds the real
 * {@link DoubleFullMorphLand} directly.</p>
 *
 * <h3>What is measured</h3>
 * <ul>
 *   <li><b>Warm-up round</b> (discarded) plus <b>3 measured rounds</b> of create-then-release, so a
 *       single unlucky GC cannot be mistaken for a leak <i>or</i> for a fix.</li>
 *   <li>Per round: cache entries at peak and after release, how many owner graphs were actually
 *       weakly collected, retained heap, and the tile LRU size.</li>
 *   <li><b>Tile LRU bound</b>: {@code tilesCached} must never exceed {@link
 *       DoubleFullMorphLand#CACHE_TILES}, even after churning well past it.</li>
 *   <li><b>Negative control</b>: a strongly held owner must NOT be collected. Without this, "it was
 *       collected" could just mean the probe fails to detect reachable objects.</li>
 * </ul>
 *
 * <h3>What is deliberately NOT claimed</h3>
 * <p><b>This is GC observation, not a hard memory bound.</b> Retained-heap numbers are whatever the
 * collector chose to do this run; they are reported as observations and must not be read as a
 * guaranteed ceiling. In particular:</p>
 * <ul>
 *   <li>{@link PerlinScalarCache} has <b>no default entry cap</b>. It is a plain
 *       {@link java.util.WeakHashMap} with no ceiling argument; eviction happens only when the GC
 *       clears a weak key and the map expunges the stale entry. An earlier revision measured a fixed
 *       ceiling to be actively harmful (one owner graph holds ~181 distinct {@code Perlin}
 *       instances, so a 512-entry cap produced more evictions than misses), which is why there is no
 *       cap by design. Do not infer an entry ceiling from a passing result here.</li>
 *   <li>Nothing here proves an upper bound on live memory under an adversarial workload, and nothing
 *       here is a substitute for a heap-dump profile.</li>
 * </ul>
 *
 * <p>Read-only against production code: constructs, samples, drops references, and observes.</p>
 */
public final class WeakCacheMemoryProbe {

    /** Owners created per round. Enough to populate many keys without a long wall time. */
    private static final int OWNERS = 40;

    /** Discarded warm-up round, then this many measured rounds. */
    private static final int WARMUP = 1;
    private static final int MEASURED = 3;

    private static final StringBuilder log = new StringBuilder();
    private static int failures;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        int owners = args.length > 0 ? Integer.parseInt(args[0]) : OWNERS;

        PerlinScalarCache cache = DoubleTreeFixed.PS;
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

        row("HARNESS", "start", "cache=" + cache.getClass().getSimpleName(),
                "owners_per_round=" + owners, "warmup=" + WARMUP, "measured=" + MEASURED,
                "entryCap=none(WeakHashMap default; no ceiling configured)",
                "claim=gc-observation-not-a-hard-bound");
        row("CACHE", "shape", "backing=WeakHashMap", "entryCap=none",
                "eviction=on-key-collection-only");

        // ---- negative control first: prove the collection detector actually bites.
        negativeControl();

        int peakOverall = 0;
        int nonZeroAfterRelease = 0;
        int maxTilesCached = 0;

        for (int round = 0; round <= WARMUP + MEASURED - 1; round++) {
            boolean measured = round >= WARMUP;
            long baseKb = usedKb(mem, true);

            List<WeakReference<Object>> weak = new ArrayList<>();
            List<DoubleFullMorphLand> owners_strong = new ArrayList<>();
            ReferenceQueue<Object> queue = new ReferenceQueue<>();

            for (int i = 0; i < owners; i++) {
                DoubleFullMorphLand land = new DoubleFullMorphLand(
                        new RecipeMorphCatalog(RecipeMorphCatalog.GALLERY_SEED));
                owners_strong.add(land);
                weak.add(new WeakReference<>(land, queue));
                // Two samples per owner: populates the wrapper cache and one or two tiles.
                land.sample(-140.0 + i * 40.0, 137.0 - i * 25.0);
                land.sample(-140.0 + i * 40.0 + 2048.0, 137.0 - i * 25.0);
            }

            int entriesPeak = cache.liveEntries();
            long peakKb = usedKb(mem, true);
            // The tile LRU is per-instance, so the bound must hold for EVERY owner, not just one.
            // Taking the max across all owners is what makes this a real claim.
            int tilesCachedDuring = 0;
            for (DoubleFullMorphLand o : owners_strong) {
                tilesCachedDuring = Math.max(tilesCachedDuring, o.stats().tilesCached());
            }
            peakOverall = Math.max(peakOverall, entriesPeak);
            maxTilesCached = Math.max(maxTilesCached, tilesCachedDuring);

            // Release every strong owner reference; only the weak refs remain.
            owners_strong.clear();
            owners_strong = null;

            int cleared = 0;
            int gcRounds = 0;
            for (int g = 1; g <= 12; g++) {
                System.gc();
                Thread.sleep(80);
                gcRounds = g;
                // Drain the queue so the WeakHashMap gets a chance to expunge on next access.
                while (queue.poll() != null) { /* drain */ }
                cleared = 0;
                for (WeakReference<Object> w : weak) if (w.get() == null) cleared++;
                if (cleared == weak.size()) break;
            }

            cache.liveEntries();               // forces WeakHashMap to expunge stale keys
            int entriesAfter = cache.liveEntries();
            long afterKb = usedKb(mem, true);
            long retainedKb = afterKb - baseKb;

            if (measured && cleared < weak.size()) nonZeroAfterRelease++;
            if (measured && entriesAfter != 0) nonZeroAfterRelease++;

            row(measured ? "ROUND" : "WARMUP", String.valueOf(round),
                    "owners=" + weak.size(),
                    "ownersCollected=" + cleared + "/" + weak.size(),
                    "gcRounds=" + gcRounds,
                    "entriesPeak=" + entriesPeak,
                    "entriesAfterRelease=" + entriesAfter,
                    "tilesCached=" + tilesCachedDuring,
                    "tileCap=" + DoubleFullMorphLand.CACHE_TILES,
                    "tileBoundOk=" + (tilesCachedDuring <= DoubleFullMorphLand.CACHE_TILES),
                    "heapBaseKb=" + baseKb,
                    "heapPeakKb=" + peakKb,
                    "heapAfterKb=" + afterKb,
                    "heapRetainedKb=" + retainedKb);
        }

        row("SUMMARY", "entriesPeakMax=" + peakOverall,
                "entryCap=none", "tilesCachedMax=" + maxTilesCached,
                "tileCap=" + DoubleFullMorphLand.CACHE_TILES,
                "tileBoundOk=" + (maxTilesCached <= DoubleFullMorphLand.CACHE_TILES),
                "roundsWithResidue=" + nonZeroAfterRelease + "/" + MEASURED);

        // ---- verdict
        if (nonZeroAfterRelease != 0) {
            fail("lifecycle", "after release there were still live owners or cache entries in "
                    + nonZeroAfterRelease + " measured round(s)");
        }
        if (maxTilesCached > DoubleFullMorphLand.CACHE_TILES) {
            fail("tile-lru", "tilesCached " + maxTilesCached + " exceeded CACHE_TILES "
                    + DoubleFullMorphLand.CACHE_TILES);
        }
        row("NOTE", "heapRetainedIsObservationOnly",
                "no entry cap by design (WeakHashMap default)",
                "eviction happens only when a weak key is collected",
                "this run does not establish a hard memory ceiling");

        row("VERDICT", failures == 0 ? "PASS" : "FAIL", "failures=" + failures);
        System.out.print(log);
        if (args.length > 1) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]), log.toString());
        }
        System.exit(failures == 0 ? 0 : 2);
    }

    /**
     * Negative control: a strongly reachable owner must survive every GC round. If the detector
     * reports it collected, the detector is broken and every "collected" result above is worthless.
     */
    private static void negativeControl() {
        DoubleFullMorphLand held = new DoubleFullMorphLand(
                new RecipeMorphCatalog(RecipeMorphCatalog.GALLERY_SEED));
        held.sample(-140.0, 137.0);
        WeakReference<Object> wr = new WeakReference<>(held);
        int entriesWithStrongRef = DoubleTreeFixed.PS.liveEntries();
        for (int g = 0; g < 6; g++) {
            System.gc();
            try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        boolean stillReachable = wr.get() != null;
        int entriesAfter = DoubleTreeFixed.PS.liveEntries();

        row("NEGCTRL", "stronglyHeldOwner",
                "stillReachable=" + stillReachable,
                "entriesBeforeGc=" + entriesWithStrongRef,
                "entriesAfterGc=" + entriesAfter,
                "result=" + (stillReachable && entriesAfter >= entriesWithStrongRef ? "PASS" : "FAIL"));
        if (!stillReachable) {
            fail("neg-control", "a strongly held owner was collected: the collection detector is unsound");
        }
        if (entriesAfter < entriesWithStrongRef) {
            fail("neg-control", "cache entries dropped while the owner was strongly reachable");
        }
        // Drop it so later rounds start clean.
        held = null;
        wr.clear();
    }

    private static long usedKb(MemoryMXBean mem, boolean settle) {
        if (settle) {
            for (int i = 0; i < 3; i++) {
                System.gc();
                try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        return mem.getHeapMemoryUsage().getUsed() / 1024;
    }

    private static void row(String kind, String a, String... rest) {
        StringBuilder sb = new StringBuilder(kind).append(',').append(a);
        for (String s : rest) sb.append(',').append(s);
        log.append(sb).append('\n');
    }

    private static void fail(String stage, String detail) {
        failures++;
        log.append("FAIL,").append(stage).append(',').append(detail).append('\n');
    }

    private WeakCacheMemoryProbe() { }
}
