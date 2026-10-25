import { defineStore } from 'pinia'
import { ref } from 'vue'
import { labApi } from '@/api/client'
import type { AssistantConversation, AssistantMessage } from '@/types'

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<AssistantConversation[]>([])
  const currentConvId = ref<string | null>(null)
  const messages = ref<AssistantMessage[]>([])
  const loading = ref(false)

  async function loadConversations() {
    const res = await labApi.get<AssistantConversation[]>(`/assistant/conversations`)
    if (res.code === 200) conversations.value = res.data || []
  }

  async function loadMessages(convId: string) {
    const res = await labApi.get<AssistantMessage[]>(`/assistant/conversations/${convId}/messages`)
    if (res.code === 200) messages.value = res.data || []
  }

  async function createConversation(conversationId: string, firstMessage: string) {
    await labApi.post(`/assistant/conversations`, { conversationId, firstMessage })
    await loadConversations()
  }

  async function saveMessage(conversationId: string, role: string, content: string) {
    await labApi.post(`/assistant/conversations/${conversationId}/messages`, { role, content })
  }

  async function deleteConv(convId: string) {
    await labApi.del(`/assistant/conversations/${convId}`)
    conversations.value = conversations.value.filter(c => c.conversationId !== convId)
    if (currentConvId.value === convId) { currentConvId.value = null; messages.value = [] }
  }

  function startNewConv() {
    currentConvId.value = null; messages.value = []
  }

  return { conversations, currentConvId, messages, loading, loadConversations, loadMessages,
           createConversation, saveMessage, deleteConv, startNewConv }
})
