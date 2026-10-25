<script setup lang="ts">
import { ref, watch, onMounted, onActivated } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { labApi } from '@/api/client'
import { renderMarkdown } from '@/utils/markdown'
import 'highlight.js/styles/github.css'

// 去除代码块，只保留文字说明（含流式输出中未闭合的代码块）
function stripCodeBlocks(text: string): string {
  // 先去掉完整闭合的代码块
  let result = text.replace(/```[\s\S]*?```/g, '`[代码已显示在编辑器中]`')
  // 再去掉流式输出中未闭合的代码块（以 ``` 开头但没有配对的）
  result = result.replace(/```[^`]*$/, '')
  return result
}

const emit = defineEmits<{ action: [action: any] }>()
const props = withDefaults(defineProps<{
  scenario?: any
  sessionId?: string | null
  currentKnowledgePoint?: string
  currentCode?: string
  originalCode?: string
  executionOutput?: string
}>(), {
  currentCode: '',
  originalCode: '',
  executionOutput: '',
})
const auth = useAuthStore()
const chatStore = useChatStore()

const inputMsg = ref('')
const chatMessages = ref<{ role: string; content: string }[]>([])
const msgContainer = ref<HTMLDivElement>()
const conversationChips = ref<any[]>([])

const sse = ref<{ state: 'idle' | 'streaming' | 'done' | 'error'; text: string; error: string | null; metadata: Record<string, any> }>({
  state: 'idle', text: '', error: null, metadata: {},
})
const currentSuggestions = ref<string[]>([])
const showSuggestions = ref(true) // 是否显示预判建议条
const welcomeLoaded = ref(false)
const feedbackLoading = ref<string>('') // 正在反馈的消息ID
let abortController: AbortController | null = null

function generateId() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 8) }

onMounted(async () => {
  await chatStore.loadConversations()
  // 如果有当前对话，恢复消息
  if (chatStore.currentConvId) {
    await chatStore.loadMessages(chatStore.currentConvId)
    chatMessages.value = chatStore.messages.map(m => ({ role: m.role, content: m.content }))
    welcomeLoaded.value = true
  } else {
    await loadWelcome()
  }
})

// KeepAlive 激活时：刷新对话列表，恢复当前对话
onActivated(async () => {
  await chatStore.loadConversations()
  if (chatStore.currentConvId) {
    await chatStore.loadMessages(chatStore.currentConvId)
    chatMessages.value = chatStore.messages.map(m => ({ role: m.role, content: m.content }))
    welcomeLoaded.value = true
  }
})

// 用户切换时：加载新用户的对话列表，清空当前聊天框
watch(() => auth.userId, async () => {
  chatMessages.value = []
  currentSuggestions.value = []
  welcomeLoaded.value = false
  await chatStore.loadConversations()
  await loadWelcome()
})

watch(() => chatStore.conversations, (val) => { conversationChips.value = val }, { immediate: true })

async function loadWelcome() {
  if (welcomeLoaded.value) return
  // 未登录用户：显示注册引导
  if (!auth.isLoggedIn) {
    chatMessages.value.push({
      role: 'assistant',
      content: '你好！我是小C助手，可以帮你学习 Java 编程。\n\n**请先[注册账号](#)或[登录](#)后再使用AI助手功能。**'
    })
    welcomeLoaded.value = true
    return
  }
  try {
    const uid = auth.userId || 'anonymous'
    const res = await labApi.get<any>('/assistant/welcome?userId=' + encodeURIComponent(uid))
    if (res.code === 200 && res.data) {
      chatMessages.value.push({ role: 'assistant', content: res.data.reply || '' })
      currentSuggestions.value = res.data.suggestions || []
      welcomeLoaded.value = true
    }
  } catch {
    chatMessages.value.push({ role: 'assistant', content: '你好！我是小C助手，可以帮你学习 Java 编程。' })
    welcomeLoaded.value = true
  }
}

/** 点击建议标签，填入输入框（用户可修改后再发送） */
function clickSuggestion(text: string) {
  inputMsg.value = text
}

