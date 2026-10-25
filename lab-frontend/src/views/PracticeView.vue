<template>
  <div class="practice-layout">
    <!-- ====== 左侧知识树面板 ====== -->
    <div class="tree-panel" :class="{ open: treePanelOpen }">
      <div class="tree-header">
        <span style="font-weight:600;font-size:14px;">Java 后端知识树</span>
        <button class="btn btn-ghost btn-sm" @click="treePanelOpen = false" title="收起侧边栏">&times;</button>
      </div>
      <KnowledgeTree @select-node="selectTreeNode" />
    </div>

    <!-- ====== 中间编辑器面板 ====== -->
    <div class="editor-panel">
      <!-- 编辑器头部 -->
      <div class="editor-header">
        <span v-if="!treePanelOpen" class="btn btn-ghost btn-sm" @click="treePanelOpen = true" style="cursor:pointer;font-size:16px;padding:0 4px;" title="展开侧边栏">&#9776;</span>
        <span style="font-size:12px;color:var(--muted);">
          <template v-if="knowledgePath"><span class="knowledge-breadcrumb">📂 {{ knowledgePath }}</span></template>
          <template v-else>{{ currentKnowledgePoint || '从左侧知识树选择一个知识点' }}</template>
          <span v-if="currentContentType && currentContentType !== 'trap'" :class="'content-badge content-badge--' + currentContentType" style="margin-left:6px;padding:1px 6px;border-radius:3px;font-size:10px;background:#e8f4fd;color:#1976d2;">{{ contentTypeLabel }}</span>
        </span>
        <div style="flex:1"></div>
        <button class="btn btn-primary" @click="runCode" :disabled="!currentScenario && !isProjectMode || currentContentType === 'concept' || currentContentType === 'command'">&#9654; 运行代码</button>
        <button class="btn btn-outline" @click="resetCode" :disabled="!currentScenario && !isProjectMode">&#8634; 重置</button>
      </div>

      <!-- Tab 切换 -->
      <div class="editor-tabs">
        <button :class="['tab-btn', { active: activeTab === 'question' }]" @click="activeTab = 'question'">题目</button>
        <button :class="['tab-btn', { active: activeTab === 'code' }]" @click="activeTab = 'code'">代码</button>
        <div style="flex:1"></div>
        <button class="tab-btn note-hint" @click="showNotebook = !showNotebook">
          笔记
          <span v-if="notebookStore.notes.length" class="tab-badge">{{ notebookStore.notes.length }}</span>
          <span style="font-size:10px;color:var(--muted);margin-left:4px;">(Tab)</span>
        </button>
      </div>

      <!-- 题目 Tab -->
      <div v-show="activeTab === 'question'" class="question-panel">
        <div v-if="!currentScenario && !isProjectMode" class="notebook-empty">
          <p>暂无题目</p>
          <p style="font-size:12px;color:var(--muted);">从知识树选择一个知识点，查看题目描述</p>
        </div>
        <div v-else>
          <div class="note-card">
            <div class="note-header"><span class="note-knowledge">📋 题目描述</span></div>
            <div class="note-content">
              <p><strong>知识点：</strong>{{ currentKnowledgePoint }}</p>
              <div v-if="currentScenario?.expectedPitfall"><strong>预期陷阱：</strong>{{ currentScenario.expectedPitfall }}</div>
              <div v-if="currentScenario?.correctExplanation"><strong>正确解释：</strong>{{ currentScenario.correctExplanation }}</div>
              <div v-if="currentScenario?.hints"><strong>提示：</strong>{{ currentScenario.hints }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 代码 Tab -->
      <div v-show="activeTab === 'code'" style="flex:1;display:flex;flex-direction:column;min-height:0;">
        <CodeEditor
          ref="editorRef"
          :code="editorCode"
          :language="editorLanguage"
          @code-change="onCodeChange"
        />
        <div class="terminal-panel">
          <div class="terminal-label">{{ currentContentType === 'concept' ? '概念讲解' : currentContentType === 'command' ? '命令演示' : '输出终端' }}</div>
          <pre class="terminal-output" id="terminal-output">{{ terminalOutput || '从知识树选择一个知识点，AI 自动生成学习内容' }}</pre>
        </div>
      </div>

    </div>

    <!-- ====== 右侧 AI 助手聊天面板 ====== -->
    <GlobalChat
      class="chat-panel"
      :scenario="currentScenario"
      :session-id="sessionId"
      :current-knowledge-point="currentKnowledgePoint"
      :current-code="editorCode"
      :original-code="originalCode"
      :execution-output="terminalOutput"
      @action="handleAction"
    />

    <!-- ====== 笔记本浮层（Tab 键弹出） ====== -->
    <div v-if="showNotebook" class="notebook-overlay" @click.self="showNotebook = false">
      <div class="notebook-dialog">
        <div class="notebook-dialog-header">
          <span>笔记本</span>
          <span style="font-size:12px;color:var(--muted);">共 {{ notebookStore.notes.length }} 条 · 按 Tab 关闭</span>
          <button class="btn btn-ghost btn-sm" @click="showNotebook = false">&times;</button>
        </div>
        <div class="notebook-dialog-body">
          <div v-if="notebookStore.notes.length === 0" class="notebook-empty">
            <p>暂无笔记</p>
            <p style="font-size:12px;color:var(--muted);">学完知识点后，AI 会自动写入笔记</p>
          </div>
          <div v-for="note in notebookStore.notes" :key="note.id" class="note-card">
            <div class="note-header">
              <span class="note-knowledge">{{ note.knowledge }}</span>
              <div style="display:flex;align-items:center;gap:8px;">
                <span class="note-time">{{ note.time }}</span>
                <button class="btn btn-ghost btn-sm" style="font-size:11px;padding:2px 6px;" @click="startEditNote(note)" title="编辑">✎</button>
                <button class="btn btn-ghost btn-sm" style="font-size:11px;padding:2px 6px;color:#e74c3c;" @click="notebookStore.deleteNote(note.id)" title="删除">✕</button>
              </div>
            </div>
            <div v-if="editingNoteId === note.id" class="note-edit-area">
              <textarea v-model="editContent" class="note-edit-textarea" rows="6"></textarea>
              <div class="note-edit-actions">
                <button class="btn btn-primary btn-sm" @click="saveEditNote(note.id)">保存</button>
                <button class="btn btn-ghost btn-sm" @click="editingNoteId = null">取消</button>
              </div>
            </div>
            <div v-else class="note-content" @dblclick="startEditNote(note)" v-html="renderMarkdown(note.content)" title="双击编辑"></div>
          </div>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, inject } from 'vue'
