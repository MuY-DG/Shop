<template>
  <div class="spec-tree-editor">
    <div v-if="!modelValue.length" class="spec-tree-editor__empty">
      <ElEmpty description="暂无规格，请添加规格名称" :image-size="72" />
    </div>

    <div v-for="(group, groupIndex) in modelValue" :key="group.groupKey" class="spec-group">
      <div class="spec-group__header">
        <div class="spec-group__title">
          <span class="spec-tree-editor__index">{{ groupIndex + 1 }}</span>
          <ElInput
            :model-value="group.name"
            maxlength="30"
            show-word-limit
            placeholder="规格名称，例如：颜色"
            :disabled="disabled"
            @update:model-value="updateGroupName(groupIndex, $event)"
          />
        </div>
        <div class="spec-group__actions">
          <ElRadio
            :model-value="imageGroupKey"
            :value="group.groupKey"
            :disabled="disabled"
            @change="selectImageGroup(group.groupKey)"
          >
            添加规格图
          </ElRadio>
          <ElButton type="danger" text :disabled="disabled" @click="removeGroup(groupIndex)">
            删除规格
          </ElButton>
        </div>
      </div>

      <div class="spec-values">
        <div
          v-for="(value, valueIndex) in group.values"
          :key="value.valueKey"
          class="spec-value"
          :class="{ 'spec-value--image': group.imageEnabled }"
        >
          <div class="spec-value__field">
            <span class="spec-value__dot" />
            <ElInput
              :model-value="value.valueName"
              maxlength="30"
              placeholder="规格值，例如：红色"
              :disabled="disabled"
              @update:model-value="updateValueName(groupIndex, valueIndex, $event)"
            />
            <ElButton
              type="danger"
              text
              :disabled="disabled"
              @click="removeValue(groupIndex, valueIndex)"
            >
              删除
            </ElButton>
          </div>

          <div v-if="group.imageEnabled" class="spec-value__image">
            <AssetPicker
              :model-value="{ fileId: value.imageFileId, url: value.image }"
              purpose="SPEC_VALUE_IMAGE"
              :disabled="disabled"
              @change="updateValueImage(groupIndex, valueIndex, $event)"
            />
            <div class="spec-value__image-hint">可选。不上传时，SKU 图片继续回退为商品封面图。</div>
          </div>
        </div>

        <ElButton
          plain
          type="primary"
          :disabled="disabled || group.values.length >= 50"
          @click="addValue(groupIndex)"
        >
          + 添加规格值
        </ElButton>
      </div>
    </div>

    <ElButton
      class="spec-tree-editor__add"
      plain
      :disabled="disabled || modelValue.length >= 10"
      @click="addGroup"
    >
      + 添加新规格
    </ElButton>
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import type { ProductEditorSpecGroup } from './editor-model'
  import { createEmptySpecGroup, createEmptySpecValue } from './sku-matrix'

  interface Props {
    modelValue: ProductEditorSpecGroup[]
    disabled?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: ProductEditorSpecGroup[]): void
  }

  const props = withDefaults(defineProps<Props>(), {
    disabled: false
  })
  const emit = defineEmits<Emits>()

  const imageGroupKey = computed(
    () => props.modelValue.find((group) => group.imageEnabled)?.groupKey || ''
  )

  const copyGroups = () =>
    props.modelValue.map((group) => ({
      ...group,
      values: group.values.map((value) => ({ ...value }))
    }))

  const commit = (groups: ProductEditorSpecGroup[]) => {
    groups.forEach((group, groupIndex) => {
      group.sortOrder = groupIndex
      group.values.forEach((value, valueIndex) => {
        value.sortOrder = valueIndex
      })
    })
    emit('update:modelValue', groups)
  }

  const addGroup = () => {
    const groups = copyGroups()
    const group = createEmptySpecGroup(groups.length)
    group.imageEnabled = groups.length === 0
    groups.push(group)
    commit(groups)
  }

  const removeGroup = (groupIndex: number) => {
    const groups = copyGroups()
    const removedImageGroup = groups[groupIndex]?.imageEnabled
    groups.splice(groupIndex, 1)
    if (removedImageGroup && groups.length) groups[0].imageEnabled = true
    commit(groups)
  }

  const updateGroupName = (groupIndex: number, value: string) => {
    const groups = copyGroups()
    groups[groupIndex].name = value
    commit(groups)
  }

  const selectImageGroup = (groupKey: string) => {
    const groups = copyGroups()
    groups.forEach((group) => {
      group.imageEnabled = group.groupKey === groupKey
    })
    commit(groups)
  }

  const addValue = (groupIndex: number) => {
    const groups = copyGroups()
    groups[groupIndex].values.push(createEmptySpecValue(groups[groupIndex].values.length))
    commit(groups)
  }

  const removeValue = (groupIndex: number, valueIndex: number) => {
    const groups = copyGroups()
    groups[groupIndex].values.splice(valueIndex, 1)
    commit(groups)
  }

  const updateValueName = (groupIndex: number, valueIndex: number, value: string) => {
    const groups = copyGroups()
    groups[groupIndex].values[valueIndex].valueName = value
    commit(groups)
  }

  const updateValueImage = (
    groupIndex: number,
    valueIndex: number,
    asset: Api.Common.AssetValue
  ) => {
    const groups = copyGroups()
    groups[groupIndex].values[valueIndex].image = asset.url
    groups[groupIndex].values[valueIndex].imageFileId = asset.fileId
    commit(groups)
  }
</script>

<style scoped lang="scss">
  .spec-tree-editor {
    display: grid;
    gap: 16px;
  }

  .spec-tree-editor__empty {
    background: var(--el-fill-color-lighter);
    border: 1px dashed var(--el-border-color);
    border-radius: 10px;
  }

  .spec-group {
    overflow: hidden;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color);
    border-radius: 10px;
  }

  .spec-group__header {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
    padding: 14px 16px;
    background: var(--el-fill-color-light);
  }

  .spec-group__title {
    display: grid;
    flex: 1;
    grid-template-columns: 28px minmax(240px, 440px);
    gap: 10px;
    align-items: center;
  }

  .spec-tree-editor__index {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    font-weight: 600;
    color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
    border-radius: 50%;
  }

  .spec-group__actions {
    display: flex;
    gap: 10px;
    align-items: center;
  }

  .spec-values {
    display: grid;
    gap: 12px;
    padding: 16px 18px 18px 54px;
  }

  .spec-value {
    display: grid;
    gap: 12px;
    padding-bottom: 12px;
    border-bottom: 1px dashed var(--el-border-color-lighter);
  }

  .spec-value__field {
    display: grid;
    grid-template-columns: 8px minmax(200px, 400px) auto;
    gap: 10px;
    align-items: center;
    justify-content: start;
  }

  .spec-value__dot {
    width: 6px;
    height: 6px;
    background: var(--el-color-primary-light-3);
    border-radius: 50%;
  }

  .spec-value__image {
    max-width: 620px;
    padding-left: 18px;
  }

  .spec-value__image-hint {
    margin-top: 6px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .spec-tree-editor__add {
    justify-self: start;
  }

  @media (width <= 768px) {
    .spec-group__header,
    .spec-group__actions {
      flex-direction: column;
      align-items: flex-start;
    }

    .spec-group__title {
      grid-template-columns: 28px minmax(0, 1fr);
      width: 100%;
    }

    .spec-values {
      padding-left: 16px;
    }

    .spec-value__field {
      grid-template-columns: 8px minmax(0, 1fr) auto;
    }
  }
</style>
