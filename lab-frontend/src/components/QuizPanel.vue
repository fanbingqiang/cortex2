<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { labApi } from '@/api/client'

const props = defineProps<{
  questionId: number
  userId: string
}>()

const options = ref<any[]>([])
const guidance = ref('')
const stage = ref('QUIZ')
const resolved = ref(false)
const loading = ref(false)
const selectedLabel = ref('')

onMounted(async () => {
  await loadOptions()
})

async function loadOptions() {
  loading.value = true
  try {
    const res = await labApi.get<any[]>('/questions/' + props.questionId + '/options')
    if (res.code === 200 && res.data) {
      options.value = res.data
    }
  } finally {
    loading.value = false
  }
}

async function selectOption(label: string) {
  if (resolved.value) return
  selectedLabel.value = label
  loading.value = true
  try {
    const res = await labApi.post<any>('/questions/' + props.questionId + '/answer', {
      userId: props.userId,
      optionLabel: label
    })
    if (res.code === 200 && res.data) {
      guidance.value = res.data.guidance || ''
      stage.value = res.data.stage || 'QUIZ'
      resolved.value = res.data.resolved || false
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="quiz-panel" v-if="options.length > 0">
    <div class="quiz-label">选择题</div>
    <div class="option-list">
      <div
        v-for="opt in options"
        :key="opt.optionLabel"
        class="option-item"
        :class="{
          'selected': selectedLabel === opt.optionLabel,
          'correct': resolved && opt.isCorrect,
          'wrong': resolved && selectedLabel === opt.optionLabel && !opt.isCorrect,
          'disabled': resolved
        }"
        @click="selectOption(opt.optionLabel)"
      >
        <span class="option-label">{{ opt.optionLabel }}</span>
        <span class="option-text">{{ opt.optionText }}</span>
      </div>
    </div>

    <div v-if="loading" class="guidance-text thinking">提交中...</div>
    <div v-else-if="guidance" class="guidance-text" :class="{ 'correct-text': resolved }">
      {{ guidance }}
    </div>
  </div>
</template>

<style scoped>
.quiz-panel {
  margin: 12px 0;
  padding: 12px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 8px;
}
.quiz-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 8px;
}
.option-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.option-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
  font-size: 13px;
}
.option-item:hover:not(.disabled) {
  border-color: var(--primary);
  background: #f0f5ff;
}
.option-item.selected {
  border-color: var(--primary);
  background: #e8f0fe;
}
.option-item.correct {
  border-color: #22c55e;
  background: #f0fdf4;
}
.option-item.wrong {
  border-color: #ef4444;
  background: #fef2f2;
}
.option-item.disabled {
  cursor: default;
  opacity: 0.8;
}
.option-label {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--surface);
  border: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}
.option-text {
  line-height: 1.5;
  padding-top: 2px;
}
.guidance-text {
  margin-top: 10px;
  padding: 8px 10px;
  background: #f8f9fa;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  border-left: 3px solid var(--primary);
}
.guidance-text.correct-text {
  border-left-color: #22c55e;
  background: #f0fdf4;
}
.guidance-text.thinking {
  color: var(--muted);
  font-style: italic;
}
</style>