import { useRouter } from 'vue-router'
import { useTreeStore } from '@/stores/tree'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { useQuestionsStore } from '@/stores/questions'
import { labApi } from '@/api/client'
import type { ScenarioDto, KnowledgeNode, AiAction, ProjectFileDTO, QuestionDto } from '@/types'
import { useNotebookStore } from '@/stores/notebook'
import { SHOW_TOAST } from '@/symbols'
import { renderMarkdown } from '@/utils/markdown'
import KnowledgeTree from '@/components/KnowledgeTree.vue'
import CodeEditor from '@/components/CodeEditor.vue'
import GlobalChat from '@/components/GlobalChat.vue'

// ---------- stores ----------
const router = useRouter()
const treeStore = useTreeStore()
const chatStore = useChatStore()
const authStore = useAuthStore()
const questionsStore = useQuestionsStore()
const notebookStore = useNotebookStore()
const showToast = inject(SHOW_TOAST) as (msg: string) => void

// ---------- refs ----------
const editorRef = ref<InstanceType<typeof CodeEditor> | null>(null)

// ---------- state ----------
const currentScenario = ref<ScenarioDto | null>(null)
const currentQuestionId = ref<number | null>(null)
const sessionId = ref<string | null>(null)
const originalCode = ref('')
const currentKnowledgePoint = ref('')
const currentNodeId = ref('')
const knowledgePath = ref('')
const terminalOutput = ref('')
const treePanelOpen = ref(true)
const editorCode = ref('')
const editorLanguage = ref('java')
const currentMode = ref('trap')
const currentContentType = ref<string | null>(null)
const activeTab = ref<'code' | 'question'>('question')
const showNotebook = ref(false)
const editingNoteId = ref<string | null>(null)
const editContent = ref('')

