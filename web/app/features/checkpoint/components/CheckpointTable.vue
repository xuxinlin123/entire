<template>
  <div class="space-y-4">
    <!-- Empty state -->
    <div v-if="isEmpty" class="text-center py-16 text-gray-500 dark:text-gray-400">
      <UIcon name="i-lucide-git-commit" class="w-12 h-12 mx-auto mb-4 opacity-50" />
      <p>暂无检查点</p>
    </div>

    <!-- Table + Pagination -->
    <template v-else>
      <UTable :data="listData" :columns="columns" class="flex-1" />
      <CheckpointDetailSlideover
        ref="detailSlideoverRef"
        v-model:open="detailSlideoverOpen"
        :checkpoint="selectedCheckpoint"
      />

      <div class="flex justify-between items-center pt-4 border-t border-gray-200 dark:border-gray-700">
        <div class="text-sm text-default">共 {{ total }} 条</div>
        <div class="flex items-center gap-3">
          <UPagination
            v-model:page="currentPage"
            :items-per-page="currentSize"
            :total="total"
            :max="7"
          />
          <USelect v-model="currentSize" :items="pageSizeOptions" class="w-36" />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="tsx">
import { UButton, UDropdownMenu } from '#components'
import CheckpointDetailSlideover from './CheckpointDetailSlideover.vue'
import type { DropdownMenuItem } from '@nuxt/ui'
import type { Row } from '@tanstack/vue-table'
import type { CheckpointDTO } from '../types/checkpoint.types'
import type { PagerPayload } from '~/shared/types/api'
import { formatTokenCount } from '~/shared/utils/format'
import { openExternal } from '~/shared/utils/browser'
import { useAgentColors } from '~/features/overview/composables/useAgentColors'

const { getAgentBadgeStyle } = useAgentColors()

const detailSlideoverRef = ref<InstanceType<typeof CheckpointDetailSlideover> | null>(null)
const detailSlideoverOpen = ref(false)
const selectedCheckpoint = ref<CheckpointDTO | null>(null)

function openCheckpointDetail(checkpoint: CheckpointDTO) {
  selectedCheckpoint.value = checkpoint
  detailSlideoverOpen.value = true
}

function formatCommitMessage(msg?: string | null): string {
  if (!msg) return '-'
  return msg.split('\n')[0]
}

function formatCommitTime(ts?: number | null): string {
  if (ts == null) return '-'
  const d = new Date(ts)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const h = String(d.getHours()).padStart(2, '0')
  const min = String(d.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${day} ${h}:${min}`
}

const props = defineProps<{
  listData: CheckpointDTO[]
  listPager: PagerPayload<CheckpointDTO> | null
  pager: { page: number; size: number }
  pageSizeOptions: { label: string; value: number }[]
}>()

const emit = defineEmits<{
  pageChange: [page: number]
  updateSize: [size: number]
}>()

const isEmpty = computed(() => !props.listData?.length)
const total = computed(() => props.listPager?.total ?? 0)

const currentPage = computed({
  get: () => props.listPager?.page ?? 1,
  set: (v) => emit('pageChange', v),
})

const currentSize = computed({
  get: () => props.pager.size,
  set: (v) => emit('updateSize', v),
})

const columns = [
  {
    accessorKey: 'commitMessage',
    header: '提交信息',
    cell: ({ row }: { row: Row<CheckpointDTO> }) => (
      <button
        type="button"
        class="group max-w-[280px] w-full text-left inline-flex items-center gap-1.5 px-1.5 py-0.5 -mx-1.5 -my-0.5 rounded hover:bg-primary/10 transition-colors"
        title="点击查看详情"
        onClick={() => openCheckpointDetail(row.original)}
      >
        <span class="truncate block text-primary hover:underline font-medium">{formatCommitMessage(row.original.commitMessage)}</span>
      </button>
    ),
  },
  {
    accessorKey: 'commitAuthorName',
    header: '作者',
    cell: ({ row }: { row: Row<CheckpointDTO> }) => (
      <div class="text-default">{row.original.commitAuthorName || '-'}</div>
    ),
  },
  {
    accessorKey: 'commitTime',
    header: '提交时间',
    cell: ({ row }: { row: Row<CheckpointDTO> }) => (
      <div class="whitespace-nowrap text-default">{formatCommitTime(row.original.commitTime)}</div>
    ),
  },
  {
    accessorKey: 'repoName',
    header: '仓库',
    cell: ({ row }: { row: Row<CheckpointDTO> }) => (
      <div class="text-default">{row.original.repoName || '-'}</div>
    ),
  },
  {
    accessorKey: 'branch',
    header: '分支',
    cell: ({ row }: { row: Row<CheckpointDTO> }) => (
      <div class="text-default">{row.original.branch || '-'}</div>
    ),
  },
  {
    accessorKey: 'agent',
    header: '智能体',
    cell: ({ row }: { row: Row<CheckpointDTO> }) =>
      row.original.agent ? (
        <UBadge
          size="xs"
          variant="soft"
          ui={{ base: 'border-0', color: { soft: '' } }}
          style={getAgentBadgeStyle(row.original.agent)}
        >
          {row.original.agent}
        </UBadge>
      ) : (
        <span class="text-gray-400">-</span>
      ),
  },
  {
    accessorKey: 'filesTouched',
    header: '文件',
    cell: ({ row }: { row: Row<CheckpointDTO> }) => (
      <div class="text-default">{row.original.filesTouched ?? '-'}</div>
    ),
  },
  {
    accessorKey: 'tokenUsage',
    header: '令牌',
    cell: ({ row }: { row: Row<CheckpointDTO> }) => (
      <div class="text-default">{formatTokenCount(row.original.tokenUsage)}</div>
    ),
  },
  {
    accessorKey: 'additions',
    header: '新增 / 删除',
    cell: ({ row }: { row: Row<CheckpointDTO> }) => (
      <div class="text-default">
        <span class="text-green-600 dark:text-green-500">+{row.original.additions ?? 0}</span>
        <span class="text-gray-400 mx-1">/</span>
        <span class="text-red-600 dark:text-red-500">-{row.original.deletions ?? 0}</span>
      </div>
    ),
  },
  {
    id: 'actions',
    cell: ({ row }: { row: Row<CheckpointDTO> }) => (
      <div class="text-right">
        <UDropdownMenu content={{ align: 'end' }} items={getRowMenuItems(row)}>
          <UButton icon="i-lucide-ellipsis-vertical" color="neutral" variant="ghost" class="ml-auto" />
        </UDropdownMenu>
      </div>
    ),
  },
]

function getRowMenuItems(row: Row<CheckpointDTO>): DropdownMenuItem[] {
  return [
    { type: 'label', label: '操作' },
    {
      label: '查看详情',
      icon: 'i-lucide-eye',
      onSelect: () => openCheckpointDetail(row.original),
    },
    {
      label: '在 Git 仓库中查看',
      icon: 'i-lucide-external-link',
      disabled: !row.original.commitUrl,
      onSelect: () => {
        if (row.original.commitUrl) openExternal(row.original.commitUrl)
      },
    },
  ]
}
</script>
