<script setup>
defineProps({
  accountForm: {
    type: Object,
    required: true
  },
  summaryParentOptions: {
    type: Array,
    required: true
  },
  kindLocked: {
    type: Boolean,
    default: false
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
        <option value="CASH">现金</option>
        <option value="INVESTMENT">投资</option>
        <option value="LIABILITY">借贷</option>
      </select>
    </label>
    <label class="field">
      <span>账户类型</span>
      <input v-model="accountForm.accountType" type="text" />
    </label>
    <label class="field">
      <span>所属账户组</span>
      <select v-model="accountForm.parentAccountId" :disabled="accountForm.summaryAccount">
        <option value="">无</option>
        <option v-for="account in summaryParentOptions" :key="account.id" :value="String(account.id)">
          {{ account.accountName }}
        </option>
      </select>
    </label>
    <label class="field switch-field">
      <span>账户组</span>
      <input v-model="accountForm.summaryAccount" type="checkbox" :disabled="kindLocked" />
    </label>
    <label class="field">
      <span>方向</span>
      <select v-model="accountForm.balanceDirection">
        <option value="ASSET">资产</option>
        <option value="DEBT">负债</option>
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
      <span>标签</span>
      <input
        v-model="accountForm.tagsText"
        type="text"
        :disabled="accountForm.summaryAccount"
        placeholder="仅子账户使用，多个标签用逗号分隔"
      />
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
    账户组用于汇总展示和保存组级金额；子账户可以挂到某个账户组下，并用标签标记用途、平台或风险类型。
  </div>
</template>
