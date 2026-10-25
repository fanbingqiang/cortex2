<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { labApi, authApi } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useQuestionsStore } from '@/stores/questions'
import type { QuestionDto } from '@/types'

const auth = useAuthStore()
const questionsStore = useQuestionsStore()

// ============ 学习进度 ============
const reviewTab = ref<'due' | 'mastered' | 'all'>('due')
const reviewList = ref<QuestionDto[]>([])
const loading = ref(false)
const stats = ref({ total: 0, masteredCount: 0, dueCount: 0, streak: 0 })
const now = ref(new Date())
const filteredList = computed(() => {
  const list = reviewList.value
  if (reviewTab.value === 'due') return list.filter(q => q.nextReviewTime && new Date(q.nextReviewTime) <= now.value)
  if (reviewTab.value === 'mastered') return list.filter(q => q.mastered === true)
  return list
})
function isOverdue(t: string | null | undefined) { return t ? new Date(t) <= now.value : false }
function fmtTime(t: string | null | undefined) {
  if (!t) return '-'
  const d = new Date(t)
  return `${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}
function startReview(q: QuestionDto) { questionsStore.selectedQuestion = q; window.location.hash = '#/practice' }
async function loadReviews() {
  if (!auth.isLoggedIn) return; loading.value = true
  try {
    const dueRes = await labApi.get<QuestionDto[]>('/questions/review/due?userId=' + encodeURIComponent(auth.userId))
    if (dueRes.code === 200) reviewList.value = dueRes.data || []
    const allRes = await labApi.get<QuestionDto[]>('/questions?userId=' + encodeURIComponent(auth.userId))
    if (allRes.code === 200 && allRes.data) {
      const all = allRes.data
      const mastered = all.filter(q => q.mastered === true)
      const due = all.filter(q => q.nextReviewTime && new Date(q.nextReviewTime) <= now.value)
      const dates = [...new Set(all.map(q => q.gmtCreate).filter(Boolean).map(d => new Date(d as string).toISOString().slice(0, 10)))].sort()
      let streak = 0
      for (let i = dates.length - 1; i >= 0; i--) {
        if (dates[i] === new Date(new Date().toISOString().slice(0,10)).getTime() - (dates.length-1-i) * 86400000 ? 'x' : '') break
        streak++
      }
      const today = new Date().toISOString().slice(0, 10)
      for (let i = dates.length - 1; i >= 0; i--) {
        const expected = new Date(today); expected.setDate(expected.getDate() - (dates.length - 1 - i))
        if (dates[i] === expected.toISOString().slice(0, 10)) streak++
        else break
      }
      stats.value = { total: all.length, masteredCount: mastered.length, dueCount: due.length, streak }
    }
  } finally { loading.value = false }
}

 // ============ AI 报告 ============
const narrativeResult = ref<any>(null)
const narrativeLoading = ref(false)
const notificationConfig = ref<any>(null)
const showNotifConfig = ref(false)
const notifLoading = ref(false)
function openNotifConfig() { showNotifConfig.value = true; loadNotificationConfig() }
async function loadNotificationConfig() {
  try {
    const res = await authApi.get<any>('/notification/config?userId=' + encodeURIComponent(auth.userId))
    if (res.code === 200) notificationConfig.value = res.data || {}
  } catch {}
}
async function updateNotificationConfig() {
  if (!notificationConfig.value) return; notifLoading.value = true
  try { await authApi.put('/notification/config', { userId: auth.userId, ...notificationConfig.value }) } catch {}
  finally { notifLoading.value = false }
}
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
    const res = await authApi.get<string>('/report/ai-narrative?userId=' + encodeURIComponent(auth.userId) + '&type=MONTHLY')
    if (res.code === 200 && res.data) {
      narrativeResult.value = parseNarrative(res.data)
    } else {
      narrativeResult.value = { summary: '生成失败', performance: res.message || '请稍后重试' }
    }
  } catch (e: any) {
    narrativeResult.value = { summary: '生成失败', performance: e?.message || '网络异常' }
  } finally { narrativeLoading.value = false }
}

onMounted(async () => {
  if (auth.isLoggedIn) { await loadReviews() }
})
</script>
<template>
  <div class="tab-page">
    <h2 style="font-size:18px;font-weight:600;margin:0 0 16px;">学习中心</h2>

    <!-- ========== 学习进度 ========== -->
      <div class="stats-grid">
        <div class="stat-card"><div class="stat-value" style="color:var(--primary);">{{ stats.total }}</div><div class="stat-label">答题数</div></div>
        <div class="stat-card"><div class="stat-value" style="color:var(--success);">{{ stats.total > 0 ? ((stats.masteredCount/stats.total)*100).toFixed(1) : 0 }}%</div><div class="stat-label">掌握率</div></div>
        <div class="stat-card"><div class="stat-value" style="color:var(--warning);">{{ stats.dueCount }}</div><div class="stat-label">待复习</div></div>
        <div class="stat-card"><div class="stat-value" style="color:var(--purple);">{{ stats.streak }}</div><div class="stat-label">连续天数</div></div>
      </div>
      <div class="review-tabs">
        <span class="tab-btn" :class="{ active: reviewTab === 'due' }" @click="reviewTab = 'due'">待复习</span>
        <span class="tab-btn" :class="{ active: reviewTab === 'mastered' }" @click="reviewTab = 'mastered'">已掌握</span>
        <span class="tab-btn" :class="{ active: reviewTab === 'all' }" @click="reviewTab = 'all'">全部</span>
      </div>
      <div v-if="loading" class="empty">加载中...</div>
      <div v-else-if="filteredList.length === 0" class="empty">{{ reviewTab === 'due' ? '暂无待复习' : reviewTab === 'mastered' ? '暂无已掌握' : '暂无记录' }}</div>
      <div v-else class="review-list">
        <div v-for="item in filteredList" :key="item.id" class="review-item" :class="{ overdue: isOverdue(item.nextReviewTime) }">
          <div class="review-item-header">
            <span class="review-item-title">{{ item.title }}</span>
            <span v-if="item.mastered" class="pill pill-easy">已掌握</span>
          </div>
          <div class="review-item-meta">
            <span class="review-item-category">{{ item.category || '未分类' }}</span>
            <span class="review-item-date" :style="{ color: isOverdue(item.nextReviewTime) ? 'var(--danger)' : 'var(--muted)' }">{{ isOverdue(item.nextReviewTime) ? '已逾期 ' : '下次 ' }}{{ fmtTime(item.nextReviewTime) }}</span>
          </div>
          <div class="review-item-action"><button class="btn btn-primary btn-sm" @click="startReview(item)">开始复习</button></div>
        </div>
      </div>

    <!-- ========== AI 报告 ========== -->
    <div style="margin-top:24px;">
      <div class="report-header">
        <span style="font-size:15px;font-weight:600;">AI 学习分析</span>
        <div style="flex:1"></div>
        <button class="btn btn-ghost btn-sm" @click="openNotifConfig">通知设置</button>
      </div>
      <div v-if="!auth.isLoggedIn" class="empty">请先登录</div>
      <div v-else>
        <div class="report-actions">
          <button class="btn btn-outline" :disabled="narrativeLoading" @click="loadAiNarrative">{{ narrativeLoading ? '生成中...' : 'AI 报告' }}</button>
        </div>
        <div v-if="narrativeResult" class="section-card" style="margin-top:16px;">
          <h3>AI 报告</h3>
          <div class="analysis-section"><div class="analysis-label">总结</div><div>{{ narrativeResult.summary }}</div></div>
          <div class="analysis-section"><div class="analysis-label">表现</div><div style="white-space:pre-wrap;">{{ narrativeResult.performance }}</div></div>
          <div v-if="narrativeResult.recommendations?.length"><div class="analysis-label">建议</div><ul><li v-for="r in narrativeResult.recommendations" :key="r">{{ r }}</li></ul></div>
        </div>
      </div>
    </div>

    <!-- 通知设置弹窗 -->
    <div v-if="showNotifConfig" class="modal-overlay" @click.self="showNotifConfig = false">
      <div class="modal-box">
        <button class="modal-close" @click="showNotifConfig = false">✕</button>
        <h3 style="margin:0 0 16px;">通知设置</h3>
        <div v-if="!notificationConfig">加载中...</div>
        <div v-else style="display:flex;flex-direction:column;gap:12px;">
          <div v-for="(_, key) in { dailyReminder: '每日提醒', reviewReminder: '复习提醒', reportPush: '报告推送', aiActiveMode: 'AI 导师主动模式' }" :key="key" style="display:flex;align-items:center;justify-content:space-between;">
            <span style="font-size:13px;">{{ key === 'dailyReminder' ? '每日学习提醒' : key === 'reviewReminder' ? '复习提醒' : key === 'reportPush' ? '报告推送' : 'AI 导师主动模式' }}</span>
            <label><input type="checkbox" v-model="notificationConfig[key]" true-value="true" false-value="false" /> <span style="font-size:12px;color:var(--muted);">{{ notificationConfig[key] === 'true' ? '已开启' : '已关闭' }}</span></label>
          </div>
          <div v-if="notificationConfig.aiActiveMode === 'true'" style="font-size:11px;color:var(--muted);padding:4px 0;">AI 会主动观察你的学习状态并给出教学建议。关闭后仅在你提问时回复。</div>
          <div style="display:flex;gap:8px;justify-content:flex-end;">
            <button class="btn btn-ghost" @click="showNotifConfig = false">取消</button>
            <button class="btn btn-primary" :disabled="notifLoading" @click="updateNotificationConfig(); showNotifConfig = false">保存</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
<style scoped>
.tab-page { flex: 1; overflow-y: auto; max-width: 780px; margin: 0 auto; padding: 20px 24px; }
.stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 20px; }
.stat-card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 16px; text-align: center; }
.stat-value { font-size: 24px; font-weight: 700; }
.stat-label { font-size: 12px; color: var(--muted); margin-top: 4px; }
.review-tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.review-list { display: flex; flex-direction: column; gap: 10px; }
.review-item { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
.review-item.overdue { border-left: 3px solid var(--danger); }
.review-item-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.review-item-title { font-size: 14px; font-weight: 600; }
.review-item-meta { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.review-item-category { font-size: 11px; color: var(--primary); background: var(--primary-light); padding: 2px 8px; border-radius: 4px; }
.review-item-date { font-size: 12px; }
.review-item-action { display: flex; justify-content: flex-end; }
.section-card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 20px; margin-bottom: 16px; }
.section-card h3 { font-size: 15px; font-weight: 600; margin: 0 0 12px; }
.section-card-header { display: flex; align-items: center; justify-content: space-between; }
.section-card-header h3 { margin: 0; }
.analysis-label { font-weight: 600; margin-bottom: 4px; color: var(--text); }
.analysis-section { line-height: 1.6; }
.report-header { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
.bar-chart { display: flex; gap: 8px; align-items: flex-end; min-height: 130px; padding: 10px 0; }
.bar-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; }
.bar { width: 100%; background: var(--primary); border-radius: 4px 4px 0 0; opacity: 0.8; min-height: 4px; }
.bar-label { font-size: 10px; color: var(--muted); }
.progress-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.progress-label { font-size: 13px; min-width: 80px; }
.progress-bar { flex: 1; height: 8px; background: var(--bg); border-radius: 4px; overflow: hidden; }
.progress-fill { height: 100%; border-radius: 4px; transition: width 0.3s; }
.progress-value { font-size: 12px; min-width: 50px; text-align: right; }
.report-actions { display: flex; gap: 8px; margin-bottom: 16px; }
.weak-row { display: flex; align-items: center; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border); font-size: 13px; }
.trend-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.trend-date { font-size: 12px; color: var(--muted); min-width: 60px; }
.trend-value { font-size: 12px; min-width: 45px; text-align: right; }
.empty { text-align: center; padding: 60px 20px; color: var(--muted); font-size: 14px; }
.modal-overlay { position: fixed; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,0.3); display:flex; align-items:center; justify-content:center; z-index:1000; }
.modal-box { background: var(--surface); border-radius: 12px; padding: 24px; min-width: 360px; max-width: 90vw; position:relative; }
.modal-close { position:absolute; top:12px; right:12px; background:none; border:none; font-size:18px; cursor:pointer; color:var(--muted); }
@media (max-width: 600px) { .stats-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
