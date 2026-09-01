<template>
  <div class="settings-layout">
    <aside class="settings-sidebar" aria-label="客服设置导航">
      <button
        v-for="item in settingItems"
        :key="item.key"
        type="button"
        :class="{ active: activeSection === item.key }"
        :disabled="saving"
        @click="selectSection(item.key)"
      >
        <component :is="item.icon" :size="17" />
        {{ item.label }}
      </button>
    </aside>

    <main class="settings-content">
      <header class="page-heading">
        <div>
          <h1>{{ currentSection.label }}</h1>
          <p v-if="currentSection.description">{{ currentSection.description }}</p>
        </div>
        <button
          v-if="showSaveButton"
          type="button"
          class="save-button"
          :disabled="saving || loading"
          @click="save"
        >
          <LoaderCircle v-if="saving" class="spin" :size="16" />
          <Save v-else :size="16" />
          保存
        </button>
      </header>

      <section v-loading="loading" class="settings-surface">
        <template v-if="activeSection === 'automatic'">
          <div class="setting-block">
            <div class="setting-title">
              <div>
                <h2>自动接入</h2>
                <p>这是你的个人接待设置，不会影响其他客服。</p>
              </div>
              <CsSwitch v-model="personalForm.autoAcceptEnabled" :disabled="loading || saving" />
            </div>

            <div class="active-count">
              <span class="active-count__icon"><MessagesSquare :size="18" /></span>
              <span>
                当前接待中
                <strong>{{ personalActiveConversationCount }}</strong>
                人
              </span>
            </div>

            <div class="auto-accept-rule" :class="{ disabled: !personalForm.autoAcceptEnabled }">
              <span>当接待中人数少于</span>
              <input
                v-model.number="personalForm.autoAcceptBelow"
                type="number"
                min="1"
                max="1000"
                step="1"
                :disabled="!personalForm.autoAcceptEnabled"
                aria-label="自动接入触发人数"
              />
              <span>人时，一次最多自动接入</span>
              <input
                v-model.number="personalForm.autoAcceptCount"
                type="number"
                min="1"
                max="1000"
                step="1"
                :disabled="!personalForm.autoAcceptEnabled"
                aria-label="单次自动接入人数"
              />
              <span>人</span>
            </div>
          </div>
        </template>

        <template v-else-if="activeSection === 'notification'">
          <div class="setting-block notification-setting-block">
            <div class="setting-title">
              <div>
                <h2>显示消息预览</h2>
                <p>开启后，站内通知和 macOS 系统通知会显示用户发来的内容。</p>
              </div>
              <CsSwitch
                :model-value="notificationPreviewEnabled"
                :disabled="loading"
                @update:model-value="updateNotificationPreview"
              />
            </div>

            <div class="notification-preview-example">
              <img :src="notificationBrandIconUrl" alt="" />
              <span>
                <small>俊祥食品客服 · 新消息</small>
                <strong>用户 959554</strong>
                <em>
                  {{
                    notificationPreviewEnabled
                      ? '您好，请问这个商品今天可以发货吗？'
                      : '收到一条新消息，点击查看'
                  }}
                </em>
              </span>
              <Eye v-if="notificationPreviewEnabled" :size="18" />
              <EyeOff v-else :size="18" />
            </div>

            <p class="notification-local-note">
              此设置只保存在当前浏览器；共享电脑建议关闭，避免锁屏或通知中心显示顾客消息。
            </p>
          </div>
        </template>

        <AutoReplySettings
          v-else-if="activeSection === 'auto-reply'"
          ref="autoReplyRef"
          v-model:active-tab="autoReplyActiveTab"
          :can-update="canUpdateAutoReply"
          :can-update-welcome="canUpdateAutoReplyWelcome"
          :saving="saving"
        />

        <QuickReplySettings
          v-else-if="activeSection === 'quick-reply'"
          :can-update="canUpdateQuickReply"
        />

        <template v-else-if="activeSection === 'routing'">
          <div class="priority-note">
            <GitBranch :size="18" />
            <span>
              分流优先级：<strong>重复来访</strong>
              优先于下方选择的一种基础分流方式。
            </span>
          </div>

          <div class="setting-block">
            <div class="setting-title">
              <div>
                <div class="setting-kicker">优先级 1</div>
                <h2>重复来访优先</h2>
                <p>用户再次咨询时，原客服仍在线且可接待则优先继续服务。</p>
              </div>
              <CsSwitch v-model="routingForm.stickyAgentEnabled" :disabled="loading || saving" />
            </div>
            <label class="inline-field" :class="{ disabled: !routingForm.stickyAgentEnabled }">
              <span>有效期</span>
              <input
                v-model.number="routingForm.stickyWindowHours"
                type="number"
                min="1"
                max="720"
                step="1"
                :disabled="!routingForm.stickyAgentEnabled"
              />
              <b>小时</b>
            </label>
          </div>

          <div class="routing-heading">
            <div>
              <span>优先级 2</span>
              <h2>基础分流方式</h2>
            </div>
            <p>三种方式必须且只能开启一种。</p>
          </div>

          <div class="strategy-switch-list">
            <div
              v-for="strategy in strategies"
              :key="strategy.value"
              class="setting-block strategy-block"
              :class="{ active: routingForm.assignmentStrategy === strategy.value }"
            >
              <div class="setting-title">
                <div class="strategy-copy">
                  <span class="strategy-icon"><component :is="strategy.icon" :size="20" /></span>
                  <span>
                    <h2>{{ strategy.label }}</h2>
                    <p>{{ strategy.description }}</p>
                  </span>
                </div>
                <CsSwitch
                  :model-value="routingForm.assignmentStrategy === strategy.value"
                  :disabled="loading || saving"
                  @update:model-value="updateStrategy(strategy.value, $event)"
                />
              </div>

              <div
                v-if="
                  strategy.value === 'WEIGHTED' && routingForm.assignmentStrategy === 'WEIGHTED'
                "
                class="capacity-panel"
              >
                <div class="capacity-panel__heading">
                  <div>
                    <strong>客服最大接待人数</strong>
                    <p>权重分配开启时每位客服都需填写 1–1000，保存后系统会重新计算权重。</p>
                  </div>
                  <span>{{ routingForm.agents.length }} 位客服</span>
                </div>

                <div v-if="routingForm.agents.length" class="capacity-table">
                  <div class="capacity-row capacity-row--header">
                    <span>客服</span>
                    <span>状态</span>
                    <span>最大接待人数</span>
                    <span>系统权重</span>
                  </div>
                  <div
                    v-for="agent in routingForm.agents"
                    :key="agent.adminUserId"
                    class="capacity-row"
                  >
                    <span class="agent-cell">
                      <i><img :src="effectiveAvatarUrl" alt="" /></i>
                      <span>
                        <strong>{{ agent.serviceName }}</strong>
                        <small>@{{ agent.username }}</small>
                      </span>
                    </span>
                    <span>
                      <em class="online-status" :class="{ online: agent.online }">
                        {{ agent.online ? '在线' : '离线' }}
                      </em>
                    </span>
                    <span class="capacity-input">
                      <input
                        :value="agent.maxActiveConversations ?? ''"
                        type="number"
                        min="1"
                        max="1000"
                        step="1"
                        placeholder="请输入"
                        :aria-label="`${agent.serviceName}最大接待人数`"
                        @input="updateRoutingCapacity(agent, $event)"
                      />
                      <small>人</small>
                    </span>
                    <span class="weight-cell">
                      <strong>{{ formatWeightPercent(agent.calculatedWeightPercent) }}</strong>
                      <small>保存后重算</small>
                    </span>
                  </div>
                </div>
                <div v-else class="capacity-empty">暂无可参与分流的客服</div>
              </div>
            </div>
          </div>
        </template>

        <template v-else-if="activeSection === 'identity'">
          <div class="setting-block identity-block">
            <div class="setting-title">
              <div>
                <h2>个人信息</h2>
              </div>
            </div>

            <div class="personal-info-list">
              <div class="personal-info-row avatar-row">
                <span class="personal-info-label">客服头像</span>
                <div class="personal-info-control avatar-field">
                  <div v-if="canManageIdentity" class="avatar-picker-row">
                    <AssetPicker
                      v-model="avatarAsset"
                      media-kind="IMAGE"
                      compact
                      :fallback-url="defaultCustomerServiceAvatar"
                      :disabled="loading || saving"
                    />
                    <ElButton
                      type="primary"
                      link
                      :disabled="loading || saving || !hasCustomAvatar"
                      @click="restoreDefaultAvatar"
                    >
                      恢复默认头像
                    </ElButton>
                  </div>
                  <div v-else class="readonly-avatar" aria-label="客服头像">
                    <img :src="effectiveAvatarUrl" alt="客服头像" />
                  </div>
                  <small v-if="canManageIdentity">
                    未上传时使用默认机器人头像；上传后，小程序与后台将展示自定义头像。
                  </small>
                </div>
              </div>

              <div v-if="canManageIdentity" class="personal-info-row">
                <span class="personal-info-label">默认客服名称</span>
                <div class="personal-info-control">
                  <label class="form-field">
                    <input
                      v-model="identityForm.defaultServiceName"
                      maxlength="64"
                      placeholder="商城客服"
                      aria-label="默认客服名称"
                    />
                    <small>客服未设置个人名称时使用。</small>
                  </label>
                </div>
              </div>

              <div v-if="hasPersonalSettings" class="personal-info-row">
                <span class="personal-info-label">客服名称</span>
                <div class="personal-info-control">
                  <label class="form-field">
                    <input
                      v-model="personalServiceName"
                      maxlength="64"
                      :placeholder="effectiveDefaultServiceName"
                      aria-label="客服名称"
                    />
                    <small v-if="personalForm.serviceName === null">
                      当前使用默认客服名称“{{ effectiveDefaultServiceName }}”。
                    </small>
                    <small v-else>留空并保存，可恢复使用默认客服名称。</small>
                  </label>
                </div>
              </div>
            </div>
          </div>
        </template>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref, watch } from 'vue'
  import { ElMessage } from 'element-plus'
  import {
    BellRing,
    Bot,
    ChartNoAxesColumnIncreasing,
    Eye,
    EyeOff,
    GitBranch,
    ListRestart,
    LoaderCircle,
    MessagesSquare,
    Save,
    Scale,
    TextQuote,
    UserRoundCog,
    Waypoints,
    Zap
  } from '@lucide/vue'
  import { useRoute, useRouter } from 'vue-router'
  import defaultCustomerServiceAvatar from '@/assets/images/customer-service/default-avatar.jpg'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import CsSwitch from '@/components/customer-ui/CsSwitch.vue'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useUserStore } from '@/store/modules/user'
  import {
    isCustomerServiceNotificationPreviewEnabled,
    setCustomerServiceNotificationPreviewEnabled
  } from '@/utils/customer-service-notification-state'
  import {
    fetchCustomerServiceManagementConfig,
    fetchCustomerServicePersonalSettings,
    updateCustomerServiceManagementIdentity,
    updateCustomerServiceManagementRouting,
    updateCustomerServicePersonalSettings
  } from '@/api/customer-service'
  import AutoReplySettings from './components/AutoReplySettings.vue'
  import QuickReplySettings from './components/QuickReplySettings.vue'

  defineOptions({ name: 'CustomerServiceSettings' })

  type SectionKey =
    | 'automatic'
    | 'notification'
    | 'auto-reply'
    | 'quick-reply'
    | 'routing'
    | 'identity'
  type AutoReplyTab = 'common' | 'welcome' | 'offline' | 'smart'

  const route = useRoute()
  const router = useRouter()
  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const roles = computed(() => userStore.info.roles || [])
  const hasPersonalSettings = computed(() => roles.value.includes('R_CUSTOMER_SERVICE'))
  const canManageRouting = computed(
    () => roles.value.includes('R_SUPER') || roles.value.includes('R_CUSTOMER_SERVICE_MANAGER')
  )
  const canManageIdentity = computed(() => roles.value.includes('R_CUSTOMER_SERVICE_MANAGER'))
  const canReadManagementConfig = computed(() => canManageRouting.value || canManageIdentity.value)
  const canReadAutoReply = computed(() => hasAuth('customer-service:auto-reply:read'))
  const canUpdateAutoReplyWelcome = computed(
    () => hasPersonalSettings.value && hasAuth('customer-service:auto-reply:welcome:update')
  )
  const canUpdateAutoReply = computed(() => hasAuth('customer-service:auto-reply:update'))
  const canReadQuickReply = computed(() => hasAuth('customer-service:quick-reply:read'))
  const canUpdateQuickReply = computed(() => hasAuth('customer-service:quick-reply:update'))

  const allSettingItems = {
    automatic: {
      key: 'automatic' as const,
      label: '自动接入',
      description: '控制自己的自动接入规则。',
      icon: Zap
    },
    notification: {
      key: 'notification' as const,
      label: '消息通知',
      description: '控制当前浏览器里的客服消息预览。',
      icon: BellRing
    },
    'auto-reply': {
      key: 'auto-reply' as const,
      label: '自动回复',
      description: '公共自动回复由客服管理员维护；客服可编辑自己的接入欢迎语。',
      icon: Bot
    },
    'quick-reply': {
      key: 'quick-reply' as const,
      label: '快捷回复',
      description: '维护全体客服共用的常见短语。',
      icon: TextQuote
    },
    routing: {
      key: 'routing' as const,
      label: '会话分流',
      description: '设置重复来访优先级和客服间的基础分流方式。',
      icon: Waypoints
    },
    identity: {
      key: 'identity' as const,
      label: '个人设置',
      description: '',
      icon: UserRoundCog
    }
  }
  const settingItems = computed(() => {
    const items = []
    if (hasPersonalSettings.value) items.push(allSettingItems.automatic)
    if (hasPersonalSettings.value) items.push(allSettingItems.notification)
    if (canReadAutoReply.value) items.push(allSettingItems['auto-reply'])
    if (canReadQuickReply.value) items.push(allSettingItems['quick-reply'])
    if (canManageRouting.value) items.push(allSettingItems.routing)
    if (hasPersonalSettings.value || canManageIdentity.value) items.push(allSettingItems.identity)
    return items
  })
  const activeSection = ref<SectionKey>(resolveAvailableSection(route.query.section))
  const currentSection = computed(
    () =>
      settingItems.value.find((item) => item.key === activeSection.value) ||
      settingItems.value[0] ||
      allSettingItems.automatic
  )

  const loading = ref(true)
  const saving = ref(false)
  const autoReplyRef = ref<InstanceType<typeof AutoReplySettings> | null>(null)
  const autoReplyActiveTab = ref<AutoReplyTab>('common')
  const canUpdateCurrentAutoReply = computed(() =>
    autoReplyActiveTab.value === 'welcome'
      ? canUpdateAutoReplyWelcome.value
      : canUpdateAutoReply.value
  )
  const showSaveButton = computed(
    () =>
      activeSection.value !== 'quick-reply' &&
      activeSection.value !== 'notification' &&
      (activeSection.value !== 'auto-reply' || canUpdateCurrentAutoReply.value)
  )
  const notificationPreviewEnabled = ref(isCustomerServiceNotificationPreviewEnabled())
  const notificationBrandIconUrl = `${import.meta.env.BASE_URL}pwa/icon-192.png`
  const personalActiveConversationCount = ref(0)
  const personalForm = reactive<Api.CustomerService.PersonalSettingsForm>({
    serviceName: null,
    autoAcceptEnabled: false,
    autoAcceptBelow: 1,
    autoAcceptCount: 1
  })
  const personalIdentity = reactive({
    defaultServiceName: '商城客服',
    avatar: ''
  })
  const routingForm = reactive<{
    assignmentStrategy: Api.CustomerService.AssignmentStrategy
    stickyAgentEnabled: boolean
    stickyWindowHours: number
    agents: Api.CustomerService.RoutingAgent[]
  }>({
    assignmentStrategy: 'LEAST_LOADED',
    stickyAgentEnabled: true,
    stickyWindowHours: 48,
    agents: []
  })
  const identityForm = reactive<Api.CustomerService.ManagementIdentityForm>({
    defaultServiceName: '商城客服',
    avatarFileId: null
  })
  const avatarAsset = ref<Api.Common.AssetValue>({ fileId: null, url: '' })
  const hasCustomAvatar = computed(() => avatarAsset.value.fileId !== null)
  const effectiveDefaultServiceName = computed(() =>
    canManageIdentity.value
      ? identityForm.defaultServiceName || '商城客服'
      : personalIdentity.defaultServiceName || '商城客服'
  )
  const effectiveAvatarUrl = computed(
    () => avatarAsset.value.url || personalIdentity.avatar || defaultCustomerServiceAvatar
  )
  const personalServiceName = computed({
    get: () => personalForm.serviceName ?? effectiveDefaultServiceName.value,
    set: (value: string) => {
      personalForm.serviceName = value
    }
  })
  const strategies = [
    {
      value: 'LEAST_LOADED' as const,
      label: '最少会话优先',
      description: '优先分配给当前活跃会话更少的在线客服。',
      icon: ChartNoAxesColumnIncreasing
    },
    {
      value: 'ROUND_ROBIN' as const,
      label: '轮询分配',
      description: '按最近分配时间轮换，尽量保持接待机会均衡。',
      icon: ListRestart
    },
    {
      value: 'WEIGHTED' as const,
      label: '客服权重',
      description: '根据每位客服的最大接待人数自动计算权重并分配会话。',
      icon: Scale
    }
  ]

  function normalizeSection(value: unknown): SectionKey {
    return value === 'notification' ||
      value === 'auto-reply' ||
      value === 'quick-reply' ||
      value === 'routing' ||
      value === 'identity'
      ? value
      : 'automatic'
  }

  function updateNotificationPreview(enabled: boolean) {
    notificationPreviewEnabled.value = enabled
    setCustomerServiceNotificationPreviewEnabled(enabled)
    ElMessage.success(enabled ? '已显示消息预览' : '已隐藏消息预览')
  }

  function resolveAvailableSection(value: unknown): SectionKey {
    const requested = normalizeSection(value)
    if (settingItems.value.some((item) => item.key === requested)) return requested
    return settingItems.value[0]?.key || 'automatic'
  }

  function selectSection(section: SectionKey) {
    if (saving.value) return
    activeSection.value = section
    void router.replace({ path: route.path, query: { ...route.query, section } })
  }

  function applyPersonalSettings(settings: Api.CustomerService.PersonalSettings) {
    personalForm.serviceName = settings.serviceNameOverride
    personalForm.autoAcceptEnabled = settings.autoAcceptEnabled
    personalForm.autoAcceptBelow = settings.autoAcceptBelow
    personalForm.autoAcceptCount = settings.autoAcceptCount
    personalActiveConversationCount.value = settings.activeConversationCount
    personalIdentity.defaultServiceName = settings.defaultServiceName
    personalIdentity.avatar = settings.avatar
  }

  function applyRoutingConfig(config: Api.CustomerService.ManagementConfig) {
    routingForm.assignmentStrategy = config.assignmentStrategy
    routingForm.stickyAgentEnabled = config.stickyAgentEnabled
    routingForm.stickyWindowHours = config.stickyWindowHours
    routingForm.agents = config.routingAgents.map((agent) => ({ ...agent }))
  }

  function applyIdentityConfig(config: Api.CustomerService.ManagementConfig) {
    identityForm.defaultServiceName = config.defaultServiceName
    identityForm.avatarFileId = config.avatarFileId
    avatarAsset.value = { fileId: config.avatarFileId, url: config.avatar }
  }

  async function load() {
    loading.value = true
    try {
      const requests: Promise<void>[] = []
      if (hasPersonalSettings.value) {
        requests.push(fetchCustomerServicePersonalSettings().then(applyPersonalSettings))
      }
      if (canReadManagementConfig.value) {
        requests.push(
          fetchCustomerServiceManagementConfig().then((config) => {
            applyRoutingConfig(config)
            applyIdentityConfig(config)
          })
        )
      }
      await Promise.all(requests)
    } finally {
      loading.value = false
    }
  }

  function isIntegerInRange(value: number, min: number, max: number) {
    return Number.isInteger(value) && value >= min && value <= max
  }

  async function savePersonalSettings(validateAutoAccept = true) {
    if (validateAutoAccept && personalForm.autoAcceptEnabled) {
      if (!isIntegerInRange(personalForm.autoAcceptBelow, 1, 1000)) {
        ElMessage.warning('自动接入触发人数需在 1–1000 之间')
        return false
      }
      if (!isIntegerInRange(personalForm.autoAcceptCount, 1, 1000)) {
        ElMessage.warning('单次自动接入人数需在 1–1000 之间')
        return false
      }
    }
    const autoAcceptBelow = isIntegerInRange(personalForm.autoAcceptBelow, 1, 1000)
      ? personalForm.autoAcceptBelow
      : 5
    const autoAcceptCount = isIntegerInRange(personalForm.autoAcceptCount, 1, 1000)
      ? personalForm.autoAcceptCount
      : 1
    const enteredServiceName = personalServiceName.value.trim()
    const serviceName =
      !enteredServiceName || enteredServiceName === effectiveDefaultServiceName.value.trim()
        ? null
        : enteredServiceName
    applyPersonalSettings(
      await updateCustomerServicePersonalSettings({
        ...personalForm,
        serviceName,
        autoAcceptBelow,
        autoAcceptCount
      })
    )
    return true
  }

  async function saveRoutingSettings() {
    if (
      routingForm.stickyAgentEnabled &&
      !isIntegerInRange(routingForm.stickyWindowHours, 1, 720)
    ) {
      ElMessage.warning('重复来访优先有效期需在 1–720 小时之间')
      return false
    }
    const stickyWindowHours = isIntegerInRange(routingForm.stickyWindowHours, 1, 720)
      ? routingForm.stickyWindowHours
      : 48
    const invalidAgent = routingForm.agents.find((agent) => {
      if (routingForm.assignmentStrategy === 'WEIGHTED') {
        return (
          agent.maxActiveConversations === null ||
          !isIntegerInRange(agent.maxActiveConversations, 1, 1000)
        )
      }
      return (
        agent.maxActiveConversations !== null &&
        !isIntegerInRange(agent.maxActiveConversations, 1, 1000)
      )
    })
    if (invalidAgent) {
      ElMessage.warning(
        routingForm.assignmentStrategy === 'WEIGHTED'
          ? `请为${invalidAgent.serviceName}填写 1–1000 的最大接待人数`
          : `${invalidAgent.serviceName}的最大接待人数需留空或填写 1–1000`
      )
      return false
    }
    applyRoutingConfig(
      await updateCustomerServiceManagementRouting({
        assignmentStrategy: routingForm.assignmentStrategy,
        stickyAgentEnabled: routingForm.stickyAgentEnabled,
        stickyWindowHours,
        agents: routingForm.agents.map((agent) => ({
          adminUserId: agent.adminUserId,
          maxActiveConversations: agent.maxActiveConversations
        }))
      })
    )
    return true
  }

  async function saveIdentitySettings() {
    if (canManageIdentity.value && !identityForm.defaultServiceName.trim()) {
      ElMessage.warning('请填写默认客服名称')
      return false
    }
    const requests: Promise<unknown>[] = []
    if (hasPersonalSettings.value) {
      requests.push(savePersonalSettings(false))
    }
    if (canManageIdentity.value) {
      requests.push(
        updateCustomerServiceManagementIdentity({
          defaultServiceName: identityForm.defaultServiceName.trim(),
          avatarFileId: avatarAsset.value.fileId
        }).then(applyIdentityConfig)
      )
    }
    await Promise.all(requests)
    return requests.length > 0
  }

  function restoreDefaultAvatar() {
    avatarAsset.value = { fileId: null, url: '' }
    identityForm.avatarFileId = null
    ElMessage.info('已切换为默认头像，点击保存后生效')
  }

  async function save() {
    saving.value = true
    try {
      let saved = false
      if (activeSection.value === 'automatic') saved = await savePersonalSettings()
      else if (activeSection.value === 'auto-reply') {
        saved = Boolean(await autoReplyRef.value?.save())
      } else if (activeSection.value === 'routing') saved = await saveRoutingSettings()
      else saved = await saveIdentitySettings()
      if (saved) ElMessage.success('设置已保存')
    } finally {
      saving.value = false
    }
  }

  function updateStrategy(strategy: Api.CustomerService.AssignmentStrategy, enabled: boolean) {
    if (enabled) {
      routingForm.assignmentStrategy = strategy
      return
    }
    if (routingForm.assignmentStrategy === strategy) {
      ElMessage.info('需保留一种基础分流方式')
    }
  }

  function updateRoutingCapacity(agent: Api.CustomerService.RoutingAgent, event: Event) {
    const value = (event.target as HTMLInputElement).value
    agent.maxActiveConversations = value === '' ? null : Number(value)
  }

  function formatWeightPercent(value: number) {
    return `${value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}%`
  }

  watch(
    () => route.query.section,
    (section) => {
      activeSection.value = resolveAvailableSection(section)
    }
  )
  watch(settingItems, () => {
    activeSection.value = resolveAvailableSection(route.query.section)
  })
  onMounted(load)
