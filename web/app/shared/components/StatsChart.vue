<template>
  <UCard>
    <template #header>
      <h3 class="text-lg font-semibold">{{ title }}</h3>
    </template>
    
    <!-- Empty data state -->
    <div v-if="isEmpty" class="text-center py-16 text-gray-500 dark:text-gray-400">
      <UIcon name="i-lucide-bar-chart-3" class="w-12 h-12 mx-auto mb-4 opacity-50"/>
      <p>暂无数据</p>
    </div>
    
    <!-- Chart -->
    <div v-else>
      <VChart 
        :option="chartOption" 
        :style="{ height: chartHeight }" 
        autoresize
      />
    </div>
  </UCard>
</template>

<script setup lang="ts">
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
import type { EChartsOption } from 'echarts'

// Register ECharts components
use([
  CanvasRenderer,
  BarChart,
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent
])

interface Props {
  title: string
  chartOption: EChartsOption
  isEmpty?: boolean
  chartHeight?: string
}

withDefaults(defineProps<Props>(), {
  isEmpty: false,
  chartHeight: '400px'
})
</script>
