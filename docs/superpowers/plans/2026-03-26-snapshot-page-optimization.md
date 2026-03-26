# Snapshot Page Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Optimize snapshot page to use minimalist list view (date + net worth per row) with full details on expand.

**Architecture:** Single-page Vue 3 app with hash routing. Snapshot list simplified to one-line-per-item with expandable details. No new components needed - modify existing App.vue and styles.css.

**Tech Stack:** Vue 3, vanilla CSS (no framework), Chart.js for chart.

---

## File Structure

```
water-web/src/
├── App.vue          # Main app - modify snapshot list template
└── styles.css       # Styles - update snapshot-related classes
```

---

### Task 1: Simplify Snapshot List Template

**Files:**
- Modify: `water-web/src/App.vue:860-881`

- [ ] **Step 1: Update snapshot list item to minimalist row format**

Replace the current `snapshot-summary` button content with simpler layout:

```vue
<article v-for="snapshot in snapshots" :key="snapshot.id" class="snapshot-item">
  <button class="snapshot-row" type="button" @click="toggleSnapshot(snapshot.id)">
    <span class="snapshot-date">{{ snapshot.snapshotDate }}</span>
    <span class="snapshot-networth" :class="summaryTone(snapshot.netWorth)">
      {{ formatAmount(snapshot.netWorth) }}
    </span>
  </button>
```

- [ ] **Step 2: Commit**

```bash
git add water-web/src/App.vue
git commit -m "feat: simplify snapshot list to minimal row format"
```

---

### Task 2: Update Snapshot List Styles

**Files:**
- Modify: `water-web/src/styles.css:239-272`

- [ ] **Step 1: Update .snapshot-list and add .snapshot-item styles**

```css
.snapshot-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.snapshot-item {
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(16, 33, 39, 0.06);
  overflow: hidden;
}

.snapshot-row {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.snapshot-row:hover {
  background: rgba(16, 33, 39, 0.02);
}

.snapshot-date {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.snapshot-networth {
  font-size: 18px;
  font-weight: 700;
}
```

- [ ] **Step 2: Remove old .snapshot-card and .snapshot-summary styles** (they will be replaced)

- [ ] **Step 3: Commit**

```bash
git add water-web/src/styles.css
git commit -m "style: update snapshot list to minimal row styling"
```

---

### Task 3: Create Expandable Detail Section

**Files:**
- Modify: `water-web/src/App.vue:883-951`

- [ ] **Step 1: Replace .snapshot-body content with optimized layout**

Replace existing expanded content with cleaner design:

```vue
<div v-if="expandedId === snapshot.id" class="snapshot-detail">
  <div class="detail-header">
    <span class="detail-date">{{ snapshot.snapshotDate }}</span>
    <div class="detail-actions">
      <button class="ghost-button small-button" type="button" @click="openEditSnapshotForm(snapshot)">编辑</button>
      <button
        class="danger-button small-button"
        type="button"
        :disabled="deletingId === snapshot.id"
        @click="deleteSnapshot(snapshot.id)"
      >
        {{ deletingId === snapshot.id ? "删除中..." : "删除" }}
      </button>
    </div>
  </div>

  <div class="detail-summary">
    <div class="summary-item">
      <span class="item-label">净资产</span>
      <span class="item-value large" :class="summaryTone(snapshot.netWorth)">
        {{ formatAmount(snapshot.netWorth) }}
      </span>
    </div>
  </div>

  <div class="detail-stats">
    <div class="stat-item">
      <span class="stat-label">收入</span>
      <span class="stat-value positive">{{ formatAmount(snapshot.income) }}</span>
    </div>
    <div class="stat-item">
      <span class="stat-label">支出</span>
      <span class="stat-value negative">{{ formatAmount(snapshot.fixedExpense) }}</span>
    </div>
    <div class="stat-item">
      <span class="stat-label">负债</span>
      <span class="stat-value negative">{{ formatAmount(snapshot.liabilityTotal) }}</span>
    </div>
    <div class="stat-item">
      <span class="stat-label">盈亏</span>
      <span class="stat-value" :class="summaryTone(snapshot.profitLoss)">
        {{ formatAmount(snapshot.profitLoss) }}
      </span>
    </div>
    <div class="stat-item">
      <span class="stat-label">公积金</span>
      <span class="stat-value">{{ formatAmount(snapshot.publicFunds) }}</span>
    </div>
    <div class="stat-item">
      <span class="stat-label">余额</span>
      <span class="stat-value">{{ formatAmount(snapshot.balance) }}</span>
    </div>
  </div>

  <div class="detail-block">
    <div class="block-header">
      <h3>账户明细</h3>
      <span>{{ snapshot.details.length }} 个账户</span>
    </div>
    <div class="detail-grid">
      <article
        v-for="detail in snapshot.details"
        :key="`${snapshot.id}-${detail.accountCode}`"
        class="account-chip"
        :data-tone="detailTone(detail)"
      >
        <span class="chip-name">{{ detail.accountName }}</span>
        <span class="chip-amount" :class="detail.balanceDirection === 'DEBT' ? 'debt' : 'asset'">
          {{ formatAmount(detail.amount, detail.currencyCode) }}
        </span>
      </article>
    </div>
  </div>
</div>
```

