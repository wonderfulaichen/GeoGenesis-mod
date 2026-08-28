package com.geogenesis.worldgen.hydrology;

/** 验证实验模式默认安全、切换可复现且不改动旧模式行为。 */
public final class HydrologyModeProbe {
    private HydrologyModeProbe() { }

    public static void main(String[] args) {
        HydrologyExperimentSwitch.setMode(null);
        boolean defaultSafe = HydrologyExperimentSwitch.mode() == HydrologyExperimentMode.LEGACY_RTF
                && !HydrologyExperimentSwitch.hydrologyEnabled();
        HydrologyExperimentSwitch.setMode(HydrologyExperimentMode.HYDROLOGY_EXPERIMENT);
        boolean enabled = HydrologyExperimentSwitch.hydrologyEnabled();
        HydrologyExperimentSwitch.setMode(HydrologyExperimentMode.LEGACY_RTF);
        boolean restored = !HydrologyExperimentSwitch.hydrologyEnabled();
        System.out.println("=== HydrologyModeProbe ===");
        System.out.println("defaultSafe=" + defaultSafe);
        System.out.println("experimentEnabled=" + enabled);
        System.out.println("legacyRestored=" + restored);
        System.out.println("status=" + (defaultSafe && enabled && restored ? "PASS" : "FAIL"));
    }
}
