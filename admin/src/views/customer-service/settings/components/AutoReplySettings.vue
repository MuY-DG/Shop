<template>
  <div v-loading="loading" class="auto-reply-settings">
    <nav class="reply-tabs" aria-label="自动回复类型">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        :class="{ active: activeTab === tab.key }"
        :disabled="interactionDisabled"
        @click="selectTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </nav>

    <div v-if="!canEditCurrentTab" class="permission-note">
      <LockKeyhole :size="15" />
      <span>
        {{
          activeTab === 'welcome'
            ? '接入欢迎语仅供参与接待的客服编辑。'
            : '此项仅客服管理员可编辑，你可以查看当前生效内容。'
        }}
      </span>
    </div>

    <section v-if="activeTab === 'common'" class="reply-panel">
      <article class="reply-card">
        <div class="card-heading">
          <div>
            <h2>开场白</h2>
            <p>用户进入客服会话页，自动回复此开场白。</p>
          </div>
          <MessageSquareText :size="20" />
        </div>
        <ElInput
          v-model="config.openingMessage"
          type="textarea"
          :rows="4"
          maxlength="2000"
          show-word-limit
          resize="none"
          :disabled="interactionDisabled || !canUpdate"
          placeholder="请输入开场白"
        />
      </article>

      <article class="reply-card common-card">
        <div class="card-heading">
          <div>
            <h2>常见问题</h2>
            <p> 在开场白的基础上配置常见问题，帮助用户快速找到答案，减少客服接待压力。 </p>
          </div>
          <button
            type="button"
            class="example-link"
            :disabled="interactionDisabled"
            @click="exampleVisible = true"
          >
            查看用户侧示例
            <ExternalLink :size="14" />
          </button>
        </div>

        <div v-if="commonQuestions.length" class="question-list">
          <div v-for="item in commonQuestions" :key="item.clientKey" class="question-row">
            <ElCheckbox
              v-model="item.enabled"
              :disabled="interactionDisabled || !canUpdate"
              aria-label="是否启用此常见问题"
            />
            <label class="question-field question-field--question">
              <span>问题</span>
              <ElInput
                v-model="item.question"
                maxlength="200"
                :disabled="interactionDisabled || !canUpdate"
                placeholder="请输入用户可能咨询的问题"
              />
            </label>
            <label class="question-field">
              <span>回复</span>
              <ElInput
                v-model="item.answer"
                type="textarea"
                :rows="2"
                maxlength="2000"
                resize="none"
                :disabled="interactionDisabled || !canUpdate"
                placeholder="请输入问题对应的回复"
              />
            </label>
            <button
              v-if="canUpdate"
              type="button"
              class="icon-button icon-button--danger"
              title="删除常见问题"
              :disabled="interactionDisabled"
              @click="removeCommonQuestion(item.clientKey)"
            >
              <Trash2 :size="16" />
            </button>
          </div>
        </div>
        <div v-else class="empty-copy">暂无常见问题</div>

        <button
          v-if="canUpdate"
          type="button"
          class="add-button"
          :disabled="interactionDisabled || commonQuestions.length >= COMMON_QUESTION_LIMIT"
          :title="
            commonQuestions.length >= COMMON_QUESTION_LIMIT
              ? `最多添加 ${COMMON_QUESTION_LIMIT} 条常见问题`
              : '新增常见问题'
          "
          @click="addCommonQuestion"
        >
          <Plus :size="16" />
          新增常见问题（{{ commonQuestions.length }}/{{ COMMON_QUESTION_LIMIT }}）
        </button>
        <p v-if="commonQuestions.length >= COMMON_QUESTION_LIMIT" class="limit-tip">
          已达到常见问题数量上限。
        </p>
      </article>
    </section>

    <section v-else-if="activeTab === 'welcome'" class="reply-panel">
      <article class="reply-card compact-card">
        <div class="card-heading">
          <div>
            <h2>接入欢迎语</h2>
            <p>客服手动或自动接入时，自动回复以下内容。</p>
          </div>
          <Handshake :size="20" />
        </div>
        <div class="personal-tip">
          <UserRound :size="16" />
          <span>这是你的接入欢迎语，不影响其他客服。</span>
        </div>
        <ElInput
          v-model="config.welcomeMessage"
          type="textarea"
          :rows="6"
          maxlength="2000"
          show-word-limit
          resize="none"
          :disabled="interactionDisabled || !canUpdateWelcome"
          placeholder="例如：您好，我是本次为您服务的客服，请问有什么可以帮您？"
        />
      </article>
    </section>

    <section v-else-if="activeTab === 'offline'" class="reply-panel">
      <article class="reply-card compact-card">
        <div class="card-heading">
          <div>
            <h2>离线回复</h2>
            <p>客服全部离线时，自动回复以下内容。一小时内对同一用户仅触发一次。</p>
          </div>
          <MoonStar :size="20" />
        </div>
        <ElInput
          v-model="config.offlineMessage"
          type="textarea"
          :rows="6"
          maxlength="2000"
          show-word-limit
          resize="none"
          :disabled="interactionDisabled || !canUpdate"
          placeholder="例如：当前客服已离线，我们上线后会尽快回复您。"
        />
      </article>
    </section>

    <section v-else class="reply-panel smart-panel">
      <div class="smart-intro">
        <div>
          <h2>智能回复</h2>
          <p>一条回复可以对应多个问题，用户咨询时智能识别问题并回复。</p>
        </div>
        <div v-if="canUpdate" class="smart-add-control">
          <button
            type="button"
            class="add-button"
            :disabled="interactionDisabled || smartReplies.length >= SMART_REPLY_LIMIT"
            :title="
              smartReplies.length >= SMART_REPLY_LIMIT
                ? `最多添加 ${SMART_REPLY_LIMIT} 组智能回复`
                : '新增一组智能回复'
            "
            @click="addSmartReply"
          >
            <Plus :size="16" />
            新增一组（{{ smartReplies.length }}/{{ SMART_REPLY_LIMIT }}）
          </button>
          <small v-if="smartReplies.length >= SMART_REPLY_LIMIT" class="limit-tip">
            已达到智能回复数量上限。
          </small>
        </div>
      </div>

      <article
        v-for="(item, groupIndex) in smartReplies"
        :key="item.clientKey"
        class="reply-card smart-card"
      >
        <div class="smart-card__heading">
          <div class="group-title">
            <span>{{ chineseGroupNumber(groupIndex) }}</span>
            <ElInput
              v-model="item.name"
              maxlength="60"
              :disabled="interactionDisabled || !canUpdate"
              :placeholder="`第 ${groupIndex + 1} 组`"
            />
          </div>
          <div class="smart-card__actions">
            <CsSwitch v-model="item.enabled" :disabled="interactionDisabled || !canUpdate" />
            <button
              v-if="canUpdate"
              type="button"
              class="icon-button icon-button--danger"
              title="删除这一组"
              :disabled="interactionDisabled"
              @click="removeSmartReply(item.clientKey)"
            >
              <Trash2 :size="16" />
            </button>
          </div>
        </div>

        <div class="smart-field">
          <div class="smart-field__heading">
            <span>问题（{{ item.questions.length }}/{{ SMART_QUESTION_LIMIT }}）</span>
            <button
              v-if="canUpdate"
              type="button"
              :disabled="interactionDisabled || item.questions.length >= SMART_QUESTION_LIMIT"
              :title="
                item.questions.length >= SMART_QUESTION_LIMIT
                  ? `每组最多添加 ${SMART_QUESTION_LIMIT} 个问题`
                  : '添加问题'
              "
              @click="addSmartQuestion(item)"
            >
              <Plus :size="14" />
              {{ item.questions.length >= SMART_QUESTION_LIMIT ? '已达上限' : '添加问题' }}
            </button>
          </div>
          <div v-if="item.questions.length" class="smart-questions">
            <div v-for="(_, questionIndex) in item.questions" :key="questionIndex">
              <ElInput
                v-model="item.questions[questionIndex]"
                maxlength="200"
                :disabled="interactionDisabled || !canUpdate"
                :placeholder="`问题 ${questionIndex + 1}`"
              />
              <button
                v-if="canUpdate"
                type="button"
                title="移除此问题"
                :disabled="interactionDisabled"
                @click="removeSmartQuestion(item, questionIndex)"
              >
                <X :size="15" />
              </button>
            </div>
          </div>
          <div v-else class="empty-copy empty-copy--inline">暂无问题</div>
        </div>

        <label class="smart-field">
          <span>回复</span>
          <ElInput
            v-model="item.reply"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            resize="none"
            :disabled="interactionDisabled || !canUpdate"
            placeholder="暂无内容"
          />
        </label>
      </article>
    </section>

    <ElDialog v-model="exampleVisible" title="用户侧示例" width="520px" align-center>
      <img class="example-image" :src="userSideExampleImage" alt="开场白与常见问题用户侧示例" />
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessage } from 'element-plus'
  import {
    ExternalLink,
    Handshake,
    LockKeyhole,
    MessageSquareText,
    MoonStar,
    Plus,
    Trash2,
    UserRound,
    X
  } from '@lucide/vue'
  import CsSwitch from '@/components/customer-ui/CsSwitch.vue'
  import userSideExampleImage from '@/assets/images/customer-service/auto-reply-user-example.png'
  import {
    fetchCustomerServiceAutoReplies,
    updateCustomerServiceAutoReplyCommon,
    updateCustomerServiceAutoReplyOffline,
    updateCustomerServiceAutoReplySmart,
    updateCustomerServiceAutoReplyWelcome
  } from '@/api/customer-service'

  defineOptions({ name: 'CustomerServiceAutoReplySettings' })

  type AutoReplyTab = 'common' | 'welcome' | 'offline' | 'smart'
  type EditableCommonQuestion = Api.CustomerService.AutoReplyCommonQuestion & {
    clientKey: string
  }
  type EditableSmartReply = Api.CustomerService.AutoReplySmartReply & {
    clientKey: string
  }

  const props = defineProps<{
    canUpdate: boolean
    canUpdateWelcome: boolean
    saving: boolean
  }>()
  const activeTab = defineModel<AutoReplyTab>('activeTab', { default: 'common' })

  const tabs: Array<{ key: AutoReplyTab; label: string }> = [
    { key: 'common', label: '常见问题' },
    { key: 'welcome', label: '接入欢迎语' },
    { key: 'offline', label: '离线回复' },
    { key: 'smart', label: '智能回复' }
  ]
  const COMMON_QUESTION_LIMIT = 20
  const SMART_REPLY_LIMIT = 100
  const SMART_QUESTION_LIMIT = 20
  const loading = ref(false)
  const exampleVisible = ref(false)
  const commonQuestions = ref<EditableCommonQuestion[]>([])
  const smartReplies = ref<EditableSmartReply[]>([])
  let localKeySequence = 0
  const config = reactive({
    revision: 0,
    openingMessage: '',
    welcomeMessage: '',
    offlineMessage: ''
  })
  const canEditCurrentTab = computed(() =>
    activeTab.value === 'welcome' ? props.canUpdateWelcome : props.canUpdate
  )
  const interactionDisabled = computed(() => loading.value || props.saving)

  function localKey(prefix: string) {
    localKeySequence += 1
    return `${prefix}-${localKeySequence}`
  }

  function normalizeQuestion(
    item: Api.CustomerService.AutoReplyCommonQuestion,
    index: number
  ): EditableCommonQuestion {
    return {
      questionId: item.questionId ?? null,
      question: item.question || '',
      answer: item.answer || '',
      enabled: item.enabled !== false,
      sortOrder: Number.isFinite(item.sortOrder) ? item.sortOrder : index,
      clientKey: item.questionId ? `question-${item.questionId}` : localKey('question')
    }
  }

  function blankSmartReply(index = smartReplies.value.length): EditableSmartReply {
    return {
      replyId: null,
      name: `第 ${index + 1} 组`,
      questions: [],
      reply: '',
      enabled: false,
      sortOrder: index,
      clientKey: localKey('smart')
    }
  }

  function normalizeSmartReply(
    item: Api.CustomerService.AutoReplySmartReply,
    index: number
  ): EditableSmartReply {
    return {
      replyId: item.replyId ?? null,
      name: item.name || `第 ${index + 1} 组`,
      questions: Array.isArray(item.questions) ? [...item.questions] : [],
      reply: item.reply || '',
      enabled: item.enabled !== false,
      sortOrder: Number.isFinite(item.sortOrder) ? item.sortOrder : index,
      clientKey: item.replyId ? `smart-${item.replyId}` : localKey('smart')
    }
  }

  function applyConfig(next: Api.CustomerService.AutoReplyConfig) {
    config.revision = next.revision
    config.openingMessage = next.openingMessage || ''
    config.welcomeMessage = next.welcomeMessage || ''
    config.offlineMessage = next.offlineMessage || ''
    commonQuestions.value = (next.commonQuestions || []).map(normalizeQuestion)
    const normalizedSmartReplies = (next.smartReplies || []).map(normalizeSmartReply)
    smartReplies.value = normalizedSmartReplies.length
      ? normalizedSmartReplies
      : [blankSmartReply(0)]
  }

  async function load() {
    loading.value = true
    try {
      applyConfig(await fetchCustomerServiceAutoReplies())
    } finally {
      loading.value = false
    }
  }

  function addCommonQuestion() {
    if (interactionDisabled.value || !props.canUpdate) return
    if (commonQuestions.value.length >= COMMON_QUESTION_LIMIT) {
      ElMessage.info(`常见问题最多添加 ${COMMON_QUESTION_LIMIT} 条`)
      return
    }
    commonQuestions.value.push({
      questionId: null,
      question: '',
      answer: '',
      enabled: true,
      sortOrder: commonQuestions.value.length,
      clientKey: localKey('question')
    })
  }

  function removeCommonQuestion(clientKey: string) {
    if (interactionDisabled.value || !props.canUpdate) return
    commonQuestions.value = commonQuestions.value.filter((item) => item.clientKey !== clientKey)
  }

  function addSmartReply() {
    if (interactionDisabled.value || !props.canUpdate) return
    if (smartReplies.value.length >= SMART_REPLY_LIMIT) {
      ElMessage.info(`智能回复最多添加 ${SMART_REPLY_LIMIT} 组`)
      return
    }
    smartReplies.value.push(blankSmartReply())
  }

  function removeSmartReply(clientKey: string) {
    if (interactionDisabled.value || !props.canUpdate) return
    smartReplies.value = smartReplies.value.filter((item) => item.clientKey !== clientKey)
    if (!smartReplies.value.length) smartReplies.value = [blankSmartReply(0)]
  }

  function addSmartQuestion(item: EditableSmartReply) {
    if (interactionDisabled.value || !props.canUpdate) return
    if (item.questions.length >= SMART_QUESTION_LIMIT) {
      ElMessage.info(`每组智能回复最多添加 ${SMART_QUESTION_LIMIT} 个问题`)
      return
    }
    item.questions.push('')
  }

  function removeSmartQuestion(item: EditableSmartReply, questionIndex: number) {
    if (interactionDisabled.value || !props.canUpdate) return
    item.questions.splice(questionIndex, 1)
  }

  function selectTab(tab: AutoReplyTab) {
    if (interactionDisabled.value) return
    activeTab.value = tab
  }

  function commonPayload(): Api.CustomerService.AutoReplyCommonQuestion[] {
    return commonQuestions.value.map((item, index) => ({
      questionId: item.questionId || null,
      question: item.question.trim(),
      answer: item.answer.trim(),
      enabled: item.enabled,
      sortOrder: index
    }))
  }

  function smartPayload(): Api.CustomerService.AutoReplySmartReply[] {
    return smartReplies.value.map((item, index) => ({
      replyId: item.replyId || null,
      name: item.name.trim() || `第 ${index + 1} 组`,
      questions: item.questions.map((question) => question.trim()).filter(Boolean),
      reply: item.reply.trim(),
      enabled: item.enabled,
      sortOrder: index
    }))
  }

  function validateCommon(payload: Api.CustomerService.AutoReplyCommonQuestion[]) {
    if (payload.length > COMMON_QUESTION_LIMIT) {
      ElMessage.warning(`常见问题最多保留 ${COMMON_QUESTION_LIMIT} 条`)
      return false
    }
    const invalid = payload.find((item) => !item.question || !item.answer)
    if (!invalid) return true
    ElMessage.warning('请完整填写每个常见问题及对应回复')
    return false
  }

  function validateSmart(payload: Api.CustomerService.AutoReplySmartReply[]) {
    if (payload.length > SMART_REPLY_LIMIT) {
      ElMessage.warning(`智能回复最多保留 ${SMART_REPLY_LIMIT} 组`)
      return false
    }
    if (
      smartReplies.value.some((item) => item.questions.length > SMART_QUESTION_LIMIT) ||
      payload.some((item) => item.questions.length > SMART_QUESTION_LIMIT)
    ) {
      ElMessage.warning(`每组智能回复最多保留 ${SMART_QUESTION_LIMIT} 个问题`)
      return false
    }
    const invalid = payload.find(
      (item) => item.enabled && (!item.questions.length || !item.reply.trim())
    )
    if (!invalid) return true
    ElMessage.warning('已启用的智能回复需至少填写一个问题和回复内容')
    return false
  }

  async function save() {
    if (loading.value) return false
    if (!canEditCurrentTab.value) return false
    try {
      let result: Api.CustomerService.AutoReplyConfig
      if (activeTab.value === 'common') {
        const questions = commonPayload()
        if (!validateCommon(questions)) return false
        result = await updateCustomerServiceAutoReplyCommon({
          revision: config.revision,
          openingMessage: config.openingMessage.trim(),
          commonQuestions: questions
        })
        config.revision = result.revision
        config.openingMessage = result.openingMessage || ''
        commonQuestions.value = (result.commonQuestions || []).map(normalizeQuestion)
      } else if (activeTab.value === 'welcome') {
        result = await updateCustomerServiceAutoReplyWelcome({
          content: config.welcomeMessage.trim()
        })
        config.welcomeMessage = result.welcomeMessage || ''
      } else if (activeTab.value === 'offline') {
        result = await updateCustomerServiceAutoReplyOffline({
          revision: config.revision,
          content: config.offlineMessage.trim()
        })
        config.revision = result.revision
        config.offlineMessage = result.offlineMessage || ''
      } else {
        const replies = smartPayload()
        if (!validateSmart(replies)) return false
        result = await updateCustomerServiceAutoReplySmart({
          revision: config.revision,
          smartReplies: replies
        })
        config.revision = result.revision
        const normalizedSmartReplies = (result.smartReplies || []).map(normalizeSmartReply)
        smartReplies.value = normalizedSmartReplies.length
          ? normalizedSmartReplies
          : [blankSmartReply(0)]
      }
      return true
    } catch (error) {
      const code =
        typeof error === 'object' && error !== null && 'code' in error ? Number(error.code) : null
      if (code === 900002) {
        await load()
        ElMessage.warning('配置已被其他管理员更新，已为你加载最新内容，请重新编辑')
      } else {
        ElMessage.error(error instanceof Error ? error.message : '设置保存失败，请稍后重试')
      }
      return false
    }
  }

  function chineseGroupNumber(index: number) {
    const numerals = ['第一组', '第二组', '第三组', '第四组', '第五组', '第六组']
    return numerals[index] || `第 ${index + 1} 组`
  }

  onMounted(load)
  defineExpose({ save, reload: load })
