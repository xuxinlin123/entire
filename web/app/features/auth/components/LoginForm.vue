<script setup lang="ts">
import { loginSchema } from '../schemas/login.schema'
import type { LoginForm } from '../types/auth.form'
import { useAuth } from '../composables/useAuth'

const { login, loading } = useAuth()

const form = reactive<LoginForm>({
  username: '',
  password: '',
  remember: false,
})

const handleSubmit = async () => {
  try {
    // Validate form
    const validatedData = await loginSchema.parseAsync(form)
    
    // Login
    await login({
      username: validatedData.username,
      password: validatedData.password,
    })
  } catch (error) {
    console.error('Login failed:', error)
  }
}
</script>

<template>
  <UCard class="w-full max-w-md">
    <template #header>
      <h2 class="text-2xl font-bold text-center">登录</h2>
    </template>

    <form @submit.prevent="handleSubmit" class="space-y-4">
      <UFormGroup label="用户名" required>
        <UInput
          v-model="form.username"
          placeholder="请输入用户名"
          :disabled="loading"
        />
      </UFormGroup>

      <UFormGroup label="密码" required>
        <UInput
          v-model="form.password"
          type="password"
          placeholder="请输入密码"
          :disabled="loading"
        />
      </UFormGroup>

      <UCheckbox
        v-model="form.remember"
        label="记住我"
        :disabled="loading"
      />

      <UButton
        type="submit"
        block
        :loading="loading"
      >
        登录
      </UButton>
    </form>
  </UCard>
</template>
