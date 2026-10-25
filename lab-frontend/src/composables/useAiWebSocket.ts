import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useQuestionsStore } from '@/stores/questions'

/** AI 导师 WebSocket 连接管理器 */
export function useAiWebSocket() {
  const auth = useAuthStore()
  const questionsStore = useQuestionsStore()
  const router = useRouter()

  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  const connected = ref(false)
  const aiMessage = ref('')       // AI 最新消息
  const aiSuggestions = ref<string[]>([])  // AI 建议

  function connect() {
    if (!auth.isLoggedIn || !auth.userId) return
    if (ws && ws.readyState === WebSocket.OPEN) return

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    // Python AI 服务在 8000 端口
    const url = `ws://localhost:8000/ws/ai?user_id=${encodeURIComponent(auth.userId)}`

    try {
      ws = new WebSocket(url)

      ws.onopen = () => {
        connected.value = true
        if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
      }

      ws.onmessage = (event: MessageEvent) => {
        try {
          const msg = JSON.parse(event.data)
          handleMessage(msg)
        } catch { /* ignore parse errors */ }
      }

      ws.onclose = () => {
        connected.value = false
        ws = null
        // 自动重连（5秒后）
        if (auth.isLoggedIn) {
          reconnectTimer = setTimeout(() => connect(), 5000)
        }
      }

      ws.onerror = () => {
        ws?.close()
      }
    } catch { /* connection failed, will retry on next login */ }
  }

  function disconnect() {
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
    if (ws) { ws.close(); ws = null }
    connected.value = false
  }

  function send(data: Record<string, unknown>) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(data))
    }
  }

  /** 向 AI 发送消息 */
  function sendMessage(content: string) {
    send({ type: 'message', content })
  }

  function handleMessage(msg: any) {
    const type = msg.type
    const data = msg.data || {}

    switch (type) {
      case 'message':
        aiMessage.value = data.content || ''
        break

      case 'code_update':
        // 写入代码编辑器——通过自定义事件通知 PracticeView
        window.dispatchEvent(new CustomEvent('ai-code-update', {
          detail: { code: data.code, topic: data.topic }
        }))
        break

      case 'action': {
        const action = data.action || {}
        const routeMap: Record<string, string> = {
          practice: '/practice', cards: '/cards', community: '/community', progress: '/progress'
        }
        if (action.type === 'switchTab' && routeMap[action.payload]) {
          router.push(routeMap[action.payload])
        }
        if (action.type === 'loadQuestion' && action.payload) {
          questionsStore.selectedQuestion = null
          // 跳转到社区陷阱页面查看题目
          router.push('/community')
          // 延迟加载题目详情
          setTimeout(() => {
            window.dispatchEvent(new CustomEvent('load-question', {
              detail: { questionId: action.payload }
            }))
          }, 300)
        }
        break
      }

      case 'suggestions':
        aiSuggestions.value = data.suggestions || []
        break

      case 'pong':
        break

      case 'error':
        console.warn('AI 服务错误:', data.content)
        break
    }
  }

  // 监听登录状态变化
  watch(() => auth.isLoggedIn, (loggedIn) => {
    if (loggedIn) connect()
    else disconnect()
  })

  return {
    connected,
    aiMessage,
    aiSuggestions,
    connect,
    disconnect,
    sendMessage,
  }
}
