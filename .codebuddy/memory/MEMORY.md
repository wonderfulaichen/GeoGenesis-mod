# GeoGenesis 长期记忆

## 用户方法论偏好（稳定）
- 参考项目优先看原版/鼻祖（TerraForged 0.3.x 是 ReTerraForged 的源）
- 引用参考模组时先完整读透再提方案/写代码
- 知识沉淀必须做、严格先案后码、模块化/单一职责

## 地形重写状态（2026-07-13 完成阶段 1-3）

### 最终范式（用户拍板）
- **统一连续高度场**：单一 `e(x,z)` 海洋/陆地同一场，大陆性 `c` 单一连续噪声场
- **样条控制点=噪声层基面**：mixer 编辑基准，侵蚀后允许偏移
- **实测海平面定海陆**：`e<0` 海洋、`e≥0` 陆地（非 `c<threshold`）
- **continentBias 单滑块**：正=海多、负=陆多，默认 0.0
- **多营力局部侵蚀 v1**：Hydraulic + Coastal + Thermal（局部算子，气候门控）
- **权威设计文档**：`docs/01-架构设计/01-地形重建设计-terrain-rebuild.md`

### 阶段完成状态
- ✅ **阶段 1**：统一连续高度场地形管线（continentBias + 实测海平面 + 生物群系映射）
- ✅ **阶段 2**：RiverField 世界坐标确定性河网 + Hydraulic 河流刻蚀
- ✅ **阶段 3**：多营力侵蚀框架（Coastal 海岸冲刷 + Thermal 坡积软化）
- ⏳ **阶段 4**：mixer 重绑 + River/Erosion 进 Forge Config 6 处同步
- ⏳ **阶段 5**：runClient/runPreview 目检 + 文档终校

### 关键技术决策（2026-07-13）
- **海岸宽度调试**：移除两处 `×1.5` 硬编码，改加法模型 `e=eOcean+eLand`
- **coastWidth 默认值**：0.30 → 0.08 → 0.15（c-space，同步 3 处）
- **材质修复**：`fillTerrainColumn` 用 `floor(height)` 确保草/泥土层正确放置
- **植被装饰控制**：`applyBiomeDecoration` 空操作覆盖，仅保留基础方块
- **侵蚀/河流状态**：`generateChunk` 中 `erosion.apply` + `riverField.apply` 已注释，待地形完善后恢复

## 配置同步铁律（增删字段须同步 6 处）
`GeoGenesisConfig`(定义+BUILDER+preview) + `TerrainParams`/`RiverSettings`(record+defaults) + `GeoGenesisGenerator`(configParams/configRiverSettings) + `GeoGenesisConfigScreen.buildParams` + `run/config/geogenesis-common.toml`

**改 Forge Config 默认值后必须同步已存在的 toml。**

## 关键工程陷阱

### Forge 1.20.1 开发环境
- **dev 运行 = sourceSets.main（official 映射）**：绝对不要把 `build`/`reobfJar` 产出的 reobf jar 拷进 `run/mods/`
- 遇 `f_XXXX_`/`m_XXXX_`（srg 名）的 `NoSuchFieldError/NoSuchMethodError` 时，第一反应是删除 `run/mods/` 里手放的 mod jar
- `NoiseColumn` 在 `net.minecraft.world.level` 包（非 levelgen）
- `Simplex` 需 `seedOffset` 构造参数；构造后必须 `.seed(worldSeed, level)` 填充置换表

### 地形与噪声
- `e→Y 映射必须非对称`：e=0 为海平面锚点（Y=seaLevel=63），避免巨型悬崖
- **⚠️ 海岸线 ≠ c=threshold（已彻底纠正，代码已落地）**：`c=threshold` 只是大陆性噪声的一个参考等值线，**不是海岸线**。海岸线是 `e` 场自然过零的等值线，由海陆两侧连续噪声自然过渡涌现，会自然偏离 `c=threshold`，绝不绑定任何 `c` 的硬阈值。`coastFactor`/`landW`/`coastWidth` **全部移除**（2026-07-13 落地：LandShape 不再乘 coastFactor、CellGenerator 改纯加法模型 `e=eOcean+eLand`），基础地形不含任何"海岸过渡"机制，e 场由连续噪声天然 C0 连续。（旧记忆"海岸 landW 必须以 threshold 为原点"已作废）
- **各类型地形由连续噪声自然合成、彼此平滑过渡、无硬边界**：深海/浅海/平原/丘陵/山脉… 各有自己连续噪声，不可能也不该用硬（绝对）边界去衔接
- **离散图层颜色数组必须与枚举同序同数**：枚举增删须同步 `T_*` 数组与 `biome_colors.json`
- **独立预览不能读 Forge 实时配置**：须用 `GeoGenesisConfig.defaultParams()` 而非 `buildParams()`

