<script setup>
defineProps({
  accountForm: {
    type: Object,
    required: true
  },
  summaryParentOptions: {
    type: Array,
    required: true
  }
});
</script>

<template>
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
      <span>分类</span>
      <select v-model="accountForm.categoryGroup">
        <option value="CASH">CASH</option>
        <option value="INVESTMENT">INVESTMENT</option>
        <option value="LIABILITY">LIABILITY</option>
      </select>
    </label>
    <label class="field">
      <span>账户类型</span>
      <input v-model="accountForm.accountType" type="text" />
    </label>
    <label class="field">
      <span>父账户</span>
      <select v-model="accountForm.parentAccountId" :disabled="accountForm.summaryAccount">
        <option value="">无</option>
        <option v-for="account in summaryParentOptions" :key="account.id" :value="String(account.id)">
          {{ account.accountName }}
        </option>
      </select>
    </label>
    <label class="field switch-field">
      <span>汇总账户</span>
      <input v-model="accountForm.summaryAccount" type="checkbox" />
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
      <input v-model="accountForm.sortOrder" type="number" step="1" @wheel.prevent />
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

  <div class="form-inline-note">
    负债类账户（例如待还信用卡）请设置为 `LIABILITY + DEBT`，录入金额时填写正数，系统会在计算净资产时自动扣减。
  </div>
</template>
