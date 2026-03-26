<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";

const HOME_PAGE = "home";
const SNAPSHOTS_PAGE = "snapshots";
const ACCOUNT_LIST_PAGE = "account-list";
const ACCOUNTS_PAGE = "accounts";

const loading = ref(true);
const saving = ref(false);
const error = ref("");
const formError = ref("");
const currentPage = ref(resolvePageFromHash());

const snapshots = ref([]);
const accountDefinitions = ref([]);
const accounts = ref([]);
const expandedId = ref(null);
const editingId = ref(null);
const deletingId = ref(null);
const editingAccountId = ref(null);
const deletingAccountId = ref(null);
const netWorthChartRef = ref(null);
let netWorthChartInstance = null;

const snapshotForm = reactive(createEmptySnapshotForm());
const accountForm = reactive(createEmptyAccountForm());

const latestSnapshot = computed(() => snapshots.value[0] ?? null);
const totalAssets = computed(() => {
  if (!latestSnapshot.value) {
    return null;
  }
  return numberValue(latestSnapshot.value.cashTotal)
    + numberValue(latestSnapshot.value.investmentTotal)
    + numberValue(latestSnapshot.value.publicFunds);
});
const latestDebts = computed(() => numberValue(latestSnapshot.value?.liabilityTotal));
const latestNetWorth = computed(() => latestSnapshot.value?.netWorth ?? null);

const isSnapshotCreating = computed(() => editingId.value === "new");
const isSnapshotEditing = computed(() => editingId.value !== null);
const snapshotSubmitLabel = computed(() => (isSnapshotCreating.value ? "新增快照" : "保存修改"));

const isAccountCreating = computed(() => editingAccountId.value === "new");
const isAccountEditing = computed(() => editingAccountId.value !== null);
const accountSubmitLabel = computed(() => (isAccountCreating.value ? "新增账户" : "保存账户"));

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

onMounted(async () => {
  window.addEventListener("hashchange", syncPageFromHash);
  await loadAll();
});

onBeforeUnmount(() => {
  window.removeEventListener("hashchange", syncPageFromHash);
  if (netWorthChartInstance) {
    netWorthChartInstance.destroy();
    netWorthChartInstance = null;
  }
});

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

async function loadAll() {
  loading.value = true;
  error.value = "";

  try {
    const [snapshotsResponse, enabledAccountsResponse, accountsResponse] = await Promise.all([
      fetch("/api/snapshots"),
      fetch("/api/snapshots/accounts"),
      fetch("/api/accounts")
    ]);

    if (!snapshotsResponse.ok) {
      throw new Error(`加载快照失败: ${snapshotsResponse.status}`);
    }
    if (!enabledAccountsResponse.ok) {
      throw new Error(`加载快照账户失败: ${enabledAccountsResponse.status}`);
    }
    if (!accountsResponse.ok) {
      throw new Error(`加载账户失败: ${accountsResponse.status}`);
    }

    snapshots.value = await snapshotsResponse.json();
    accountDefinitions.value = await enabledAccountsResponse.json();
    accounts.value = await accountsResponse.json();

    if (snapshots.value.length > 0 && expandedId.value === null) {
      expandedId.value = snapshots.value[0].id;
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载失败";
  } finally {
    if (currentPage.value === HOME_PAGE) {
      initNetWorthChart();
    }
    loading.value = false;
  }
}

function resolvePageFromHash() {
  const hash = window.location.hash;
  if (hash === "#/accounts") return ACCOUNTS_PAGE;
  if (hash === "#/account-list") return ACCOUNT_LIST_PAGE;
  if (hash === "#/snapshots") return SNAPSHOTS_PAGE;
  return HOME_PAGE;
}

function syncPageFromHash() {
  currentPage.value = resolvePageFromHash();
}

function navigateTo(page) {
  currentPage.value = page;
  if (page === ACCOUNTS_PAGE) {
    window.location.hash = "/accounts";
  } else if (page === ACCOUNT_LIST_PAGE) {
    window.location.hash = "/account-list";
  } else if (page === SNAPSHOTS_PAGE) {
    window.location.hash = "/snapshots";
  } else {
    window.location.hash = "/";
  }
  formError.value = "";
}

function createEmptySnapshotForm() {
  return {
    snapshotDate: "",
    income: "",
    fixedExpense: "",
    cashTotal: "",
    investmentTotal: "",
    liabilityTotal: "",
    grossAccountValue: "",
    profitLoss: "",
    netWorth: "",
    publicFunds: "",
    extraAmount: "",
    balance: "",
    note: "",
    remark: "",
    details: []
  };
}

function createEmptyAccountForm() {
  return {
    accountCode: "",
    accountName: "",
    accountType: "EWALLET",
    balanceDirection: "ASSET",
    currencyCode: "CNY",
    institutionName: "",
    ownerName: "",
    remark: "",
    sortOrder: "",
    enabled: true
  };
}

function resetSnapshotForm(next) {
  Object.assign(snapshotForm, createEmptySnapshotForm(), next);
}

function resetAccountForm(next) {
  Object.assign(accountForm, createEmptyAccountForm(), next);
}

function toFieldValue(value) {
  return value === null || value === undefined ? "" : String(value);
}

function parseNullableNumber(value) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const numeric = Number(value);
  return Number.isNaN(numeric) ? null : numeric;
}

