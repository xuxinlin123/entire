<template>
  <UDashboardPanel id="dashboard">
    <template #header>
      <UDashboardNavbar title="概览">
        <template #leading>
          <UDashboardSidebarCollapse />
        </template>
      </UDashboardNavbar>
    </template>
    <template #body>
      <div class="space-y-6 overflow-y-auto h-full p-4">
        <!-- Date range selector -->
        <div class="flex items-center justify-end gap-2 flex-wrap">
          <!-- Quick options -->
          <UButton
            v-for="option in DATE_RANGE_OPTIONS"
            :key="option.value"
            :variant="dateRangeType === option.value ? 'solid' : 'outline'"
            :color="dateRangeType === option.value ? 'primary' : 'neutral'"
            size="sm"
            @click="selectDateRange(option.value)"
          >
            {{ option.label }}
          </UButton>

          <!-- Custom date range - only shown when 'custom' is selected -->
          <template v-if="dateRangeType === 'custom'">
            <UInput
              v-model="customStartDate"
              type="date"
              size="sm"
              :max="customEndDate || formatDate(new Date())"
              placeholder="开始日期"
            />
            <span class="text-gray-500">-</span>
            <UInput
              v-model="customEndDate"
              type="date"
              size="sm"
              :min="customStartDate"
              :max="formatDate(new Date())"
              placeholder="结束日期"
            />
          </template>
        </div>

        <div v-if="pending" class="flex items-center justify-center py-16">
          <UIcon name="i-lucide-loader-2" class="w-10 h-10 animate-spin text-primary" />
        </div>

        <!-- Error state -->
        <UAlert
          v-else-if="error"
          color="error"
          variant="soft"
          :title="error.message || '统计数据加载失败'"
          icon="i-lucide-alert-circle"
        />

        <!-- Stats cards -->
        <div v-else-if="stats" class="space-y-6">
          <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            <StatsCard
              title="最近活跃仓库"
              :value="stats.activeProjectCount"
              description="所选时间范围内产生检查点的仓库"
              icon="i-lucide-folder-git"
              icon-color="text-blue-600 dark:text-blue-500"
            />
            <StatsCard
              title="贡献者"
              :value="stats.submitterCount"
              description="所选时间范围内的唯一贡献者"
              icon="i-lucide-users"
              icon-color="text-green-600 dark:text-green-500"
            />
            <StatsCard
              title="检查点"
              :value="stats.checkpointCount"
              description="所选时间范围内的检查点总数"
              icon="i-lucide-git-commit"
              icon-color="text-amber-600 dark:text-amber-500"
            />
            <StatsCard
              title="令牌总用量"
              :value="stats.totalTokenUsage"
              description="所选时间范围内消耗的令牌总数"
              icon="i-lucide-coins"
              icon-color="text-purple-600 dark:text-purple-500"
              :formatter="formatTokenCount"
            />
          </div>

          <!-- Contribution chart -->
          <div v-if="queryParams?.startTime && queryParams?.endTime">
            <div v-if="checkpointsPending" class="flex justify-center py-8">
              <UIcon name="i-lucide-loader-2" class="w-8 h-8 animate-spin text-primary" />
            </div>
            <template v-else>
              <ContributionChart
                :chart-data="chartData"
                :start-time="queryParams?.startTime"
                :end-time="queryParams?.endTime"
                :agent-stats="agentStats"
                :chart-data-truncated="chartDataTruncated"
                :checkpoint-count="stats.checkpointCount"
              />
            </template>
          </div>

          <!-- Checkpoints list: full load replaces content; pagination uses overlay to preserve scroll -->
          <div
            v-if="queryParams?.startTime && queryParams?.endTime"
            class="relative"
          >
            <div
              v-if="checkpointsPending"
              class="flex justify-center py-8"
            >
              <UIcon name="i-lucide-loader-2" class="w-8 h-8 animate-spin text-primary" />
            </div>
            <template v-else>
              <div
                v-if="checkpointsListPending"
                class="absolute inset-0 flex justify-center items-center bg-white/80 dark:bg-gray-900/80 z-10 rounded-lg"
              >
                <UIcon name="i-lucide-loader-2" class="w-8 h-8 animate-spin text-primary" />
              </div>
              <CheckpointsList
                :list-data="listData"
                :pager="listPager"
                :page-size-options="pageSizeOptions"
                @page-change="goToPage"
                @update-size="goToSize"
              />
            </template>
          </div>
        </div>

        <!-- Empty state: custom range without dates -->
        <div
          v-else-if="dateRangeType === 'custom' && (!customStartDate || !customEndDate)"
          class="text-center py-16 text-gray-500 dark:text-gray-400"
        >
          <UIcon name="i-lucide-calendar" class="w-12 h-12 mx-auto mb-4 opacity-50" />
          <p>请选择开始日期和结束日期</p>
        </div>

        <!-- Empty state (no data) -->
        <div v-else class="text-center py-16 text-gray-500 dark:text-gray-400">
          <UIcon name="i-lucide-bar-chart-3" class="w-12 h-12 mx-auto mb-4 opacity-50" />
          <p>暂无数据</p>
        </div>
      </div>
    </template>
  </UDashboardPanel>
</template>

<script setup lang="ts">
import { useOverviewStats } from '~/features/overview/composables/useOverviewStats'
import { useOverviewCheckpoints } from '~/features/overview/composables/useOverviewCheckpoints'
import { DATE_RANGE_OPTIONS } from '~/features/overview/constants/overview.constants'
import ContributionChart from '~/features/overview/components/ContributionChart.vue'
import CheckpointsList from '~/features/overview/components/CheckpointsList.vue'
import StatsCard from '~/shared/components/StatsCard.vue'
import { formatDate } from '~/shared/utils/date'
import { formatTokenCount } from '~/shared/utils/format'

definePageMeta({
  layout: 'admin',
  middleware: 'auth',
})

const {
  stats,
  pending,
  error,
  refresh,
  dateRangeType,
  customStartDate,
  customEndDate,
  selectDateRange,
  queryParams,
} = useOverviewStats()

const {
  chartData,
  chartDataTruncated,
  listData,
  listPager,
  agentStats,
  pending: checkpointsPending,
  listPending: checkpointsListPending,
  goToPage,
  goToSize,
  pageSizeOptions,
} = useOverviewCheckpoints(queryParams)
</script>
