<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useConfigStore } from '@/stores/config'
const cfg = useConfigStore()
const saved = ref(false)
onMounted(() => { if (!cfg.baseUrl) cfg.loadConfig() })
const vendors: Record<string, { baseUrl: string; model: string }> = {
  deepseek: { baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat' },
  openai: { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o' },
  siliconflow: { baseUrl: 'https://api.siliconflow.cn/v1', model: 'deepseek-ai/DeepSeek-V2.5' },
  ollama: { baseUrl: 'http://localhost:11434/v1', model: 'qwen2.5-coder:14b' },
  custom: { baseUrl: '', model: '' },
}
function onVendorChange(vendor: string) {
  const preset = vendors[vendor] || vendors.custom
  cfg.baseUrl = preset.baseUrl; cfg.model = preset.model
  doSave()
}
async function doSave() {
  saved.value = false
  await cfg.saveConfig()
  saved.value = true
  setTimeout(() => { saved.value = false }, 2000)
}
</script>
<template>
  <div class="config-panel">
    <div style="font-size:11px;color:var(--muted);margin-bottom:6px;">此配置对所有 AI 功能生效</div>
    <div class="config-row"><label>模型厂商</label><select v-model="cfg.vendor" @change="onVendorChange(cfg.vendor)" style="flex:1;font-size:12px;">
      <option v-for="(v, k) in vendors" :key="k" :value="k">{{ k }}</option>
    </select></div>
    <div class="config-row"><label>接口地址</label><input v-model="cfg.baseUrl" placeholder="https://api.deepseek.com" @blur="doSave()" style="flex:1;font-size:12px;"></div>
    <div class="config-row"><label>模型</label><input v-model="cfg.model" placeholder="deepseek-chat" @blur="doSave()" style="flex:1;font-size:12px;"></div>
    <div class="config-row"><label>温度</label><input v-model.number="cfg.temperature" type="range" min="0" max="1" step="0.1" @change="doSave()"><span style="font-size:11px;min-width:30px;">{{ cfg.temperature }}</span></div>
    <div class="config-row" style="align-items:start;"><label style="margin-top:4px;">提示词</label><textarea v-model="cfg.systemPrompt" rows="2" @blur="doSave()" style="font-size:11px;"></textarea></div>
    <div v-if="saved" style="font-size:11px;color:var(--success);text-align:right;">已保存</div>
  </div>
</template>
