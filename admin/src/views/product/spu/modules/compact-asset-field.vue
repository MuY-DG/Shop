<template>
  <div class="compact-asset-field" :class="{ 'is-small': small }">
    <AssetPicker
      v-if="sourceMode === 'upload'"
      :model-value="modelValue"
      :media-kind="mediaKind"
      :disabled="disabled"
      compact
      :compact-size="small ? 'small' : 'default'"
      @change="handleAssetChange"
    />
    <ElInput
      v-else
      class="compact-asset-field__url"
      :model-value="modelValue.url"
      :placeholder="mediaKind === 'VIDEO' ? '请输入公开的视频 URL' : '请输入公开的图片 URL'"
      :disabled="disabled"
      clearable
      @update:model-value="updateUrl"
    />

    <ElButton
      v-if="allowUrl"
      class="compact-asset-field__switch"
      link
      type="primary"
      :disabled="disabled"
      @click="toggleSource"
    >
      {{ sourceMode === 'upload' ? '改用 URL' : `改为上传${mediaKindLabel}` }}
    </ElButton>
  </div>
</template>

<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import AssetPicker from '@/components/business/asset-picker/index.vue'

  interface Props {
    modelValue: Api.Common.AssetValue
    mediaKind: Exclude<Api.Storage.MediaKind, 'DOCUMENT'>
    disabled?: boolean
    allowUrl?: boolean
    small?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: Api.Common.AssetValue): void
    (event: 'change', value: Api.Common.AssetValue): void
  }

  const props = withDefaults(defineProps<Props>(), {
    disabled: false,
    allowUrl: true,
    small: false
  })
  const emit = defineEmits<Emits>()

  const sourceMode = ref<'upload' | 'url'>('upload')
  const sourceModeTouched = ref(false)
  const mediaKindLabel = computed(() => (props.mediaKind === 'VIDEO' ? '视频' : '图片'))

  const emitValue = (value: Api.Common.AssetValue) => {
    emit('update:modelValue', value)
    emit('change', value)
  }

  const handleAssetChange = (value: Api.Common.AssetValue) => {
    sourceModeTouched.value = true
    sourceMode.value = 'upload'
    emitValue(value)
  }

  const updateUrl = (url: string) => {
    sourceModeTouched.value = true
    sourceMode.value = 'url'
    emitValue({ fileId: null, url })
  }

  const toggleSource = () => {
    sourceModeTouched.value = true
    sourceMode.value = sourceMode.value === 'upload' ? 'url' : 'upload'
    if (sourceMode.value === 'url' && props.modelValue.fileId) {
      emitValue({ fileId: null, url: props.modelValue.url })
    }
  }

  watch(
    () => [props.modelValue.fileId, props.modelValue.url] as const,
    ([fileId, url]) => {
      if (fileId) {
        sourceMode.value = 'upload'
        return
      }
      if (!sourceModeTouched.value) sourceMode.value = url ? 'url' : 'upload'
    },
    { immediate: true }
  )
</script>

<style scoped lang="scss">
  .compact-asset-field {
    display: inline-flex;
    flex-wrap: wrap;
    gap: 6px 10px;
    align-items: flex-end;
    max-width: 100%;
  }

  .compact-asset-field__url {
    width: min(520px, 100%);
  }

  .compact-asset-field__switch {
    align-self: flex-end;
    min-height: 24px;
    padding: 0;
    font-size: 12px;
  }

  .compact-asset-field.is-small {
    display: grid;
    justify-items: start;

    .compact-asset-field__url {
      width: min(280px, 100%);
    }
  }
</style>