- [ ] **Step 2: Commit**

```bash
git add water-web/src/App.vue
git commit -m "feat: add optimized expandable detail section"
```

---

### Task 4: Add Detail Section Styles

**Files:**
- Modify: `water-web/src/styles.css`

- [ ] **Step 1: Add styles for .snapshot-detail and its components**

```css
.snapshot-detail {
  padding: 0 20px 20px;
  border-top: 1px solid rgba(16, 33, 39, 0.06);
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 0;
}

.detail-date {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-muted);
}

.detail-actions {
  display: flex;
  gap: 8px;
}

.detail-summary {
  padding: 12px 0 16px;
  border-bottom: 1px solid rgba(16, 33, 39, 0.04);
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.item-label {
  font-size: 12px;
  color: var(--text-muted);
  text-transform: uppercase;
}

.item-value.large {
  font-size: 28px;
  font-weight: 800;
}

.item-value.positive { color: #176a58; }
.item-value.negative { color: #a33d31; }

.detail-stats {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
  padding: 16px 0;
}

.stat-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-label {
  font-size: 11px;
  color: var(--text-muted);
  text-transform: uppercase;
}

.stat-value {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}

.stat-value.positive { color: #176a58; }
.stat-value.negative { color: #a33d31; }

.block-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0 8px;
}

.block-header h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.block-header span {
  font-size: 12px;
  color: var(--text-muted);
}

.account-chip {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px;
  border-radius: 12px;
}

.chip-name {
  font-size: 14px;
  font-weight: 500;
}

.chip-amount {
  font-size: 15px;
  font-weight: 700;
}

.chip-amount.asset { color: #176a58; }
.chip-amount.debt { color: #a33d31; }

.account-chip[data-tone="asset"] {
  background: rgba(234, 245, 239, 0.9);
}

.account-chip[data-tone="investment"] {
  background: rgba(255, 242, 225, 0.9);
}

.account-chip[data-tone="debt"] {
  background: rgba(250, 232, 228, 0.9);
}
```

- [ ] **Step 2: Update responsive styles for mobile**

Add to existing `@media (max-width: 640px)` section:

```css
.detail-stats {
  grid-template-columns: repeat(2, 1fr);
}
```

- [ ] **Step 3: Commit**

```bash
git add water-web/src/styles.css
git commit -m "style: add snapshot detail section styles"
```

---

### Task 5: Verify and Test

- [ ] **Step 1: Start dev server and test**

```bash
cd water-web && npm run dev
```

- [ ] **Step 2: Navigate to snapshot page and verify:**
- List shows only date and net worth per row
- Clicking row expands to show all details
- Stats are in horizontal layout
- Account chips show name and amount with correct colors
- Mobile responsive

- [ ] **Step 3: Commit final changes**

```bash
git add -A
git commit -m "feat: complete snapshot page optimization"
```

---

## Success Criteria

1. ✅ List view shows one row per snapshot with date and net worth
2. ✅ Clicking expands to show full details
3. ✅ Stats displayed horizontally with semantic colors
4. ✅ Account chips show account name and amount
5. ✅ Responsive on mobile (stats wrap to 2 columns)
6. ✅ No console errors
