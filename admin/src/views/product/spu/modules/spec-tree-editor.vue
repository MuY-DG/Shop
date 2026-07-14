<template>
  <div class="spec-tree-editor">
    <div v-if="!modelValue.length" class="spec-tree-editor__empty">
      暂无商品规格，请点击“添加新规格”
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
          <ElCheckbox
            :model-value="group.imageEnabled"
            :disabled="disabled"
            @change="toggleImageGroup(group.groupKey, Boolean($event))"
          >
            设为规格图
          </ElCheckbox>
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
          <button
            v-if="!disabled"
            type="button"
            class="spec-value__remove"
            aria-label="删除规格值"
            title="删除规格值"
            @click="removeValue(groupIndex, valueIndex)"
          >
            <ElIcon size="14"><Close /></ElIcon>
          </button>
          <div class="spec-value__field">
            <ElInput
              :model-value="value.valueName"
              maxlength="30"
              placeholder="规格值，例如：红色"
              :disabled="disabled"
              @update:model-value="updateValueName(groupIndex, valueIndex, $event)"
            />
          </div>

          <div v-if="group.imageEnabled" class="spec-value__image">
            <CompactAssetField
              :model-value="{ fileId: value.imageFileId, url: value.image }"
              media-kind="IMAGE"
              :disabled="disabled"
              :allow-url="false"
              small
              @change="updateValueImage(groupIndex, valueIndex, $event)"
            />
          </div>
        </div>

        <ElButton
          class="spec-values__add"
          type="primary"
          plain
          :disabled="disabled || group.values.length >= 50"
          @click="addValue(groupIndex)"
        >
          + 添加规格值
        </ElButton>
      </div>
    </div>

    <ElButton
      v-if="showAddButton"
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
  import { Close } from '@element-plus/icons-vue'
  import type { ProductEditorSpecGroup } from './editor-model'
  import { createEmptySpecGroup, createEmptySpecValue } from './sku-matrix'
  import CompactAssetField from './compact-asset-field.vue'

  interface Props {
    modelValue: ProductEditorSpecGroup[]
    disabled?: boolean
    showAddButton?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: ProductEditorSpecGroup[]): void
  }

  const props = withDefaults(defineProps<Props>(), {
    disabled: false,
    showAddButton: true
  })
  const emit = defineEmits<Emits>()

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
    groups.push(group)
    commit(groups)
  }

  const removeGroup = (groupIndex: number) => {
    const groups = copyGroups()
    groups.splice(groupIndex, 1)
    commit(groups)
  }

  const updateGroupName = (groupIndex: number, value: string) => {
    const groups = copyGroups()
    groups[groupIndex].name = value
    commit(groups)
  }

  const toggleImageGroup = (groupKey: string, enabled: boolean) => {
    const groups = copyGroups()
    groups.forEach((group) => {
      group.imageEnabled = enabled && group.groupKey === groupKey
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

  defineExpose({ addGroup })
</script>

<style scoped lang="scss">
  .spec-tree-editor {
    display: grid;
    gap: 16px;
  }

  .spec-tree-editor__empty {
    padding: 16px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
    text-align: center;
    border: 1px dashed var(--el-border-color);
    border-radius: 8px;
  }

  .spec-group {
    overflow: hidden;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
  }

  .spec-group__header {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
    padding: 10px 12px;
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
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: flex-start;
    padding: 12px;
  }

  .spec-value {
    position: relative;
    display: flex;
    flex: 0 0 174px;
    flex-direction: column;
    gap: 7px;
    align-items: center;
    min-width: 0;
    background: transparent;
  }

  .spec-value__field {
    width: 100%;
  }

  .spec-value__remove {
    position: absolute;
    top: -7px;
    right: -7px;
    z-index: 2;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    padding: 0;
    color: #fff;
    cursor: pointer;
    visibility: hidden;
    background: var(--el-color-danger);
    border: 2px solid var(--el-bg-color);
    border-radius: 50%;
    opacity: 0;
    transition: opacity 0.2s ease;

    &:focus-visible {
      visibility: visible;
      outline: none;
      opacity: 1;
    }
  }

  .spec-value:hover .spec-value__remove {
    visibility: visible;
    opacity: 1;
  }

  .spec-value__image {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    min-height: 72px;
  }

  .spec-values__add {
    height: 32px;
    background: transparent;

    &:hover,
    &:focus,
    &:active {
      background: transparent;
    }
  }

  .spec-tree-editor__add {
    align-self: flex-start;
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
      padding: 10px;
    }

    .spec-value {
      flex-basis: 174px;
    }
  }
</style>
