<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import SnapshotEditor from "./components/SnapshotEditor.vue";
import AccountEditor from "./components/AccountEditor.vue";

const HOME_PAGE = "home";
const SNAPSHOTS_PAGE = "snapshots";
const ACCOUNT_LIST_PAGE = "account-list";
const RECORD_ENTRY_PAGE = "record-entry";
const RECORD_ENTRY_TAG_ADJUSTMENTS_STORAGE_KEY = "water.recordEntryTagAdjustments.v1";

const loading = ref(true);
const saving = ref(false);
const error = ref("");
const formError = ref("");
const formSuccess = ref("");
const currentPage = ref(resolvePageFromHash());

const snapshots = ref([]);
const accountDefinitions = ref([]);
const accounts = ref([]);
const expandedId = ref(null);
const editingId = ref(null);
const deletingId = ref(null);
const accountEditingId = ref(null);
const accountDeletingId = ref(null);
const netWorthChartRef = ref(null);
let netWorthChartInstance = null;
let formSuccessTimer = null;

const snapshotForm = reactive(createEmptySnapshotForm());
const accountForm = reactive(createEmptyAccountForm());
const accountListFilters = reactive(createAccountListFilters());
const recordEntryForm = reactive(createEmptyRecordEntryForm());
const expandedAccountIds = ref([]);
const expandedRemarkIds = ref([]);
const collapsedRecordEntryIds = ref([]);
const expandedRecordTagNames = ref([]);
const recordEntryTagAdjustments = reactive(loadRecordEntryTagAdjustments());

watch(recordEntryTagAdjustments, persistRecordEntryTagAdjustments, { deep: true });

const latestSnapshot = computed(() => snapshots.value[0] ?? null);
const previousRecordSnapshot = computed(() => {
  if (!recordEntryForm.snapshotDate) {
    return null;
  }

  return snapshots.value.find((snapshot) => snapshot.snapshotDate < recordEntryForm.snapshotDate) ?? null;
});
const totalAssets = computed(() => {
  if (!latestSnapshot.value) {
    return null;
  }
  return calculateSnapshotTotalAssets(latestSnapshot.value);
});
const latestDebts = computed(() => numberValue(latestSnapshot.value?.liabilityTotal));
const latestNetWorth = computed(() => latestSnapshot.value?.netWorth ?? null);
const categoryGroupLabels = {
  CASH: "现金",
  INVESTMENT: "投资",
  LIABILITY: "借贷"
};
const accountGroupOrder = ["CASH", "INVESTMENT", "LIABILITY"];
const recordEntryTagOrder = ["可支配资产", "不可支配资产", "风险资产", "固收", "现金"];
const accountIdMap = computed(() => new Map(accounts.value.map((account) => [account.id, account])));
const latestSnapshotDetailMap = computed(() => {
  const map = new Map();

  for (const detail of latestSnapshot.value?.details ?? []) {
    if (detail.accountId !== null && detail.accountId !== undefined) {
      map.set(Number(detail.accountId), detail);
      continue;
    }

    const account = accounts.value.find(
      (item) => item.accountCode === detail.accountCode && Boolean(item.summaryAccount) === Boolean(detail.summaryAccount)
    );
    if (account) {
      map.set(account.id, detail);
    }
  }

  return map;
});
const previousRecordDetailMap = computed(() => {
  const map = new Map();

  for (const detail of previousRecordSnapshot.value?.details ?? []) {
    if (detail.accountId !== null && detail.accountId !== undefined) {
      map.set(Number(detail.accountId), detail);
    }
  }

  return map;
});
const filteredAccounts = computed(() => {
  const query = accountListFilters.query.trim().toLowerCase();

  return accounts.value.filter((account) => {
    if (accountListFilters.group !== "ALL" && account.categoryGroup !== accountListFilters.group) {
      return false;
    }

    if (accountListFilters.status === "ENABLED" && !account.enabled) {
      return false;
    }
    if (accountListFilters.status === "DISABLED" && account.enabled) {
      return false;
    }

    if (accountListFilters.structure === "SUMMARY" && !account.summaryAccount) {
      return false;
    }
    if (accountListFilters.structure === "DETAIL" && account.summaryAccount) {
      return false;
    }

    if (!query) {
      return true;
    }

    const parentName = accountIdMap.value.get(account.parentAccountId)?.accountName ?? "";
    const haystack = [
      account.accountCode,
      account.accountName,
      account.accountType,
      account.balanceDirection,
      account.currencyCode,
      account.institutionName,
      account.ownerName,
      account.remark,
      ...(account.tags ?? []),
      parentName,
      categoryGroupLabel(account.categoryGroup)
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase();

    return haystack.includes(query);
  });
});
const visibleAccounts = computed(() => {
  const visibleIds = new Set();

  for (const account of filteredAccounts.value) {
    let current = account;
    while (current) {
      if (visibleIds.has(current.id)) {
        break;
      }
      visibleIds.add(current.id);
      current = current.parentAccountId ? accountIdMap.value.get(current.parentAccountId) ?? null : null;
    }
  }

  return accounts.value.filter((account) => visibleIds.has(account.id));
});
const groupedAccounts = computed(() =>
  accountGroupOrder.map((group) => {
    const items = visibleAccounts.value.filter((account) => account.categoryGroup === group);
    const itemsByParent = new Map();

    for (const item of items) {
      const key = item.parentAccountId ?? "root";
      const bucket = itemsByParent.get(key) ?? [];
      bucket.push(item);
      itemsByParent.set(key, bucket);
    }

    const roots = itemsByParent.get("root") ?? [];

    return {
      key: group,
      label: categoryGroupLabels[group],
      items,
      roots,
      itemsByParent
    };
  })
);
const summaryParentOptions = computed(() => accounts.value.filter((account) => account.summaryAccount));
const accountListSummary = computed(() => ({
  total: visibleAccounts.value.length,
  enabled: visibleAccounts.value.filter((account) => account.enabled).length,
  summary: visibleAccounts.value.filter((account) => account.summaryAccount).length,
  withBalance: visibleAccounts.value.filter((account) => account.latestAmount !== null && account.latestAmount !== undefined).length
}));
const accountTableRows = computed(() =>
  groupedAccounts.value.flatMap((group) => {
    const rows = [];
    const visit = (parentId, level) => {
      const children = group.itemsByParent.get(parentId ?? "root") ?? [];
      for (const child of children) {
        const childAccounts = group.itemsByParent.get(child.id) ?? [];
        rows.push({
          ...child,
          level,
          isChild: level > 0,
          hasChildren: childAccounts.length > 0,
          isExpanded: childAccounts.length > 0 && isAccountExpanded(child.id)
        });
        if (childAccounts.length > 0 && isAccountExpanded(child.id)) {
          visit(child.id, level + 1);
        }
      }
    };

    visit(null, 0);
    return rows.map((account, index) => ({
      ...account,
      showCategoryGroup: index === 0,
      categoryGroupRowSpan: index === 0 ? rows.length : 0
    }));
  })
);
const accountCardGroups = computed(() =>
  groupedAccounts.value.flatMap((group) =>
    group.roots.map((root) => ({
      ...root,
      groupLabel: group.label,
      children: group.itemsByParent.get(root.id) ?? [],
      isExpanded: isAccountExpanded(root.id)
    }))
  )
);
const recordEntryEnabledAccounts = computed(() =>
  accounts.value
    .filter((account) => account.enabled)
    .slice()
    .sort((a, b) => {
      const groupCompare = accountGroupOrder.indexOf(a.categoryGroup) - accountGroupOrder.indexOf(b.categoryGroup);
      if (groupCompare !== 0) {
        return groupCompare;
      }
      const parentCompare = (a.parentAccountId ?? 0) - (b.parentAccountId ?? 0);
      if (parentCompare !== 0) {
        return parentCompare;
      }
      const sortCompare = Number(a.sortOrder ?? 0) - Number(b.sortOrder ?? 0);
      if (sortCompare !== 0) {
        return sortCompare;
      }
      return String(a.accountName ?? "").localeCompare(String(b.accountName ?? ""), "zh-CN");
    })
);
const recordEntryAccountChildrenMap = computed(() => {
  const map = new Map();

  for (const account of recordEntryEnabledAccounts.value) {
    const key = account.parentAccountId ?? "root";
    const bucket = map.get(key) ?? [];
    bucket.push(account);
    map.set(key, bucket);
  }

  return map;
});
const recordEntryTopLevelAccounts = computed(() =>
  recordEntryEnabledAccounts.value.filter((account) => account.parentAccountId === null)
);
const recordEntryRootGroups = computed(() =>
  accountGroupOrder.map((group) => ({
    key: group,
    label: categoryGroupLabels[group],
    roots: recordEntryTopLevelAccounts.value.filter((account) => account.categoryGroup === group)
  }))
);
const recordEntryCategoryTotals = computed(() =>
  Object.fromEntries(
    accountGroupOrder.map((group) => [
      group,
      roundToSingleDecimal(
        recordEntryTopLevelAccounts.value
          .filter((account) => account.categoryGroup === group)
          .reduce((sum, account) => sum + recordEntrySignedAmount(account), 0)
      )
    ])
  )
);
const previousRecordCategoryTotals = computed(() =>
  Object.fromEntries(
    accountGroupOrder.map((group) => [group, snapshotCategorySignedTotal(previousRecordSnapshot.value, group)])
  )
);
const recordEntryNetWorth = computed(() =>
  roundToSingleDecimal(
    recordEntryTopLevelAccounts.value.reduce((sum, account) => sum + recordEntrySignedAmount(account), 0)
  )
);
const recordEntrySummaryDeltas = computed(() => ({
  CASH: roundToSingleDecimal(numberValue(recordEntryCategoryTotals.value.CASH) - numberValue(previousRecordCategoryTotals.value.CASH)),
  INVESTMENT: roundToSingleDecimal(
    numberValue(recordEntryCategoryTotals.value.INVESTMENT) - numberValue(previousRecordCategoryTotals.value.INVESTMENT)
  ),
  LIABILITY: roundToSingleDecimal(
    numberValue(recordEntryCategoryTotals.value.LIABILITY) - numberValue(previousRecordCategoryTotals.value.LIABILITY)
  ),
  NET_WORTH: roundToSingleDecimal(recordEntryNetWorth.value - snapshotNetWorth(previousRecordSnapshot.value))
}));
const recordEntryTagTotals = computed(() => {
  const totals = new Map();

  for (const account of recordEntryEnabledAccounts.value) {
    if (Boolean(account.summaryAccount) || isRecordEntryHiddenMirrorChild(account) || !account.tags?.length) {
      continue;
    }

    const amount = recordEntrySignedAmount(account);
    for (const tag of account.tags) {
      const item = totals.get(tag) ?? {
        tag,
        total: 0,
        accountCount: 0,
        accounts: []
      };
      item.total = roundToSingleDecimal(item.total + amount);
      item.accounts.push({
        account,
        amount
      });
      item.accountCount = item.accounts.length;
      totals.set(tag, item);
    }
  }

  return [...totals.values()].map((item) => {
    const baseTotal = roundToSingleDecimal(item.total);
    const adjustment = roundToSingleDecimal(recordEntryTagAdjustments[item.tag]);
    return {
      ...item,
      baseTotal,
      adjustment,
      total: roundToSingleDecimal(baseTotal + adjustment),
      accounts: item.accounts.sort((a, b) => {
      const categoryCompare = accountGroupOrder.indexOf(a.account.categoryGroup) - accountGroupOrder.indexOf(b.account.categoryGroup);
      if (categoryCompare !== 0) {
        return categoryCompare;
      }
      const sortCompare = Number(a.account.sortOrder ?? 0) - Number(b.account.sortOrder ?? 0);
      if (sortCompare !== 0) {
        return sortCompare;
      }
      return String(a.account.accountName ?? "").localeCompare(String(b.account.accountName ?? ""), "zh-CN");
      })
    };
  }).sort((a, b) => {
    const aOrder = recordEntryTagOrder.indexOf(a.tag);
    const bOrder = recordEntryTagOrder.indexOf(b.tag);
    const aRank = aOrder === -1 ? recordEntryTagOrder.length : aOrder;
    const bRank = bOrder === -1 ? recordEntryTagOrder.length : bOrder;
    if (aRank !== bRank) {
      return aRank - bRank;
    }

    const totalCompare = Math.abs(b.total) - Math.abs(a.total);
    if (totalCompare !== 0) {
      return totalCompare;
    }
    return a.tag.localeCompare(b.tag, "zh-CN");
  });
});
const areAllRecordTagsExpanded = computed(() =>
  recordEntryTagTotals.value.length > 0 &&
  recordEntryTagTotals.value.every((item) => isRecordTagExpanded(item.tag))
);
const recordEntryChangeOverview = computed(() => {
  const overviewAccounts = recordEntryEnabledAccounts.value
    .filter((account) => !Boolean(account.summaryAccount) && !isRecordEntryHiddenMirrorChild(account));
  const rows = overviewAccounts
    .map((account) => {
      const currentAmount = recordEntryResolvedAmount(account);
      const previousAmount = previousRecordAmount(account);
      const delta = recordEntryDelta(account);

      return {
        account,
        displayAccount: recordEntryPrimaryInputAccount(account),
        currentAmount,
        previousAmount,
        delta
      };
    })
    .filter((item) => item.delta !== 0);

  const increases = rows
    .filter((item) => item.delta > 0)
    .sort((a, b) => b.delta - a.delta);
  const decreases = rows
    .filter((item) => item.delta < 0)
    .sort((a, b) => a.delta - b.delta);

  return {
    increases,
    decreases,
    totalIncrease: roundToSingleDecimal(increases.reduce((sum, item) => sum + item.delta, 0)),
    totalDecrease: roundToSingleDecimal(decreases.reduce((sum, item) => sum + Math.abs(item.delta), 0)),
    total: overviewAccounts.length,
    changed: rows.length,
    unchanged: Math.max(overviewAccounts.length - rows.length, 0)
  };
});

const isSnapshotCreating = computed(() => editingId.value === "new");
const isSnapshotEditing = computed(() => editingId.value !== null);
const snapshotSubmitLabel = computed(() => (isSnapshotCreating.value ? "新增快照" : "保存修改"));
const isAccountCreating = computed(() => accountEditingId.value === "new");
const isAccountEditing = computed(() => accountEditingId.value !== null);
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
    totalAssets: chartSnapshots.value.map((s) => calculateSnapshotTotalAssets(s)),
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
  if (formSuccessTimer) {
    clearTimeout(formSuccessTimer);
    formSuccessTimer = null;
  }
});