### 注册与编解码
- **biome 是动态(datapack)注册表**：`BiomeSource` 解析 `Holder<Biome>` 必须在 CODEC 解码期用 `RegistryOps.retrieveGetter(Registries.BIOME)`
- **⚠️ Forge 1.20.1：dynamic 注册表（biome）解析须用 `ServerLifecycleHooks.getCurrentServer().registryAccess()`**（非 `BuiltInRegistries.BIOME`/`ForgeRegistries.BIOMES`）。群系是 dynamic(datapack) 注册表，已从 `BuiltInRegistries` 移除；`ForgeRegistries.BIOMES` 是 Forge 静态包装，在 `createLevels`→`createBiomes`（世界生成初期）尚未从当前世界同步 → `getHolder(plains)` 返回 empty。正确做法：`server.registryAccess().registryOrThrow(Registries.BIOME).getHolder(key)`（Forge 在 `MinecraftServer` 构造期即 `setCurrentServer`，早于 `createLevels`，故 RegistryAccess 完整）。首次成功时缓存 `Registry<Biome>` 静态字段复用。`getNoiseBiome` 返回 null 会让 null Holder 进 biome palette → 玩家加入世界时 `LinearPalette.getSerializedSize`→`Registry.asHolderIdMap().getId(null)` 空指针崩溃（`Couldn't place player in world`）。
- **自定义 fieldless CODEC 必须用 `RecordCodecBuilder.create`**：禁止 `Codec.unit(x).stable()` 作 dispatch 元素
- CODEC 用 `DeferredRegister.create(Registries.CHUNK_GENERATOR, MODID)`
- **⚠️ 自定义 ChunkGenerator/BiomeSource 的叶子 CODEC 禁止 `.stable()`/`.withLifecycle()`**：`MapCodec.stable()`→`withLifecycle()` 会把 `MapCodec` 包成**非 `MapCodecCodec` 的匿名子类**；而 `ChunkGenerator.CODEC`/`BiomeSource.CODEC` 是 `byNameCodec().dispatchStable(...)`（即 `KeyDispatchCodec`，`assumeMap=false`），派发解码时要求叶子 codec `c instanceof MapCodecCodec`（否则走 `input.get("value")` 分支，字段不存在 → `c.decode(ops, null)` → `getMap(null)` → **"Not a JSON object: null"** → world_preset 解析失败抛 "Missing overworld dimension" → 创建世界崩溃）。`RecordCodecBuilder.create(...)` 直接返回 `MapCodecCodec`，所以叶子 codec **不要加 `.stable()`**。`GeoGenesisBiomeSource.CODEC` 曾因末尾 `.stable()` 导致创建世界崩溃，已修复（2026-07-13）。
- **诊断自定义 codec 必须用 `RegistryOps`**：裸 `JsonOps` 无法访问注册表，派发 codec 解码必失败且报错误导（"Not a JSON object: null"）；正确做法 `RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY))`。（`RegistryAccess.BUILTIN` 在 1.20.1 不存在，用 fromRegistryOfRegistries）

## 工作流决策

### AI 误写代码事件处置（2026-07-13）
- Agent 被要求「更新文档」却误解为继续写地形/河流/侵蚀代码，用户在侵蚀阶段中止
- **决策：保留代码、不回退**（git 历史干净时优先保留工作区代码，盲回退风险更高）
- 原则：git 历史干净时优先保留工作区代码

### 关键编译经验
- `getBaseColumn` 是 Forge backport 抽象方法，必须 override
- 世界预设显示需加入 `minecraft:normal` 标签
- `Seed` 索引用 `&511`（非 `&255`）；2D 数组索引顺序必须一致

### 地形重写权威设计文档
- 实际路径：`docs/01-架构设计/01-地形重建设计-terrain-rebuild.md`（旧 `docs/design/...` 路径已作废）

## 最近修复（2026-07-13 末）