function numberValue(value) {
  const numeric = Number(value);
  return Number.isNaN(numeric) ? 0 : numeric;
}

function accountDetailDraft(snapshot, account) {
  const existing = snapshot?.details?.find((detail) => detail.accountCode === account.accountCode);
  return {
    accountCode: account.accountCode,
    accountName: account.accountName,
    accountType: account.accountType,
    balanceDirection: account.balanceDirection,
    currencyCode: existing?.currencyCode ?? account.currencyCode,
    amount: toFieldValue(existing?.amount)
  };
}

function openCreateSnapshotForm() {
  editingId.value = "new";
  formError.value = "";
  resetSnapshotForm({
    details: accountDefinitions.value.map((account) => accountDetailDraft(null, account))
  });
}

function openEditSnapshotForm(snapshot) {
  editingId.value = snapshot.id;
  expandedId.value = snapshot.id;
  formError.value = "";
  resetSnapshotForm({
    snapshotDate: snapshot.snapshotDate ?? "",
    income: toFieldValue(snapshot.income),
    fixedExpense: toFieldValue(snapshot.fixedExpense),
    cashTotal: toFieldValue(snapshot.cashTotal),
    investmentTotal: toFieldValue(snapshot.investmentTotal),
    liabilityTotal: toFieldValue(snapshot.liabilityTotal),
    grossAccountValue: toFieldValue(snapshot.grossAccountValue),
    profitLoss: toFieldValue(snapshot.profitLoss),
    netWorth: toFieldValue(snapshot.netWorth),
    publicFunds: toFieldValue(snapshot.publicFunds),
    extraAmount: toFieldValue(snapshot.extraAmount),
    balance: toFieldValue(snapshot.balance),
    note: snapshot.note ?? "",
    remark: snapshot.remark ?? "",
    details: accountDefinitions.value.map((account) => accountDetailDraft(snapshot, account))
  });
}

function cancelSnapshotEditing() {
  editingId.value = null;
  formError.value = "";
  resetSnapshotForm({ details: [] });
}

function buildSnapshotPayload() {
  return {
    snapshotDate: snapshotForm.snapshotDate,
    income: parseNullableNumber(snapshotForm.income),
    fixedExpense: parseNullableNumber(snapshotForm.fixedExpense),
    cashTotal: parseNullableNumber(snapshotForm.cashTotal),
    investmentTotal: parseNullableNumber(snapshotForm.investmentTotal),
    liabilityTotal: parseNullableNumber(snapshotForm.liabilityTotal),
    grossAccountValue: parseNullableNumber(snapshotForm.grossAccountValue),
    profitLoss: parseNullableNumber(snapshotForm.profitLoss),
    netWorth: parseNullableNumber(snapshotForm.netWorth),
    publicFunds: parseNullableNumber(snapshotForm.publicFunds),
    extraAmount: parseNullableNumber(snapshotForm.extraAmount),
    balance: parseNullableNumber(snapshotForm.balance),
    note: snapshotForm.note.trim() || null,
    remark: snapshotForm.remark.trim() || null,
    details: snapshotForm.details.map((detail) => ({
      accountCode: detail.accountCode,
      amount: parseNullableNumber(detail.amount),
      currencyCode: detail.currencyCode
    }))
  };
}

