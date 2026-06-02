# GeoGenesis 地形侵蚀系统 - 开发报告

## 一、项目文件结构

### 测试工具（独立Java程序，用于快速迭代验证）
```
erosion-test-tool/
├── src/com/erosiontest/
│   ├── ErosionPipeline.java   ← 主程序：地形生成 + 多尺度侵蚀 + 可视化
│   ├── Noise.java             ← 噪声生成器
│   └── Erosion.java           ← 旧侵蚀引擎（已弃用，保留备用）
├── output/                    ← 输出图像目录
└── DEV_REPORT.md              ← 本文件
```

### 模组（Minecraft Forge 1.20.1）
```
forge-1.20.1-47.4.10-mdk/
└── src/main/java/com/geogenesis/
    ├── GeoGenesis.java                                    ← 主Mod类
    ├── config/GeoGenesisConfig.java                       ← 配置文件
    ├── client/GeoGenesisConfigScreen.java                 ← 配置界面
    └── worldgen/
        ├── GeoGenesisGenerator.java                       ← 世界生成器入口
        ├── TerrainCache.java                              ← 地形缓存（关键：区域管理）
        ├── HeightmapPreview.java                          ← 高度图预览
        ├── NoiseEngine.java                               ← 噪声引擎
        ├── erosion/
        │   └── ErosionEngine.java                         ← 侵蚀引擎（核心算法）
        ├── climate/ClimateSystem.java                     ← 气候系统
        ├── geology/GeologySystem.java                     ← 地质系统
        └── hydrology/HydrologySystem.java                 ← 水文系统
```

---

## 二、核心算法：多尺度水力侵蚀管道

### 总体架构

侵蚀分为三个尺度（粗→中→细），逐级叠加，形成从主河谷到细支流的完整沟壑网络：

```
原始地形 → 降采样 → 粗侵蚀（大笔刷挖主谷） → 上采样 → 
          → 中侵蚀（中笔刷挖支流） → 
          → 上采样 → 细侵蚀（小笔刷挖三级分支） → 降采样 → 最终地形
```

### 核心函数：`terraforgedErosion`

文件：`erosion-test-tool/src/com/erosiontest/ErosionPipeline.java` 第335行

```java
static void terraforgedErosion(float[][] map, int size,
    int drops, float strength, int radius, float fallOff,
    float inertia, float gravity,
    float erodeSpeed, float depositSpeed)
```

这是基于TerraForged的**圆形笔刷水力侵蚀算法**，关键参数：

| 参数 | 作用 | 典型值 |
|------|------|--------|
| `drops` | 粒子总数 | 52500~225000 |
| `radius` | 圆形笔刷半径 | 6~14 |
| `inertia` | 粒子惯性(0=完全沿梯度, 1=永不转向) | 0.001~0.005 |
| `gravity` | 重力加速度 | 2.5~3.5 |
| `capFactor` | 容沙系数(越大山谷刻越深) | 10 |
| `fallOff` | 高度衰减阈值 | 0.15~0.5 |
| `erodeSpeed` | 侵蚀速度 | 0.2~0.3 |
| `depositSpeed` | 沉积速度(远小于侵蚀速度) | 0.02~0.05 |
| `evaporate` | 蒸发率 | 0.35 |

### 粒子生命周期（一个drop）

```
1. 随机位置生成粒子
2. 计算该位置的梯度（双线性插值）
3. 方向 = 惯性 * 旧方向 + (1-惯性) * (-梯度方向)
4. 沿方向移动1个单位
5. 计算新位置高度差 dh
6. 容沙量 cap = max(-dh * speed * water * capFactor, minCap)
7. 如果 sed > cap 或 dh > 0:
     沉积: dep = (sed - cap) * depositSpeed
     散布到4个角点（双线性权重）
   否则:
     侵蚀: 使用圆形笔刷从周围节点挖土
     挖土量 = min((cap - sed) * erodeSpeed, -dh)
8. 更新速度: speed = sqrt(speed² + dh * gravity)
9. 更新水量: water *= (1 - evaporate)
10. 重复步骤2~9，最多60步
```

### 圆形笔刷

侵蚀时不是只挖一个点，而是影响周围圆形区域内的所有像素：

```
权重 = 1 - (到中心距离 / 半径)
总权重归一化 = 每个像素权重 / 权重总和
```

---

## 三、三尺度参数配置

### 测试工具（最终版）
文件：`erosion-test-tool/src/com/erosiontest/ErosionPipeline.java` 第28-67行

| 层级 | 分辨率 | 像素对应世界 | 粒子数 | 强度 | 笔刷半径 | fallOff | inertia | gravity | erodeSpeed | depositSpeed |
|------|--------|------------|--------|------|---------|---------|---------|---------|-----------|-------------|
| 粗 | 128×128 | 8方块/px | 52500 | 1.2 | **14** | 0.5 | 0.001 | 2.5 | 0.3 | 0.05 |
| 中 | 256×256 | 4方块/px | 112500 | 1.5 | **10** | 0.3 | 0.002 | 3.0 | 0.25 | 0.03 |
| 细 | 512×512 | 2方块/px | 225000 | 2.0 | **6** | 0.15 | 0.005 | 3.5 | 0.2 | 0.02 |

**总粒子数：约39万**
**覆盖世界范围：1024×1024 方块**

### 模组（最终版）
文件：`forge-1.20.1-47.4.10-mdk/.../erosion/ErosionEngine.java` 第15-70行

