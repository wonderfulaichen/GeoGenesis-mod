package com.geogenesis.worldgen.core;

import java.util.concurrent.atomic.LongAdder;

/** Observation only; owned by one ProcessContext, never consulted by terrain/cache decisions.
 * Exact outcome counts use the Entry read by sampleE (not a later cache reread).
 * Repeat tracking deterministically selects hash(raw x bits, raw z bits) & 63 == 0 (nominal
 * 1/64 of coordinates, NOT 1/64 of calls), then retains at most 1024 coordinates. No unbiased
 * extrapolation is claimed: retained repeats/overlaps are lower bounds, NOT collision waste.
 * Repeat means a previous computation START was retained, even if that computation failed.
 * Overlap means an earlier observed miss-to-publication interval has not ended, not CPU overlap
 * or proof that coalescing would save time. Both scopes share the tracker; counters attribute
 * the later start to its own scope. Active buckets cannot be evicted. No waiting for terrain
 * computations or value reuse occurs. Other-scope LongAdder snapshots are weakly consistent;
 * finished tile counts are merged exactly once, while unfinished tile counts are excluded.
 */
public final class BaseCacheDiagnostics {
    public static final int HIT = 0, EMPTY = 1, CONFLICT = 2, BYPASS = 3;
    private static final int SAMPLED = 4, REPEAT = 5, OVERLAP = 6, DROPPED = 7, FAILED = 8;
    private static final String[] NAMES = {"hit", "missEmpty", "missConflict", "bypass",
            "sampledCompute", "retainedRepeat", "overlapStart", "trackerDrop", "computeFailed"};
    private final Bucket[] buckets = new Bucket[1024];
    private final LongAdder[] tile = adders(), other = adders();
    private long tiles;

    public BaseCacheDiagnostics() {
        for (int i = 0; i < buckets.length; i++) buckets[i] = new Bucket();
    }
    private static LongAdder[] adders() {
        LongAdder[] result = new LongAdder[NAMES.length];
        for (int i = 0; i < result.length; i++) result[i] = new LongAdder();
        return result;
    }
    /** Task-local; explicit parameter means nested/non-input callers cannot inherit this scope. */
    public static final class Input {
        private final long[] counts = new long[NAMES.length];
    }
    public Input input() { return new Input(); }
    private void count(Input input, int index) {
        if (input == null) other[index].increment();
        else input.counts[index]++;
    }
    public void outcome(Input input, int outcome) { count(input, outcome); }
    private static long hash(long x, long z) {
        long h = x * 0x9E3779B97F4A7C15L + Long.rotateLeft(z, 27);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
    /** Token zero = not tracked. Counts computation starts, including later failures. */
    public int begin(Input input, long x, long z) {
        long hash = hash(x, z);
        if ((hash & 63) != 0) return 0;
        count(input, SAMPLED);
        int slot = (int) (hash >>> 6) & (buckets.length - 1);
        Bucket b = buckets[slot];
        synchronized (b) {
            boolean same = b.seen && b.x == x && b.z == z;
            if (!same && b.active != 0) {
                count(input, DROPPED);
                return 0;
            }
            if (same) {
                count(input, REPEAT);
                if (b.active != 0) count(input, OVERLAP);
            } else {
                b.x = x;
                b.z = z;
                b.seen = true;
            }
            b.active++;
        }
        return slot + 1;
    }
    public void end(Input input, int token, boolean success) {
        if (!success) count(input, FAILED);
        if (token == 0) return;
        Bucket b = buckets[token - 1];
        synchronized (b) { b.active--; }
    }
    /** One batch merge per input, including partial/cancelled inputs. Never called per point. */
    public synchronized String finish(Input input) {
        for (int i = 0; i < tile.length; i++) tile[i].add(input.counts[i]);
        return "batch=" + (tiles + 1) + " tiles=" + ++tiles + " input={" + format(input.counts) + "} contextTile={"
                + format(snapshot(tile)) + "} contextOther={" + format(snapshot(other)) + "}"
                + " repeatPolicy=hash1/64,buckets1024,retained-lower-bound"
                + " cumulative=finished-inputs+live-other";
    }
    public record Counts(long calls, long pointHits, long rawComputations, long failures) {}
    public Counts inputCounts(Input input) { return counts(input.counts); }
    public Counts tileCounts() { return counts(snapshot(tile)); }
    private static Counts counts(long[] values) {
        long raw = values[EMPTY] + values[CONFLICT] + values[BYPASS];
        return new Counts(raw + values[HIT], values[HIT], raw, values[FAILED]);
    }
    public String inputSummary(Input input) { return format(input.counts); }
    public String otherSummary() { return format(snapshot(other)); }
    private static long[] snapshot(LongAdder[] counters) {
        long[] result = new long[counters.length];
        for (int i = 0; i < result.length; i++) result[i] = counters[i].sum();
        return result;
    }
    private static String format(long[] values) {
        StringBuilder text = new StringBuilder("total=")
                .append(values[HIT] + values[EMPTY] + values[CONFLICT] + values[BYPASS]);
        for (int i = 0; i < values.length; i++) text.append(' ').append(NAMES[i]).append('=').append(values[i]);
        return text.toString();
    }
    private static final class Bucket {
        long x, z;
        int active;
        boolean seen;
    }
}
