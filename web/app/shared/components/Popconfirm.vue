<script setup lang="ts">
import { ref, computed } from 'vue'
import { UPopover } from '#components'
import type { PopoverContentProps } from 'reka-ui'
import type { UIColor, UIButtonVariant } from '~/shared/types/ui'

interface Props {
  /** Title */
  title?: string
  /** Description content */
  description?: string
  /** Confirm button text */
  okText?: string
  /** Cancel button text */
  cancelText?: string
  /** Whether to show confirm button */
  showOk?: boolean
  /** Whether to show cancel button */
  showCancel?: boolean
  /** Theme color */
  color?: UIColor
  /** Confirm button type */
  okColor?: UIColor
  /** Cancel button type */
  cancelColor?: UIColor
  /** Confirm button variant */
  okVariant?: UIButtonVariant
  /** Cancel button variant */
  cancelVariant?: UIButtonVariant
  /** Icon */
  icon?: string
  /** Icon color */
  iconColor?: UIColor
  /** Trigger mode */
  mode?: 'click' | 'hover'
  /** Popover position config */
  content?: PopoverContentProps
  /** Confirm callback (supports async) */
  onOk?: () => void | Promise<void>
  /** Cancel callback (supports async) */
  onCancel?: () => void | Promise<void>
}

const props = withDefaults(defineProps<Props>(), {
  okText: '确认',
  cancelText: '取消',
  showOk: true,
  showCancel: true,
  color: 'primary',
  cancelColor: 'neutral',
  okVariant: 'solid',
  cancelVariant: 'outline',
  icon: 'i-lucide-circle-alert',
  mode: 'click',
})

// Calculate final okColor, use color if not configured
const finalOkColor = computed(() => props.okColor || props.color)

// Calculate final iconColor, use color if not configured
const finalIconColor = computed(() => props.iconColor || props.color)

const emit = defineEmits<{
  /** Confirm event */
  ok: []
  /** Cancel event */
  cancel: []
}>()

// v-model:open - control popover display
const open = defineModel<boolean>('open', { default: false })

// Confirm button loading state
const loading = ref(false)

// Close popover
const close = () => {
  open.value = false
  loading.value = false
}

// Confirm operation
const handleOk = async () => {
  if (!props.onOk) {
    close()
    return
  }

  loading.value = true

  try {
    await props.onOk()
    close()
  } catch (error) {
    loading.value = false
    throw error
  }
}

// Cancel operation
const handleCancel = async () => {
  // Prefer props.onCancel, if not then emit (backward compatible)
  if (props.onCancel) {
    await props.onCancel()
  }
  close()
}
</script>

<template>
  <UPopover v-model:open="open" :mode="mode" :content="content" :arrow="false" :modal="true">
    <slot />
    <!-- Popover content -->
    <template #content>
      <div class="p-4 space-y-3 min-w-3xs max-w-xs">
        <!-- Title and icon -->
        <div class="flex items-start gap-3">
          <UIcon v-if="icon" :name="icon" :class="['flex-shrink-0 w-5 h-5 mt-0.5', `text-${finalIconColor}`]" />
          <div class="flex-1 space-y-1">
            <div v-if="title" class="font-medium text-highlighted">
              {{ title }}
            </div>
            <div v-if="description" class="text-sm text-muted">
              {{ description }}
            </div>
          </div>
        </div>

        <!-- Action buttons -->
        <div v-if="showOk || showCancel" class="flex items-center justify-end gap-2 pt-1">
          <UButton
            v-if="showCancel"
            :variant="cancelVariant"
            :color="cancelColor"
            size="xs"
            :disabled="loading"
            @click="handleCancel"
          >
            {{ cancelText }}
          </UButton>
          <UButton
            v-if="showOk"
            :variant="okVariant"
            :color="finalOkColor"
            size="xs"
            :loading="loading"
            @click="handleOk"
          >
            {{ okText }}
          </UButton>
        </div>
      </div>
    </template>
  </UPopover>
</template>
