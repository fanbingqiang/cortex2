import { defineStore } from 'pinia'
import { ref } from 'vue'
import { labApi } from '@/api/client'

export const useConfigStore = defineStore('config', () => {
  const apiKey = ref(''); const baseUrl = ref('https://api.deepseek.com'); const model = ref('deepseek-chat')
  const temperature = ref(0.7); const vendor = ref('deepseek')
  const systemPrompt = ref('')

  async function loadConfig() {
    const res = await labApi.get<Record<string, string>>('/assistant/config')
    if (res.code === 200 && res.data) {
      const c = res.data
      apiKey.value = c.api_key || ''; baseUrl.value = c.base_url || 'https://api.deepseek.com'; model.value = c.model || 'deepseek-chat'
      temperature.value = parseFloat(c.temperature || '0.7');
      systemPrompt.value = c.system_prompt || ''
    }
  }
  async function saveConfig() {
    await labApi.put('/assistant/config', {
      api_key: apiKey.value, base_url: baseUrl.value, model: model.value, temperature: String(temperature.value),
      system_prompt: systemPrompt.value
    })
  }
  return { apiKey, baseUrl, model, temperature, vendor, systemPrompt, loadConfig, saveConfig }
})
