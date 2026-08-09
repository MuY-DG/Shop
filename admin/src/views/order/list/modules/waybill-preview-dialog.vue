<template>
  <ElDialog
    :model-value="modelValue"
    :title="title"
    width="900px"
    append-to-body
    destroy-on-close
    class="waybill-preview-dialog"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="releaseCurrentUrl"
  >
    <ElAlert
      title="面单内容来自微信接口，仅在当前预览期间保留"
      description="预览区域已禁止脚本、表单、弹窗和顶层页面跳转。关闭窗口或切换订单后，本地临时地址会立即释放。"
      type="info"
      :closable="false"
      show-icon
      class="waybill-preview__notice"
    />

    <div v-loading="!frameReady" class="waybill-preview__frame-wrap">
      <iframe
        v-if="blobUrl"
        ref="frameRef"
        :src="blobUrl"
        title="电子面单预览"
        sandbox="allow-same-origin allow-modals"
        class="waybill-preview__frame"
        @load="handleFrameLoad"
      />
      <ElEmpty v-else description="暂无可预览的面单内容" :image-size="72" />
    </div>

    <template #footer>
      <div class="waybill-preview__footer">
        <ElButton @click="emit('update:modelValue', false)">关闭</ElButton>
        <ElButton type="primary" :disabled="!frameReady" @click="invokePrint"> 调起打印 </ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
  import { ElMessage } from 'element-plus'
  import { releaseBlobUrl, replaceBlobUrl } from '../waybill-workflow'

  const props = withDefaults(
    defineProps<{
      modelValue: boolean
      blob: Blob | null
      orderKey: number | string | null
      contentOrderKey: number | string | null
      title?: string
      autoPrint?: boolean
    }>(),
    {
      title: '电子面单预览',
      autoPrint: false
    }
  )

  const emit = defineEmits<{
    'update:modelValue': [value: boolean]
    printed: []
  }>()

  const frameRef = ref<HTMLIFrameElement>()
  const blobUrl = ref<string | null>(null)
  const frameReady = ref(false)
  const autoPrintPending = ref(false)
  const revokeObjectUrl = (url: string) => URL.revokeObjectURL(url)

  const releaseCurrentUrl = () => {
    blobUrl.value = releaseBlobUrl(blobUrl.value, revokeObjectUrl)
    frameReady.value = false
    autoPrintPending.value = false
  }

  const installBlob = async (blob: Blob) => {
    const nextUrl = URL.createObjectURL(blob)
    blobUrl.value = replaceBlobUrl(blobUrl.value, nextUrl, revokeObjectUrl)
    frameReady.value = false
    autoPrintPending.value = props.autoPrint
    await nextTick()
  }

  const invokePrint = () => {
    if (!frameReady.value || !frameRef.value?.contentWindow) return
    try {
      frameRef.value.contentWindow.focus()
      frameRef.value.contentWindow.print()
      ElMessage.success('已调起打印')
      emit('printed')
    } catch {
      ElMessage.error('无法调起浏览器打印，请重新打开面单后再试')
    }
  }

  const handleFrameLoad = () => {
    frameReady.value = true
    if (!autoPrintPending.value) return
    autoPrintPending.value = false
    invokePrint()
  }

  watch(
    () => [props.modelValue, props.blob, props.orderKey, props.contentOrderKey] as const,
    async ([visible, blob, orderKey, contentOrderKey]) => {
      releaseCurrentUrl()
      if (visible && blob && orderKey === contentOrderKey) await installBlob(blob)
    },
    { immediate: true }
  )

  onBeforeUnmount(releaseCurrentUrl)
</script>

<style scoped lang="scss">
  .waybill-preview__notice {
    margin-bottom: 14px;
  }

  .waybill-preview__frame-wrap {
    min-height: 560px;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .waybill-preview__frame {
    display: block;
    width: 100%;
    height: 68vh;
    min-height: 560px;
    background: white;
    border: 0;
  }

  .waybill-preview__footer {
    display: flex;
    gap: 12px;
    justify-content: flex-end;
  }

  @media (width <= 720px) {
    .waybill-preview__frame-wrap,
    .waybill-preview__frame {
      min-height: 440px;
    }
  }
</style>
