<template>
  <div class="management-page art-full-height">
    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      :show-expand="false"
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="loadMembers">
        <template #left>
          <div class="table-actions">
            <ElButton type="primary" @click="openAddDialog">添加客服</ElButton>
            <span class="table-hint">共 {{ members.length }} 位客服</span>
          </div>
        </template>
      </ArtTableHeader>

      <ArtTable
        row-key="adminUserId"
        :empty-text="searchedKeyword ? '没有匹配的客服' : '还没有客服成员'"
        :loading="loading"
        :data="members"
        :columns="columns"
      >
        <template #member="{ row }">
          <div class="member-identity">
            <ElAvatar :size="40" :src="row.serviceAvatar">
              {{ avatarText(row.serviceName) }}
            </ElAvatar>
            <div class="member-copy">
              <div class="member-name">
                <strong>{{ row.serviceName }}</strong>
                <ElTag v-if="row.manager" type="primary" size="small" effect="light">
                  <ShieldCheck :size="13" />
                  客服管理员
                </ElTag>
              </div>
              <span>客户可见名称</span>
            </div>
          </div>
        </template>

        <template #username="{ row }">
          <span class="username">@{{ row.username }}</span>
        </template>

        <template #status="{ row }">
          <ElTag :type="row.online ? 'success' : 'info'" size="small" effect="light">
            <span class="status-content">
              <i :class="{ online: row.online }" />
              {{ row.online ? '在线' : '离线' }}
            </span>
          </ElTag>
        </template>

        <template #boundAt="{ row }">
          <span class="bound-at">{{ formatDateTime(row.boundAt) }}</span>
        </template>
      </ArtTable>
    </ElCard>

    <ElDialog
      v-model="addDialogVisible"
      title="添加客服"
      width="620px"
      align-center
      destroy-on-close
      :close-on-click-modal="!addingMember"
      :close-on-press-escape="!addingMember"
    >
      <p class="dialog-description">从游客账号中选择一位添加为客服。</p>
      <div class="candidate-search">
        <ElInput
          v-model="candidateKeyword"
          clearable
          placeholder="搜索游客名称或账号"
          @keyup.enter="loadCandidates"
          @clear="loadCandidates"
        >
          <template #prefix><Search :size="16" /></template>
        </ElInput>
        <ElButton :loading="candidateLoading" @click="loadCandidates">搜索</ElButton>
      </div>

      <div v-loading="candidateLoading" class="candidate-list">
        <button
          v-for="candidate in candidates"
          :key="candidate.adminUserId"
          type="button"
          class="candidate-row"
          :class="{ selected: selectedGuestId === candidate.adminUserId }"
          @click="selectCandidate(candidate)"
        >
          <span class="candidate-avatar">
            <img v-if="candidate.avatar" :src="candidate.avatar" alt="" />
            <span v-else>{{ avatarText(candidate.displayName) }}</span>
          </span>
          <span class="candidate-copy">
            <strong>{{ candidate.displayName }}</strong>
            <small>@{{ candidate.username }}</small>
          </span>
          <span class="selection-dot" />
        </button>
        <ElEmpty
          v-if="!candidateLoading && !candidates.length"
          description="没有可添加的游客账号"
          :image-size="72"
        />
      </div>

      <label v-if="selectedGuest" class="dialog-field">
        <span>客服名称</span>
        <ElInput
          v-model="addServiceName"
          maxlength="64"
          show-word-limit
          placeholder="请输入客户可见的客服名称"
        />
      </label>

      <template #footer>
        <ElButton :disabled="addingMember" @click="addDialogVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="addingMember"
          :disabled="!selectedGuest"
          @click="addSelectedGuest"
        >
          添加客服
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="nameDialogVisible"
      title="编辑客服名称"
      width="460px"
      align-center
      :close-on-click-modal="!savingName"
      :close-on-press-escape="!savingName"
    >
      <p class="dialog-description">
        {{ editingMember?.username ? `@${editingMember.username}` : '' }} · 该名称会展示给客户。
      </p>
      <label class="dialog-field">
        <span>客服名称</span>
        <ElInput
          v-model="editServiceName"
          maxlength="64"
          show-word-limit
          placeholder="请输入客服名称"
          @keyup.enter="saveMemberName"
        />
      </label>
      <template #footer>
        <ElButton :disabled="savingName" @click="nameDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="savingName" @click="saveMemberName">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, h, onMounted, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { Search, ShieldCheck } from '@lucide/vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import {
    addCustomerServiceMember,
    deleteCustomerServiceMember,
    fetchCustomerServiceGuestCandidates,
    fetchCustomerServiceMembers,
    updateCustomerServiceMemberManager,
    updateCustomerServiceMemberName
  } from '@/api/customer-service'

  defineOptions({ name: 'CustomerServiceMembers' })

  type CustomerServiceMember = Api.CustomerService.CustomerServiceMember

  const members = ref<CustomerServiceMember[]>([])
  const candidates = ref<Api.CustomerService.GuestCandidate[]>([])
  const searchForm = ref<{ keyword?: string }>({ keyword: undefined })
  const searchedKeyword = ref('')
  const candidateKeyword = ref('')
  const selectedGuestId = ref('')
  const addServiceName = ref('')
  const editingMember = ref<Api.CustomerService.CustomerServiceMember | null>(null)
  const editServiceName = ref('')
  const loading = ref(false)
  const candidateLoading = ref(false)
  const addDialogVisible = ref(false)
  const nameDialogVisible = ref(false)
  const addingMember = ref(false)
  const savingName = ref(false)
  const savingKey = ref('')

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '客服关键字',
      labelWidth: '84px',
      key: 'keyword',
      type: 'input',
      props: {
        clearable: true,
        placeholder: '客服名称 / 登录账号'
      }
    }
  ])

  const { columns, columnChecks } = useTableColumns<CustomerServiceMember>(() => [
    { prop: 'member', label: '客服', minWidth: 260, useSlot: true },
    { prop: 'username', label: '账号', minWidth: 160, useSlot: true },
    { prop: 'status', label: '状态', width: 100, useSlot: true },
    { prop: 'boundAt', label: '绑定时间', width: 180, useSlot: true },
    {
      prop: 'operation',
      label: '操作',
      width: 110,
      fixed: 'right',
      formatter: (row) =>
        h(ArtButtonMore, {
          list: getMemberActions(row),
          onClick: (item: ButtonMoreItem) => handleMoreAction(item, row)
        })
    }
  ])

  const selectedGuest = computed(
    () =>
      candidates.value.find((candidate) => candidate.adminUserId === selectedGuestId.value) || null
  )

  onMounted(loadMembers)

  async function loadMembers() {
    loading.value = true
    try {
      members.value = await fetchCustomerServiceMembers(searchedKeyword.value)
    } finally {
      loading.value = false
    }
  }

  function handleSearch(params: { keyword?: string }) {
    searchedKeyword.value = params.keyword?.trim() || ''
    void loadMembers()
  }

  function handleReset() {
    searchForm.value = { keyword: undefined }
    searchedKeyword.value = ''
    void loadMembers()
  }

  function openAddDialog() {
    candidateKeyword.value = ''
    selectedGuestId.value = ''
    addServiceName.value = ''
    candidates.value = []
    addDialogVisible.value = true
    void loadCandidates()
  }

  async function loadCandidates() {
    candidateLoading.value = true
    try {
      candidates.value = await fetchCustomerServiceGuestCandidates(candidateKeyword.value.trim())
      if (!candidates.value.some((candidate) => candidate.adminUserId === selectedGuestId.value)) {
        selectedGuestId.value = ''
        addServiceName.value = ''
      }
    } finally {
      candidateLoading.value = false
    }
  }

  function selectCandidate(candidate: Api.CustomerService.GuestCandidate) {
    selectedGuestId.value = candidate.adminUserId
    addServiceName.value = candidate.displayName
  }

  async function addSelectedGuest() {
    if (!selectedGuest.value) return
    const serviceName = addServiceName.value.trim()
    if (!serviceName) {
      ElMessage.warning('请填写客服名称')
      return
    }
    addingMember.value = true
    try {
      await addCustomerServiceMember(selectedGuest.value.adminUserId, { serviceName })
      ElMessage.success(`已将“${serviceName}”添加为客服`)
      addDialogVisible.value = false
      await loadMembers()
    } finally {
      addingMember.value = false
    }
  }

  function getMemberActions(member: CustomerServiceMember): ButtonMoreItem[] {
    return [
      {
        key: 'edit-name',
        label: '编辑名称',
        icon: 'ri:edit-2-line'
      },
      {
        key: 'toggle-manager',
        label: member.manager ? '取消管理员' : '设为管理员',
        icon: 'ri:shield-user-line',
        disabled: isSaving('manager', member.adminUserId)
      },
      {
        key: 'delete',
        label: '删除客服',
        icon: 'ri:delete-bin-line',
        color: '#f56c6c',
        disabled: isSaving('delete', member.adminUserId)
      }
    ]
  }

  function handleMoreAction(item: ButtonMoreItem, member: CustomerServiceMember) {
    if (item.key === 'edit-name') openNameEditor(member)
    if (item.key === 'toggle-manager') void toggleManager(member)
    if (item.key === 'delete') void removeMember(member)
  }

  function openNameEditor(member: Api.CustomerService.CustomerServiceMember) {
    editingMember.value = member
    editServiceName.value = member.serviceName
    nameDialogVisible.value = true
  }

  async function saveMemberName() {
    if (!editingMember.value) return
    const serviceName = editServiceName.value.trim()
    if (!serviceName) {
      ElMessage.warning('请填写客服名称')
      return
    }
    savingName.value = true
    try {
      await updateCustomerServiceMemberName(editingMember.value.adminUserId, { serviceName })
      ElMessage.success('客服名称已更新')
      nameDialogVisible.value = false
      await loadMembers()
    } finally {
      savingName.value = false
    }
  }

  async function toggleManager(member: Api.CustomerService.CustomerServiceMember) {
    const manager = !member.manager
    try {
      await ElMessageBox.confirm(
        manager
          ? `确定将“${member.serviceName}”设置为客服管理员吗？`
          : `确定取消“${member.serviceName}”的客服管理员身份吗？`,
        manager ? '设置客服管理员' : '取消客服管理员',
        {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: manager ? 'warning' : 'info'
        }
      )
      savingKey.value = `manager:${member.adminUserId}`
      await updateCustomerServiceMemberManager(member.adminUserId, { manager })
      ElMessage.success(manager ? '已设置为客服管理员' : '已取消客服管理员')
      await loadMembers()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
    } finally {
      savingKey.value = ''
    }
  }

  async function removeMember(member: Api.CustomerService.CustomerServiceMember) {
    try {
      await ElMessageBox.confirm(
        `删除后，“${member.serviceName}”将移出客服列表，账号角色会改为游客。`,
        '删除客服',
        {
          confirmButtonText: '确认删除',
          cancelButtonText: '取消',
          type: 'warning',
          confirmButtonClass: 'el-button--danger'
        }
      )
      savingKey.value = `delete:${member.adminUserId}`
      await deleteCustomerServiceMember(member.adminUserId)
      ElMessage.success('客服已删除，账号已改为游客')
      await loadMembers()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
    } finally {
      savingKey.value = ''
    }
  }

  function isSaving(action: string, adminUserId: string) {
    return savingKey.value === `${action}:${adminUserId}`
  }

  function avatarText(name: string) {
    return name.trim().slice(0, 1).toUpperCase() || '客'
  }

  function formatDateTime(value: string) {
    if (!value) return '-'
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return value.replace('T', ' ')
    return new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false
    }).format(date)
  }
