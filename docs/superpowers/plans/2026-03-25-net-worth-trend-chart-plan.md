# 净资产趋势折线图 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在首页顶部指标卡下方增加净资产趋势折线图，展示净资产、总资产、总负债随时间的变化。

**Architecture:** 纯前端实现，使用 Chart.js 4.x（CDN引入），Vue 3 Composition API 管理生命周期。

**Tech Stack:** Chart.js 4.x, Vue 3, vanilla CSS

---

## 文件变更概览

| 文件 | 操作 | 职责 |
|------|------|------|
| `water-web/index.html` | 修改 | 引入 Chart.js CDN |
| `water-web/src/styles.css` | 修改 | 新增 `.chart-card` 样式 |
| `water-web/src/App.vue` | 修改 | 新增 canvas、chart ref、初始化/清理逻辑 |

---

## Task 1: 引入 Chart.js CDN

**Files:**
- Modify: `water-web/index.html:12-13`

- [ ] **Step 1: 添加 Chart.js CDN**

在 `index.html` 的 `</head>` 前添加：
```html
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"></script>
```

- [ ] **Step 2: 验证 CDN 可访问**

Run: `curl -sI "https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js" | head -1`
Expected: `HTTP/2 200` 或 `HTTP/1.1 200`

- [ ] **Step 3: Commit**

```bash
git add water-web/index.html
git commit -m "chore: add Chart.js CDN"
```

---

## Task 2: 添加图表容器样式

**Files:**
- Modify: `water-web/src/styles.css`

- [ ] **Step 1: 添加 `.chart-card` 样式**

在 `styles.css` 末尾添加：
```css
.chart-card {
  margin-top: 20px;
  padding: 24px;
}

.chart-empty {
  height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-size: 14px;
}

.chart-wrapper {
  position: relative;
  height: 280px;
}

@media (max-width: 640px) {
  .chart-wrapper {
    height: 200px;
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add water-web/src/styles.css
git commit -m "style: add chart-card styles"
```

---

## Task 3: 实现图表逻辑

**Files:**
- Modify: `water-web/src/App.vue`

### 3.1 添加 chart ref 和 computed

**位置：** 在现有 `ref` 声明区域添加（约第 16 行附近）

```javascript
const netWorthChartRef = ref(null);
let netWorthChartInstance = null;
```

**位置：** 在 `onBeforeUnmount` 中添加 chart 销毁（约第 51 行）

```javascript
onBeforeUnmount(() => {
  window.removeEventListener("hashchange", syncPageFromHash);
  if (netWorthChartInstance) {
    netWorthChartInstance.destroy();
    netWorthChartInstance = null;
  }
});
```

### 3.2 添加 chart 数据计算 computed

**位置：** 在现有 computed 区域添加（约第 43 行后）

```javascript
const chartSnapshots = computed(() => {
  if (!snapshots.value || snapshots.value.length < 2) {
    return null;
  }
  return snapshots.value
    .slice()
    .sort((a, b) => new Date(a.snapshotDate) - new Date(b.snapshotDate));
});

const chartData = computed(() => {
  if (!chartSnapshots.value) {
    return null;
  }
  return {
    labels: chartSnapshots.value.map((s) => s.snapshotDate),
    netWorth: chartSnapshots.value.map((s) => (s.netWorth !== null ? Number(s.netWorth) : null)),
    totalAssets: chartSnapshots.value.map(
      (s) =>
        numberValue(s.cashTotal) +
        numberValue(s.investmentTotal) +
        numberValue(s.publicFunds)
    ),
    totalDebt: chartSnapshots.value.map((s) => (s.liabilityTotal !== null ? Number(s.liabilityTotal) : null))
  };
});
```

### 3.3 添加图表初始化函数

**位置：** 在 `loadAll` 函数之前（约第 88 行）

