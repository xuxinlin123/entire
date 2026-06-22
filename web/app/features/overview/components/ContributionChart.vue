<template>
  <UCard>
    <template #header>
      <div class="flex items-center justify-between">
        <h3 class="text-lg font-semibold">贡献分布</h3>
        <span v-if="checkpointCount !== null" class="text-sm text-gray-500 dark:text-gray-400">
          {{ checkpointCount }} 个检查点
        </span>
      </div>
    </template>

    <p v-if="chartDataTruncated" class="text-sm text-amber-600 dark:text-amber-500 mb-2">
      仅显示最新 500 条记录
    </p>

    <!-- Empty state: no date range and no data -->
    <div v-if="isEmpty" class="text-center py-16 text-gray-500 dark:text-gray-400">
      <UIcon name="i-lucide-git-commit" class="w-12 h-12 mx-auto mb-4 opacity-50" />
      <p>当前范围暂无检查点</p>
    </div>

    <!-- Chart: show when has date range (all dates on x-axis) or has data -->
    <div v-else-if="showChart" class="w-full" :style="{ height: chartHeight }">
      <VChart :option="chartOption" autoresize />
    </div>

    <!-- Legend -->
    <div v-if="agentStats?.length" class="flex flex-wrap gap-4 mt-4 pt-4 border-t border-gray-200 dark:border-gray-700">
      <span
        v-for="stat in agentStats"
        :key="stat.agent"
        class="inline-flex items-center gap-2 text-sm"
      >
        <span
          class="inline-block w-3 h-3 rounded-full"
          :style="{ backgroundColor: getAgentColor(stat.agent) }"
        />
        {{ stat.agent }} {{ stat.percentage }}%
      </span>
    </div>
  </UCard>
</template>

<script setup lang="ts">
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { ScatterChart } from 'echarts/charts'
import {
  TooltipComponent,
  LegendComponent,
  GridComponent,
} from 'echarts/components'
import VChart from 'vue-echarts'
import type { EChartsOption } from 'echarts'
import type { OverviewCheckpointChartItem, OverviewAgentStat } from '~/shared/types/stats'
import { useAgentColors } from '../composables/useAgentColors'
import { getDateRange } from '~/shared/utils/date'

use([CanvasRenderer, ScatterChart, TooltipComponent, LegendComponent, GridComponent])

const { getAgentColor } = useAgentColors()

const props = withDefaults(
  defineProps<{
    chartData: OverviewCheckpointChartItem[]
    startTime?: number
    endTime?: number
    agentStats?: OverviewAgentStat[] | null
    chartDataTruncated?: boolean
    checkpointCount?: number | null
    chartHeight?: string
  }>(),
  {
    startTime: undefined,
    endTime: undefined,
    agentStats: () => [],
    chartDataTruncated: false,
    checkpointCount: null,
    chartHeight: '400px',
  }
)

/** Show empty state only when no date range is provided and no data */
const isEmpty = computed(
  () =>
    !props.chartData?.length &&
    (props.startTime == null || props.endTime == null)
)

/** Show chart when: has date range (even if no data) OR has data */
const showChart = computed(
  () =>
    (props.startTime != null && props.endTime != null) || (props.chartData?.length ?? 0) > 0
)

/** Convert Unix ms to date string (YYYY-MM-DD) in local timezone */
function toDateStr(ts: number): string {
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Convert Unix ms to hour (0-24) in local timezone */
function toHour(ts: number): number {
  const d = new Date(ts)
  return d.getHours() + d.getMinutes() / 60 + d.getSeconds() / 3600
}

/** Circle size: min 8, max 40, scale by additions. null/0 -> 8 */
function symbolSize(additions: number): number {
  const a = additions ?? 0
  if (a <= 0) return 8
  return Math.min(40, Math.max(8, 8 + Math.log2(a + 1) * 4))
}

const chartOption = computed<EChartsOption>(() => {
  const data = props.chartData ?? []
  const byAgent = new Map<string, { x: string; y: number; size: number; item: OverviewCheckpointChartItem }[]>()
  for (const item of data) {
    const agent = item.agent ?? '未知'
    if (!byAgent.has(agent)) byAgent.set(agent, [])
    byAgent.get(agent)!.push({
      x: toDateStr(item.commitTime),
      y: toHour(item.commitTime),
      size: symbolSize(item.additions ?? 0),
      item,
    })
  }

  const series = Array.from(byAgent.entries()).map(([agent, points]) => ({
    name: agent,
    type: 'scatter',
    data: points.map((p) => [p.x, p.y, p.size]),
    symbolSize: (val: number[]) => val[2] ?? 8,
    itemStyle: { color: getAgentColor(agent) },
    emphasis: { scale: 1.2 },
  }))

  const xAxisData =
    props.startTime != null && props.endTime != null && props.startTime <= props.endTime
      ? getDateRange(props.startTime, props.endTime)
      : (() => {
          const fromData = [...new Set(data.map((d) => toDateStr(d.commitTime)))].sort()
          return fromData.length ? fromData : ['']
        })()

  return {
    tooltip: {
      trigger: 'item',
      formatter: (params: any) => {
        const p = params.data
        const idx = data.findIndex(
          (d) => toDateStr(d.commitTime) === p[0] && Math.abs(toHour(d.commitTime) - p[1]) < 0.01
        )
        const item = idx >= 0 ? data[idx] : null
        if (!item) return `${params.seriesName}<br/>${p[0]} ${p[1].toFixed(1)} 时`
        return `${item.checkpointId}<br/>${item.agent ?? '未知'}<br/>新增 +${item.additions ?? 0} / 删除 -${item.deletions ?? 0}`
      },
    },
    grid: { left: 60, right: 40, top: 20, bottom: 40 },
    xAxis: {
      type: 'category',
      data: xAxisData,
      axisLabel: { rotate: 45 },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 24,
      axisLabel: { formatter: '{value}' },
    },
    series,
  }
})
</script>
