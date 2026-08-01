<template>
  <div v-loading="loading" class="quick-reply-settings">
    <article class="quick-card">
      <div class="quick-heading">
        <div>
          <h2>常见短语</h2>
          <p>内容编辑后默认保存，即时生效。全部客服共用同一套快捷回复。</p>
        </div>
        <div class="quick-heading__actions">
          <button
            type="button"
            class="icon-action"
            title="刷新快捷回复"
            :disabled="loading || hasUnsettledSaves"
            @click="load"
          >
            <RefreshCw :size="16" />
          </button>
          <button
            v-if="canUpdate"
            type="button"
            class="create-button create-button--secondary"
            :disabled="hasUnsettledSaves"
            @click="openCreateGroupDialog"
          >
            <FolderPlus :size="16" />
            新增分组
          </button>
          <button
            v-if="canUpdate"
            type="button"
            class="create-button"
            :disabled="hasUnsettledSaves"
            @click="openCreateDialog"
          >
            <Plus :size="16" />
            新增快捷回复
          </button>
        </div>
      </div>

      <div v-if="!canUpdate" class="manager-note">
        <ShieldCheck :size="16" />
        <span>快捷回复由客服管理员统一维护，你可以查看当前全员共用内容。</span>
      </div>

      <div v-if="groups.length" class="group-list">
        <section v-for="group in groups" :key="group.groupId" class="reply-group">
          <div class="group-heading">
            <div>
              <FolderOpen :size="17" />
              <strong>{{ group.name || '默认分组' }}</strong>
            </div>
            <span>{{ group.replies.length }} 条</span>
          </div>

          <div v-if="group.replies.length" class="reply-list">
            <div
              v-for="(reply, replyIndex) in group.replies"
              :key="reply.replyId"
              class="reply-row"
            >
              <span class="reply-order">{{ replyIndex + 1 }}</span>
              <ElInput
                v-model="reply.content"
                type="textarea"
                :rows="2"
                maxlength="2000"
                resize="none"
                :disabled="!canUpdate"
                placeholder="请输入快捷回复内容"
                @input="scheduleSave(reply)"
              />
              <span
                v-if="canUpdate"
                class="save-state"
                :class="`save-state--${saveState(reply.replyId)}`"
              >
                <LoaderCircle
                  v-if="saveState(reply.replyId) === 'saving'"
                  :size="13"
                  class="spin"
                />
                <CircleAlert v-else-if="saveState(reply.replyId) === 'error'" :size="13" />
                <CircleCheck v-else-if="saveState(reply.replyId) === 'saved'" :size="13" />
                {{ saveStateText(reply.replyId) }}
              </span>
              <div v-if="canUpdate" class="reply-actions">
                <button
                  v-if="saveState(reply.replyId) === 'error'"
                  type="button"
                  title="重试保存"
                  @click="retrySave(reply)"
                >
                  <RotateCcw :size="15" />
                </button>
                <button
                  type="button"
                  class="danger"
                  title="删除快捷回复"
                  @click="removeReply(group, reply)"
                >
                  <Trash2 :size="15" />
                </button>
              </div>
            </div>
          </div>
          <div v-else class="empty-group">暂无快捷回复</div>
        </section>
      </div>
      <div v-else-if="!loading" class="empty-library">
        <MessagesSquare :size="30" />
        <strong>暂无快捷回复分组</strong>
        <span>点击“新增分组”后，即可在这里维护全员共用短语。</span>
      </div>
    </article>

    <ElDialog
      v-model="createGroupDialogVisible"
      title="新增快捷回复分组"
      width="440px"
      align-center
      destroy-on-close
      :close-on-click-modal="!groupCreating"
      :close-on-press-escape="!groupCreating"
    >
      <ElForm label-position="top" @submit.prevent="createGroup">
        <ElFormItem label="分组名称">
          <ElInput
            v-model="newGroupName"
            maxlength="64"
            show-word-limit
            placeholder="例如：售前咨询"
            @keyup.enter="createGroup"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton :disabled="groupCreating" @click="createGroupDialogVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="groupCreating" @click="createGroup">新增分组</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="createDialogVisible"
      title="新增快捷回复"
      width="500px"
      align-center
      destroy-on-close
      :close-on-click-modal="!creating"
      :close-on-press-escape="!creating"
    >
      <ElForm label-position="top">
        <ElFormItem v-if="groups.length > 1" label="分组">
          <ElSelect v-model="newReplyGroupId" class="group-select" placeholder="请选择分组">
            <ElOption
              v-for="group in groups"
              :key="group.groupId"
              :label="group.name || '默认分组'"
              :value="group.groupId"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="回复内容">
          <ElInput
            v-model="newReplyContent"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            resize="none"
            placeholder="请输入客服常用的回复内容"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton :disabled="creating" @click="createDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="creating" @click="createReply">新增并生效</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    CircleAlert,
    CircleCheck,
    FolderOpen,
    FolderPlus,
    LoaderCircle,
    MessagesSquare,
    Plus,
    RefreshCw,
    RotateCcw,
    ShieldCheck,
    Trash2
  } from '@lucide/vue'
  import {
    createCustomerServiceQuickReply,
    createCustomerServiceQuickReplyGroup,
    deleteCustomerServiceQuickReply,
    fetchCustomerServiceQuickReplies,
    updateCustomerServiceQuickReply
  } from '@/api/customer-service'

  defineOptions({ name: 'CustomerServiceQuickReplySettings' })

  type SaveState = 'idle' | 'pending' | 'saving' | 'saved' | 'error'

  defineProps<{ canUpdate: boolean }>()

  const groups = ref<Api.CustomerService.QuickReplyGroup[]>([])
  const loading = ref(false)
  const groupCreating = ref(false)
  const creating = ref(false)
  const createGroupDialogVisible = ref(false)
  const createDialogVisible = ref(false)
  const newGroupName = ref('')
  const newReplyGroupId = ref('')
  const newReplyContent = ref('')
  const saveStates = ref<Record<string, SaveState>>({})
  const editVersions = new Map<string, number>()
  const saveTimers = new Map<string, ReturnType<typeof setTimeout>>()
  const savingReplyIds = new Set<string>()
  const removedReplyIds = new Set<string>()
  let loadSequence = 0
  const hasUnsettledSaves = computed(() =>
    Object.values(saveStates.value).some((state) => ['pending', 'saving', 'error'].includes(state))
  )

  function normalizeLibrary(library: Api.CustomerService.QuickReplyLibrary) {
    groups.value = (library.groups || [])
      .map((group) => ({
        ...group,
        replies: [...(group.replies || [])].sort((left, right) => left.sortOrder - right.sortOrder)
      }))
      .sort((left, right) => left.sortOrder - right.sortOrder)
    saveStates.value = {}
    editVersions.clear()
    removedReplyIds.clear()
  }

  async function load() {
    const sequence = ++loadSequence
    loading.value = true
    try {
      const library = await fetchCustomerServiceQuickReplies()
      if (sequence === loadSequence) normalizeLibrary(library)
    } finally {
      if (sequence === loadSequence) loading.value = false
    }
  }

  function saveState(replyId: string): SaveState {
    return saveStates.value[replyId] || 'idle'
  }

  function saveStateText(replyId: string) {
    const state = saveState(replyId)
    if (state === 'pending') return '等待保存'
    if (state === 'saving') return '保存中'
    if (state === 'saved') return '已保存'
    if (state === 'error') return '保存失败'
    return ''
  }

  function setSaveState(replyId: string, state: SaveState) {
    saveStates.value[replyId] = state
  }

  function scheduleSave(reply: Api.CustomerService.QuickReplyItem) {
    const replyId = reply.replyId
    if (removedReplyIds.has(replyId)) return
    const version = (editVersions.get(replyId) || 0) + 1
    editVersions.set(replyId, version)
    const existingTimer = saveTimers.get(replyId)
    if (existingTimer) clearTimeout(existingTimer)
    setSaveState(replyId, 'pending')
    saveTimers.set(
      replyId,
      setTimeout(() => {
        saveTimers.delete(replyId)
        void persistReply(reply, version)
      }, 600)
    )
  }

  async function persistReply(reply: Api.CustomerService.QuickReplyItem, version: number) {
    const replyId = reply.replyId
    if (removedReplyIds.has(replyId) || savingReplyIds.has(replyId)) return
    if (editVersions.get(replyId) !== version) return
    const content = reply.content.trim()
    if (!content) {
      if (editVersions.get(replyId) === version) setSaveState(replyId, 'error')
      return
    }
    savingReplyIds.add(replyId)
    setSaveState(replyId, 'saving')
    try {
      await updateCustomerServiceQuickReply(replyId, {
        content,
        sortOrder: reply.sortOrder
      })
      if (editVersions.get(replyId) === version) {
        reply.content = content
        setSaveState(replyId, 'saved')
      }
    } catch {
      if (editVersions.get(replyId) === version) setSaveState(replyId, 'error')
    } finally {
      savingReplyIds.delete(replyId)
      if (!removedReplyIds.has(replyId)) {
        const latestVersion = editVersions.get(replyId) || 0
        if (latestVersion > version && !saveTimers.has(replyId)) {
          const latestReply = findReply(replyId)
          if (latestReply) void persistReply(latestReply, latestVersion)
        }
      }
    }
  }

  function retrySave(reply: Api.CustomerService.QuickReplyItem) {
    const version = (editVersions.get(reply.replyId) || 0) + 1
    editVersions.set(reply.replyId, version)
    void persistReply(reply, version)
  }

  function openCreateDialog() {
    if (!groups.value.length) {
      ElMessage.warning('暂无可用分组，请刷新后重试')
      return
    }
    newReplyGroupId.value = groups.value[0].groupId
    newReplyContent.value = ''
    createDialogVisible.value = true
  }

  function openCreateGroupDialog() {
    newGroupName.value = ''
    createGroupDialogVisible.value = true
  }

  async function createGroup() {
    const name = newGroupName.value.trim()
    if (!name || groupCreating.value) {
      if (!name) ElMessage.warning('请填写分组名称')
      return
    }
    groupCreating.value = true
    try {
      const group = await createCustomerServiceQuickReplyGroup({ name })
      groups.value.push(group)
      groups.value.sort((left, right) => left.sortOrder - right.sortOrder)
      createGroupDialogVisible.value = false
      ElMessage.success('快捷回复分组已新增')
    } finally {
      groupCreating.value = false
    }
  }

  async function createReply() {
    const content = newReplyContent.value.trim()
    if (!newReplyGroupId.value || !content) {
      ElMessage.warning('请填写快捷回复内容')
      return
    }
    creating.value = true
    try {
      await createCustomerServiceQuickReply({
        groupId: newReplyGroupId.value,
        content
      })
      createDialogVisible.value = false
      ElMessage.success('快捷回复已新增并生效')
      await load()
    } finally {
      creating.value = false
    }
  }

  async function removeReply(
    group: Api.CustomerService.QuickReplyGroup,
    reply: Api.CustomerService.QuickReplyItem
  ) {
    try {
      await ElMessageBox.confirm('删除后全体客服将立即无法使用这条快捷回复。', '删除快捷回复', {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
      const timer = saveTimers.get(reply.replyId)
      if (timer) clearTimeout(timer)
      saveTimers.delete(reply.replyId)
      editVersions.set(reply.replyId, (editVersions.get(reply.replyId) || 0) + 1)
      removedReplyIds.add(reply.replyId)
      await deleteCustomerServiceQuickReply(reply.replyId)
      group.replies = group.replies.filter((item) => item.replyId !== reply.replyId)
      delete saveStates.value[reply.replyId]
      ElMessage.success('快捷回复已删除')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        removedReplyIds.delete(reply.replyId)
        setSaveState(reply.replyId, 'error')
        throw error
      }
    }
  }

  function findReply(replyId: string) {
    return groups.value.flatMap((group) => group.replies).find((reply) => reply.replyId === replyId)
  }

  onMounted(load)
  onBeforeUnmount(() => {
    saveTimers.forEach((timer, replyId) => {
      clearTimeout(timer)
      const reply = findReply(replyId)
      if (reply) void persistReply(reply, editVersions.get(replyId) || 0)
    })
    saveTimers.clear()
  })
</script>

<style scoped>
  .quick-reply-settings {
    min-height: 320px;
  }

  .quick-card {
    padding: 28px 32px;
    background: #fff;
    border: 1px solid #ecefed;
    border-radius: 12px;
  }

  .quick-heading,
  .quick-heading__actions,
  .group-heading,
  .group-heading > div,
  .manager-note,
  .reply-actions {
    display: flex;
    align-items: center;
  }

  .quick-heading,
  .group-heading {
    gap: 20px;
    justify-content: space-between;
  }

  .quick-heading {
    align-items: flex-start;
  }

  .quick-heading h2 {
    margin: 0;
    font-size: 18px;
    font-weight: 650;
    color: #292929;
  }

  .quick-heading p {
    margin: 7px 0 0;
    font-size: 13px;
    color: #999;
  }

  .quick-heading__actions,
  .reply-actions {
    gap: 8px;
  }

  .create-button,
  .icon-action,
  .reply-actions button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    border-radius: 8px;
  }

  .create-button {
    gap: 7px;
    min-height: 38px;
    padding: 0 14px;
    font-size: 13px;
    font-weight: 600;
    color: #fff;
    background: #0ac666;
    border: 0;
  }

  .create-button:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }

  .create-button--secondary {
    color: #238653;
    background: #effaf4;
    border: 1px solid #ccebd9;
  }

  .icon-action,
  .reply-actions button {
    width: 34px;
    height: 34px;
    padding: 0;
    color: #727b76;
    background: #f5f7f6;
    border: 1px solid #e6eae8;
  }

  .icon-action:disabled {
    cursor: default;
    opacity: 0.5;
  }

  .manager-note {
    gap: 8px;
    padding: 11px 14px;
    margin-top: 20px;
    font-size: 12px;
    color: #6d746f;
    background: #f7f9f8;
    border: 1px solid #e7ebe9;
    border-radius: 8px;
  }

  .group-list {
    display: grid;
    gap: 16px;
    margin-top: 22px;
  }

  .reply-group {
    overflow: hidden;
    border: 1px solid #e7ebe9;
    border-radius: 10px;
  }

  .group-heading {
    min-height: 50px;
    padding: 0 18px;
    color: #465048;
    background: #fafbfa;
    border-bottom: 1px solid #ecefed;
  }

  .group-heading > div {
    gap: 9px;
  }

  .group-heading svg {
    color: #13ad5c;
  }

  .group-heading strong {
    font-size: 14px;
  }

  .group-heading > span {
    padding: 3px 8px;
    font-size: 11px;
    color: #7c857f;
    background: #edf0ee;
    border-radius: 999px;
  }

  .reply-list {
    display: grid;
  }

  .reply-row {
    display: grid;
    grid-template-columns: 28px minmax(0, 1fr) 88px 74px;
    gap: 12px;
    align-items: center;
    padding: 13px 16px;
    border-bottom: 1px solid #f0f2f1;
  }

  .reply-row:last-child {
    border-bottom: 0;
  }

  .reply-order {
    display: grid;
    place-items: center;
    width: 26px;
    height: 26px;
    font-size: 11px;
    color: #758079;
    background: #f1f4f2;
    border-radius: 50%;
  }

  .reply-row :deep(.el-textarea__inner) {
    min-height: 56px !important;
    background: #fafbfa;
    box-shadow: 0 0 0 1px #e4e8e6 inset;
  }

  .reply-row :deep(.el-textarea__inner:focus) {
    box-shadow:
      0 0 0 1px #27c875 inset,
      0 0 0 3px rgb(39 200 117 / 8%);
  }

  .save-state {
    display: inline-flex;
    gap: 5px;
    align-items: center;
    min-width: 74px;
    font-size: 11px;
    color: #9aa19d;
  }

  .save-state--saving,
  .save-state--pending {
    color: #7f8b84;
  }

  .save-state--saved {
    color: #0a9b50;
  }

  .save-state--error {
    color: #dc5252;
  }

  .reply-actions button.danger:hover {
    color: #dc5252;
    background: #fff2f2;
    border-color: #f6d7d7;
  }

  .empty-group,
  .empty-library {
    display: grid;
    place-items: center;
    color: #a0a7a3;
  }

  .empty-group {
    min-height: 92px;
    font-size: 13px;
  }

  .empty-library {
    min-height: 220px;
    margin-top: 20px;
    text-align: center;
    background: #fafbfa;
    border: 1px dashed #e0e5e2;
    border-radius: 10px;
  }

  .empty-library svg {
    margin-bottom: 9px;
    color: #b9c1bc;
  }

  .empty-library strong {
    font-size: 14px;
    color: #707873;
  }

  .empty-library span {
    margin-top: 5px;
    font-size: 12px;
  }

  .group-select {
    width: 100%;
  }

  .spin {
    animation: quick-reply-spin 0.9s linear infinite;
  }

  @keyframes quick-reply-spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (width <= 1180px) {
    .quick-card {
      padding: 24px;
    }

    .reply-row {
      grid-template-columns: 28px minmax(0, 1fr) 74px;
    }

    .save-state {
      display: none;
    }
  }
</style>
