<script setup lang="ts">
import * as v from 'valibot'
import { useAuth } from '~/features/auth/composables/useAuth'

definePageMeta({
  layout: 'auth',
})


const auth = useAuth()
const router = useRouter()
const loading = ref(false)
const errorMessage = ref('')
const showPassword = ref(false)

const schema = v.object({
  username: v.pipe(
      v.string(),
      v.nonEmpty('不能为空'),
      v.minLength(2, '至少需要 2 个字符'),
      v.maxLength(32, '不能超过 32 个字符'),
  ),
  password: v.pipe(
      v.string(),
      v.nonEmpty('不能为空'),
      v.minLength(2, '至少需要 2 个字符'),
      v.maxLength(32, '不能超过 32 个字符'),
  ),
})

type Schema = v.InferOutput<typeof schema>

const state = reactive<Partial<Schema>>({
  username: '',
  password: '',
  rememberMe: false,
})

// If already logged in, redirect to overview
onMounted(async () => {
  if (import.meta.client && auth.isAuthenticated.value) {
    router.push('/admin/overview')
  }

  // Read remembered username from localStorage
  if (import.meta.client) {
    const rememberedUsername = localStorage.getItem('rememberedUsername')
    if (rememberedUsername) {
      state.username = rememberedUsername
      state.rememberMe = true
    }
  }

})

const handleLogin = async () => {

  errorMessage.value = ''
  loading.value = true

  try {
    await auth.login({
      username: state.username,
      password: state.password,
    })

    // Handle "remember me" functionality
    if (import.meta.client) {
      if (state.rememberMe) {
        localStorage.setItem('rememberedUsername', state.username)
      } else {
        localStorage.removeItem('rememberedUsername')
      }
    }

    await router.push('/admin/overview')
  } catch (error: any) {
    // Extract specific error message from backend
    const backendMessage = error?.response?._data?.message || 
                          error?.data?.message || 
                          error?.response?.data?.message
    
    errorMessage.value = backendMessage || error.message || '登录失败，请检查用户名和密码'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div>
    <div class="flex flex-col items-center">
      <div class="flex items-center gap-3">
        <UIcon name="i-lucide-layout-dashboard" class="w-10 h-10 sm:w-12 sm:h-12 text-success"/>
        <span class="text-2xl sm:text-3xl font-semibold text-highlighted ">Entire 仪表盘</span>
      </div>
    </div>

    <UForm :schema="schema" :state="state" class="space-y-4" data-id="login-form"@submit="handleLogin">
      <UFormField label="用户名" name="username">
        <UInput v-model="state.username" class="w-full" />
      </UFormField>

      <UFormField label="密码" name="password">
        <UInput v-model="state.password" :type="showPassword ? 'text' : 'password'" class="w-full">
          <template #trailing>
            <UButton
                color="neutral"
                variant="link"
                size="sm"
                :icon="showPassword ? 'i-lucide-eye-off' : 'i-lucide-eye'"
                @click="showPassword = !showPassword"
            />
          </template>
        </UInput>
      </UFormField>

      <!-- Remember me -->
      <div class="flex items-center">
        <UCheckbox
            v-model="state.rememberMe"
            label="记住我"
            :disabled="loading"
        />
      </div>

      <!-- Error message -->
      <UAlert
          v-if="errorMessage"
          color="error"
          variant="subtle"
          :title="errorMessage"
          :close-button="{ icon: 'i-lucide-x', color: 'gray', variant: 'link' }"
          @close="errorMessage = ''"
      />

      <UButton type="submit" block>登录</UButton>
    </UForm>
  </div>

</template>



