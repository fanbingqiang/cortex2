<script setup lang="ts">
import { ref, onMounted, inject } from 'vue'
import { labApi } from '@/api/client'
import { SHOW_TOAST } from '@/symbols'
import type { CardDto } from '@/types'

const showToast = inject(SHOW_TOAST) as (msg: string) => void

const cards = ref<CardDto[]>([])
const loading = ref(false)
const expandedId = ref<number | null>(null)

// 自建卡片
const showAddModal = ref(false)
const addForm = ref<{ title: string; knowledgePath: string; answer: string }>({ title: '', knowledgePath: '', answer: '' })
const adding = ref(false)

// AI 提问生成
const aiQuestion = ref('')
const aiGenerating = ref(false)

/** 加载卡片列表 */
async function loadCards() {
  loading.value = true
  try {
    const res = await labApi.get<CardDto[]>('/cards')
    if (res.code === 200) cards.value = res.data || []
  } finally {
    loading.value = false
  }
}

function toggle(id: number) {
  expandedId.value = expandedId.value === id ? null : id
}

/** 用户自建卡片 */
async function addCard() {
  if (!addForm.value.title.trim()) { showToast('请输入卡片题目'); return }
  if (!addForm.value.answer.trim()) { showToast('请输入简易答案'); return }
  adding.value = true
  try {
    const res = await labApi.post<CardDto>('/cards', {
      title: addForm.value.title.trim(),
      knowledgePath: addForm.value.knowledgePath.trim() || undefined,
      detailExplanation: addForm.value.answer.trim(),
    })
    if (res.code === 200 && res.data) {
      cards.value.push(res.data)
      showAddModal.value = false
      addForm.value = { title: '', knowledgePath: '', answer: '' }
      showToast('卡片已添加')
    } else {
      showToast(res.message || '添加失败')
    }
  } finally {
    adding.value = false
  }
}

/** AI 提问生成简易卡片 */
async function generateByAI() {
  const q = aiQuestion.value.trim()
  if (!q) { showToast('请输入你想了解的问题'); return }
  aiGenerating.value = true
  try {
    const res = await labApi.post<CardDto>('/cards/generate', { question: q })
    if (res.code === 200 && res.data) {
      cards.value.push(res.data)
      aiQuestion.value = ''
      showToast('AI 卡片已生成')
    } else {
      showToast(res.message || '生成失败')
    }
  } finally {
    aiGenerating.value = false
  }
}

/** 删除卡片 */
async function deleteCard(c: CardDto) {
  if (!confirm('确定删除这张卡片？')) return
  const res = await labApi.del('/cards/' + c.id)
  if (res.code === 200) {
    cards.value = cards.value.filter(x => x.id !== c.id)
    if (expandedId.value === c.id) expandedId.value = null
    showToast('卡片已删除')
  } else {
    showToast(res.message || '删除失败')
  }
}

onMounted(loadCards)
</script>

