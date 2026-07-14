<template>
  <ElDialog
    :model-value="visible"
    :title="isEdit ? '编辑规格模板' : '新增规格模板'"
    width="900px"
    align-center
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="emit('update:visible', $event)"
    @closed="resetForm"
  >
    <div v-loading="loading" class="template-editor">
      <ElAlert
        v-if="isEdit"
        title="为避免已使用模板的商品规格失效，编辑时只能修改模板名称、规格名称和规格值，不能新增或删除规格名和值。"
        type="warning"
        :closable="false"
        show-icon
        class="edit-alert"
      />

      <ElForm
        ref="formRef"
        :model="formData"
        label-position="left"
        label-width="128px"
        class="template-form"
      >
        <ElFormItem
          class="template-name-item"
          label="规格模板名称"
          prop="name"
          :rules="[
            { required: true, message: '请输入规格模板名称', trigger: 'blur' },
            { max: 64, message: '规格模板名称不能超过 64 个字符', trigger: 'blur' }
          ]"
        >
          <ElInput
            v-model="formData.name"
            maxlength="64"
            show-word-limit
            placeholder="例如：服装颜色尺码"
          />
        </ElFormItem>

        <div class="section-heading">
          <div>
            <div class="section-title">商品规格</div>
            <div class="section-description">
              最多 10 个规格，每个规格最多 50 个规格值；必须且只能选择一个图片规格。
            </div>
          </div>
        </div>

        <div class="spec-tree">
          <section
            v-for="(group, groupIndex) in formData.groups"
            :key="group.clientKey"
            class="spec-group-card"
          >
            <button
              v-if="!isEdit"
              type="button"
              class="group-remove"
              aria-label="删除规格"
              title="删除规格"
              @click="removeGroup(groupIndex)"
            >
              <ElIcon size="14"><Close /></ElIcon>
            </button>

            <div class="group-header">
              <div class="group-number">规格 {{ groupIndex + 1 }}</div>
              <div class="group-actions">
                <ElCheckbox
                  :model-value="group.imageEnabled"
                  :disabled="isEdit"
                  @change="setImageGroup(groupIndex, Boolean($event))"
                >
                  设为规格图
                </ElCheckbox>
              </div>
            </div>

            <div class="group-body">
              <ElFormItem
                class="group-name-item"
                label="规格名称"
                :prop="`groups.${groupIndex}.name`"
                :rules="[
                  { required: true, message: '请输入规格名称', trigger: 'blur' },
                  { max: 30, message: '规格名称不能超过 30 个字符', trigger: 'blur' }
                ]"
              >
                <ElInput
                  v-model="group.name"
                  maxlength="30"
                  show-word-limit
                  placeholder="例如：颜色"
                />
              </ElFormItem>

              <div class="value-field">
                <div class="value-label">
                  <span class="value-title">规格值</span>
                  <span class="value-count">{{ group.values.length }}/50</span>
                </div>
                <div class="value-list">
                  <div
                    v-for="(value, valueIndex) in group.values"
                    :key="value.clientKey"
                    class="value-row"
                  >
                    <button
                      v-if="!isEdit"
                      type="button"
                      class="value-remove"
                      aria-label="删除规格值"
                      title="删除规格值"
                      @click="removeValue(groupIndex, valueIndex)"
                    >
                      <ElIcon size="14"><Close /></ElIcon>
                    </button>
                    <ElFormItem
                      class="value-form-item"
                      label-width="0"
                      :prop="`groups.${groupIndex}.values.${valueIndex}.valueName`"
                      :rules="[
                        { required: true, message: '请输入规格值', trigger: 'blur' },
                        { max: 30, message: '规格值不能超过 30 个字符', trigger: 'blur' }
                      ]"
                    >
                      <ElInput
                        v-model="value.valueName"
                        maxlength="30"
                        show-word-limit
                        placeholder="例如：红色"
                      />
                    </ElFormItem>
                  </div>
                  <ElButton
                    v-if="!isEdit"
                    class="value-add"
                    type="primary"
                    plain
                    :disabled="group.values.length >= 50"
                    @click="addValue(groupIndex)"
                  >
                    + 添加规格值
                  </ElButton>
                </div>
              </div>
            </div>
          </section>
        </div>

        <ElButton
          v-if="!isEdit"
          class="group-add"
          plain
          :disabled="formData.groups.length >= 10"
          @click="addGroup"
        >
          + 添加新规格
        </ElButton>
      </ElForm>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <ElButton @click="emit('update:visible', false)">取消</ElButton>
        <ElButton type="primary" :loading="submitting" :disabled="loading" @click="submit">
          保存
        </ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { computed, nextTick, reactive, ref, watch } from 'vue'
  import { Close } from '@element-plus/icons-vue'
  import { ElMessage, type FormInstance } from 'element-plus'
  import {
    createProductSpecTemplate,
    fetchProductSpecTemplateDetail,
    updateProductSpecTemplate
  } from '@/api/product'

  defineOptions({ name: 'ProductSpecTemplateDialog' })

  interface Props {
    visible: boolean
    templateId?: number | null
  }

  interface Emits {
    (event: 'update:visible', value: boolean): void
    (event: 'success', mode: 'create' | 'update'): void
  }

  interface EditableValue extends Api.Product.SpecTemplateValue {
    clientKey: string
  }

  interface EditableGroup extends Omit<Api.Product.SpecTemplateGroup, 'values'> {
    clientKey: string
    values: EditableValue[]
  }

  interface TemplateFormModel {
    name: string
    groups: EditableGroup[]
  }

  const props = withDefaults(defineProps<Props>(), {
    templateId: null
  })
  const emit = defineEmits<Emits>()

  const formRef = ref<FormInstance>()
  const loading = ref(false)
  const submitting = ref(false)
  let clientKeySeed = 0

  const nextClientKey = (prefix: string) => `${prefix}-${Date.now()}-${clientKeySeed++}`

  const createValue = (): EditableValue => ({
    clientKey: nextClientKey('value'),
    valueKey: '',
    valueName: '',
    sortOrder: 0
  })

  const createGroup = (imageEnabled = false): EditableGroup => ({
    clientKey: nextClientKey('group'),
    groupKey: '',
    name: '',
    imageEnabled,
    sortOrder: 0,
    values: [createValue()]
  })

  const defaultForm = (): TemplateFormModel => ({
    name: '',
    groups: [createGroup(true)]
  })

  const formData = reactive<TemplateFormModel>(defaultForm())
  const isEdit = computed(() => props.templateId != null)

  const replaceForm = (value: TemplateFormModel) => {
    formData.name = value.name
    formData.groups.splice(0, formData.groups.length, ...value.groups)
  }

  const resetForm = () => {
    replaceForm(defaultForm())
    formRef.value?.clearValidate()
  }

  const loadTemplate = async () => {
    resetForm()
    if (!props.templateId) {
      await nextTick()
      formRef.value?.clearValidate()
      return
    }

    loading.value = true
    try {
      const detail = await fetchProductSpecTemplateDetail(props.templateId)
      replaceForm({
        name: detail.name,
        groups: detail.groups.map((group) => ({
          ...group,
          clientKey: nextClientKey('group'),
          values: group.values.map((value) => ({
            ...value,
            clientKey: nextClientKey('value')
          }))
        }))
      })
      await nextTick()
      formRef.value?.clearValidate()
    } finally {
      loading.value = false
    }
  }

  watch(
    () => [props.visible, props.templateId] as const,
    ([visible]) => {
      if (visible) loadTemplate()
    },
    { immediate: true }
  )

  const addGroup = () => {
    if (formData.groups.length >= 10) return
    formData.groups.push(createGroup(formData.groups.length === 0))
  }

  const removeGroup = (groupIndex: number) => {
    if (formData.groups.length === 1) {
      ElMessage.warning('规格模板至少需要一个规格')
      return
    }

    const removedImageGroup = formData.groups[groupIndex]?.imageEnabled
    formData.groups.splice(groupIndex, 1)
    if (removedImageGroup && formData.groups.length) {
      formData.groups[0].imageEnabled = true
    }
  }

  const addValue = (groupIndex: number) => {
    const group = formData.groups[groupIndex]
    if (!group || group.values.length >= 50) return
    group.values.push({ ...createValue(), sortOrder: group.values.length })
  }

  const removeValue = (groupIndex: number, valueIndex: number) => {
    const group = formData.groups[groupIndex]
    if (!group) return
    if (group.values.length === 1) {
      ElMessage.warning('每个规格至少需要一个规格值')
      return
    }
    group.values.splice(valueIndex, 1)
  }

  const setImageGroup = (groupIndex: number, checked: boolean) => {
    if (!checked) {
      ElMessage.info('必须保留一个图片规格；如需切换，请直接勾选其他规格')
      return
    }
    formData.groups.forEach((group, index) => {
      group.imageEnabled = index === groupIndex
    })
  }

  const validateBusinessRules = (): boolean => {
    if (formData.groups.length < 1 || formData.groups.length > 10) {
      ElMessage.error('规格模板需要包含 1 至 10 个规格')
      return false
    }

    if (formData.groups.filter((group) => group.imageEnabled).length !== 1) {
      ElMessage.error('必须且只能选择一个图片规格')
      return false
    }

    const groupNames = formData.groups.map((group) => group.name.trim())
    if (new Set(groupNames).size !== groupNames.length) {
      ElMessage.error('规格名称不能重复')
      return false
    }

    for (const group of formData.groups) {
      if (group.values.length < 1 || group.values.length > 50) {
        ElMessage.error(`规格“${group.name.trim() || '未命名'}”需要包含 1 至 50 个规格值`)
        return false
      }
      const valueNames = group.values.map((value) => value.valueName.trim())
      if (new Set(valueNames).size !== valueNames.length) {
        ElMessage.error(`规格“${group.name.trim() || '未命名'}”的规格值不能重复`)
        return false
      }
    }
    return true
  }

  const buildPayload = (): Api.Product.SpecTemplateForm => ({
    name: formData.name.trim(),
    groups: formData.groups.map((group, groupIndex) => ({
      id: group.id,
      groupKey: group.groupKey.trim(),
      name: group.name.trim(),
      imageEnabled: group.imageEnabled,
      sortOrder: group.sortOrder ?? groupIndex,
      values: group.values.map((value, valueIndex) => ({
        id: value.id,
        valueKey: value.valueKey.trim(),
        valueName: value.valueName.trim(),
        sortOrder: value.sortOrder ?? valueIndex
      }))
    }))
  })

  const submit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch(() => false)
    if (!valid || !validateBusinessRules()) return

    submitting.value = true
    try {
      const payload = buildPayload()
      if (props.templateId) {
        await updateProductSpecTemplate(props.templateId, payload)
        emit('success', 'update')
      } else {
        await createProductSpecTemplate(payload)
        emit('success', 'create')
      }
      emit('update:visible', false)
    } finally {
      submitting.value = false
    }
  }