```javascript
function initNetWorthChart() {
  if (netWorthChartInstance) {
    netWorthChartInstance.destroy();
    netWorthChartInstance = null;
  }

  if (!chartData.value) {
    return;
  }

  const ctx = netWorthChartRef.value.getContext("2d");
  netWorthChartInstance = new Chart(ctx, {
    type: "line",
    data: {
      labels: chartData.value.labels,
      datasets: [
        {
          label: "净资产",
          data: chartData.value.netWorth,
          borderColor: "#176a58",
          backgroundColor: "rgba(23, 106, 88, 0.1)",
          fill: true,
          tension: 0.3,
          pointRadius: 4,
          pointHoverRadius: 6
        },
        {
          label: "总资产",
          data: chartData.value.totalAssets,
          borderColor: "#b05f2f",
          backgroundColor: "rgba(176, 95, 47, 0.1)",
          fill: true,
          tension: 0.3,
          pointRadius: 4,
          pointHoverRadius: 6
        },
        {
          label: "总负债",
          data: chartData.value.totalDebt,
          borderColor: "#a33d31",
          backgroundColor: "rgba(163, 61, 49, 0.1)",
          fill: true,
          tension: 0.3,
          pointRadius: 4,
          pointHoverRadius: 6
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: {
        mode: "index",
        intersect: false
      },
      plugins: {
        legend: {
          position: "top",
          align: "end",
          labels: {
            usePointStyle: true,
            padding: 20,
            font: {
              family: "'Manrope', 'Noto Sans SC', sans-serif",
              size: 12
            }
          }
        },
        tooltip: {
          backgroundColor: "rgba(255, 255, 255, 0.95)",
          titleColor: "#102127",
          bodyColor: "#587078",
          borderColor: "rgba(16, 33, 39, 0.08)",
          borderWidth: 1,
          padding: 12,
          titleFont: {
            family: "'Manrope', 'Noto Sans SC', sans-serif",
            weight: "bold"
          },
          bodyFont: {
            family: "'Manrope', 'Noto Sans SC', sans-serif"
          },
          callbacks: {
            label: function (context) {
              const value = context.parsed.y;
              if (value === null) return null;
              return context.dataset.label + ": " + formatAmount(value);
            }
          }
        }
      },
      scales: {
        x: {
          grid: {
            display: false
          },
          ticks: {
            font: {
              family: "'Manrope', 'Noto Sans SC', sans-serif",
              size: 11
            },
            color: "#587078"
          }
        },
        y: {
          grid: {
            color: "rgba(16, 33, 39, 0.08)"
          },
          ticks: {
            font: {
              family: "'Manrope', 'Noto Sans SC', sans-serif",
              size: 11
            },
            color: "#587078",
            callback: function (value) {
              if (value >= 10000) {
                return (value / 10000).toFixed(1) + "万";
              }
              return value;
            }
          }
        }
      }
    }
  });
}
```

### 3.4 修改 loadAll 在数据加载后初始化图表

**位置：** `loadAll` 函数末尾，约第 86 行

在 `loading.value = false` 之前添加：
```javascript
if (currentPage.value === HOME_PAGE) {
  initNetWorthChart();
}
```

### 3.5 在模板中添加图表容器

**位置：** hero-grid 后方，约第 511 行

```html
<div v-if="currentPage === HOME_PAGE" class="chart-card content-card">
  <div class="section-head">
    <div>
      <p class="eyebrow">Trend</p>
      <h2>净资产趋势</h2>
    </div>
  </div>

  <div v-if="!chartData" class="chart-empty">暂无趋势数据</div>
  <div v-else class="chart-wrapper">
    <canvas ref="netWorthChartRef"></canvas>
  </div>
</div>
```

- [ ] **Step 1: 添加 chart ref**

在 `water-web/src/App.vue` 第 16 行后添加：
```javascript
const netWorthChartRef = ref(null);
let netWorthChartInstance = null;
```

- [ ] **Step 2: 更新 onBeforeUnmount**

在 `onBeforeUnmount` 中添加 chart 销毁逻辑。

- [ ] **Step 3: 添加 chartSnapshots 和 chartData computed**

在现有 computed 区域添加两个新的 computed。

- [ ] **Step 4: 添加 initNetWorthChart 函数**

在 `loadAll` 函数之前添加完整实现。

- [ ] **Step 5: 修改 loadAll 在数据加载后初始化图表**

在 `loadAll` 函数末尾 `loading.value = false` 之前添加图表初始化调用。

- [ ] **Step 6: 在模板中添加图表容器**

在 hero-grid 的 `</div>` 后、formError section 前添加图表 HTML。

- [ ] **Step 7: 运行开发服务器验证**

Run: `cd water-web && npm run dev`
Expected: 浏览器打开后首页显示折线图

- [ ] **Step 8: Commit**

```bash
git add water-web/src/App.vue
git commit -m "feat: add net worth trend chart with Chart.js"
```

---

## 验证清单

- [ ] 打开首页，能看到折线图（三条线）
- [ ] 三条线颜色正确（净资产绿、总资产橙、总负债红）
- [ ] 悬停显示 tooltip，数值格式正确
- [ ] 无数据或不足2条时显示"暂无趋势数据"
- [ ] 移动端（<640px）图表高度自适应（200px）
- [ ] 页面切换到账户页再返回，图表正常显示
- [ ] 刷新页面，图表正常显示
