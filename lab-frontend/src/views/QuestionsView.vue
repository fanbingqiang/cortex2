<template>
  <div style="display:flex;flex:1;overflow:hidden;">
    <div style="width:340px;min-width:340px;background:var(--surface);border-right:1px solid var(--border);display:flex;flex-direction:column;">
      <div style="padding:14px 16px;border-bottom:1px solid var(--border);display:flex;flex-direction:column;gap:10px;">
        <div style="display:flex;gap:8px;flex-wrap:wrap;">
          <button class="btn btn-primary btn-sm" @click="showGenerate = true">AI 生成</button>
          <button class="btn btn-outline btn-sm" @click="showImport = true">手动导入</button>
          <button class="btn btn-outline btn-sm" @click="showBatchImport = true">批量导入</button>
        </div>
        <div style="display:flex;gap:6px;">
          <input v-model="searchKey" placeholder="搜索题目..." @input="onSearch" style="flex:1;padding:6px 10px;border:1px solid var(--border);border-radius:6px;font-size:13px;outline:none;" />
          <button class="btn btn-sm btn-outline" @click="onSearch">搜索</button>
        </div>
      </div>
      <div class="scroll-y" style="flex:1;">
        <div v-for="q in store.questions" :key="q.id" class="question-card" :class="{ selected: selectedId === q.id }" @click="selectQ(q)" style="margin:6px 10px;">
          <div style="display:flex;align-items:center;gap:8px;">
            <span @click.stop="toggleM(q)" style="cursor:pointer;font-size:16px;">{{ q.mastered ? '✅' : '⬜' }}</span>
            <div style="flex:1;min-width:0;">
              <div style="font-size:13px;font-weight:500;" :style="q.mastered ? 'text-decoration:line-through;color:var(--muted)' : ''">{{ esc(q.title) }}</div>
              <div style="font-size:11px;color:var(--muted);display:flex;align-items:center;gap:4px;">
                <span>{{ q.category || '未知' }}</span>
                <span>{{ '⭐'.repeat(Math.min(q.difficulty||1,5)) }}</span>
                <span v-if="q.status && q.status !== 'ACTIVE'" :class="'q-status ' + statusClass(q.status)">{{ statusLabel(q.status) }}</span>
                <span v-if="q.nextReviewTime"> · 复习:{{ fmtDate(q.nextReviewTime) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div class="detail-panel" style="flex:1;overflow-y:auto;padding:16px;">
      <template v-if="selectedQ">
        <div style="max-width:800px;">
          <div style="display:flex;align-items:start;justify-content:space-between;margin-bottom:12px;">
            <div>
              <h2 style="margin:0;font-size:18px;">{{ esc(selectedQ.title) }}</h2>
              <div style="font-size:12px;color:var(--muted);display:flex;align-items:center;gap:6px;margin-top:4px;">
                <span>{{ selectedQ.category }} · {{ '⭐'.repeat(Math.min(selectedQ.difficulty||1,5)) }}</span>
                <span v-if="selectedQ.status && selectedQ.status !== 'ACTIVE'" :class="'q-status ' + statusClass(selectedQ.status)">{{ statusLabel(selectedQ.status) }}</span>
              </div>
            </div>
            <div style="display:flex;gap:4px;flex-shrink:0;">
              <button v-if="selectedQ.status === 'PENDING'" class="btn btn-sm btn-success" @click="approveQ(selectedQ.id)">审核通过</button>
              <button v-if="selectedQ.status === 'PENDING'" class="btn btn-sm btn-danger" @click="rejectQ(selectedQ.id)">审核不通过</button>
              <button class="btn btn-sm btn-ghost" style="color:var(--danger);" :disabled="deleting" @click="deleteQ(selectedQ.id)">删除</button>
            </div>
          </div>
          <div v-if="selectedQ.description" style="background:var(--bg);border-radius:8px;padding:12px;margin-bottom:12px;font-size:13px;">{{ esc(selectedQ.description) }}</div>
          <div v-if="selectedQ.trapCode" style="margin-bottom:12px;">
            <div style="font-size:12px;font-weight:600;margin-bottom:4px;">陷阱代码：</div>
            <pre style="background:#1e293b;color:#e2e8f0;border-radius:8px;padding:14px;font-size:12px;overflow-x:auto;white-space:pre-wrap;word-break:break-all;"><code>{{ selectedQ.trapCode }}</code></pre>
            <button class="btn btn-sm btn-primary" @click="practiceWithCode(selectedQ)" style="margin-top:8px;">▶ 去实战练习</button>
          </div>
          <div v-if="selectedQ.expectedPitfall" style="background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:12px;margin-bottom:12px;">
            <div style="font-size:12px;font-weight:600;color:var(--danger);margin-bottom:4px;">预期陷阱</div>
            <div style="font-size:13px;">{{ esc(selectedQ.expectedPitfall) }}</div>
          </div>
          <div v-if="selectedQ.correctExplanation" style="background:#f0fdf4;border:1px solid #bbf7d0;border-radius:8px;padding:12px;margin-bottom:12px;">
            <div style="font-size:12px;font-weight:600;color:var(--success);margin-bottom:4px;">✅ 正确解释</div>
            <div style="font-size:13px;">{{ esc(selectedQ.correctExplanation) }}</div>
          </div>

          <!-- ====== AI 导师自评 ====== -->
          <div v-if="selectedQ" class="tutor-section" style="margin-bottom:12px;">
            <!-- 初始状态：答对/答错按钮 -->
            <div v-if="!tutorResult" style="display:flex;align-items:center;gap:8px;padding:4px 0;">
              <span style="font-size:13px;color:#666;">自评：</span>
              <button class="btn btn-sm btn-outline" @click="selfAssess(true)" style="font-size:12px;padding:4px 12px;">✅ 答对了</button>
              <button class="btn btn-sm btn-outline" @click="selfAssess(false)" style="font-size:12px;padding:4px 12px;">❌ 没答对</button>
            </div>

            <!-- 点评结果 -->
            <div v-if="tutorResult" style="border:1px solid #e5e7eb;border-radius:8px;padding:10px 12px;">
              <!-- 反馈文字 -->
              <div style="font-size:13px;font-weight:500;margin-bottom:6px;">{{ tutorResult.feedback }}</div>

              <!-- 答错了→引导例子 -->
              <div v-if="tutorResult.examples?.length" style="margin-bottom:8px;">
                <div v-for="(ex, i) in tutorResult.examples" :key="'ex-'+i" style="font-size:12px;color:#666;padding:2px 0;line-height:1.6;">
                  {{ ex }}
                </div>
              </div>

              <!-- 答对了→拓展知识点 -->
              <div v-if="tutorResult.tips?.length" style="margin-top:4px;">
                <div v-for="(tip, i) in tutorResult.tips" :key="'tip-'+i" style="margin-bottom:2px;">
                  <span
                    class="tutor-tip-link"
                    @click="explainTip(tip)"
                  >▸ {{ tip }}</span>
                  <!-- 展开解释 -->
                  <div v-if="expandingTip === tip && tipLoading" style="font-size:12px;color:#999;padding:4px 0 4px 16px;">加载中...</div>
                  <div v-else-if="expandingTip === tip && tipExplanation" style="font-size:13px;color:#333;padding:8px 12px;margin:4px 0 4px 12px;background:#f8f9fa;border-radius:6px;line-height:1.7;white-space:pre-wrap;word-break:break-all;">{{ tipExplanation }}</div>
                </div>
              </div>

              <!-- 答错后的操作区 -->
              <div v-if="tutorWrong" style="display:flex;gap:8px;margin-top:8px;padding-top:6px;border-top:1px solid #f0f0f0;">
                <button class="btn btn-sm btn-outline" @click="selfAssess(true)" style="font-size:12px;padding:3px 10px;">我改对了 ✅</button>
                <button class="btn btn-sm btn-ghost" @click="resetAssess" style="font-size:12px;padding:3px 10px;">重评</button>
              </div>

              <!-- 重新评价 -->
              <div v-if="!tutorWrong" style="margin-top:6px;">
                <span @click="resetAssess" style="font-size:11px;color:#aaa;cursor:pointer;">重新评价</span>
              </div>
            </div>
          </div>

          <div v-if="selectedQ.hints" style="margin-bottom:12px;">
            <div style="font-size:12px;font-weight:600;margin-bottom:4px;">提示</div>
            <ul style="margin:0;padding-left:20px;font-size:13px;">
              <li v-for="(h, i) in parseHints(selectedQ.hints)" :key="i" style="margin-bottom:4px;">{{ esc(h) }}</li>
            </ul>
          </div>

          <!-- 选择题选项 -->
          <QuizPanel
            v-if="selectedQ && selectedQ.id"
            :question-id="selectedQ.id"
            :user-id="auth.userId || 'anonymous'"
          />

          <!-- Card Area -->
          <div v-if="card" style="background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:16px;margin-bottom:12px;">
            <div style="font-size:14px;font-weight:600;margin-bottom:8px;">知识卡片</div>
            <div v-if="card.title" style="font-size:15px;font-weight:600;margin-bottom:8px;">{{ esc(card.title) }}</div>
            <div v-if="card.keyPoints" style="margin-bottom:8px;">
              <div style="font-size:12px;font-weight:600;color:var(--muted);margin-bottom:4px;">关键点</div>
              <div style="font-size:13px;white-space:pre-wrap;">{{ esc(card.keyPoints) }}</div>
            </div>
            <div v-if="card.detailExplanation" style="margin-bottom:8px;">
              <div style="font-size:12px;font-weight:600;color:var(--muted);margin-bottom:4px;">详细解释</div>
              <div style="font-size:13px;white-space:pre-wrap;">{{ esc(card.detailExplanation) }}</div>
            </div>
            <div v-if="card.codeSnippet" style="margin-bottom:8px;">
              <div style="font-size:12px;font-weight:600;color:var(--muted);margin-bottom:4px;">代码示例</div>
              <pre style="background:#1e293b;color:#e2e8f0;border-radius:6px;padding:10px;font-size:12px;overflow-x:auto;"><code>{{ card.codeSnippet }}</code></pre>
            </div>
            <div v-if="card.commonPitfalls">
              <div style="font-size:12px;font-weight:600;color:var(--muted);margin-bottom:4px;">常见陷阱</div>
              <div style="font-size:13px;white-space:pre-wrap;">{{ esc(card.commonPitfalls) }}</div>
            </div>
          </div>
          <button v-else class="btn btn-outline btn-sm" @click="genCard" style="margin-bottom:12px;">生成知识卡片</button>
          <!-- Discussions -->
          <div style="margin-top:16px;border-top:1px solid var(--border);padding-top:12px;">
            <div style="font-size:13px;font-weight:600;margin-bottom:8px;">讨论 ({{ discussions.length }})</div>
            <div style="display:flex;gap:6px;margin-bottom:10px;">
              <input v-model="discussInput" placeholder="添加评论..." @keydown.enter="addDiscuss" style="flex:1;padding:6px 10px;border:1px solid var(--border);border-radius:6px;font-size:12px;outline:none;" />
              <button class="btn btn-sm btn-primary" @click="addDiscuss">发送</button>
            </div>
            <div v-for="d in discussions" :key="d.id" class="comment-bubble" style="display:flex;justify-content:space-between;align-items:start;">
              <div>
                <span style="font-size:11px;color:var(--muted);">{{ d.userId === auth.userId ? auth.username || '我' : d.userId.slice(0, 4) + '...' }}</span>
                <div style="font-size:13px;margin-top:2px;">{{ esc(d.content) }}</div>
              </div>
              <button v-if="d.userId === auth.userId" class="btn btn-ghost btn-sm" @click="delDiscuss(d.id)" style="font-size:11px;padding:2px 6px;">✕</button>
            </div>
          </div>
        </div>
      </template>
      <div v-else style="display:flex;align-items:center;justify-content:center;height:100%;color:var(--muted);">从左侧选择一道题目开始学习</div>
    </div>
    <GenerateModal v-if="showGenerate" @close="showGenerate=false;loadQ()" />
    <ImportModal v-if="showImport" @close="showImport=false;loadQ()" />
    <BatchImportModal v-if="showBatchImport" @close="showBatchImport=false" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useQuestionsStore } from '@/stores/questions'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import { labApi } from '@/api/client'
import type { QuestionDto, CardDto, DiscussionDto } from '@/types'
import GenerateModal from '@/components/GenerateModal.vue'
import ImportModal from '@/components/ImportModal.vue'
import BatchImportModal from '@/components/BatchImportModal.vue'
import QuizPanel from '@/components/QuizPanel.vue'

const store = useQuestionsStore()
const auth = useAuthStore()
const router = useRouter()

// AI 导师自评状态
const tutorResult = ref<any>(null)       // 点评结果
const tutorWrong = ref(false)            // 当前是否答错状态
const expandingTip = ref<string | null>(null)  // 正在展开的知识点
const tipExplanation = ref('')           // 知识点解释
const tipLoading = ref(false)            // 解释加载中
const assessLoading = ref(false)         // 自评加载中
function practiceWithCode(q: QuestionDto) {
  store.selectedQuestion = q
  router.push('/practice')
}
const selectedId = ref<number | null>(null)
const selectedQ = ref<QuestionDto | null>(null)
const card = ref<CardDto | null>(null)
const discussions = ref<DiscussionDto[]>([])
const searchKey = ref('')
const showGenerate = ref(false)
const showImport = ref(false)
const showBatchImport = ref(false)
const discussInput = ref('')
const deleting = ref(false)

/** 审核通过 */
async function approveQ(id: number) {
  const res = await labApi.post('/questions/' + id + '/approve')
  if (res.code === 200 && selectedQ.value) {
    selectedQ.value.status = 'ACTIVE'
    const found = store.questions.find(q => q.id === id)
    if (found) found.status = 'ACTIVE'
  }
}

/** 审核拒绝 */
async function rejectQ(id: number) {
  if (!confirm('确定拒绝该题目？')) return
  const res = await labApi.post('/questions/' + id + '/reject')
  if (res.code === 200 && selectedQ.value) {
    selectedQ.value.status = 'REJECTED'
    const found = store.questions.find(q => q.id === id)
    if (found) found.status = 'REJECTED'
  }
}

/** 删除题目 */
async function deleteQ(id: number) {
  if (!confirm('确定删除该题目？')) return
  deleting.value = true
  try {
    await labApi.del('/questions/' + id)
    store.questions = store.questions.filter(q => q.id !== id)
    selectedQ.value = null
    selectedId.value = null
  } finally {
    deleting.value = false
  }
}

function statusLabel(s: string): string {
  const map: Record<string, string> = { PENDING: '待审核', ACTIVE: '已通过', REJECTED: '已拒绝' }
  return map[s] || s
}
function statusClass(s: string): string {
  const map: Record<string, string> = { PENDING: 'badge-warn', ACTIVE: 'badge-ok', REJECTED: 'badge-err' }
  return map[s] || ''
}

function loadQ() { store.loadQuestions(auth.userId) }
onMounted(loadQ)

async function selectQ(q: QuestionDto) {
  selectedId.value = q.id
  const res = await store.getQuestion(q.id, auth.userId)
  selectedQ.value = res ?? null
  card.value = null
  discussions.value = []
  resetAssess()  // 重置自评
  // load card
  const cardRes = await labApi.get<CardDto>('/questions/' + q.id + '/card')
  if (cardRes.code === 200 && cardRes.data) card.value = cardRes.data
  // load discussions
  const discRes = await labApi.get<DiscussionDto[]>('/questions/' + q.id + '/discussions')
  if (discRes.code === 200 && discRes.data) discussions.value = discRes.data
}
async function toggleM(q: QuestionDto) {
  await store.toggleMastered(q.id, !q.mastered)
  q.mastered = !q.mastered
}
async function genCard() {
  if (!selectedQ.value) return
  const res = await labApi.post<CardDto>('/questions/' + selectedQ.value.id + '/card/generate')
  if (res.code === 200 && res.data) card.value = res.data
}
async function addDiscuss() {
  if (!discussInput.value.trim() || !selectedQ.value) return
  const res = await labApi.post('/questions/' + selectedQ.value.id + '/discussions', { content: discussInput.value, userId: auth.userId })
  if (res.code === 200) { discussInput.value = ''; selectQ(selectedQ.value) }
}
async function delDiscuss(id: number) {
  await labApi.del('/discussions/' + id)
  if (selectedQ.value) selectQ(selectedQ.value)
}
// AI 自评：答对/答错
async function selfAssess(correct: boolean) {
  if (!selectedQ.value || assessLoading.value) return
  assessLoading.value = true
  try {
    const res = await labApi.post<any>('/tutor/assess', {
      questionId: selectedQ.value.id,
      userId: auth.userId || 'anonymous',
      correct,
    })
    if (res.code === 200 && res.data) {
      tutorResult.value = res.data
      tutorWrong.value = !correct
      expandingTip.value = null
      tipExplanation.value = ''
    }
  } catch (e) {
    console.error('自评失败:', e)
  } finally {
    assessLoading.value = false
  }
}

// 查看拓展知识点的详细解释
async function explainTip(tip: string) {
  if (tipLoading.value) return
  if (expandingTip.value === tip && tipExplanation.value) {
    expandingTip.value = null
    return
  }
  if (!selectedQ.value) return
  expandingTip.value = tip
  tipLoading.value = true
  tipExplanation.value = ''
  try {
    const res = await labApi.post<any>('/tutor/explain-tip', {
      questionId: selectedQ.value.id,
      tip,
      userId: auth.userId || 'anonymous',
    })
    if (res.code === 200 && res.data) {
      tipExplanation.value = res.data.explanation || '暂无解释'
    }
  } catch (e) {
    tipExplanation.value = '获取解释失败，请稍后再试'
  } finally {
    tipLoading.value = false
  }
}

// 重置自评
function resetAssess() {
  tutorResult.value = null
  tutorWrong.value = false
  expandingTip.value = null
  tipExplanation.value = ''
}

function onSearch() {
  if (searchKey.value.trim()) store.searchQuestions(searchKey.value)
  else store.loadQuestions(auth.userId)
}
function parseHints(h: string): string[] {
  try { const p = JSON.parse(h); return Array.isArray(p) ? p : [] } catch { return [] }
}
function esc(s: any) { return !s ? '' : String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;') }
function fmtDate(d: string) { return d ? new Date(d).toLocaleDateString() : '' }
</script>

<style scoped>
.tutor-tip-link {
  font-size: 12px;
  color: #888;
  cursor: pointer;
  line-height: 1.8;
  border-bottom: 1px dashed #ddd;
  transition: color 0.15s;
}
.tutor-tip-link:hover {
  color: #333;
}
.q-status {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 500;
}
.q-status.badge-warn {
  background: #fef3c7;
  color: #92400e;
}
.q-status.badge-ok {
  background: #d1fae5;
  color: #065f46;
}
.q-status.badge-err {
  background: #fee2e2;
  color: #991b1b;
}
</style>