async function submitSnapshotForm() {
  formError.value = "";
  if (!snapshotForm.snapshotDate) {
    formError.value = "快照日期不能为空";
    return;
  }

  saving.value = true;
  try {
    const method = isSnapshotCreating.value ? "POST" : "PUT";
    const url = isSnapshotCreating.value ? "/api/snapshots" : `/api/snapshots/${editingId.value}`;
    const response = await fetch(url, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(buildSnapshotPayload())
    });

    if (!response.ok) {
      const message = await response.text();
      throw new Error(message || `保存失败: ${response.status}`);
    }

    const snapshot = await response.json();
    await loadAll();
    expandedId.value = snapshot.id;
    cancelSnapshotEditing();
  } catch (err) {
    formError.value = err instanceof Error ? err.message : "保存失败";
  } finally {
    saving.value = false;
  }
}

async function deleteSnapshot(id) {
  const snapshot = snapshots.value.find((item) => item.id === id);
  if (!snapshot) {
    return;
  }

  if (!window.confirm(`确认删除 ${snapshot.snapshotDate} 这条快照吗？`)) {
    return;
  }

  deletingId.value = id;
  formError.value = "";
  try {
    const response = await fetch(`/api/snapshots/${id}`, { method: "DELETE" });
    if (!response.ok) {
      const message = await response.text();
      throw new Error(message || `删除失败: ${response.status}`);
    }

    if (editingId.value === id) {
      cancelSnapshotEditing();
    }

    await loadAll();
    if (expandedId.value === id) {
      expandedId.value = snapshots.value[0]?.id ?? null;
    }
  } catch (err) {
    formError.value = err instanceof Error ? err.message : "删除失败";
  } finally {
    deletingId.value = null;
  }
}

function openCreateAccountForm() {
  editingAccountId.value = "new";
  formError.value = "";
  resetAccountForm({});
}

function openEditAccountForm(account) {
  editingAccountId.value = account.id;
  formError.value = "";
  resetAccountForm({
    accountCode: account.accountCode ?? "",
    accountName: account.accountName ?? "",
    accountType: account.accountType ?? "EWALLET",
    balanceDirection: account.balanceDirection ?? "ASSET",
    currencyCode: account.currencyCode ?? "CNY",
    institutionName: account.institutionName ?? "",
    ownerName: account.ownerName ?? "",
    remark: account.remark ?? "",
    sortOrder: toFieldValue(account.sortOrder),
    enabled: Boolean(account.enabled)
  });
}

function cancelAccountEditing() {
  editingAccountId.value = null;
  formError.value = "";
  resetAccountForm({});
}

function buildAccountPayload() {
  return {
    accountCode: accountForm.accountCode.trim(),
    accountName: accountForm.accountName.trim(),
    accountType: accountForm.accountType.trim(),
    balanceDirection: accountForm.balanceDirection.trim(),
    currencyCode: accountForm.currencyCode.trim(),
    institutionName: accountForm.institutionName.trim() || null,
    ownerName: accountForm.ownerName.trim() || null,
    remark: accountForm.remark.trim() || null,
    sortOrder: Number(accountForm.sortOrder || 0),
    enabled: Boolean(accountForm.enabled)
  };
}

async function submitAccountForm() {
  formError.value = "";
  if (!accountForm.accountCode.trim() || !accountForm.accountName.trim()) {
    formError.value = "账户编码和账户名称不能为空";
    return;
  }

  saving.value = true;
  try {
    const method = isAccountCreating.value ? "POST" : "PUT";
    const url = isAccountCreating.value ? "/api/accounts" : `/api/accounts/${editingAccountId.value}`;
    const response = await fetch(url, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(buildAccountPayload())
    });

    if (!response.ok) {
      const message = await response.text();
      throw new Error(message || `保存账户失败: ${response.status}`);
    }

    await loadAll();
    cancelAccountEditing();
  } catch (err) {
    formError.value = err instanceof Error ? err.message : "保存账户失败";
  } finally {
    saving.value = false;
  }
}