// 项目模式相关
const isProjectMode = ref(false)
const currentProjectFiles = ref<ProjectFileDTO[]>([])
const currentProjectDir = ref('')
const currentFilePath = ref('')

// 编辑器就绪标志
const editorReady = ref(false)

// 内容类型标记
const contentTypeLabel = computed(() => {
  const labels: Record<string, string> = {
    concept: '概念讲解',
    command: '命令演示',
    algorithm: '算法练习',
    trap: '陷阱代码',
  }
  return labels[currentContentType.value || 'trap'] || '代码'
})

const generatingNode = ref(false)

// ---------- 选择树节点 ----------
async function selectTreeNode(node: KnowledgeNode) {
  // 允许点击非叶子节点（如「其他」分类节点）
  currentKnowledgePoint.value = node.name || node.path
  // 知识树路径与 AI 出题路径同级，点击时覆盖（包括 AI 写的知识归类路径）
  knowledgePath.value = node.path || node.name
  currentNodeId.value = node.id
  isProjectMode.value = false
  currentScenario.value = null
  currentQuestionId.value = null
  terminalOutput.value = '正在生成内容，请稍候...'
  editorCode.value = ''
  currentContentType.value = null
  generatingNode.value = true

  try {
    if (node.type === 'project') {
      const project = await treeStore.generateProject(node.id)
      if (project) {
        isProjectMode.value = true
        currentContentType.value = 'project'
        const files = project.files || []
        currentProjectFiles.value = files
        currentProjectDir.value = project.projectName || ''
        if (files.length > 0) {
          currentFilePath.value = files[0].path
          editorCode.value = files[0].content
          originalCode.value = files[0].content
        }
      }
    } else {
      const scenario = await treeStore.generateFromNode(node.id)
      if (scenario) {
        currentScenario.value = scenario
        currentContentType.value = scenario.type || 'trap'
        currentQuestionId.value = scenario.id || null

        if (scenario.type === 'concept' || scenario.type === 'command') {
          terminalOutput.value = scenario.generatedContent || scenario.trapCode || ''
          editorLanguage.value = scenario.type === 'command' ? 'shell' : 'java'
          editorCode.value = '/** ' + node.name + ' — 请查看下方输出终端 */\n// ' + scenario.trapCode?.substring(0, 60)
        } else if (scenario.type === 'algorithm') {
          editorCode.value = scenario.trapCode || scenario.generatedContent || ''
          originalCode.value = editorCode.value
          editorLanguage.value = 'java'
          terminalOutput.value = '📐 算法练习 — 阅读下方代码，理解算法思路和实现'
        } else {
          editorCode.value = scenario.trapCode || ''
          originalCode.value = scenario.trapCode || ''
          editorLanguage.value = 'java'
          terminalOutput.value = ''
        }
        sessionId.value = 'session_' + Date.now()
      } else {
        terminalOutput.value = '未能生成内容，请检查 API Key 配置（小C助手 → 配置）'
        showToast('内容生成失败')
      }
    }
  } catch (e: any) {
    console.error('选择节点失败:', e)
    const msg = e.message || '加载失败'
    terminalOutput.value = '错误: ' + msg
    showToast(msg)
  } finally {
    generatingNode.value = false
  }
}