### 海岸建模范式纠正：过程驱动、空间异质、海岸线由 e 场自然涌现（用户拍板，已彻底改写文档）
- **用户核心洞察**：① "现实里也有海边悬崖，我们这样太随便了" —— 之前"看到≈90°就调 coastWidth"是随意的 ② **`c=threshold` 只是大陆性噪声的一个参考等值线，不等于海岸线**。各类型地形（深海/浅海/平原/丘陵/山脉…）各有自己的连续噪声，彼此自然平滑过渡、**不可能用硬（绝对）边界去衔接**。海岸线是 `e` 场自然穿过 0 的零等值线，由海陆两侧噪声自然过渡涌现，**会自然偏离 `c=threshold`，绝不绑定任何 `c` 的硬阈值**。
- **旧误区（全部已作废）**：① 隐含"海岸必须缓坡"错误前提 ② 用全局 uniform coastWidth 控制所有海岸同一坡度（抹杀空间异质）③ `LandShape` 的 `raw *= coastFactor` 把整块陆地高度天花板在海岸乘因子压低（违背统一连续场）④ **（最关键）误把 `c=threshold` 当作海岸线绝对锚点**，用硬阈值/硬因子去对齐或过渡海岸
- **正确范式**：① 海岸线 = `e` 场自然过零的等值线，不绑定 `c=threshold` 硬阈值 ② 各类型地形由连续噪声自然合成、彼此平滑过渡、无硬边界（统一连续场精髓）③ **基础地形不含任何"海岸过渡"机制**——`coastFactor`/`landW`/`coastWidth` **全部移除**，e 场由连续噪声天然 C0 连续 ④ 海岸形态（海蚀崖/海蚀平台/海滩）由 Coastal agent 在连续 e 场上按岩性/抬升/波浪能局部雕琢（与 c 阈值无关）
- **设计文档已彻底修订**：`docs/01-架构设计/01-地形重建设计-terrain-rebuild.md` §1.4 整段重写 + §0.1/§1.1/§1.2/§2.1/§2.3/§5 同步；**代码改动已落地（2026-07-13）**：LandShape.coastFactor/landW 已移除、coastWidth 从 TerrainParams/GeoGenesisConfig 同步清单移除（6→5 处）、CellGenerator 改纯加法模型 `e=eOcean+eLand`；海岸线由 e 场自然过零涌现（不绑定 c 阈值）。Coastal agent 海蚀崖/平台/海滩职责待阶段 3 恢复时接回。

### 海岸宽度调试（历史，已彻底过时）
- **最终认知**：`coastWidth`/`coastFactor`/`landW` **全部移除**（非"重新定位"），基础地形本就由连续噪声天然连续，无海岸过渡旋钮
- **材质与装饰修复**仍有效：`fillTerrainColumn` 用 `floor(height)`；`applyBiomeDecoration` 空操作覆盖（用户要求仅基础方块）

### 断裂溯源与回归
- **断裂根因**：侵蚀/河流引入断裂，基础地形正常
- **处理**：注释掉 `erosion.apply` + `riverField.apply`，专注完善基础地形

### 构建解锁：修复未跟踪重写文件的编译错误（2026-07-13）
- **根因**：`GeoGenesisBiomeSource`/`GeoGenesisGenerator`/`RiverField`/`Cell` 四文件为**未跟踪新文件**（地形重建/误写阶段写入、按"保留不回退"保留），重写不完整导致编译错误，挡住 `runPreview` 验证。
- **五处错误**：① Cell 缺 `riverFloorY`/`riverSurfaceY` 字段（RiverField 写、Generator.fillRiverColumn 读）→ 补 `double` 字段；② BiomeSource 用 `RecordCodecBuilder`/`Optional` 缺 import → 补两 import；③ Generator 调 `gbs.setTerrain` 但 BiomeSource 无该方法 → 补 `terrain` 字段 + `setTerrain` setter；④ `RecordCodecBuilder.create` 泛型塌成 `Object` → 加显式类型见证 `<GeoGenesisBiomeSource>`；⑤ `Optional.empty()` 缺类型见证 → `Optional.<Integer>empty()`。
- **教训**：未跟踪的重写文件易凭"保留不回退"遗留编译错误；每次改动后必须 `gradlew compileJava --rerun-tasks` 验证全量编译，不能只信局部/记忆里的 BUILD SUCCESSFUL（记忆里的成功可能对应更早的已提交状态，未跟踪重写文件当时还不存在）。

## 待办事项
- **阶段 4**：mixer 重绑 + River/Erosion 进 Forge Config 6 处同步
- **阶段 5**：runClient/runPreview 目检 + 文档终校
- **预览文件旧 API 错误**：随阶段 4 mixer 重绑处理
- **植被装饰恢复**：用户明确"后续再恢复"

## 已知问题
- **runPreview 目检海岸异质性待做**：平原应缓岸、山脉应陡贴海（由海洋样条斜率 × 陆地起伏自然决定，无 global 海岸旋钮）
- **陆海比校准待做**：移除 coastFactor 后 eLand 正偏置使海岸线外推（海洋占比或下降），可能需下压 `shallowDepth`/`shelfDepth` 或略降 `hillsHigh`/`plainRough`
- 植被装饰是否完全抑制待验证（`applyBiomeDecoration` 仍空操作）
- 侵蚀/河流系统待地形完善后恢复并调试（当前 `generateChunk` 中已注释）