async function deleteAccount(id) {
  const account = accounts.value.find((item) => item.id === id);
  if (!account) {
    return;
  }

  if (!window.confirm(`确认删除账户 ${account.accountName} (${account.accountCode}) 吗？`)) {
    return;
  }

  deletingAccountId.value = id;
  formError.value = "";
  try {
    const response = await fetch(`/api/accounts/${id}`, { method: "DELETE" });
    if (!response.ok) {
      const message = await response.text();
      throw new Error(message || `删除账户失败: ${response.status}`);
    }

    if (editingAccountId.value === id) {
      cancelAccountEditing();
    }

    await loadAll();
  } catch (err) {
    formError.value = err instanceof Error ? err.message : "删除账户失败";
  } finally {
    deletingAccountId.value = null;
  }
}

function toggleSnapshot(id) {
  expandedId.value = expandedId.value === id ? null : id;
}

function formatAmount(value, currencyCode = "CNY") {
  if (value === null || value === undefined || value === "") {
    return "--";
  }

  const numeric = Number(value);
  if (Number.isNaN(numeric)) {
    return String(value);
  }

  const locale = currencyCode === "USD" ? "en-US" : "zh-CN";
  return new Intl.NumberFormat(locale, {
    style: "currency",
    currency: currencyCode,
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
  }).format(numeric);
}

function detailTone(detail) {
  if (detail.balanceDirection === "DEBT") {
    return "debt";
  }
  if (detail.accountType === "INVESTMENT") {
    return "investment";
  }
  return "asset";
}

function summaryTone(value) {
  if (value === null || value === undefined) {
    return "";
  }
  return Number(value) < 0 ? "negative" : "positive";
}

function formTone(account) {
  if (account.balanceDirection === "DEBT") {
    return "debt";
  }
  if (account.accountType === "INVESTMENT") {
    return "investment";
  }
  return "asset";
}
</script>