</script>

<style scoped>
  .settings-layout {
    display: flex;
    height: 100%;
    overflow: hidden;
    color: #242424;
    background: #f1f1f1;
  }

  .settings-sidebar {
    box-sizing: border-box;
    flex: 0 0 260px;
    width: 260px;
    padding: 28px;
    background: #fafafa;
    border-right: 1px solid #e6e6e6;
  }

  .settings-sidebar button {
    display: flex;
    gap: 11px;
    align-items: center;
    width: 100%;
    height: 52px;
    padding: 0 15px;
    font-size: 15px;
    color: #333;
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-bottom: 1px solid #ececec;
  }

  .settings-sidebar button.active {
    font-weight: 650;
    color: #0abd61;
  }

  .settings-sidebar button:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }

  .settings-content {
    flex: 1;
    min-width: 0;
    padding: 32px 42px;
    overflow: auto;
  }

  .page-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    max-width: 1180px;
    margin: 0 auto 24px;
  }

  h1 {
    margin: 0;
    font-size: 26px;
    font-weight: 650;
    color: #222;
  }

  .page-heading p {
    margin: 7px 0 0;
    font-size: 13px;
    color: #999;
  }

  .save-button {
    display: flex;
    gap: 7px;
    align-items: center;
    justify-content: center;
    min-width: 100px;
    height: 40px;
    font-weight: 650;
    color: #fff;
    cursor: pointer;
    background: #0ac666;
    border: 0;
    border-radius: 6px;
  }

  .save-button:disabled {
    cursor: default;
    opacity: 0.55;
  }

  .settings-surface {
    max-width: 1180px;
    min-height: 260px;
    margin: 0 auto;
  }

  .setting-block {
    padding: 28px 36px;
    margin-bottom: 18px;
    background: #fff;
    border: 1px solid transparent;
    border-radius: 12px;
  }

  .notification-setting-block {
    max-width: 760px;
  }

  .notification-preview-example {
    display: grid;
    grid-template-columns: 46px minmax(0, 1fr) auto;
    gap: 13px;
    align-items: center;
    padding: 16px 18px;
    margin-top: 24px;
    background: #fbfaf8;
    border: 1px solid #eee8e3;
    border-radius: 13px;
    box-shadow: 0 8px 24px rgb(65 45 35 / 6%);
  }

  .notification-preview-example > img {
    width: 46px;
    height: 46px;
    object-fit: contain;
  }

  .notification-preview-example > span {
    display: grid;
    gap: 3px;
    min-width: 0;
  }

  .notification-preview-example small {
    font-size: 11px;
    font-weight: 650;
    color: #9b2821;
  }

  .notification-preview-example strong {
    overflow: hidden;
    font-size: 14px;
    color: #272321;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .notification-preview-example em {
    overflow: hidden;
    font-size: 12px;
    font-style: normal;
    color: #77716d;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .notification-preview-example > svg {
    color: #aaa29c;
  }

  .notification-local-note {
    padding: 11px 13px;
    margin: 16px 0 0;
    font-size: 12px;
    line-height: 1.6;
    color: #807871;
    background: #f6f3f0;
    border-radius: 8px;
  }

  .setting-title {
    display: flex;
    gap: 28px;
    align-items: flex-start;
    justify-content: space-between;
  }

  h2 {
    margin: 0;
    font-size: 17px;
    font-weight: 650;
    color: #292929;
  }

  .setting-title p,
  .capacity-panel__heading p {
    margin: 7px 0 0;
    font-size: 13px;
    line-height: 1.6;
    color: #999;
  }

  .active-count {
    display: inline-flex;
    gap: 9px;
    align-items: center;
    padding: 9px 12px;
    margin-top: 24px;
    font-size: 13px;
    color: #5f6368;
    background: #f7f8f8;
    border-radius: 7px;
  }

  .active-count__icon {
    display: grid;
    place-items: center;
    color: #09ae59;
  }

  .active-count strong {
    margin: 0 3px;
    font-size: 16px;
    color: #222;
  }

  .auto-accept-rule,
  .inline-field {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
    margin-top: 22px;
    font-size: 14px;
    color: #444;
  }

  .auto-accept-rule input,
  .inline-field input,
  .capacity-input input {
    box-sizing: border-box;
    width: 100px;
    height: 38px;
    padding: 0 12px;
    color: #222;
    background: #fff;
    border: 1px solid #dedede;
    border-radius: 6px;
    outline: none;
  }

  .auto-accept-rule input:focus,
  .inline-field input:focus,
  .capacity-input input:focus,
  .form-field input:focus {
    border-color: #27c875;
    box-shadow: 0 0 0 3px rgb(39 200 117 / 9%);
  }

  .disabled {
    opacity: 0.5;
  }

  .form-field {
    display: grid;
    gap: 8px;
    margin-top: 22px;
    font-size: 14px;
    color: #444;
  }

  .form-field input {
    box-sizing: border-box;
    width: 100%;
    height: 42px;
    padding: 0 13px;
    border: 1px solid #dedede;
    border-radius: 6px;
    outline: none;
  }

  .form-field input[readonly] {
    color: #666;
    cursor: default;
    background: #f7f8f8;
  }

  .form-field small {
    color: #aaa;
  }

  .priority-note {
    display: flex;
    gap: 10px;
    align-items: center;
    padding: 14px 18px;
    margin-bottom: 18px;
    font-size: 13px;
    color: #147b45;
    background: #ebfaf2;
    border: 1px solid #d5f2e2;
    border-radius: 10px;
  }

  .priority-note strong {
    font-weight: 700;
  }

  .setting-kicker,
  .routing-heading span {
    margin-bottom: 6px;
    font-size: 11px;
    font-weight: 700;
    color: #0aad59;
    text-transform: uppercase;
    letter-spacing: 0.08em;
  }

  .inline-field b {
    font-weight: 400;
  }

  .routing-heading {
    display: flex;
    align-items: end;
    justify-content: space-between;
    padding: 8px 4px 13px;
  }

  .routing-heading p {
    margin: 0;
    font-size: 12px;
    color: #999;
  }

  .strategy-switch-list {
    display: grid;
    gap: 14px;
  }

  .strategy-block {
    margin-bottom: 0;
    transition:
      border-color 0.18s ease,
      box-shadow 0.18s ease;
  }

  .strategy-block.active {
    border-color: #bcebd2;
    box-shadow: 0 0 0 1px rgb(10 198 102 / 6%);
  }

  .strategy-copy {
    display: flex;
    gap: 14px;
    align-items: flex-start;
  }

  .strategy-icon {
    display: grid;
    flex: 0 0 auto;
    place-items: center;
    width: 38px;
    height: 38px;
    color: #777;
    background: #f5f5f5;
    border-radius: 9px;
  }

  .strategy-block.active .strategy-icon {
    color: #08af58;
    background: #eaf9f1;
  }

  .strategy-copy h2 {
    padding-top: 1px;
  }

  .capacity-panel {
    margin-top: 25px;
    overflow: hidden;
    border: 1px solid #e8ecea;
    border-radius: 10px;
  }

  .capacity-panel__heading {
    display: flex;
    gap: 20px;
    align-items: flex-start;
    justify-content: space-between;
    padding: 18px 20px;
    background: #fafbfa;
    border-bottom: 1px solid #ecefed;
  }

  .capacity-panel__heading > span {
    flex: 0 0 auto;
    padding: 4px 9px;
    font-size: 12px;
    color: #68716c;
    background: #eef1ef;
    border-radius: 999px;
  }

  .capacity-table {
    width: 100%;
    overflow-x: auto;
  }

  .capacity-row {
    display: grid;
    grid-template-columns: minmax(210px, 1.5fr) 100px minmax(170px, 1fr) 110px;
    min-width: 720px;
    min-height: 68px;
    padding: 0 20px;
    border-bottom: 1px solid #f0f1f0;
  }

  .capacity-row:last-child {
    border-bottom: 0;
  }

  .capacity-row > span {
    display: flex;
    align-items: center;
  }

  .capacity-row--header {
    min-height: 42px;
    font-size: 12px;
    color: #929994;
    background: #fdfefd;
  }

  .agent-cell {
    gap: 10px;
  }

  .agent-cell > i {
    display: grid;
    flex: 0 0 auto;
    place-items: center;
    width: 34px;
    height: 34px;
    overflow: hidden;
    font-size: 13px;
    font-style: normal;
    font-weight: 700;
    color: #087c42;
    background: #e8f8f0;
    border-radius: 50%;
  }

  .agent-cell > i img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .agent-cell > span,
  .weight-cell {
    display: grid !important;
    gap: 3px;
  }

  .agent-cell strong {
    font-size: 13px;
  }

  .agent-cell small,
  .weight-cell small {
    font-size: 11px;
    color: #a0a5a2;
  }

  .online-status {
    position: relative;
    padding-left: 13px;
    font-size: 12px;
    font-style: normal;
    color: #929994;
  }

  .online-status::before {
    position: absolute;
    top: 50%;
    left: 0;
    width: 7px;
    height: 7px;
    content: '';
    background: #c9cecb;
    border-radius: 50%;
    transform: translateY(-50%);
  }

  .online-status.online {
    color: #08974d;
  }

  .online-status.online::before {
    background: #12be65;
  }

  .capacity-input {
    gap: 7px;
  }

  .capacity-input input {
    width: 116px;
  }

  .capacity-input small {
    color: #999;
  }

  .weight-cell strong {
    font-size: 14px;
    color: #087d42;
  }

  .capacity-empty {
    display: grid;
    place-items: center;
    min-height: 120px;
    font-size: 13px;
    color: #aaa;
  }

  .identity-block {
    max-width: 940px;
    margin-bottom: 0;
  }

  .personal-info-list {
    display: grid;
    gap: 10px;
    margin-top: 12px;
  }

  .personal-info-row {
    display: grid;
    grid-template-columns: 150px minmax(0, 1fr);
    gap: 28px;
    padding: 8px 0;
  }

  .personal-info-label {
    padding-top: 11px;
    font-size: 14px;
    font-weight: 600;
    color: #3e3e3e;
  }

  .avatar-row .personal-info-label {
    padding-top: 33px;
  }

  .personal-info-control {
    min-width: 0;
    max-width: 600px;
  }

  .personal-info-control .form-field {
    margin-top: 0;
  }

  .personal-info-control > small {
    display: block;
    margin-top: 8px;
    font-size: 12px;
    color: #999;
  }

  .avatar-field :deep(.asset-picker__compact-target) {
    width: 84px;
    height: 84px;
    border-radius: 50%;
  }

  .avatar-picker-row {
    display: flex;
    gap: 12px;
    align-items: center;
  }

  .readonly-avatar {
    display: grid;
    place-items: center;
    width: 84px;
    height: 84px;
    overflow: hidden;
    color: #08b95d;
    background: #eafaf1;
    border: 1px solid #dcece3;
    border-radius: 50%;
  }

  .readonly-avatar img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .spin {
    animation: spin 0.9s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (width <= 1180px) {
    .settings-sidebar {
      flex-basis: 220px;
      width: 220px;
      padding-inline: 20px;
    }

    .settings-content {
      padding: 26px;
    }

    .personal-info-row {
      grid-template-columns: 130px minmax(0, 1fr);
      gap: 24px;
    }
  }
</style>
