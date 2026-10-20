<script setup>
import { reactive, ref, watch } from 'vue';
import { requestJson } from '../api.js';

const props = defineProps({ entries: { type: Array, required: true }, tags: { type: Array, default: () => [] } });
const emit = defineEmits(['saved']);
const rate = ref('');
const usdRate = ref('');
const amounts = reactive({});
const busy = ref(false);
const message = ref('');
const failed = ref(false);
watch(() => [props.entries, props.tags], () => {
  rate.value = String(props.entries.find(e => e.dictType === 'EXCHANGE_RATE' && e.dictKey === 'HKD_CNY')?.value ?? '');
  usdRate.value = String(props.entries.find(e => e.dictType === 'EXCHANGE_RATE' && e.dictKey === 'USD_CNY')?.value ?? '');
  Object.keys(amounts).forEach(key => delete amounts[key]);
  props.tags.forEach(tag => { amounts[tag] = '0'; });
  props.entries.filter(e => e.dictType === 'TAG_ADJUSTMENT').forEach(e => { amounts[e.dictKey] = String(e.value); });
}, { immediate: true });

async function save() {
  message.value = '';
  failed.value = false;
  if (!Number.isFinite(Number(rate.value)) || Number(rate.value) <= 0) {
    failed.value = true;
    message.value = '港币汇率必须大于 0。';
    return;
  }
  const entries = [{ dictType: 'EXCHANGE_RATE', dictKey: 'HKD_CNY', value: rate.value, displayName: '港币兑人民币', remark: '1 HKD 对应的 CNY 金额' }];
  if (usdRate.value !== '') {
    if (!Number.isFinite(Number(usdRate.value)) || Number(usdRate.value) <= 0) {
      failed.value = true;
      message.value = '美元汇率必须大于 0。';
      return;
    }
    entries.push({ dictType: 'EXCHANGE_RATE', dictKey: 'USD_CNY', value: usdRate.value, displayName: '美元兑人民币', remark: '1 USD 对应的 CNY 金额；历史快照保留当期汇率' });
  } else if (props.entries.some(e => e.dictType === 'EXCHANGE_RATE' && e.dictKey === 'USD_CNY')) {
    failed.value = true;
    message.value = '已配置的美元汇率不能留空。';
    return;
  }
  for (const [tag, value] of Object.entries(amounts)) {
    if (String(value).trim() === '' || !Number.isFinite(Number(value))) {
      failed.value = true;
      message.value = `${tag}：请输入有效金额，不需要调整时填 0。`;
      return;
    }
    entries.push({ dictType: 'TAG_ADJUSTMENT', dictKey: tag, value, displayName: tag, remark: '新记录的默认调整金额' });
  }
  busy.value = true;
  try {
    emit('saved', await requestJson('/api/dictionary', { method: 'PUT', body: JSON.stringify(entries) }));
    message.value = '配置已保存到数据库，新记录采用此配置，历史快照保持原值。';
  } catch (err) { failed.value = true; message.value = err.message; }
  finally { busy.value = false; }
}
</script>

<template>
  <details class="content-card dictionary-settings">
    <summary>金额与汇率配置</summary>
    <p>这些默认值保存在数据库，可在其他浏览器读取。标签调整可为负数；不调整时填 0。</p>
    <form @submit.prevent="save">
      <fieldset :disabled="busy" class="dictionary-fields">
        <div class="form-grid">
          <label class="field"><span>港币兑人民币（1 HKD = 多少 CNY）</span><input v-model="rate" type="number" step="0.00000001" min="0.00000001" required /></label>
          <label class="field"><span>美元兑人民币（1 USD = 多少 CNY）</span><input v-model="usdRate" type="number" step="0.00000001" min="0.00000001" placeholder="录入美元账户前必须配置" /></label>
          <label v-for="(_, tag) in amounts" :key="tag" class="field"><span>{{ tag }} · 默认调整金额</span><input v-model="amounts[tag]" type="number" step="0.01" required /></label>
        </div>
        <button class="primary-button" type="submit">{{ busy ? '保存中...' : '保存金额与汇率配置' }}</button>
      </fieldset>
    </form>
    <p v-if="message" :class="{ error: failed }" :role="failed ? 'alert' : 'status'">{{ message }}</p>
  </details>
</template>

<style scoped>
.dictionary-settings summary { cursor: pointer; font-size: 18px; font-weight: 700; }
.dictionary-settings p { color: var(--text-muted); line-height: 1.7; }
.dictionary-settings .error { color: var(--negative); }
.dictionary-fields { border: 0; margin: 0; padding: 0; min-width: 0; }
.dictionary-fields button { margin-top: 16px; }
</style>
