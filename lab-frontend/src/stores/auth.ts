import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api/client'
import type { AuthResponse } from '@/types'

// 匿名用户使用持久化的 session UUID，保证同一浏览器匿名对话不丢失
function getAnonymousId(): string {
  let id = localStorage.getItem('anonymous_session_id')
  if (!id) {
    id = 'anon_' + crypto.randomUUID()
    localStorage.setItem('anonymous_session_id', id)
  }
  return id
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('auth_token') || '')
  const userId = ref(localStorage.getItem('user_id') || getAnonymousId())
  const username = ref(localStorage.getItem('username') || '')
  const role = ref(localStorage.getItem('role') || '')
  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => role.value === 'ROOT' || role.value === 'ADMIN')

  async function login(user: string, pass: string) {
    const res = await authApi.post<AuthResponse>('/login', { username: user, password: pass })
    if (res.code === 200 && res.data) {
      token.value = res.data.token; userId.value = res.data.userId; username.value = res.data.username; role.value = res.data.role || ''
      localStorage.setItem('auth_token', res.data.token); localStorage.setItem('user_id', res.data.userId); localStorage.setItem('username', res.data.username); localStorage.setItem('role', res.data.role || '')
    }
    return res
  }
  async function register(user: string, pass: string, email?: string) {
    const res = await authApi.post<AuthResponse>('/register', { username: user, password: pass, email })
    if (res.code === 200 && res.data) {
      token.value = res.data.token; userId.value = res.data.userId; username.value = res.data.username; role.value = res.data.role || ''
      localStorage.setItem('auth_token', res.data.token); localStorage.setItem('user_id', res.data.userId); localStorage.setItem('username', res.data.username); localStorage.setItem('role', res.data.role || '')
    }
    return res
  }
  function logout() {
    authApi.post('/logout', {}).catch(() => {})
    token.value = ''; userId.value = getAnonymousId(); username.value = ''; role.value = ''
    localStorage.removeItem('auth_token'); localStorage.removeItem('user_id'); localStorage.removeItem('username'); localStorage.removeItem('role')
  }
  return { token, userId, username, role, isLoggedIn, isAdmin, login, register, logout }
})
