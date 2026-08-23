<template>
  <div class="merchant-compliance-page art-full-height">
    <ElAlert
      title="各项资质字段均可留空保存并发布；已填写的字段仅做基础格式校验。留空的字段不会在小程序公示页展示，请尽量填写真实证照信息。"
      type="warning"
      :closable="false"
      show-icon
    />

    <ElCard class="summary-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">当前对外公示</div>
            <div class="card-description">小程序只能读取当前已发布版本，草稿不会对用户可见。</div>
          </div>
          <div class="header-actions">
            <ElButton @click="loadHistory">刷新</ElButton>
            <ElButton
              type="primary"
              v-auth="'compliance:merchant:write'"
              @click="openDraft(currentPublished)"
            >
              {{ currentPublished ? '基于当前版本新建草稿' : '新建草稿' }}
            </ElButton>
          </div>
        </div>
      </template>

      <ElDescriptions v-if="currentPublished" :column="2" border>
        <ElDescriptionsItem label="主体名称">{{ currentPublished.legalName }}</ElDescriptionsItem>
        <ElDescriptionsItem label="主体类型">{{ currentPublished.entityType }}</ElDescriptionsItem>
        <ElDescriptionsItem label="统一社会信用代码">
          {{ currentPublished.unifiedSocialCreditCode }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="客服电话">
          {{ currentPublished.customerServicePhone }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="投诉电话">{{
          currentPublished.complaintPhone
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="食品资质有效期">
          {{ currentPublished.foodQualificationValidFrom }} 至
          {{ currentPublished.foodQualificationValidUntil }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="经营地址" :span="2">
          {{ currentPublished.businessAddress }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="营业执照原件">
          <ElImage
            class="qualification-image"
            :src="currentPublished.businessLicenseUrl"
            :preview-src-list="[currentPublished.businessLicenseUrl]"
            preview-teleported
            fit="cover"
          />
        </ElDescriptionsItem>
        <ElDescriptionsItem :label="currentPublished.foodQualificationType || '食品资质原件'">
          <ElImage
            class="qualification-image"
            :src="currentPublished.foodQualificationUrl"
            :preview-src-list="[currentPublished.foodQualificationUrl]"
            preview-teleported
            fit="cover"
          />
        </ElDescriptionsItem>
      </ElDescriptions>
      <ElEmpty v-else description="尚未发布商家资质；正式发布前，小程序公示页保持空状态" />
    </ElCard>

    <ElCard class="history-card">
      <template #header>
        <div class="card-title">版本历史</div>
      </template>
      <ElTable :data="history" row-key="id" v-loading="loading">
        <ElTableColumn prop="revisionNo" label="修订号" width="100" />
        <ElTableColumn prop="legalName" label="主体名称" min-width="180" show-overflow-tooltip />
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            <ElTag :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="创建时间" width="180">
          <template #default="{ row }">{{ formatTimestamp(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="发布时间" width="180">
          <template #default="{ row }">{{ formatTimestamp(row.publishedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <ElButton
              v-if="row.status === 'DRAFT'"
              link
              type="primary"
              v-auth="'compliance:merchant:write'"
              :loading="publishingId === row.id"
              @click="handlePublish(row)"
            >
              发布
            </ElButton>
            <ElButton v-else link @click="openPreview(row)">查看</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDrawer v-model="drawerVisible" title="新建商家资质草稿" size="720px" destroy-on-close>
      <ElAlert
        title="保存会生成一个不可变草稿版本；发布前请逐项核对原件和有效期。"
        type="info"
        :closable="false"
        show-icon
      />
      <ElForm ref="formRef" class="draft-form" :model="form" label-width="132px">
        <ElDivider content-position="left">经营主体</ElDivider>
        <ElFormItem label="主体名称">
          <ElInput v-model="form.legalName" maxlength="160" />
        </ElFormItem>
        <ElFormItem label="主体类型">
          <ElInput v-model="form.entityType" maxlength="32" placeholder="按真实证照填写" />
        </ElFormItem>
        <ElFormItem label="统一社会信用代码">
          <ElInput
            v-model="form.unifiedSocialCreditCode"
            maxlength="18"
            placeholder="18 位数字或大写字母"
            @input="normalizeCreditCode"
          />
        </ElFormItem>
        <ElFormItem label="经营地址">
          <ElInput v-model="form.businessAddress" maxlength="512" type="textarea" :rows="2" />
        </ElFormItem>
        <ElFormItem label="客服电话">
          <ElInput v-model="form.customerServicePhone" maxlength="32" />
        </ElFormItem>
        <ElFormItem label="投诉电话">
          <ElInput v-model="form.complaintPhone" maxlength="32" />
        </ElFormItem>
        <ElFormItem label="营业执照原件">
          <AssetPicker v-model="businessLicenseAsset" media-kind="IMAGE" />
        </ElFormItem>

        <ElDivider content-position="left">食品经营资质</ElDivider>
        <ElFormItem label="资质类型">
          <ElInput v-model="form.foodQualificationType" maxlength="40" />
        </ElFormItem>
        <ElFormItem label="资质编号">
          <ElInput v-model="form.foodQualificationNumber" maxlength="96" />
        </ElFormItem>
        <ElFormItem label="资质原件">
          <AssetPicker v-model="foodQualificationAsset" media-kind="IMAGE" />
        </ElFormItem>
        <ElFormItem label="有效期">
          <ElDatePicker
            v-model="qualificationDateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="生效日期"
            end-placeholder="到期日期"
            style="width: 100%"
          />
        </ElFormItem>
      </ElForm>

      <template #footer>
        <ElButton @click="drawerVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveDraft">保存不可变草稿</ElButton>
      </template>
    </ElDrawer>

    <ElDialog v-model="previewVisible" title="资质版本详情" width="720px">
      <ElDescriptions v-if="previewRow" :column="1" border>
        <ElDescriptionsItem label="主体名称">{{ previewRow.legalName || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="统一社会信用代码">
          {{ previewRow.unifiedSocialCreditCode || '-' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="经营地址">{{
          previewRow.businessAddress || '-'
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="客服电话">
          {{ previewRow.customerServicePhone || '-' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="投诉电话">{{
          previewRow.complaintPhone || '-'
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="食品资质">
          {{ previewRow.foodQualificationType || '-' }} /
          {{ previewRow.foodQualificationNumber || '-' }}
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import {
    createMerchantPublicationDraft,
    fetchMerchantPublicationHistory,
    publishMerchantPublication
  } from '@/api/compliance'

  defineOptions({ name: 'MerchantCompliance' })

  const emptyDraft = (): Api.Compliance.MerchantPublicationDraft => ({
    legalName: '',
    entityType: '',
    unifiedSocialCreditCode: '',
    businessAddress: '',
    customerServicePhone: '',
    complaintPhone: '',
    businessLicenseAssetId: null,
    foodQualificationType: '',
    foodQualificationNumber: '',
    foodQualificationAssetId: null,
    foodQualificationValidFrom: null,
    foodQualificationValidUntil: null
  })

  const loading = ref(false)
  const saving = ref(false)
  const publishingId = ref<Api.Compliance.Identifier | null>(null)
  const drawerVisible = ref(false)
  const previewVisible = ref(false)
  const previewRow = ref<Api.Compliance.MerchantPublication | null>(null)
  const history = ref<Api.Compliance.MerchantPublication[]>([])
  const form = reactive<Api.Compliance.MerchantPublicationDraft>(emptyDraft())
  const businessLicenseUrl = ref('')
  const foodQualificationUrl = ref('')

  const currentPublished = computed(
    () => history.value.find((item) => item.status === 'PUBLISHED') || null
  )

  const businessLicenseAsset = computed<Api.Common.AssetValue>({
    get: () => ({ fileId: form.businessLicenseAssetId, url: businessLicenseUrl.value }),
    set: (value) => {
      form.businessLicenseAssetId = value.fileId
      businessLicenseUrl.value = value.url
    }
  })

  const foodQualificationAsset = computed<Api.Common.AssetValue>({
    get: () => ({ fileId: form.foodQualificationAssetId, url: foodQualificationUrl.value }),
    set: (value) => {
      form.foodQualificationAssetId = value.fileId
      foodQualificationUrl.value = value.url
    }
  })

  const qualificationDateRange = computed<string[]>({
    get: () =>
      form.foodQualificationValidFrom && form.foodQualificationValidUntil
        ? [form.foodQualificationValidFrom, form.foodQualificationValidUntil]
        : [],
    set: (value: string[]) => {
      form.foodQualificationValidFrom = value?.[0] || null
      form.foodQualificationValidUntil = value?.[1] || null
    }
  })

  const loadHistory = async () => {
    loading.value = true
    try {
      history.value = await fetchMerchantPublicationHistory()
    } finally {
      loading.value = false
    }
  }

  const openDraft = (source?: Api.Compliance.MerchantPublication | null) => {
    Object.assign(form, emptyDraft(), source || {})
    businessLicenseUrl.value = source?.businessLicenseUrl || ''
    foodQualificationUrl.value = source?.foodQualificationUrl || ''
    drawerVisible.value = true
  }

  const saveDraft = async () => {
    saving.value = true
    try {
      await createMerchantPublicationDraft({
        ...form,
        legalName: form.legalName.trim(),
        entityType: form.entityType.trim(),
        unifiedSocialCreditCode: form.unifiedSocialCreditCode.trim().toUpperCase(),
        businessAddress: form.businessAddress.trim(),
        customerServicePhone: form.customerServicePhone.trim(),
        complaintPhone: form.complaintPhone.trim(),
        foodQualificationType: form.foodQualificationType.trim(),
        foodQualificationNumber: form.foodQualificationNumber.trim()
      })
      drawerVisible.value = false
      await loadHistory()
    } finally {
      saving.value = false
    }
  }

  const handlePublish = async (row: Api.Compliance.MerchantPublication) => {
    await ElMessageBox.confirm(
      `发布修订 #${row.revisionNo} 后，它将立即替换小程序当前公示。请确认已填写的内容与原件一致；留空的字段不会在小程序展示。`,
      '发布商家资质',
      { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '继续检查' }
    )
    publishingId.value = row.id
    try {
      await publishMerchantPublication(row.id)
      await loadHistory()
    } finally {
      publishingId.value = null
    }
  }

  const openPreview = (row: Api.Compliance.MerchantPublication) => {
    previewRow.value = row
    previewVisible.value = true
  }

  const normalizeCreditCode = (value: string) => {
    form.unifiedSocialCreditCode = value.toUpperCase().replace(/[^0-9A-Z]/g, '')
  }

  const formatTimestamp = (value?: string | null) =>
    value ? value.replace('T', ' ').replace(/\.\d+$/, '') : '-'

  const statusLabel = (status: Api.Compliance.PublicationStatus) =>
    ({ DRAFT: '草稿', PUBLISHED: '当前发布', SUPERSEDED: '历史版本' })[status]

  const statusTagType = (status: Api.Compliance.PublicationStatus) =>
    ({ DRAFT: 'warning', PUBLISHED: 'success', SUPERSEDED: 'info' })[status] as
      | 'warning'
      | 'success'
      | 'info'

  onMounted(loadHistory)
</script>

<style scoped lang="scss">
  .merchant-compliance-page {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }

  .header-actions {
    display: flex;
    gap: 8px;
  }

  .card-title {
    font-size: 16px;
    font-weight: 600;
  }

  .card-description {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .qualification-image {
    width: 120px;
    height: 86px;
    border-radius: 6px;
  }

  .draft-form {
    margin-top: 20px;
    padding-right: 12px;
  }

  @media (width <= 768px) {
    .card-header {
      align-items: flex-start;
      flex-direction: column;
    }
  }
</style>
