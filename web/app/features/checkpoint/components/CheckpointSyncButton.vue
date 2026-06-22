<template>
  <UButton
    :loading="isSyncing"
    :disabled="isSyncing"
    icon="i-lucide-refresh-cw"
    color="primary"
    variant="solid"
    size="sm"
    @click="syncNow"
  >
    {{ isSyncing ? '同步中...' : '立即同步' }}
  </UButton>
</template>

<script setup lang="ts">
import { checkpointApi } from '../api/checkpoint.api'
import { useMessage } from '~/shared/composables/useMessage'

const props = withDefaults(defineProps<{
  fullScan?: boolean
}>(), {
  fullScan: false,
})

const emit = defineEmits<{
  synced: []
}>()

const message = useMessage()
const isSyncing = ref(false)

async function syncNow() {
  if (isSyncing.value) return

  isSyncing.value = true
  try {
    await checkpointApi.syncAll(props.fullScan)
    message.success('数据同步完成')
    emit('synced')
  } catch (err: any) {
    message.error({
      title: '数据同步失败',
      description: err?.data?.message || err?.message || '请稍后重试',
    })
  } finally {
    isSyncing.value = false
  }
}
</script>
