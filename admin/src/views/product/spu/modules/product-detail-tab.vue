<template>
  <div class="product-detail-tab">
    <div class="product-detail-tab__heading">
      <div>
        <h3>商品详细</h3>
        <p>支持图文混排；富文本中的图片会继续按商品详情素材关系保存。</p>
      </div>
      <ElTag type="info">富文本</ElTag>
    </div>
    <ArtWangEditor
      :model-value="modelValue.detailHtml"
      height="520px"
      :disabled="disabled"
      @update:model-value="patchDetail"
    />
  </div>
</template>

<script setup lang="ts">
  import ArtWangEditor from '@/components/core/forms/art-wang-editor/index.vue'
  import type { ProductEditorForm } from './editor-model'

  interface Props {
    modelValue: ProductEditorForm
    disabled?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: ProductEditorForm): void
  }

  const props = withDefaults(defineProps<Props>(), {
    disabled: false
  })
  const emit = defineEmits<Emits>()

  const patchDetail = (detailHtml: string) => {
    emit('update:modelValue', { ...props.modelValue, detailHtml })
  }

  const validate = async () => true
  defineExpose({ validate })
</script>

<style scoped lang="scss">
  .product-detail-tab {
    min-height: 620px;
    padding: 20px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 12px;
  }

  .product-detail-tab__heading {
    display: flex;
    gap: 20px;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 18px;

    h3 {
      margin: 0;
      font-size: 16px;
    }

    p {
      margin: 6px 0 0;
      font-size: 13px;
      color: var(--el-text-color-secondary);
    }
  }
</style>
