<script setup lang="ts">
import CheckpointSearchForm from '~/features/checkpoint/components/CheckpointSearchForm.vue'
import CheckpointTable from '~/features/checkpoint/components/CheckpointTable.vue'
import { useCheckpointSearch } from '~/features/checkpoint/composables/useCheckpointSearch'

definePageMeta({
  layout: 'admin',
})

const {
  startDateStr,
  endDateStr,
  repoIds,
  commitAuthorNames,
  commitMessage,
  pager,
  listData,
  listPager,
  isLoading,
  repoOptions,
  commitAuthorOptions,
  loadingRepos,
  loadingAuthors,
  onPageChange,
  resetFilters,
  pageSizeOptions,
} = useCheckpointSearch()
</script>

<template>
  <UDashboardPanel id="dashboard">
    <template #header>
      <UDashboardNavbar title="检查点">
        <template #leading>
          <UDashboardSidebarCollapse />
        </template>
      </UDashboardNavbar>
    </template>
    <template #body>
      <div class="space-y-4 mt-2">
        <CheckpointSearchForm
          v-model:start-date-str="startDateStr"
          v-model:end-date-str="endDateStr"
          v-model:repo-ids="repoIds"
          v-model:commit-author-names="commitAuthorNames"
          v-model:commit-message="commitMessage"
          :repo-options="repoOptions"
          :commit-author-options="commitAuthorOptions"
          :loading-repos="loadingRepos"
          :loading-authors="loadingAuthors"
          @reset="resetFilters"
        />
        <div v-if="isLoading" class="flex justify-center py-12">
          <UIcon name="i-lucide-loader-2" class="w-8 h-8 animate-spin text-primary" />
        </div>
        <CheckpointTable
          v-else
          :list-data="listData"
          :list-pager="listPager"
          :pager="pager"
          :page-size-options="pageSizeOptions"
          @page-change="onPageChange"
          @update-size="pager.size = $event"
        />
      </div>
    </template>
  </UDashboardPanel>
</template>