async function sendMessage() {
  if (!auth.isLoggedIn) return
  if (!inputMsg.value.trim() || sse.value.state === 'streaming') return
  const msg = inputMsg.value; inputMsg.value = ''
  chatMessages.value.push({ role: 'user', content: msg })
  chatMessages.value.push({ role: 'assistant', content: '' })
  scrollToBottom()

  // 自动创建对话
  if (!chatStore.currentConvId) {
    const convId = generateId()
    chatStore.currentConvId = convId
    await chatStore.createConversation(convId, msg)
  }
  // 保存用户消息
  await chatStore.saveMessage(chatStore.currentConvId!, 'user', msg)

  sse.value = { state: 'streaming', text: '', error: null, metadata: {} }
  abortController = new AbortController()

  try {
    // 通过 Java 网关代理（JWT 鉴权 + 限流 + 画像注入）
    const response = await labApi.stream('/assistant/chat/stream', {
      message: msg,
      conversationId: chatStore.currentConvId || '',
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    const reader = response.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue
        const data = line.slice(6).trim()
        try {
          const event = JSON.parse(data)
          if (event.type === 'token') {
            sse.value.text += event.content
            const last = chatMessages.value[chatMessages.value.length - 1]
            if (last.role === 'assistant') last.content = sse.value.text
          } else if (event.type === 'code') {
            // 代码块 → 发送到 PracticeView 代码编辑器
            emit('action', { type: 'setCode', payload: { language: event.language, content: event.content } })
          } else if (event.type === 'knowledge_path') {
            // 知识点归类路径 → 显示在代码模块上方（如 redis/redis持久化）
            emit('action', { type: 'setKnowledgePath', payload: event.path })
          } else if (event.type === 'note') {
            // AI 讲解 → 写入笔记本
            emit('action', { type: 'addNote', payload: { knowledge: event.knowledge, content: event.content } })
          } else if (event.type === 'done') {
            sse.value.metadata = { action: event.action, sessionId: event.session_id }
          } else if (event.type === 'suggestions') {
            currentSuggestions.value = event.items || []
          }
        } catch {
          sse.value.text += data
          const last = chatMessages.value[chatMessages.value.length - 1]
          if (last.role === 'assistant') last.content = sse.value.text
        }
      }
    }
    sse.value.state = 'done'
    const last = chatMessages.value[chatMessages.value.length - 1]
    if (last.role === 'assistant' && !last.content) {
      last.content = sse.value.text || '（暂无回复）'
    }
    // 保存 AI 回复
    if (last.role === 'assistant' && last.content) {
      await chatStore.saveMessage(chatStore.currentConvId!, 'assistant', last.content)
    }
    // AI 自动分析对话，更新画像
    if (auth.isLoggedIn) {
      analyzeChatAndUpdateProfile()
    }
  } catch (e: any) {
    sse.value.state = 'error'
    sse.value.error = e.message
    const last = chatMessages.value[chatMessages.value.length - 1]
    if (last.role === 'assistant' && !last.content) {
      last.content = '对话失败，请检查 Python AI 服务是否已启动（端口 8001）'
    }
  }
  scrollToBottom()
}

async function analyzeChatAndUpdateProfile() {
    try {
      // 只取最近 10 条消息用于分析，避免 token 过长
      const recent = chatMessages.value.slice(-10)
      const messages = recent
        .filter(m => m.role === 'user' || m.role === 'assistant')
        .map(m => ({ role: m.role, content: m.content }))
      if (messages.length < 2) return // 至少有一问一答
      await labApi.post('/user/profile/analyze-chat', { messages })
    } catch {
      // 分析失败不影响聊天流程
    }
  }

  async function switchConv(conv: any) {
  chatStore.currentConvId = conv.conversationId
  await chatStore.loadMessages(conv.conversationId)
  chatMessages.value = chatStore.messages.map(m => ({ role: m.role, content: m.content }))
  currentSuggestions.value = []
  welcomeLoaded.value = true
  scrollToBottom()
}

function startNewConv() {
  chatStore.currentConvId = null
  chatMessages.value = []
  currentSuggestions.value = []
  welcomeLoaded.value = false
  loadWelcome()
}

function deleteConv(convId: string) {
  chatStore.deleteConv(convId)
  if (chatStore.currentConvId === convId) startNewConv()
}

function scrollToBottom() {
  setTimeout(() => { if (msgContainer.value) msgContainer.value.scrollTop = msgContainer.value.scrollHeight }, 50)
}

/** 点击不满意，收集上下文发送给 AI 分析并生成优化建议 */
async function submitFeedback(msgIndex: number) {
  if (!auth.isLoggedIn) return
  const msg = chatMessages.value[msgIndex]
  if (!msg || msg.role !== 'assistant') return
  const feedbackId = `${msgIndex}`
  if (feedbackLoading.value) return
  feedbackLoading.value = feedbackId

  try {
    // 收集该消息及之前的上下文（最多 10 条）
    const contextStart = Math.max(0, msgIndex - 9)
    const context = chatMessages.value.slice(contextStart, msgIndex + 1)
      .map(m => ({ role: m.role, content: m.content }))

    const res = await labApi.post<any>('/assistant/feedback', {
      messages: context,
      conversationId: chatStore.currentConvId || '',
    })

    if (res.code === 200) {
      // 在消息下方显示确认
      const last = chatMessages.value[chatMessages.value.length - 1]
      if (last.role === 'assistant') {
        last.content += '\n\n> *已收到你的反馈，AI 正在分析并优化回复质量...*'
      }
    }
  } catch {
    // 静默失败
  } finally {
    feedbackLoading.value = ''
  }
}

