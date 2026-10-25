import type { ApiResponse } from '@/types'

const LAB_PREFIX = '/api/lab'
const AUTH_PREFIX = '/api/auth'

function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = localStorage.getItem('auth_token')
  if (token) headers['Authorization'] = 'Bearer ' + token
  return headers
}

async function request<T>(method: string, path: string, body?: unknown, prefix = LAB_PREFIX): Promise<ApiResponse<T>> {
  try {
    const res = await fetch(`${prefix}${path}`, { method, headers: getAuthHeaders(), body: body ? JSON.stringify(body) : undefined })
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      return { code: res.status, message: text || `HTTP ${res.status}`, data: null as T }
    }
    return res.json()
  } catch {
    return { code: 500, message: '网络异常，请确认后端已启动（端口 8081）', data: null as T }
  }
}

export const labApi = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
  stream: (path: string, body: unknown) => fetch(`${LAB_PREFIX}${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...getAuthHeaders() }, body: JSON.stringify(body) })
}

export const authApi = {
  get: <T>(path: string) => request<T>('GET', path, undefined, AUTH_PREFIX),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body, AUTH_PREFIX),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body, AUTH_PREFIX),
  del: <T>(path: string) => request<T>('DELETE', path, undefined, AUTH_PREFIX),
}

// Python AI 直连
const PYTHON_PREFIX = 'http://localhost:8001'

async function pythonRequest<T>(method: string, path: string, body?: unknown): Promise<ApiResponse<T>> {
  try {
    const res = await fetch(`${PYTHON_PREFIX}${path}`, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    })
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      return { code: res.status, message: text || `HTTP ${res.status}`, data: null as T }
    }
    return res.json()
  } catch {
    return { code: 500, message: 'Python 服务连接异常（端口 8001）', data: null as T }
  }
}

export const pythonApi = {
  chatStream: (body: { user_id: string; message: string; system_prompt?: string }) =>
    fetch(`${PYTHON_PREFIX}/api/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
}

// 后台管理 API（通过 Java 代理，含角色校验）
export const adminApi = {
  getPrompts: () => labApi.get<any>('/admin/prompts'),
  updatePrompts: (prompts: Record<string, string>, author?: string) => labApi.put('/admin/prompts', { prompts, author }),
  getSessions: () => labApi.get<any>('/admin/sessions'),
  getSession: (id: string) => labApi.get<any>(`/admin/sessions/${id}`),
  getPromptChanges: (limit = 50) => labApi.get<any>(`/admin/prompt-changes?limit=${limit}`),
  getKeyPool: () => labApi.get<any>('/admin/key-pool'),
  addKey: (key: string) => labApi.post<any>('/admin/key-pool/add', { key }),
  removeKey: (index: number) => labApi.delete<any>(`/admin/key-pool/${index}`),
  getStats: () => labApi.get<any>('/admin/stats'),
  getUsers: () => labApi.get<any>('/admin/users'),
  banUser: (userId: string) => labApi.put<any>(`/admin/users/${userId}/ban`),
  unbanUser: (userId: string) => labApi.put<any>(`/admin/users/${userId}/unban`),
  getPromptHistory: (key: string) => labApi.get<any>(`/admin/prompts/${key}/history`),
  rollbackPrompt: (key: string, version: number, author?: string) => labApi.put(`/admin/prompts/${key}/rollback`, { version, author }),
  summarizeChanges: (changes: any[]) => labApi.post<any>('/admin/prompt-changes/summarize', { changes }),
}

export { getAuthHeaders }



