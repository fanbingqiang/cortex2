<script setup lang="ts">
import { ref, onMounted, computed, inject } from 'vue'
import { labApi } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import { SHOW_TOAST } from '@/symbols'
import type { CommunityTrapDto, CommunityTrapSubmitRequest, ApiResponse } from '@/types'

const auth = useAuthStore()
const router = useRouter()
const showToast = inject(SHOW_TOAST) as (msg: string) => void

const traps = ref<CommunityTrapDto[]>([])
const selectedTrap = ref<CommunityTrapDto | null>(null)
const loading = ref(false)
const detailLoading = ref(false)
const showSubmitModal = ref(false)
const submitting = ref(false)
const submitError = ref('')

const filters = ref<{ version: string; category: string; status: string }>({
  version: 'All',
  category: 'All',
  status: 'All',
})

const versions = ['All', 'Java 8', 'Java 11', 'Java 17', 'Java 21']
const categories = ['All', 'Java核心', 'Spring项目']
const statuses = ['All', '已通过', '待审核', '已拒绝']

/** 转义 HTML 特殊字符 */
function esc(s: string): string {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/** 解析筛选条件为 API 参数 */
function buildFilterParams(): string {
  const params = new URLSearchParams()
  if (filters.value.version !== 'All') params.set('version', filters.value.version)
  if (filters.value.category !== 'All') params.set('category', filters.value.category)
  if (filters.value.status !== 'All') params.set('status', filters.value.status)
  const qs = params.toString()
  return qs ? '?' + qs : ''
}

/** 加载陷阱列表 */
async function loadTraps() {
  loading.value = true
  try {
    const res = await labApi.get<CommunityTrapDto[]>('/community/traps' + buildFilterParams())
    if (res.code === 200 && res.data) {
      traps.value = res.data
    }
  } finally {
    loading.value = false
  }
}

/** 选中陷阱查看详情 */
async function selectTrap(trap: CommunityTrapDto) {
  detailLoading.value = true
  try {
    const res = await labApi.get<CommunityTrapDto>('/community/traps/' + trap.id)
    if (res.code === 200 && res.data) {
      selectedTrap.value = res.data
    }
  } finally {
    detailLoading.value = false
  }
}

/** 投票 */
async function vote(trapId: number) {
  const res = await labApi.post<{ voteCount: number }>('/community/traps/' + trapId + '/vote')
  if (res.code === 200 && selectedTrap.value) {
    selectedTrap.value.voteCount = res.data?.voteCount ?? selectedTrap.value.voteCount + 1
    // 更新列表中的投票数
    const found = traps.value.find(t => t.id === trapId)
    if (found) found.voteCount = selectedTrap.value.voteCount
  }
}

/** 去实战：把问题/代码加载到实战练习编辑器 */
function goPractice(trap: CommunityTrapDto) {
  router.push('/practice')
  setTimeout(() => {
    window.dispatchEvent(new CustomEvent('load-code', {
      detail: { code: trap.trapCode || '', title: trap.title, knowledgePoint: trap.knowledgePoint || trap.title },
    }))
  }, 100)
}

/** 提交新问题（简化：只提问题，代码可选） */
async function submitTrap() {
  submitError.value = ''
  submitting.value = true
  try {
    const res = await labApi.post<CommunityTrapDto>('/community/submit', submitForm.value)
    if (res.code === 200) {
      showSubmitModal.value = false
      resetSubmitForm()
      await loadTraps()
      showToast('提交成功，等待管理员审核')
    } else {
      submitError.value = res.message || '提交失败'
    }
  } finally {
    submitting.value = false
  }
}

const submitForm = ref<CommunityTrapSubmitRequest>({
  title: '',
  knowledgePoint: '',
  category: 'Java核心',
  javaVersion: 'Java 17',
  trapCode: '',
  expectedPitfall: '',
  correctExplanation: '',
  hints: '',
  difficulty: 3,
})

function resetSubmitForm() {
  submitForm.value = {
    title: '',
    knowledgePoint: '',
    category: 'Java核心',
    javaVersion: 'Java 17',
    trapCode: '',
    expectedPitfall: '',
    correctExplanation: '',
    hints: '',
    difficulty: 3,
  }
}

/** 难度星标 */
function difficultyStars(d: number): string {
  return '★'.repeat(d) + '☆'.repeat(5 - d)
}

/** 状态标签文字 */
function statusLabel(s: string): string {
  const map: Record<string, string> = {
    PENDING: '待审核',
    APPROVED: '已通过',
    REJECTED: '已拒绝',
  }
  return map[s] || s
}

/** 状态标签样式 */
function statusClass(s: string): string {
  const map: Record<string, string> = {
    PENDING: 'pill',
    APPROVED: 'pill pill-easy',
    REJECTED: 'pill pill-hard',
  }
  return map[s] || 'pill'
}

onMounted(() => {
  loadTraps()
})
</script>

<template>
  <div class="community-layout">
    <!-- 左侧面板 -->
    <div class="left-panel">
      <div class="left-panel-inner">
        <div class="left-header">
          <span class="left-title">陷阱社区</span>
          <button class="btn btn-primary btn-sm" @click="showSubmitModal = true">+ 提交问题</button>
        </div>

        <!-- 版本筛选 -->
        <div class="filter-section">
          <div class="filter-label">Java 版本</div>
          <div class="filter-tabs">
            <span
              v-for="v in versions"
              :key="v"
              class="tab-btn"
              :class="{ active: filters.version === v }"
              @click="filters.version = v; loadTraps()"
            >{{ v }}</span>
          </div>
        </div>

        <!-- 分类筛选 -->
        <div class="filter-section">
          <div class="filter-label">分类</div>
          <div class="filter-tabs">
            <span
              v-for="c in categories"
              :key="c"
              class="tab-btn"
              :class="{ active: filters.category === c }"
              @click="filters.category = c; loadTraps()"
            >{{ c }}</span>
          </div>
        </div>

        <!-- 状态筛选 -->
        <div class="filter-section">
          <div class="filter-label">状态</div>
          <div class="filter-tabs">
            <span
              v-for="s in statuses"
              :key="s"
              class="tab-btn"
              :class="{ active: filters.status === s }"
              @click="filters.status = s; loadTraps()"
            >{{ s }}</span>
          </div>
        </div>

        <!-- 陷阱列表 -->
        <div class="trap-list scroll-y">
          <div v-if="loading" class="trap-list-empty">加载中...</div>
          <div
            v-for="trap in traps"
            :key="trap.id"
            class="trap-item"
            :class="{ selected: selectedTrap?.id === trap.id }"
            @click="selectTrap(trap)"
          >
            <div class="trap-item-title">{{ trap.title }}</div>
            <div class="trap-item-meta">
              <span class="trap-item-difficulty">{{ difficultyStars(trap.difficulty) }}</span>
              <span class="trap-item-category">{{ trap.category }}</span>
              <span class="trap-item-version">{{ trap.javaVersion }}</span>
            </div>
            <div class="trap-item-footer">
              <span :class="statusClass(trap.status)">{{ statusLabel(trap.status) }}</span>
              <span class="trap-item-votes">{{ trap.voteCount }} 票</span>
            </div>
          </div>
          <div v-if="!loading && traps.length === 0" class="trap-list-empty">暂无陷阱数据</div>
        </div>
      </div>
    </div>

    <!-- 右侧详情面板 -->
    <div class="right-panel detail-panel">
      <div v-if="detailLoading" class="detail-placeholder">加载中...</div>
      <div v-else-if="!selectedTrap" class="detail-placeholder">
        <div class="placeholder-icon">!</div>
        <div>选择一个陷阱查看详情</div>
      </div>
      <div v-else class="trap-detail">
        <h2 class="detail-title">{{ selectedTrap.title }}</h2>
        <div class="detail-meta">
          <span class="detail-difficulty">{{ difficultyStars(selectedTrap.difficulty) }}</span>
          <span :class="statusClass(selectedTrap.status)">{{ statusLabel(selectedTrap.status) }}</span>
        </div>
        <div class="detail-info">
          <span>分类: {{ selectedTrap.category }}</span>
          <span>Java版本: {{ selectedTrap.javaVersion }}</span>
          <span>提交者: {{ selectedTrap.submitter }}</span>
          <span>票数: {{ selectedTrap.voteCount }}</span>
        </div>

        <div v-if="selectedTrap.trapCode" class="detail-section">
          <div class="detail-section-label">相关代码</div>
          <pre class="code-block"><code>{{ esc(selectedTrap.trapCode) }}</code></pre>
        </div>

        <div v-if="selectedTrap.expectedPitfall" class="detail-section">
          <div class="detail-section-label">问题描述</div>
          <div class="detail-text">{{ selectedTrap.expectedPitfall }}</div>
        </div>

        <div v-if="selectedTrap.correctExplanation" class="detail-section">
          <div class="detail-section-label">解答</div>
          <div class="detail-text">{{ selectedTrap.correctExplanation }}</div>
        </div>

        <div v-if="selectedTrap.hints" class="detail-section">
          <div class="detail-section-label">提示</div>
          <div class="detail-text">{{ selectedTrap.hints }}</div>
        </div>

        <div class="detail-actions">
          <button class="btn btn-outline btn-sm" @click="vote(selectedTrap.id)">
            {{ selectedTrap.voteCount > 0 ? '投票 (' + selectedTrap.voteCount + ')' : '投票' }}
          </button>
          <button
            v-if="selectedTrap.status === 'APPROVED'"
            class="btn btn-primary btn-sm"
            @click="goPractice(selectedTrap)"
          >▶ 去实战练习</button>
        </div>
      </div>
    </div>

    <!-- 提交问题弹窗 -->
    <div v-if="showSubmitModal" class="modal-overlay" @click.self="showSubmitModal = false">
      <div class="modal-box">
        <button class="modal-close" @click="showSubmitModal = false">&times;</button>
        <h3 style="margin: 0 0 16px; font-size: 16px;">提交问题</h3>

        <div class="submit-form">
          <div class="form-row">
            <label>问题标题</label>
            <input v-model="submitForm.title" placeholder="如: 缓存击穿是什么？" />
          </div>
          <div class="form-row">
            <label>所属知识点</label>
            <input v-model="submitForm.knowledgePoint" placeholder="如: Redis 缓存" />
          </div>
          <div class="form-row">
            <label>问题描述 <span style="color:var(--danger);">*</span></label>
            <textarea v-model="submitForm.expectedPitfall" placeholder="描述你的问题，或写出你认为有问题的代码片段" rows="5"></textarea>
          </div>
          <div class="form-row">
            <label>相关代码（可选）</label>
            <textarea
              v-model="submitForm.trapCode"
              placeholder="粘贴相关代码，方便他人分析"
              rows="5"
              class="code-textarea"
            ></textarea>
          </div>
          <div v-if="submitError" class="submit-error">{{ submitError }}</div>
          <div class="form-actions">
            <button class="btn btn-ghost" @click="showSubmitModal = false">取消</button>
            <button class="btn btn-primary" :disabled="submitting" @click="submitTrap">
              {{ submitting ? '提交中...' : '提交' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.community-layout {
  display: flex;
  flex: 1;
  overflow: hidden;
  min-width: 0;
}

/* 左侧面板 */
.left-panel {
  width: 360px;
  min-width: 360px;
  background: var(--surface);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.left-panel-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.left-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.left-title {
  font-weight: 600;
  font-size: 14px;
}

/* 筛选区域 */
.filter-section {
  padding: 10px 16px 6px;
  flex-shrink: 0;
}

.filter-label {
  font-size: 11px;
  color: var(--muted);
  margin-bottom: 6px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.filter-tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

/* 陷阱列表 */
.trap-list {
  flex: 1;
  padding: 8px;
}

.trap-item {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.15s;
}

.trap-item:hover {
  border-color: var(--primary);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.trap-item.selected {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px var(--primary-light);
}

.trap-item-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
  line-height: 1.4;
}

.trap-item-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.trap-item-difficulty {
  font-size: 11px;
  color: var(--warning);
}

.trap-item-category {
  font-size: 11px;
  color: var(--primary);
  background: var(--primary-light);
  padding: 1px 6px;
  border-radius: 4px;
}

.trap-item-version {
  font-size: 11px;
  color: var(--muted);
}

.trap-item-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.trap-item-votes {
  font-size: 11px;
  color: var(--muted);
}

.trap-list-empty {
  text-align: center;
  padding: 40px 20px;
  color: var(--muted);
  font-size: 13px;
}

/* 右侧详情 */
.right-panel {
  flex: 1;
  min-width: 0;
}

.detail-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--muted);
  font-size: 14px;
  gap: 12px;
}

.placeholder-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--bg);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: 700;
  color: var(--border);
}

.trap-detail {
  max-width: 720px;
}

.detail-title {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 8px;
}

.detail-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.detail-difficulty {
  font-size: 14px;
  color: var(--warning);
}

.detail-info {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 20px;
}

.detail-section {
  margin-bottom: 16px;
}

.detail-section-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text);
  margin-bottom: 6px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.code-block {
  background: #1e293b;
  color: #e2e8f0;
  border-radius: 8px;
  padding: 14px 16px;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  font-size: 13px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  margin: 0;
}