</script>

<style scoped>
  .dialog-description {
    margin: 9px 0 0;
    font-size: 14px;
    color: var(--el-text-color-secondary);
  }

  .table-actions {
    display: flex;
    gap: 12px;
    align-items: center;
  }

  .table-hint {
    font-size: 14px;
    color: var(--el-text-color-secondary);
  }

  .member-identity {
    display: flex;
    gap: 12px;
    align-items: center;
  }

  .candidate-avatar {
    display: grid;
    flex: 0 0 auto;
    place-items: center;
    width: 40px;
    height: 40px;
    overflow: hidden;
    font-weight: 750;
    color: #1d4ed8;
    background: #dbeafe;
    border-radius: 50%;
  }

  .candidate-avatar img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .member-name {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }

  .member-name strong {
    font-size: 14px;
  }

  .member-copy > span,
  .candidate-copy small {
    display: block;
    margin-top: 4px;
    font-size: 12px;
    color: #94a3b8;
  }

  .member-name :deep(.el-tag__content) {
    display: inline-flex;
    gap: 4px;
    align-items: center;
  }

  .username,
  .bound-at {
    font-size: 13px;
    color: #475569;
  }

  .status-content {
    display: inline-flex;
    gap: 6px;
    align-items: center;
  }

  .status-content i {
    width: 6px;
    height: 6px;
    background: #cbd5e1;
    border-radius: 50%;
  }

  .status-content i.online {
    background: #22c55e;
  }

  .candidate-search {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 10px;
    margin-top: 18px;
  }

  .candidate-list {
    min-height: 180px;
    max-height: 330px;
    padding: 6px;
    margin-top: 14px;
    overflow: auto;
    background: #f8fafc;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
  }

  .candidate-row {
    display: flex;
    gap: 12px;
    align-items: center;
    width: 100%;
    padding: 11px 12px;
    color: #0f172a;
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 1px solid transparent;
    border-radius: 10px;
  }

  .candidate-row:hover {
    background: #fff;
  }

  .candidate-row.selected {
    background: #eff6ff;
    border-color: #93c5fd;
  }

  .candidate-copy {
    flex: 1;
    min-width: 0;
  }

  .selection-dot {
    box-sizing: border-box;
    width: 17px;
    height: 17px;
    border: 2px solid #cbd5e1;
    border-radius: 50%;
  }

  .candidate-row.selected .selection-dot {
    background: #2563eb;
    border: 4px solid #dbeafe;
    box-shadow: 0 0 0 1px #2563eb;
  }

  .dialog-field {
    display: grid;
    gap: 8px;
    margin-top: 18px;
    font-size: 13px;
    font-weight: 650;
    color: #334155;
  }
</style>
