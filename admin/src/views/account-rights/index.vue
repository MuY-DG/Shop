<template>
  <div class="account-rights-page art-full-height">
    <ElAlert
      title="账户注销与个人信息请求必须逐项人工核验。系统不会预填保留理由；请只记录真实处理依据和实际保留的数据类别。"
      type="warning"
      :closable="false"
      show-icon
    />

    <ElCard shadow="never">
      <div class="filters">
        <ElInput v-model="userIdInput" clearable placeholder="用户 ID" inputmode="numeric" />
        <ElSelect v-model="filters.requestType" clearable placeholder="请求类型">
          <ElOption
            v-for="item in requestTypes"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
        <ElSelect v-model="filters.status" clearable placeholder="处理状态">
          <ElOption
            v-for="item in requestStatuses"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
        <ElButton type="primary" @click="search">查询</ElButton>
        <ElButton @click="reset">重置</ElButton>
      </div>

      <ElTable v-loading="loading" :data="rows" row-key="id">
        <ElTableColumn prop="id" label="申请 ID" width="110" />
        <ElTableColumn label="用户" min-width="180">
          <template #default="{ row }">
            <div>{{ row.userNickname || '未设置昵称' }}</div>
            <small>ID {{ row.userId }} · {{ row.userStatus }}</small>
          </template>
        </ElTableColumn>
        <ElTableColumn label="请求类型" min-width="170">
          <template #default="{ row }">{{ requestTypeLabel(row.requestType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            <ElTag :type="requestStatusTone(row.status)">
              {{ requestStatusLabel(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="身份核验" width="180">
          <template #default="{ row }">{{ formatTime(row.identityVerifiedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="申请时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" min-width="260" fixed="right">
          <template #default="{ row }">
            <ElButton link type="primary" @click="openDetail(row.id)">详情</ElButton>
            <ElButton
              v-for="action in availableAdminActions(row.status)"
              :key="action"
              v-auth="'account-rights:manage'"
              link
              :type="action === 'reject' ? 'danger' : 'primary'"
              @click="openAction(row, action)"
            >
              {{ actionLabel(action) }}
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="pagination">
        <ElPagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :total="page.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadRows"
          @size-change="handleSizeChange"
        />
      </div>
    </ElCard>

    <ElDrawer v-model="detailVisible" title="账户权利申请详情" size="760px" destroy-on-close>
      <template v-if="detail">
        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="请求类型">
            {{ requestTypeLabel(detail.request.requestType) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="用户说明">
            {{ detail.request.requestNote || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="处理原因">
            {{ detail.request.reviewReason || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="数据保留或删除说明">
            {{ detail.request.retentionExplanation || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="保留数据类别">
            {{ detail.request.retainedDataCategories.join('、') || '-' }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <ElDivider content-position="left">审计轨迹</ElDivider>
        <ElTimeline>
          <ElTimelineItem
            v-for="audit in detail.audits"
            :key="audit.id"
            :timestamp="formatTime(audit.createdAt)"
          >
            <b>{{ audit.action }}</b>
            <div>{{ audit.fromStatus || '初始' }} → {{ audit.toStatus }}</div>
            <div v-if="audit.reason">原因：{{ audit.reason }}</div>
            <div v-if="audit.retentionExplanation">
              数据说明：{{ audit.retentionExplanation }}
            </div>
          </ElTimelineItem>
        </ElTimeline>
      </template>
    </ElDrawer>

    <ElDialog v-model="actionVisible" :title="actionTitle" width="620px" destroy-on-close>
      <ElAlert
        v-if="activeAction === 'complete'"
        title="完成前后端会再次核对未完结订单、支付、退款和售后；存在履约义务时操作会被拒绝。"
        type="warning"
        :closable="false"
        show-icon
      />
      <ElForm label-position="top" class="action-form">
        <ElFormItem label="本次处理原因" required>
          <ElInput v-model="actionForm.reason" type="textarea" :rows="3" maxlength="1000" />
        </ElFormItem>
        <ElFormItem label="数据保留或删除说明" required>
          <ElInput
            v-model="actionForm.retentionExplanation"
            type="textarea"
            :rows="3"
            maxlength="2000"
            placeholder="按实际处理结果填写，不得使用模板化虚假说明"
          />
        </ElFormItem>
        <ElFormItem label="依法或履约需要保留的数据类别">
          <ElSelect
            v-model="actionForm.retainedDataCategories"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="按实际情况逐项输入；没有则留空"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="actionVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitAction">
          确认{{ actionTitle }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessage } from 'element-plus'
  import {
    fetchAccountRightsRequestDetail,
    fetchAccountRightsRequests,
    transitionAccountRightsRequest
  } from '@/api/account-rights'
  import {
    actionLabel,
    availableAdminActions,
    requestStatusLabel,
    requestStatusTone,
    requestTypeLabel,
    validateActionForm
  } from './account-rights-state'

  defineOptions({ name: 'AccountRights' })

  const requestTypes: Array<{ value: Api.AccountRights.RequestType; label: string }> = [
    { value: 'ACCOUNT_CANCELLATION', label: '注销账户' },
    { value: 'PERSONAL_INFORMATION_DELETION', label: '删除个人信息' },
    { value: 'ACCESS_COPY', label: '查阅/复制个人信息' },
    { value: 'CORRECTION', label: '更正个人信息' }
  ]
  const requestStatuses: Array<{ value: Api.AccountRights.RequestStatus; label: string }> = [
    { value: 'PENDING', label: '待处理' },
    { value: 'IN_REVIEW', label: '审核中' },
    { value: 'APPROVED', label: '已批准' },
    { value: 'REJECTED', label: '已拒绝' },
    { value: 'WITHDRAWN', label: '用户已撤回' },
    { value: 'COMPLETED', label: '已完成' }
  ]

  const loading = ref(false)
  const submitting = ref(false)
  const rows = ref<Api.AccountRights.RequestItem[]>([])
  const userIdInput = ref('')
  const filters = reactive<{
    requestType?: Api.AccountRights.RequestType
    status?: Api.AccountRights.RequestStatus
  }>({})
  const page = reactive({ current: 1, size: 20, total: 0 })
  const detailVisible = ref(false)
  const detail = ref<Api.AccountRights.RequestDetail | null>(null)
  const actionVisible = ref(false)
  const activeRequest = ref<Api.AccountRights.RequestItem | null>(null)
  const activeAction = ref<Api.AccountRights.AdminAction | null>(null)
  const actionForm = reactive<Api.AccountRights.ActionForm>({
    version: 0,
    reason: '',
    retentionExplanation: '',
    retainedDataCategories: []
  })

  const actionTitle = computed(() =>
    activeAction.value ? actionLabel(activeAction.value) : '处理申请'
  )

  const formatTime = (value?: string | null) => (value ? new Date(value).toLocaleString() : '-')

  const loadRows = async () => {
    loading.value = true
    try {
      const userId = userIdInput.value.trim()
      const result = await fetchAccountRightsRequests({
        current: page.current,
        size: page.size,
        ...(/^[1-9]\d*$/.test(userId) ? { userId } : {}),
        ...(filters.requestType ? { requestType: filters.requestType } : {}),
        ...(filters.status ? { status: filters.status } : {})
      })
      rows.value = result.records
      page.current = result.current
      page.size = result.size
      page.total = result.total
    } finally {
      loading.value = false
    }
  }

  const search = () => {
    page.current = 1
    loadRows()
  }

  const reset = () => {
    userIdInput.value = ''
    filters.requestType = undefined
    filters.status = undefined
    search()
  }

  const handleSizeChange = () => {
    page.current = 1
    loadRows()
  }

  const openDetail = async (requestId: Api.AccountRights.Identifier) => {
    detail.value = await fetchAccountRightsRequestDetail(requestId)
    detailVisible.value = true
  }

  const openAction = (
    row: Api.AccountRights.RequestItem,
    action: Api.AccountRights.AdminAction
  ) => {
    activeRequest.value = row
    activeAction.value = action
    Object.assign(actionForm, {
      version: row.version,
      reason: '',
      retentionExplanation: '',
      retainedDataCategories: []
    })
    actionVisible.value = true
  }

  const submitAction = async () => {
    if (!activeRequest.value || !activeAction.value) return
    const error = validateActionForm(actionForm)
    if (error) {
      ElMessage.error(error)
      return
    }
    submitting.value = true
    try {
      await transitionAccountRightsRequest(activeRequest.value.id, activeAction.value, {
        version: actionForm.version,
        reason: actionForm.reason.trim(),
        retentionExplanation: actionForm.retentionExplanation.trim(),
        retainedDataCategories: actionForm.retainedDataCategories
          .map((item) => item.trim())
          .filter(Boolean)
      })
      actionVisible.value = false
      await loadRows()
    } finally {
      submitting.value = false
    }
  }

  onMounted(loadRows)
</script>

<style scoped lang="scss">
  .account-rights-page {
    display: grid;
    gap: 16px;
  }

  .filters {
    display: grid;
    grid-template-columns: 180px 220px 180px auto auto 1fr;
    gap: 12px;
    margin-bottom: 16px;
  }

  small {
    color: var(--el-text-color-secondary);
  }

  .pagination {
    display: flex;
    justify-content: flex-end;
    margin-top: 18px;
  }

  .action-form {
    margin-top: 16px;
  }

  @media (width <= 900px) {
    .filters {
      grid-template-columns: 1fr;
    }
  }
</style>
