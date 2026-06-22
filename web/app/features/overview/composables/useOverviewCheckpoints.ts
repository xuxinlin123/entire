/**
 * Composable for fetching overview checkpoints (Contribution chart + list)
 * Shares date range with Overview - resets to page 1 when date range changes
 *
 * Strategy: Initial load uses getCheckpoints (chart + list). Pagination uses
 * getCheckpointsList only, so StatsCard and ContributionChart stay unchanged.
 */
import { toValue, type MaybeRefOrGetter } from 'vue'
import { overviewApi } from '../api/overview.api'
import type { OverviewCheckpointsParams } from '~/shared/types/stats'
import type { PagerPayload } from '~/shared/types/api'
import type { OverviewCheckpointListItem } from '~/shared/types/stats'

const PAGE_SIZE_OPTIONS = [
  { label: '10 条/页', value: 10 },
  { label: '20 条/页', value: 20 },
  { label: '50 条/页', value: 50 },
  { label: '100 条/页', value: 100 },
]

export function useOverviewCheckpoints(
  queryParams: MaybeRefOrGetter<{ startTime: number; endTime: number }>
) {
  const page = ref(0) // 0-based for API
  const pageSize = ref(20)

  const chartData = ref<Awaited<ReturnType<typeof overviewApi.getCheckpoints>>['chartData']>([])
  const chartDataTruncated = ref(false)
  const agentStats = ref<Awaited<ReturnType<typeof overviewApi.getCheckpoints>>['agentStats']>([])
  const listPager = ref<PagerPayload<OverviewCheckpointListItem> | null>(null)

  const isLoading = ref(false) // full load (chart + list)
  const listLoading = ref(false) // list-only (pagination)
  const fetchError = ref<Error | null>(null)

  async function fetchFullCheckpoints() {
    const params = toValue(queryParams)
    if (!params?.startTime || !params?.endTime) {
      chartData.value = []
      chartDataTruncated.value = false
      agentStats.value = []
      listPager.value = null
      return
    }
    isLoading.value = true
    fetchError.value = null
    try {
      const apiParams: OverviewCheckpointsParams = {
        startTime: params.startTime,
        endTime: params.endTime,
        page: 0,
        size: pageSize.value,
      }
      const res = await overviewApi.getCheckpoints(apiParams)
      chartData.value = res.chartData ?? []
      chartDataTruncated.value = res.chartDataTruncated ?? false
      agentStats.value = res.agentStats ?? []
      listPager.value = res.list ?? null
    } catch (e) {
      fetchError.value = e as Error
    } finally {
      isLoading.value = false
    }
  }

  async function fetchListOnly() {
    const params = toValue(queryParams)
    if (!params?.startTime || !params?.endTime) return
    listLoading.value = true
    fetchError.value = null
    try {
      const apiParams: OverviewCheckpointsParams = {
        startTime: params.startTime,
        endTime: params.endTime,
        page: page.value,
        size: pageSize.value,
      }
      listPager.value = await overviewApi.getCheckpointsList(apiParams)
    } catch (e) {
      fetchError.value = e as Error
    } finally {
      listLoading.value = false
    }
  }

  watch(
    () => toValue(queryParams),
    (val) => {
      if (val?.startTime && val?.endTime) {
        page.value = 0
        fetchFullCheckpoints()
      } else {
        chartData.value = []
        chartDataTruncated.value = false
        agentStats.value = []
        listPager.value = null
      }
    },
    { immediate: true, deep: true }
  )

  watch(page, () => {
    const params = toValue(queryParams)
    if (!params?.startTime || !params?.endTime) return
    // Always use fetchListOnly for pagination (including back to page 1) to avoid
    // replacing chart+list with loading and resetting scroll position
    fetchListOnly()
  })

  watch(pageSize, () => {
    const params = toValue(queryParams)
    if (!params?.startTime || !params?.endTime) return
    page.value = 0
    fetchFullCheckpoints()
  })

  /** @param page1Based - 1-based page number (for UI) */
  function goToPage(page1Based: number) {
    page.value = Math.max(0, page1Based - 1)
  }

  function goToSize(size: number) {
    pageSize.value = size
  }

  function refresh() {
    if (page.value === 0) {
      fetchFullCheckpoints()
    } else {
      fetchListOnly()
    }
  }

  return {
    chartData: computed(() => chartData.value ?? []),
    chartDataTruncated: computed(() => chartDataTruncated.value ?? false),
    listData: computed(() => listPager.value?.data ?? []),
    listPager: computed(() => listPager.value),
    listTotal: computed(() => listPager.value?.total ?? 0),
    listPage: computed(() => listPager.value?.page ?? 1),
    listSize: computed(() => listPager.value?.size ?? pageSize.value),
    agentStats: computed(() => agentStats.value ?? []),
    pending: isLoading,
    listPending: listLoading,
    error: fetchError,
    refresh,
    page,
    goToPage,
    pageSize,
    pageSizeOptions: PAGE_SIZE_OPTIONS,
    goToSize,
  }
}