// ---------- 运行代码 ----------
async function runCode() {
  if (!currentScenario.value && !isProjectMode.value) return
  if (currentContentType.value === 'concept' || currentContentType.value === 'command') return

  const code = editorRef.value?.getValue() || editorCode.value
  terminalOutput.value = '正在执行...\n'

  try {
    const res = await labApi.post<{ stdout: string; stderr: string }>('/execute', {
      code,
      language: editorLanguage.value,
      knowledgePoint: currentKnowledgePoint.value,
    })
    if (res.code === 200 && res.data) {
      const output = res.data.stderr
        ? '编译/运行错误:\n' + res.data.stderr
        : res.data.stdout || '(无输出)'
      terminalOutput.value = output
    } else {
      terminalOutput.value = '执行失败: ' + (res.message || '未知错误')
    }
  } catch (e: any) {
    terminalOutput.value = '执行异常: ' + (e.message || '请求失败')
  }
}

// ---------- 重置代码 ----------
function resetCode() {
  if (originalCode.value) {
    editorCode.value = originalCode.value
    if (editorRef.value) {
      editorRef.value.setValue(originalCode.value)
    }
  }
}

// ---------- 代码变更回调 ----------
function onCodeChange(code: string) {
  editorCode.value = code
}

// ---------- AI 动作处理 ----------
function handleAction(action: AiAction) {
  if (!action || !action.type) return
  switch (action.type) {
    case 'switchTab':
      if (action.payload && typeof action.payload === 'string') {
        const routeMap: Record<string, string> = { practice: '/practice', cards: '/cards', community: '/community', progress: '/progress' }
        const names: Record<string, string> = { practice: '练习', cards: '知识卡片', community: '社区陷阱', progress: '学习中心' }
        router.push(routeMap[action.payload] || '/practice')
        showToast('已跳转到' + (names[action.payload] || action.payload))
      }
      break
    case 'selectQuestion':
      if (action.payload) {
        router.push('/community')
        showToast('已打开社区陷阱')
      }
      break
    case 'loadToEditor':
      if (action.payload && typeof action.payload === 'string') {
        editorCode.value = action.payload
        if (editorRef.value) editorRef.value.setValue(action.payload)
        showToast('代码已加载到编辑器')
      }
      break
    case 'loadQuestion':
      if (action.payload) {
        const qId = typeof action.payload === 'object' ? action.payload.questionId : action.payload
        loadQuestionToEditor(qId)
        showToast('正在加载题目...')
      }
      break
    case 'generateScenario':
      if (action.payload && typeof action.payload === 'string') {
        selectTreeNode({ id: action.payload, name: '', leaf: true })
        showToast('正在生成场景...')
      }
      break
    case 'runCode': runCode(); break
    case 'resetCode': resetCode(); showToast('代码已重置'); break
    case 'modifyCode':
      if (action.payload && typeof action.payload === 'object') {
        const { target, replacement } = action.payload as any
        if (target !== undefined && replacement !== undefined) {
          const current = editorRef.value?.getValue() || editorCode.value
          const newCode = current.replace(target, replacement)
          editorCode.value = newCode
          if (editorRef.value) editorRef.value.setValue(newCode)
          showToast('代码已修改')
        }
      }
      break
    case 'insertCode':
      if (action.payload && typeof action.payload === 'string') {
        const current = editorRef.value?.getValue() || editorCode.value
        const newCode = current + '\n' + action.payload
        editorCode.value = newCode
        if (editorRef.value) editorRef.value.setValue(newCode)
        showToast('代码已插入')
      }
      break
    case 'deleteCode':
      if (action.payload && typeof action.payload === 'string') {
        const current = editorRef.value?.getValue() || editorCode.value
        const newCode = current.replace(action.payload, '')
        editorCode.value = newCode
        if (editorRef.value) editorRef.value.setValue(newCode)
        showToast('代码已删除')
      }
      break
    case 'setCode':
      if (action.payload && typeof action.payload === 'object') {
        const { language, content } = action.payload as any
        editorCode.value = content
        if (language) editorLanguage.value = language
        if (editorRef.value) editorRef.value.setValue(content)
        // 创建虚拟场景，使"运行代码"按钮可用（AI 出题没有真实场景数据）
        currentScenario.value = {
          id: -Date.now(), knowledgePoint: knowledgePath.value || currentKnowledgePoint.value || 'AI 生成题目',
          trapCode: content, type: 'trap' as any,
        }
        currentContentType.value = 'trap'
        originalCode.value = content
        isProjectMode.value = false
        activeTab.value = 'code'
      }
      break
    case 'setKnowledgePath':
      // AI 生成的知识归类路径 → 显示在代码模块上方（如 redis篇/redis持久化）
      if (typeof action.payload === 'string' && action.payload) {
        knowledgePath.value = action.payload
        // 同步知识点名称（路径最后一级），与知识树选择同级覆盖
        const leaf = action.payload.split('/').pop()?.trim()
        if (leaf) currentKnowledgePoint.value = leaf
      }
      break
    case 'addNote':
      if (action.payload && typeof action.payload === 'object') {
        const { knowledge, content } = action.payload as any
        notebookStore.addNote(knowledge || currentKnowledgePoint.value, content)
        showToast('已写入笔记')
      }
      break
    default:
      console.warn('未知 action 类型:', action.type)
  }
}

