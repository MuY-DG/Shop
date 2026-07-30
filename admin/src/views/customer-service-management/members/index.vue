<template>
  <div class="management-page">
    <header class="page-heading">
      <div>
        <div class="heading-eyebrow"><Headphones :size="16" /> 客服组织</div>
        <h1>客服成员</h1>
        <p>从现有后台用户中分配客服与客服管理员，并管理每位客服的对外名称和接待容量。</p>
      </div>
      <CsButton variant="outline" :loading="loading" @click="loadUsers">
        <RefreshCw :size="16" />
        刷新
      </CsButton>
    </header>

    <section class="metric-grid">
      <article class="metric-card">
        <span class="metric-icon metric-icon--blue"><UsersRound :size="19" /></span>
        <div>
          <span>客服成员</span>
          <strong>{{ agentCount }}</strong>
        </div>
      </article>
      <article class="metric-card">
        <span class="metric-icon metric-icon--green"><Wifi :size="19" /></span>
        <div>
          <span>当前在线</span>
          <strong>{{ onlineCount }}</strong>
        </div>
      </article>
      <article class="metric-card">
        <span class="metric-icon metric-icon--violet"><ShieldCheck :size="19" /></span>
        <div>
          <span>客服管理员</span>
          <strong>{{ managerCount }}</strong>
        </div>
      </article>
    </section>

    <section class="content-card">
      <div class="toolbar">
        <div class="search-box">
          <Search :size="17" />
          <input
            v-model="keyword"
            placeholder="搜索后台账号、真实姓名或客服名称"
            @keyup.enter="loadUsers"
          />
          <button v-if="keyword" type="button" aria-label="清空搜索" @click="clearSearch">
            <X :size="15" />
          </button>
        </div>
        <div class="filter-tabs" aria-label="成员筛选">
          <button
            v-for="item in filters"
            :key="item.value"
            type="button"
            :class="{ active: filter === item.value }"
            @click="filter = item.value"
          >
            {{ item.label }}
          </button>
        </div>
      </div>

      <div v-if="loading && !users.length" class="empty-state">
        <LoaderCircle class="spin" :size="24" />
        正在读取成员…
      </div>

      <div v-else-if="!filteredUsers.length" class="empty-state">
        <UserRoundSearch :size="28" />
        <strong>没有匹配的后台用户</strong>
        <span>尝试调整筛选条件或搜索关键词。</span>
      </div>

      <div v-else class="member-list">
        <article v-for="user in filteredUsers" :key="user.adminUserId" class="member-row">
          <div class="member-main">
            <div class="avatar-shell">
              <img v-if="user.adminAvatar" :src="user.adminAvatar" alt="" />
              <span v-else>{{ avatarText(user.displayName) }}</span>
              <i v-if="user.online" title="在线" />
            </div>
            <div class="member-copy">
              <div class="member-title">
                <strong>{{ user.displayName }}</strong>
                <span class="username">@{{ user.username }}</span>
                <span v-if="user.agent" class="badge badge--blue">客服</span>
                <span v-if="user.manager" class="badge badge--violet">客服管理员</span>
              </div>
              <div v-if="user.agent" class="member-detail">
                对外名称
                <b>{{ user.serviceName }}</b>
                <span>·</span>
                {{ user.activeConversationCount }}/{{ user.maxActiveConversations }} 个活跃会话
                <span>·</span>
                <em :class="`status-${user.workStatus.toLowerCase()}`">
                  {{ workStatusLabel(user.workStatus) }}
                </em>
              </div>
              <div v-else class="member-detail">尚未加入客服团队</div>
            </div>
          </div>
          <div class="member-actions">
            <CsButton
              v-if="!user.agent"
              size="sm"
              :loading="savingId === user.adminUserId"
              @click="quickEnableAgent(user)"
            >
              <UserPlus :size="15" />
              设为客服
            </CsButton>
            <CsButton v-else size="sm" variant="outline" @click="openEditor(user)">
              <Settings2 :size="15" />
              管理
            </CsButton>
            <CsButton
              v-if="!user.agent && canManageManagers"
              size="sm"
              variant="ghost"
              :loading="savingId === user.adminUserId"
              @click="toggleManager(user)"
            >
              <ShieldCheck :size="15" />
              {{ user.manager ? '取消管理员' : '设为管理员' }}
            </CsButton>
          </div>
        </article>
      </div>
    </section>

    <Teleport to="body">
      <div v-if="editing" class="dialog-layer" @mousedown.self="closeEditor">
        <section
          class="dialog-card"
          role="dialog"
          aria-modal="true"
          aria-labelledby="member-dialog"
        >
          <header>
            <div>
              <h2 id="member-dialog">管理客服成员</h2>
              <p>{{ editing.displayName }} · @{{ editing.username }}</p>
            </div>
            <button type="button" aria-label="关闭" @click="closeEditor"><X :size="19" /></button>
          </header>

          <div class="dialog-body">
            <div class="role-option">
              <div>
                <strong>接待客服</strong>
                <span>可登录独立客服工作台并参与会话分流。</span>
              </div>
              <CsSwitch v-model="editForm.agent" />
            </div>
            <div class="role-option">
              <div>
                <strong>客服管理员</strong>
                <span>可管理成员、统一形象和会话分流，不会自动参与接待。</span>
              </div>
              <CsSwitch v-if="canManageManagers" v-model="editForm.manager" />
              <span v-else class="role-lock">仅超级管理员可调整</span>
            </div>

            <div class="form-grid" :class="{ disabled: !editForm.agent }">
              <label class="form-field form-field--wide">
                <span>客服对外名称</span>
                <input
                  v-model="editForm.serviceNameOverride"
                  :disabled="!editForm.agent"
                  maxlength="64"
                  placeholder="留空则使用全局默认名称"
                />
                <small>客户只会看到该名称；后台仍保留真实姓名用于审计。</small>
              </label>
              <label class="form-field">
                <span>最大接待数</span>
                <input
                  v-model.number="editForm.maxActiveConversations"
                  :disabled="!editForm.agent"
                  type="number"
                  min="1"
                  max="1000"
                />
              </label>
              <label class="form-field">
                <span>分流权重</span>
                <input
                  v-model.number="editForm.routingWeight"
                  :disabled="!editForm.agent"
                  type="number"
                  min="1"
                  max="1000"
                />
              </label>
            </div>

            <div v-if="editing.activeConversationCount > 0 && !editForm.agent" class="warning-box">
              <TriangleAlert :size="17" />
              该客服还有
              {{ editing.activeConversationCount }} 个活跃会话，需先转接或结束后才能移除。
            </div>
          </div>

          <footer>
            <CsButton variant="outline" @click="closeEditor">取消</CsButton>
            <CsButton :loading="savingId === editing.adminUserId" @click="saveEditor">
              保存更改
            </CsButton>
          </footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessage } from 'element-plus'
  import { useRoute, useRouter } from 'vue-router'
  import { useUserStore } from '@/store/modules/user'
  import {
    Headphones,
    LoaderCircle,
    RefreshCw,
    Search,
    Settings2,
    ShieldCheck,
    TriangleAlert,
    UserPlus,
    UserRoundSearch,
    UsersRound,
    Wifi,
    X
  } from '@lucide/vue'
  import CsButton from '@/components/customer-ui/CsButton.vue'
  import CsSwitch from '@/components/customer-ui/CsSwitch.vue'
  import {
    fetchCustomerServiceManagedUsers,
    updateCustomerServiceManagedUser
  } from '@/api/customer-service'

  type Filter = 'all' | 'agent' | 'manager' | 'available'

  const loading = ref(false)
  const savingId = ref('')
  const users = ref<Api.CustomerService.ManagedUser[]>([])
  const keyword = ref('')
  const filter = ref<Filter>('all')
  const editing = ref<Api.CustomerService.ManagedUser | null>(null)
  const route = useRoute()
  const router = useRouter()
  const userStore = useUserStore()
  const editForm = reactive<Api.CustomerService.ManagedUserForm>({
    agent: false,
    manager: false,
    serviceNameOverride: '',
    maxActiveConversations: 5,
    routingWeight: 100
  })

  const filters: Array<{ label: string; value: Filter }> = [
    { label: '全部用户', value: 'all' },
    { label: '客服', value: 'agent' },
    { label: '管理员', value: 'manager' },
    { label: '待添加', value: 'available' }
  ]

  const agentCount = computed(() => users.value.filter((user) => user.agent).length)
  const managerCount = computed(() => users.value.filter((user) => user.manager).length)
  const onlineCount = computed(() => users.value.filter((user) => user.agent && user.online).length)
  const canManageManagers = computed(() => userStore.info.roles?.includes('R_SUPER') ?? false)
  const filteredUsers = computed(() => {
    if (filter.value === 'agent') return users.value.filter((user) => user.agent)
    if (filter.value === 'manager') return users.value.filter((user) => user.manager)
    if (filter.value === 'available') return users.value.filter((user) => !user.agent)
    return users.value
  })

  onMounted(loadUsers)

  async function loadUsers() {
    loading.value = true
    try {
      users.value = await fetchCustomerServiceManagedUsers(keyword.value.trim())
      const targetUserId = typeof route.query.userId === 'string' ? route.query.userId : ''
      if (targetUserId) {
        const target = users.value.find((user) => user.adminUserId === targetUserId)
        if (target) openEditor(target)
        await router.replace({ path: route.path })
      }
    } finally {
      loading.value = false
    }
  }

  function clearSearch() {
    keyword.value = ''
    loadUsers()
  }

  function openEditor(user: Api.CustomerService.ManagedUser) {
    editing.value = user
    editForm.agent = user.agent
    editForm.manager = user.manager
    editForm.serviceNameOverride = user.serviceNameOverride || ''
    editForm.maxActiveConversations = user.maxActiveConversations
    editForm.routingWeight = user.routingWeight
  }

  function closeEditor() {
    if (!savingId.value) editing.value = null
  }

  async function quickEnableAgent(user: Api.CustomerService.ManagedUser) {
    await saveUser(user, {
      agent: true,
      manager: user.manager,
      serviceNameOverride: '',
      maxActiveConversations: 5,
      routingWeight: 100
    })
  }

  async function toggleManager(user: Api.CustomerService.ManagedUser) {
    await saveUser(user, {
      agent: user.agent,
      manager: !user.manager,
      serviceNameOverride: user.serviceNameOverride || '',
      maxActiveConversations: user.maxActiveConversations,
      routingWeight: user.routingWeight
    })
  }

  async function saveEditor() {
    if (!editing.value) return
    if (editForm.maxActiveConversations < 1 || editForm.routingWeight < 1) {
      ElMessage.warning('接待数和分流权重必须大于 0')
      return
    }
    await saveUser(editing.value, { ...editForm })
    editing.value = null
  }

  async function saveUser(
    user: Api.CustomerService.ManagedUser,
    form: Api.CustomerService.ManagedUserForm
  ) {
    savingId.value = user.adminUserId
    try {
      const updated = await updateCustomerServiceManagedUser(user.adminUserId, form)
      const index = users.value.findIndex((item) => item.adminUserId === user.adminUserId)
      if (index >= 0) users.value[index] = updated
      ElMessage.success('客服成员设置已更新')
    } finally {
      savingId.value = ''
    }
  }

  function avatarText(name: string) {
    return name.trim().slice(0, 1).toUpperCase() || '客'
  }

  function workStatusLabel(status: Api.CustomerService.AgentWorkStatus) {
    if (status === 'AVAILABLE') return '可接待'
    if (status === 'OFFLINE') return '离线'
    return '忙碌'
  }