<template>
  <div class="page-shell">
    <main class="layout">
      <section class="hero-card">
        <div class="hero-top">
          <div>
            <p class="eyebrow">Personal Asset Snapshot</p>
            <div class="hero-heading">
              <h1>{{ currentPage === HOME_PAGE ? "资产概览" : currentPage === SNAPSHOTS_PAGE ? "快照列表" : currentPage === ACCOUNT_LIST_PAGE ? "账户列表" : "账户管理" }}</h1>
              <p v-if="currentPage === HOME_PAGE">净资产趋势图表</p>
              <p v-else-if="currentPage === SNAPSHOTS_PAGE">管理每日资产快照</p>
              <p v-else-if="currentPage === ACCOUNT_LIST_PAGE">查看所有账户</p>
              <p v-else>管理账户基础信息</p>
            </div>
          </div>

          <div class="button-row">
            <button
              class="ghost-button"
              :class="{ 'active-tab': currentPage === HOME_PAGE }"
              type="button"
              @click="navigateTo(HOME_PAGE)"
            >
              首页
            </button>
            <button
              class="ghost-button"
              :class="{ 'active-tab': currentPage === SNAPSHOTS_PAGE }"
              type="button"
              @click="navigateTo(SNAPSHOTS_PAGE)"
            >
              快照页
            </button>
            <button
              class="ghost-button"
              :class="{ 'active-tab': currentPage === ACCOUNT_LIST_PAGE }"
              type="button"
              @click="navigateTo(ACCOUNT_LIST_PAGE)"
            >
              账户列表
            </button>
            <button
              class="ghost-button"
              :class="{ 'active-tab': currentPage === ACCOUNTS_PAGE }"
              type="button"
              @click="navigateTo(ACCOUNTS_PAGE)"
            >
              账户设置
            </button>
          </div>
        </div>

        <div v-if="currentPage === SNAPSHOTS_PAGE && latestSnapshot" class="hero-grid">
          <article class="metric-card">
            <span>最新日期</span>
            <strong>{{ latestSnapshot.snapshotDate }}</strong>
          </article>
          <article class="metric-card">
            <span>总资产</span>
            <strong>{{ formatAmount(totalAssets) }}</strong>
          </article>
          <article class="metric-card">
            <span>总负债</span>
            <strong :class="summaryTone(latestDebts)">{{ formatAmount(latestDebts) }}</strong>
          </article>
          <article class="metric-card">
            <span>净资产</span>
            <strong :class="summaryTone(latestNetWorth)">{{ formatAmount(latestNetWorth) }}</strong>
          </article>
        </div>

        <div v-if="currentPage === ACCOUNT_LIST_PAGE" class="hero-grid">
          <article class="metric-card">
            <span>账户总数</span>
            <strong>{{ accounts.length }}</strong>
          </article>
          <article class="metric-card">
            <span>资产类账户</span>
            <strong>{{ accounts.filter(a => a.balanceDirection === 'ASSET').length }}</strong>
          </article>
          <article class="metric-card">
            <span>负债类账户</span>
            <strong>{{ accounts.filter(a => a.balanceDirection === 'DEBT').length }}</strong>
          </article>
        </div>
      </section>

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

      <section v-if="formError" class="content-card">
        <div class="state-panel error">{{ formError }}</div>
      </section>

      <template v-if="currentPage === SNAPSHOTS_PAGE">
        <section v-if="isSnapshotEditing" class="content-card form-card">
          <div class="section-head">
            <div>
              <p class="eyebrow">Editor</p>
              <h2>{{ isSnapshotCreating ? "新增快照" : "编辑快照" }}</h2>
            </div>
            <div class="button-row">
              <button class="ghost-button" type="button" @click="cancelSnapshotEditing">取消</button>
              <button class="primary-button" type="button" :disabled="saving" @click="submitSnapshotForm">
                {{ saving ? "保存中..." : snapshotSubmitLabel }}
              </button>
            </div>
          </div>

          <div class="form-grid">
            <label class="field">
              <span>快照日期</span>
              <input v-model="snapshotForm.snapshotDate" type="date" />
            </label>
            <label class="field">
              <span>收入</span>
              <input v-model="snapshotForm.income" type="number" step="0.01" />
            </label>
            <label class="field">
              <span>固定支出</span>
              <input v-model="snapshotForm.fixedExpense" type="number" step="0.01" />
            </label>
            <label class="field">
              <span>现金总额</span>
              <input v-model="snapshotForm.cashTotal" type="number" step="0.01" />
            </label>
            <label class="field">
              <span>投资总额</span>
              <input v-model="snapshotForm.investmentTotal" type="number" step="0.01" />
            </label>
            <label class="field">
              <span>负债总额</span>
              <input v-model="snapshotForm.liabilityTotal" type="number" step="0.01" />
            </label>
            <label class="field">
              <span>账户总值</span>
              <input v-model="snapshotForm.grossAccountValue" type="number" step="0.01" />
            </label>
            <label class="field">
              <span>盈亏</span>
              <input v-model="snapshotForm.profitLoss" type="number" step="0.01" />
            </label>
            <label class="field">
              <span>净资产</span>
              <input v-model="snapshotForm.netWorth" type="number" step="0.01" />
            </label>
            <label class="field">
              <span>公积金 / 公共资金</span>
              <input v-model="snapshotForm.publicFunds" type="number" step="0.01" />
            </label>
            <label class="field">
              <span>额外金额</span>
              <input v-model="snapshotForm.extraAmount" type="number" step="0.01" />
            </label>
            <label class="field">
              <span>余额</span>
              <input v-model="snapshotForm.balance" type="number" step="0.01" />
            </label>
            <label class="field field-wide">
              <span>备注</span>
              <textarea v-model="snapshotForm.note" rows="3" />
            </label>
            <label class="field field-wide">
              <span>补充说明</span>
              <textarea v-model="snapshotForm.remark" rows="3" />
            </label>
          </div>

          <div class="detail-block">
            <div class="detail-head">
              <h3>账户明细</h3>
              <span>{{ snapshotForm.details.length }} 个账户</span>
            </div>

            <div class="editor-detail-grid">
              <article
                v-for="detail in snapshotForm.details"
                :key="detail.accountCode"
                class="editor-detail-card"
                :data-tone="formTone(detail)"
              >
                <div class="detail-top">
                  <strong>{{ detail.accountName }}</strong>
                  <span>{{ detail.accountCode }}</span>
                </div>

                <div class="detail-meta">
                  <span>{{ detail.accountType }}</span>
                  <span>{{ detail.currencyCode }}</span>
                </div>

                <label class="field compact-field">
                  <span>金额</span>
                  <input v-model="detail.amount" type="number" step="0.01" />
                </label>
              </article>
            </div>
          </div>
        </section>

        <section class="content-card">
          <div class="section-head">
            <div>
              <p class="eyebrow">All Snapshots</p>
              <h2>全部快照</h2>
            </div>
            <span class="count-chip">{{ snapshots.length }} 条</span>
          </div>

          <div v-if="loading" class="state-panel">正在加载数据...</div>
          <div v-else-if="error" class="state-panel error">{{ error }}</div>
          <div v-else-if="snapshots.length === 0" class="state-panel">暂无数据</div>

          <div v-else class="snapshot-list">
            <article v-for="snapshot in snapshots" :key="snapshot.id" class="snapshot-item">
              <button class="snapshot-row" type="button" @click="toggleSnapshot(snapshot.id)">
                <span class="snapshot-date">{{ snapshot.snapshotDate }}</span>
                <span class="snapshot-networth" :class="summaryTone(snapshot.netWorth)">
                  {{ formatAmount(snapshot.netWorth) }}
                </span>
              </button>

              <div v-if="expandedId === snapshot.id" class="snapshot-body">
                <div class="snapshot-toolbar">
                  <div class="button-row">
                    <button class="ghost-button" type="button" @click="openEditSnapshotForm(snapshot)">编辑</button>
                    <button
                      class="danger-button"
                      type="button"
                      :disabled="deletingId === snapshot.id"
                      @click="deleteSnapshot(snapshot.id)"
                    >
                      {{ deletingId === snapshot.id ? "删除中..." : "删除" }}
                    </button>
                  </div>
                </div>

                <div class="snapshot-stats-grid">
                  <div class="stat-card">
                    <span class="stat-label">收入</span>
                    <span class="stat-value positive">{{ formatAmount(snapshot.income) }}</span>
                  </div>
                  <div class="stat-card">
                    <span class="stat-label">固定支出</span>
                    <span class="stat-value negative">{{ formatAmount(snapshot.fixedExpense) }}</span>
                  </div>
                  <div class="stat-card">
                    <span class="stat-label">负债总额</span>
                    <span class="stat-value negative">{{ formatAmount(snapshot.liabilityTotal) }}</span>
                  </div>
                  <div class="stat-card">
                    <span class="stat-label">盈亏</span>
                    <span class="stat-value" :class="summaryTone(snapshot.profitLoss)">{{ formatAmount(snapshot.profitLoss) }}</span>
                  </div>
                  <div class="stat-card">
                    <span class="stat-label">公积金</span>
                    <span class="stat-value">{{ formatAmount(snapshot.publicFunds) }}</span>
                  </div>
                  <div class="stat-card">
                    <span class="stat-label">余额</span>
                    <span class="stat-value">{{ formatAmount(snapshot.balance) }}</span>
                  </div>
                </div>

                <div class="detail-block">
                  <div class="detail-head">
                    <h3>账户明细</h3>
                    <span>{{ snapshot.details.length }} 个账户</span>
                  </div>

                  <div class="detail-grid">
                    <article
                      v-for="detail in snapshot.details"
                      :key="`${snapshot.id}-${detail.accountCode}`"
                      class="detail-card"
                      :data-tone="detailTone(detail)"
                    >
                      <div class="detail-top">
                        <strong>{{ detail.accountName }}</strong>
                        <span class="detail-balance" :class="detail.balanceDirection === 'DEBT' ? 'debt' : 'asset'">
                          {{ formatAmount(detail.amount, detail.currencyCode) }}
                        </span>
                      </div>
                      <div class="detail-meta">
                        <span>{{ detail.accountType }}</span>
                        <span>{{ detail.currencyCode }}</span>
                      </div>
                    </article>
                  </div>
                </div>
              </div>
            </article>
          </div>
        </section>
      </template>

      <template v-if="currentPage === ACCOUNT_LIST_PAGE">
        <section class="content-card">
          <div class="section-head">
            <div>
              <p class="eyebrow">All Accounts</p>
              <h2>账户列表</h2>
            </div>
            <span class="count-chip">{{ accounts.length }} 个</span>
          </div>

          <div v-if="loading" class="state-panel">正在加载账户...</div>
          <div v-else-if="error" class="state-panel error">{{ error }}</div>
          <div v-else class="account-table-wrap">
            <table class="account-table">
              <thead>
                <tr>
                  <th>编码</th>
                  <th>名称</th>
                  <th>类型</th>
                  <th>方向</th>
                  <th>币种</th>
                  <th>排序</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="account in accounts" :key="account.id">
                  <td>{{ account.accountCode }}</td>
                  <td>
                    <strong>{{ account.accountName }}</strong>
                    <div class="table-sub">{{ account.institutionName || account.remark || "--" }}</div>
                  </td>
                  <td>{{ account.accountType }}</td>
                  <td>{{ account.balanceDirection }}</td>
                  <td>{{ account.currencyCode }}</td>
                  <td>{{ account.sortOrder }}</td>
                  <td>{{ account.enabled ? "启用" : "停用" }}</td>
                  <td>
                    <div class="button-row">
                      <button class="ghost-button small-button" type="button" @click="openEditAccountForm(account)">
                        编辑
                      </button>
                      <button
                        class="danger-button small-button"
                        type="button"
                        :disabled="deletingAccountId === account.id"
                        @click="deleteAccount(account.id)"
                      >
                        {{ deletingAccountId === account.id ? "删除中..." : "删除" }}
                      </button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>

      <template v-else-if="currentPage === ACCOUNTS_PAGE">
        <section v-if="isAccountEditing" class="content-card form-card">
          <div class="section-head">
            <div>
              <p class="eyebrow">Account Editor</p>
              <h2>{{ isAccountCreating ? "新增账户" : "编辑账户" }}</h2>
            </div>
            <div class="button-row">
              <button class="ghost-button" type="button" @click="cancelAccountEditing">取消</button>
              <button class="primary-button" type="button" :disabled="saving" @click="submitAccountForm">
                {{ saving ? "保存中..." : accountSubmitLabel }}
              </button>
            </div>
          </div>

          <div class="form-grid">
            <label class="field">
              <span>账户编码</span>
              <input v-model="accountForm.accountCode" type="text" />
            </label>
            <label class="field">
              <span>账户名称</span>
              <input v-model="accountForm.accountName" type="text" />
            </label>
            <label class="field">
              <span>账户类型</span>
              <input v-model="accountForm.accountType" type="text" />
            </label>
            <label class="field">
              <span>方向</span>
              <select v-model="accountForm.balanceDirection">
                <option value="ASSET">ASSET</option>
                <option value="DEBT">DEBT</option>
              </select>
            </label>
            <label class="field">
              <span>币种</span>
              <select v-model="accountForm.currencyCode">
                <option value="CNY">CNY</option>
                <option value="USD">USD</option>
                <option value="HKD">HKD</option>
              </select>
            </label>
            <label class="field">
              <span>排序</span>
              <input v-model="accountForm.sortOrder" type="number" step="1" />
            </label>
            <label class="field">
              <span>机构</span>
              <input v-model="accountForm.institutionName" type="text" />
            </label>
            <label class="field">
              <span>归属人</span>
              <input v-model="accountForm.ownerName" type="text" />
            </label>
            <label class="field field-wide">
              <span>备注</span>
              <input v-model="accountForm.remark" type="text" />
            </label>
            <label class="field switch-field">
              <span>启用</span>
              <input v-model="accountForm.enabled" type="checkbox" />
            </label>
          </div>
        </section>
      </template>
    </main>
  </div>
</template>
