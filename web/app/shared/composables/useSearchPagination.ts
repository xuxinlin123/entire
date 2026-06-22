import type { SelectItem } from '@nuxt/ui'

/**
 * Pagination parameters type
 */
export interface Pager {
  /**
   * Current page number (starts from 1)
   */
  page: number
  /**
   * Number of items per page
   */
  size: number
}

/**
 * useSearchPagination options
 */
export interface UseSearchPaginationOptions {
  /**
   * Default items per page, defaults to 10
   */
  defaultSize?: number
  /**
   * Options for items per page, defaults to [10, 50, 100]
   */
  pageSizeOptions?: number[]

  /**
   * Whether to sync search conditions and pagination params to URL query params, defaults to true
   */
  syncUrlQueryParams?: boolean
}

/**
 * Generic search pagination logic
 *
 * Features:
 * 1. Initialize search conditions and pagination params from URL query params
 * 2. Search conditions and pagination params automatically sync to URL
 * 3. Reset pagination to first page when search conditions change
 * 4. Provide pagination-related computed properties and controls
 *
 * @param initialConditions Initial search conditions (excluding page and size)
 * @param options Configuration options
 * @returns Returns search conditions, pagination params, pagination controls, etc.
 *
 * @example
 * ```ts
 * const { conditions, pager, pageSizeOptions } = useSearchPagination({
 *   id: '',
 *   keywords: ''
 * }, {
 *   defaultSize: 10,
 *   pageSizeOptions: [10, 50, 100]
 * });
 *
 * // Bind pager.size directly in template, will auto reset to first page when modified
 * // <USelect v-model="pager.size" :items="pageSizeOptions" />
 * // pager.page starts from 1, can be used directly for UI display
 * ```
 */
export function useSearchPagination<T extends Record<string, any>>(
  initialConditions: T,
  options: UseSearchPaginationOptions = {},
) {
  const route = useRoute()
  const router = useRouter()

  const {
    defaultSize = 10,
    pageSizeOptions: customPageSizeOptions = [2, 10, 20, 50, 100],
    syncUrlQueryParams = true,
  } = options

  // Independent pagination params
  const pager = reactive<Pager>({
    page: 1,
    size: defaultSize,
  })

  // Search conditions (excluding page and size)
  const conditions = reactive<T>({
    ...initialConditions,
  } as T)

  if (syncUrlQueryParams) {
    // Initialize: read search conditions from URL
    Object.keys(initialConditions).forEach((key) => {
      const queryValue = route.query[key]

      if (queryValue !== undefined && queryValue !== null) {
        const initialValue = initialConditions[key as keyof T]

        // Convert based on initial value type
        if (typeof initialValue === 'number') {
          ;(conditions as Record<string, unknown>)[key] = Number(queryValue)
        } else if (typeof initialValue === 'boolean') {
          ;(conditions as Record<string, unknown>)[key] = queryValue === 'true'
        } else {
          ;(conditions as Record<string, unknown>)[key] = queryValue
        }
      }
    })

    // Initialize: read pagination params from URL (backend one-indexed, page valid range >= 1)
    if (route.query.page !== undefined) {
      const pageNum = Number(route.query.page)
      pager.page = Number.isNaN(pageNum) || pageNum < 1 ? 1 : pageNum
    }
    if (route.query.size !== undefined) {
      pager.size = Number(route.query.size)
    }
  }

  /**
   * Sync search conditions and pagination params to URL query params
   */
  const syncConditionsToUrl = async () => {
    if (!syncUrlQueryParams) {
      return
    }

    const query: Record<string, string> = {}

    // Iterate through all fields of search conditions
    Object.keys(conditions).forEach((key) => {
      const value = (conditions as Record<string, unknown>)[key]

      // Convert value to string
      if (value !== undefined && value !== null && value !== '') {
        query[key] = String(value)
      }
    })

    // Add pagination params (always kept)
    query.page = String(pager.page)
    query.size = String(pager.size)

    await router.push({ query })
  }

  // Watch for search condition changes, auto reset page to 1
  watch(
    () => ({ ...conditions }),
    async (newVal, oldVal) => {
      // Only reset page when value actually changes
      if (oldVal && JSON.stringify(newVal) !== JSON.stringify(oldVal)) {
        pager.page = 1
        await syncConditionsToUrl()
      }
    },
    { deep: true },
  )

  // Watch pager.size changes, auto reset to first page
  watch(
    () => pager.size,
    (newSize, oldSize) => {
      if (oldSize !== undefined && newSize !== oldSize) {
        pager.page = 1
      }
    },
  )

  // Watch pagination param changes, sync to URL
  watch(
    () => ({ ...pager }),
    async (newVal, oldVal) => {
      if (oldVal && (newVal.page !== oldVal.page || newVal.size !== oldVal.size)) {
        await syncConditionsToUrl()
      }
    },
  )

  // Items per page options
  const pageSizeItems = computed<SelectItem[]>(() => {
    return customPageSizeOptions.map((size) => ({
      label: `${size} 条/页`,
      value: size,
    }))
  })

  /**
   * Calculate total pages
   * @param total Total record count
   */
  const getTotalPages = (total: number) => {
    return Math.ceil(total / pager.size)
  }

  return {
    /**
     * Search conditions (reactive object, excluding page and size)
     */
    conditions,
    /**
     * Pagination params (reactive object, includes page and size)
     * Note: modifying pager.size will auto reset to first page
     */
    pager,
    /**
     * Items per page options
     */
    pageSizeOptions: pageSizeItems,
    /**
     * Manually sync conditions to URL (usually no need to call manually, auto syncs)
     */
    syncConditionsToUrl,
    /**
     * Calculate total pages
     */
    getTotalPages,
  }
}
