import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface NoteEntry {
  id: string
  knowledge: string
  content: string
  time: string
}

export const useNotebookStore = defineStore('notebook', () => {
  const notes = ref<NoteEntry[]>([])

  function addNote(knowledge: string, content: string) {
    notes.value.push({
      id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
      knowledge,
      content,
      time: new Date().toLocaleString('zh-CN'),
    })
  }

  function updateNote(id: string, content: string) {
    const note = notes.value.find(n => n.id === id)
    if (note) {
      note.content = content
      note.time = new Date().toLocaleString('zh-CN') + ' (已编辑)'
    }
  }

  function deleteNote(id: string) {
    notes.value = notes.value.filter(n => n.id !== id)
  }

  function clearNotes() {
    notes.value = []
  }

  return { notes, addNote, updateNote, deleteNote, clearNotes }
})