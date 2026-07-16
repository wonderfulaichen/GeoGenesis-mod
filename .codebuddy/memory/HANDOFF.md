# GeoGenesis 项目交接文档

> 最后更新：2026-07-16 23:30
> 接收人：新对话的 AI 助手
> 交接人：当前会话的 AI 助手

## 当前状态

**Voronoi 区域地形系统已实现并部署（BUILD SUCCESSFUL + runPreview 已启动）。**

**重要**：在此之前的迭代情况：
1. ~~嵌套样条树（SplineTree）~~ → 全部失败，删除
2. ~~FBM 噪声 + 阈值分段（baseElev + 硬阈值）~~ → 椒盐状类型分布，效果差
3. **Voronoi CELL 区域系统** → 当前成功方案

## Voronoi 区域系统架构

```
wx, wz
  ├─ ContinentField → c → HeightCurve.eFromC(c) → eOcean
  ├─ VoronoiRegionField
  │     ├─ 最近 2 个 Voronoi 细胞 → 类型 → TypeGenerator(s)
  │     └─ 高斯距离加权混合 → eLand
  └─ e = clamp(eOcean + eLand, -1, 1)
       └─ heightFromE(e) → Y
       └─ classify(e, eLand, cellType, temp) → TerrainClass
            ├─ e<0 → OCEAN/DEEP_OCEAN
            ├─ e<0.03 → BEACH（海岸薄环）
            ├─ MOUNTAINS + eLand>0.75 → PEAK（峰值叠加）
            ├─ eLand>0.65 + temp<-0.3 → SNOW（雪盖叠加）
            └─ 默认 → Voronoi 细胞类型
```

## 新文件

| 文件 | 作用 |
|------|------|
| `VoronoiRegionField.java` | Voronoi 细胞引擎（确定性哈希、距离加权混合） |
| `TypeLandShape.java` | Voronoi 区域委托层（簿记+种子，已无独立逻辑） |
| `TypeGenerators.java` | 5 个核类型噪声配方（PLAIN/HILLS/MOUNTAINS/PLATEAU/BASIN） |

## 核类型与叠加检测

### Voronoi 细胞类型分布（直接哈希概率）
| 类型 | 概率 | eLand 范围 | Y 范围 | 配方 |
|------|------|-----------|--------|------|
| PLAIN | 30% | [0.015, 0.06] | 67~78 | 单噪声低幅 FBM |
| HILLS | 25% | [0.06, 0.25] | 78~127 | 单噪声中幅 FBM |
| MOUNTAINS | 20% | [0.30, 0.85] | 140~281 | 3 层 FBM + warp + mask |
| PLATEAU | 15% | [0.32, 0.55] | 145~204 | 高位偏置平滑（无内部深谷） |
| BASIN | 10% | [0.015, 0.08] | 67~83 | 反转噪声（凹盆） |

### 叠加检测类型（不作为 Voronoi 细胞）
| 类型 | 触发条件 | 高度来源 |
|------|----------|----------|
| BEACH | e < 0.03（海岸薄环） | 所在细胞类型的最低端 |
| PEAK | MOUNTAINS 细胞 + eLand > 0.75 | MOUNTAINS 生成器高端 |
| SNOW | eLand > 0.65 + temp < -0.3 | 所在细胞类型的高度 |

## 修改文件

| 文件 | 变更 |
|------|------|
| `CellGenerator.java` | 去掉 baseElev、classify 改为 Voronoi 主导类型 + 叠加检测 |
| `Cell.java` | 移除 baseElev 字段 |
| `GeoGenesisTerrain.java` | 注释中 classifyTerrain 签名更新 |

## ⚠️ 关键经验教训

1. **FBM 连续噪声 + 硬阈值不适用于类型分布** → 产生椒盐斑块
2. **Voronoi 细胞区域系统**产生自然的地理区域形状
3. **BEACH/PEAK/SNOW 应作为叠加层**而非独立地形类型——简化生成器，避免类型膨胀
4. **高斯距离加权**比样条插值简单且效果更好
5. **加法模型 e = eOcean + eLand 是稳定基座**——不折腾

## 待办

1. **VoronoiRegionField 参数化**：细胞间距和类型权重目前硬编码，需从 TerrainParams 注入
2. **GeoGenesisConfigScreen**：省滑块需替换为 Voronoi 类型参数滑块（类型权重等）
3. **runClient 目检**：游戏内地形验证
4. **mixer 面板**：调音台需重绑到新类型参数
5. **侵蚀/河流恢复**：`GeoGenesisTerrain.java` 中侵蚀代码仍被注释，需测试后启用

## 工作记忆

- `Working Memory` 文件在 `.codebuddy/memory/`
- 关键设计记录在 `docs/` 目录（需更新）
- git 分支 `backup-before-rewrite`，HEAD = `5e73d6c`
- `LandShape.java` / `TypeMorphology.java` / `TerrainComposer.java` / `ClimateWeights.java` 已删除
- `TypeGenerators.java` 已简化（去掉 PEAK/BEACH/SNOW 生成器）

## 运行命令

```bash
cd forge-1.20.1-47.4.10-mdk
.\gradlew.bat compileJava              # 编译
.\gradlew.bat runPreview --args=12345  # 独立预览
.\gradlew.bat runClient                # 启动游戏
```
