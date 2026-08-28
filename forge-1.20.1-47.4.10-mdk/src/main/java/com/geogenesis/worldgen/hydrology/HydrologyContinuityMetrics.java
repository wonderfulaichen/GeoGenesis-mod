package com.geogenesis.worldgen.hydrology;

/** 雕刻连续性指标。 */
public record HydrologyContinuityMetrics(int samples, int missing, int invalid,
                                         int maxErosionJumpCount, double maxErosionJump,
                                         int maxWidthJumpCount, double maxWidthJump,
                                         int maxDepthJumpCount, double maxDepthJump,
                                         long hash) {
}
