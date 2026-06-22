<script setup lang="ts">
import { repoApi } from '../api/repo.api'
import type { FormSubmitEvent } from '#ui/types'
import { PLATFORM_OPTIONS } from '../constants/repo.constants'
import { repoFormSchema } from '../schemas/repo.schema'
import type { RepoCreateParams, RepoDTO } from '~/features/repository/types/repoDTO'

const emit = defineEmits<{
  ok: [repo: RepoDTO]
}>()

const message = useMessage()
const toast = useToast()
const formRef = ref()
const loading = ref(false)
const validatingToken = ref(false)
const submitMode = ref<'confirm' | 'saveAndContinue'>('confirm')
const showAccessToken = ref(false)

async function handleValidateToken() {
  if (!state.webUrl || !state.platform || !state.accessToken) {
    toast.add({
      title: '校验失败',
      description: '请先填写仓库地址、平台和访问令牌',
      color: 'warning',
    })
    return
  }
  validatingToken.value = true
  try {
    const result = await repoApi.validateToken({
      webUrl: state.webUrl,
      platform: state.platform,
      accessToken: state.accessToken,
    })
    if (result.valid) {
      message.success('访问令牌有效')
    } else {
      toast.add({
        title: '访问令牌无效',
        description: result.message || '访问令牌校验失败',
        color: 'error',
      })
    }
  } catch (error: any) {
    toast.add({
      title: '校验失败',
      description: error.message || '访问令牌校验失败',
      color: 'error',
    })
  } finally {
    validatingToken.value = false
  }
}

const { open, state, resetForm } = useModalForm<RepoCreateParams>({
  name: '',
  webUrl: '',
  platform: 'GITLAB',
  accessToken: '',
})

async function onSubmit(event: FormSubmitEvent<RepoCreateParams>) {
  if (loading.value) return
  loading.value = true

  try {
    const repo = await repoApi.create(event.data)
    message.success('仓库创建成功')

    if (submitMode.value === 'saveAndContinue') {
      state.name = ''
      state.webUrl = ''
      emit('ok', repo)
    } else {
      resetForm()
      open.value = false
      emit('ok', repo)
    }
  } catch (error: any) {
    toast.add({
      title: '创建失败',
      description: error.message || '仓库创建失败，请重试',
      color: 'error',
    })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <UButton label="新建仓库" icon="i-lucide-plus" color="primary" @click="open = true" />
  <USlideover
    v-model:open="open"
    :ui="{
      content: 'right-0 inset-y-0 w-[600px] max-w-[96vw]',
      header: 'flex items-center justify-between px-6 py-4',
      body: 'p-6',
      footer: 'flex items-center justify-end gap-3 px-6 py-4',
    }"
  >
    <template #header>
      <h2 class="text-base font-medium">创建仓库</h2>
      <UButton color="neutral" variant="ghost" icon="i-lucide-x" size="md" square @click="open = false" />
    </template>

    <template #body>
      <UForm
        ref="formRef"
        :state="state"
        :schema="repoFormSchema"
        class="space-y-4"
        @submit="onSubmit"
        :validateOn="['input', 'change']"
      >
        <UFormField label="名称" name="name" size="md" :ui="{ label: 'text-sm font-normal mb-1' }">
          <UInput v-model="state.name" placeholder="请输入仓库名称" size="md" class="w-full" />
        </UFormField>

        <UFormField label="仓库地址" name="webUrl" :ui="{ label: 'text-sm font-normal mb-1' }">
          <UInput v-model="state.webUrl" placeholder="https://github.com/user/repo" class="w-full" />
        </UFormField>

        <UFormField label="平台" name="platform" :ui="{ label: 'text-sm font-normal mb-1' }">
          <USelect v-model="state.platform" :items="PLATFORM_OPTIONS" placeholder="请选择平台" />
        </UFormField>

        <UFormField label="访问令牌" name="accessToken" :ui="{ label: 'text-sm font-normal mb-1' }">
          <div class="flex gap-2">
            <div class="relative flex-1">
              <UInput
                v-model="state.accessToken"
                :type="showAccessToken ? 'text' : 'password'"
                placeholder="请输入访问令牌（可选）"
                size="md"
                class="w-full"
              />
              <UButton
                :icon="showAccessToken ? 'i-lucide-eye-off' : 'i-lucide-eye'"
                color="neutral"
                variant="ghost"
                size="sm"
                class="absolute right-1 top-1/2 -translate-y-1/2"
                @click="showAccessToken = !showAccessToken"
              />
            </div>
            <UButton
              label="校验"
              color="neutral"
              variant="outline"
              size="md"
              :loading="validatingToken"
              @click="handleValidateToken"
            />
          </div>
        </UFormField>
      </UForm>
    </template>

    <template #footer>
      <UButton label="取消" color="neutral" variant="subtle" @click="open = false" />
      <UButton
        label="保存并继续"
        color="primary"
        variant="outline"
        :loading="loading"
        @click="submitMode = 'saveAndContinue'; formRef?.submit()"
      />
      <UButton
        label="确认"
        color="success"
        variant="solid"
        :loading="loading"
        @click="submitMode = 'confirm'; formRef?.submit()"
      />
    </template>
  </USlideover>
</template>