// 根据题目ID加载代码到编辑器
async function loadQuestionToEditor(questionId: number) {
  try {
    const res = await labApi.get<QuestionDto>('/questions/' + questionId + '?userId=' + encodeURIComponent(authStore.userId || ''))
    if (res.code === 200 && res.data) {
      const q = res.data
      isProjectMode.value = false
      currentContentType.value = 'trap'
      editorCode.value = q.trapCode || ''
      originalCode.value = q.trapCode || ''
      currentKnowledgePoint.value = q.title || ''
      terminalOutput.value = ''
      // 创建虚拟场景使运行按钮可用
      currentScenario.value = {
        id: q.id, knowledgePoint: q.title, trapCode: q.trapCode || '',
        expectedPitfall: q.expectedPitfall, correctExplanation: q.correctExplanation,
        hints: undefined, difficulty: q.difficulty ?? undefined, type: 'trap' as any, generatedContent: undefined
      }
    }
  } catch (e: any) {
    console.error('加载题目失败:', e)
    terminalOutput.value = '加载题目失败: ' + (e.message || '')
  }
}

// ---------- 布局更新 ----------
function layoutEditor() {
  editorRef.value?.layout()
}

// ---------- 键盘事件（Tab 键弹出笔记本） ----------
function onKeyDown(e: KeyboardEvent) {
  if (e.key === 'Tab') {
    e.preventDefault()
    showNotebook.value = !showNotebook.value
  }
  if (e.key === 'Escape' && showNotebook.value) {
    showNotebook.value = false
  }
}

// ---------- 笔记编辑 ----------
function startEditNote(note: { id: string; content: string }) {
  editingNoteId.value = note.id
  editContent.value = note.content
}
function saveEditNote(id: string) {
  notebookStore.updateNote(id, editContent.value)
  editingNoteId.value = null
}

// ---------- 生命周期 ----------
onMounted(async () => {
  window.addEventListener('load-code', onLoadCode)
  document.addEventListener('keydown', onKeyDown)
  // 从题库跳转过来的，把题目加载到编辑器
  if (questionsStore.selectedQuestion) {
    const q = questionsStore.selectedQuestion
    isProjectMode.value = false
    currentContentType.value = 'trap'
    editorCode.value = q.trapCode || ''
    originalCode.value = q.trapCode || ''
    currentKnowledgePoint.value = q.title || ''
    terminalOutput.value = ''
    currentScenario.value = {
      id: q.id, knowledgePoint: q.title, trapCode: q.trapCode || '',
      expectedPitfall: q.expectedPitfall, correctExplanation: q.correctExplanation,
      hints: undefined, difficulty: q.difficulty ?? undefined, type: 'trap' as any, generatedContent: undefined
    }
    questionsStore.selectedQuestion = null
  }
  if (authStore.isLoggedIn && authStore.userId) {
    await chatStore.loadConversations()
  }
})
onUnmounted(() => {
  document.removeEventListener('keydown', onKeyDown)
})

