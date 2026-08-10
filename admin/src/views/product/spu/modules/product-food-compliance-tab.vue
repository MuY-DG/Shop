<template>
  <div class="food-compliance-tab">
    <ElAlert
      title="食品信息必须来自真实标签与资质原件；禁止填写示例、占位或推测数据。历史商品默认为未分类，未完成分类前后端会阻止重新上架。"
      type="warning"
      :closable="false"
      show-icon
    />

    <ElAlert
      v-if="lockedOnSale"
      title="在售商品的食品分类与标签已锁定；如需修改，请先下架商品，核对并保存真实信息后再重新上架。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElCard shadow="never">
      <ElForm label-position="top">
        <ElFormItem label="商品合规分类" required>
          <ElRadioGroup
            :model-value="modelValue.complianceType"
            :disabled="disabled"
            @update:model-value="patch({ complianceType: $event as ProductComplianceType })"
          >
            <ElRadioButton value="UNCLASSIFIED">未分类</ElRadioButton>
            <ElRadioButton value="FOOD">食品</ElRadioButton>
            <ElRadioButton value="NON_FOOD">非食品</ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>

        <ElAlert
          v-if="modelValue.complianceType === 'UNCLASSIFIED'"
          title="未分类商品可以保存草稿，但不能上架。请按商品真实属性选择食品或非食品。"
          type="info"
          :closable="false"
          show-icon
        />

        <template v-if="modelValue.complianceType === 'FOOD'">
          <ElDivider content-position="left">标签结构化信息</ElDivider>
          <div class="food-compliance-grid">
            <ElFormItem label="食品名称" required>
              <ElInput
                :model-value="modelValue.foodName"
                maxlength="160"
                :disabled="disabled"
                @update:model-value="patch({ foodName: $event })"
              />
            </ElFormItem>
            <ElFormItem label="产地" required>
              <ElInput
                :model-value="modelValue.origin"
                maxlength="160"
                :disabled="disabled"
                @update:model-value="patch({ origin: $event })"
              />
            </ElFormItem>
            <ElFormItem label="生产者名称" required>
              <ElInput
                :model-value="modelValue.manufacturerName"
                maxlength="160"
                :disabled="disabled"
                @update:model-value="patch({ manufacturerName: $event })"
              />
            </ElFormItem>
            <ElFormItem label="食品生产许可证编号" required>
              <ElInput
                :model-value="modelValue.productionLicenseNumber"
                maxlength="96"
                :disabled="disabled"
                @update:model-value="patch({ productionLicenseNumber: $event })"
              />
            </ElFormItem>
          </div>
          <ElFormItem label="生产者地址" required>
            <ElInput
              :model-value="modelValue.manufacturerAddress"
              type="textarea"
              :rows="2"
              maxlength="512"
              :disabled="disabled"
              @update:model-value="patch({ manufacturerAddress: $event })"
            />
          </ElFormItem>
          <ElFormItem label="配料表" required>
            <ElInput
              :model-value="modelValue.ingredients"
              type="textarea"
              :rows="3"
              maxlength="3000"
              :disabled="disabled"
              @update:model-value="patch({ ingredients: $event })"
            />
          </ElFormItem>
          <ElFormItem label="过敏原信息">
            <ElInput
              :model-value="modelValue.allergenInformation"
              type="textarea"
              :rows="2"
              maxlength="1000"
              placeholder="仅按真实标签填写；标签未载明时请留空"
              :disabled="disabled"
              @update:model-value="patch({ allergenInformation: $event })"
            />
          </ElFormItem>
          <div class="food-compliance-grid">
            <ElFormItem label="贮存条件" required>
              <ElInput
                :model-value="modelValue.storageConditions"
                maxlength="255"
                :disabled="disabled"
                @update:model-value="patch({ storageConditions: $event })"
              />
            </ElFormItem>
            <ElFormItem label="保质期说明" required>
              <ElInput
                :model-value="modelValue.shelfLifeDescription"
                maxlength="500"
                placeholder="按标签原文填写，例如在规定贮存条件下的期限"
                :disabled="disabled"
                @update:model-value="patch({ shelfLifeDescription: $event })"
              />
            </ElFormItem>
          </div>
          <ElFormItem label="批次与生产日期说明" required>
            <ElInput
              :model-value="modelValue.variableProductionNotice"
              type="textarea"
              :rows="2"
              maxlength="500"
              placeholder="说明以收到商品包装标示为准，不得虚构固定批次或生产日期"
              :disabled="disabled"
              @update:model-value="patch({ variableProductionNotice: $event })"
            />
          </ElFormItem>
          <ElFormItem label="消费提示">
            <ElInput
              :model-value="modelValue.consumerNotice"
              type="textarea"
              :rows="2"
              maxlength="1000"
              :disabled="disabled"
              @update:model-value="patch({ consumerNotice: $event })"
            />
          </ElFormItem>

          <ElDivider content-position="left">食品标签原图</ElDivider>
          <div class="label-assets">
            <div
              v-for="(asset, index) in modelValue.labelAssets"
              :key="`${asset.fileId || 'new'}-${index}`"
              class="label-asset"
            >
              <AssetPicker
                :model-value="{ fileId: asset.fileId, url: asset.url }"
                media-kind="IMAGE"
                :disabled="disabled"
                @change="updateLabelAsset(index, $event)"
              />
              <ElButton type="danger" plain :disabled="disabled" @click="removeLabelAsset(index)">
                移除标签图
              </ElButton>
            </div>
            <ElButton
              v-if="modelValue.labelAssets.length < 12"
              type="primary"
              plain
              :disabled="disabled"
              @click="addLabelAsset"
            >
              添加食品标签图
            </ElButton>
          </div>
        </template>
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import type {
    ProductComplianceType,
    ProductEditorFoodDisclosure,
    ProductEditorSku
  } from './editor-model'
  import { validateFoodDisclosure } from './food-compliance'

  interface Props {
    modelValue: ProductEditorFoodDisclosure
    skus: ProductEditorSku[]
    disabled?: boolean
    lockedOnSale?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: ProductEditorFoodDisclosure): void
  }

  const props = withDefaults(defineProps<Props>(), { disabled: false, lockedOnSale: false })
  const emit = defineEmits<Emits>()

  const patch = (value: Partial<ProductEditorFoodDisclosure>) => {
    emit('update:modelValue', { ...props.modelValue, ...value })
  }

  const addLabelAsset = () => {
    patch({
      labelAssets: [
        ...props.modelValue.labelAssets,
        { fileId: null, url: '', sortOrder: props.modelValue.labelAssets.length }
      ]
    })
  }

  const updateLabelAsset = (index: number, value: Api.Common.AssetValue) => {
    const labelAssets = props.modelValue.labelAssets.map((asset, assetIndex) =>
      assetIndex === index ? { fileId: value.fileId, url: value.url, sortOrder: index } : asset
    )
    patch({ labelAssets })
  }

  const removeLabelAsset = (index: number) => {
    patch({
      labelAssets: props.modelValue.labelAssets
        .filter((_, assetIndex) => assetIndex !== index)
        .map((asset, assetIndex) => ({ ...asset, sortOrder: assetIndex }))
    })
  }

  const validate = async () => {
    const error = validateFoodDisclosure(props.modelValue, props.skus)
    if (error) {
      ElMessage.error(error)
      return false
    }
    return true
  }

  defineExpose({ validate })
</script>

<style scoped lang="scss">
  .food-compliance-tab {
    display: grid;
    gap: 16px;
    max-width: 1100px;
    margin: 0 auto;
  }

  .food-compliance-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 18px;
  }

  .label-assets {
    display: grid;
    gap: 16px;
  }

  .label-asset {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 12px;
    align-items: start;
    padding: 14px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  @media (width <= 760px) {
    .food-compliance-grid,
    .label-asset {
      grid-template-columns: 1fr;
    }
  }
</style>
