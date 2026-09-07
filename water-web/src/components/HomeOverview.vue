<script setup>
import { computed, ref, watch, onBeforeUnmount } from 'vue';
import Chart from 'chart.js/auto';

const props = defineProps({ snapshots: { type: Array, required: true }, formatAmount: Function, convertToRmb: Function });
const period = ref('all');
const canvas = ref(null);
let chart;
const latest = computed(() => props.snapshots[0]);
const previous = computed(() => props.snapshots[1]);
const yearBase = computed(() => props.snapshots.find(s => s.snapshotDate < `${latest.value?.snapshotDate.slice(0, 4)}-01-01`));
const rows = computed(() => {
  const end = latest.value?.snapshotDate;
  if (!end) return [];
  const cutoff = period.value === 'year' ? `${end.slice(0, 4)}-01-01` : period.value === 'quarter'
    ? new Date(Date.UTC(Number(end.slice(0, 4)), Number(end.slice(5, 7)) - 4, Number(end.slice(8)))).toISOString().slice(0, 10) : '';
  return props.snapshots.filter(s => s.snapshotDate >= cutoff).slice().reverse();
});
const changes = computed(() => {
  if (!previous.value) return [];
  const signed = (d, rate) => props.convertToRmb(d.amount, d.currencyCode, rate) * (d.balanceDirection === 'DEBT' ? -1 : 1);
  const before = new Map((previous.value.details ?? []).filter(d => !d.summaryAccount && !d.subtotalAccount).map(d => [d.accountId, d]));
  const after = new Map((latest.value?.details ?? []).filter(d => !d.summaryAccount && !d.subtotalAccount).map(d => [d.accountId, d]));
  return [...new Set([...before.keys(), ...after.keys()])].map(id => ({
    id, name: (after.get(id) ?? before.get(id)).accountName,
    delta: (after.has(id) ? signed(after.get(id), latest.value.hkdToCnyRate) : 0) - (before.has(id) ? signed(before.get(id), previous.value.hkdToCnyRate) : 0)
  })).filter(r => Math.abs(r.delta) > 0.001).sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta)).slice(0, 5);
});
watch([canvas, rows], () => {
  chart?.destroy();
  if (!canvas.value) return;
  chart = new Chart(canvas.value, {
    type: 'line',
    data: {
      labels: rows.value.map(s => s.snapshotDate),
      datasets: [['净资产', 'netWorth', '#176a58'], ['总资产', 'grossAccountValue', '#b05f2f'], ['总负债', 'liabilityTotal', '#a33d31']]
        .map(([label, key, borderColor]) => ({ label, data: rows.value.map(s => s[key]), borderColor, tension: 0.2, pointRadius: rows.value.length > 60 ? 0 : 3 }))
    },
    options: { responsive: true, maintainAspectRatio: false, interaction: { mode: 'index', intersect: false },
      plugins: { tooltip: { callbacks: { label: c => `${c.dataset.label}: ${props.formatAmount(c.parsed.y)}` } } } }
  });
}, { flush: 'post' });
onBeforeUnmount(() => chart?.destroy());
</script>

<template>
  <section v-if="latest" class="content-card">
    <div class="section-head"><h2>资产概览</h2><span>截至 {{ latest.snapshotDate }}</span></div>
    <div class="hero-grid">
      <article class="metric-card"><span>净资产</span><strong>{{ formatAmount(latest.netWorth) }}</strong></article>
      <article class="metric-card"><span>总资产</span><strong>{{ formatAmount(latest.grossAccountValue) }}</strong></article>
      <article class="metric-card"><span>总负债</span><strong>{{ formatAmount(latest.liabilityTotal) }}</strong></article>
      <article class="metric-card"><span>较上次变化</span><strong>{{ previous ? formatAmount(latest.netWorth - previous.netWorth) : '暂无对比' }}</strong></article>
    </div>
    <p>较年初变化：{{ yearBase ? formatAmount(latest.netWorth - yearBase.netWorth) : '缺少上年末记录' }}。净资产变化包含入账、支出和账户调整，不等同于投资收益。</p>
  </section>
  <section class="chart-card content-card">
    <div class="section-head"><h2>净资产趋势</h2>
      <label class="field">时间范围<select v-model="period"><option value="quarter">近三个月</option><option value="year">最新记录所在年</option><option value="all">全部</option></select></label>
    </div>
    <div v-if="rows.length" class="chart-wrapper"><canvas ref="canvas" aria-label="净资产、总资产与负债趋势" role="img"></canvas></div>
    <p v-else class="state-panel">暂无记录，录入第一笔资产后即可查看。</p>
  </section>
  <section v-if="previous" class="content-card">
    <div class="section-head"><h2>账户变化前五名</h2><span>对比 {{ previous.snapshotDate }}</span></div>
    <p>按明细账户对净资产的影响排序；新增、移除账户也会出现在这里，汇总账户不重复计入。</p>
    <div v-for="item in changes" :key="item.id" class="section-head"><span>{{ item.name }}</span><strong>{{ item.delta > 0 ? '+' : '' }}{{ formatAmount(item.delta) }}</strong></div>
    <p v-if="!changes.length">明细账户金额没有变化。</p>
  </section>
</template>
