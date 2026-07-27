<script setup>
defineProps({
  snapshotForm: {
    type: Object,
    required: true
  },
  categoryGroupLabel: {
    type: Function,
    required: true
  },
  formTone: {
    type: Function,
    required: true
  }
});
</script>

<template>
  <div class="form-grid snapshot-editor-panel">
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

  <div class="detail-summary snapshot-editor-summary">
    <div class="summary-item">
      <span class="item-label">自动汇总</span>
      <span class="item-value large">{{ snapshotForm.details.length }} 个账户明细参与计算</span>
    </div>
    <div class="summary-item">
      <span class="item-label">说明</span>
      <span class="item-value muted">
        现金、投资、负债、账户总值和净资产会在保存时由系统自动计算；负债类账户请填写正数，系统会自动扣减。
      </span>
    </div>
  </div>

  <div class="detail-block snapshot-editor-details">
    <div class="detail-head snapshot-editor-head">
      <h3>账户明细</h3>
      <span>{{ snapshotForm.details.length }} 个账户</span>
    </div>

    <div class="editor-detail-grid snapshot-editor-grid">
      <article
        v-for="detail in snapshotForm.details"
        :key="detail.accountId ?? detail.accountCode"
        class="editor-detail-card snapshot-editor-card"
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

        <p v-if="detail.categoryGroup === 'LIABILITY'" class="detail-note">
          这是负债账户，金额填正数，保存后会自动从净资产中扣减。
        </p>
      </article>
    </div>
  </div>
</template>
