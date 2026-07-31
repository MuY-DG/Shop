<template>
  <div class="customer-service-shell">
    <aside class="service-rail" aria-label="客服导航">
      <ElDropdown trigger="click" placement="bottom-start" @command="handleAvatarCommand">
        <button
          type="button"
          class="profile-trigger"
          :class="{ 'is-online': isAvailable }"
          aria-label="客服状态与退出"
        >
          <img v-if="profileAvatar" :src="profileAvatar" alt="" />
          <UserRound v-else :size="22" />
          <span class="profile-status" />
        </button>
        <template #dropdown>
          <ElDropdownMenu class="profile-menu">
            <ElDropdownItem v-if="isAgent" command="toggle-status" :disabled="statusLoading">
              <WifiOff v-if="isAvailable" :size="16" />
              <Wifi v-else :size="16" />
              {{ isAvailable ? '切换为离线' : '切换为在线' }}
            </ElDropdownItem>
            <ElDropdownItem command="logout" divided>
              <LogOut :size="16" />
              退出登录
            </ElDropdownItem>
          </ElDropdownMenu>
        </template>
      </ElDropdown>

      <nav class="rail-navigation">
        <RouterLink
          v-for="item in navigation"
          :key="item.path"
          :to="item.path"
          class="rail-link"
          :class="{ 'is-active': item.active }"
          :aria-label="item.label"
          :title="item.label"
        >
          <component :is="item.icon" :size="22" :stroke-width="1.8" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="rail-spacer" />
      <div class="rail-state" :title="isAvailable ? '在线' : '离线'">
        <span :class="{ 'is-online': isAvailable }" />
        {{ isAvailable ? '在线' : '离线' }}
      </div>
    </aside>

    <main class="service-stage">
      <RouterView v-if="route.path !== '/customer-service'" />
      <CustomerServiceConversations v-else />
    </main>
  </div>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { BarChart3, LogOut, MessageCircle, Settings, UserRound, Wifi, WifiOff } from '@lucide/vue'
  import { useRoute } from 'vue-router'
  import {
    fetchCustomerServiceAgentProfile,
    fetchCustomerServiceAgentState,
    updateCustomerServiceAgentState
  } from '@/api/customer-service'
  import { useUserStore } from '@/store/modules/user'
  import { realtimeClient } from '@/utils/realtime'
  import CustomerServiceConversations from './conversations/index.vue'

  defineOptions({ name: 'CustomerServiceWorkspace' })

  const route = useRoute()
  const userStore = useUserStore()
  const agentState = ref<Api.CustomerService.AgentState | null>(null)
  const agentProfile = ref<Api.CustomerService.AgentProfile | null>(null)
  const statusLoading = ref(false)
  let stateTimer: ReturnType<typeof setInterval> | null = null
  let unsubscribeRealtime: (() => void) | null = null

  const roles = computed(() => userStore.info.roles || [])
  const isAgent = computed(() => roles.value.includes('R_CUSTOMER_SERVICE'))
  const canManageSettings = computed(
    () => roles.value.includes('R_SUPER') || roles.value.includes('R_CUSTOMER_SERVICE_MANAGER')
  )
  const canViewSettings = computed(() => isAgent.value || canManageSettings.value)
  const profileAvatar = computed(() => agentProfile.value?.avatar || userStore.info.avatar || '')
  const isAvailable = computed(
    () => Boolean(agentState.value?.online) && agentState.value?.workStatus === 'AVAILABLE'
  )
  const navigation = computed(() => {
    const items = [
      {
        label: '会话',
        path: '/customer-service',
        icon: MessageCircle,
        active: route.path === '/customer-service'
      },
      {
        label: '概况',
        path: '/customer-service/overview',
        icon: BarChart3,
        active: route.path === '/customer-service/overview'
      }
    ]
    if (canViewSettings.value) {
      items.push({
        label: '设置',
        path: '/customer-service/settings',
        icon: Settings,
        active: route.path.startsWith('/customer-service/settings')
      })
    }
    return items
  })

  async function loadAgentState() {
    if (!isAgent.value) return
    try {
      agentState.value = await fetchCustomerServiceAgentState()
    } catch {
      agentState.value = null
    }
  }

  async function loadAgentProfile() {
    try {
      agentProfile.value = await fetchCustomerServiceAgentProfile()
    } catch {
      agentProfile.value = null
    }
  }

  async function toggleStatus() {
    if (!isAgent.value || statusLoading.value) return
    statusLoading.value = true
    try {
      const nextStatus = isAvailable.value ? 'OFFLINE' : 'AVAILABLE'
      agentState.value = await updateCustomerServiceAgentState(nextStatus)
      ElMessage.success(nextStatus === 'AVAILABLE' ? '已切换为在线' : '已切换为离线')
    } finally {
      statusLoading.value = false
    }
  }

  async function logout() {
    try {
      await ElMessageBox.confirm('确定退出当前客服账号吗？', '退出登录', {
        confirmButtonText: '退出',
        cancelButtonText: '取消',
        type: 'warning'
      })
      userStore.logOut()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
    }
  }

  function handleAvatarCommand(command: 'toggle-status' | 'logout') {
    if (command === 'toggle-status') {
      void toggleStatus()
      return
    }
    void logout()
  }

  onMounted(() => {
    unsubscribeRealtime = realtimeClient.subscribe(() => undefined)
    void loadAgentProfile()
    setTimeout(() => void loadAgentState(), 500)
    stateTimer = setInterval(() => {
      if (!document.hidden) void loadAgentState()
    }, 15000)
  })

  onBeforeUnmount(() => {
    unsubscribeRealtime?.()
    if (stateTimer) clearInterval(stateTimer)
  })