</script>

<style scoped lang="scss">
  .template-editor {
    min-height: 240px;
  }

  .edit-alert {
    margin-bottom: 14px;
  }

  .template-form,
  .section-heading,
  .group-header,
  .value-field {
    display: flex;
    align-items: center;
  }

  .template-form {
    display: block;

    :deep(.el-form-item__label) {
      justify-content: flex-start;
      text-align: left;
      white-space: nowrap;
    }
  }

  .template-name-item {
    margin-bottom: 14px;

    :deep(.el-form-item__content) {
      max-width: 520px;
    }
  }

  .section-heading {
    justify-content: space-between;
    margin: 14px 0 10px;
  }

  .section-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .section-description {
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .spec-tree {
    display: grid;
    gap: 12px;
  }

  .spec-group-card {
    position: relative;
    background: var(--el-fill-color-blank);
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
  }

  .group-header {
    justify-content: space-between;
    min-height: 42px;
    padding: 0 12px;
    background: var(--el-fill-color-light);
    border-bottom: 1px solid var(--el-border-color-lighter);
    border-radius: 8px 8px 0 0;
  }

  .group-number {
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .value-title {
    font-weight: 400;
    color: var(--el-text-color-regular);
  }

  .group-actions {
    display: flex;
    gap: 12px;
    align-items: center;
  }

  .group-body {
    padding: 12px 12px 12px 0;
  }

  .group-name-item {
    margin-bottom: 10px;

    :deep(.el-form-item__content) {
      max-width: 520px;
    }
  }

  .value-field {
    align-items: flex-start;
  }

  .value-label {
    box-sizing: border-box;
    flex: 0 0 128px;
    padding-right: 12px;
    line-height: 32px;
    text-align: left;

    &::before {
      margin-right: 4px;
      color: transparent;
      content: '*';
    }
  }

  .value-count {
    margin-left: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .value-list {
    display: flex;
    flex: 1;
    flex-wrap: wrap;
    gap: 10px;
    min-width: 0;
  }

  .value-row {
    position: relative;
    flex: 0 0 190px;
    min-width: 0;
  }

  .value-form-item {
    width: 100%;
    margin-bottom: 0;
  }

  .group-remove,
  .value-remove {
    position: absolute;
    z-index: 3;
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

  .group-remove {
    top: -8px;
    right: -8px;
  }

  .value-remove {
    top: -8px;
    right: -8px;
  }

  .spec-group-card:hover > .group-remove,
  .value-row:hover > .value-remove {
    visibility: visible;
    opacity: 1;
  }

  .value-add,
  .group-add {
    height: 32px;
    background: transparent;

    &:hover,
    &:focus,
    &:active {
      background: transparent;
    }
  }

  .group-add {
    margin: 12px 0 0 128px;
  }

  .dialog-footer {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
  }

  @media (width <= 768px) {
    .group-header {
      flex-direction: column;
      align-items: flex-start;
      padding: 12px 16px;
    }

    .template-form {
      :deep(.el-form-item) {
        display: block;
      }

      :deep(.el-form-item__label) {
        display: block;
        width: auto !important;
        margin-bottom: 6px;
        text-align: left;
      }
    }

    .value-field {
      display: block;
    }

    .value-label {
      width: auto;
      padding-right: 0;
      margin-bottom: 6px;
      text-align: left;
    }

    .value-row {
      flex-basis: min(190px, 100%);
    }

    .group-add {
      margin-left: 0;
    }
  }

  @media (hover: none) {
    .group-remove,
    .value-remove {
      visibility: visible;
      opacity: 1;
    }
  }
</style>
