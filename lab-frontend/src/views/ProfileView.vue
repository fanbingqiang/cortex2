<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { labApi } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// ============ 用户画像 ============
interface ProfileData {
  userId: string
  username: string
  email: string
  role: string
  skillLevel: string
  totalQuestionsAnswered: number
  totalCorrect: number
  studyStreak: number
  accuracy: number
  weakPoints: string[]
  masteredPoints: string[]
  customProfile: string
  personalityTags: string[]
  learningGoal: string
  preferredDirection: string
}

const profile = ref<ProfileData>({
  userId: '', username: '', email: '', role: '', skillLevel: '',
  totalQuestionsAnswered: 0, totalCorrect: 0, studyStreak: 0, accuracy: 0,
  weakPoints: [], masteredPoints: [], customProfile: '',
  personalityTags: [], learningGoal: '', preferredDirection: ''
})

const loading = ref(false)
const saving = ref(false)
const savedMsg = ref('')

// 编辑状态
const editingWeak = ref('')
const editingMastered = ref('')
const editingTag = ref('')

// 折叠/展开状态
const showAllWeak = ref(false)
const showAllMastered = ref(false)
const MAX_VISIBLE_TAGS = 5

// ============ AI 报告 ============
const narrativeResult = ref<any>(null)
const narrativeLoading = ref(false)

function parseNarrative(data: unknown) {
  if (data == null) return { summary: '暂无数据', performance: '' }
  if (typeof data === 'object') return data as Record<string, unknown>
  if (typeof data === 'string') {
    const trimmed = data.trim()
    if (trimmed.startsWith('{')) {
      try { return JSON.parse(trimmed) } catch { /* fall through */ }
    }
    return { summary: 'AI 学习报告', performance: trimmed }
  }
  return { summary: 'AI 学习报告', performance: String(data) }
}

async function loadAiNarrative() {
  if (!auth.isLoggedIn) return
  narrativeLoading.value = true
  narrativeResult.value = null
  try {
    const res = await labApi.get<string>('/report/ai-narrative?userId=' + encodeURIComponent(auth.userId))
    if (res.code === 200 && res.data) {
      narrativeResult.value = parseNarrative(res.data)
    } else {
      narrativeResult.value = { summary: '生成失败', performance: res.message || '请稍后重试' }
    }
  } catch (e: any) {
    narrativeResult.value = { summary: '生成失败', performance: e?.message || '网络异常' }
  } finally { narrativeLoading.value = false }
}

// ============ 加载画像 ============
async function loadProfile() {
  if (!auth.isLoggedIn) return
  loading.value = true
  try {
    const res = await labApi.get<ProfileData>('/user/profile')
    if (res.code === 200 && res.data) {
      profile.value = res.data
    }
  } catch (e) {
    console.error('加载画像失败', e)
  } finally { loading.value = false }
}

// ============ 保存画像 ============
async function saveProfile() {
  saving.value = true
  savedMsg.value = ''
  try {
    const res = await labApi.put<void>('/user/profile', {
      skillLevel: profile.value.skillLevel,
      weakPoints: profile.value.weakPoints,
      masteredPoints: profile.value.masteredPoints,
      customProfile: profile.value.customProfile,
      personalityTags: profile.value.personalityTags,
      learningGoal: profile.value.learningGoal,
      preferredDirection: profile.value.preferredDirection
    })
    if (res.code === 200) {
      savedMsg.value = '保存成功'
      setTimeout(() => savedMsg.value = '', 2000)
    } else {
      savedMsg.value = '保存失败: ' + (res.message || '未知错误')
    }
  } catch (e: any) {
    savedMsg.value = '保存失败: ' + (e?.message || '网络异常')
  } finally { saving.value = false }
}

// ============ 标签操作 ============
function addWeakPoint() {
  const v = editingWeak.value.trim()
  if (v && !profile.value.weakPoints.includes(v)) {
    profile.value.weakPoints.push(v)
    editingWeak.value = ''
  }
}
function removeWeakPoint(idx: number) {
  profile.value.weakPoints.splice(idx, 1)
}
function removeWeakPointByValue(val: string) {
  const idx = profile.value.weakPoints.indexOf(val)
  if (idx >= 0) profile.value.weakPoints.splice(idx, 1)
}
function addMasteredPoint() {
  const v = editingMastered.value.trim()
  if (v && !profile.value.masteredPoints.includes(v)) {
    profile.value.masteredPoints.push(v)
    editingMastered.value = ''
  }
}
function removeMasteredPoint(idx: number) {
  profile.value.masteredPoints.splice(idx, 1)
}
function removeMasteredPointByValue(val: string) {
  const idx = profile.value.masteredPoints.indexOf(val)
  if (idx >= 0) profile.value.masteredPoints.splice(idx, 1)
}
function addTag() {
  const v = editingTag.value.trim()
  if (v && !profile.value.personalityTags.includes(v)) {
    profile.value.personalityTags.push(v)
    editingTag.value = ''
  }
}
function removeTag(idx: number) {
  profile.value.personalityTags.splice(idx, 1)
}