</script>

<style scoped>
  .customer-service-shell {
    display: flex;
    width: 100vw;
    min-width: 1024px;
    height: 100vh;
    min-height: 640px;
    overflow: hidden;
    color: #202124;
    background: #f3f3f3;
  }

  .service-rail {
    position: relative;
    z-index: 10;
    box-sizing: border-box;
    display: flex;
    flex: 0 0 78px;
    flex-direction: column;
    align-items: center;
    width: 78px;
    padding: 24px 0 18px;
    background: #f7f7f7;
    border-right: 1px solid #e8e8e8;
  }

  .profile-trigger {
    position: relative;
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    padding: 0;
    overflow: visible;
    color: #5f6368;
    cursor: pointer;
    background: #f0f0f0;
    border: 2px solid #d4d4d4;
    border-radius: 50%;
    outline: none;
  }

  .profile-trigger.is-online {
    border-color: #24bf67;
  }

  .profile-trigger img {
    width: 38px;
    height: 38px;
    object-fit: cover;
    border-radius: 50%;
  }

  .profile-status {
    position: absolute;
    right: -1px;
    bottom: 1px;
    box-sizing: border-box;
    width: 10px;
    height: 10px;
    background: #b9b9b9;
    border: 2px solid #f7f7f7;
    border-radius: 50%;
  }

  .is-online .profile-status {
    background: #14c466;
  }

  .rail-navigation {
    display: grid;
    gap: 18px;
    margin-top: 74px;
  }

  .rail-link {
    box-sizing: border-box;
    display: grid;
    place-items: center;
    width: 52px;
    min-height: 50px;
    color: #333;
    text-decoration: none;
    border-radius: 10px;
  }

  .rail-link span {
    display: none;
  }

  .rail-link:hover {
    color: #0bbf62;
    background: #f0f8f3;
  }

  .rail-link.is-active {
    color: #08bd60;
  }

  .rail-spacer {
    flex: 1;
  }

  .rail-state {
    display: flex;
    gap: 6px;
    align-items: center;
    font-size: 11px;
    color: #999;
  }

  .rail-state span {
    width: 7px;
    height: 7px;
    background: #b9b9b9;
    border-radius: 50%;
  }

  .rail-state span.is-online {
    background: #14c466;
  }

  .service-stage {
    flex: 1;
    min-width: 0;
    overflow: hidden;
  }

  :global(.profile-menu .el-dropdown-menu__item) {
    gap: 9px;
    min-width: 150px;
  }
</style>