</script>
<template>
  <div class="chat-panel">
    <div class="chat-header">
      <span style="width:8px;height:8px;border-radius:50%;background:var(--success);display:inline-block;"></span>
      小C助手
      <div style="flex:1"></div>
    </div>
    
    <div class="conv-selector">
      <span v-for="c in conversationChips" :key="c.conversationId" class="conv-chip" :class="{ active: c.conversationId === chatStore.currentConvId }" @click="switchConv(c)">
        {{ c.title || c.conversationId?.slice(0,8) || '对话' }}
        <span class="conv-close" @click.stop="deleteConv(c.conversationId)">&times;</span>
      </span>
      <span class="conv-chip new" @click="startNewConv">+ 新对话</span>
    </div>
    <div class="chat-messages" ref="msgContainer">
      <div v-for="(msg, i) in chatMessages" :key="i" class="chat-msg" :class="msg.role">
        <div class="msg-content" v-html="renderMarkdown(msg.role === 'assistant' ? stripCodeBlocks(msg.content) : msg.content)"></div>
        <div v-if="msg.role === 'assistant' && msg.content && auth.isLoggedIn" class="msg-actions">
          <button class="feedback-btn" :disabled="!!feedbackLoading" @click="submitFeedback(i)" :title="feedbackLoading === `${i}` ? '提交中...' : '不满意此回复'">
            {{ feedbackLoading === `${i}` ? '...' : '不满意' }}
          </button>
        </div>
        <div v-if="!msg.content && i === chatMessages.length - 1 && sse.state === 'streaming'" class="msg-content streaming">正在思考...</div>
      </div>
    </div>
    <!-- 预判建议条：放在输入框上方 -->
    <div v-if="auth.isLoggedIn && showSuggestions && currentSuggestions.length > 0" class="suggestions-bar">
      <span class="sug-label">你可能想说：</span>
      <span v-for="(s, si) in currentSuggestions" :key="si" class="suggestion-chip" @click="clickSuggestion(s)">{{ s }}</span>
    </div>
    <div class="chat-input-area">
      <input v-if="auth.isLoggedIn" v-model="inputMsg" @keydown.enter="sendMessage" placeholder="问小C助手..." :disabled="sse.state === 'streaming'" />
      <button v-if="auth.isLoggedIn && currentSuggestions.length > 0" class="btn btn-outline btn-sm quick-answer-btn" @click="showSuggestions = !showSuggestions" :title="showSuggestions ? '隐藏快捷答案' : '快捷答案'">
        {{ showSuggestions ? '隐藏' : '快捷答案' }}
      </button>
      <button v-if="auth.isLoggedIn" class="btn btn-primary btn-sm" @click="sendMessage" :disabled="sse.state === 'streaming' || !inputMsg.trim()">发送</button>
      <div v-else class="guest-hint">请先注册或登录后使用AI助手</div>
    </div>
  </div>
</template>

<style scoped>
/* 预判建议条 */
.suggestions-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-top: 1px solid #eee;
  background: #fafbfc;
}
.sug-label {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
  margin-right: 2px;
}
.suggestion-chip {
  display: inline-block;
  padding: 3px 10px;
  background: #e8f4fd;
  color: #0969da;
  border: 1px solid #b6d9f7;
  border-radius: 14px;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s;
  user-select: none;
}
.suggestion-chip:hover {
  background: #d0ebff;
  border-color: #79b8f2;
}
.quick-answer-btn {
  white-space: nowrap;
  font-size: 12px;
  padding: 4px 10px;
}
.conv-close {
  margin-left: 2px;
  font-size: 16px;
  line-height: 1;
  color: #999;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 4px;
  user-select: none;
}
.conv-close:hover {
  color: #e74c3c;
  background: rgba(231, 76, 60, 0.08);
}
.guest-hint {
  flex: 1;
  text-align: center;
  padding: 8px;
  font-size: 13px;
  color: #999;
  border: 1px dashed #ddd;
  border-radius: 6px;
}
.msg-actions {
  padding: 0 12px 4px;
  display: flex;
  gap: 6px;
}
.feedback-btn {
  background: none;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  font-size: 11px;
  color: #999;
  cursor: pointer;
  padding: 2px 8px;
  transition: all 0.15s;
}
.feedback-btn:hover {
  color: #e74c3c;
  border-color: #e74c3c;
  background: rgba(231, 76, 60, 0.05);
}
.feedback-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
:deep(.msg-content) {
  line-height: 1.7;
  padding: 0 12px;
  font-size: 14px;
  color: #1f2328;
}
:deep(.msg-content pre) {
  background: #1e293b;
  color: #e2e8f0;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  padding: 12px;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.45;
}
:deep(.msg-content code) {
  font-family: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace;
  font-size: 13px;
}
:deep(.msg-content p > code) {
  background: #f6f8fa;
  padding: 2px 6px;
  border-radius: 4px;
  color: #cf222e;
}
:deep(.msg-content p) {
  margin: 8px 0;
  white-space: pre-wrap;
}
:deep(.msg-content ul),
:deep(.msg-content ol) {
  padding-left: 20px;
  margin: 8px 0;
}
:deep(.msg-content h1),
:deep(.msg-content h2),
:deep(.msg-content h3),
:deep(.msg-content h4) {
  margin: 12px 0 6px;
  font-weight: 600;
}
:deep(.msg-content blockquote) {
  border-left: 3px solid #d0d7de;
  padding-left: 12px;
  color: #57606a;
  margin: 8px 0;
}
:deep(.msg-content a) {
  color: #0969da;
  text-decoration: none;
}
:deep(.msg-content a:hover) {
  text-decoration: underline;
}
</style>
