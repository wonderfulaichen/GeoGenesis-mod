package com.geogenesis.worldgen.hydrology;

/** 进程级河流模型开关；默认水文模型（2026-08-27 用户定案：旧 RTF 太假，不再作为默认）。 */
public final class HydrologyExperimentSwitch {
    /** ★ 默认水文模型：用户确认旧 RTF 不符合预期，水文模型为当前生产路径。 */
    private static volatile HydrologyExperimentMode mode = HydrologyExperimentMode.HYDROLOGY_EXPERIMENT;

    private HydrologyExperimentSwitch() { }

    public static HydrologyExperimentMode mode() {
        return mode;
    }

    public static void setMode(HydrologyExperimentMode next) {
        mode = next == null ? HydrologyExperimentMode.HYDROLOGY_EXPERIMENT : next;
    }

    public static boolean hydrologyEnabled() {
        return mode == HydrologyExperimentMode.HYDROLOGY_EXPERIMENT;
    }
}
