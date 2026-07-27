<template>
  <ElDrawer
    v-model="visible"
    :title="drawerTitle"
    :size="drawerSize"
    append-to-body
    destroy-on-close
  >
    <div class="session-toolbar">
      <div>
        <div class="session-summary">
          当前共有 <strong>{{ sessions.length }}</strong> 个登录设备
        </div>
        <div class="session-hint">同一浏览器的多个标签页会共用一个登录设备会话。</div>
      </div>
      <div class="session-toolbar__actions">
        <ElButton :loading="loading" @click="loadSessions">刷新</ElButton>
        <ElButton
          v-if="allowRevoke"
          type="danger"
          plain
          :disabled="sessions.length === 0"
          :loading="logoutAllLoading"
          @click="handleLogoutAll"
        >
          全部下线
        </ElButton>
      </div>
    </div>

    <ElTable
      v-loading="loading"
      :data="sessions"
      row-key="sessionId"
      class="session-table"
      empty-text="暂无登录设备"
    >
      <ElTableColumn label="设备" min-width="210">
        <template #default="{ row }">
          <div class="device-cell">
            <div class="device-cell__title">
              <ArtSvgIcon icon="ri:computer-line" />
              <span>{{ displayText(row.deviceName, '未知设备') }}</span>
              <ElTag v-if="row.current" type="success" size="small">当前设备</ElTag>
            </div>
            <div class="device-cell__meta">
              {{ sessionPlatform(row) }}
            </div>
          </div>
        </template>
      </ElTableColumn>
      <ElTableColumn label="IP 地址" min-width="140">
        <template #default="{ row }">
          {{ displayText(row.ipAddress) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="登录时间" min-width="170">
        <template #default="{ row }">
          {{ formatDateTime(row.loginAt) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="最近活跃" min-width="170">
        <template #default="{ row }">
          {{ formatDateTime(row.lastSeenAt) }}
        </template>
      </ElTableColumn>
      <ElTableColumn v-if="allowRevoke" label="操作" width="84" fixed="right">
        <template #default="{ row }">
          <ElButton
            type="danger"
            link
            :loading="revokingSessionIds.has(row.sessionId)"
            @click="handleRevoke(row)"
          >
            下线
          </ElButton>
        </template>
      </ElTableColumn>
    </ElTable>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { useWindowSize } from '@vueuse/core'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { fetchMyAdminSessions, logoutAllAdminSessions, revokeMyAdminSession } from '@/api/auth'
  import {
    fetchAdminUserSessions,
    logoutAllAdminUserSessions,
    revokeAdminUserSession
  } from '@/api/system-manage'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'AdminSessionDrawer' })

  type SessionMode = 'self' | 'managed'
  type AdminSession = Api.Auth.AdminSession

  const props = withDefaults(
    defineProps<{
      modelValue: boolean
      mode: SessionMode
      userId?: number
      userName?: string
      canRevoke?: boolean
    }>(),
    {
      userId: undefined,
      userName: '',
      canRevoke: false
    }
  )

  const emit = defineEmits<{
    (event: 'update:modelValue', value: boolean): void
    (event: 'changed'): void
  }>()

  const userStore = useUserStore()
  const { width: windowWidth } = useWindowSize()
  const sessions = ref<AdminSession[]>([])
  const loading = ref(false)
  const logoutAllLoading = ref(false)
  const revokingSessionIds = ref(new Set<string>())
  let loadRequestId = 0

  const visible = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
  })
  const drawerSize = computed(() => (windowWidth.value < 768 ? '94%' : '780px'))
  const drawerTitle = computed(() => {
    if (props.mode === 'self') return '我的登录设备'
    return props.userName ? `登录设备 - ${props.userName}` : '管理员登录设备'
  })
  const allowRevoke = computed(() => props.mode === 'self' || props.canRevoke)

  const loadSessions = async () => {
    if (props.mode === 'managed' && !props.userId) {
      sessions.value = []
      return
    }

    const requestId = ++loadRequestId
    loading.value = true
    try {
      const records =
        props.mode === 'self'
          ? await fetchMyAdminSessions()
          : await fetchAdminUserSessions(props.userId as number)
      if (requestId === loadRequestId) {
        sessions.value = records
      }
    } finally {
      if (requestId === loadRequestId) {
        loading.value = false
      }
    }
  }

  watch([() => props.modelValue, () => props.mode, () => props.userId], ([open]) => {
    if (open) {
      sessions.value = []
      void loadSessions()
    }
  })

  const displayText = (value?: string | null, fallback = '-') => value?.trim() || fallback

  const sessionPlatform = (session: AdminSession) => {
    const parts = [session.browser, session.os].map((item) => item?.trim()).filter(Boolean)
    return parts.length > 0 ? parts.join(' · ') : '浏览器与系统未知'
  }

  const formatDateTime = (value?: string | null) => {
    if (!value) return '-'
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) {
      return value.replace('T', ' ').replace(/Z$/, '').slice(0, 19)
    }
    return new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23'
    })
      .format(date)
      .replaceAll('/', '-')
  }

  const setSessionRevoking = (sessionId: string, revoking: boolean) => {
    const next = new Set(revokingSessionIds.value)
    if (revoking) {
      next.add(sessionId)
    } else {
      next.delete(sessionId)
    }
    revokingSessionIds.value = next
  }

  const isCurrentAccount = () =>
    props.mode === 'self' || (props.mode === 'managed' && props.userId === userStore.info.userId)

  const handleRevoke = async (session: AdminSession) => {
    try {
      await ElMessageBox.confirm(
        session.current
          ? '下线当前设备后需要重新登录，确定继续吗？'
          : `确定下线“${displayText(session.deviceName, '未知设备')}”吗？`,
        '下线设备',
        {
          type: 'warning',
          confirmButtonText: '下线',
          cancelButtonText: '取消'
        }
      )
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      throw error
    }

    setSessionRevoking(session.sessionId, true)
    try {
      if (props.mode === 'self') {
        await revokeMyAdminSession(session.sessionId)
      } else {
        await revokeAdminUserSession(props.userId as number, session.sessionId)
      }
      ElMessage.success('设备已下线')

      if (session.current) {
        visible.value = false
        userStore.logOut()
        return
      }
      sessions.value = sessions.value.filter((item) => item.sessionId !== session.sessionId)
      emit('changed')
    } finally {
      setSessionRevoking(session.sessionId, false)
    }
  }

  const handleLogoutAll = async () => {
    try {
      await ElMessageBox.confirm(
        isCurrentAccount()
          ? '将退出当前账号在所有设备上的登录，包括本设备。确定继续吗？'
          : `将立即下线“${displayText(props.userName, '该管理员')}”的所有登录设备，确定继续吗？`,
        '全部下线',
        {
          type: 'warning',
          confirmButtonText: '全部下线',
          cancelButtonText: '取消'
        }
      )
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      throw error
    }

    logoutAllLoading.value = true
    try {
      if (props.mode === 'self') {
        await logoutAllAdminSessions()
      } else {
        await logoutAllAdminUserSessions(props.userId as number)
      }
      ElMessage.success('所有设备已下线')

      if (isCurrentAccount()) {
        visible.value = false
        userStore.logOut()
        return
      }
      sessions.value = []
      emit('changed')
    } finally {
      logoutAllLoading.value = false
    }
  }
</script>

<style scoped>
  .session-toolbar {
    display: flex;
    gap: 16px;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 16px;
  }

  .session-summary {
    color: var(--el-text-color-primary);
  }

  .session-hint,
  .device-cell__meta {
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .session-toolbar__actions,
  .device-cell__title {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .session-table {
    width: 100%;
  }

  .device-cell__title {
    font-weight: 500;
    color: var(--el-text-color-primary);
  }

  @media (width <= 767px) {
    .session-toolbar {
      flex-direction: column;
    }

    .session-toolbar__actions {
      width: 100%;
    }
  }
</style>
