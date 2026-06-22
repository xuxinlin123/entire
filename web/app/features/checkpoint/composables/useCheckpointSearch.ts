/**
 * Composable for checkpoint search with pagination and filter dropdowns
 *
 * Query conditions are synced to URL so they persist across page refresh.
 */
import { checkpointApi } from '../api/checkpoint.api'
import type { CheckpointDTO, RepoOption } from '../types/checkpoint.types'
import type { PagerPayload } from '~/shared/types/api'
import { getDaysAgo } from '~/shared/utils/date'
import { formatDate } from '~/shared/utils/date'

/** Separator for array values in URL (pipe to avoid conflicts with commas in names) */
const ARRAY_SEP = '|'

function getStartOfDay(date: Date): number {
  const d = new Date(date)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

function getEndOfDay(date: Date): number {
  const d = new Date(date)
  d.setHours(23, 59, 59, 999)
  return d.getTime()
}

const DEFAULT_DAYS = 7

function parseRepoIdsFromQuery(val: string | string[] | undefined): number[] {
  if (!val) return []
  const str = Array.isArray(val) ? val[0] : val
  if (!str) return []
  return str.split(',').map((s) => Number(s.trim())).filter((n) => !Number.isNaN(n))
}

function parseCommitAuthorNamesFromQuery(val: string | string[] | undefined): string[] {
  if (!val) return []
  const str = Array.isArray(val) ? val[0] : val
  if (!str) return []
  return str.split(ARRAY_SEP).map((s) => s.trim()).filter(Boolean)
}

export function useCheckpointSearch() {
  const route = useRoute()
  const router = useRouter()

  const today = new Date()
  const defaultStart = getDaysAgo(DEFAULT_DAYS)
  const defaultEnd = today

  // Initialize from URL or defaults
  const startDateStr = ref(
    (route.query.startDateStr as string) || formatDate(defaultStart)
  )
  const endDateStr = ref(
    (route.query.endDateStr as string) || formatDate(defaultEnd)
  )
  const repoIds = ref<number[]>(parseRepoIdsFromQuery(route.query.repoIds))
  const commitAuthorNames = ref<string[]>(
    parseCommitAuthorNamesFromQuery(route.query.commitAuthorNames)
  )
  const commitMessage = ref((route.query.commitMessage as string) || '')

  const pager = reactive({
    page: (() => {
      const p = route.query.page
      const n = p ? Number(p) : 1
      return Number.isNaN(n) || n < 1 ? 1 : n
    })(),
    size: (() => {
      const s = route.query.size
      const n = s ? Number(s) : 20
      return Number.isNaN(n) ? 20 : n
    })(),
  })

  const listData = ref<CheckpointDTO[]>([])
  const listPager = ref<PagerPayload<CheckpointDTO> | null>(null)
  const isLoading = ref(false)
  const repoOptions = ref<RepoOption[]>([])
  const commitAuthorOptions = ref<string[]>([])
  const loadingRepos = ref(false)
  const loadingAuthors = ref(false)

  const startTime = computed(() => {
    const d = new Date(startDateStr.value)
    d.setHours(0, 0, 0, 0)
    return d.getTime()
  })
  const endTime = computed(() => {
    const d = new Date(endDateStr.value)
    d.setHours(23, 59, 59, 999)
    return d.getTime()
  })

  const filterParams = computed(() => ({
    startTime: startTime.value,
    endTime: endTime.value,
    commitMessage: commitMessage.value || undefined,
    repoIds: repoIds.value.length ? repoIds.value : undefined,
    commitAuthorNames: commitAuthorNames.value.length ? commitAuthorNames.value : undefined,
  }))

  async function fetchRepos() {
    loadingRepos.value = true
    try {
      repoOptions.value = await checkpointApi.getRepos({
        startTime: startTime.value,
        endTime: endTime.value,
        commitMessage: commitMessage.value || undefined,
        commitAuthorNames: commitAuthorNames.value.length ? commitAuthorNames.value : undefined,
      })
    } finally {
      loadingRepos.value = false
    }
  }

  async function fetchCommitAuthors() {
    loadingAuthors.value = true
    try {
      commitAuthorOptions.value = await checkpointApi.getCommitAuthors({
        startTime: startTime.value,
        endTime: endTime.value,
        repoIds: repoIds.value.length ? repoIds.value : undefined,
        commitMessage: commitMessage.value || undefined,
      })
    } finally {
      loadingAuthors.value = false
    }
  }

  async function fetchList() {
    isLoading.value = true
    try {
      const res = await checkpointApi.search(
        {
          startTime: startTime.value,
          endTime: endTime.value,
          repoIds: repoIds.value.length ? repoIds.value : undefined,
          commitAuthorNames: commitAuthorNames.value.length ? commitAuthorNames.value : undefined,
          commitMessage: commitMessage.value || undefined,
        },
        { page: pager.page, size: pager.size }
      )
      listData.value = res.data || []
      listPager.value = res
    } catch {
      listData.value = []
      listPager.value = null
    } finally {
      isLoading.value = false
    }
  }

  async function refresh() {
    await Promise.all([fetchRepos(), fetchCommitAuthors(), fetchList()])
  }

  async function onPageChange(page: number) {
    pager.page = page
    await fetchList()
  }

  /** Sync current search conditions and pager to URL query params (client-only) */
  async function syncToUrl() {
    if (!import.meta.client) return
    const query: Record<string, string> = {
      startDateStr: startDateStr.value,
      endDateStr: endDateStr.value,
      page: String(pager.page),
      size: String(pager.size),
    }
    if (commitMessage.value) {
      query.commitMessage = commitMessage.value
    }
    if (repoIds.value.length) {
      query.repoIds = repoIds.value.join(',')
    }
    if (commitAuthorNames.value.length) {
      query.commitAuthorNames = commitAuthorNames.value.join(ARRAY_SEP)
    }
    await router.replace({ query })
  }

  watch(
    [startDateStr, endDateStr],
    () => {
      refresh()
      syncToUrl()
    },
    { immediate: true }
  )

  watch(
    [repoIds, commitAuthorNames, commitMessage],
    () => {
      pager.page = 1
      refresh()
      syncToUrl()
    },
    { deep: true }
  )

  watch(
    () => pager.size,
    () => {
      pager.page = 1
      fetchList()
      syncToUrl()
    }
  )

  watch(
    () => pager.page,
    () => {
      syncToUrl()
    }
  )

  function resetFilters() {
    startDateStr.value = formatDate(getDaysAgo(DEFAULT_DAYS))
    endDateStr.value = formatDate(new Date())
    repoIds.value = []
    commitAuthorNames.value = []
    commitMessage.value = ''
    pager.page = 1
    // refresh and syncToUrl are triggered by watchers
  }

  const pageSizeOptions = [
    { label: '10 条/页', value: 10 },
    { label: '20 条/页', value: 20 },
    { label: '50 条/页', value: 50 },
    { label: '100 条/页', value: 100 },
  ]

  return {
    startDateStr,
    endDateStr,
    repoIds,
    commitAuthorNames,
    commitMessage,
    pager,
    pageSizeOptions,
    listData,
    listPager,
    isLoading,
    repoOptions,
    commitAuthorOptions,
    loadingRepos,
    loadingAuthors,
    refresh,
    onPageChange,
    resetFilters,
  }
}
