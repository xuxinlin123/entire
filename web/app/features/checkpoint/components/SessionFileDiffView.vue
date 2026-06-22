<script setup lang="ts">
import { createUnifiedDiff } from '../utils/format-diff'
import { stripProjectRoot } from '../utils/transcript-parser'
import type { FileDiff, ParsedTranscript, ToolUse } from '../utils/transcript-parser'

const props = defineProps<{
  parsed: ParsedTranscript
  file: string
}>()

// Mirror backend FilePathExtractor key order so a path on the tool's input is recognized.
const FILE_PATH_KEYS = [
  'file_path',
  'path',
  'file',
  'target_file',
  'target_directory',
  'notebook_path',
] as const

const EDIT_TOOLS = new Set([
  'Edit',
  'MultiEdit',
  'NotebookEdit',
  'str_replace',
  'search_replace',
  'replace',
])
const WRITE_TOOLS = new Set(['Write', 'Create', 'create_file', 'apply_patch'])

type BlockKind = 'del' | 'add' | 'json'
interface Block {
  kind: BlockKind
  text: string
}
const BLOCK_CLASS: Record<BlockKind, string> = {
  del: 'bg-red-500/10 text-red-600 dark:text-red-400',
  add: 'bg-green-500/10 text-green-600 dark:text-green-400',
  json: 'text-gray-500',
}

function getInputString(input: unknown, key: string): string {
  if (!input || typeof input !== 'object') return ''
  const v = (input as Record<string, unknown>)[key]
  return typeof v === 'string' ? v : ''
}

function getToolFilePath(t: ToolUse): string | null {
  const input = t.input
  if (!input || typeof input !== 'object') return null
  const obj = input as Record<string, unknown>
  for (const k of FILE_PATH_KEYS) {
    const v = obj[k]
    if (typeof v === 'string' && v.length > 0) return v
  }
  return null
}

// Prefix every line so multi-line old_string/new_string content renders as a real diff.
function prefixLines(text: string, prefix: string): string {
  if (!text) return ''
  return text.split('\n').map((l) => prefix + l).join('\n')
}

// Tree path is the raw path with the project root (OpenCode) or shared dir prefix
// (Claude Code) stripped. Match via projectRoot first, then equality / endsWith fallback.
function matchesTreePath(rawPath: string, treePath: string, projectRoot?: string): boolean {
  if (stripProjectRoot(rawPath, projectRoot) === treePath) return true
  if (rawPath === treePath) return true
  if (rawPath.endsWith('/' + treePath)) return true
  return false
}

function blocksFor(t: ToolUse): Block[] {
  if (t.tool && EDIT_TOOLS.has(t.tool)) {
    const out: Block[] = []
    const oldText = prefixLines(getInputString(t.input, 'old_string'), '- ')
    const newText = prefixLines(getInputString(t.input, 'new_string'), '+ ')
    if (oldText) out.push({ kind: 'del', text: oldText })
    if (newText) out.push({ kind: 'add', text: newText })
    if (out.length) return out
  }
  if (t.tool && WRITE_TOOLS.has(t.tool)) {
    const content = prefixLines(getInputString(t.input, 'content'), '+ ')
    if (content) return [{ kind: 'add', text: content }]
  }
  return [{ kind: 'json', text: JSON.stringify(t.input, null, 2) }]
}

interface FragmentView {
  tool: ToolUse
  messageId: string
  blocks: Block[]
}

interface FileView {
  diff: FileDiff | null
  fragments: FragmentView[]
}

const fileView = computed<FileView>(() => {
  const messages = props.parsed.messages ?? []

  // The unified diff only exists for transcripts that populate per-message diffs
  // (e.g. OpenCode). Take the last such message so multi-edit files show the final
  // state. findLast avoids building an intermediate array of every match.
  const lastMsg = messages.findLast((m) => m.diffs?.some((d) => d.file === props.file))
  const diff = lastMsg?.diffs?.find((d) => d.file === props.file) ?? null

  const fragments: FragmentView[] = []
  for (const m of messages) {
    for (const t of m.tools ?? []) {
      const p = getToolFilePath(t)
      if (p && matchesTreePath(p, props.file, props.parsed.projectRoot)) {
        fragments.push({ tool: t, messageId: m.id, blocks: blocksFor(t) })
      }
    }
  }

  return { diff, fragments }
})

const diffLines = computed(() => {
  const d = fileView.value.diff
  if (!d) return []
  return createUnifiedDiff(props.file, d.before ?? '', d.after ?? '')
    .split(/\r?\n/)
    .map((line) => ({
      type:
        line.startsWith('-') && !line.startsWith('---')
          ? 'del'
          : line.startsWith('+') && !line.startsWith('+++')
            ? 'add'
            : 'ctx',
      text: line,
    }))
})
</script>

<template>
  <div class="font-mono text-sm">
    <div class="text-xs text-gray-500 mb-2 truncate">{{ file }}</div>

    <div
      v-if="diffLines.length"
      class="rounded border border-gray-200 dark:border-gray-700 overflow-hidden"
    >
      <div
        v-for="(line, i) in diffLines"
        :key="i"
        class="px-3 py-0.5 whitespace-pre"
        :class="{
          'bg-red-500/10': line.type === 'del',
          'bg-green-500/10': line.type === 'add',
          'text-red-600 dark:text-red-400': line.type === 'del',
          'text-green-600 dark:text-green-400': line.type === 'add',
        }"
      >
        {{ line.text }}
      </div>
    </div>

    <div v-else>
      <div class="text-xs text-gray-500 mb-3">
        当前智能体没有提供逐消息差异，下面展示触碰此文件的工具调用。
      </div>
      <div v-if="fileView.fragments.length" class="space-y-2">
        <div
          v-for="(f, i) in fileView.fragments"
          :key="`${f.messageId}-${f.tool.callID || i}`"
          class="rounded border border-gray-200 dark:border-gray-700 overflow-hidden"
        >
          <div
            class="px-3 py-1 text-xs text-gray-600 dark:text-gray-400 bg-gray-50 dark:bg-gray-800/50 border-b border-gray-200 dark:border-gray-700"
          >
            {{ f.tool.tool }}{{ f.tool.title ? `("${f.tool.title}")` : '' }}
          </div>
          <template v-for="(b, bi) in f.blocks" :key="bi">
            <pre
              v-if="b.kind === 'json'"
              class="px-3 py-1 text-xs overflow-auto max-h-48 whitespace-pre-wrap break-words"
              :class="BLOCK_CLASS.json"
            >{{ b.text }}</pre>
            <div
              v-else
              class="px-3 py-0.5 whitespace-pre"
              :class="[BLOCK_CLASS[b.kind], bi < f.blocks.length - 1 && 'border-b border-gray-200 dark:border-gray-700']"
            >
              {{ b.text }}
            </div>
          </template>
        </div>
      </div>
      <div v-else class="text-sm text-gray-500">
        转录内容中没有找到触碰此文件的工具调用。
      </div>
    </div>
  </div>
</template>
