# 净资产趋势折线图 — 设计文档

## 概述

在首页顶部增加净资产趋势折线图，直观展示资产随时间的变化趋势。

## 设计决策

| 项目 | 决策 |
|------|------|
| 图表类型 | 折线图（Line Chart） |
| 位置 | 首页 hero 区域，指标卡下方 |
| 数据范围 | 全部快照，按日期升序 |
| 显示线条 | 3条：净资产、总资产、总负债 |
| 技术方案 | Chart.js 4.x（CDN引入） |

## 布局结构

```
┌─────────────────────────────────────┐
│  Personal Asset Snapshot             │
│  资产快照                            │
├──────┬──────┬──────┬──────┤
│日期  │总资产│总负债│净资产│  ← 4个指标卡
└──────┴──────┴──────┴──────┘
┌─────────────────────────────────────┐
│  净资产趋势               [图例]     │
│  ╭─╮    ╭──╮                       │
│ ╭╯ ╰───╯  ╰─────╮    ╭─            │  ← 折线图
│╯              ╰────╯  ╰──          │
│─────────────────────────────────────│
│  快照1  快照2  快照3  快照4  ...    │
└─────────────────────────────────────┘
```

## 视觉规范

### 配色

| 线条 | 颜色 | CSS变量 |
|------|------|---------|
| 净资产 | `#176a58` | `--positive` |
| 总资产 | `#b05f2f` | `--accent` |
| 总负债 | `#a33d31` | `--negative` |
| 背景 | 透明 | — |
| 网格线 | `rgba(16,33,39,0.08)` | — |

### 图表尺寸

- 高度：`280px`（桌面端）
- 高度：`200px`（移动端 <640px）
- 卡片：与 hero-card 保持一致圆角（28px）、内边距（24px）

### 图例

- 位于图表右上角
- 水平排列，圆点 + 文字
- 点击某图例可单独显示/隐藏该线条

### Tooltip

- 鼠标悬停显示 vertical line + tooltip
- Tooltip 内容：日期 + 各指标名称和数值
- 数值格式：货币格式，与页面其他地方一致

## 交互规范

### 响应式行为

- `<640px`：图表高度 200px，网格线减少
- `640px-980px`：2列网格时图表宽度不变
- `>980px`：全宽显示

### 空状态

- 当 snapshots 为空或不足2条时：显示空状态卡片
- 文案："暂无趋势数据"
- 不渲染 Chart.js 实例

## 技术实现

### 依赖

Chart.js 4.x via CDN:
```html
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"></script>
```

### 数据处理

```javascript
// 按日期升序排列
const sortedSnapshots = snapshots.value.slice().sort(
  (a, b) => new Date(a.snapshotDate) - new Date(b.snapshotDate)
)

// 提取数据
const labels = sortedSnapshots.map(s => s.snapshotDate)
const netWorthData = sortedSnapshots.map(s => s.netWorth)
const totalAssetsData = sortedSnapshots.map(s => s.cashTotal + s.investmentTotal + s.publicFunds)
const totalDebtData = sortedSnapshots.map(s => s.liabilityTotal)
```

### 组件位置

- 新增 `<canvas id="netWorthChart">` 在 hero-card 的 hero-grid 下方
- Chart 实例存储在 `onBeforeUnmount` 中清理

### 性能

- Chart.js 默认线性插值，适合资产数据
- 数据更新时调用 `chart.update()` 而非重新创建

## 文件变更

- `water-web/src/App.vue` — 新增图表 canvas 和初始化逻辑
- `water-web/src/styles.css` — 新增 `.chart-card` 样式

## 验证标准

1. 打开首页，能看到折线图
2. 三条线颜色正确（净资产绿、总资产橙、总负债红）
3. 悬停显示 tooltip，数值格式正确
4. 无数据时显示"暂无趋势数据"
5. 移动端（<640px）图表高度自适应