</script>

<style scoped>
  .auto-reply-settings {
    min-height: 320px;
  }

  .reply-tabs {
    display: flex;
    gap: 6px;
    padding: 7px 12px;
    margin-bottom: 18px;
    overflow-x: auto;
    background: #fff;
    border: 1px solid #ecefed;
    border-radius: 12px;
  }

  .reply-tabs button {
    flex: 0 0 auto;
    min-width: 112px;
    height: 44px;
    padding: 0 16px;
    font-size: 14px;
    color: #777;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-radius: 8px;
    transition:
      color 0.18s ease,
      background 0.18s ease;
  }

  .reply-tabs button:hover {
    color: #08aa57;
    background: #f2faf6;
  }

  .reply-tabs button:disabled:hover {
    color: #777;
    background: transparent;
  }

  .reply-tabs button.active {
    font-weight: 650;
    color: #0aae5a;
    background: #edf9f2;
  }

  .permission-note,
  .personal-tip {
    display: flex;
    gap: 8px;
    align-items: center;
    padding: 11px 14px;
    margin-bottom: 14px;
    font-size: 12px;
    color: #6c746f;
    background: #f7f9f8;
    border: 1px solid #e8ecea;
    border-radius: 8px;
  }

  .reply-panel {
    display: grid;
    gap: 18px;
  }

  .reply-card {
    padding: 28px 32px;
    background: #fff;
    border: 1px solid #ecefed;
    border-radius: 12px;
  }

  .compact-card {
    max-width: 920px;
  }

  .card-heading,
  .smart-intro,
  .smart-card__heading,
  .smart-field__heading {
    display: flex;
    gap: 20px;
    align-items: flex-start;
    justify-content: space-between;
  }

  .card-heading {
    margin-bottom: 20px;
  }

  .card-heading > svg {
    flex: 0 0 auto;
    color: #15b562;
  }

  h2 {
    margin: 0;
    font-size: 17px;
    font-weight: 650;
    color: #292929;
  }

  p {
    margin: 7px 0 0;
    font-size: 13px;
    line-height: 1.65;
    color: #999;
  }

  .reply-card :deep(.el-textarea__inner),
  .reply-card :deep(.el-input__wrapper) {
    background: #fafafa;
    box-shadow: 0 0 0 1px #e5e8e6 inset;
  }

  .reply-card :deep(.el-textarea__inner:focus),
  .reply-card :deep(.el-input__wrapper.is-focus) {
    box-shadow:
      0 0 0 1px #27c875 inset,
      0 0 0 3px rgb(39 200 117 / 8%);
  }

  .example-link,
  .smart-field__heading button {
    display: inline-flex;
    gap: 5px;
    align-items: center;
    padding: 0;
    font-size: 12px;
    color: #148a4f;
    cursor: pointer;
    background: transparent;
    border: 0;
  }

  .question-list {
    overflow-x: auto;
    border: 1px solid #e8ecea;
    border-radius: 10px;
  }

  .question-row {
    display: grid;
    grid-template-columns: 28px minmax(220px, 0.55fr) minmax(320px, 1fr) 34px;
    gap: 12px;
    align-items: center;
    min-width: 760px;
    padding: 15px 16px;
    border-bottom: 1px solid #eef0ef;
  }

  .question-row:last-child {
    border-bottom: 0;
  }

  .question-field {
    display: grid;
    gap: 7px;
  }

  .question-field > span,
  .smart-field > span,
  .smart-field__heading > span {
    font-size: 12px;
    font-weight: 650;
    color: #606760;
  }

  .question-field--question {
    align-self: stretch;
  }

  .icon-button {
    display: inline-grid;
    place-items: center;
    width: 32px;
    height: 32px;
    padding: 0;
    color: #7e8782;
    cursor: pointer;
    background: #f4f6f5;
    border: 0;
    border-radius: 7px;
  }

  .icon-button--danger:hover {
    color: #dc5252;
    background: #fff1f1;
  }

  .add-button {
    display: inline-flex;
    gap: 7px;
    align-items: center;
    justify-content: center;
    min-height: 36px;
    padding: 0 13px;
    margin-top: 16px;
    font-size: 13px;
    color: #098a48;
    cursor: pointer;
    background: #eef9f3;
    border: 1px solid #d9f0e4;
    border-radius: 8px;
  }

  button:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }

  .limit-tip {
    margin: 8px 0 0;
    font-size: 12px;
    color: #9a6b19;
  }

  .empty-copy {
    display: grid;
    place-items: center;
    min-height: 88px;
    font-size: 13px;
    color: #aaa;
    background: #fafbfa;
    border: 1px dashed #e1e5e3;
    border-radius: 8px;
  }

  .empty-copy--inline {
    min-height: 52px;
  }

  .personal-tip {
    margin: 0 0 16px;
    color: #147b45;
    background: #edf9f2;
    border-color: #d7f0e2;
  }

  .smart-intro {
    align-items: center;
    padding: 8px 4px 0;
  }

  .smart-intro .add-button {
    flex: 0 0 auto;
    margin-top: 0;
  }

  .smart-add-control {
    display: grid;
    justify-items: end;
  }

  .smart-card__heading {
    align-items: center;
    padding-bottom: 18px;
    border-bottom: 1px solid #eef0ef;
  }

  .group-title {
    display: grid;
    grid-template-columns: auto minmax(170px, 320px);
    gap: 12px;
    align-items: center;
  }

  .group-title > span {
    font-size: 15px;
    font-weight: 700;
    color: #202420;
  }

  .smart-card__actions {
    display: flex;
    gap: 10px;
    align-items: center;
  }

  .smart-field {
    display: grid;
    gap: 10px;
    margin-top: 20px;
  }

  .smart-questions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }

  .smart-questions > div {
    position: relative;
  }

  .smart-questions button {
    position: absolute;
    top: 50%;
    right: 9px;
    display: grid;
    place-items: center;
    padding: 0;
    color: #a0a6a2;
    cursor: pointer;
    background: transparent;
    border: 0;
    transform: translateY(-50%);
  }

  .smart-questions :deep(.el-input__inner) {
    padding-right: 24px;
  }

  .example-image {
    display: block;
    width: min(100%, 390px);
    max-height: 70vh;
    margin: 0 auto;
    object-fit: contain;
    border-radius: 10px;
  }

  @media (width <= 1180px) {
    .reply-card {
      padding: 24px;
    }

    .smart-questions {
      grid-template-columns: 1fr;
    }
  }
</style>