.detail-text {
  font-size: 14px;
  line-height: 1.7;
  color: var(--text);
  padding: 12px;
  background: var(--bg);
  border-radius: 8px;
}

.detail-actions {
  display: flex;
  gap: 8px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
}

/* 提交表单 */
.submit-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.form-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.form-row label {
  font-size: 12px;
  font-weight: 500;
  color: var(--muted);
}

.form-row input,
.form-row select,
.form-row textarea {
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 13px;
  outline: none;
  font-family: inherit;
  background: var(--bg);
  color: var(--text);
}

.form-row input:focus,
.form-row select:focus,
.form-row textarea:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px var(--primary-light);
}

.form-row-half {
  display: flex;
  flex-direction: row;
  gap: 12px;
}

.form-field {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.code-textarea {
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  font-size: 12px !important;
}

.difficulty-selector {
  display: flex;
  align-items: center;
  gap: 2px;
}

.star-option {
  font-size: 20px;
  cursor: pointer;
  color: var(--border);
  transition: color 0.15s;
  padding: 2px;
}

.star-option.active {
  color: var(--warning);
}

.star-option:hover {
  color: var(--warning);
}

.submit-error {
  color: var(--danger);
  font-size: 12px;
  padding: 6px 10px;
  background: #fef2f2;
  border-radius: 6px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 8px;
}

@media (max-width: 800px) {
  .left-panel {
    width: 280px;
    min-width: 280px;
  }
}
</style>