// 技能等级选项
const skillLevels = ['入门', '初级', '中级', '高级', '专家']

// 计算掌握率
const masteredRate = computed(() => {
  if (!profile.value.totalQuestionsAnswered) return 0
  return Math.round((profile.value.totalCorrect / profile.value.totalQuestionsAnswered) * 100)
})

// 可见标签（折叠逻辑）
const visibleWeakPoints = computed(() => {
  if (showAllWeak.value || profile.value.weakPoints.length <= MAX_VISIBLE_TAGS) return profile.value.weakPoints
  return profile.value.weakPoints.slice(0, MAX_VISIBLE_TAGS)
})
const visibleMasteredPoints = computed(() => {
  if (showAllMastered.value || profile.value.masteredPoints.length <= MAX_VISIBLE_TAGS) return profile.value.masteredPoints
  return profile.value.masteredPoints.slice(0, MAX_VISIBLE_TAGS)
})

onMounted(() => {
  if (auth.isLoggedIn) loadProfile()
})

// 监听登录状态变化，切换账号时自动刷新
watch(
  () => auth.userId,
  (newId, oldId) => {
    if (newId !== oldId) {
      showAllWeak.value = false
      showAllMastered.value = false
      narrativeResult.value = null
      if (auth.isLoggedIn) loadProfile()
      else {
        profile.value = {
          userId: '', username: '', email: '', role: '', skillLevel: '',
          totalQuestionsAnswered: 0, totalCorrect: 0, studyStreak: 0, accuracy: 0,
          weakPoints: [], masteredPoints: [], customProfile: '',
          personalityTags: [], learningGoal: '', preferredDirection: ''
        }
      }
    }
  }
)
</script>

