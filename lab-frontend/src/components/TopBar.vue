<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
const emit = defineEmits<{ 'open-auth': [] }>()
const router = useRouter(); const route = useRoute()
const auth = useAuthStore()
const tabs = [
  { name: 'practice', label: '实战练习', path: '/practice' },
  { name: 'cards', label: '知识卡片', path: '/cards' },
  { name: 'community', label: '社区陷阱', path: '/community' },
  { name: 'progress', label: '个人中心', path: '/progress' },
  ]
function isActive(path: string) { return route.path === path || (path === '/practice' && route.path === '/') }
</script>
<template>
  <div class="topbar">
    <span class="logo">Cortex Lab</span>
    <template v-for="tab in tabs" :key="tab.name">
      <router-link :to="tab.path" class="nav-tab" :class="{ active: isActive(tab.path) }">{{ tab.label }}</router-link>
    </template>
    <router-link v-if="auth.isAdmin" to="/admin" class="nav-tab" :class="{ active: isActive('/admin') }">后台管理</router-link>
    <div style="flex:1"></div>
    <div style="display:flex;align-items:center;gap:6px;">
      <span v-if="auth.username" class="avatar-circle">{{ auth.username.charAt(0).toUpperCase() }}</span>
      <button v-if="!auth.isLoggedIn" class="btn btn-sm btn-outline" @click="emit('open-auth')" style="font-size:12px;padding:4px 12px;">登录 / 注册</button>
      <button v-else class="btn btn-sm btn-ghost" @click="auth.logout()" style="font-size:12px;padding:4px 12px;">退出</button>
    </div>
  </div>
</template>
<style scoped>
.avatar-circle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #e8eaed;
  color: #555;
  font-size: 13px;
  font-weight: 600;
}
</style>