| 层级 | 分辨率 | 像素对应世界 | 粒子数 | 强度 | 笔刷半径 | fallOff | inertia | gravity | erodeSpeed | depositSpeed |
|------|--------|------------|--------|------|---------|---------|---------|---------|-----------|-------------|
| 粗 | 40×40 | 4方块/px | 22500 | 1.2 | **14** | 0.5 | 0.001 | 2.5 | 0.3 | 0.05 |
| 中 | 160×160 | 1方块/px | 37500 | 1.5 | **10** | 0.3 | 0.002 | 3.0 | 0.25 | 0.03 |
| 细 | 320×320 | 0.5方块/px | 75000 | 2.0 | **6** | 0.15 | 0.005 | 3.5 | 0.2 | 0.02 |

**总粒子数：约13.5万**
**覆盖区域：128×128 方块（8×8区块 + padding）**

### 模组区域配置
文件：`forge-1.20.1-47.4.10-mdk/.../TerrainCache.java` 第14-18行

```java
REGION_SIZE = 8;        // 8×8 区块为一个区域
REGION_BLOCK = 128;     // 128 方块
REGION_PAD = 16;        // 16 方块 padding（防边缘不连续）
REGION_BUF = 160;       // 128 + 16*2 = 160 像素缓冲区
```

---

## 四、关键改进记录

### v1 边缘过渡（解决5×5区块断裂）
- 在TerrainCache中添加跨区域双线性插值混合
- 侵蚀区域增加padding（镜像扩展边缘）
- 侵蚀后裁剪回原大小

### v2 连续梯度（解决45°斜线）
- **问题**：D8离散方向导致粒子只能沿8个方向移动
- **修复**：改用双线性插值计算连续梯度方向
- 不再判断8个邻居，而是用 `gx = (hNE-hNW)*(1-fy) + (hSE-hSW)*fy`

### v3 圆形笔刷（解决沟壑宽度一致）
- **问题**：单点侵蚀导致沟壑太窄且深浅一致
- **修复**：TerraForged风格的圆形笔刷侵蚀

### v4 惯性系统（解决山脊线被切断）
- **问题**：粒子精确沿梯度方向，在山顶汇聚刻穿山脊
- **修复**：添加inertia参数，粒子保留部分旧方向动量
- 山脊顶部的水流保持分散 → 山脊线保留

### v5 三级侵蚀管道（解决只有2级分支）
- 128粗尺→ 256中尺→ 512细尺，逐级上采样+侵蚀
- 每级使用不同的笔刷半径、fallOff和inertia

### v6 edge padding（解决边缘柱子和区域平台）
- 每级侵蚀前用镜像padding扩展网格
- 侵蚀后裁剪回原大小
- 边界数据来自"镜像扩展区"的自然侵蚀，与内部无缝衔接

### v7 erodeSpeed/depositSpeed分离（解决红色沉积噪点）
- **参考TerraForged**：沉积不使用笔刷扩散，只做4角点沉积
- depositSpeed设为0.02~0.05（比原来降低10倍以上）
- 每级侵蚀后加高斯模糊（sigma=1.0, 3×3 kernel）平滑噪点

### v8 扩大模组区域（解决游戏里看不到大侵蚀）
- **问题**：游戏区域只有4×4区块(64方块)，笔刷半径才5方块
- **修复**：区域扩大到8×8区块(128方块)，笔刷半径增大到14
- 粗侵蚀在一个像素=4方块的分辨率上运行

---

## 五、已知问题

1. **性能**：模组区域从4→8区块后，侵蚀计算量约增加4倍，可能需要优化或后台异步计算
2. **沉积点噪声**：虽然depositSpeed已降低，但在平坦区域仍可能有轻微点状沉积
3. **色阶显示**：地形高度范围较窄(0.28~0.43)，颜色区分度有限
4. **跨区域一致性**：虽然加了padding和混合，但8×8区域之间仍可能存在微小不连续

---

## 六、参考代码

TerraForged 0.3.x 参考目录：
```
forge-1.20.1-47.4.10-mdk/参考/TerraForged-0.3.x/
```

关键参考文件：
- `.../noise/erosion/ErosionFilter.java` — 侵蚀过滤器（核心参考）
- `.../noise/erosion/ErodedNoiseGenerator.java` — 侵蚀噪声生成器
- `.../terrain/TerrainBlender.java` — 地形混合器（跨区域过渡）

TerraForged核心设计理念：
1. 侵蚀和沉积用不同速度参数，分开控制
2. 沉积不用笔刷（单点4角散布），侵蚀用圆形笔刷
3. 粒子惯性约0.005，避免切穿山脊
4. 容沙量 = -dh * speed * water * factor，不额外乘以强度

---

## 七、如何继续开发

### 下一步可能的方向
1. 将侵蚀应用到更多地形类型（沙漠风蚀、冰川、海岸）
2. 地质硬度影响侵蚀（硬岩石不易侵蚀）
3. 河流雕刻（在侵蚀基础上加深河道）
4. 沉积物堆积形成冲积扇
5. 多线程优化（每个区域独立计算）

### 测试方式
- **快速迭代**：修改 `ErosionPipeline.java` 中的参数，运行测试工具看输出图像
- **游戏验证**：`gradlew.bat runClient` 进入游戏查看实际地形
- **高度图预览**：`HeightmapPreview.java` 保存1024×1024高度图预览