watch(currentPage, (page) => {
  if (page === HOME_PAGE) {
    renderHomeChart();
    return;
  }

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

  if (!chartData.value || !netWorthChartRef.value) {
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

function renderHomeChart() {
  if (currentPage.value !== HOME_PAGE) {
    return;
  }

  nextTick(() => {
    if (currentPage.value === HOME_PAGE) {
      initNetWorthChart();
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
    syncRecordEntryForm(accounts.value);

    if (snapshots.value.length > 0 && expandedId.value === null) {
      expandedId.value = snapshots.value[0].id;
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载失败";
  } finally {
    renderHomeChart();
    loading.value = false;
  }
}

function resolvePageFromHash() {
  const hash = window.location.hash;
  if (hash === "#/record-entry") return RECORD_ENTRY_PAGE;
  if (hash === "#/accounts") return ACCOUNT_LIST_PAGE;
  if (hash === "#/account-list") return ACCOUNT_LIST_PAGE;
  if (hash === "#/snapshots") return SNAPSHOTS_PAGE;
  return HOME_PAGE;
}

function syncPageFromHash() {
  currentPage.value = resolvePageFromHash();
}

function navigateTo(page) {
  currentPage.value = page;
  if (page === RECORD_ENTRY_PAGE) {
    window.location.hash = "/record-entry";
  } else if (page === ACCOUNT_LIST_PAGE) {
    window.location.hash = "/account-list";
  } else if (page === SNAPSHOTS_PAGE) {
    window.location.hash = "/snapshots";
  } else {
    window.history.pushState(null, "", window.location.pathname + window.location.search);
  }
  formError.value = "";
  formSuccess.value = "";
}

function showFormSuccess(message) {
  formSuccess.value = message;
  if (formSuccessTimer) {
    clearTimeout(formSuccessTimer);
  }
  formSuccessTimer = setTimeout(() => {
    formSuccess.value = "";
    formSuccessTimer = null;
  }, 2500);
}

function createEmptySnapshotForm() {
  return {
    snapshotDate: "",
    income: "",
    fixedExpense: "",
    profitLoss: "",
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
    categoryGroup: "CASH",
    accountType: "BANK_CARD",
    parentAccountId: "",
    summaryAccount: false,
    balanceDirection: "ASSET",
    currencyCode: "CNY",
    institutionName: "",
    ownerName: "",
    remark: "",
    sortOrder: 0,
    enabled: true,
    tagsText: ""
  };
}

function createAccountListFilters() {
  return {
    query: "",
    group: "ALL",
    status: "ALL",
    structure: "ALL"
  };
}

function createEmptyRecordEntryForm() {
  return {
    snapshotDate: new Date().toISOString().slice(0, 10),
    note: "",
    amounts: {},
    parentRemarks: {},
    parentManualOverrides: {}
  };
}

function resetSnapshotForm(next) {
  Object.assign(snapshotForm, createEmptySnapshotForm(), next);
}

function resetAccountForm(next) {
  Object.assign(accountForm, createEmptyAccountForm(), next);
}

function resetAccountListFilters() {
  Object.assign(accountListFilters, createAccountListFilters());
}

function syncRecordEntryForm(nextAccounts) {
  const nextAmounts = {};
  const nextParentRemarks = {};
  const nextParentManualOverrides = {};

  nextAccounts
    .filter((account) => account.enabled)
    .forEach((account) => {
      const key = accountKey(account);
      const existingAmount = recordEntryForm.amounts[key];
      const latestAmount = latestRecordDetail(account)?.amount;
      nextAmounts[key] =
        existingAmount !== undefined && existingAmount !== ""
          ? existingAmount
          : toFieldValue(latestAmount);

      if (isRecordEntryParentAccount(account)) {
        nextParentManualOverrides[key] = hasRecordEntryChildren(account)
          ? false
          : (recordEntryForm.parentManualOverrides[key] ?? false);
      }
    });

  nextAccounts
    .filter((account) => account.enabled && isRecordEntryParentAccount(account))
    .forEach((account) => {
      const key = accountKey(account);
      const existingRemark = recordEntryForm.parentRemarks[key];
      const latestRemark = latestRecordDetail(account)?.remark;
      nextParentRemarks[key] =
        existingRemark !== undefined && existingRemark !== ""
          ? existingRemark
          : (latestRemark ?? "");
    });

  recordEntryForm.amounts = nextAmounts;
  recordEntryForm.parentRemarks = nextParentRemarks;
  recordEntryForm.parentManualOverrides = nextParentManualOverrides;
}

function isAccountExpanded(accountId) {
  return expandedAccountIds.value.includes(accountId);
}

function toggleAccountChildren(accountId) {
  if (isAccountExpanded(accountId)) {
    expandedAccountIds.value = expandedAccountIds.value.filter((id) => id !== accountId);
    return;
  }
  expandedAccountIds.value = [...expandedAccountIds.value, accountId];
}

function isRecordEntryExpanded(accountId) {
  return !collapsedRecordEntryIds.value.includes(accountId);
}

function toggleRecordEntryChildren(accountId) {
  if (isRecordEntryExpanded(accountId)) {
    collapsedRecordEntryIds.value = [...collapsedRecordEntryIds.value, accountId];
    return;
  }
  collapsedRecordEntryIds.value = collapsedRecordEntryIds.value.filter((id) => id !== accountId);
}

function isRemarkExpanded(accountId) {
  return expandedRemarkIds.value.includes(accountId);
}

function toggleRecordRemark(accountId) {
  if (isRemarkExpanded(accountId)) {
    expandedRemarkIds.value = expandedRemarkIds.value.filter((id) => id !== accountId);
    return;
  }
  expandedRemarkIds.value = [...expandedRemarkIds.value, accountId];
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

function parseTagsText(value) {
  const seen = new Set();
  return String(value ?? "")
    .split(/[，,]/)
    .map((tag) => tag.trim())
    .filter((tag) => {
      if (!tag || seen.has(tag)) {
        return false;
      }
      seen.add(tag);
      return true;
    });
}

function nextAccountSortOrder(parent = null) {
  const related = parent
    ? accounts.value.filter((account) => account.parentAccountId === parent.id)
    : accounts.value;
  const maxSortOrder = related.reduce(
    (max, account) => Math.max(max, Number(account.sortOrder ?? 0)),
    Number(parent?.sortOrder ?? 0)
  );
  return maxSortOrder + (parent ? 1 : 10);
}

function roundToSingleDecimal(value) {
  return Math.round(numberValue(value) * 10) / 10;
}

function convertToRmb(amount, currencyCode) {
  if (amount === null || amount === undefined || amount === "") {
    return 0;
  }

  const numeric = numberValue(amount);
  if (currencyCode === "HKD") {
    return numeric * 0.87;
  }

  return numeric;
}

function receivablesValue(snapshot) {
  if (!snapshot?.details?.length) {
    return 0;
  }
  const receivables = snapshot.details.find((detail) => detail.accountCode === "RECEIVABLES");
  return numberValue(receivables?.amount);
}

function calculateSnapshotTotalAssets(snapshot) {
  if (!snapshot) {
    return null;
  }
  return numberValue(snapshot.grossAccountValue);
}

function snapshotCategorySignedTotal(snapshot, group) {
  if (!snapshot?.details?.length) {
    return 0;
  }

  return roundToSingleDecimal(
    snapshot.details
      .filter((detail) => detail.categoryGroup === group && detail.parentAccountId === null)
      .reduce((sum, detail) => {
        const amount = convertToRmb(detail.amount, detail.currencyCode);
        return sum + (detail.balanceDirection === "DEBT" ? amount * -1 : amount);
      }, 0)
  );
}

function snapshotNetWorth(snapshot) {
  return roundToSingleDecimal(
    accountGroupOrder.reduce((sum, group) => sum + snapshotCategorySignedTotal(snapshot, group), 0)
  );
}

function categoryGroupLabel(group) {
  return categoryGroupLabels[group] ?? group ?? "--";
}

function accountKey(account) {
  return String(account.id);
}

function hasAccountTag(account, tag) {
  return (account?.tags ?? []).some((item) => String(item).trim() === tag);
}

function isRecordTagExpanded(tag) {
  return expandedRecordTagNames.value.includes(tag);
}

function toggleRecordTag(tag) {
  if (isRecordTagExpanded(tag)) {
    expandedRecordTagNames.value = expandedRecordTagNames.value.filter((item) => item !== tag);
    return;
  }
  expandedRecordTagNames.value = [...expandedRecordTagNames.value, tag];
}

function toggleAllRecordTags() {
  expandedRecordTagNames.value = areAllRecordTagsExpanded.value
    ? []
    : recordEntryTagTotals.value.map((item) => item.tag);
}

function recordEntryChildren(account) {
  return recordEntryAccountChildrenMap.value.get(account.id) ?? [];
}

function recordEntrySameCodeChild(account) {
  return recordEntryChildren(account).find((child) => child.accountCode === account.accountCode) ?? null;
}

function recordEntryRollupChildren(account) {
  const children = recordEntryChildren(account);
  const sameCodeChild = recordEntrySameCodeChild(account);
  if (children.length > 1 && sameCodeChild) {
    return children.filter((child) => child.id !== sameCodeChild.id);
  }
  return children;
}

function isRecordEntryHiddenMirrorChild(account) {
  if (account.parentAccountId === null || account.parentAccountId === undefined) {
    return false;
  }

  const parent = accountIdMap.value.get(account.parentAccountId);
  return Boolean(parent && recordEntryChildren(parent).length > 1 && parent.accountCode === account.accountCode);
}

function recordEntryPrimaryInputAccount(account) {
  const children = recordEntryChildren(account);
  if (children.length === 0) {
    return account;
  }

  const sameCodeChild = recordEntrySameCodeChild(account);
  if (sameCodeChild) {
    return sameCodeChild;
  }

  return children.length === 1 ? children[0] : account;
}

function isRecordEntryFlattenedAccount(account) {
  return recordEntryChildren(account).length === 1;
}

function recordEntryPrimaryInputValue(account) {
  const ownValue = recordEntryForm.amounts[accountKey(account)];
  if (ownValue !== undefined && ownValue !== null && String(ownValue).trim() !== "") {
    return ownValue;
  }
  return recordEntryForm.amounts[accountKey(recordEntryPrimaryInputAccount(account))];
}

function recordEntryVisibleChildren(account) {
  const children = recordEntryChildren(account);
  const sameCodeChild = recordEntrySameCodeChild(account);
  if (!sameCodeChild) {
    return children;
  }
  return children.filter((child) => child.id !== sameCodeChild.id);
}

function hasRecordEntryChildren(account) {
  return !isRecordEntryFlattenedAccount(account) && recordEntryVisibleChildren(account).length > 0;
}

function isRecordEntryParentAccount(account) {
  return hasRecordEntryChildren(account) || Boolean(account.summaryAccount);
}

function recordEntryAggregateAmount(account) {
  const children = recordEntryRollupChildren(account);
  if (children.length === 0) {
    return roundToSingleDecimal(convertToRmb(recordEntryForm.amounts[accountKey(account)], account.currencyCode));
  }

  return roundToSingleDecimal(children.reduce((sum, child) => sum + recordEntryResolvedAmount(child), 0));
}

function latestRecordDetail(account) {
  return latestSnapshotDetailMap.value.get(account.id) ?? null;
}

function latestRecordDateLabel() {
  return latestSnapshot.value?.snapshotDate ?? "";
}

function previousRecordDateLabel() {
  return previousRecordSnapshot.value?.snapshotDate ?? "";
}

function hasRecordEntryValue(account) {
  const value = recordEntryForm.amounts[accountKey(account)];
  if (value !== undefined && value !== null && String(value).trim() !== "") {
    return true;
  }
  return recordEntryRollupChildren(account).some((child) => hasRecordEntryValue(child));
}

function recordEntryResolvedAmount(account) {
  const key = accountKey(account);
  const rawValue = recordEntryForm.amounts[key];
  const children = recordEntryRollupChildren(account);
  const hasChildInput = children.some((child) => hasRecordEntryValue(child));
  if (hasChildInput) {
    return recordEntryAggregateAmount(account);
  }

  if (rawValue !== undefined && rawValue !== null && String(rawValue).trim() !== "") {
    return roundToSingleDecimal(convertToRmb(rawValue, account.currencyCode));
  }

  return 0;
}

function recordEntrySignedAmount(account) {
  const amount = recordEntryResolvedAmount(account);
  return account.balanceDirection === "DEBT" ? amount * -1 : amount;
}

function previousRecordAmount(account) {
  const detail = previousRecordDetailMap.value.get(account.id);
  if (!detail) {
    return 0;
  }
  return roundToSingleDecimal(convertToRmb(detail.amount, detail.currencyCode ?? account.currencyCode));
}

function recordEntryDelta(account) {
  const amountDelta = recordEntryResolvedAmount(account) - previousRecordAmount(account);
  const impactDelta = account.balanceDirection === "DEBT" ? amountDelta * -1 : amountDelta;
  return roundToSingleDecimal(impactDelta);
}

function recordEntryDeltaLabel(account) {
  if (!previousRecordSnapshot.value) {
    return "暂无历史对比";
  }

  const amountDelta = roundToSingleDecimal(recordEntryResolvedAmount(account) - previousRecordAmount(account));
  const delta = recordEntryDelta(account);
  if (account.balanceDirection === "DEBT") {
    if (amountDelta > 0) {
      return `负债增加，净值减少 ${formatSignedDelta(Math.abs(delta), account.currencyCode)}`;
    }
    if (amountDelta < 0) {
      return `负债减少，净值增加 ${formatSignedDelta(Math.abs(delta), account.currencyCode)}`;
    }
    return "较上次无变化";
  }

  if (delta > 0) {
    return `较上次增加 ${formatSignedDelta(delta, account.currencyCode)}`;
  }
  if (delta < 0) {
    return `较上次减少 ${formatSignedDelta(Math.abs(delta), account.currencyCode)}`;
  }
  return "较上次无变化";
}

function recordEntryDeltaTone(account) {
  if (!previousRecordSnapshot.value) {
    return "";
  }

  const delta = recordEntryDelta(account);
  if (delta > 0) {
    return "positive";
  }
  if (delta < 0) {
    return "negative";
  }
  return "";
}

function summaryDeltaLabel(value, currencyCode = "CNY") {
  if (!previousRecordSnapshot.value) {
    return "暂无历史对比";
  }
  if (value > 0) {
    return `较上次增加 ${formatSignedDelta(value, currencyCode)}`;
  }
  if (value < 0) {
    return `较上次减少 ${formatSignedDelta(Math.abs(value), currencyCode)}`;
  }
  return "较上次无变化";
}

function summaryDeltaTone(value) {
  if (!previousRecordSnapshot.value) {
    return "";
  }
  if (value > 0) {
    return "positive";
  }
  if (value < 0) {
    return "negative";
  }
  return "";
}

function handleParentAmountInput(account, value) {
  const key = accountKey(account);
  recordEntryForm.amounts[key] = value;
  recordEntryForm.parentManualOverrides[key] = String(value ?? "").trim() !== "";
}

function handleRecordEntryPrimaryAmountInput(root, value) {
  const inputAccount = recordEntryPrimaryInputAccount(root);
  const rootKey = accountKey(root);
  recordEntryForm.amounts[rootKey] = value;
  recordEntryForm.parentManualOverrides[rootKey] = String(value ?? "").trim() !== "";

  const key = accountKey(inputAccount);
  if (key !== rootKey) {
    recordEntryForm.amounts[key] = value;
  }
}

watch(
  () => ({ ...recordEntryForm.amounts }),
  () => {
    for (const account of accounts.value.filter((item) => item.enabled && isRecordEntryParentAccount(item))) {
      const key = accountKey(account);
      if (recordEntryForm.parentManualOverrides[key]) {
        continue;
      }

      const children = recordEntryChildren(account);
      if (children.length === 0) {
        continue;
      }

      const rollupChildren = recordEntryRollupChildren(account);
      const hasChildInput = rollupChildren.some((child) => hasRecordEntryValue(child));
      if (!hasChildInput) {
        continue;
      }

      const nextAmount = recordEntryAggregateAmount(account).toFixed(1);
      if (recordEntryForm.amounts[key] !== nextAmount) {
        recordEntryForm.amounts[key] = nextAmount;
      }
    }
  },
  { deep: true }
);

function accountRowName(account) {
  return `${"\u00A0\u00A0".repeat(account.level ?? 0)}${account.accountName}`;
}

function accountParentName(account) {
  if (!account?.parentAccountId) {
    return "--";
  }
  return accountIdMap.value.get(account.parentAccountId)?.accountName ?? "--";
}

function accountStatusLabel(account) {
  return account.enabled ? "启用" : "停用";
}

function accountStructureLabel(account) {
  return account.summaryAccount ? "账户组" : "子账户";
}

function latestAmountTone(account) {
  if (account.latestAmount === null || account.latestAmount === undefined) {
    return "";
  }
  if (account.balanceDirection === "DEBT") {
    return "negative";
  }
  return Number(account.latestAmount) < 0 ? "negative" : "positive";
}

function accountDetailDraft(snapshot, account) {
  const existing = snapshot?.details?.find((detail) =>
    detail.accountId === account.id ||
    (detail.accountId === undefined && detail.accountCode === account.accountCode && Boolean(detail.summaryAccount) === Boolean(account.summaryAccount))
  );
  return {
    accountId: account.id,
    accountCode: account.accountCode,
    accountName: account.accountName,
    accountType: account.accountType,
    categoryGroup: account.categoryGroup,
    parentAccountId: account.parentAccountId,
    summaryAccount: account.summaryAccount,
    balanceDirection: account.balanceDirection,
    currencyCode: existing?.currencyCode ?? account.currencyCode,
    amount: toFieldValue(existing?.amount)
  };
}

function loadRecordEntryTagAdjustments() {
  try {
    const cached = window.localStorage.getItem(RECORD_ENTRY_TAG_ADJUSTMENTS_STORAGE_KEY);
    if (!cached) {
      return {};
    }

    const parsed = JSON.parse(cached);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return {};
    }

    return Object.fromEntries(
      Object.entries(parsed).filter(([tag, value]) =>
        tag.trim() && String(value ?? "").trim() !== "" && Number.isFinite(Number(value))
      )
    );
  } catch {
    return {};
  }
}

function persistRecordEntryTagAdjustments(values) {
  try {
    const cacheableValues = Object.fromEntries(
      Object.entries(values).filter(([tag, value]) =>
        tag.trim() && String(value ?? "").trim() !== "" && Number.isFinite(Number(value))
      )
    );
    window.localStorage.setItem(RECORD_ENTRY_TAG_ADJUSTMENTS_STORAGE_KEY, JSON.stringify(cacheableValues));
  } catch {
    // 本地缓存不可用时保持当前页面功能，不影响台账录入。
  }
}

function openCreateAccountGroupForm() {
  accountEditingId.value = "new";
  formError.value = "";
  resetAccountForm({
    summaryAccount: true,
    accountType: "SUMMARY",
    sortOrder: nextAccountSortOrder()
  });
}

function openCreateChildAccountForm(parent = null) {
  accountEditingId.value = "new";
  formError.value = "";
  resetAccountForm({
    categoryGroup: parent?.categoryGroup ?? "CASH",
    parentAccountId: parent ? String(parent.id) : "",
    summaryAccount: false,
    balanceDirection: parent?.balanceDirection ?? "ASSET",
    currencyCode: parent?.currencyCode ?? "CNY",
    accountType: parent?.accountType ?? "BANK_CARD",
    sortOrder: nextAccountSortOrder(parent)
  });
}

function openEditAccountForm(account) {
  accountEditingId.value = account.id;
  formError.value = "";
  resetAccountForm({
    accountCode: account.accountCode ?? "",
    accountName: account.accountName ?? "",
    categoryGroup: account.categoryGroup ?? "CASH",
    accountType: account.accountType ?? "",
    parentAccountId: account.parentAccountId === null || account.parentAccountId === undefined ? "" : String(account.parentAccountId),
    summaryAccount: Boolean(account.summaryAccount),
    balanceDirection: account.balanceDirection ?? "ASSET",
    currencyCode: account.currencyCode ?? "CNY",
    institutionName: account.institutionName ?? "",
    ownerName: account.ownerName ?? "",
    remark: account.remark ?? "",
    sortOrder: account.sortOrder ?? 0,
    enabled: account.enabled ?? true,
    tagsText: (account.tags ?? []).join(", ")
  });
}

function cancelAccountEditing() {
  accountEditingId.value = null;
  formError.value = "";
  resetAccountForm();
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
    profitLoss: toFieldValue(snapshot.profitLoss),
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
    profitLoss: parseNullableNumber(snapshotForm.profitLoss),
    publicFunds: parseNullableNumber(snapshotForm.publicFunds),
    extraAmount: parseNullableNumber(snapshotForm.extraAmount),
    balance: parseNullableNumber(snapshotForm.balance),
    note: snapshotForm.note.trim() || null,
    remark: snapshotForm.remark.trim() || null,
    details: snapshotForm.details.map((detail) => ({
      accountId: detail.accountId,
      accountCode: detail.accountCode,
      amount: parseNullableNumber(detail.amount),
      currencyCode: detail.currencyCode
    }))
  };
}

function buildAccountPayload() {
  const parentAccountId = accountForm.summaryAccount || !accountForm.parentAccountId
    ? null
    : Number(accountForm.parentAccountId);
  return {
    accountCode: accountForm.accountCode.trim(),
    accountName: accountForm.accountName.trim(),
    categoryGroup: accountForm.categoryGroup,
    accountType: accountForm.accountType.trim(),
    parentAccountId,
    summaryAccount: Boolean(accountForm.summaryAccount),
    balanceDirection: accountForm.balanceDirection,
    currencyCode: accountForm.currencyCode,
    institutionName: accountForm.institutionName.trim() || null,
    ownerName: accountForm.ownerName.trim() || null,
    remark: accountForm.remark.trim() || null,
    sortOrder: Number(accountForm.sortOrder ?? 0),
    enabled: Boolean(accountForm.enabled),
    tags: accountForm.summaryAccount ? [] : parseTagsText(accountForm.tagsText)
  };
}

function buildRecordEntryPayload() {
  const details = recordEntryEnabledAccounts.value
    .map((account) => {
      if (isRecordEntryHiddenMirrorChild(account)) {
        return null;
      }

      const key = accountKey(account);
      const rawAmount = recordEntryForm.amounts[key];
      const amount = isRecordEntryParentAccount(account) && hasRecordEntryValue(account)
        ? recordEntryResolvedAmount(account)
        : parseNullableNumber(rawAmount);
      if (amount === null) {
        return null;
      }

      const remark = isRecordEntryParentAccount(account)
        ? (recordEntryForm.parentRemarks[key]?.trim() || null)
        : null;

      return {
        accountId: account.id,
        accountCode: account.accountCode,
        amount,
        currencyCode: account.currencyCode,
        remark
      };
    })
    .filter(Boolean);

  return {
    snapshotDate: recordEntryForm.snapshotDate,
    income: null,
    fixedExpense: null,
    profitLoss: null,
    publicFunds: null,
    extraAmount: null,
    balance: null,
    note: recordEntryForm.note.trim() || null,
    remark: null,
    details
  };
}

async function submitSnapshotForm() {
  formError.value = "";
  formSuccess.value = "";
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
    showFormSuccess(isSnapshotCreating.value ? "新增快照成功" : "保存快照成功");
  } catch (err) {
    formError.value = err instanceof Error ? err.message : "保存失败";
  } finally {
    saving.value = false;
  }
}

async function submitAccountForm() {
  formError.value = "";
  formSuccess.value = "";
  if (!accountForm.accountCode.trim() || !accountForm.accountName.trim()) {
    formError.value = "账户编码和账户名称不能为空";
    return;
  }

  saving.value = true;
  try {
    const method = isAccountCreating.value ? "POST" : "PUT";
    const url = isAccountCreating.value ? "/api/accounts" : `/api/accounts/${accountEditingId.value}`;
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
    showFormSuccess(isAccountCreating.value ? "新增账户成功" : "保存账户成功");
  } catch (err) {
    formError.value = err instanceof Error ? err.message : "保存账户失败";
  } finally {
    saving.value = false;
  }
}

async function submitRecordEntry() {
  formError.value = "";
  formSuccess.value = "";
  if (!recordEntryForm.snapshotDate) {
    formError.value = "记录日期不能为空";
    return;
  }

  const payload = buildRecordEntryPayload();
  if (payload.details.length === 0) {
    formError.value = "请至少填写一个账户金额";
    return;
  }

  saving.value = true;
  try {
    let existingSnapshot = await findSnapshotByDate(recordEntryForm.snapshotDate);
    if (existingSnapshot) {
      await deleteSnapshotById(existingSnapshot.id);
    }

    try {
      await createSnapshotFromPayload(payload);
    } catch (err) {
      if (!(err instanceof Error) || !err.message.includes("409")) {
        throw err;
      }

      existingSnapshot = await findSnapshotByDate(recordEntryForm.snapshotDate);
      if (!existingSnapshot) {
        throw err;
      }

      await deleteSnapshotById(existingSnapshot.id);
      await createSnapshotFromPayload(payload);
    }

    await loadAll();
    formError.value = "";
    showFormSuccess("保存记录成功");
  } catch (err) {
    formError.value = err instanceof Error ? err.message : "保存记录失败";
  } finally {
    saving.value = false;
  }
}

async function findSnapshotByDate(snapshotDate) {
  const response = await fetch("/api/snapshots");
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `加载快照失败: ${response.status}`);
  }

  const snapshotList = await response.json();
  return snapshotList.find((snapshot) => snapshot.snapshotDate === snapshotDate) ?? null;
}

async function deleteSnapshotById(id) {
  const response = await fetch(`/api/snapshots/${id}`, { method: "DELETE" });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `删除旧记录失败: ${response.status}`);
  }
}

