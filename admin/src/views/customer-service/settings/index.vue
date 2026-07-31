<template>
  <div class="settings-layout">
    <aside class="settings-sidebar" aria-label="客服设置导航">
      <button
        v-for="item in settingItems"
        :key="item.key"
        type="button"
        :class="{ active: activeSection === item.key }"
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
          <p>{{ currentSection.description }}</p>
        </div>
        <button type="button" class="save-button" :disabled="saving || loading" @click="save">
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

          <div class="setting-block personal-name-block">
            <div class="setting-title">
              <div>
                <h2>我的客服名称</h2>
                <p>客户在会话中看到的客服名称，最多 64 个字符。</p>
              </div>
            </div>
            <label class="form-field">
              <span>客服名称</span>
              <input
                v-model="personalForm.serviceName"
                maxlength="64"
                placeholder="请输入客服名称"
              />
            </label>
          </div>
        </template>

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
                      <i>{{ avatarText(agent.serviceName) }}</i>
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

        <template v-else>
          <div class="setting-block identity-block">
            <div class="setting-title">
              <div>
                <h2>客服默认形象</h2>
                <p>统一头像仅客服管理员可配置，个人名称为空时使用默认客服名称。</p>
              </div>
            </div>

            <div class="identity-layout">
              <div class="identity-preview">
                <span class="service-avatar">
                  <img v-if="avatarAsset.url" :src="avatarAsset.url" alt="客服统一头像预览" />
                  <Headset v-else :size="28" />
                </span>
                <span>
                  <strong>{{ identityForm.defaultServiceName || '商城客服' }}</strong>
                  <small>客户侧展示预览</small>
                </span>
              </div>

              <div class="identity-fields">
                <label class="form-field">
                  <span>默认客服名称</span>
                  <input
                    v-model="identityForm.defaultServiceName"
                    maxlength="64"
                    placeholder="商城客服"
                  />
                  <small>客服没有设置个人名称时使用。</small>
                </label>

                <div class="form-field avatar-field">
                  <span>统一头像</span>
                  <AssetPicker
                    v-model="avatarAsset"
                    media-kind="IMAGE"
                    compact
                    :disabled="loading || saving"
                  />
                  <small>点击上传图片或从素材库选择，建议使用正方形图片。</small>
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
    ChartNoAxesColumnIncreasing,
    GitBranch,
    Headset,
    ListRestart,
    LoaderCircle,
    MessagesSquare,
    Save,
    Scale,
    UserRoundCog,
    Waypoints,
    Zap
  } from '@lucide/vue'
  import { useRoute, useRouter } from 'vue-router'
  import AssetPicker from '@/components/business/asset-picker/index.vue'
  import CsSwitch from '@/components/customer-ui/CsSwitch.vue'
  import { useUserStore } from '@/store/modules/user'
  import {
    fetchCustomerServiceManagementConfig,
    fetchCustomerServicePersonalSettings,
    updateCustomerServiceManagementIdentity,
    updateCustomerServiceManagementRouting,
    updateCustomerServicePersonalSettings
  } from '@/api/customer-service'

  defineOptions({ name: 'CustomerServiceSettings' })

  type SectionKey = 'automatic' | 'routing' | 'identity'

  const route = useRoute()
  const router = useRouter()
  const userStore = useUserStore()
  const roles = computed(() => userStore.info.roles || [])
  const hasPersonalSettings = computed(() => roles.value.includes('R_CUSTOMER_SERVICE'))
  const canManageRouting = computed(
    () => roles.value.includes('R_SUPER') || roles.value.includes('R_CUSTOMER_SERVICE_MANAGER')
  )
  const canManageIdentity = computed(() => roles.value.includes('R_CUSTOMER_SERVICE_MANAGER'))
  const canReadManagementConfig = computed(() => canManageRouting.value || canManageIdentity.value)

  const allSettingItems = {
    automatic: {
      key: 'automatic' as const,
      label: '自动接入',
      description: '控制自己的自动接入规则和客户可见名称。',
      icon: Zap
    },
    routing: {
      key: 'routing' as const,
      label: '会话分流',
      description: '设置重复来访优先级和客服间的基础分流方式。',
      icon: Waypoints
    },
    identity: {
      key: 'identity' as const,
      label: '对外形象',
      description: '设置客户可见的默认客服名称和统一头像。',
      icon: UserRoundCog
    }
  }
  const settingItems = computed(() => {
    const items = []
    if (hasPersonalSettings.value) items.push(allSettingItems.automatic)
    if (canManageRouting.value) items.push(allSettingItems.routing)
    if (canManageIdentity.value) items.push(allSettingItems.identity)
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
  const personalActiveConversationCount = ref(0)
  const personalForm = reactive<Api.CustomerService.PersonalSettingsForm>({
    serviceName: '',
    autoAcceptEnabled: false,
    autoAcceptBelow: 1,
    autoAcceptCount: 1
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
    return value === 'routing' || value === 'identity' ? value : 'automatic'
  }

  function resolveAvailableSection(value: unknown): SectionKey {
    const requested = normalizeSection(value)
    if (settingItems.value.some((item) => item.key === requested)) return requested
    return settingItems.value[0]?.key || 'automatic'
  }

  function selectSection(section: SectionKey) {
    activeSection.value = section
    void router.replace({ path: route.path, query: { ...route.query, section } })
  }

  function applyPersonalSettings(settings: Api.CustomerService.PersonalSettings) {
    personalForm.serviceName = settings.serviceName
    personalForm.autoAcceptEnabled = settings.autoAcceptEnabled
    personalForm.autoAcceptBelow = settings.autoAcceptBelow
    personalForm.autoAcceptCount = settings.autoAcceptCount
    personalActiveConversationCount.value = settings.activeConversationCount
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

  async function savePersonalSettings() {
    const serviceName = personalForm.serviceName.trim()
    if (!serviceName) {
      ElMessage.warning('请填写客服名称')
      return false
    }
    if (personalForm.autoAcceptEnabled) {
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
    const defaultServiceName = identityForm.defaultServiceName.trim()
    if (!defaultServiceName) {
      ElMessage.warning('请填写默认客服名称')
      return false
    }
    const config = await updateCustomerServiceManagementIdentity({
      defaultServiceName,
      avatarFileId: avatarAsset.value.fileId
    })
    applyIdentityConfig(config)
    return true
  }

  async function save() {
    saving.value = true
    try {
      let saved = false
      if (activeSection.value === 'automatic') saved = await savePersonalSettings()
      else if (activeSection.value === 'routing') saved = await saveRoutingSettings()
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

  function avatarText(name: string) {
    return name.trim().slice(0, 1).toUpperCase() || '客'
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

  .personal-name-block .form-field {
    max-width: 560px;
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
    font-size: 13px;
    font-style: normal;
    font-weight: 700;
    color: #087c42;
    background: #e8f8f0;
    border-radius: 50%;
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
  }

  .identity-layout {
    display: grid;
    grid-template-columns: 260px minmax(0, 1fr);
    gap: 34px;
    margin-top: 24px;
  }

  .identity-preview {
    display: flex;
    flex-direction: column;
    gap: 14px;
    align-items: center;
    justify-content: center;
    min-height: 220px;
    padding: 22px;
    text-align: center;
    background: #fafafa;
    border: 1px solid #ececec;
    border-radius: 10px;
  }

  .service-avatar {
    display: grid;
    place-items: center;
    width: 78px;
    height: 78px;
    overflow: hidden;
    color: #08b95d;
    background: #eafaf1;
    border-radius: 50%;
  }

  .service-avatar img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .identity-preview > span:last-child {
    display: grid;
    gap: 6px;
  }

  .identity-preview small {
    color: #999;
  }

  .identity-fields {
    min-width: 0;
  }

  .identity-fields .form-field:first-child {
    margin-top: 0;
  }

  .avatar-field :deep(.asset-picker__compact-target) {
    width: 112px;
    height: 112px;
    border-radius: 10px;
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

    .identity-layout {
      grid-template-columns: 220px minmax(0, 1fr);
      gap: 24px;
    }
  }
</style>
