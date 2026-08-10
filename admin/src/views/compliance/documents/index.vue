<template>
  <div class="legal-documents-page art-full-height">
    <ElAlert
      title="法律文档按版本保存且发布后不可修改。请粘贴经过业务或法律审核的正式内容；系统不会提供示例条款。"
      type="warning"
      :closable="false"
      show-icon
    />

    <ElCard class="documents-card">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">法律文档版本</div>
            <div class="card-description">
              当前隐私政策版本会参与小程序登录校验，并留下用户同意内容摘要。
            </div>
          </div>
          <div class="header-actions">
            <ElButton @click="loadDocuments()">刷新</ElButton>
            <ElButton type="primary" v-auth="'compliance:document:write'" @click="openDraft">
              新建{{ activeTypeLabel }}草稿
            </ElButton>
          </div>
        </div>
      </template>

      <ElTabs v-model="activeType" @tab-change="handleTypeChange">
        <ElTabPane
          v-for="option in documentTypes"
          :key="option.value"
          :name="option.value"
          :label="option.label"
        />
      </ElTabs>

      <ElTable :data="documents" row-key="id" v-loading="loading">
        <ElTableColumn prop="version" label="版本" width="150" />
        <ElTableColumn prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            <ElTag :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="内容摘要" min-width="220">
          <template #default="{ row }">
            <ElText class="digest" truncated>{{ row.contentSha256 }}</ElText>
          </template>
        </ElTableColumn>
        <ElTableColumn label="生效时间" width="180">
          <template #default="{ row }">{{ formatTimestamp(row.effectiveAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <ElButton link @click="openPreview(row)">预览</ElButton>
            <ElButton
              v-if="row.status === 'DRAFT'"
              link
              type="primary"
              v-auth="'compliance:document:write'"
              :loading="publishingId === row.id"
              @click="handlePublish(row)"
            >
              发布
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-if="!loading && !documents.length" :description="`暂无${activeTypeLabel}版本`" />
    </ElCard>

    <ElDrawer v-model="drawerVisible" :title="`新建${activeTypeLabel}草稿`" size="760px">
      <ElForm ref="formRef" class="document-form" :model="form" :rules="rules" label-width="90px">
        <ElFormItem label="版本号" prop="version">
          <ElInput
            v-model="form.version"
            maxlength="40"
            placeholder="例如 2026.08.1；发布后不可复用"
          />
        </ElFormItem>
        <ElFormItem label="标题" prop="title">
          <ElInput v-model="form.title" maxlength="160" />
        </ElFormItem>
        <ElFormItem label="生效时间">
          <ElDatePicker
            v-model="form.effectiveAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="留空则发布时立即生效；暂不支持预约发布"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="正文" prop="content">
          <ElInput
            v-model="form.content"
            type="textarea"
            :rows="22"
            maxlength="100000"
            show-word-limit
            resize="vertical"
            placeholder="粘贴已审核的完整正式正文"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="drawerVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveDraft">保存不可变草稿</ElButton>
      </template>
    </ElDrawer>

    <ElDialog v-model="previewVisible" :title="previewDocument?.title || '文档预览'" width="760px">
      <div v-if="previewDocument" class="document-preview">
        <div class="preview-meta">
          <ElTag>{{ previewDocument.version }}</ElTag>
          <span>SHA-256：{{ previewDocument.contentSha256 }}</span>
        </div>
        <pre>{{ previewDocument.content }}</pre>
      </div>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
  import {
    createLegalDocumentDraft,
    fetchLegalDocumentHistory,
    publishLegalDocument
  } from '@/api/compliance'

  defineOptions({ name: 'LegalDocuments' })

  const documentTypes: Array<{ value: Api.Compliance.LegalDocumentType; label: string }> = [
    { value: 'PRIVACY_POLICY', label: '隐私保护指引' },
    { value: 'USER_AGREEMENT', label: '用户协议' },
    { value: 'AFTER_SALE_POLICY', label: '售后政策' }
  ]

  const emptyDraft = (): Api.Compliance.LegalDocumentDraft => ({
    version: '',
    title: '',
    content: '',
    effectiveAt: null
  })

  const activeType = ref<Api.Compliance.LegalDocumentType>('PRIVACY_POLICY')
  const loading = ref(false)
  const saving = ref(false)
  const publishingId = ref<Api.Compliance.Identifier | null>(null)
  const drawerVisible = ref(false)
  const previewVisible = ref(false)
  const previewDocument = ref<Api.Compliance.LegalDocument | null>(null)
  const documents = ref<Api.Compliance.LegalDocument[]>([])
  const formRef = ref<FormInstance>()
  const form = reactive<Api.Compliance.LegalDocumentDraft>(emptyDraft())

  const activeTypeLabel = computed(
    () => documentTypes.find((item) => item.value === activeType.value)?.label || '法律文档'
  )

  const rules: FormRules<Api.Compliance.LegalDocumentDraft> = {
    version: [
      { required: true, message: '请输入版本号', trigger: 'blur' },
      {
        pattern: /^[0-9A-Za-z._-]{1,40}$/,
        message: '版本号只能包含数字、字母、点、下划线和短横线',
        trigger: 'blur'
      }
    ],
    title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
    content: [{ required: true, message: '请粘贴经审核的正式正文', trigger: 'blur' }]
  }

  const loadDocuments = async () => {
    loading.value = true
    try {
      documents.value = await fetchLegalDocumentHistory(activeType.value)
    } finally {
      loading.value = false
    }
  }

  const handleTypeChange = () => {
    loadDocuments()
  }

  const openDraft = () => {
    Object.assign(form, emptyDraft())
    drawerVisible.value = true
  }

  const saveDraft = async () => {
    if (!formRef.value) return
    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch(() => false)
    if (!valid) return
    saving.value = true
    try {
      await createLegalDocumentDraft(activeType.value, {
        version: form.version.trim(),
        title: form.title.trim(),
        content: form.content.replace(/\r\n/g, '\n').trim(),
        effectiveAt: form.effectiveAt || null
      })
      drawerVisible.value = false
      await loadDocuments()
    } finally {
      saving.value = false
    }
  }

  const handlePublish = async (row: Api.Compliance.LegalDocument) => {
    const consentWarning =
      activeType.value === 'PRIVACY_POLICY'
        ? '发布后，小程序登录只接受此版本；旧页面上的版本会被后端拒绝并要求重新加载。'
        : '发布后，小程序将只展示此版本。'
    await ElMessageBox.confirm(
      `${consentWarning} 请确认标题、正文、生效时间和内容摘要均已人工复核。`,
      `发布${activeTypeLabel.value}`,
      { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '继续检查' }
    )
    publishingId.value = row.id
    try {
      await publishLegalDocument(row.id)
      await loadDocuments()
    } finally {
      publishingId.value = null
    }
  }

  const openPreview = (row: Api.Compliance.LegalDocument) => {
    previewDocument.value = row
    previewVisible.value = true
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

  onMounted(loadDocuments)
</script>

<style scoped lang="scss">
  .legal-documents-page {
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

  .card-description,
  .preview-meta {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .digest {
    display: block;
    max-width: 240px;
    font-family: monospace;
  }

  .document-form {
    padding: 12px 16px 0 0;
  }

  .preview-meta {
    display: flex;
    align-items: center;
    gap: 12px;
    overflow-wrap: anywhere;
  }

  .document-preview pre {
    max-height: 60vh;
    padding: 18px;
    margin-top: 16px;
    overflow: auto;
    font: inherit;
    line-height: 1.8;
    white-space: pre-wrap;
    word-break: break-word;
    background: var(--el-fill-color-light);
    border-radius: 8px;
  }

  @media (width <= 768px) {
    .card-header {
      align-items: flex-start;
      flex-direction: column;
    }
  }
</style>
