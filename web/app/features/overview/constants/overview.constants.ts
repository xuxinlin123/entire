import type { DateRangeOption } from '~/shared/types/stats'

export const DATE_RANGE_OPTIONS: DateRangeOption[] = [
  { label: '最近 7 天', value: 'week' },
  { label: '最近 14 天', value: 'twoWeeks' },
  { label: '最近 30 天', value: 'month' },
  { label: '自定义', value: 'custom' },
]
