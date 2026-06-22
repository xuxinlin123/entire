<script setup lang="ts">
import type { DropdownMenuItem } from '@nuxt/ui'

defineProps<{
  collapsed?: boolean
}>()

const open = ref(false)
const colorMode = useColorMode()
const auth = useAuth()

// User info (using fixed value)
const user = ref({
  name: '管理员',
  icon: 'i-lucide-circle-user-round',
})

// Handle logout
const handleLogout = async () => {
  open.value = false
  try {
    await auth.logout()
  } catch (error) {
    console.error('Logout failed:', error)
  }
}

const items = computed<DropdownMenuItem[][]>(() => [
  [
    {
      type: 'label',
      label: user.value.name,
      icon: user.value.icon,
    },
  ],
  [
    {
      label: '外观',
      icon: 'i-lucide-sun-moon',
      children: [
        {
          label: '浅色',
          icon: 'i-lucide-sun',
          type: 'checkbox',
          checked: colorMode.value === 'light',
          onSelect(e: Event) {
            e.preventDefault()
            colorMode.preference = 'light'
          },
        },
        {
          label: '深色',
          icon: 'i-lucide-moon',
          type: 'checkbox',
          checked: colorMode.value === 'dark',
          onSelect(e: Event) {
            e.preventDefault()
            colorMode.preference = 'dark'
          },
        },
      ],
    },
  ],
  [
    {
      label: '退出登录',
      icon: 'i-lucide-log-out',
      onSelect: handleLogout,
    },
  ],
])
</script>

<template>
    <UDropdownMenu
        v-model:open="open"
        :items="items"
        :content="{ align: 'center', collisionPadding: 12 }"
        :ui="{ content: collapsed ? 'w-48' : 'w-(--reka-dropdown-menu-trigger-width)' }"
    >
    <UButton
        :icon="user.icon"
        :label="collapsed ? undefined : user.name"
        color="neutral"
        variant="ghost"
        block
        :square="collapsed"
        class="data-[state=open]:bg-elevated justify-start"
    />
  </UDropdownMenu>
</template>
