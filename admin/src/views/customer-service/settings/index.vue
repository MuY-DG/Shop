<template>
  <div class="settings-layout">
    <aside class="settings-sidebar">
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
                <p>开启后，新会话进入队列时自动选择当前可接待的在线客服。</p>
              </div>
              <CsSwitch v-model="form.autoAssignEnabled" />
            </div>
          </div>

          <div class="setting-block">
            <div class="setting-title">
              <div>
                <h2>原客服优先</h2>
                <p>用户再次咨询时，原客服仍在线且未满载则优先继续接待。</p>
              </div>
              <CsSwitch v-model="form.stickyAgentEnabled" />
            </div>
            <label class="inline-field" :class="{ disabled: !form.stickyAgentEnabled }">
              <span>有效期</span>
              <input
                v-model.number="form.stickyWindowHours"
                type="number"
                min="1"
                max="720"
                :disabled="!form.stickyAgentEnabled"
              />
              <b>小时</b>
            </label>
          </div>
        </template>

        <template v-else-if="activeSection === 'routing'">
          <div class="setting-block">
            <div class="setting-title">
              <div>
                <h2>会话分流策略</h2>
                <p>系统只会选择在线、状态为可接待且未达到最大接待数的客服。</p>
              </div>
            </div>
            <div class="strategy-list">
              <button
                v-for="strategy in strategies"
                :key="strategy.value"
                type="button"
                :class="{ active: form.assignmentStrategy === strategy.value }"
                :disabled="!form.autoAssignEnabled"
                @click="form.assignmentStrategy = strategy.value"
              >
                <span class="radio-dot" />
                <component :is="strategy.icon" :size="20" />
                <span>
                  <strong>{{ strategy.label }}</strong>
                  <small>{{ strategy.description }}</small>
                </span>
              </button>
            </div>
            <p v-if="!form.autoAssignEnabled" class="muted-tip">
              请先在“自动接入”中开启自动分配。
            </p>
          </div>
        </template>

        <template v-else>
          <div class="setting-block identity-block">
            <div class="setting-title">
              <div>
                <h2>客服默认形象</h2>
                <p>头像全员统一；名称为默认值，管理员仍可在后台成员页为单个客服覆盖。</p>
              </div>
            </div>

            <div class="identity-preview">
              <span class="service-avatar">
                <img v-if="form.avatar" :src="form.avatar" alt="" />
                <Headset v-else :size="25" />
              </span>
              <span>
                <strong>{{ form.defaultServiceName || '商城客服' }}</strong>
                <small>客户侧展示预览</small>
              </span>
            </div>

            <label class="form-field">
              <span>默认客服名称</span>
              <input v-model="form.defaultServiceName" maxlength="64" placeholder="商城客服" />
              <small>未设置个人客服名称时使用。</small>
            </label>

            <label class="form-field">
              <span>统一头像地址</span>
              <input v-model="form.avatar" maxlength="255" placeholder="https://..." />
              <small>建议使用正方形图片，修改后全体客服统一生效。</small>
            </label>
          </div>
        </template>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
  import { computed, markRaw, onMounted, reactive, ref, watch } from 'vue'
  import { ElMessage } from 'element-plus'
  import {
    ChartNoAxesColumnIncreasing,
    Headset,
    ListRestart,
    LoaderCircle,
    Save,
    Scale,
    UserRoundCog,
    Waypoints,
    Zap
  } from '@lucide/vue'
  import { useRoute, useRouter } from 'vue-router'
  import CsSwitch from '@/components/customer-ui/CsSwitch.vue'
  import {
    fetchCustomerServiceManagementConfig,
    updateCustomerServiceManagementConfig
  } from '@/api/customer-service'

  defineOptions({ name: 'CustomerServiceSettings' })

  type SectionKey = 'automatic' | 'routing' | 'identity'

  const route = useRoute()
  const router = useRouter()
  const loading = ref(true)
  const saving = ref(false)
  const activeSection = ref<SectionKey>(normalizeSection(route.query.section))
  const settingItems = [
    {
      key: 'automatic' as const,
      label: '自动接入',
      description: '设置新会话是否自动分配，以及重复来访的接待规则。',
      icon: Zap
    },
    {
      key: 'routing' as const,
      label: '会话分流',
      description: '选择客服间的会话分配策略。',
      icon: Waypoints
    },
    {
      key: 'identity' as const,
      label: '对外形象',
      description: '设置客户可见的默认客服名称和统一头像。',
      icon: UserRoundCog
    }
  ]
  const currentSection = computed(
    () => settingItems.find((item) => item.key === activeSection.value) || settingItems[0]
  )
  const form = reactive<Api.CustomerService.ManagementConfigForm>({
    defaultServiceName: '商城客服',
    avatar: '',
    autoAssignEnabled: false,
    assignmentStrategy: 'LEAST_LOADED',
    stickyAgentEnabled: true,
    stickyWindowHours: 48
  })
  const strategies = [
    {
      value: 'LEAST_LOADED' as const,
      label: '最少会话优先',
      description: '优先选择当前活跃会话更少的客服。',
      icon: markRaw(ChartNoAxesColumnIncreasing)
    },
    {
      value: 'ROUND_ROBIN' as const,
      label: '轮询分配',
      description: '按最近分配时间轮换，尽量保持接待机会均衡。',
      icon: markRaw(ListRestart)
    },
    {
      value: 'WEIGHTED' as const,
      label: '按权重分配',
      description: '按照管理员为成员配置的权重分配。',
      icon: markRaw(Scale)
    }
  ]

  function normalizeSection(value: unknown): SectionKey {
    return value === 'routing' || value === 'identity' ? value : 'automatic'
  }

  function selectSection(section: SectionKey) {
    activeSection.value = section
    void router.replace({ path: route.path, query: { ...route.query, section } })
  }

  async function load() {
    loading.value = true
    try {
      Object.assign(form, await fetchCustomerServiceManagementConfig())
    } finally {
      loading.value = false
    }
  }

  async function save() {
    if (!form.defaultServiceName.trim()) {
      ElMessage.warning('请填写默认客服名称')
      return
    }
    if (form.stickyWindowHours < 1 || form.stickyWindowHours > 720) {
      ElMessage.warning('原客服优先有效期需在 1–720 小时之间')
      return
    }
    saving.value = true
    try {
      Object.assign(
        form,
        await updateCustomerServiceManagementConfig({
          ...form,
          defaultServiceName: form.defaultServiceName.trim(),
          avatar: form.avatar.trim()
        })
      )
      ElMessage.success('设置已保存')
    } finally {
      saving.value = false
    }
  }

  watch(
    () => route.query.section,
    (section) => {
      activeSection.value = normalizeSection(section)
    }
  )
  onMounted(load)