function onLoadCode(e: any) {
  const detail = e.detail || {}
  if (detail.code) {
    isProjectMode.value = false
    currentContentType.value = 'trap'
    editorCode.value = detail.code
    originalCode.value = detail.code
    currentKnowledgePoint.value = detail.title || ''
    terminalOutput.value = ''
    currentScenario.value = {
      id: undefined, knowledgePoint: detail.title || '', trapCode: detail.code,
      expectedPitfall: '', correctExplanation: '', hints: undefined,
      difficulty: undefined, type: 'trap' as any, generatedContent: undefined
    }
  }
}
</script>

<style scoped>
/* ====== 知识路径面包屑 ====== */
.knowledge-breadcrumb {
  display: inline-block;
  background: #fff7e6;
  border: 1px solid #ffd591;
  color: #d46b08;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  margin-right: 8px;
  white-space: nowrap;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: middle;
}

/* ====== 面板分割线 ====== */
.practice-layout {
  display: flex;
  flex: 1;
  overflow: hidden;
  min-width: 0;
}

/* ====== 编辑器头部按钮禁用态 ====== */
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ====== 终端输出 ====== */
.terminal-output {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}

/* ====== 响应式：小屏折叠聊天面板 ====== */
@media (max-width: 900px) {
  .chat-panel {
    width: 100% !important;
    height: 260px;
    border-left: none;
    border-top: 1px solid var(--border);
  }
}

/* ====== Tab 切换 ====== */
.editor-tabs {
  display: flex;
  gap: 0;
  border-bottom: 1px solid var(--border);
  padding: 0 8px;
  background: var(--bg-soft);
}
.tab-btn {
  padding: 6px 16px;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 13px;
  color: var(--muted);
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
  position: relative;
}
.tab-btn:hover { color: var(--text); }
.tab-btn.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
}
.tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  border-radius: 9px;
  background: var(--primary);
  color: #fff;
  font-size: 11px;
  margin-left: 4px;
  padding: 0 5px;
}

/* ====== 笔记本浮层 ====== */
.notebook-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}
.notebook-dialog {
  width: 680px;
  max-width: 90vw;
  max-height: 80vh;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.2);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.notebook-dialog-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  font-weight: 600;
  font-size: 15px;
}
.notebook-dialog-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}
.notebook-empty {
  text-align: center;
  padding: 40px 20px;
  color: var(--muted);
}
.note-card {
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 10px;
}
.note-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.note-knowledge {
  font-weight: 600;
  font-size: 13px;
}
.note-time {
  font-size: 11px;
  color: var(--muted);
}
.note-content {
  font-size: 13px;
  line-height: 1.6;
  color: var(--text);
}
.note-content :deep(pre) {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 10px;
  border-radius: 6px;
  overflow-x: auto;
  font-size: 12px;
}
.note-content :deep(code) {
  font-family: 'Consolas', 'Courier New', monospace;
}
.note-edit-area {
  margin-top: 4px;
}
.note-edit-textarea {
  width: 100%;
  border: 1px solid var(--primary);
  border-radius: 6px;
  padding: 8px;
  font-size: 13px;
  line-height: 1.6;
  font-family: inherit;
  resize: vertical;
  outline: none;
}
.note-edit-actions {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  justify-content: flex-end;
}

/* ====== 题目面板 ====== */
.question-panel {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}
</style>
