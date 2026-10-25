<template>
  <div class="admin-layout">
    <div class="admin-header">
      <h2>后台管理系统</h2>
      <span style="font-size:12px;color:var(--muted);">仪表盘 / 提示词 / 流程追踪 / 变更日志 / 用户管理</span>
    </div>

    <!-- ====== 仪表盘 ====== -->
    <div v-show="activeTab === 'dashboard'" class="tab-content">
      <div class="dashboard-grid">
        <!-- 统计卡片 -->
        <div class="dash-card">
          <div class="dash-card-num">{{ stats.total_users ?? '-' }}</div>
          <div class="dash-card-label">注册用户</div>
        </div>
        <div class="dash-card">
          <div class="dash-card-num">{{ stats.total_sessions ?? '-' }}</div>
          <div class="dash-card-label">Python 会话</div>
        </div>
        <div class="dash-card">
          <div class="dash-card-num">{{ stats.total_conversations ?? '-' }}</div>
          <div class="dash-card-label">对话记录</div>
        </div>
        <div class="dash-card">
          <div class="dash-card-num">{{ stats.total_prompt_changes ?? '-' }}</div>
          <div class="dash-card-label">变更日志</div>
        </div>
        <div class="dash-card">
          <div class="dash-card-num">{{ stats.prompt_count ?? '-' }}</div>
          <div class="dash-card-label">Agent 提示词</div>
        </div>
        <div class="dash-card">
          <div class="dash-card-num">{{ stats.total_profiles ?? '-' }}</div>
          <div class="dash-card-label">用户画像</div>
        </div>
      </div>

      <!-- Key 池状态 -->
      <div class="section-title">API Key 池状态</div>
      <div v-if="loading.keyPool" class="loading">加载中...</div>
      <div v-else-if="keyPool" class="keypool-grid">
        <div class="keypool-card">
          <div class="keypool-label">Key 总数</div>
          <div class="keypool-value">{{ keyPool.keys }}</div>
        </div>
        <div class="keypool-card">
          <div class="keypool-label">RPM 限制</div>
          <div class="keypool-value">{{ keyPool.rpm_limit }}</div>
        </div>
        <div class="keypool-card">
          <div class="keypool-label">队列长度</div>
          <div class="keypool-value" :class="{ 'queue-warn': keyPool.queue_length > 0 }">{{ keyPool.queue_length }}</div>
        </div>
        <div v-for="(rpm, key) in keyPool.key_rpms" :key="key" class="keypool-card">
          <div class="keypool-label">{{ key }}...</div>
          <div class="keypool-value" :class="{ 'rpm-high': rpm >= (keyPool.rpm_limit || 50) * 0.8 }">{{ rpm }} / {{ keyPool.rpm_limit }}</div>
        </div>
      </div>

      <div class="section-title" style="margin-top:16px;">管理 Key</div>
      <div class="key-mgmt-row">
        <input v-model="newKeyInput" class="key-input" placeholder="输入新的 API Key..." @keyup.enter="addApiKey" />
        <button class="btn btn-primary btn-sm" @click="addApiKey" :disabled="addingKey || !newKeyInput.trim()">{{ addingKey ? '...' : '添加' }}</button>
      </div>
      <div v-if="keyPool?.key_list" class="key-list" style="margin-top:8px;">
        <div v-for="(k, i) in keyPool.key_list" :key="i" class="key-item">
          <span class="key-item-text">{{ k?.slice(0, 12) }}...{{ k?.slice(-4) }}</span>
          <span class="key-item-rpm" v-if="keyPool.key_rpms && keyPool.key_rpms[k]">{{ keyPool.key_rpms[k] }} rpm</span>
          <button class="btn btn-danger btn-xs" @click="removeApiKey(i)" :disabled="removingKey === i">{{ removingKey === i ? '...' : '删除' }}</button>
        </div>
      </div>

      <div style="margin-top:16px;">
        <button class="btn btn-outline btn-sm" @click="loadKeyPool" :disabled="loading.keyPool">刷新 Key 池</button>
        <button class="btn btn-outline btn-sm" style="margin-left:8px;" @click="loadStats" :disabled="loading.stats">刷新统计</button>
      </div>
    </div>

    <!-- ====== 提示词管理 ====== -->
    <div v-show="activeTab === 'prompts'" class="tab-content">
      <div v-if="loading.prompts" class="loading">加载中...</div>
      <div v-else-if="promptsError" class="error-msg">{{ promptsError }}</div>
      <div v-else class="prompts-grid">
        <div v-for="(value, key) in agentPrompts" :key="key" class="prompt-card">
          <div class="prompt-card-header">
            <span class="prompt-agent-name">{{ agentLabels[key] || key }}</span>
            <div style="display:flex;align-items:center;gap:8px;">
              <span class="prompt-key">{{ key }}</span>
              <button class="btn btn-ghost btn-sm" style="font-size:11px;padding:1px 6px;" @click="showHistory(key)">历史</button>
              <button class="btn btn-ghost btn-sm" style="font-size:11px;padding:1px 6px;" @click="resetPrompt(key)">重置</button>
            </div>
          </div>
          <textarea
            :value="editPrompts[key] ?? value"
            @input="onPromptEdit(key, ($event.target as HTMLTextAreaElement).value)"
            class="prompt-editor"
            rows="14"
          ></textarea>
          <div class="prompt-card-footer">
            <span class="char-count">{{ (editPrompts[key] ?? value).length }} 字符</span>
            <button
              class="btn btn-primary btn-sm"
              :disabled="saving === key"
              @click="savePrompt(key)"
            >{{ saving === key ? '保存中...' : '保存' }}</button>
          </div>
          <!-- 版本历史 -->
          <div v-if="historyKey === key" class="prompt-history">
            <div class="history-title">版本历史 ({{ (promptHistoryMap[historyKey] || []).length }})</div>
            <div v-if="loading.history" class="loading" style="padding:12px;">加载中...</div>
            <div v-else class="history-list">
              <div v-for="(h, i) in (promptHistoryMap[historyKey] || [])" :key="i" class="history-item" :class="{ 'history-current': i === (promptHistoryMap[historyKey] || []).length - 1 }">
                <div class="history-meta">
                  <span class="history-version">v{{ h.version }}</span>
                  <span class="history-author">{{ h.author === 'system' ? '系统默认' : h.author }}</span>
                  <span class="history-time">{{ h.timestamp }}</span>
                </div>
                <div class="history-msg">{{ h.message }}</div>
                <pre class="history-preview">{{ h.prompt?.slice(0, 120) }}{{ h.prompt?.length > 120 ? '...' : '' }}</pre>
                <button
                  v-if="i < (promptHistoryMap[historyKey] || []).length - 1"
                  class="btn btn-outline btn-xs"
                  :disabled="rollingBack === key"
                  @click="doRollback(key, h.version)"
                >{{ rollingBack === key ? '...' : '回滚到此版本' }}</button>
              </div>
            </div>
            <button class="btn btn-ghost btn-sm" style="margin-top:8px;" @click="historyKey = ''">关闭</button>
          </div>
        </div>
      </div>
    </div>

    <!-- ====== 流程追踪 ====== -->
    <div v-show="activeTab === 'sessions'" class="tab-content">
      <div class="sessions-toolbar">
        <button class="btn btn-outline btn-sm" @click="loadSessions" :disabled="loading.sessions">刷新</button>
        <span style="font-size:12px;color:var(--muted);">共 {{ sessions.length }} 个会话</span>
      </div>
      <div v-if="loading.sessions" class="loading">加载中...</div>
      <div v-else-if="sessionsError" class="error-msg">{{ sessionsError }}</div>
      <div v-else class="sessions-list">
        <div
          v-for="s in sessions"
          :key="s.session_id"
          class="session-row"
          :class="{ expanded: expandedSession === s.session_id }"
        >
          <div class="session-summary" @click="toggleSession(s.session_id)">
            <span :class="'status-dot status-' + (s.status || 'unknown').toLowerCase()"></span>
            <span class="session-id">{{ s.session_id?.slice(0, 12) }}</span>
            <span class="session-title">{{ s.knowledge_title || '未命名' }}</span>
            <span class="session-user">{{ s.user_id || '-' }}</span>
            <span class="session-status">{{ s.status || '-' }}</span>
            <span class="session-score">{{ s.mastery_score != null ? s.mastery_score + '分' : '-' }}</span>
            <span class="expand-icon">{{ expandedSession === s.session_id ? '▼' : '▶' }}</span>
          </div>
          <div v-if="expandedSession === s.session_id" class="session-detail">
            <div v-if="loading.sessionDetail" class="loading">加载详情...</div>
            <div v-else-if="sessionDetail" class="detail-content">
              <div class="detail-section">
                <h4>基本信息</h4>
                <div class="detail-grid">
                  <span>知识点：{{ sessionDetail.knowledge_title }}</span>
                  <span>代码类型：{{ sessionDetail.code_type }}</span>
                  <span>评分：{{ sessionDetail.mastery_score ?? '-' }}</span>
                  <span>知识盲区：{{ (sessionDetail.knowledge_gaps || []).join(', ') || '-' }}</span>
                </div>
              </div>
              <div class="detail-section">
                <h4>题目代码</h4>
                <pre class="code-block">{{ sessionDetail.question_code || '无' }}</pre>
              </div>
              <div class="detail-section">
                <h4>问题</h4>
                <p>{{ sessionDetail.question || '无' }}</p>
              </div>
              <div class="detail-section">
                <h4>参考答案</h4>
                <p>{{ sessionDetail.reference_answer || '无' }}</p>
              </div>
              <div class="detail-section">
                <h4>用户回答</h4>
                <p>{{ sessionDetail.user_answer || '无' }}</p>
              </div>
              <div class="detail-section" v-if="sessionDetail.steps?.length">
                <h4>执行步骤 ({{ sessionDetail.steps.length }})</h4>
                <div v-for="step in sessionDetail.steps" :key="step.step_id" class="step-card">
                  <div class="step-header">
                    <span class="step-name">{{ step.step_name }}</span>
                    <span :class="'step-status status-' + (step.status || '').toLowerCase()">{{ step.status }}</span>
                  </div>
                  <div v-if="step.input" class="step-io">
                    <strong>输入：</strong>
                    <pre>{{ step.input }}</pre>
                  </div>
                  <div v-if="step.output" class="step-io">
                    <strong>输出：</strong>
                    <pre>{{ step.output?.slice(0, 500) }}{{ step.output?.length > 500 ? '...' : '' }}</pre>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ====== 提示词变更日志 ====== -->
    <div v-show="activeTab === 'promptChanges'" class="tab-content">
      <div class="sessions-toolbar">
        <button class="btn btn-outline btn-sm" @click="loadPromptChanges" :disabled="loading.promptChanges">刷新</button>
        <select v-model="changeFilter" class="sug-filter">
          <option value="">全部 Agent</option>
          <option v-for="(label, key) in agentLabels" :key="key" :value="key">{{ label }}</option>
        </select>
        <select v-model="changeTypeFilter" class="sug-filter">
          <option value="">全部类型</option>
          <option value="prompt_change">提示词修改</option>
          <option value="feedback">AI优化建议</option>
        </select>
        <span style="font-size:12px;color:var(--muted);">共 {{ filteredChanges.length }} 条</span>
        <div style="flex:1;"></div>
        <button class="btn btn-primary btn-sm" @click="aiSummarizeChanges" :disabled="aiSummaryLoading || filteredChanges.length === 0">
          {{ aiSummaryLoading ? 'AI分析中...' : 'AI总结变更' }}
        </button>
        <button class="btn btn-outline btn-sm" @click="scrollToLatest" title="跳到最新变更">
          跳至最新
          <span v-if="unreadCount > 0" class="unread-dot">{{ unreadCount }}</span>
        </button>
      </div>

      <!-- AI总结区域 -->
      <div v-if="aiSummary || aiSummaryLoading" class="ai-summary-panel">
        <div v-if="aiSummaryLoading" class="loading" style="padding:16px;">AI 正在分析变更记录，请稍候...</div>
        <div v-else class="ai-summary-content">
          <div class="ai-summary-header">
            <strong>AI 变更总结</strong>
            <button class="btn btn-xs btn-outline" @click="aiSummary = ''; editedSummaryPrompt = ''">关闭</button>
          </div>
          <div class="ai-summary-text">{{ aiSummary }}</div>
          <div v-if="editedSummaryPrompt !== null" class="ai-summary-prompt-section">
            <div class="ai-summary-header" style="margin-top:12px;">
              <strong>AI 生成的优化提示词</strong>
              <select v-model="summaryTargetAgent" class="sug-filter" style="width:auto;">
                <option v-for="(label, key) in agentLabels" :key="key" :value="key">{{ label }}</option>
              </select>
            </div>
            <textarea
              v-model="editedSummaryPrompt"
              class="prompt-editor"
              style="min-height:120px;border:1px solid var(--border);border-radius:4px;width:100%;"
              @input="onSummaryPromptEdit"
            ></textarea>
            <div style="display:flex;gap:8px;margin-top:8px;">
              <button class="btn btn-primary btn-sm" @click="applySummaryPrompt">应用此提示词</button>
              <button class="btn btn-outline btn-sm" @click="editedSummaryPrompt = null; aiSummary = ''">关闭</button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="loading.promptChanges" class="loading">加载中...</div>
      <div v-else-if="filteredChanges.length === 0" class="empty-state">
        <p>暂无变更记录</p>
        <p style="font-size:12px;color:var(--muted);">管理员修改提示词或用户点击"不满意"后会出现在这里</p>
      </div>
      <div v-else class="changes-list" ref="changesListRef">
        <div v-for="(c, i) in filteredChanges" :key="i" class="change-card" :class="{ 'feedback-card': c.type === 'feedback' }">
          <div class="change-header">
            <span class="change-type-badge" :class="c.type === 'feedback' ? 'badge-feedback' : 'badge-prompt'">
              {{ c.type === 'feedback' ? 'AI优化' : '提示词' }}
            </span>
            <span class="change-agent">{{ agentLabels[c.agent_key] || c.agent_key }}</span>
            <span v-if="c.type !== 'feedback'" class="change-version">v{{ c.version }}</span>
            <span class="change-author">{{ c.author === 'system' ? '系统默认' : formatAuthor(c.author) }}</span>
            <span class="change-time">{{ c.timestamp }}</span>
          </div>
          <div class="change-msg">{{ c.message }}</div>
          <pre v-if="c.prompt_preview" class="change-preview">{{ c.prompt_preview }}{{ c.prompt_preview?.length >= 100 ? '...' : '' }}</pre>
        </div>
      </div>
    </div>

    <!-- ====== 用户管理 ====== -->
    <div v-show="activeTab === 'users'" class="tab-content">
      <div class="sessions-toolbar">
        <button class="btn btn-outline btn-sm" @click="loadUsers" :disabled="loading.users">刷新</button>
        <input
          v-model="userSearch"
          class="user-search-input"
          placeholder="搜索用户名/ID..."
          @input="onUserSearch"
        />
        <span style="font-size:12px;color:var(--muted);">共 {{ filteredUsers.length }} 个用户</span>
      </div>
      <div v-if="loading.users" class="loading">加载中...</div>
      <div v-else-if="usersError" class="error-msg">{{ usersError }}</div>
      <div v-else class="users-table-wrap">
        <table class="users-table">
          <thead>
            <tr>
              <th>用户名</th>
              <th>角色</th>
              <th>状态</th>
              <th>等级</th>
              <th>答题数</th>
              <th>正确数</th>
              <th>连续学习</th>
              <th>学习目标</th>
              <th>薄弱点</th>
              <th>最后登录</th>
              <th style="width:80px;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in filteredUsers" :key="u.user_id" :class="{ 'row-banned': u.status === 'BANNED' }">
              <td><strong>{{ u.username }}</strong></td>
              <td><span :class="'role-tag role-' + (u.role || '').toLowerCase()">{{ u.role }}</span></td>
              <td><span :class="'status-tag status-' + (u.status || 'active').toLowerCase()">{{ u.status === 'BANNED' ? '已封禁' : '正常' }}</span></td>
              <td>{{ skillLabels[u.skill_level] || u.skill_level || '-' }}</td>
              <td>{{ u.total_questions }}</td>
              <td>{{ u.total_correct }}</td>
              <td>{{ u.study_streak }} 天</td>
              <td style="max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" :title="u.learning_goal">{{ u.learning_goal || '-' }}</td>
              <td style="max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" :title="u.weak_areas">{{ u.weak_areas || '-' }}</td>
              <td style="font-size:11px;color:var(--muted);">{{ u.last_login ? u.last_login.slice(0, 10) : '-' }}</td>
              <td>
                <button
                  v-if="u.role !== 'ROOT'"
                  :class="['btn', 'btn-xs', u.status === 'BANNED' ? 'btn-success' : 'btn-danger']"
                  :disabled="banning === u.user_id"
                  @click="toggleBan(u)"
                >{{ banning === u.user_id ? '...' : (u.status === 'BANNED' ? '解封' : '封禁') }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ====== 社区审核 ====== -->
    <div v-show="activeTab === 'community'" class="tab-content">
      <div class="sessions-toolbar">
        <button class="btn btn-outline btn-sm" @click="loadCommunityTraps" :disabled="loadingTraps">刷新</button>
        <span style="font-size:12px;color:var(--muted);">共 {{ communityTraps.length }} 条提交</span>
      </div>
      <div v-if="loadingTraps" class="loading">加载中...</div>
      <div v-else-if="communityTraps.length === 0" class="empty-state">
        <p>暂无社区提交</p>
        <p style="font-size:12px;color:var(--muted);">用户在"社区陷阱"页面提交的问题会显示在这里，可进行审核</p>
      </div>
      <div v-else class="traps-list">
        <div v-for="t in communityTraps" :key="t.id" class="trap-row">
          <div class="trap-row-main">
            <div class="trap-row-title">
              <span :class="'pill ' + (t.status === 'APPROVED' ? 'pill-easy' : t.status === 'REJECTED' ? 'pill-hard' : '')">{{ trapStatusLabel(t.status) }}</span>
              <strong>{{ t.title }}</strong>
            </div>
            <div class="trap-row-meta">
              <span>知识点: {{ t.knowledgePoint || '-' }}</span>
              <span>提交者: {{ t.submitter || '-' }}</span>
              <span>票数: {{ t.voteCount || 0 }}</span>
            </div>
            <div v-if="t.expectedPitfall" class="trap-row-desc">{{ t.expectedPitfall }}</div>
            <pre v-if="t.trapCode" class="code-block trap-row-code">{{ t.trapCode }}</pre>
          </div>
          <div class="trap-row-actions">
            <button
              v-if="t.status === 'PENDING'"
              class="btn btn-success btn-sm"
              @click="approveTrap(t.id)"
            >通过</button>
            <button
              v-if="t.status === 'PENDING'"
              class="btn btn-danger btn-sm"
              @click="rejectTrap(t.id)"
            >拒绝</button>
            <button class="btn btn-ghost btn-sm" style="color:var(--danger);" @click="deleteTrapAdmin(t.id)">删除</button>
          </div>
        </div>
      </div>
    </div>

    <!-- Tab 导航 -->
    <div class="admin-tabs">
      <button :class="['tab-btn', { active: activeTab === 'dashboard' }]" @click="activeTab = 'dashboard'">仪表盘</button>
      <button :class="['tab-btn', { active: activeTab === 'prompts' }]" @click="activeTab = 'prompts'">提示词管理</button>
      <button :class="['tab-btn', { active: activeTab === 'sessions' }]" @click="activeTab = 'sessions'">流程追踪</button>
      <button :class="['tab-btn', { active: activeTab === 'promptChanges' }]" @click="openChangeLog">
        变更日志
        <span v-if="unreadCount > 0" class="tab-badge tab-badge-unread">{{ unreadCount }}</span>
      </button>
      <button :class="['tab-btn', { active: activeTab === 'community' }]" @click="activeTab = 'community'">社区审核</button>
      <button :class="['tab-btn', { active: activeTab === 'users' }]" @click="activeTab = 'users'">用户管理</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { adminApi, labApi } from '@/api/client'

const activeTab = ref<'dashboard' | 'prompts' | 'sessions' | 'promptChanges' | 'users' | 'community'>('dashboard')

const loading = reactive({
  prompts: false, sessions: false, sessionDetail: false,
  promptChanges: false, keyPool: false, stats: false, users: false,
  history: false,
})
const promptsError = ref('')
const sessionsError = ref('')
const promptChangesError = ref('')
const usersError = ref('')

// 仪表盘
const stats = ref<Record<string, any>>({})
const keyPool = ref<any>(null)

// 提示词
const agentPrompts = ref<Record<string, string>>({})
const editPrompts = ref<Record<string, string>>({})
const saving = ref('')

const agentLabels: Record<string, string> = {
  exam: '出题 Agent', diagnose: '诊断 Agent', teach: '教学 Agent',
  intent: '意图分类 Agent', chat: '闲聊 Agent',
}
const actionLabels: Record<string, string> = {
  question: '出题', diagnose: '诊断', diagnose_teach: '诊断+教学',
  reveal_answer: '查看答案', chat: '闲聊',
}
const skillLabels: Record<string, string> = {
  BEGINNER: '入门', INTERMEDIATE: '中级', ADVANCED: '高级', EXPERT: '专家',
}

// 流程追踪
const sessions = ref<any[]>([])
const expandedSession = ref('')
const sessionDetail = ref<any>(null)

// 用户管理
const users = ref<any[]>([])
const userSearch = ref('')
const banning = ref('')

const filteredUsers = computed(() => {
  if (!userSearch.value.trim()) return users.value
  const q = userSearch.value.trim().toLowerCase()
  return users.value.filter((u: any) =>
    (u.username || '').toLowerCase().includes(q) ||
    (u.user_id || '').toLowerCase().includes(q)
  )
})

function onUserSearch() { /* computed 自动计算 */ }

async function toggleBan(u: any) {
  const isBanned = u.status === 'BANNED'
  if (!confirm(`确定要${isBanned ? '解封' : '封禁'}用户 "${u.username}" 吗？`)) return
  banning.value = u.user_id
  try {
    const res = isBanned
      ? await adminApi.unbanUser(u.user_id)
      : await adminApi.banUser(u.user_id)
    if (res.code === 200) {
      u.status = isBanned ? 'ACTIVE' : 'BANNED'
    } else {
      alert(res.message || '操作失败')
    }
  } catch { alert('操作失败') }
  finally { banning.value = '' }
}

// ─── 仪表盘 ───
async function loadStats() {
  loading.stats = true
  try {
    const res = await adminApi.getStats()
    if (res.code === 200) stats.value = res.data
  } catch { /* ignore */ }
  finally { loading.stats = false }
}
async function loadKeyPool() {
  loading.keyPool = true
  try {
    const res = await adminApi.getKeyPool()
    if (res.code === 200) keyPool.value = res.data?.data || res.data
  } catch { /* ignore */ }
  finally { loading.keyPool = false }
}

const newKeyInput = ref('')
const addingKey = ref(false)
const removingKey = ref(-1)

async function addApiKey() {
  if (!newKeyInput.value.trim()) return
  addingKey.value = true
  try {
    const res = await adminApi.addKey(newKeyInput.value.trim())
    if (res.code === 200) {
      newKeyInput.value = ''
      await loadKeyPool()
    } else { alert(res.message || '添加失败') }
  } catch { alert('添加失败') }
  finally { addingKey.value = false }
}

async function removeApiKey(index: number) {
  if (!confirm('确定删除这个 Key 吗？')) return
  removingKey.value = index
  try {
    const res = await adminApi.removeKey(index)
    if (res.code === 200) await loadKeyPool()
    else alert(res.message || '删除失败')
  } catch { alert('删除失败') }
  finally { removingKey.value = -1 }
}

// ─── 提示词管理 ───
async function loadPrompts() {
  loading.prompts = true; promptsError.value = ''
  try {
    const res = await adminApi.getPrompts()
    if (res.code === 200) {
      const prompts = res.data?.prompts || res.data || {}
      agentPrompts.value = prompts
      editPrompts.value = { ...prompts }
    } else { promptsError.value = res.message || '加载失败' }
  } catch { promptsError.value = '后端服务连接失败' }
  finally { loading.prompts = false }
}
function onPromptEdit(key: string, value: string) { editPrompts.value[key] = value }
function resetPrompt(key: string) {
  const defaults: Record<string, string> = {
    exam: '', diagnose: '', teach: '', intent: '', chat: '',
  }
  editPrompts.value[key] = defaults[key] || ''
}
async function savePrompt(key: string) {
  saving.value = key
  try {
    const res = await adminApi.updatePrompts({ [key]: editPrompts.value[key] }, 'admin')
    if (res.code === 200) {
      agentPrompts.value[key] = editPrompts.value[key]
      // 保存后自动展示历史面板并刷新
      historyKey.value = key
      await loadPromptHistory(key)
      // 刷新变更日志
      await loadPromptChanges()
    }
  } catch { /* ignore */ }
  finally { saving.value = '' }
}

// ─── 提示词版本历史 ───
const historyKey = ref('')
const promptHistoryMap = reactive<Record<string, any[]>>({})
const rollingBack = ref('')

async function showHistory(key: string) {
  if (historyKey.value === key) { historyKey.value = ''; return }
  historyKey.value = key
  await loadPromptHistory(key)
}

async function loadPromptHistory(key: string) {
  if (!promptHistoryMap[key]) promptHistoryMap[key] = []
  loading.history = true
  try {
    const res = await adminApi.getPromptHistory(key)
    if (res.code === 200 && historyKey.value === key) {
      promptHistoryMap[key] = res.data?.history || []
    }
  } catch { /* ignore */ }
  finally { loading.history = false }
}

async function doRollback(key: string, version: number) {
  if (!confirm(`确定将 "${agentLabels[key] || key}" 回滚到 v${version} 吗？`)) return
  rollingBack.value = key
  try {
    const res = await adminApi.rollbackPrompt(key, version, 'admin')
    if (res.code === 200) {
      const promptsRes = await adminApi.getPrompts()
      if (promptsRes.code === 200) {
        const prompts = promptsRes.data?.prompts || promptsRes.data || {}
        agentPrompts.value = prompts
        editPrompts.value = { ...prompts }
      }
      await loadPromptHistory(key)
      await loadPromptChanges()
    } else { alert('回滚失败: ' + (res.message || '未知错误')) }
  } catch { alert('回滚失败，请检查 Python 服务是否运行') }
  finally { rollingBack.value = '' }
}

// ─── 流程追踪 ───
async function loadSessions() {
  loading.sessions = true; sessionsError.value = ''
  try {
    const res = await adminApi.getSessions()
    if (res.code === 200) {
      sessions.value = (res.data?.sessions || []).reverse()
    } else { sessionsError.value = res.message || '加载失败' }
  } catch { sessionsError.value = '后端服务连接失败' }
  finally { loading.sessions = false }
}
async function toggleSession(sid: string) {
  if (expandedSession.value === sid) {
    expandedSession.value = ''; sessionDetail.value = null; return
  }
  expandedSession.value = sid
  loading.sessionDetail = true; sessionDetail.value = null
  try {
    const res = await adminApi.getSession(sid)
    if (res.code === 200) sessionDetail.value = res.data
  } catch { /* ignore */ }
  finally { loading.sessionDetail = false }
}

// ─── 提示词变更日志 ───
const promptChanges = ref<any[]>([])
const changeFilter = ref('')
const changeTypeFilter = ref('')
const changesListRef = ref<HTMLElement | null>(null)

// 未读计数
const LAST_READ_KEY = 'admin_change_log_last_read'
const unreadCount = ref(0)
const lastReadTimestamp = ref('')

function loadLastRead() {
  const ts = localStorage.getItem(LAST_READ_KEY) || ''
  lastReadTimestamp.value = ts
}

function markAllRead() {
  const now = new Date().toISOString()
  localStorage.setItem(LAST_READ_KEY, now)
  lastReadTimestamp.value = now
  unreadCount.value = 0
}

function openChangeLog() {
  activeTab.value = 'promptChanges'
  markAllRead()
  loadPromptChanges(true) // 跳过重新计算，保持已清零
}

function calcUnread(changes: any[]) {
  if (!lastReadTimestamp.value) { unreadCount.value = 0; return }
  unreadCount.value = changes.filter((c: any) => c.timestamp > lastReadTimestamp.value).length
}

function formatAuthor(author: string) {
  if (!author) return ''
  if (author === 'system') return '系统默认'
  if (author.startsWith('用户 ')) return author.slice(0, 15)
  return author
}

function scrollToLatest() {
  markAllRead()
  const el = changesListRef.value
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

const filteredChanges = computed(() => {
  let list = promptChanges.value
  if (changeFilter.value) {
    list = list.filter((c: any) => c.agent_key === changeFilter.value)
  }
  if (changeTypeFilter.value) {
    list = list.filter((c: any) => c.type === changeTypeFilter.value)
  }
  return list
})

async function loadPromptChanges(skipCalc = false) {
  loading.promptChanges = true; promptChangesError.value = ''
  try {
    const res = await adminApi.getPromptChanges(200)
    if (res.code === 200) {
      promptChanges.value = res.data?.changes || []
      if (!skipCalc) calcUnread(promptChanges.value)
    } else { promptChangesError.value = res.message || '加载失败' }
  } catch { promptChangesError.value = '后端服务连接失败' }
  finally { loading.promptChanges = false }
}

// AI 总结变更
const aiSummary = ref('')
const aiSummaryLoading = ref(false)
const editedSummaryPrompt = ref<string | null>(null)
const summaryTargetAgent = ref('exam')

async function aiSummarizeChanges() {
  if (aiSummaryLoading.value || filteredChanges.value.length === 0) return
  aiSummaryLoading.value = true
  aiSummary.value = ''
  editedSummaryPrompt.value = null
  try {
    const recentChanges = filteredChanges.value.slice(0, 20)
    const res = await adminApi.summarizeChanges(recentChanges)
    if (res.code === 200) {
      aiSummary.value = res.data?.summary || 'AI 无法生成总结'
      editedSummaryPrompt.value = res.data?.prompt || ''
      summaryTargetAgent.value = res.data?.target_agent || 'exam'
    } else {
      aiSummary.value = '总结失败: ' + (res.message || '未知错误')
    }
  } catch {
    aiSummary.value = 'AI 总结服务暂不可用'
  } finally {
    aiSummaryLoading.value = false
  }
}

function onSummaryPromptEdit() {
  // v-model 自动双向绑定
}

async function applySummaryPrompt() {
  if (!editedSummaryPrompt.value) return
  const key = summaryTargetAgent.value
  saving.value = key
  try {
    await adminApi.updatePrompts({ [key]: editedSummaryPrompt.value }, 'AI优化')
    aiSummary.value = ''
    editedSummaryPrompt.value = null
    await loadPrompts()
    await loadPromptChanges()
  } catch { alert('应用提示词失败') }
  finally { saving.value = '' }
}

// ─── 用户管理 ───
async function loadUsers() {
  loading.users = true; usersError.value = ''
  try {
    const res = await adminApi.getUsers()
    if (res.code === 200) users.value = res.data || []
    else usersError.value = res.message || '加载失败'
  } catch { usersError.value = '后端服务连接失败' }
  finally { loading.users = false }
}

// ─── 社区审核 ───
const communityTraps = ref<any[]>([])
const loadingTraps = ref(false)

async function loadCommunityTraps() {
  loadingTraps.value = true
  try {
    const res = await labApi.get<any[]>('/community/traps')
    if (res.code === 200) communityTraps.value = res.data || []
  } catch { /* ignore */ }
  finally { loadingTraps.value = false }
}

async function approveTrap(id: number) {
  const res = await labApi.post('/community/traps/' + id + '/approve')
  if (res.code === 200) {
    const t = communityTraps.value.find(x => x.id === id)
    if (t) t.status = 'APPROVED'
  } else alert(res.message || '操作失败')
}

async function rejectTrap(id: number) {
  if (!confirm('确定拒绝该提交？')) return
  const res = await labApi.post('/community/traps/' + id + '/reject')
  if (res.code === 200) {
    const t = communityTraps.value.find(x => x.id === id)
    if (t) t.status = 'REJECTED'
  } else alert(res.message || '操作失败')
}

async function deleteTrapAdmin(id: number) {
  if (!confirm('确定删除该提交？')) return
  const res = await labApi.post('/community/traps/' + id + '/delete')
  if (res.code === 200) {
    communityTraps.value = communityTraps.value.filter(x => x.id !== id)
  } else alert(res.message || '操作失败')
}

function trapStatusLabel(s: string): string {
  const map: Record<string, string> = { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝' }
  return map[s] || s
}

onMounted(() => {
  loadLastRead()
  loadStats()
  loadKeyPool()
  loadPrompts()
  loadSessions()
  loadPromptChanges()
  loadUsers()
  loadCommunityTraps()
})
</script>

<style scoped>
.admin-layout { flex: 1; display: flex; flex-direction: column; overflow: hidden; padding: 0 24px 16px; gap: 12px; }
.admin-header { padding: 12px 0 4px; }
.admin-header h2 { margin: 0; font-size: 18px; }
.admin-tabs { display: flex; gap: 0; border-bottom: 1px solid var(--border); margin-top: auto; }
.tab-btn { padding: 8px 20px; border: none; background: none; cursor: pointer; font-size: 14px; color: var(--muted); border-bottom: 2px solid transparent; transition: all 0.15s; position: relative; }
.tab-btn:hover { color: var(--text); }
.tab-btn.active { color: var(--primary); border-bottom-color: var(--primary); font-weight: 600; }
.tab-badge { display: inline-flex; align-items: center; justify-content: center; min-width: 18px; height: 18px; border-radius: 9px; background: #e74c3c; color: #fff; font-size: 11px; margin-left: 4px; padding: 0 5px; }
.tab-content { flex: 1; overflow-y: auto; min-height: 0; }
.loading, .error-msg, .empty-state { text-align: center; padding: 40px; color: var(--muted); }
.error-msg { color: #e74c3c; }
.section-title { font-size: 14px; font-weight: 600; margin: 16px 0 8px; }

/* ====== 仪表盘 ====== */
.dashboard-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 12px; }
.dash-card { background: var(--bg-soft); border: 1px solid var(--border); border-radius: 8px; padding: 16px; text-align: center; }
.dash-card-num { font-size: 28px; font-weight: 700; color: var(--primary); }
.dash-card-label { font-size: 12px; color: var(--muted); margin-top: 4px; }
.keypool-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 10px; }
.keypool-card { background: #f0f4f8; border: 1px solid var(--border); border-radius: 6px; padding: 10px 14px; }
.keypool-label { font-size: 11px; color: var(--muted); }
.keypool-value { font-size: 18px; font-weight: 700; color: var(--text); }
.keypool-value.queue-warn { color: #e74c3c; }
.keypool-value.rpm-high { color: #e65100; }
.key-mgmt-row { display: flex; gap: 8px; align-items: center; }
.key-input { flex: 1; max-width: 400px; padding: 5px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 12px; outline: none; }
.key-input:focus { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-light); }
.key-list { display: flex; flex-direction: column; gap: 4px; }
.key-item { display: flex; align-items: center; gap: 8px; padding: 4px 8px; background: #f8fafc; border: 1px solid var(--border); border-radius: 4px; font-size: 12px; }
.key-item-text { font-family: monospace; color: var(--text); }
.key-item-rpm { font-size: 11px; color: var(--muted); }

/* ====== 提示词管理 ====== */
.prompts-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(420px, 1fr)); gap: 16px; }
.prompt-card { background: var(--bg-soft); border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.prompt-card-header { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: #f0f4f8; border-bottom: 1px solid var(--border); }
.prompt-agent-name { font-weight: 600; font-size: 13px; }
.prompt-key { font-size: 11px; color: var(--muted); font-family: monospace; }
.prompt-editor { width: 100%; border: none; padding: 10px 12px; font-size: 12px; font-family: 'Consolas', 'Courier New', monospace; line-height: 1.5; resize: vertical; background: #fff; color: #333; outline: none; }
.prompt-card-footer { display: flex; justify-content: space-between; align-items: center; padding: 6px 12px; border-top: 1px solid var(--border); background: #fafbfc; }
.char-count { font-size: 11px; color: var(--muted); }

/* ====== 流程追踪 ====== */
.sessions-toolbar { display: flex; align-items: center; gap: 12px; padding: 8px 0; }
.sessions-list { display: flex; flex-direction: column; gap: 4px; }
.session-row { border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
.session-summary { display: flex; align-items: center; gap: 12px; padding: 8px 12px; cursor: pointer; background: var(--bg-soft); font-size: 13px; }
.session-summary:hover { background: #eef2f7; }
.session-row.expanded .session-summary { background: #e3eaf2; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.status-running { background: #3498db; }
.status-completed { background: #27ae60; }
.status-cancelled { background: #95a5a6; }
.status-unknown { background: #e0e0e0; }
.session-id { font-family: monospace; font-size: 12px; color: var(--muted); }
.session-title { font-weight: 600; flex: 1; }
.session-user { font-size: 11px; color: var(--muted); }
.session-status { font-size: 11px; }
.session-score { font-size: 12px; font-weight: 600; color: var(--primary); }
.expand-icon { font-size: 10px; color: var(--muted); }
.session-detail { padding: 12px 16px; border-top: 1px solid var(--border); background: #fff; }
.detail-content { display: flex; flex-direction: column; gap: 12px; }
.detail-section h4 { margin: 0 0 6px; font-size: 13px; color: var(--primary); }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 16px; font-size: 13px; }
.code-block { background: #1e293b; color: #e2e8f0; padding: 10px; border-radius: 6px; font-size: 12px; overflow-x: auto; margin: 0; white-space: pre-wrap; }
.step-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; margin-bottom: 6px; }
.step-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.step-name { font-weight: 600; font-size: 13px; }
.step-status { font-size: 11px; padding: 1px 8px; border-radius: 10px; background: #e8f5e9; color: #2e7d32; }
.step-status.pending { background: #fff3e0; color: #e65100; }
.step-status.failed { background: #ffebee; color: #c62828; }
.step-io { margin-top: 4px; font-size: 12px; }
.step-io pre { background: #f1f5f9; padding: 6px 8px; border-radius: 4px; font-size: 11px; margin: 2px 0 0; white-space: pre-wrap; word-break: break-all; max-height: 200px; overflow-y: auto; }

/* ====== 提示词变更日志 ====== */
.changes-list { display: flex; flex-direction: column; gap: 10px; }
.change-card { border: 1px solid var(--border); border-radius: 8px; padding: 12px; background: #fff; }
.feedback-card { border-left: 3px solid #e67e22; background: #fffaf5; }
.change-type-badge { font-size: 10px; padding: 1px 6px; border-radius: 3px; font-weight: 600; }
.badge-prompt { background: var(--primary-light); color: var(--primary); }
.badge-feedback { background: #fef3e2; color: #e67e22; }
.change-header { display: flex; gap: 10px; align-items: center; margin-bottom: 6px; font-size: 12px; }
.change-agent { font-weight: 600; color: var(--primary); background: var(--primary-light); padding: 1px 8px; border-radius: 3px; }
.change-version { font-family: monospace; font-size: 11px; color: var(--muted); }
.change-author { color: #666; }
.change-time { margin-left: auto; color: var(--muted); font-size: 11px; }
.change-msg { font-size: 13px; color: var(--text); margin-bottom: 6px; }
.change-preview { font-size: 11px; color: var(--muted); background: #f8fafc; padding: 6px 8px; border-radius: 4px; white-space: pre-wrap; word-break: break-all; max-height: 60px; overflow: hidden; }
.unread-dot { display: inline-flex; align-items: center; justify-content: center; min-width: 18px; height: 18px; border-radius: 9px; background: #e74c3c; color: #fff; font-size: 11px; padding: 0 5px; margin-left: 4px; }
.tab-badge-unread { background: #e74c3c; animation: pulse 1.5s infinite; }
@keyframes pulse { 0%, 100% { transform: scale(1); } 50% { transform: scale(1.1); } }
.ai-summary-panel { background: #f0f7ff; border: 1px solid #b3d4ff; border-radius: 8px; margin-top: 8px; padding: 12px; }
.ai-summary-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.ai-summary-text { font-size: 13px; color: var(--text); line-height: 1.6; white-space: pre-wrap; }
.ai-summary-prompt-section { margin-top: 8px; }
/* ====== 用户管理 ====== */
.users-table-wrap { overflow-x: auto; }
.users-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.users-table th { background: #f0f4f8; padding: 8px 10px; text-align: left; font-weight: 600; font-size: 12px; border-bottom: 2px solid var(--border); white-space: nowrap; }
.users-table td { padding: 8px 10px; border-bottom: 1px solid var(--border); }
.users-table tbody tr:hover { background: #f8fafc; }
.role-tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; font-weight: 600; }
.role-root { background: #ffebee; color: #c62828; }
.role-admin { background: #fff3e0; color: #e65100; }
.role-user { background: #e3f2fd; color: #1565c0; }
.status-tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; font-weight: 600; }
.status-active { background: #e8f5e9; color: #2e7d32; }
.status-banned { background: #ffebee; color: #c62828; }
.row-banned { opacity: 0.55; background: #fafafa; }
.user-search-input { width: 160px; padding: 5px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 12px; outline: none; }
.user-search-input:focus { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-light); }
.btn-xs { padding: 2px 8px; font-size: 11px; border-radius: 4px; border: none; cursor: pointer; }
.btn-danger { background: #e74c3c; color: #fff; }
.btn-danger:hover { background: #c0392b; }
.btn-success { background: #27ae60; color: #fff; }
.btn-success:hover { background: #219a52; }

/* ====== 提示词版本历史 ====== */
.prompt-history { border-top: 1px solid var(--border); padding: 10px 12px; background: #fafbfc; }
.history-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; }
.history-list { display: flex; flex-direction: column; gap: 6px; max-height: 300px; overflow-y: auto; }
.history-item { padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: #fff; font-size: 12px; }
.history-item.history-current { border-color: var(--primary); background: var(--primary-light); }
.history-meta { display: flex; gap: 8px; align-items: center; margin-bottom: 2px; }
.history-version { font-weight: 700; color: var(--primary); font-family: monospace; }
.history-author { font-size: 11px; color: var(--muted); }
.history-time { font-size: 11px; color: var(--muted); }
.history-msg { font-size: 11px; color: var(--muted); margin-bottom: 4px; font-style: italic; }
.history-preview { background: #f1f5f9; padding: 4px 6px; border-radius: 4px; font-size: 11px; margin: 4px 0; white-space: pre-wrap; word-break: break-all; color: #555; }

.sug-filter { padding: 3px 8px; border: 1px solid var(--border); border-radius: 4px; font-size: 12px; background: #fff; outline: none; }

/* ====== 社区审核 ====== */
.pill { display: inline-block; font-size: 11px; padding: 1px 8px; border-radius: 10px; background: #fff3e0; color: #e65100; margin-right: 6px; }
.pill.pill-easy { background: #e8f5e9; color: #2e7d32; }
.pill.pill-hard { background: #ffebee; color: #c62828; }
.traps-list { display: flex; flex-direction: column; gap: 10px; overflow-y: auto; }
.trap-row { display: flex; justify-content: space-between; gap: 12px; border: 1px solid var(--border); border-radius: 8px; padding: 12px 14px; background: #fff; }
.trap-row-main { flex: 1; min-width: 0; }
.trap-row-title { font-size: 14px; margin-bottom: 6px; }
.trap-row-meta { display: flex; gap: 16px; font-size: 12px; color: var(--muted); margin-bottom: 6px; flex-wrap: wrap; }
.trap-row-desc { font-size: 13px; color: #444; margin-bottom: 6px; white-space: pre-wrap; }
.trap-row-code { max-height: 160px; overflow-y: auto; }
.trap-row-actions { display: flex; flex-direction: column; gap: 6px; flex-shrink: 0; }
</style>