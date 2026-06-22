import type { EChartsOption } from 'echarts'
import type { ProjectStatsItem, AuthorStatsItem, CodeChangeStatsItem } from '~/shared/types/stats'

/**
 * Statistics chart configuration Composable
 */
export function useStatsCharts() {
  const colorMode = useColorMode()
  const isDark = computed(() => colorMode.value === 'dark')

  // Common color configuration
  const colors = {
    primary: '#3b82f6',
    success: '#10b981',
    warning: '#f59e0b',
    danger: '#ef4444',
    text: computed(() => isDark.value ? '#e5e7eb' : '#374151'),
    subText: computed(() => isDark.value ? '#9ca3af' : '#6b7280'),
    axisLine: computed(() => isDark.value ? '#374151' : '#e5e7eb'),
    splitLine: computed(() => isDark.value ? '#1f2937' : '#f3f4f6')
  }

  /**
   * Get Top N data (max 30 items by default)
   */
  function getTopN<T>(data: T[], n: number = 30): T[] {
    return data.slice(0, n)
  }

  /**
   * Project commit statistics chart configuration
   */
  function getProjectCommitChartOption(data: ProjectStatsItem[]): EChartsOption {
    const topData = getTopN(data)
    const projectNames = topData.map(item => item.projectName)
    const commitCounts = topData.map(item => item.commitCount)

    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'shadow'
        },
        formatter: (params: any) => {
          const item = params[0]
          return `${item.name}<br/>提交数：${item.value}`
        }
      },
      grid: {
        top: '5%',
        left: '3%',
        right: '3%',
        bottom: '3%',
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: projectNames,
        axisLabel: {
          color: colors.text.value,
          rotate: 45,
          interval: 0,
          align: 'right',
          verticalAlign: 'middle'
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        }
      },
      yAxis: {
        type: 'value',
        // name: 'Commits',
        nameTextStyle: {
          color: colors.subText.value
        },
        axisLabel: {
          color: colors.text.value
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        },
        splitLine: {
          lineStyle: {
            color: colors.splitLine.value
          }
        }
      },
      series: [
        {
          name: '提交数',
          type: 'bar',
          data: commitCounts,
          itemStyle: {
            color: colors.primary
          },
          label: {
            show: true,
            position: 'top',
            color: colors.text.value
          }
        }
      ],
      dataZoom: topData.length > 15 ? [
        {
          type: 'slider',
          show: true,
          start: 0,
          end: 50
        }
      ] : undefined
    }
  }

  /**
   * Project average score chart configuration
   */
  function getProjectScoreChartOption(data: ProjectStatsItem[]): EChartsOption {
    // Sort by average score descending
    const sortedData = [...data].sort((a, b) => b.averageScore - a.averageScore)
    const topData = getTopN(sortedData)
    const projectNames = topData.map(item => item.projectName)
    const scores = topData.map(item => item.averageScore)

    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'shadow'
        },
        formatter: (params: any) => {
          const item = params[0]
          return `${item.name}<br/>平均分：${Number(item.value).toFixed(2)}`
        }
      },
      grid: {
        top: '5%',
        left: '3%',
        right: '3%',
        bottom: '3%',
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: projectNames,
        axisLabel: {
          color: colors.text.value,
          rotate: 45,
          interval: 0,
          align: 'right',
          verticalAlign: 'middle'
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        }
      },
      yAxis: {
        type: 'value',
        // name: 'Avg Score',
        nameTextStyle: {
          color: colors.subText.value
        },
        axisLabel: {
          color: colors.text.value
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        },
        splitLine: {
          lineStyle: {
            color: colors.splitLine.value
          }
        },
        min: 0,
        max: 100
      },
      series: [
        {
          name: '平均分',
          type: 'bar',
          data: scores,
          itemStyle: {
            color: colors.success
          },
          label: {
            show: true,
            position: 'top',
            color: colors.text.value,
            formatter: (params: any) => Number(params.value).toFixed(2)
          }
        }
      ],
      dataZoom: topData.length > 15 ? [
        {
          type: 'slider',
          show: true,
          start: 0,
          end: 50
        }
      ] : undefined
    }
  }

  /**
   * Author commit statistics chart configuration
   */
  function getAuthorCommitChartOption(data: AuthorStatsItem[]): EChartsOption {
    const topData = getTopN(data)
    const authors = topData.map(item => item.author)
    const commitCounts = topData.map(item => item.commitCount)

    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'shadow'
        },
        formatter: (params: any) => {
          const item = params[0]
          return `${item.name}<br/>提交数：${item.value}`
        }
      },
      grid: {
        top: '5%',
        left: '3%',
        right: '3%',
        bottom: '3%',
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: authors,
        axisLabel: {
          color: colors.text.value,
          rotate: 45,
          interval: 0,
          align: 'right',
          verticalAlign: 'middle'
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        }
      },
      yAxis: {
        type: 'value',
        // name: 'Commits',
        nameTextStyle: {
          color: colors.subText.value
        },
        axisLabel: {
          color: colors.text.value
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        },
        splitLine: {
          lineStyle: {
            color: colors.splitLine.value
          }
        }
      },
      series: [
        {
          name: '提交数',
          type: 'bar',
          data: commitCounts,
          itemStyle: {
            color: colors.primary
          },
          label: {
            show: true,
            position: 'top',
            color: colors.text.value
          }
        }
      ],
      dataZoom: topData.length > 15 ? [
        {
          type: 'slider',
          show: true,
          start: 0,
          end: 50
        }
      ] : undefined
    }
  }

  /**
   * Author average score chart configuration
   */
  function getAuthorScoreChartOption(data: AuthorStatsItem[]): EChartsOption {
    // Sort by average score descending
    const sortedData = [...data].sort((a, b) => b.averageScore - a.averageScore)
    const topData = getTopN(sortedData)
    const authors = topData.map(item => item.author)
    const scores = topData.map(item => item.averageScore)

    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'shadow'
        },
        formatter: (params: any) => {
          const item = params[0]
          return `${item.name}<br/>平均分：${Number(item.value).toFixed(2)}`
        }
      },
      grid: {
        top: '5%',
        left: '3%',
        right: '3%',
        bottom: '3%',
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: authors,
        axisLabel: {
          color: colors.text.value,
          rotate: 45,
          interval: 0,
          align: 'right',
          verticalAlign: 'middle'
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        }
      },
      yAxis: {
        type: 'value',
        // name: 'Avg Score',
        nameTextStyle: {
          color: colors.subText.value
        },
        axisLabel: {
          color: colors.text.value
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        },
        splitLine: {
          lineStyle: {
            color: colors.splitLine.value
          }
        },
        min: 0,
        max: 100
      },
      series: [
        {
          name: '平均分',
          type: 'bar',
          data: scores,
          itemStyle: {
            color: colors.success
          },
          label: {
            show: true,
            position: 'top',
            color: colors.text.value,
            formatter: (params: any) => Number(params.value).toFixed(2)
          }
        }
      ],
      dataZoom: topData.length > 15 ? [
        {
          type: 'slider',
          show: true,
          start: 0,
          end: 50
        }
      ] : undefined
    }
  }

  /**
   * Project code change chart configuration
   */
  function getProjectCodeChangeChartOption(data: CodeChangeStatsItem[]): EChartsOption {
    const topData = getTopN(data)
    const names = topData.map(item => item.name)
    const additions = topData.map(item => item.additions)
    const deletions = topData.map(item => -item.deletions) // Convert to negative value

    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'shadow'
        },
        formatter: (params: any) => {
          const name = params[0].name
          const addValue = params[0].value
          const delValue = Math.abs(params[1].value)
          const total = addValue + delValue
          return `${name}<br/>新增：+${addValue}<br/>删除：-${delValue}<br/>总计：${total}`
        }
      },
      grid: {
        top: '5%',
        left: '3%',
        right: '3%',
        bottom: '3%',
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: names,
        axisLabel: {
          color: colors.text.value,
          rotate: 45,
          interval: 0,
          align: 'right',
          verticalAlign: 'middle'
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        }
      },
      yAxis: {
        type: 'value',
        // name: 'Lines of code',
        nameTextStyle: {
          color: colors.subText.value
        },
        axisLabel: {
          color: colors.text.value,
          formatter: (value: number) => Math.abs(value).toString()
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        },
        splitLine: {
          lineStyle: {
            color: colors.splitLine.value
          }
        }
      },
      series: [
        {
          name: '新增',
          type: 'bar',
          barGap: '-100%', // Overlap bars at the same position
          data: additions,
          itemStyle: {
            color: colors.success
          },
          label: {
            show: true,
            position: 'top',
            color: colors.text.value,
            formatter: (params: any) => params.value > 0 ? `+${params.value}` : ''
          }
        },
        {
          name: '删除',
          type: 'bar',
          data: deletions,
          itemStyle: {
            color: colors.danger
          },
          label: {
            show: true,
            position: 'bottom',
            color: colors.text.value,
            formatter: (params: any) => params.value < 0 ? `-${Math.abs(params.value)}` : ''
          }
        }
      ],
      dataZoom: topData.length > 15 ? [
        {
          type: 'slider',
          show: true,
          start: 0,
          end: 50
        }
      ] : undefined
    }
  }

  /**
   * Author code change chart configuration
   */
  function getAuthorCodeChangeChartOption(data: CodeChangeStatsItem[]): EChartsOption {
    const topData = getTopN(data)
    const names = topData.map(item => item.name)
    const additions = topData.map(item => item.additions)
    const deletions = topData.map(item => -item.deletions) // Convert to negative value

    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'shadow'
        },
        formatter: (params: any) => {
          const name = params[0].name
          const addValue = params[0].value
          const delValue = Math.abs(params[1].value)
          const total = addValue + delValue
          return `${name}<br/>新增：+${addValue}<br/>删除：-${delValue}<br/>总计：${total}`
        }
      },
      grid: {
        top: '5%',
        left: '3%',
        right: '3%',
        bottom: '3%',
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: names,
        axisLabel: {
          color: colors.text.value,
          rotate: 45,
          interval: 0,
          align: 'right',
          verticalAlign: 'middle'
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        }
      },
      yAxis: {
        type: 'value',
        // name: 'Lines of code',
        nameTextStyle: {
          color: colors.subText.value
        },
        axisLabel: {
          color: colors.text.value,
          formatter: (value: number) => Math.abs(value).toString()
        },
        axisLine: {
          lineStyle: {
            color: colors.axisLine.value
          }
        },
        splitLine: {
          lineStyle: {
            color: colors.splitLine.value
          }
        }
      },
      series: [
        {
          name: '新增',
          type: 'bar',
          barGap: '-100%', // Overlap bars at the same position
          data: additions,
          itemStyle: {
            color: colors.success
          },
          label: {
            show: true,
            position: 'top',
            color: colors.text.value,
            formatter: (params: any) => params.value > 0 ? `+${params.value}` : ''
          }
        },
        {
          name: '删除',
          type: 'bar',
          data: deletions,
          itemStyle: {
            color: colors.danger
          },
          label: {
            show: true,
            position: 'bottom',
            color: colors.text.value,
            formatter: (params: any) => params.value < 0 ? `-${Math.abs(params.value)}` : ''
          }
        }
      ],
      dataZoom: topData.length > 15 ? [
        {
          type: 'slider',
          show: true,
          start: 0,
          end: 50
        }
      ] : undefined
    }
  }

  return {
    getProjectCommitChartOption,
    getProjectScoreChartOption,
    getAuthorCommitChartOption,
    getAuthorScoreChartOption,
    getProjectCodeChangeChartOption,
    getAuthorCodeChangeChartOption
  }
}
