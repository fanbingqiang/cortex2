<script setup lang="ts">
import { ref, provide, onMounted } from 'vue'
import TopBar from '@/components/TopBar.vue'
import AuthModal from '@/components/AuthModal.vue'
import { useConfigStore } from '@/stores/config'
import { useAuthStore } from '@/stores/auth'
import { useAiWebSocket } from '@/composables/useAiWebSocket'
import { SHOW_TOAST, SHOW_AUTH } from '@/symbols'

const toastMsg = ref('')
let toastTimer: ReturnType<typeof setTimeout> | null = null

function showToast(msg: string, duration = 2500) {
  toastMsg.value = msg
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastMsg.value = '' }, duration)
}
provide(SHOW_TOAST, showToast)

const showAuthModal = ref(false)

function openAuth() { showAuthModal.value = true }
provide(SHOW_AUTH, openAuth)

// AI 导师 WebSocket
const authStore = useAuthStore()
const aiWs = useAiWebSocket()
provide('aiWebSocket', aiWs)

onMounted(() => {
  const configStore = useConfigStore(); configStore.loadConfig()
  if (authStore.isLoggedIn) aiWs.connect()
})
</script>
<template>
  <div id="app">
    <TopBar @open-auth="showAuthModal = true" />
    <div class="main">
      <router-view v-slot="{ Component }">
        <keep-alive>
          <component :is="Component" />
        </keep-alive>
      </router-view>
    </div>
    <AuthModal v-if="showAuthModal" @close="showAuthModal = false" />
    <div v-if="toastMsg" id="toast" style="position:fixed;bottom:30px;left:50%;transform:translateX(-50%);background:#1e293b;color:white;padding:10px 24px;border-radius:8px;font-size:13px;z-index:9999;animation:fadeIn 0.3s;max-width:80%;text-align:center;">{{ toastMsg }}</div>
  </div>
</template>
