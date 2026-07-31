<template>
  <div class="management-page">
    <header class="page-heading">
      <div>
        <div class="heading-eyebrow"><Headphones :size="16" /> 客服组织</div>
        <h1>客服管理</h1>
        <p>管理客服账号、对外名称与客服管理员身份。</p>
      </div>
      <div class="heading-actions">
        <CsButton variant="outline" :loading="loading" @click="loadMembers">
          <RefreshCw :size="16" />
          刷新
        </CsButton>
        <CsButton @click="openAddDialog">
          <UserPlus :size="16" />
          添加客服
        </CsButton>
      </div>
    </header>

    <section class="content-card">
      <div class="toolbar">
        <div class="search-box">
          <Search :size="17" />
          <input
            v-model="keyword"
            placeholder="搜索客服名称或登录账号"
            @keyup.enter="loadMembers"
          />
          <button v-if="keyword" type="button" aria-label="清空搜索" @click="clearSearch">
            <X :size="15" />
          </button>
        </div>
        <span class="member-count">共 {{ members.length }} 位客服</span>
      </div>

      <div v-if="loading && !members.length" class="empty-state">
        <LoaderCircle class="spin" :size="24" />
        正在读取客服成员…
      </div>

      <div v-else-if="!members.length" class="empty-state">
        <UsersRound :size="30" />
        <strong>{{ keyword ? '没有匹配的客服' : '还没有客服成员' }}</strong>
        <span>{{
          keyword ? '请尝试其他名称或账号。' : '点击右上角“添加客服”，从游客中选择账号。'
        }}</span>
      </div>

      <div v-else class="table-scroll" :class="{ 'is-refreshing': loading }">
        <table class="member-table">
          <thead>
            <tr>
              <th>客服</th>
              <th>账号</th>
              <th>状态</th>
              <th>绑定时间</th>
              <th class="actions-heading">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="member in members" :key="member.adminUserId">
              <td>
                <div class="member-identity">
                  <span class="avatar-shell">
                    <img v-if="member.serviceAvatar" :src="member.serviceAvatar" alt="" />
                    <span v-else>{{ avatarText(member.serviceName) }}</span>
                  </span>
                  <div>
                    <div class="member-name">
                      <strong>{{ member.serviceName }}</strong>
                      <span v-if="member.manager" class="manager-badge">
                        <ShieldCheck :size="13" />
                        客服管理员
                      </span>
                    </div>
                    <small>客户可见名称</small>
                  </div>
                </div>
              </td>
              <td
                ><span class="username">@{{ member.username }}</span></td
              >
              <td>
                <span class="status-pill" :class="{ online: member.online }">
                  <i />
                  {{ member.online ? '在线' : '离线' }}
                </span>
              </td>
              <td
                ><span class="bound-at">{{ formatDateTime(member.boundAt) }}</span></td
              >
              <td>
                <div class="member-actions">
                  <CsButton size="sm" variant="outline" @click="openNameEditor(member)">
                    <Pencil :size="14" />
                    编辑名称
                  </CsButton>
                  <CsButton
                    size="sm"
                    variant="ghost"
                    :loading="isSaving('manager', member.adminUserId)"
                    @click="toggleManager(member)"
                  >
                    <ShieldCheck :size="14" />
                    {{ member.manager ? '取消管理员' : '设为管理员' }}
                  </CsButton>
                  <CsButton
                    size="sm"
                    variant="danger"
                    :loading="isSaving('delete', member.adminUserId)"
                    @click="removeMember(member)"
                  >
                    <Trash2 :size="14" />
                    删除
                  </CsButton>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

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
  import { computed, onMounted, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    Headphones,
    LoaderCircle,
    Pencil,
    RefreshCw,
    Search,
    ShieldCheck,
    Trash2,
    UserPlus,
    UsersRound,
    X
  } from '@lucide/vue'
  import CsButton from '@/components/customer-ui/CsButton.vue'
  import {
    addCustomerServiceMember,
    deleteCustomerServiceMember,
    fetchCustomerServiceGuestCandidates,
    fetchCustomerServiceMembers,
    updateCustomerServiceMemberManager,
    updateCustomerServiceMemberName
  } from '@/api/customer-service'

  const members = ref<Api.CustomerService.CustomerServiceMember[]>([])
  const candidates = ref<Api.CustomerService.GuestCandidate[]>([])
  const keyword = ref('')
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

  const selectedGuest = computed(
    () =>
      candidates.value.find((candidate) => candidate.adminUserId === selectedGuestId.value) || null
  )

  onMounted(loadMembers)

  async function loadMembers() {
    loading.value = true
    try {
      members.value = await fetchCustomerServiceMembers(keyword.value.trim())
    } finally {
      loading.value = false
    }
  }

  function clearSearch() {
    keyword.value = ''
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
  .management-page {
    min-height: 100%;
    padding: 28px;
    color: #0f172a;
    background: #f6f8fb;
  }

  .page-heading,
  .content-card {
    max-width: 1320px;
    margin-right: auto;
    margin-left: auto;
  }

  .page-heading {
    display: flex;
    gap: 24px;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 22px;
  }

  .heading-eyebrow {
    display: flex;
    gap: 7px;
    align-items: center;
    margin-bottom: 8px;
    font-size: 13px;
    font-weight: 700;
    color: #2563eb;
  }

  h1 {
    margin: 0;
    font-size: 28px;
    letter-spacing: -0.04em;
  }

  .page-heading p,
  .dialog-description {
    margin: 9px 0 0;
    font-size: 14px;
    color: #64748b;
  }

  .heading-actions {
    display: flex;
    gap: 10px;
  }

  .content-card {
    overflow: hidden;
    background: #fff;
    border: 1px solid #e5eaf1;
    border-radius: 16px;
    box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
  }

  .toolbar {
    display: flex;
    gap: 18px;
    align-items: center;
    justify-content: space-between;
    min-height: 76px;
    padding: 0 22px;
    border-bottom: 1px solid #edf0f4;
  }

  .search-box {
    display: flex;
    gap: 9px;
    align-items: center;
    width: min(420px, 100%);
    height: 40px;
    padding: 0 12px;
    color: #94a3b8;
    background: #f8fafc;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
  }

  .search-box:focus-within {
    border-color: #93c5fd;
    box-shadow: 0 0 0 3px rgb(37 99 235 / 8%);
  }

  .search-box input {
    flex: 1;
    min-width: 0;
    font-size: 14px;
    color: #0f172a;
    background: transparent;
    border: 0;
    outline: 0;
  }

  .search-box button {
    display: grid;
    place-items: center;
    padding: 2px;
    color: #64748b;
    cursor: pointer;
    background: transparent;
    border: 0;
  }

  .member-count {
    font-size: 13px;
    color: #94a3b8;
  }

  .table-scroll {
    overflow-x: auto;
    transition: opacity 0.18s ease;
  }

  .table-scroll.is-refreshing {
    pointer-events: none;
    opacity: 0.58;
  }

  .member-table {
    width: 100%;
    min-width: 1050px;
    border-collapse: collapse;
  }

  .member-table th {
    height: 46px;
    padding: 0 18px;
    font-size: 12px;
    font-weight: 650;
    color: #64748b;
    text-align: left;
    background: #fbfcfd;
    border-bottom: 1px solid #edf0f4;
  }

  .member-table td {
    height: 82px;
    padding: 12px 18px;
    border-bottom: 1px solid #f0f2f5;
  }

  .member-table tbody tr:last-child td {
    border-bottom: 0;
  }

  .member-table tbody tr:hover {
    background: #fbfdff;
  }

  .member-table th:first-child {
    width: 30%;
  }

  .member-table th:last-child {
    width: 360px;
  }

  .member-identity {
    display: flex;
    gap: 12px;
    align-items: center;
  }

  .avatar-shell,
  .candidate-avatar {
    display: grid;
    flex: 0 0 auto;
    place-items: center;
    overflow: hidden;
    font-weight: 750;
    color: #1d4ed8;
    background: #dbeafe;
    border-radius: 50%;
  }

  .avatar-shell {
    width: 44px;
    height: 44px;
  }

  .avatar-shell img,
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

  .member-identity small,
  .candidate-copy small {
    display: block;
    margin-top: 4px;
    font-size: 12px;
    color: #94a3b8;
  }

  .manager-badge {
    display: inline-flex;
    gap: 4px;
    align-items: center;
    padding: 3px 7px;
    font-size: 11px;
    font-weight: 650;
    color: #6d28d9;
    background: #f3e8ff;
    border-radius: 999px;
  }

  .username,
  .bound-at {
    font-size: 13px;
    color: #475569;
  }

  .status-pill {
    display: inline-flex;
    gap: 7px;
    align-items: center;
    font-size: 13px;
    color: #64748b;
  }

  .status-pill i {
    width: 8px;
    height: 8px;
    background: #cbd5e1;
    border-radius: 50%;
  }

  .status-pill.online {
    color: #15803d;
  }

  .status-pill.online i {
    background: #22c55e;
    box-shadow: 0 0 0 3px rgb(34 197 94 / 12%);
  }

  .actions-heading {
    padding-left: 26px !important;
  }

  .member-actions {
    display: flex;
    gap: 7px;
    justify-content: flex-end;
  }

  .empty-state {
    display: grid;
    place-content: center;
    justify-items: center;
    min-height: 280px;
    color: #94a3b8;
    text-align: center;
  }

  .empty-state strong {
    margin-top: 12px;
    font-size: 15px;
    color: #334155;
  }

  .empty-state span {
    margin-top: 6px;
    font-size: 13px;
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

  .candidate-avatar {
    width: 40px;
    height: 40px;
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

  .spin {
    animation: spin 0.9s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (width <= 760px) {
    .management-page {
      padding: 18px;
    }

    .page-heading,
    .toolbar {
      align-items: stretch;
    }

    .page-heading,
    .toolbar,
    .heading-actions {
      flex-direction: column;
    }

    .member-count {
      display: none;
    }
  }
</style>