<template>
  <div class="profile-page">
    <div class="profile-header">
      <h2>个人中心</h2>
      <button class="btn btn-primary" :disabled="saving" @click="saveProfile">
        {{ saving ? '保存中...' : '保存画像' }}
      </button>
      <span v-if="savedMsg" class="save-msg" :class="{ error: savedMsg.includes('失败') }">{{ savedMsg }}</span>
    </div>

    <div v-if="loading" class="loading">加载中...</div>

    <template v-else>
      <!-- ========== 用户信息卡片 ========== -->
      <div class="user-card">
        <div class="user-avatar">{{ (profile.username || auth.username || '?').charAt(0).toUpperCase() }}</div>
        <div class="user-info">
          <div class="user-name">{{ profile.username || auth.username || '未登录' }}</div>
          <div class="user-meta">
            <span v-if="profile.role" class="meta-badge">{{ profile.role === 'ROOT' ? '管理员' : profile.role === 'ADMIN' ? '管理员' : '普通用户' }}</span>
            <span v-if="profile.email" class="meta-text">{{ profile.email }}</span>
          </div>
        </div>
      </div>

      <!-- ========== 学习统计 ========== -->
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-value" style="color:var(--primary)">{{ profile.totalQuestionsAnswered || 0 }}</div>
          <div class="stat-label">答题数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color:var(--success)">{{ profile.accuracy ?? 0 }}%</div>
          <div class="stat-label">正确率</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color:var(--purple)">{{ profile.studyStreak || 0 }}</div>
          <div class="stat-label">连续天数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color:var(--warning)">{{ profile.masteredPoints?.length || 0 }}</div>
          <div class="stat-label">已掌握</div>
        </div>
      </div>

      <div class="profile-grid">
        <!-- 左列 -->
        <div class="profile-col">
          <!-- ========== 薄弱点 ========== -->
          <div class="section-card">
            <div class="section-title">
              <span class="section-icon">&#9888;</span> 薄弱点
              <span class="section-hint">（可手动编辑）</span>
            </div>
            <div class="tag-list">
              <span v-for="(w, i) in visibleWeakPoints" :key="i" class="tag tag-danger">
                {{ w }}
                <button class="tag-remove" @click="removeWeakPointByValue(w)">&times;</button>
              </span>
              <span v-if="!profile.weakPoints?.length" class="empty-hint">暂无薄弱点，开始答题后自动识别</span>
            </div>
            <div v-if="profile.weakPoints.length > MAX_VISIBLE_TAGS" class="expand-row">
              <button class="expand-btn" @click="showAllWeak = !showAllWeak">
                {{ showAllWeak ? '收起' : `展开更多 (${profile.weakPoints.length - MAX_VISIBLE_TAGS})` }}
              </button>
            </div>
            <div class="tag-input-row">
              <input v-model="editingWeak" placeholder="添加薄弱点..." @keyup.enter="addWeakPoint" />
              <button class="btn btn-sm btn-outline" @click="addWeakPoint">添加</button>
            </div>
          </div>

          <!-- ========== 掌握点 ========== -->
          <div class="section-card">
            <div class="section-title">
              <span class="section-icon">&#10003;</span> 掌握点
              <span class="section-hint">（自动识别 + 可手动编辑）</span>
            </div>
            <div class="tag-list">
              <span v-for="(m, i) in visibleMasteredPoints" :key="i" class="tag tag-success">
                {{ m }}
                <button class="tag-remove" @click="removeMasteredPointByValue(m)">&times;</button>
              </span>
              <span v-if="!profile.masteredPoints?.length" class="empty-hint">暂无掌握点，在知识树中标记掌握后自动显示</span>
            </div>
            <div v-if="profile.masteredPoints.length > MAX_VISIBLE_TAGS" class="expand-row">
              <button class="expand-btn" @click="showAllMastered = !showAllMastered">
                {{ showAllMastered ? '收起' : `展开更多 (${profile.masteredPoints.length - MAX_VISIBLE_TAGS})` }}
              </button>
            </div>
            <div class="tag-input-row">
              <input v-model="editingMastered" placeholder="添加掌握点..." @keyup.enter="addMasteredPoint" />
              <button class="btn btn-sm btn-outline" @click="addMasteredPoint">添加</button>
            </div>
          </div>

          <!-- ========== 技能等级 ========== -->
          <div class="section-card">
            <div class="section-title"><span class="section-icon">&#9733;</span> 技能等级</div>
            <div class="skill-selector">
              <button
                v-for="lv in skillLevels" :key="lv"
                class="skill-btn"
                :class="{ active: profile.skillLevel === lv }"
                @click="profile.skillLevel = lv"
              >{{ lv }}</button>
            </div>
          </div>
        </div>

        <!-- 右列 -->
        <div class="profile-col">
          <!-- ========== 自定义画像 ========== -->
          <div class="section-card">
            <div class="section-title">
              <span class="section-icon">&#9998;</span> 自定义画像
              <span class="section-hint">（会传给 AI 导师）</span>
            </div>
            <textarea
              v-model="profile.customProfile"
              class="custom-profile-input"
              placeholder="介绍一下你自己，比如：&#10;我是 Java 后端开发，有 2 年经验，正在准备大厂面试，比较薄弱的是并发编程和 JVM..."
              rows="5"
            ></textarea>
          </div>

          <!-- ========== 学习目标 ========== -->
          <div class="section-card">
            <div class="section-title"><span class="section-icon">&#127919;</span> 学习目标</div>
            <input
              v-model="profile.learningGoal"
              class="text-input"
              placeholder="例如：准备阿里巴巴 Java 后端面试"
            />
          </div>

          <!-- ========== 偏好方向 ========== -->
          <div class="section-card">
            <div class="section-title"><span class="section-icon">&#128269;</span> 偏好方向</div>
            <input
              v-model="profile.preferredDirection"
              class="text-input"
              placeholder="例如：Redis、微服务、高并发"
            />
          </div>
        </div>
      </div>

      <!-- ========== AI 学习报告 ========== -->
      <div class="section-card" style="margin-top:0;">
        <div class="report-header">
          <span class="section-title" style="margin-bottom:0;">
            <span class="section-icon">&#129504;</span> AI 学习分析
          </span>
          <button class="btn btn-outline btn-sm" :disabled="narrativeLoading" @click="loadAiNarrative">
            {{ narrativeLoading ? '生成中...' : '生成报告' }}
          </button>
        </div>
        <div v-if="narrativeResult" class="narrative-result">
          <div class="narrative-section">
            <div class="narrative-label">总结</div>
            <div>{{ narrativeResult.summary }}</div>
          </div>
          <div class="narrative-section">
            <div class="narrative-label">表现</div>
            <div style="white-space:pre-wrap;">{{ narrativeResult.performance }}</div>
          </div>
          <div v-if="narrativeResult.recommendations?.length">
            <div class="narrative-label">建议</div>
            <ul><li v-for="r in narrativeResult.recommendations" :key="r">{{ r }}</li></ul>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.profile-page {
  flex: 1;
  overflow-y: auto;
  max-width: 900px;
  margin: 0 auto;
  padding: 20px 24px;
}