</script>

<style scoped>
  .management-page {
    min-height: 100%;
    padding: 28px;
    color: #0f172a;
    background: #f6f8fb;
  }

  .page-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    max-width: 1240px;
    margin: 0 auto 22px;
  }

  .heading-eyebrow {
    display: flex;
    gap: 7px;
    align-items: center;
    margin-bottom: 8px;
    color: #2563eb;
    font-size: 13px;
    font-weight: 700;
  }

  h1 {
    margin: 0;
    font-size: 28px;
    letter-spacing: -0.04em;
  }

  .page-heading p {
    margin: 9px 0 0;
    color: #64748b;
    font-size: 14px;
  }

  .metric-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 14px;
    max-width: 1240px;
    margin: 0 auto 14px;
  }

  .metric-card,
  .content-card {
    background: #fff;
    border: 1px solid #e5eaf1;
    border-radius: 16px;
    box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
  }

  .metric-card {
    display: flex;
    gap: 13px;
    align-items: center;
    padding: 17px 18px;
  }

  .metric-icon {
    display: grid;
    width: 40px;
    height: 40px;
    place-items: center;
    border-radius: 11px;
  }

  .metric-icon--blue {
    color: #2563eb;
    background: #eff6ff;
  }

  .metric-icon--green {
    color: #059669;
    background: #ecfdf5;
  }

  .metric-icon--violet {
    color: #7c3aed;
    background: #f5f3ff;
  }

  .metric-card div {
    display: grid;
    gap: 2px;
  }

  .metric-card span {
    color: #64748b;
    font-size: 12px;
  }

  .metric-card strong {
    font-size: 22px;
  }

  .content-card {
    max-width: 1240px;
    min-height: 420px;
    margin: 0 auto;
    overflow: hidden;
  }

  .toolbar {
    display: flex;
    gap: 18px;
    align-items: center;
    justify-content: space-between;
    padding: 17px 18px;
    border-bottom: 1px solid #edf0f4;
  }

  .search-box {
    display: flex;
    width: min(430px, 46vw);
    height: 40px;
    gap: 9px;
    align-items: center;
    padding: 0 12px;
    color: #94a3b8;
    background: #f8fafc;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
  }

  .search-box:focus-within {
    background: #fff;
    border-color: #93c5fd;
    box-shadow: 0 0 0 3px rgb(37 99 235 / 9%);
  }

  .search-box input {
    flex: 1;
    min-width: 0;
    color: #0f172a;
    background: transparent;
    border: 0;
    outline: 0;
  }

  .search-box button {
    display: grid;
    padding: 2px;
    color: #94a3b8;
    cursor: pointer;
    background: transparent;
    border: 0;
    place-items: center;
  }

  .filter-tabs {
    display: flex;
    gap: 3px;
    padding: 3px;
    background: #f1f5f9;
    border-radius: 10px;
  }

  .filter-tabs button {
    padding: 7px 11px;
    color: #64748b;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-radius: 8px;
  }

  .filter-tabs button.active {
    color: #0f172a;
    font-weight: 600;
    background: #fff;
    box-shadow: 0 1px 2px rgb(15 23 42 / 10%);
  }

  .member-row {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
    padding: 18px 20px;
    border-bottom: 1px solid #edf0f4;
  }

  .member-row:last-child {
    border-bottom: 0;
  }

  .member-main,
  .member-title,
  .member-actions {
    display: flex;
    align-items: center;
  }

  .member-main {
    min-width: 0;
    gap: 13px;
  }

  .avatar-shell {
    position: relative;
    display: grid;
    flex: 0 0 auto;
    width: 44px;
    height: 44px;
    overflow: visible;
    color: #1e40af;
    font-weight: 700;
    background: #dbeafe;
    border-radius: 13px;
    place-items: center;
  }

  .avatar-shell img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    border-radius: inherit;
  }

  .avatar-shell i {
    position: absolute;
    right: -2px;
    bottom: -2px;
    width: 11px;
    height: 11px;
    background: #10b981;
    border: 2px solid #fff;
    border-radius: 50%;
  }

  .member-copy {
    min-width: 0;
  }

  .member-title {
    flex-wrap: wrap;
    gap: 7px;
  }

  .member-title strong {
    font-size: 15px;
  }

  .username,
  .member-detail {
    color: #64748b;
    font-size: 13px;
  }

  .member-detail {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
    margin-top: 6px;
  }

  .member-detail b {
    color: #334155;
  }

  .member-detail em {
    font-style: normal;
  }

  .status-available {
    color: #059669;
  }

  .status-busy {
    color: #d97706;
  }

  .status-offline {
    color: #94a3b8;
  }

  .badge {
    padding: 3px 7px;
    font-size: 11px;
    font-weight: 700;
    border-radius: 999px;
  }

  .badge--blue {
    color: #1d4ed8;
    background: #eff6ff;
  }

  .badge--violet {
    color: #6d28d9;
    background: #f5f3ff;
  }

  .member-actions {
    flex: 0 0 auto;
    gap: 7px;
  }

  .empty-state {
    display: flex;
    min-height: 300px;
    flex-direction: column;
    gap: 9px;
    align-items: center;
    justify-content: center;
    color: #94a3b8;
  }

  .empty-state strong {
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

  .dialog-layer {
    position: fixed;
    z-index: 3000;
    display: grid;
    padding: 24px;
    background: rgb(15 23 42 / 38%);
    inset: 0;
    place-items: center;
    backdrop-filter: blur(2px);
  }

  .dialog-card {
    width: min(580px, 100%);
    overflow: hidden;
    background: #fff;
    border: 1px solid rgb(255 255 255 / 70%);
    border-radius: 18px;
    box-shadow: 0 25px 70px rgb(15 23 42 / 24%);
  }

  .dialog-card header,
  .dialog-card footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 20px 22px;
  }

  .dialog-card header {
    border-bottom: 1px solid #edf0f4;
  }

  .dialog-card header h2 {
    margin: 0;
    font-size: 18px;
  }

  .dialog-card header p {
    margin: 5px 0 0;
    color: #64748b;
    font-size: 13px;
  }

  .dialog-card header button {
    display: grid;
    width: 34px;
    height: 34px;
    color: #64748b;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-radius: 8px;
    place-items: center;
  }

  .dialog-card header button:hover {
    background: #f1f5f9;
  }

  .dialog-body {
    display: grid;
    gap: 13px;
    padding: 20px 22px;
  }

  .role-option {
    display: flex;
    gap: 20px;
    align-items: center;
    justify-content: space-between;
    padding: 14px;
    background: #f8fafc;
    border: 1px solid #e8edf3;
    border-radius: 12px;
  }

  .role-option div {
    display: grid;
    gap: 4px;
  }

  .role-option strong,
  .form-field > span {
    font-size: 13px;
  }

  .role-option span,
  .form-field small {
    color: #64748b;
    font-size: 12px;
  }

  .form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 13px;
    padding-top: 5px;
  }

  .form-grid.disabled {
    opacity: 0.55;
  }

  .form-field {
    display: grid;
    gap: 7px;
  }

  .form-field--wide {
    grid-column: 1 / -1;
  }

  .form-field input {
    height: 40px;
    padding: 0 11px;
    color: #0f172a;
    background: #fff;
    border: 1px solid #dbe2ea;
    border-radius: 9px;
    outline: 0;
  }

  .form-field input:focus {
    border-color: #60a5fa;
    box-shadow: 0 0 0 3px rgb(37 99 235 / 9%);
  }

  .warning-box {
    display: flex;
    gap: 8px;
    align-items: flex-start;
    padding: 11px 12px;
    color: #9a3412;
    font-size: 12px;
    background: #fff7ed;
    border: 1px solid #fed7aa;
    border-radius: 9px;
  }

  .dialog-card footer {
    gap: 9px;
    justify-content: flex-end;
    background: #fafbfc;
    border-top: 1px solid #edf0f4;
  }

  @media (width <= 780px) {
    .management-page {
      padding: 18px 12px;
    }

    .metric-grid {
      grid-template-columns: 1fr;
    }

    .toolbar,
    .member-row {
      align-items: stretch;
      flex-direction: column;
    }

    .search-box {
      width: 100%;
    }

    .filter-tabs {
      overflow-x: auto;
    }

    .member-actions {
      justify-content: flex-end;
    }
  }
</style>