<template>
  <div style="flex:1;overflow-y:auto;padding:20px 24px;max-width:960px;margin:0 auto;">
    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px;">
      <h2 style="font-size:18px;font-weight:600;margin:0;">知识卡片</h2>
      <span style="font-size:12px;color:var(--muted);">{{ cards.length }} 张</span>
    </div>

    <!-- AI 提问生成区 -->
    <div style="background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:14px 16px;margin-bottom:16px;">
      <div style="font-size:13px;font-weight:600;margin-bottom:8px;">🤖 AI 提问生成卡片</div>
      <div style="display:flex;gap:8px;">
        <input
          v-model="aiQuestion"
          placeholder="输入问题，AI 用最简单的话回答，如：什么是缓存击穿？"
          style="flex:1;padding:8px 12px;border:1px solid var(--border);border-radius:6px;font-size:13px;outline:none;"
          @keyup.enter="generateByAI"
        />
        <button class="btn btn-primary" @click="generateByAI" :disabled="aiGenerating">
          {{ aiGenerating ? 'AI 生成中...' : '生成卡片' }}
        </button>
      </div>
    </div>

    <!-- 操作按钮 -->
    <div style="margin-bottom:12px;">
      <button class="btn btn-outline btn-sm" @click="showAddModal = true">＋ 添加卡片</button>
    </div>

    <!-- 卡片列表 -->
    <div v-if="loading" style="text-align:center;padding:40px;color:var(--muted);">加载中...</div>
    <div v-else-if="cards.length === 0" style="text-align:center;padding:60px;color:var(--muted);font-size:14px;">
      还没有知识卡片<br>
      <span style="font-size:13px;">用上面的 AI 提问生成一张，或手动添加一张</span>
    </div>
    <div v-else style="display:flex;flex-direction:column;gap:8px;">
      <div v-for="c in cards" :key="c.id" style="background:var(--surface);border:1px solid var(--border);border-radius:10px;overflow:hidden;">
        <div @click="toggle(c.id)" style="padding:14px 16px;cursor:pointer;display:flex;align-items:center;gap:8px;">
          <span style="font-size:16px;transition:transform 0.2s;display:inline-block;" :style="expandedId === c.id ? 'transform:rotate(90deg)' : ''">▶</span>
          <div style="flex:1;min-width:0;">
            <div style="font-size:14px;font-weight:600;">{{ c.title }}</div>
            <div v-if="c.knowledgePath" style="font-size:11px;color:var(--primary);margin-top:2px;">📂 {{ c.knowledgePath }}</div>
            <div v-else-if="c.detailExplanation" style="font-size:12px;color:var(--muted);margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">{{ c.detailExplanation }}</div>
          </div>
        </div>
        <div v-if="expandedId === c.id" style="border-top:1px solid var(--border);padding:16px;">
          <div style="font-size:14px;line-height:1.8;white-space:pre-wrap;color:#333;">{{ c.detailExplanation || '暂无内容' }}</div>
          <div v-if="c.codeSnippet" style="margin-top:12px;">
            <pre style="background:#1e293b;color:#e2e8f0;border-radius:6px;padding:10px;font-size:12px;overflow-x:auto;margin:0;"><code>{{ c.codeSnippet }}</code></pre>
          </div>
          <div style="display:flex;justify-content:flex-end;margin-top:10px;">
            <button class="btn btn-ghost btn-sm" style="color:var(--danger);" @click.stop="deleteCard(c)">删除</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 添加卡片弹窗 -->
    <div v-if="showAddModal" class="modal-overlay" @click.self="showAddModal = false">
      <div class="modal-box">
        <button class="modal-close" @click="showAddModal = false">&times;</button>
        <h3 style="margin:0 0 16px;font-size:16px;">添加知识卡片</h3>
        <div style="display:flex;flex-direction:column;gap:12px;">
          <div style="display:flex;flex-direction:column;gap:4px;">
            <label style="font-size:12px;font-weight:500;color:var(--muted);">卡片题目</label>
            <input v-model="addForm.title" placeholder="如：缓存击穿是什么？" style="padding:8px 10px;border:1px solid var(--border);border-radius:6px;font-size:13px;outline:none;" />
          </div>
          <div style="display:flex;flex-direction:column;gap:4px;">
            <label style="font-size:12px;font-weight:500;color:var(--muted);">知识点路径（可选）</label>
            <input v-model="addForm.knowledgePath" placeholder="如：Redis篇/Redis缓存" style="padding:8px 10px;border:1px solid var(--border);border-radius:6px;font-size:13px;outline:none;" />
          </div>
          <div style="display:flex;flex-direction:column;gap:4px;">
            <label style="font-size:12px;font-weight:500;color:var(--muted);">简易答案</label>
            <textarea v-model="addForm.answer" rows="5" placeholder="用一两句最简单的话写清楚" style="padding:8px 10px;border:1px solid var(--border);border-radius:6px;font-size:13px;resize:vertical;outline:none;"></textarea>
          </div>
          <div style="display:flex;justify-content:flex-end;gap:8px;">
            <button class="btn btn-ghost" @click="showAddModal = false">取消</button>
            <button class="btn btn-primary" :disabled="adding" @click="addCard">{{ adding ? '保存中...' : '保存' }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.modal-box {
  background: #fff;
  border-radius: 12px;
  padding: 20px 24px;
  width: 440px;
  max-width: 92vw;
  position: relative;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.15);
}
.modal-close {
  position: absolute;
  top: 10px;
  right: 12px;
  background: none;
  border: none;
  font-size: 22px;
  color: #999;
  cursor: pointer;
  line-height: 1;
}
</style>
