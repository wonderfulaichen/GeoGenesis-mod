---
name: fix-coast-cliff-and-land-elevation
overview: 修复海岸悬崖断裂和陆地整体抬升两个问题，核心是调整 blendE 海岸过渡宽度和 landShape 低海拔映射。
todos:
  - id: "1"
    content: 重写 CellGenerator.blendE 海岸过渡（COAST_WIDTH 0.15→0.30 + HEIGHT_E 末端→0.04 + eLandCoast=eLand×landW²）
    status: pending
  - id: "2"
    content: 调整 HeightCurve.landShape 低段控制点（XS/YS 压平 e∈[0,0.10]段）
    status: pending
  - id: "3"
    content: compileJava 编译验证
    status: pending
    dependencies:
      - "1"
      - "2"
---

## 用户需求

1. **海岸过渡断裂**（截图1）：海岸线与陆地之间有明显垂直悬崖（水面上方 4-5 格的石头垂直面），不是平缓的海滩过渡。水直接拍在悬崖上。

2. **陆地整体抬升**（截图2，用户标注为"之前就有的bug"）：平原/盆地区域整体高出海平面 4-5 格，看起来像被抬升了。Minecraft 原版平原应紧贴海平面（y≈64）。

## 根因分析

### 问题1：海岸悬崖

`CellGenerator.blendE` 的 lerp 逻辑：`e = lerp(baseE, eLand, landW)`

- `landW = smoothstep(c/0.15)`，当 c=0.05（约 83 格离岸）时 landW≈0.28
- `eLand`（即使 plainBase=0.02，丘陵/高原区域可达 0.12-0.3）被 lerp 按 0.28 权重拉入
- 结果 e 跳到 0.08+ → landShape(0.08) → Y≈70
- 而海洋侧 c=-0.01 时 baseE≈-0.12 → Y≈56
- 14 格落差在 1-2 格内完成 → 视觉悬崖

### 问题2：陆地抬升

`landShape` 控制点 XS={0.00,0.04,...} YS={0.00,0.06,...}

- plainBase=0.02 → landShape(0.02)≈0.03 → Y = 63+0.03×167 ≈ 68（+5格）
- MC 原版平原 y=64，这里高出 4 格

## 技术方案

### Part A：重写 blendE 海岸过渡

**核心思路**：eLand 在过渡带内必须被有效压制——用 `landW²` 代替直接 lerp，使近岸贡献四阶趋零。

**修改文件**：`CellGenerator.java`

1. `COAST_WIDTH` 0.15 → 0.30（过渡带从 ~250 格加宽到 ~500 格）
2. `HEIGHT_C` 末端 0.15 → 0.30；`HEIGHT_E` 末端 0.12 → 0.04（c=0.3 时 baseE=0.04，接近海平面）
3. `blendE` 核心改为：

```java
double landW = smoothstep(NoiseUtil.clamp(c / COAST_WIDTH, 0.0, 1.0));
double eLandCoast = eLand * landW * landW;  // 近岸压制：landW² 衰减
double e = NoiseUtil.lerp(baseE, eLandCoast, landW);
```

**效果**：

- c=0.01（~17 格离岸）：landW≈0.004，landW²≈0.00002 → eLand 贡献 ≈0 → e≈baseE≈0（海平面）
- c=0.05（~83 格）：landW≈0.067，landW²≈0.004 → eLand 贡献 ≈0.001 → e≈0.005（仍近海平面）
- c=0.15（~250 格）：landW=1，landW²=1 → eLand 全额参与

### Part B：调整 landShape 低段控制点

**修改文件**：`HeightCurve.java`

将 landShape 的 XS/YS 从：

```
XS = {0.00, 0.04, 0.28, 0.52, 0.78, 1.00}
YS = {0.00, 0.06, 0.24, 0.50, 0.78, 1.00}
```

改为：

```
XS = {0.00, 0.10, 0.30, 0.55, 0.80, 1.00}
YS = {0.00, 0.02, 0.22, 0.52, 0.82, 1.00}
```

**效果**：

| e 值 | 旧 YS 映射 | 新 YS 映射 | 旧世界Y | 新世界Y |
| --- | --- | --- | --- | --- |
| 0.02 (plainBase) | 0.03 | 0.004 | 68 (+5) | 64 (+1) |
| 0.03 (basinBase) | 0.045 | 0.006 | 71 (+8) | 64 (+1) |
| 0.12 (hillsLow) | 0.06 | 0.03 | 73 (+10) | 68 (+5) |
| 0.30 | 0.24 | 0.22 | 103 | 100 |
| 1.00 | 1.00 | 1.00 | 230 | 230 |


### Implementation Notes

- `landEToWorldY` / `landWorldYToE` 是通用转换函数，引用 `landShape`，控制点更新后自动适配，无需改
- `ConfigBinding` 中陆地 setter 的 `landWorldYToE` 同理自动适应
- `blendE` 变更影响 `computeShape`、`shapeE`、`landHeight` 三处调用——它们都调用同一个 `blendE` 方法，改一处即全部生效
- `HEIGHT_E` 末端改为 0.04 后，baseE 在 c=0.3 处为 0.04（≈海平面+7格），而此时 landW=1 eLand 全额参与（平原 0.02→Y≈64），两者衔接自然
- 海洋侧（c<0）不受影响：baseE 由样条决定，eLandCoast 因 landW=0 而为 0

## Directory Structure

```
forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/
├── CellGenerator.java    [MODIFY] blendE 重写 + COAST_WIDTH + HEIGHT_C/HEIGHT_E
└── HeightCurve.java      [MODIFY] landShape XS/YS 控制点更新
```

# Agent Extensions

（无需扩展）