<script setup lang="ts">
const emit = defineEmits<{ 'select-node': [node: any] }>()
const props = defineProps<{ node: any; depth: number; searchQuery: string; masteredNodes: Set<string> }>()
import { ref, computed } from 'vue'
import { useTreeStore } from '@/stores/tree'
const tree = useTreeStore()
const expanded = ref(false)

const isLeaf = props.node.leaf
const isMastered = computed(() => props.masteredNodes.has(props.node.id))

// 搜索时自动展开所有匹配路径
const isAutoExpanded = computed(() => !!props.searchQuery && !isLeaf)

function toggleExpand() {
  if (!isLeaf) expanded.value = !expanded.value
}

// 搜索时父节点自动展开，让匹配的子节点可见
const isExpandedOrAuto = computed(() => expanded.value || isAutoExpanded.value)
function onSelect() {
  emit('select-node', props.node)
}
</script>
<template>
  <div class="tree-node">
    <div class="tree-node-content" :style="{ paddingLeft: (12 + depth * 16) + 'px' }" @click="toggleExpand()">
      <span v-if="isLeaf" class="arrow" style="font-size:8px;">○</span>
      <span v-else class="arrow" :class="{ expanded }" style="font-size:10px;">▶</span>
      <span class="icon"></span>
      <span class="label" @click.stop="onSelect">{{ node.name }}</span>
      <span v-if="isLeaf" @click.stop="tree.toggleMaster(node.id, !isMastered)" style="font-size:11px;margin-left:auto;cursor:pointer;opacity:0.7;padding:0 4px;">{{ isMastered ? '✅' : '✔' }}</span>
    </div>
    <div v-if="!isLeaf && node.children" class="tree-node-children" :class="{ open: isExpandedOrAuto }">
      <TreeNode v-for="child in node.children" :key="child.id" :node="child" :depth="depth + 1" :search-query="searchQuery" :mastered-nodes="masteredNodes" @select-node="(n: any) => emit('select-node', n)" />
    </div>
  </div>
</template>
