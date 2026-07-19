<template>
  <div class="customer-service-page art-full-height">
    <ElCard class="toolbar-card" shadow="never">
      <div class="toolbar">
        <div>
          <div class="toolbar__title">在线客服</div>
          <div class="toolbar__subtitle">公共待接待队列 · 按次咨询 · 支持图片、商品和订单卡片</div>
        </div>
        <div class="toolbar__actions">
          <div v-if="agentState" class="agent-state">
            <ElTag :type="agentStateTagType" effect="plain">{{ agentStateLabel }}</ElTag>
            <span
              >{{ agentState.activeConversationCount }}/{{
                agentState.maxActiveConversations
              }}</span
            >
            <ElButton
              v-if="agentState.workStatus === 'AVAILABLE'"
              size="small"
              :loading="agentStateLoading"
              @click="handleAgentStateChange('BUSY')"
            >
              暂停接待
            </ElButton>
            <ElButton
              v-else
              type="primary"
              size="small"
              :loading="agentStateLoading"
              @click="handleAgentStateChange('AVAILABLE')"
            >
              开始接待
            </ElButton>
          </div>
          <ElBadge :value="pendingTransfers.length" :hidden="pendingTransfers.length === 0">
            <ElButton @click="pendingDialogVisible = true">转接申请</ElButton>
          </ElBadge>
          <ElRadioGroup v-model="activeStatus" @change="handleStatusChange">
            <ElRadioButton label="ALL">全部</ElRadioButton>
            <ElRadioButton label="WAITING">待接待</ElRadioButton>
            <ElRadioButton label="ACTIVE">接待中</ElRadioButton>
            <ElRadioButton label="CLOSED">已结束</ElRadioButton>
          </ElRadioGroup>
          <ElButton :loading="listLoading" @click="refreshAll">刷新</ElButton>
        </div>
      </div>
    </ElCard>

    <div class="workspace">
      <ElCard class="conversation-panel" shadow="never" body-class="panel-body">
        <template #header>
          <div class="panel-header">
            <span>会话队列</span>
            <ElTag type="info" effect="plain">{{ conversationPage.total }} 个</ElTag>
          </div>
        </template>
        <div v-loading="listLoading" class="conversation-list">
          <button
            v-for="conversation in conversationPage.records"
            :key="conversation.conversationId"
            type="button"
            class="conversation-item"
            :class="{ 'is-active': selectedConversationId === conversation.conversationId }"
            @click="selectConversation(conversation.conversationId)"
          >
            <div class="conversation-item__top">
              <strong>{{ conversation.userNickname || `用户 ${conversation.appUserId}` }}</strong>
              <ElBadge
                v-if="conversation.adminUnreadCount > 0"
                :value="conversation.adminUnreadCount"
                :max="99"
              />
              <ElTag :type="statusConfig[conversation.status].type" size="small">
                {{ statusConfig[conversation.status].label }}
              </ElTag>
            </div>
            <div class="conversation-item__message">
              {{ conversation.lastMessagePreview || '用户已打开客服会话' }}
            </div>
            <div
              v-if="conversation.currentContext.type !== 'GENERAL'"
              class="conversation-item__context"
            >
              {{ contextLabel(conversation.currentContext) }}
            </div>
            <div class="conversation-item__meta">
              <span>{{ conversation.assignedAdminDisplayName || '尚未分配客服' }}</span>
              <span>{{
                formatDateTime(conversation.lastMessageAt || conversation.createdAt)
              }}</span>
            </div>
          </button>
          <ElEmpty
            v-if="!listLoading && conversationPage.records.length === 0"
            description="当前没有会话"
            :image-size="80"
          />
        </div>
      </ElCard>

      <ElCard class="chat-panel" shadow="never" body-class="chat-panel__body">
        <template #header>
          <div class="panel-header chat-header">
            <div>
              <strong>{{ currentDetail?.userNickname || '请选择一个会话' }}</strong>
              <span v-if="currentDetail" class="chat-header__status">
                {{ statusConfig[currentDetail.status].label }}
                <template v-if="currentDetail.assignedAdminDisplayName">
                  · {{ currentDetail.assignedAdminDisplayName }}
                </template>
              </span>
            </div>
            <div v-if="currentDetail" class="chat-header__actions">
              <ElButton
                v-if="currentDetail.status === 'WAITING'"
                v-auth="'customer-service:conversation:claim'"
                type="primary"
                :disabled="!agentState?.canReceive"
                :loading="actionLoading"
                @click="handleClaim"
              >
                认领会话
              </ElButton>
              <template v-else-if="currentDetail.status === 'ACTIVE'">
                <ElButton
                  v-if="isCurrentAgent"
                  v-auth="'customer-service:conversation:transfer'"
                  :loading="actionLoading"
                  @click="openTransferDialog"
                >
                  转接
                </ElButton>
                <ElButton
                  v-else-if="hasAgentManage"
                  :loading="actionLoading"
                  @click="openTransferDialog"
                >
                  管理转接
                </ElButton>
                <ElButton
                  v-if="isCurrentAgent"
                  v-auth="'customer-service:conversation:transfer'"
                  :loading="actionLoading"
                  @click="handleRelease"
                >
                  退回待接待
                </ElButton>
                <ElButton
                  v-else-if="hasAgentManage"
                  :loading="actionLoading"
                  @click="handleRelease"
                >
                  退回待接待
                </ElButton>
                <ElButton
                  v-if="isCurrentAgent"
                  v-auth="'customer-service:conversation:close'"
                  type="danger"
                  plain
                  :loading="actionLoading"
                  @click="handleClose"
                >
                  结束会话
                </ElButton>
              </template>
            </div>
          </div>
        </template>

        <div v-loading="detailLoading" class="chat-content">
          <div v-if="currentDetail" ref="messageListRef" class="message-list">
            <div
              v-for="message in currentDetail.messages"
              :key="message.messageId"
              class="message-row"
              :class="`is-${message.senderType.toLowerCase()}`"
            >
              <div v-if="message.senderType !== 'SYSTEM'" class="message-sender">
                {{ message.senderName }} · {{ formatDateTime(message.createdAt) }}
              </div>
              <div v-if="message.senderType === 'SYSTEM'" class="system-message">
                {{ message.content }}
              </div>
              <ElImage
                v-else-if="message.messageType === 'IMAGE'"
                class="message-image"
                :src="imageUrls[message.messageId]"
                :preview-src-list="
                  imageUrls[message.messageId] ? [imageUrls[message.messageId]] : []
                "
                fit="cover"
                preview-teleported
              >
                <template #error><div class="message-image__error">图片加载失败</div></template>
              </ElImage>
              <button
                v-else-if="message.messageType === 'ORDER_CARD' && message.order"
                type="button"
                class="message-card"
                @click="openOrder(message.order.orderNo)"
              >
                <img
                  v-if="message.order.primaryProductImage"
                  :src="message.order.primaryProductImage"
                />
                <span>
                  <strong>{{ message.order.orderNo }}</strong>
                  <small>{{ message.order.primaryProductTitle || '订单商品' }}</small>
                  <small
                    >{{ orderStatusLabel(message.order.status) }} ·
                    {{ formatMoney(message.order.payableAmountCent) }}</small
                  >
                </span>
              </button>
              <button
                v-else-if="message.messageType === 'PRODUCT_CARD' && message.product"
                type="button"
                class="message-card"
                @click="openProduct(message.product.productId)"
              >
                <img v-if="message.product.image" :src="message.product.image" />
                <span>
                  <strong>{{ message.product.title }}</strong>
                  <small>{{ productPrice(message.product) }}</small>
                </span>
              </button>
              <div v-else class="message-bubble">{{ message.content }}</div>
            </div>
            <ElEmpty
              v-if="currentDetail.messages.length === 0"
              description="暂无消息，认领后即可开始回复"
              :image-size="72"
            />
          </div>
          <ElEmpty v-else description="从左侧选择一个会话" />
        </div>

        <div class="composer">
          <ElInput
            v-model="messageDraft"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
            resize="none"
            :disabled="!canSend"
            :placeholder="composerPlaceholder"
            @keydown.ctrl.enter.prevent="handleSend"
            @keydown.meta.enter.prevent="handleSend"
          />
          <div class="composer__footer">
            <div class="composer__tools">
              <input
                ref="imageInputRef"
                class="image-input"
                type="file"
                accept="image/jpeg,image/png,image/webp,image/gif"
                @change="handleImageSelected"
              />
              <ElButton
                v-auth="'customer-service:message:send'"
                :disabled="!canSend"
                :loading="uploadingImage"
                @click="openImagePicker"
                >图片</ElButton
              >
              <ElButton
                v-auth="'customer-service:order:link'"
                :disabled="!canSend"
                @click="openOrderDialog"
                >订单</ElButton
              >
              <ElButton
                v-auth="'customer-service:product:send'"
                :disabled="!canSend"
                @click="openProductDialog"
                >商品</ElButton
              >
              <span>Ctrl / ⌘ + Enter 发送</span>
            </div>
            <ElButton
              v-auth="'customer-service:message:send'"
              type="primary"
              :disabled="!canSend || !messageDraft.trim()"
              :loading="sending"
              @click="handleSend"
              >发送</ElButton
            >
          </div>
        </div>
      </ElCard>

      <ElCard class="context-panel" shadow="never" body-class="context-body">
        <template #header>
          <div class="panel-header">
            <span>本次咨询</span>
            <ElTag v-if="currentDetail" type="info" effect="plain"
              >第 {{ currentDetail.consultationNo }} 次</ElTag
            >
          </div>
        </template>
        <template v-if="currentDetail">
          <ElDescriptions :column="1" border>
            <ElDescriptionsItem label="用户名称">
              {{ currentDetail.userNickname || '-' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="用户 ID">
              {{ currentDetail.appUserId }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="当前客服">
              {{ currentDetail.assignedAdminDisplayName || '未分配' }}
            </ElDescriptionsItem>
          </ElDescriptions>

          <div class="context-section-title">当前咨询对象</div>
          <button
            v-if="currentDetail.currentContext.order"
            type="button"
            class="linked-order"
            @click="openOrder(currentDetail.currentContext.order.orderNo)"
          >
            <strong>{{ currentDetail.currentContext.order.orderNo }}</strong>
            <span>{{ currentDetail.currentContext.order.primaryProductTitle || '订单商品' }}</span>
            <span
              >{{ orderStatusLabel(currentDetail.currentContext.order.status) }} ·
              {{ formatMoney(currentDetail.currentContext.order.payableAmountCent) }}</span
            >
          </button>
          <button
            v-else-if="currentDetail.currentContext.product"
            type="button"
            class="linked-order"
            @click="openProduct(currentDetail.currentContext.product.productId)"
          >
            <strong>{{ currentDetail.currentContext.product.title }}</strong>
            <span>{{ productPrice(currentDetail.currentContext.product) }}</span>
          </button>
          <ElEmpty v-else description="普通咨询" :image-size="48" />

          <div class="context-section-title">本次相关订单</div>
          <div v-if="currentDetail.linkedOrders.length" class="linked-orders">
            <button
              v-for="order in currentDetail.linkedOrders"
              :key="order.orderId"
              type="button"
              class="linked-order"
              @click="openOrder(order.orderNo)"
            >
              <strong>{{ order.orderNo }}</strong>
              <span
                >{{ orderStatusLabel(order.status) }} ·
                {{ formatMoney(order.payableAmountCent) }}</span
              >
              <span>{{ formatDateTime(order.createdAt) }}</span>
            </button>
          </div>
          <ElEmpty v-else description="暂无相关订单" :image-size="48" />

          <div class="context-section-title">本次相关商品</div>
          <div v-if="currentDetail.linkedProducts.length" class="linked-orders">
            <button
              v-for="product in currentDetail.linkedProducts"
              :key="product.productId"
              type="button"
              class="linked-order"
              @click="openProduct(product.productId)"
            >
              <strong>{{ product.title }}</strong>
              <span>{{ productPrice(product) }}</span>
            </button>
          </div>
          <ElEmpty v-else description="暂无相关商品" :image-size="48" />
        </template>
        <ElEmpty v-else description="暂无用户信息" />
      </ElCard>
    </div>

    <ElDialog v-model="transferDialogVisible" title="转接会话" width="520px">
      <ElForm label-width="90px">
        <ElFormItem label="目标客服">
          <ElSelect
            v-model="targetAgentId"
            filterable
            class="dialog-select"
            placeholder="请选择客服"
          >
            <ElOption
              v-for="agent in transferAgents"
              :key="agent.adminUserId"
              :label="agentOptionLabel(agent)"
              :value="agent.adminUserId"
              :disabled="!agent.online || (!hasAgentManage && !agent.canReceive)"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="转接原因">
          <ElSelect v-model="transferReasonCode" class="dialog-select" placeholder="请选择原因">
            <ElOption label="专业问题" value="EXPERTISE" />
            <ElOption label="交接班" value="SHIFT" />
            <ElOption label="用户指定客服" value="USER_REQUEST" />
            <ElOption label="其他" value="OTHER" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="补充说明">
          <ElInput
            v-model="transferReasonNote"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="向接收客服说明需要交接的内容"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="transferDialogVisible = false">取消</ElButton>
        <ElButton
          v-if="isCurrentAgent"
          type="primary"
          :disabled="!selectedTransferAgent?.canReceive || !transferReasonCode"
          :loading="actionLoading"
          @click="handleTransfer"
        >
          发送转接申请
        </ElButton>
        <ElButton
          v-if="hasAgentManage"
          type="danger"
          plain
          :disabled="!selectedTransferAgent?.online || !transferReasonCode"
          :loading="actionLoading"
          @click="handleForceTransfer"
        >
          强制转接
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="pendingDialogVisible" title="待处理转接申请" width="620px">
      <div v-if="pendingTransfers.length" class="transfer-request-list">
        <div v-for="request in pendingTransfers" :key="request.requestId" class="transfer-request">
          <div>
            <strong>
              {{ request.fromAdminDisplayName }} 请求转接
              {{ request.userNickname || `用户 ${request.appUserId}` }}
            </strong>
            <p>
              {{ transferReasonLabel(request.reasonCode) }}
              <template v-if="request.reasonNote"> · {{ request.reasonNote }}</template>
            </p>
            <p>
              {{ contextLabel(request.currentContext) }} ·
              {{ request.lastMessagePreview || '暂无消息' }}
            </p>
            <small>请在 {{ formatDateTime(request.expiresAt) }} 前处理</small>
          </div>
          <div class="transfer-request__actions">
            <ElButton size="small" @click="handleRejectTransfer(request)">拒绝</ElButton>
            <ElButton
              type="primary"
              size="small"
              :disabled="!agentState?.canReceive"
              @click="handleAcceptTransfer(request)"
            >
              接受
            </ElButton>
          </div>
        </div>
      </div>
      <ElEmpty v-else description="暂无待处理转接申请" :image-size="72" />
    </ElDialog>

    <ElDialog v-model="orderDialogVisible" title="发送用户订单" width="700px">
      <ElTable :data="orderCandidates" v-loading="orderCandidatesLoading" max-height="420">
        <ElTableColumn prop="orderNo" label="订单号" min-width="210" />
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">{{ orderStatusLabel(row.status) }}</template>
        </ElTableColumn>
        <ElTableColumn label="应付金额" width="110">
          <template #default="{ row }">{{ formatMoney(row.payableAmountCent) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="90">
          <template #default="{ row }">
            <ElButton
              type="primary"
              link
              :disabled="isOrderLinked(row.orderId)"
              @click="handleLinkOrder(row.orderId)"
            >
              {{ isOrderLinked(row.orderId) ? '已发送' : '发送' }}
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElDialog>

    <ElDialog v-model="productDialogVisible" title="发送商品" width="720px">
      <div class="product-search">
        <ElInput
          v-model="productKeyword"
          clearable
          placeholder="搜索商品名称"
          @keyup.enter="loadProductCandidates"
        />
        <ElButton type="primary" @click="loadProductCandidates">搜索</ElButton>
      </div>
      <ElTable :data="productCandidates" v-loading="productCandidatesLoading" max-height="420">
        <ElTableColumn label="商品" min-width="320">
          <template #default="{ row }">
            <div class="product-cell">
              <img v-if="row.image" :src="row.image" />
              <span>{{ row.title }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="价格" width="140">
          <template #default="{ row }">{{ productPrice(row) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="90">
          <template #default="{ row }"
            ><ElButton type="primary" link @click="handleLinkProduct(row.productId)"
              >发送</ElButton
            ></template
          >
        </ElTableColumn>
      </ElTable>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useRoute, useRouter } from 'vue-router'
  import {
    acceptCustomerServiceTransfer,
    claimCustomerServiceConversation,
    closeCustomerServiceConversation,
    fetchCustomerServiceAgents,
    fetchCustomerServiceAgentState,
    fetchCustomerServiceConversation,
    fetchCustomerServiceConversations,
    fetchCustomerServiceImage,
    fetchCustomerServiceOrderCandidates,
    fetchCustomerServiceProductCandidates,
    fetchPendingCustomerServiceTransfers,
    forceTransferCustomerServiceConversation,
    linkCustomerServiceOrder,
    linkCustomerServiceProduct,
    rejectCustomerServiceTransfer,
    releaseCustomerServiceConversation,
    requestCustomerServiceTransfer,
    sendCustomerServiceMessage,
    updateCustomerServiceAgentState,
    uploadCustomerServiceImage
  } from '@/api/customer-service'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useUserStore } from '@/store/modules/user'
  import {
    customerServiceStatusFromQuery,
    type CustomerServiceStatusFilter
  } from '@/utils/business-route-query'
  import { realtimeClient, type RealtimeEvent } from '@/utils/realtime'

  const route = useRoute()
  const router = useRouter()
  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const activeStatus = ref<CustomerServiceStatusFilter>(
    customerServiceStatusFromQuery(route.query.status) || 'ALL'
  )
  const conversationPage = ref<Api.CustomerService.ConversationPage>({
    records: [],
    current: 1,
    size: 50,
    total: 0
  })
  const selectedConversationId = ref<number | null>(null)
  const currentDetail = ref<Api.CustomerService.ConversationDetail | null>(null)
  const listLoading = ref(false)
  const detailLoading = ref(false)
  const actionLoading = ref(false)
  const sending = ref(false)
  const uploadingImage = ref(false)
  const messageDraft = ref('')
  const messageListRef = ref<HTMLElement | null>(null)
  const imageInputRef = ref<HTMLInputElement | null>(null)
  const imageUrls = ref<Record<number, string>>({})
  const transferDialogVisible = ref(false)
  const agents = ref<Api.CustomerService.Agent[]>([])
  const targetAgentId = ref<number | null>(null)
  const transferReasonCode = ref('')
  const transferReasonNote = ref('')
  const pendingDialogVisible = ref(false)
  const pendingTransfers = ref<Api.CustomerService.TransferRequest[]>([])
  const agentState = ref<Api.CustomerService.AgentState | null>(null)
  const agentStateLoading = ref(false)
  const orderDialogVisible = ref(false)
  const orderCandidatesLoading = ref(false)
  const orderCandidates = ref<Api.CustomerService.LinkedOrder[]>([])
  const productDialogVisible = ref(false)
  const productCandidatesLoading = ref(false)
  const productCandidates = ref<Api.CustomerService.LinkedProduct[]>([])
  const productKeyword = ref('')
  let detailRequestSequence = 0
  let pollTimer: ReturnType<typeof setInterval> | null = null
  let realtimeRefreshTimer: ReturnType<typeof setTimeout> | null = null
  let unsubscribeRealtime: (() => void) | null = null

  const statusConfig: Record<
    Api.CustomerService.ConversationStatus,
    { label: string; type: 'warning' | 'success' | 'info' }
  > = {
    DRAFT: { label: '草稿', type: 'info' },
    WAITING: { label: '待接待', type: 'warning' },
    ACTIVE: { label: '接待中', type: 'success' },
    CLOSED: { label: '已结束', type: 'info' }
  }

  const orderStatusLabels: Record<Api.Order.OrderStatus, string> = {
    CREATED: '待付款',
    PAYING: '支付中',
    PAID: '待发货',
    SHIPPED: '待收货',
    COMPLETED: '已完成',
    CLOSED: '已关闭',
    REFUNDING: '退款中',
    REFUNDED: '已退款'
  }

  const currentAdminId = computed(() => Number(userStore.info.userId || 0))
  const hasAgentManage = computed(() => hasAuth('customer-service:agent:manage'))
  const isCurrentAgent = computed(
    () =>
      currentDetail.value?.status === 'ACTIVE' &&
      currentDetail.value.assignedAdminUserId === currentAdminId.value
  )
  const canSend = computed(() => Boolean(currentDetail.value && isCurrentAgent.value))
  const composerPlaceholder = computed(() => {
    if (!currentDetail.value) return '请先选择会话'
    if (currentDetail.value.status === 'WAITING') return '请先认领会话'
    if (currentDetail.value.status === 'CLOSED') return '会话已结束，用户再次咨询后会重新进入队列'
    if (!isCurrentAgent.value)
      return `当前由 ${currentDetail.value.assignedAdminDisplayName || '其他客服'} 接待`
    return '输入回复内容'
  })
  const transferAgents = computed(() =>
    agents.value.filter((agent) => agent.adminUserId !== currentDetail.value?.assignedAdminUserId)
  )
  const selectedTransferAgent = computed(() =>
    agents.value.find((agent) => agent.adminUserId === targetAgentId.value)
  )
  const agentStateLabel = computed(() => {
    if (!agentState.value?.online || agentState.value.workStatus === 'OFFLINE') return '离线'
    if (agentState.value.workStatus === 'BUSY') return '在线 · 暂停接待'
    if (!agentState.value.canReceive) return '在线 · 已满'
    return '在线 · 可接待'
  })
  const agentStateTagType = computed<'success' | 'warning' | 'info'>(() => {
    if (!agentState.value?.online || agentState.value.workStatus === 'OFFLINE') return 'info'
    return agentState.value.canReceive ? 'success' : 'warning'
  })

  const formatDateTime = (value?: string | null) => {
    if (!value) return '-'
    return value.replace('T', ' ').slice(0, 19)
  }
  const formatMoney = (value: number) => `¥${(value / 100).toFixed(2)}`
  const orderStatusLabel = (status: Api.Order.OrderStatus) => orderStatusLabels[status] || status
  const productPrice = (product: Api.CustomerService.LinkedProduct) => {
    if (product.minPriceCent === null) return '价格待确认'
    if (product.maxPriceCent === null || product.maxPriceCent === product.minPriceCent)
      return formatMoney(product.minPriceCent)
    return `${formatMoney(product.minPriceCent)} - ${formatMoney(product.maxPriceCent)}`
  }
  const contextLabel = (context: Api.CustomerService.ConsultationContext) => {
    if (context.order) return `咨询订单：${context.order.orderNo}`
    if (context.product) return `咨询商品：${context.product.title}`
    return '普通咨询'
  }
  const transferReasonLabels: Record<string, string> = {
    EXPERTISE: '专业问题',
    SHIFT: '交接班',
    USER_REQUEST: '用户指定客服',
    SUPERVISOR: '管理员调度',
    OTHER: '其他'
  }
  const transferReasonLabel = (reasonCode: string) => transferReasonLabels[reasonCode] || reasonCode
  const agentOptionLabel = (agent: Api.CustomerService.Agent) => {
    const status = !agent.online
      ? '离线'
      : agent.workStatus === 'BUSY'
        ? '忙碌'
        : agent.canReceive
          ? '可接待'
          : '已满'
    return `${agent.displayName}（${agent.username}）· ${status} ${agent.activeConversationCount}/${agent.maxActiveConversations}`
  }

  const clearImageUrls = () => {
    Object.values(imageUrls.value).forEach((url) => URL.revokeObjectURL(url))
    imageUrls.value = {}
  }

  const syncImageUrls = async (messages: Api.CustomerService.Message[]) => {
    const missing = messages.filter(
      (message) => message.messageType === 'IMAGE' && !imageUrls.value[message.messageId]
    )
    const downloaded = await Promise.allSettled(
      missing.map(async (message) => {
        const blob = await fetchCustomerServiceImage(message.messageId)
        return [message.messageId, URL.createObjectURL(blob)] as const
      })
    )
    const next = { ...imageUrls.value }
    downloaded.forEach((result) => {
      if (result.status === 'fulfilled') next[result.value[0]] = result.value[1]
    })
    imageUrls.value = next
  }

  const loadConversations = async (selectFirst = false) => {
    listLoading.value = true
    try {
      const page = await fetchCustomerServiceConversations({
        current: 1,
        size: 50,
        status: activeStatus.value === 'ALL' ? undefined : activeStatus.value
      })
      conversationPage.value = page
      if (selectFirst && !selectedConversationId.value && page.records.length) {
        await selectConversation(page.records[0].conversationId)
      }
    } finally {
      listLoading.value = false
    }
  }

  const loadAgentState = async () => {
    agentState.value = await fetchCustomerServiceAgentState()
  }

  const loadPendingTransfers = async () => {
    pendingTransfers.value = await fetchPendingCustomerServiceTransfers()
    if (pendingTransfers.value.length === 0) pendingDialogVisible.value = false
  }

  const loadDetail = async (conversationId: number) => {
    const requestId = ++detailRequestSequence
    detailLoading.value = true
    try {
      const detail = await fetchCustomerServiceConversation(conversationId)
      if (requestId !== detailRequestSequence) return
      currentDetail.value = detail
      await syncImageUrls(detail.messages)
      await nextTick()
      if (messageListRef.value) messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    } finally {
      if (requestId === detailRequestSequence) detailLoading.value = false
    }
  }

  const selectConversation = async (conversationId: number) => {
    if (selectedConversationId.value !== conversationId) clearImageUrls()
    selectedConversationId.value = conversationId
    await loadDetail(conversationId)
    const record = conversationPage.value.records.find(
      (conversation) => conversation.conversationId === conversationId
    )
    if (record) record.adminUnreadCount = 0
  }

  const refreshAll = async () => {
    await Promise.all([loadConversations(), loadAgentState(), loadPendingTransfers()])
    if (selectedConversationId.value) await loadDetail(selectedConversationId.value)
  }

  const handleStatusChange = async () => {
    selectedConversationId.value = null
    currentDetail.value = null
    await loadConversations(true)
  }

  watch(
    () => route.query.status,
    async () => {
      const status = customerServiceStatusFromQuery(route.query.status) || 'ALL'
      if (route.path !== '/customer-service' || status === activeStatus.value) return
      activeStatus.value = status
      await handleStatusChange()
    }
  )

  const handleClaim = async () => {
    if (!selectedConversationId.value) return
    actionLoading.value = true
    try {
      currentDetail.value = await claimCustomerServiceConversation(selectedConversationId.value)
      await Promise.all([loadConversations(), loadAgentState()])
      ElMessage.success('会话已认领')
    } finally {
      actionLoading.value = false
    }
  }

  const handleSend = async () => {
    const content = messageDraft.value.trim()
    if (!selectedConversationId.value || !content || !canSend.value) return
    sending.value = true
    try {
      await sendCustomerServiceMessage(selectedConversationId.value, {
        content,
        clientMessageId: createClientMessageId()
      })
      messageDraft.value = ''
      await loadDetail(selectedConversationId.value)
      await loadConversations()
    } finally {
      sending.value = false
    }
  }

  const openImagePicker = () => imageInputRef.value?.click()

  const handleImageSelected = async (event: Event) => {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    input.value = ''
    if (!selectedConversationId.value || !file || !canSend.value) return
    uploadingImage.value = true
    try {
      await uploadCustomerServiceImage(selectedConversationId.value, file)
      await loadDetail(selectedConversationId.value)
      await loadConversations()
    } finally {
      uploadingImage.value = false
    }
  }

  const openTransferDialog = async () => {
    agents.value = await fetchCustomerServiceAgents()
    targetAgentId.value = null
    transferReasonCode.value = ''
    transferReasonNote.value = ''
    transferDialogVisible.value = true
  }

  const handleTransfer = async () => {
    if (
      !selectedConversationId.value ||
      !targetAgentId.value ||
      !transferReasonCode.value ||
      !selectedTransferAgent.value?.canReceive
    )
      return
    actionLoading.value = true
    try {
      await requestCustomerServiceTransfer(selectedConversationId.value, {
        targetAdminUserId: targetAgentId.value,
        reasonCode: transferReasonCode.value,
        reasonNote: transferReasonNote.value.trim() || undefined
      })
      transferDialogVisible.value = false
      ElMessage.success('转接申请已发送，等待对方确认')
    } finally {
      actionLoading.value = false
    }
  }

  const handleForceTransfer = async () => {
    if (
      !selectedConversationId.value ||
      !targetAgentId.value ||
      !transferReasonCode.value ||
      !selectedTransferAgent.value?.online ||
      !hasAgentManage.value
    )
      return
    await ElMessageBox.confirm(
      `将立即把会话交给 ${selectedTransferAgent.value.displayName}，无需对方确认。`,
      '确认强制转接',
      { type: 'warning' }
    )
    actionLoading.value = true
    try {
      currentDetail.value = await forceTransferCustomerServiceConversation(
        selectedConversationId.value,
        {
          targetAdminUserId: targetAgentId.value,
          reasonCode: transferReasonCode.value,
          reasonNote: transferReasonNote.value.trim() || undefined
        }
      )
      transferDialogVisible.value = false
      await Promise.all([loadConversations(), loadAgentState()])
      ElMessage.success('会话已强制转接')
    } finally {
      actionLoading.value = false
    }
  }

  const handleRelease = async () => {
    if (!selectedConversationId.value) return
    await ElMessageBox.confirm('会话将清空当前负责人并回到公共待接待队列。', '退回待接待', {
      type: 'warning'
    })
    actionLoading.value = true
    try {
      currentDetail.value = await releaseCustomerServiceConversation(selectedConversationId.value)
      await Promise.all([loadConversations(), loadAgentState()])
      ElMessage.success('会话已退回待接待队列')
    } finally {
      actionLoading.value = false
    }
  }

  const handleAcceptTransfer = async (request: Api.CustomerService.TransferRequest) => {
    actionLoading.value = true
    try {
      currentDetail.value = await acceptCustomerServiceTransfer(request.requestId)
      selectedConversationId.value = request.conversationId
      await Promise.all([loadConversations(), loadAgentState(), loadPendingTransfers()])
      ElMessage.success('已接受转接')
    } finally {
      actionLoading.value = false
    }
  }

  const handleRejectTransfer = async (request: Api.CustomerService.TransferRequest) => {
    actionLoading.value = true
    try {
      await rejectCustomerServiceTransfer(request.requestId)
      await loadPendingTransfers()
      ElMessage.success('已拒绝转接')
    } finally {
      actionLoading.value = false
    }
  }

  const handleAgentStateChange = async (workStatus: 'AVAILABLE' | 'BUSY') => {
    agentStateLoading.value = true
    try {
      agentState.value = await updateCustomerServiceAgentState(workStatus)
      ElMessage.success(workStatus === 'AVAILABLE' ? '已开始接待' : '已暂停接待')
    } finally {
      agentStateLoading.value = false
    }
  }

  const handleClose = async () => {
    if (!selectedConversationId.value) return
    await ElMessageBox.confirm('结束后，用户再次发送消息会重新进入待接待队列。', '结束会话', {
      type: 'warning'
    })
    actionLoading.value = true
    try {
      currentDetail.value = await closeCustomerServiceConversation(selectedConversationId.value)
      await Promise.all([loadConversations(), loadAgentState()])
      ElMessage.success('会话已结束')
    } finally {
      actionLoading.value = false
    }
  }

  const openOrderDialog = async () => {
    if (!selectedConversationId.value) return
    orderDialogVisible.value = true
    orderCandidatesLoading.value = true
    try {
      orderCandidates.value = await fetchCustomerServiceOrderCandidates(
        selectedConversationId.value
      )
    } finally {
      orderCandidatesLoading.value = false
    }
  }

  const isOrderLinked = (orderId: number) =>
    currentDetail.value?.linkedOrders.some((order) => order.orderId === orderId) ?? false

  const handleLinkOrder = async (orderId: number) => {
    if (!selectedConversationId.value) return
    await linkCustomerServiceOrder(selectedConversationId.value, orderId)
    await loadDetail(selectedConversationId.value)
    orderDialogVisible.value = false
    ElMessage.success('订单卡片已发送')
  }

  const loadProductCandidates = async () => {
    if (!selectedConversationId.value) return
    productCandidatesLoading.value = true
    try {
      productCandidates.value = await fetchCustomerServiceProductCandidates(
        selectedConversationId.value,
        productKeyword.value.trim() || undefined
      )
    } finally {
      productCandidatesLoading.value = false
    }
  }

  const openProductDialog = async () => {
    if (!selectedConversationId.value) return
    productDialogVisible.value = true
    await loadProductCandidates()
  }

  const handleLinkProduct = async (productId: number) => {
    if (!selectedConversationId.value) return
    await linkCustomerServiceProduct(selectedConversationId.value, productId)
    await loadDetail(selectedConversationId.value)
    productDialogVisible.value = false
    ElMessage.success('商品卡片已发送')
  }

  const openOrder = (orderNo: string) => {
    void router.push({ path: '/trade/orders', query: { orderNo } })
  }

  const openProduct = (productId: number) => {
    void router.push({ path: '/product/spu', query: { mode: 'edit', id: String(productId) } })
  }

  const createClientMessageId = () => {
    if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
    return `admin-${Date.now()}-${Math.random().toString(16).slice(2)}`
  }

  const handleRealtimeEvent = (event: RealtimeEvent) => {
    if (event.type.startsWith('CUSTOMER_SERVICE_TRANSFER_')) {
      void Promise.all([loadPendingTransfers(), loadAgentState()])
      const targetAdminUserId = Number(event.data.toAdminUserId || 0)
      const sourceAdminUserId = Number(event.data.fromAdminUserId || 0)
      if (
        event.type === 'CUSTOMER_SERVICE_TRANSFER_REQUESTED' &&
        targetAdminUserId === currentAdminId.value
      ) {
        pendingDialogVisible.value = true
        ElMessage.info('收到新的转接申请')
      }
      if (
        sourceAdminUserId === currentAdminId.value &&
        event.type === 'CUSTOMER_SERVICE_TRANSFER_REJECTED'
      ) {
        ElMessage.warning('对方已拒绝转接，会话仍由你接待')
      }
      if (
        sourceAdminUserId === currentAdminId.value &&
        event.type === 'CUSTOMER_SERVICE_TRANSFER_TIMEOUT'
      ) {
        ElMessage.warning('转接申请已超时，会话仍由你接待')
      }
    }
    if (event.type !== 'CUSTOMER_SERVICE_CONVERSATION_UPDATED') return
    if (realtimeRefreshTimer) clearTimeout(realtimeRefreshTimer)
    realtimeRefreshTimer = setTimeout(() => {
      realtimeRefreshTimer = null
      void refreshAll()
    }, 250)
  }

  onMounted(async () => {
    unsubscribeRealtime = realtimeClient.subscribe(handleRealtimeEvent)
    await new Promise((resolve) => setTimeout(resolve, 300))
    await Promise.all([loadConversations(true), loadAgentState(), loadPendingTransfers()])
    pollTimer = setInterval(() => {
      if (!document.hidden) void refreshAll()
    }, 15000)
  })

  onBeforeUnmount(() => {
    unsubscribeRealtime?.()
    if (pollTimer) clearInterval(pollTimer)
    if (realtimeRefreshTimer) clearTimeout(realtimeRefreshTimer)
    clearImageUrls()
  })
</script>

<style scoped lang="scss">
  .customer-service-page {
    display: flex;
    flex-direction: column;
    gap: 12px;
    min-height: 720px;
  }

  .toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }

  .toolbar__title {
    font-size: 20px;
    font-weight: 600;
  }

  .toolbar__subtitle {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  .toolbar__actions,
  .agent-state,
  .panel-header,
  .chat-header__actions,
  .composer__footer {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .agent-state {
    padding-right: 10px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
    border-right: 1px solid var(--el-border-color-lighter);
  }

  .panel-header,
  .composer__footer {
    justify-content: space-between;
  }

  .workspace {
    display: grid;
    grid-template-columns: minmax(270px, 320px) minmax(520px, 1fr) minmax(260px, 320px);
    flex: 1;
    gap: 12px;
    min-height: 0;
  }

  .conversation-panel,
  .chat-panel,
  .context-panel {
    min-height: 0;
  }

  :deep(.panel-body),
  :deep(.chat-panel__body),
  :deep(.context-body) {
    height: calc(100% - 56px);
    padding: 0;
  }

  .conversation-list {
    height: 100%;
    overflow-y: auto;
  }

  .conversation-item {
    display: block;
    width: 100%;
    padding: 14px 16px;
    color: inherit;
    text-align: left;
    background: transparent;
    border: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
    cursor: pointer;
  }

  .conversation-item:hover,
  .conversation-item.is-active {
    background: var(--el-color-primary-light-9);
  }

  .conversation-item__top,
  .conversation-item__meta {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .conversation-item__top strong {
    flex: 1;
  }

  .conversation-item__message {
    margin: 8px 0;
    overflow: hidden;
    color: var(--el-text-color-regular);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .conversation-item__context {
    margin-bottom: 8px;
    overflow: hidden;
    color: var(--el-color-primary);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .conversation-item__meta {
    justify-content: space-between;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  .chat-header {
    justify-content: space-between;
  }

  .chat-header__status {
    margin-left: 10px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  :deep(.chat-panel__body) {
    display: flex;
    flex-direction: column;
  }

  .chat-content {
    flex: 1;
    min-height: 0;
  }

  .message-list {
    height: 100%;
    padding: 20px;
    overflow-y: auto;
    background: var(--el-fill-color-lighter);
  }

  .message-row {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    margin-bottom: 18px;
  }

  .message-row.is-admin {
    align-items: flex-end;
  }

  .message-row.is-system {
    align-items: center;
    margin: 12px 0;
  }

  .message-sender {
    margin-bottom: 5px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  .message-bubble {
    max-width: 76%;
    padding: 10px 13px;
    line-height: 1.6;
    white-space: pre-wrap;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-light);
    border-radius: 10px;
  }

  .message-image {
    width: 220px;
    max-width: 76%;
    min-height: 120px;
    overflow: hidden;
    background: var(--el-fill-color);
    border-radius: 10px;
  }

  .message-image__error {
    display: grid;
    min-height: 120px;
    color: var(--el-text-color-secondary);
    place-items: center;
  }

  .message-card {
    display: flex;
    width: min(360px, 76%);
    gap: 12px;
    padding: 12px;
    color: inherit;
    text-align: left;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-light);
    border-radius: 10px;
    cursor: pointer;
  }

  .message-card img {
    width: 72px;
    height: 72px;
    object-fit: cover;
    border-radius: 8px;
  }

  .message-card span {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
    justify-content: center;
    gap: 6px;
  }

  .message-card small {
    overflow: hidden;
    color: var(--el-text-color-secondary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .is-admin .message-bubble {
    color: #fff;
    background: var(--el-color-primary);
    border-color: var(--el-color-primary);
  }

  .system-message {
    padding: 4px 10px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    background: var(--el-fill-color);
    border-radius: 12px;
  }

  .composer {
    padding: 14px;
    border-top: 1px solid var(--el-border-color-lighter);
  }

  .composer__footer {
    margin-top: 10px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  .composer__tools,
  .product-search,
  .product-cell {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .image-input {
    display: none;
  }

  .product-search {
    margin-bottom: 12px;
  }

  .product-cell img {
    width: 48px;
    height: 48px;
    object-fit: cover;
    border-radius: 6px;
  }

  .context-panel :deep(.context-body) {
    padding: 16px;
    overflow-y: auto;
  }

  .context-section-title {
    margin: 22px 0 10px;
    font-weight: 600;
  }

  .linked-orders {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .linked-order {
    display: flex;
    flex-direction: column;
    gap: 5px;
    padding: 12px;
    color: inherit;
    text-align: left;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    cursor: pointer;
  }

  .linked-order span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  .dialog-select {
    width: 100%;
  }

  .transfer-request-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .transfer-request {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 14px;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .transfer-request p {
    margin: 6px 0;
    color: var(--el-text-color-regular);
  }

  .transfer-request small {
    color: var(--el-text-color-secondary);
  }

  .transfer-request__actions {
    display: flex;
    flex-shrink: 0;
    gap: 8px;
  }

  @media (max-width: 1280px) {
    .workspace {
      grid-template-columns: 280px 1fr;
    }

    .context-panel {
      display: none;
    }
  }
</style>
