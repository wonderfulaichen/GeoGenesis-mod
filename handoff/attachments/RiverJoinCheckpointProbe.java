package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;
import java.util.List;

/** Characterizes an unfinished checkpoint; PASS means the known defect reproduced, not acceptance. */
public final class RiverJoinCheckpointProbe {
    public static void main(String[] args) {
        var downstream = new DeterministicSkeleton.Trace(new int[]{11, 21, 31, 41, 51},
                DeterministicSkeleton.End.MOUTH_SEA, false);
        var upstream = new DeterministicSkeleton.Trace(new int[]{13, 22, 31},
                DeterministicSkeleton.End.JOIN, false);
        var rivers = DeterministicSkeleton.buildRivers(List.of(downstream, upstream), 10);
        int[] observed = rivers.get(0).cells;
        int[] knownDefect = {13, 22, 31, 11, 21, 31, 41, 51};
        int[] requiredSuffix = {13, 22, 31, 41, 51};
        if (rivers.size() != 1 || !Arrays.equals(observed, knownDefect)
                || Arrays.equals(observed, requiredSuffix)) {
            throw new AssertionError("Checkpoint behavior changed; update characterization and acceptance separately");
        }
        System.out.println("KNOWN_DEFECT_REPRODUCED middle-JOIN=" + Arrays.toString(observed));
        System.out.println("REQUIRED_SUFFIX=" + Arrays.toString(requiredSuffix));
        System.out.println("RIVER_CONTINUITY_ACCEPTANCE=FAIL; production channel still consumes traces");
    }
}