.profile-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}
.profile-header h2 {
  font-size: 20px;
  font-weight: 700;
  margin: 0;
  flex: 1;
}

.save-msg {
  font-size: 13px;
  color: var(--success);
}
.save-msg.error {
  color: var(--danger);
}

.loading {
  text-align: center;
  padding: 60px 20px;
  color: var(--muted);
  font-size: 14px;
}

/* 用户卡片 */
.user-card {
  display: flex;
  align-items: center;
  gap: 16px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px 24px;
  margin-bottom: 20px;
}
.user-avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--primary), var(--purple));
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: 700;
  flex-shrink: 0;
}
.user-name {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}
.user-meta {
  display: flex;
  gap: 8px;
  align-items: center;
}
.meta-badge {
  font-size: 11px;
  background: var(--primary-light);
  color: var(--primary);
  padding: 2px 8px;
  border-radius: 4px;
}
.meta-text {
  font-size: 12px;
  color: var(--muted);
}

/* 统计 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}
.stat-value {
  font-size: 24px;
  font-weight: 700;
}
.stat-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px;
  text-align: center;
}
.stat-label {
  font-size: 12px;
  color: var(--muted);
  margin-top: 4px;
}

/* 双列布局 */
.profile-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
  align-items: start;
}
.profile-col {
  display: flex;
  flex-direction: column;
}
@media (max-width: 700px) {
  .profile-grid { grid-template-columns: 1fr; }
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
}

/* 通用卡片 */
.section-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 18px 20px;
  margin-bottom: 16px;
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.section-icon {
  font-size: 16px;
}
.section-hint {
  font-size: 11px;
  font-weight: 400;
  color: var(--muted);
  margin-left: 4px;
}

/* 标签 */
.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
  min-height: 28px;
}
.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}
.tag-danger {
  background: #fef2f2;
  color: var(--danger);
  border: 1px solid #fecaca;
}
.tag-success {
  background: #f0fdf4;
  color: var(--success);
  border: 1px solid #bbf7d0;
}
.tag-primary {
  background: var(--primary-light);
  color: var(--primary);
  border: 1px solid #bfdbfe;
}
.tag-remove {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 0 2px;
  color: inherit;
  opacity: 0.6;
}
.tag-remove:hover {
  opacity: 1;
}
.tag-input-row {
  display: flex;
  gap: 8px;
}
.tag-input-row input {
  flex: 1;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 12px;
  outline: none;
}
.tag-input-row input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px var(--primary-light);
}
.empty-hint {
  font-size: 12px;
  color: var(--muted);
  font-style: italic;
}
.expand-row {
  margin-bottom: 8px;
}
.expand-btn {
  background: none;
  border: none;
  font-size: 11px;
  color: var(--primary);
  cursor: pointer;
  padding: 0;
}
.expand-btn:hover {
  text-decoration: underline;
}

/* 技能等级选择器 */
.skill-selector {
  display: flex;
  gap: 8px;
}
.skill-btn {
  flex: 1;
  padding: 8px 4px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
  text-align: center;
  color: var(--text);
}
.skill-btn:hover {
  border-color: var(--primary);
  background: var(--primary-light);
}
.skill-btn.active {
  background: var(--primary);
  color: white;
  border-color: var(--primary);
  font-weight: 600;
}

/* 文本输入 */
.text-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  box-sizing: border-box;
}
.text-input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px var(--primary-light);
}
.custom-profile-input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  resize: vertical;
  font-family: inherit;
  line-height: 1.6;
  box-sizing: border-box;
}
.custom-profile-input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px var(--primary-light);
}

/* AI 报告 */
.report-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.narrative-result {
  border-top: 1px solid var(--border);
  padding-top: 12px;
}
.narrative-section {
  margin-bottom: 12px;
  line-height: 1.6;
}
.narrative-label {
  font-weight: 600;
  margin-bottom: 4px;
  color: var(--text);
}
</style>