async function createSnapshotFromPayload(payload) {
  const response = await fetch("/api/snapshots", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `POST /api/snapshots failed: ${response.status}`);
  }

  return response.json();
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
  formSuccess.value = "";
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

async function deleteAccount(account) {
  if (!account) {
    return;
  }

  if (!window.confirm(`确认删除 ${account.accountName} 吗？已有历史记录的账户会被后端拦截。`)) {
    return;
  }

  accountDeletingId.value = account.id;
  formError.value = "";
  formSuccess.value = "";
  try {
    const response = await fetch(`/api/accounts/${account.id}`, { method: "DELETE" });
    if (!response.ok) {
      const message = await response.text();
      throw new Error(message || `删除账户失败: ${response.status}`);
    }

    if (accountEditingId.value === account.id) {
      cancelAccountEditing();
    }
    await loadAll();
    showFormSuccess("删除账户成功");
  } catch (err) {
    formError.value = err instanceof Error ? err.message : "删除账户失败";
  } finally {
    accountDeletingId.value = null;
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

function formatSignedDelta(value, currencyCode = "CNY") {
  return formatAmount(value, currencyCode);
}

function detailTone(detail) {
  if (detail.categoryGroup === "LIABILITY") {
    return "debt";
  }
  if (detail.categoryGroup === "INVESTMENT") {
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
  if (account.categoryGroup === "LIABILITY") {
    return "debt";
  }
  if (account.categoryGroup === "INVESTMENT") {
    return "investment";
  }
  return "asset";
}
</script>

<template>
  <div class="page-shell">
    <main class="layout" :class="{ 'layout-wide layout-compact': currentPage === RECORD_ENTRY_PAGE }">
      <section class="hero-card" :class="{ 'hero-card-compact': currentPage === RECORD_ENTRY_PAGE }">
        <div class="hero-top">
          <div>
            <p class="eyebrow">Personal Asset Snapshot</p>
            <div class="hero-heading">
              <h1>{{
                currentPage === HOME_PAGE
                  ? "资产概览"
                  : currentPage === SNAPSHOTS_PAGE
                    ? "快照列表"
                    : currentPage === RECORD_ENTRY_PAGE
                      ? "录入台账"
                      : "账户列表"
              }}</h1>
              <p v-if="currentPage === HOME_PAGE">净资产趋势图表</p>
              <p v-else-if="currentPage === SNAPSHOTS_PAGE">管理每日资产快照</p>
              <p v-else-if="currentPage === RECORD_ENTRY_PAGE">父子账户同屏录入，页面会自动汇总分类结果</p>
              <p v-else-if="currentPage === ACCOUNT_LIST_PAGE">查看所有账户</p>
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
              :class="{ 'active-tab': currentPage === RECORD_ENTRY_PAGE }"
              type="button"
              @click="navigateTo(RECORD_ENTRY_PAGE)"
            >
              录入台账
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

      <section v-if="formSuccess" class="content-card">
        <div class="state-panel success">{{ formSuccess }}</div>
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

          <SnapshotEditor
            :snapshot-form="snapshotForm"
            :category-group-label="categoryGroupLabel"
            :form-tone="formTone"
          />

          <div class="form-grid snapshot-editor-clean">
            <label class="field">
              <span>快照日期</span>
              <input v-model="snapshotForm.snapshotDate" type="date" />
            </label>
            <label class="field">
              <span>收入</span>
              <input v-model="snapshotForm.income" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>固定支出</span>
              <input v-model="snapshotForm.fixedExpense" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>盈亏</span>
              <input v-model="snapshotForm.profitLoss" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>公积金 / 公共资金</span>
              <input v-model="snapshotForm.publicFunds" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>额外金额</span>
              <input v-model="snapshotForm.extraAmount" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>余额</span>
              <input v-model="snapshotForm.balance" type="number" step="0.01" @wheel.prevent />
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

          <div class="detail-summary editor-summary-clean">
            <div class="summary-item">
              <span class="item-label">自动汇总</span>
              <span class="item-value large">{{ snapshotForm.details.length }} 个账户明细参与计算</span>
            </div>
            <div class="summary-item">
              <span class="item-label">说明</span>
              <span class="item-value muted">现金、投资、负债、账户总值和净资产会在保存时由系统自动计算。</span>
            </div>
          </div>

          <div class="form-grid snapshot-form-grid">
            <label class="field">
              <span>快照日期</span>
              <input v-model="snapshotForm.snapshotDate" type="date" />
            </label>
            <label class="field">
              <span>收入</span>
              <input v-model="snapshotForm.income" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>固定支出</span>
              <input v-model="snapshotForm.fixedExpense" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>现金总额</span>
            </label>
            <label class="field">
              <span>投资总额</span>
              <input v-model="snapshotForm.investmentTotal" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>负债总额</span>
              <input v-model="snapshotForm.liabilityTotal" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>账户总值</span>
              <input v-model="snapshotForm.grossAccountValue" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>盈亏</span>
              <input v-model="snapshotForm.profitLoss" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>净资产</span>
              <input v-model="snapshotForm.netWorth" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>公积金 / 公共资金</span>
              <input v-model="snapshotForm.publicFunds" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>额外金额</span>
              <input v-model="snapshotForm.extraAmount" type="number" step="0.01" @wheel.prevent />
            </label>
            <label class="field">
              <span>余额</span>
              <input v-model="snapshotForm.balance" type="number" step="0.01" @wheel.prevent />
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

          <div class="detail-summary editor-summary">
            <div class="summary-item">
              <span class="item-label">自动汇总</span>
              <span class="item-value large">{{ snapshotForm.details.length }} 个账户明细参与计算</span>
            </div>
            <div class="summary-item">
              <span class="item-label">说明</span>
              <span class="item-value muted">现金、投资、负债、账户总值和净资产会在保存时由系统自动计算。</span>
            </div>
          </div>

          <div class="detail-block">
            <div class="detail-head">
              <h3>账户明细</h3>
              <span>{{ snapshotForm.details.length }} 个账户</span>
            </div>

            <div class="editor-detail-grid">
              <article
                v-for="detail in snapshotForm.details"
                :key="detail.accountId ?? detail.accountCode"
                class="editor-detail-card"
                :data-tone="formTone(detail)"
              >
                <div class="detail-top">
                  <strong>{{ detail.accountName }}</strong>
                  <span>{{ detail.accountCode }}</span>
                </div>

                <div class="detail-meta">
                  <span>{{ categoryGroupLabel(detail.categoryGroup) }}</span>
                  <span>{{ detail.currencyCode }}</span>
                </div>

                <label class="field compact-field">
                  <span>金额</span>
                  <input v-model="detail.amount" type="number" step="0.01" @wheel.prevent />
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
              <p class="account-page-intro">先看每次快照的净资产、总资产和负债概览，展开后再处理细项。</p>
            </div>
            <div class="button-row">
              <span class="count-chip">{{ snapshots.length }} 条</span>
              <button class="primary-button" type="button" @click="openCreateSnapshotForm">新增快照</button>
            </div>
          </div>

          <div v-if="loading" class="state-panel">正在加载数据...</div>
          <div v-else-if="error" class="state-panel error">{{ error }}</div>
          <div v-else-if="snapshots.length === 0" class="state-panel">暂无数据</div>

          <div v-else class="snapshot-list">
            <article v-for="snapshot in snapshots" :key="snapshot.id" class="snapshot-item">
              <button class="snapshot-row" type="button" @click="toggleSnapshot(snapshot.id)">
                <div class="snapshot-row-main">
                  <div class="snapshot-row-title">
                    <span class="snapshot-date">{{ snapshot.snapshotDate }}</span>
                    <span class="snapshot-row-tag">{{ snapshot.details.length }} 个账户</span>
                  </div>
                  <div class="snapshot-row-meta">
                    <span>总资产 {{ formatAmount(calculateSnapshotTotalAssets(snapshot)) }}</span>
                    <span>负债 {{ formatAmount(snapshot.liabilityTotal) }}</span>
                    <span>余额 {{ formatAmount(snapshot.balance) }}</span>
                  </div>
                </div>
                <div class="snapshot-row-side">
                  <span class="snapshot-networth-label">净资产</span>
                  <span class="snapshot-networth" :class="summaryTone(snapshot.netWorth)">
                    {{ formatAmount(snapshot.netWorth) }}
                  </span>
                  <span class="snapshot-expand-indicator">{{ expandedId === snapshot.id ? "收起" : "展开" }}</span>
                </div>
              </button>

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
                  <div class="summary-item">
                    <span class="item-label">账户总值</span>
                    <span class="item-value">{{ formatAmount(calculateSnapshotTotalAssets(snapshot)) }}</span>
                  </div>
                  <div class="summary-item">
                    <span class="item-label">总负债</span>
                    <span class="item-value negative">{{ formatAmount(snapshot.liabilityTotal) }}</span>
                  </div>
                  <div class="summary-item">
                    <span class="item-label">账户数量</span>
                    <span class="item-value">{{ snapshot.details.length }} 个</span>
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
                      :key="`${snapshot.id}-${detail.accountId ?? detail.accountCode}`"
                      class="account-chip"
                      :data-tone="detailTone(detail)"
                    >
                      <span class="chip-name">{{ detail.accountName }}</span>
                      <span class="chip-name">{{ categoryGroupLabel(detail.categoryGroup) }}</span>
                      <span class="chip-amount" :class="detail.balanceDirection === 'DEBT' ? 'debt' : 'asset'">
                        {{ formatAmount(detail.amount, detail.currencyCode) }}
                      </span>
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
              <p class="account-page-intro">只保留账户结构本身，按分类分组展示，展开后看对应子账户。</p>
            </div>
            <div class="button-row account-list-actions">
              <button class="ghost-button small-button" type="button" @click="openCreateChildAccountForm()">
                新增子账户
              </button>
              <button class="primary-button small-button" type="button" @click="openCreateAccountGroupForm">
                新增账户组
              </button>
              <span class="count-chip">{{ visibleAccounts.length }} 个</span>
            </div>
          </div>

          <div v-if="loading" class="state-panel">正在加载账户...</div>
          <div v-else-if="error" class="state-panel error">{{ error }}</div>
          <div v-else class="account-list-panel">
            <div v-if="isAccountEditing" class="account-editor">
              <div class="detail-head">
                <div>
                  <p class="eyebrow">Account Form</p>
                  <h3>{{ isAccountCreating ? (accountForm.summaryAccount ? "新增账户组" : "新增子账户") : "编辑账户" }}</h3>
                </div>
                <div class="button-row">
                  <button class="ghost-button" type="button" @click="cancelAccountEditing">取消</button>
                  <button class="primary-button" type="button" :disabled="saving" @click="submitAccountForm">
                    {{ saving ? "保存中..." : accountSubmitLabel }}
                  </button>
                </div>
              </div>
              <AccountEditor
                :account-form="accountForm"
                :summary-parent-options="summaryParentOptions"
                :kind-locked="!isAccountCreating"
              />
            </div>

            <div v-if="visibleAccounts.length === 0" class="state-panel">
              当前没有可展示的账户
            </div>

            <div v-else class="account-groups">
              <section
                v-for="group in groupedAccounts.filter((item) => item.roots.length > 0)"
                :key="group.key"
                class="account-group-block"
              >
                <div class="account-group-header">
                  <div>
                    <h3>{{ group.label }}</h3>
                    <p>{{ group.roots.length }} 个主账户</p>
                  </div>
                </div>

                <div class="account-root-list">
                  <article
                    v-for="root in group.roots"
                    :key="root.id"
                    class="account-simple-card"
                  >
                    <div class="account-flat-row">
                      <div class="account-title-block">
                        <div class="account-list-tags">
                          <span class="account-list-tag">{{ group.label }}</span>
                          <span class="account-list-tag muted">{{ accountStructureLabel(root) }}</span>
                          <span v-if="!root.enabled" class="account-list-tag muted">停用</span>
                        </div>
                        <h3>{{ root.accountName }}</h3>
                        <p>{{ root.accountCode }}</p>
                      </div>

                      <div class="account-meta-pills account-meta-inline">
                        <span>{{ root.accountType }}</span>
                        <span>{{ root.currencyCode }}</span>
                        <span>{{ root.balanceDirection }}</span>
                        <span v-if="root.institutionName">{{ root.institutionName }}</span>
                        <span v-else-if="root.remark">{{ root.remark }}</span>
                      </div>

                      <div class="button-row account-row-actions">
                        <button
                          v-if="(group.itemsByParent.get(root.id) ?? []).length > 0"
                          class="ghost-button small-button"
                          type="button"
                          @click="toggleAccountChildren(root.id)"
                        >
                          {{ isAccountExpanded(root.id) ? "收起子账户" : `展开子账户 (${(group.itemsByParent.get(root.id) ?? []).length})` }}
                        </button>
                        <button
                          v-if="root.summaryAccount"
                          class="ghost-button small-button"
                          type="button"
                          @click="openCreateChildAccountForm(root)"
                        >
                          新增子账户
                        </button>
                        <button class="ghost-button small-button" type="button" @click="openEditAccountForm(root)">
                          编辑
                        </button>
                        <button
                          class="danger-button small-button"
                          type="button"
                          :disabled="accountDeletingId === root.id"
                          @click="deleteAccount(root)"
                        >
                          {{ accountDeletingId === root.id ? "删除中" : "删除" }}
                        </button>
                      </div>
                    </div>

                    <div
                      v-if="(group.itemsByParent.get(root.id) ?? []).length > 0 && isAccountExpanded(root.id)"
                      class="account-child-list"
                    >
                      <div
                        v-for="child in group.itemsByParent.get(root.id) ?? []"
                        :key="child.id"
                        class="account-child-row"
                      >
                        <div class="account-flat-row account-flat-row-child">
                          <div class="account-child-head">
                            <strong>{{ child.accountName }}</strong>
                            <span>{{ child.accountCode }}</span>
                          </div>
                          <div class="account-meta-pills compact account-meta-inline">
                            <span>{{ child.accountType }}</span>
                            <span>{{ child.currencyCode }}</span>
                            <span v-for="tag in child.tags ?? []" :key="`${child.id}-${tag}`">#{{ tag }}</span>
                            <span v-if="!child.enabled">停用</span>
                            <span v-if="child.institutionName">{{ child.institutionName }}</span>
                            <span v-else-if="child.remark">{{ child.remark }}</span>
                          </div>
                          <div class="button-row account-row-actions">
                            <button class="ghost-button small-button" type="button" @click="openEditAccountForm(child)">
                              编辑
                            </button>
                            <button
                              class="danger-button small-button"
                              type="button"
                              :disabled="accountDeletingId === child.id"
                              @click="deleteAccount(child)"
                            >
                              {{ accountDeletingId === child.id ? "删除中" : "删除" }}
                            </button>
                          </div>
                        </div>
                      </div>
                    </div>
                  </article>
                </div>
              </section>
            </div>
          </div>
        </section>
      </template>

      <template v-if="currentPage === RECORD_ENTRY_PAGE">
        <section class="content-card record-entry-shell">
          <div class="record-entry-top">
            <div class="record-entry-title">
              <h2>录入台账</h2>
              <p>父账户默认展开，金额变动和统计概览尽量同屏呈现。</p>
            </div>

            <div class="record-entry-actions">
              <label class="field record-date-field">
                <span>记录日期</span>
                <input v-model="recordEntryForm.snapshotDate" type="date" />
              </label>
              <div v-if="latestSnapshot" class="record-last-date">
                <span>上次记录</span>
                <strong>{{ latestRecordDateLabel() }}</strong>
              </div>
              <div v-if="previousRecordSnapshot" class="record-last-date">
                <span>对比基准</span>
                <strong>{{ previousRecordDateLabel() }}</strong>
              </div>
              <button class="primary-button" type="button" :disabled="saving" @click="submitRecordEntry">
                {{ saving ? "保存中..." : "保存记录" }}
              </button>
            </div>
          </div>

          <div class="record-entry-summary">
            <article class="record-summary-card" data-tone="cash">
              <span>现金</span>
              <strong>{{ formatAmount(recordEntryCategoryTotals.CASH) }}</strong>
              <em class="record-summary-delta" :class="summaryDeltaTone(recordEntrySummaryDeltas.CASH)">
                {{ summaryDeltaLabel(recordEntrySummaryDeltas.CASH) }}
              </em>
            </article>
            <article class="record-summary-card" data-tone="investment">
              <span>投资</span>
              <strong>{{ formatAmount(recordEntryCategoryTotals.INVESTMENT) }}</strong>
              <em class="record-summary-delta" :class="summaryDeltaTone(recordEntrySummaryDeltas.INVESTMENT)">
                {{ summaryDeltaLabel(recordEntrySummaryDeltas.INVESTMENT) }}
              </em>
            </article>
            <article class="record-summary-card" data-tone="debt">
              <span>借贷净额</span>
              <strong :class="summaryTone(recordEntryCategoryTotals.LIABILITY)">{{ formatAmount(recordEntryCategoryTotals.LIABILITY) }}</strong>
              <em class="record-summary-delta" :class="summaryDeltaTone(recordEntrySummaryDeltas.LIABILITY)">
                {{ summaryDeltaLabel(recordEntrySummaryDeltas.LIABILITY) }}
              </em>
            </article>
            <article class="record-summary-card" data-tone="networth">
              <span>净资产</span>
              <strong :class="summaryTone(recordEntryNetWorth)">{{ formatAmount(recordEntryNetWorth) }}</strong>
              <em class="record-summary-delta" :class="summaryDeltaTone(recordEntrySummaryDeltas.NET_WORTH)">
                {{ summaryDeltaLabel(recordEntrySummaryDeltas.NET_WORTH) }}
              </em>
            </article>
          </div>

          <div class="record-entry-groups">
            <section
              v-for="group in recordEntryRootGroups.filter((item) => item.roots.length > 0)"
              :key="group.key"
              class="record-group-card"
            >
              <div class="record-group-head">
                <div>
                  <h3>{{ group.label }}</h3>
                  <p>{{ group.roots.length }} 个父账户 / {{ recordEntryEnabledAccounts.filter((account) => account.categoryGroup === group.key).length }} 个账户</p>
                </div>
                <strong :class="summaryTone(recordEntryCategoryTotals[group.key])">{{ formatAmount(recordEntryCategoryTotals[group.key]) }}</strong>
              </div>

              <div class="record-parent-list">
                <article
                  v-for="root in group.roots"
                  :key="root.id"
                  class="record-parent-card"
                  :class="{ expanded: isRecordEntryExpanded(root.id) }"
                >
                  <div
                    v-if="hasRecordEntryChildren(root)"
                    class="record-parent-toggle"
                  >
                    <button
                      class="record-parent-trigger"
                      type="button"
                      @click="toggleRecordEntryChildren(root.id)"
                    >
                      <div class="record-parent-head">
                        <div class="record-parent-title">
                          <div class="record-parent-title-main">
                            <strong>{{ root.accountName }}</strong>
                            <button
                              class="record-remark-toggle"
                              type="button"
                              @click.stop="toggleRecordRemark(root.id)"
                            >
                              <span class="record-remark-icon">{{ isRemarkExpanded(root.id) ? "▾" : "▸" }}</span>
                            </button>
                          </div>
                          <span>{{ root.accountCode }}</span>
                        </div>
                        <div class="record-parent-meta">
                          <span>{{ root.currencyCode }}</span>
                          <span v-if="root.institutionName">{{ root.institutionName }}</span>
                          <span>{{ recordEntryVisibleChildren(root).length }} 个子账户</span>
                        </div>
                      </div>
                      <span class="record-parent-arrow">{{ isRecordEntryExpanded(root.id) ? "收起" : "展开" }}</span>
                    </button>
                    <div class="record-parent-total">
                      <strong class="record-computed-total">{{ formatAmount(recordEntryResolvedAmount(root), root.currencyCode) }}</strong>
                      <span class="record-delta" :class="recordEntryDeltaTone(root)">
                        {{ recordEntryDeltaLabel(root) }}
                      </span>
                    </div>
                  </div>

                  <div
                    v-else
                    class="record-single-row"
                    :class="{ 'is-volatile-account': hasAccountTag(recordEntryPrimaryInputAccount(root), '易变') }"
                  >
                    <div class="record-account-cell">
                      <div class="record-parent-title-main">
                        <div class="record-account-name-line">
                          <strong>{{ recordEntryPrimaryInputAccount(root).accountName }}</strong>
                          <span
                            v-if="hasAccountTag(recordEntryPrimaryInputAccount(root), '易变')"
                            class="record-volatile-badge"
                          >
                            易变
                          </span>
                        </div>
                        <button
                          class="record-remark-toggle"
                          type="button"
                          @click.stop="toggleRecordRemark(root.id)"
                        >
                          <span class="record-remark-icon">{{ isRemarkExpanded(root.id) ? "▾" : "▸" }}</span>
                        </button>
                      </div>
                      <span>{{ recordEntryPrimaryInputAccount(root).accountCode }}</span>
                    </div>
                    <span class="record-currency-chip">{{ recordEntryPrimaryInputAccount(root).currencyCode }}</span>
                    <div class="record-parent-total">
                      <input
                        :value="recordEntryPrimaryInputValue(root)"
                        class="record-amount-input"
                        type="number"
                        @wheel.prevent
                        step="0.01"
                        placeholder="0.00"
                        @input="handleRecordEntryPrimaryAmountInput(root, $event.target.value)"
                      />
                      <span class="record-delta" :class="recordEntryDeltaTone(root)">
                        {{ recordEntryDeltaLabel(root) }}
                      </span>
                    </div>
                  </div>

                  <div class="record-parent-remark-wrap">
                    <label v-if="isRemarkExpanded(root.id)" class="field record-parent-remark">
                      <input
                        v-model="recordEntryForm.parentRemarks[accountKey(root)]"
                        type="text"
                        :placeholder="latestRecordDetail(root)?.remark || '填写本次父账户备注'"
                      />
                    </label>
                  </div>

                  <div
                    v-if="hasRecordEntryChildren(root) && isRecordEntryExpanded(root.id)"
                    class="record-child-list"
                  >
                    <label
                      v-for="child in recordEntryVisibleChildren(root)"
                      :key="child.id"
                      class="record-child-row"
                      :class="{ 'is-volatile-account': hasAccountTag(child, '易变') }"
                    >
                      <div class="record-account-cell">
                        <div class="record-account-name-line">
                          <strong>{{ child.accountName }}</strong>
                          <span v-if="hasAccountTag(child, '易变')" class="record-volatile-badge">易变</span>
                        </div>
                        <span>{{ child.accountCode }}</span>
                      </div>
                      <span class="record-currency-chip">{{ child.currencyCode }}</span>
                      <div class="record-parent-total">
                        <input
                          v-model="recordEntryForm.amounts[accountKey(child)]"
                          class="record-amount-input"
                          type="number"
                          @wheel.prevent
                          step="0.01"
                          placeholder="0.00"
                        />
                        <span class="record-delta" :class="recordEntryDeltaTone(child)">{{ recordEntryDeltaLabel(child) }}</span>
                      </div>
                    </label>
                  </div>
                </article>
              </div>
            </section>
          </div>

          <div class="record-entry-note-inline">
            <label class="field">
              <span>备注</span>
              <input
                v-model="recordEntryForm.note"
                type="text"
                placeholder="可选：汇率、未到账资金、一次性调整"
              />
            </label>
          </div>

          <section class="record-tag-overview">
            <div class="record-tag-head">
              <h3>标签汇总</h3>
              <div class="record-tag-head-actions">
                <span>{{ recordEntryTagTotals.length }} 个标签</span>
                <button
                  v-if="recordEntryTagTotals.length > 0"
                  class="record-tag-toggle"
                  type="button"
                  @click="toggleAllRecordTags"
                >
                  {{ areAllRecordTagsExpanded ? "全部收起" : "全部展开" }}
                </button>
              </div>
            </div>
            <div v-if="recordEntryTagTotals.length > 0" class="record-tag-list">
              <article
                v-for="item in recordEntryTagTotals"
                :key="item.tag"
                class="record-tag-card"
                :class="{ expanded: isRecordTagExpanded(item.tag) }"
              >
                <div class="record-tag-card-main">
                  <div class="record-tag-title">
                    <span>#{{ item.tag }}</span>
                    <small>{{ item.accountCount }} 个子账户</small>
                  </div>
                  <strong :class="summaryTone(item.total)">{{ formatAmount(item.total) }}</strong>
                  <button class="record-tag-toggle" type="button" @click="toggleRecordTag(item.tag)">
                    {{ isRecordTagExpanded(item.tag) ? "收起" : "展开" }}
                  </button>
                </div>
                <div v-if="isRecordTagExpanded(item.tag)" class="record-tag-account-list">
                  <div
                    v-for="entry in item.accounts"
                    :key="`${item.tag}-${entry.account.id}`"
                    class="record-tag-account-row"
                  >
                    <div>
                      <strong>{{ entry.account.accountName }}</strong>
                      <span>{{ entry.account.accountCode }}</span>
                    </div>
                    <em :class="summaryTone(entry.amount)">{{ formatAmount(entry.amount) }}</em>
                  </div>
                  <label class="record-tag-adjustment-row">
                    <div>
                      <strong>临时调整</strong>
                      <span>原汇总 {{ formatAmount(item.baseTotal) }}，仅当前页面生效</span>
                    </div>
                    <div class="record-tag-adjustment-control">
                      <span>±</span>
                      <input
                        v-model="recordEntryTagAdjustments[item.tag]"
                        type="number"
                        step="0.01"
                        placeholder="0.00"
                        @wheel.prevent
                      />
                    </div>
                  </label>
                </div>
              </article>
            </div>
            <p v-else class="record-change-empty-panel">暂无带标签的子账户</p>
          </section>

          <section class="record-change-overview">
            <div class="record-change-head">
              <div>
                <h3>统计概览</h3>
                <p v-if="previousRecordSnapshot">对比 {{ previousRecordDateLabel() }}，快速看本次录入的账户变化。</p>
                <p v-else>选定日期之前暂无记录，保存下一次后这里会展示变化对比。</p>
              </div>
              <div v-if="previousRecordSnapshot" class="record-change-counts">
                <span class="positive">
                  {{ recordEntryChangeOverview.increases.length }} 项增加 · +{{ formatAmount(recordEntryChangeOverview.totalIncrease) }}
                </span>
                <span class="negative">
                  {{ recordEntryChangeOverview.decreases.length }} 项减少 · -{{ formatAmount(recordEntryChangeOverview.totalDecrease) }}
                </span>
                <span>{{ recordEntryChangeOverview.unchanged }} 项无变化</span>
              </div>
            </div>

            <div v-if="previousRecordSnapshot && recordEntryChangeOverview.changed > 0" class="record-change-columns">
              <article class="record-change-column" data-tone="increase">
                <div class="record-change-column-head">
                  <span>增加项</span>
                  <strong>{{ recordEntryChangeOverview.increases.length }} 项</strong>
                </div>
                <div v-if="recordEntryChangeOverview.increases.length > 0" class="record-change-list">
                  <div
                    v-for="item in recordEntryChangeOverview.increases"
                    :key="`increase-${item.account.id}`"
                    class="record-change-row"
                  >
                    <div class="record-change-account">
                      <strong>{{ item.displayAccount.accountName }}</strong>
                      <span>{{ categoryGroupLabel(item.displayAccount.categoryGroup) }} / {{ item.displayAccount.accountCode }}</span>
                    </div>
                    <div class="record-change-amount positive">
                      +{{ formatAmount(Math.abs(item.delta)) }}
                      <span>{{ formatAmount(item.previousAmount) }} -> {{ formatAmount(item.currentAmount) }}</span>
                    </div>
                  </div>
                </div>
                <p v-else class="record-change-empty">没有增加项</p>
              </article>

              <article class="record-change-column" data-tone="decrease">
                <div class="record-change-column-head">
                  <span>减少项</span>
                  <strong>{{ recordEntryChangeOverview.decreases.length }} 项</strong>
                </div>
                <div v-if="recordEntryChangeOverview.decreases.length > 0" class="record-change-list">
                  <div
                    v-for="item in recordEntryChangeOverview.decreases"
                    :key="`decrease-${item.account.id}`"
                    class="record-change-row"
                  >
                    <div class="record-change-account">
                      <strong>{{ item.displayAccount.accountName }}</strong>
                      <span>{{ categoryGroupLabel(item.displayAccount.categoryGroup) }} / {{ item.displayAccount.accountCode }}</span>
                    </div>
                    <div class="record-change-amount negative">
                      -{{ formatAmount(Math.abs(item.delta)) }}
                      <span>{{ formatAmount(item.previousAmount) }} -> {{ formatAmount(item.currentAmount) }}</span>
                    </div>
                  </div>
                </div>
                <p v-else class="record-change-empty">没有减少项</p>
              </article>
            </div>

            <div v-else-if="previousRecordSnapshot" class="record-change-empty-panel">
              本次所有账户与上次记录一致。
            </div>
            <div v-else class="record-change-empty-panel">
              暂无可对比的历史记录。
            </div>
          </section>
        </section>
      </template>
    </main>
  </div>
</template>