</script>

<style scoped>
  .settings-layout {
    display: flex;
    height: 100%;
    overflow: hidden;
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
    max-width: 1420px;
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
    color: #aaa;
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
    max-width: 1420px;
    min-height: 230px;
    margin: 0 auto;
  }

  .setting-block {
    padding: 28px 36px;
    margin-bottom: 24px;
    background: #fff;
    border-radius: 12px;
  }

  .setting-title {
    display: flex;
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
  .muted-tip {
    margin: 7px 0 0;
    font-size: 13px;
    color: #aaa;
  }

  .inline-field {
    display: flex;
    gap: 12px;
    align-items: center;
    margin-top: 25px;
    font-size: 14px;
    color: #444;
  }

  .inline-field input {
    box-sizing: border-box;
    width: 120px;
    height: 38px;
    padding: 0 12px;
    border: 1px solid #e3e3e3;
    border-radius: 6px;
    outline: none;
  }

  .inline-field b {
    font-weight: 400;
  }

  .disabled {
    opacity: 0.5;
  }

  .strategy-list {
    display: grid;
    grid-template-columns: repeat(3, minmax(180px, 1fr));
    gap: 16px;
    margin-top: 24px;
  }

  .strategy-list button {
    position: relative;
    display: flex;
    gap: 14px;
    align-items: flex-start;
    min-height: 116px;
    padding: 22px;
    color: #777;
    text-align: left;
    cursor: pointer;
    background: #fff;
    border: 1px solid #e5e5e5;
    border-radius: 8px;
  }

  .strategy-list button.active {
    color: #08b95d;
    border-color: #30c97a;
    box-shadow: 0 0 0 1px #30c97a;
  }

  .strategy-list button:disabled {
    cursor: default;
    opacity: 0.5;
  }

  .strategy-list button > span:not(.radio-dot) {
    display: grid;
    gap: 7px;
  }

  .strategy-list strong {
    font-size: 14px;
    color: #333;
  }

  .strategy-list small {
    line-height: 1.55;
    color: #999;
  }

  .radio-dot {
    position: absolute;
    top: 12px;
    right: 12px;
    width: 8px;
    height: 8px;
    border: 1px solid #bbb;
    border-radius: 50%;
  }

  .strategy-list button.active .radio-dot {
    background: #11c466;
    border-color: #11c466;
  }

  .identity-block {
    max-width: 900px;
  }

  .identity-preview {
    display: flex;
    gap: 14px;
    align-items: center;
    padding: 18px;
    margin: 24px 0;
    background: #fafafa;
    border: 1px solid #ececec;
    border-radius: 8px;
  }

  .service-avatar {
    display: grid;
    place-items: center;
    width: 52px;
    height: 52px;
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

  .form-field {
    display: grid;
    gap: 8px;
    max-width: 620px;
    margin-top: 22px;
    font-size: 14px;
    color: #444;
  }

  .form-field input {
    box-sizing: border-box;
    height: 42px;
    padding: 0 13px;
    border: 1px solid #dedede;
    border-radius: 6px;
    outline: none;
  }

  .form-field input:focus {
    border-color: #27c875;
  }

  .form-field small {
    color: #aaa;
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
  }
</style>
