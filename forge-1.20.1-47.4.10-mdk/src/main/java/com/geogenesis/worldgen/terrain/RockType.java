package com.geogenesis.worldgen.terrain;

/**
 * 岩性类型（★ 2026-09-12，地质系统 Phase T4）。
 *
 * <p>覆盖主要地质岩性，按<b>成因</b>分三类（岩浆岩 / 沉积岩 / 变质岩），
 * 各类内部按成分或变质程度排列。这是"地层"系统的岩性维度。
 *
 * <p><b>与构造环境的关系</b>（本项目的核心设计）：岩性不是随机分配的，
 * 而由 {@link TectonicField} 判定的构造环境决定（见 {@link StratumField}）：
 * <ul>
 *   <li>克拉通/地盾 → {@link #GNEISS} {@link #GRANITE}（古老结晶基底）</li>
 *   <li>造山带 → {@link #SCHIST} {@link #GNEISS}（区域变质）</li>
 *   <li>裂谷盆地 → {@link #SANDSTONE} {@link #SHALE} {@link #LIMESTONE}（沉积充填）</li>
 *   <li>火山弧/洋壳 → {@link #BASALT} {@link #ANDESITE}（火山岩）</li>
 * </ul>
 *
 * <p><b>注意</b>：Phase T4 仅建立<b>数据层</b>（岩性标签、供预览图层与后续地质系统消费），
 * <b>尚未</b>让岩性影响地貌（"软岩成谷、硬岩成脊"需与侵蚀耦合，属后续阶段）。
 */
public enum RockType {

    // ===== 变质岩（metamorphic）：深 → 浅变质程度 =====
    /** 片麻岩：高级区域变质，克拉通基底典型岩性。 */
    GNEISS,
    /** 片岩：中级区域变质，造山带常见。 */
    SCHIST,

    // ===== 岩浆岩·侵入（intrusive） =====
    /** 花岗岩：深成侵入体，造山带核部/克拉通常见。 */
    GRANITE,

    // ===== 沉积岩（sedimentary）：按粒径由粗到细 =====
    /** 砂岩：粗粒碎屑沉积，盆地边缘/河流相。 */
    SANDSTONE,
    /** 页岩：细粒碎屑沉积，盆地深水相。 */
    SHALE,
    /** 石灰岩：化学/生物沉积，浅海台地相。 */
    LIMESTONE,

    // ===== 岩浆岩·喷出（volcanic） =====
    /** 玄武岩：基性喷出岩，洋壳与裂谷典型。 */
    BASALT,
    /** 安山岩：中性喷出岩，俯冲带火山弧典型。 */
    ANDESITE;

    /** 岩石大类（供图例分组/着色）。 */
    public enum Kind {
        /** 变质岩 */ METAMORPHIC,
        /** 侵入岩 */ INTRUSIVE,
        /** 沉积岩 */ SEDIMENTARY,
        /** 喷出岩 */ VOLCANIC
    }

    /** 所属大类。 */
    public Kind kind() {
        return switch (this) {
            case GNEISS, SCHIST -> Kind.METAMORPHIC;
            case GRANITE -> Kind.INTRUSIVE;
            case SANDSTONE, SHALE, LIMESTONE -> Kind.SEDIMENTARY;
            case BASALT, ANDESITE -> Kind.VOLCANIC;
        };
    }

    /** 地质学硬度序（越大越抗蚀，供后续"软岩成谷、硬岩成脊"使用）。 */
    public double resistance() {
        return switch (this) {
            case GNEISS -> 0.85;      // 高级变质，很硬
            case GRANITE -> 0.90;     // 侵入岩，最硬
            case SCHIST -> 0.55;      // 片理发育，易沿片理剥蚀
            case SANDSTONE -> 0.65;
            case SHALE -> 0.30;       // 细粒软弱，最易蚀
            case LIMESTONE -> 0.50;   // 可溶（岩溶）
            case BASALT -> 0.80;
            case ANDESITE -> 0.70;
        };
    }
}
