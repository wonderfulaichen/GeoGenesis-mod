---
name: fix-params-page-layout
overview: 修复参数页三个布局缺陷：WorldHeightBar标记与滑块重叠、底部大片空白、滚动溢出覆盖标签页。
todos:
  - id: fix-worldheightbar
    content: 重构 WorldHeightBar 布局：移除标记文字标签，仅保留标记线+滑块标签
    status: pending
  - id: fix-content-height
    content: GeoGenesisConfigScreen 动态计算 contentH 替代硬编码 estContent
    status: pending
  - id: fix-scissor-clip
    content: 增强 BasicParamsPanel scissor 裁剪防止滚动溢出
    status: pending
  - id: verify-build
    content: 编译验证 + runClient 目检
    status: pending
    dependencies:
      - fix-worldheightbar
      - fix-content-height
      - fix-scissor-clip
---

## 产品概述

修复 GeoGenesis 配置屏参数页的三个布局缺陷。

## 核心功能

1. **WorldHeightBar 内部重叠修复**：标记线标签（山脊/雪线/海平面/底）与右侧滑块（山脊上限/海平面/世界底）在水平和垂直方向上重叠。需要重新设计柱状图与滑块的布局关系，消除视觉冲突。
2. **参数页底部空白修复**：当屏幕较大时，面板高度高于实际内容，底部出现大面积空白。需要让 contentH 动态匹配实际内容高度而非硬编码估算。
3. **滚动溢出覆盖标签页修复**：滚动时 BasicParamsPanel 的 widget 被 setY 到标签页/种子输入框上方，子组件内的 ParamSlider 不受父面板 scissor 裁剪保护。需要增强裁剪逻辑确保内容不溢出。

## 视觉效果

- WorldHeightBar：柱状图左侧显示标记线（无文字），右侧滑块清晰排列，标记线与滑块行对应
- 参数页：内容紧凑填充面板，无多余空白
- 滚动时内容严格限制在面板区域内，不覆盖上方种子输入框和标签页

## 技术栈

- Minecraft Forge 1.20.1 (Java 17)
- Minecraft GUI 组件系统 (AbstractWidget, GuiGraphics, RenderSystem)
- 无新依赖，纯布局逻辑修改

## 实现方案

### 修复1：WorldHeightBar 布局重构

**方案**：移除柱状图上的文字标签，改为仅画标记线 + 滑块自带标签文字。这样柱状图只负责可视化高度范围，滑块负责显示参数名和值，彻底消除重叠。

具体改动：

- `drawMarker()` 只画标记线，不画文字标签
- 柱状图区域缩小为 `barH = getHeight() - PAD * 2`（不再预留底部空间）
- 滑块 Y 位置从 `getY() + PAD` 开始，3个滑块按 ROW_HEIGHT 等间距排列
- 标记线从柱状图右侧延伸到滑块左侧，形成视觉连接
- `calculateMinimumHeight()` 调整为仅需 `PAD * 2 + SLIDER_COUNT * ROW_HEIGHT`

### 修复2：底部空白 - 动态 contentH 计算

**方案**：在 `GeoGenesisConfigScreen.init()` 中，用实际 `calculateMinimumHeight()` 值加上滑块和间距来计算精确内容高度，替代硬编码 `estContent=720`。

```java
int headerH = 42; // 预设按钮区域
int widgetH = WorldHeightBar.calculateMinimumHeight() + 8
            + SnowLineChart.calculateMinimumHeight() + 8
            + ScalePreview.calculateMinimumHeight() + 8;
int sliderH = buildParamSpecs().size() * 22;
int totalContent = headerH + widgetH + sliderH + 4;
int contentH = Math.min(availH, totalContent + 8);
```

同时将 `buildParamSpecs()` 提前调用一次以获取 slider 数量。

### 修复3：滚动溢出裁剪增强

**方案**：在 BasicParamsPanel.renderWidget() 中，scissor 裁剪坐标需要额外考虑子组件的内部滑块。核心问题是子组件（WorldHeightBar/SnowLineChart/ScalePreview）内部的 ParamSlider 在 `repositionSliders()` 中用 `getY()` 设置 Y 坐标，当 widget 被 BasicParamsPanel 的 `widget.setY(y)` 移动后，滑块跟随移动到正确位置，但 scissor 只裁剪面板自身区域。

实际上当前 scissor 是在面板坐标系下计算的，应该已经能裁剪。但 `widget.setY(y)` 改变了 widget 的 Y，子组件的 `repositionSliders()` 使用 `getY()` 重新定位，这是正确的。**真正的问题**在于 scissor 的坐标计算：需要确保 `clipY` 和 `clipH` 正确覆盖面板区域。当前代码看起来正确，但可能因为 Minecraft 的 PoseStack 变换导致 scissor 坐标偏移。

修复：在 scissor 后添加 `g.pose().pushPose()` + `g.pose().translate(0, 0, 0)` 确保变换矩阵正确；或者更简单地，在子组件渲染前确保 scissor 仍然生效。

## 实现要点

- 不改变 SnowLineChart 和 ScalePreview 的内部布局
- 保持现有鼠标交互逻辑不变
- 保持现有 Tooltip 渲染逻辑（在 scissor 外渲染）

## 关键文件

- `WorldHeightBar.java` — 布局重构（移除标记文字、调整滑块位置）
- `BasicParamsPanel.java` — 增强 scissor 裁剪
- `GeoGenesisConfigScreen.java` — 动态 contentH 计算