<template>
  <div class="customer-service-page art-full-height">
    <div class="workspace">
      <ElCard class="conversation-panel" shadow="never" body-class="panel-body">
        <template #header>
          <div class="conversation-heading">
            <div class="queue-title">
              <strong>待接入 {{ waitingCount }}</strong>
              <button
                type="button"
                :class="{ active: activeStatus === 'ACTIVE' }"
                @click="setStatus('ACTIVE')"
              >
                已接入 {{ activeCount }}
              </button>
              <ElBadge :value="pendingTransfers.length" :hidden="pendingTransfers.length === 0">
                <button type="button" class="transfer-entry" @click="pendingDialogVisible = true">
                  转接
                </button>
              </ElBadge>
            </div>
            <button type="button" class="icon-button" title="刷新" @click="refreshAll">
              <RefreshCw :size="16" />
            </button>
          </div>
          <div class="conversation-search">
            <Search :size="15" />
            <input v-model="conversationKeyword" placeholder="搜索用户或消息" />
          </div>
          <div class="filter-tabs">
            <button
              v-for="status in statusFilters"
              :key="status.value"
              type="button"
              :class="{ active: activeStatus === status.value }"
              @click="setStatus(status.value)"
            >
              {{ status.label }}
            </button>
          </div>
        </template>
        <div v-loading="listLoading" class="conversation-list">
          <button
            v-for="conversation in filteredConversations"
            :key="conversation.conversationId"
            type="button"
            class="conversation-item"
            :class="{ 'is-active': selectedConversationId === conversation.conversationId }"
            @click="selectConversation(conversation.conversationId)"
          >
            <span class="conversation-avatar">
              <UserRound :size="18" />
              <i v-if="conversation.adminUnreadCount > 0" />
            </span>
            <div class="conversation-summary">
              <div class="conversation-item__top">
                <strong>{{ conversation.userNickname || `用户 ${conversation.appUserId}` }}</strong>
                <time>{{ shortTime(conversation.lastMessageAt || conversation.createdAt) }}</time>
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
                <span>{{ statusConfig[conversation.status].label }}</span>
                <ElBadge
                  v-if="conversation.adminUnreadCount > 0"
                  :value="conversation.adminUnreadCount"
                  :max="99"
                />
              </div>
            </div>
          </button>
          <ElEmpty
            v-if="!listLoading && filteredConversations.length === 0"
            description="当前没有会话"
            :image-size="80"
          />
        </div>
      </ElCard>

      <ElCard class="chat-panel" shadow="never" body-class="chat-panel__body">
        <template #header>
          <div class="panel-header chat-header">
            <div class="chat-user">
              <span class="chat-user__avatar"><UserRound :size="20" /></span>
              <div>
                <strong>{{ currentDetail?.userNickname || '请选择一个会话' }}</strong>
                <span v-if="currentDetail" class="chat-header__status">
                  {{ statusConfig[currentDetail.status].label }}
                  <template v-if="currentDetail.assignedAdminDisplayName">
                    · {{ currentDetail.assignedAdminDisplayName }}
                  </template>
                </span>
              </div>
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

        <div class="chat-content">
          <div v-if="detailLoading && !currentDetail" class="chat-content__loading">
            <LoaderCircle :size="22" />
            <span>正在同步会话…</span>
          </div>
          <div v-if="currentDetail" ref="messageListRef" class="message-list">
            <div
              v-for="message in currentDetail.messages"
              :key="message.messageId"
              class="message-row"
              :class="`is-${message.senderType.toLowerCase()}`"
            >
              <div v-if="message.senderType !== 'SYSTEM'" class="message-sender">
                <span class="message-avatar">
                  <img v-if="message.senderAvatar" :src="message.senderAvatar" alt="" />
                  <UserRound v-else :size="13" />
                </span>
                {{ message.senderName }} · {{ formatDateTime(message.createdAt) }}
              </div>
              <div v-if="message.senderType === 'SYSTEM'" class="system-message">
                {{ message.content }}
              </div>
              <template v-else-if="message.messageType === 'IMAGE'">
                <div
                  :ref="(target) => registerImageTarget(message.messageId, target)"
                  class="message-image-target"
                  role="button"
                  tabindex="0"
                  aria-label="查看原图"
                  @click="handleImagePreview(message)"
                  @keydown.enter.prevent="handleImagePreview(message)"
                  @keydown.space.prevent="handleImagePreview(message)"
                >
                  <ElImage
                    v-if="imageUrls[message.messageId]"
                    class="message-image"
                    :class="{ 'is-uploading': isLocalImageUploading(message) }"
                    :src="imageUrls[message.messageId]"
                    fit="cover"
                    @load="handleImageLoad(message)"
                    @error="handleImageError(message)"
                  >
                    <template #error><div class="message-image__error">图片加载失败</div></template>
                  </ElImage>
                  <div v-else class="message-image message-image__status">
                    {{ imageLoadStates[message.messageId] === 'error' ? '图片加载失败' : '' }}
                  </div>
                </div>
              </template>
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
              <div v-else class="message-delivery">
                <button
                  v-if="isLocalTextSendFailed(message)"
                  type="button"
                  class="message-send-error"
                  title="发送失败，点击重试"
                  aria-label="发送失败，点击重试"
                  @click="retryTextMessage(message)"
                >
                  <CircleAlert :size="17" />
                </button>
                <div class="message-bubble">{{ message.content }}</div>
              </div>
            </div>
            <ElEmpty
              v-if="currentDetail.messages.length === 0"
              description="暂无消息，认领后即可开始回复"
              :image-size="72"
            />
          </div>
          <ElEmpty v-else-if="!detailLoading" description="从左侧选择一个会话" />
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
                accept="image/jpeg,image/png,image/webp,image/gif,image/svg+xml,.svg"
                @change="handleImageSelected"
              />
              <ElButton
                v-auth="'customer-service:message:send'"
                :disabled="!canSend || uploadingImage"
                @click="openImagePicker"
                ><ImageIcon :size="15" />图片</ElButton
              >
              <ElButton
                v-auth="'customer-service:order:link'"
                :disabled="!canSend"
                @click="openOrderDialog"
                ><ShoppingBag :size="15" />订单</ElButton
              >
              <ElButton
                v-auth="'customer-service:product:send'"
                :disabled="!canSend"
                @click="openProductDialog"
                ><PackageSearch :size="15" />商品</ElButton
              >
              <span>Ctrl / ⌘ + Enter 发送</span>
            </div>
            <ElButton
              v-auth="'customer-service:message:send'"
              type="primary"
              :disabled="!canSend || !messageDraft.trim()"
              @click="handleSend"
              ><Send :size="15" />发送</ElButton
            >
          </div>
        </div>
      </ElCard>

      <ElCard class="context-panel" shadow="never" body-class="context-body">
        <template #header>
          <div class="panel-header">
            <span>基础信息</span>
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
    <ElImageViewer
      v-if="previewImageUrl"
      :url-list="[previewImageUrl]"
      hide-on-click-modal
      teleported
      @close="closeImagePreview"
    />
  </div>
</template>

<script setup lang="ts">
  import {
    computed,
    nextTick,
    onBeforeUnmount,
    onMounted,
    ref,
    watch,
    type ComponentPublicInstance
  } from 'vue'
  import { ElLoading, ElMessageBox } from 'element-plus'
  import {
    CircleAlert,
    Image as ImageIcon,
    LoaderCircle,
    PackageSearch,
    RefreshCw,
    Search,
    Send,
    ShoppingBag,
    UserRound
  } from '@lucide/vue'
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
    fetchCustomerServiceThumbnail,
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
    uploadCustomerServiceImage
  } from '@/api/customer-service'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useUserStore } from '@/store/modules/user'
  import {
    customerServiceStatusFromQuery,
    type CustomerServiceStatusFilter
  } from '@/utils/business-route-query'
  import {
    realtimeClient,
    type RealtimeConnectionState,
    type RealtimeEvent
  } from '@/utils/realtime'
  import {
    cacheCustomerServiceImage,
    evictCustomerServiceImage,
    getCustomerServiceImageUrl
  } from '@/utils/customer-service-image-cache'
  import { isPersistedCustomerServiceMessageId } from '@/utils/customer-service-message'

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
  const conversationKeyword = ref('')
  const detailLoading = ref(false)
  const actionLoading = ref(false)
  const uploadingImage = ref(false)
  const messageDraft = ref('')
  const messageListRef = ref<HTMLElement | null>(null)
  const imageInputRef = ref<HTMLInputElement | null>(null)
  const imageUrls = ref<Record<number, string>>({})
  const imageLoadStates = ref<Record<number, 'loading' | 'error'>>({})
  const previewImageUrl = ref('')
  const previewImageLoading = ref(false)
  const imageRefreshAttempts = new Set<number>()
  const imageRefreshRequests = new Set<number>()
  const imageTargets = new Map<number, Element>()
  type LocalMessage = Api.CustomerService.Message & {
    localUploadState?: 'uploading'
    localSendState?: 'sending' | 'failed'
  }
  interface PendingImageUpload {
    conversationId: number
    file: File
    previewUrl: string
    message: LocalMessage
  }
  interface PendingTextSend {
    conversationId: number
    message: LocalMessage
  }
  const pendingImageUploads = new Map<number, PendingImageUpload>()
  const pendingTextSends = new Map<number, PendingTextSend>()
  const pendingTextRequests = new Set<number>()
  const detailCache = new Map<number, Api.CustomerService.ConversationDetail>()
  let nextPendingMessageId = -1
  let imageObserver: IntersectionObserver | null = null
  const transferDialogVisible = ref(false)
  const agents = ref<Api.CustomerService.Agent[]>([])
  const targetAgentId = ref<number | null>(null)
  const transferReasonCode = ref('')
  const transferReasonNote = ref('')
  const pendingDialogVisible = ref(false)
  const pendingTransfers = ref<Api.CustomerService.TransferRequest[]>([])
  const agentState = ref<Api.CustomerService.AgentState | null>(null)
  const orderDialogVisible = ref(false)
  const orderCandidatesLoading = ref(false)
  const orderCandidates = ref<Api.CustomerService.LinkedOrder[]>([])
  const productDialogVisible = ref(false)
  const productCandidatesLoading = ref(false)
  const productCandidates = ref<Api.CustomerService.LinkedProduct[]>([])
  const productKeyword = ref('')
  let detailRequestSequence = 0
  let listRequestSequence = 0
  let conversationListLoaded = false
  let pollTimer: ReturnType<typeof setInterval> | null = null
  let realtimeRefreshTimer: ReturnType<typeof setTimeout> | null = null
  let unsubscribeRealtime: (() => void) | null = null
  let unsubscribeRealtimeState: (() => void) | null = null
  let realtimeState: RealtimeConnectionState = 'DISCONNECTED'
  let initialLoadComplete = false
  let pageMounted = false
  let previewRequestSequence = 0
  let previewLoadingInstance: ReturnType<typeof ElLoading.service> | null = null
  const pendingRealtimeMessages = new Map<number, Set<number>>()
  const locallyHandledMessageIds = new Map<number, number>()
  const localMutationCounts = new Map<number, number>()

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
    REFUNDING: '退款处理中',
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
  const statusFilters: Array<{ label: string; value: CustomerServiceStatusFilter }> = [
    { label: '全部', value: 'ALL' },
    { label: '待接入', value: 'WAITING' },
    { label: '接待中', value: 'ACTIVE' },
    { label: '已结束', value: 'CLOSED' }
  ]
  const waitingCount = computed(
    () =>
      conversationPage.value.records.filter((conversation) => conversation.status === 'WAITING')
        .length
  )
  const activeCount = computed(
    () =>
      conversationPage.value.records.filter((conversation) => conversation.status === 'ACTIVE')
        .length
  )
  const filteredConversations = computed(() => {
    const keyword = conversationKeyword.value.trim().toLowerCase()
    return conversationPage.value.records.filter((conversation) => {
      if (activeStatus.value !== 'ALL' && conversation.status !== activeStatus.value) return false
      if (!keyword) return true
      return [conversation.userNickname, conversation.appUserId, conversation.lastMessagePreview]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(keyword))
    })
  })

  const formatDateTime = (value?: string | null) => {
    if (!value) return '-'
    return value.replace('T', ' ').slice(0, 19)
  }
  const shortTime = (value?: string | null) => {
    if (!value) return ''
    const date = new Date(value)
    const today = new Date()
    if (date.toDateString() === today.toDateString()) {
      return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    }
    return `${date.getMonth() + 1}/${date.getDate()}`
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

  const isLocalImageUploading = (message: Api.CustomerService.Message) =>
    (message as LocalMessage).localUploadState === 'uploading'

  const isLocalTextSendFailed = (message: Api.CustomerService.Message) =>
    message.messageType === 'TEXT' && (message as LocalMessage).localSendState === 'failed'

  const detachCurrentImageUrls = () => {
    imageObserver?.disconnect()
    imageTargets.clear()
    imageUrls.value = {}
    imageLoadStates.value = {}
    imageRefreshAttempts.clear()
    closeImagePreview()
  }

  const loadAuthenticatedImage = (message: Api.CustomerService.Message) => {
    if (!isPersistedCustomerServiceMessageId(message.messageId) || isLocalImageUploading(message))
      return
    if (imageRefreshRequests.has(message.messageId)) return

    imageRefreshRequests.add(message.messageId)
    imageLoadStates.value = {
      ...imageLoadStates.value,
      [message.messageId]: 'loading'
    }
    const request =
      message.image?.thumbnailStatus === 'READY'
        ? fetchCustomerServiceThumbnail(message.messageId)
        : fetchCustomerServiceImage(message.messageId)
    void request
      .then((blob) => {
        const url = cacheCustomerServiceImage(message.messageId, blob)
        if (
          !currentDetail.value?.messages.some(
            (currentMessage) => currentMessage.messageId === message.messageId
          )
        )
          return
        imageUrls.value = { ...imageUrls.value, [message.messageId]: url }
        const nextStates = { ...imageLoadStates.value }
        delete nextStates[message.messageId]
        imageLoadStates.value = nextStates
      })
      .catch(() => {
        if (
          !currentDetail.value?.messages.some(
            (currentMessage) => currentMessage.messageId === message.messageId
          )
        )
          return
        imageLoadStates.value = {
          ...imageLoadStates.value,
          [message.messageId]: 'error'
        }
      })
      .finally(() => {
        imageRefreshRequests.delete(message.messageId)
      })
  }

  const handleImageLoad = (message: Api.CustomerService.Message) => {
    imageRefreshAttempts.delete(message.messageId)
    const nextStates = { ...imageLoadStates.value }
    delete nextStates[message.messageId]
    imageLoadStates.value = nextStates
  }

  const handleImageError = (message: Api.CustomerService.Message) => {
    if (!isPersistedCustomerServiceMessageId(message.messageId)) return
    evictCustomerServiceImage(message.messageId)
    const nextUrls = { ...imageUrls.value }
    delete nextUrls[message.messageId]
    imageUrls.value = nextUrls
    if (imageRefreshAttempts.has(message.messageId)) {
      imageLoadStates.value = {
        ...imageLoadStates.value,
        [message.messageId]: 'error'
      }
      return
    }
    imageRefreshAttempts.add(message.messageId)
    loadAuthenticatedImage(message)
  }

  const releasePreviewImage = () => {
    if (previewImageUrl.value.startsWith('blob:')) URL.revokeObjectURL(previewImageUrl.value)
    previewImageUrl.value = ''
  }

  const closeImagePreview = () => {
    previewRequestSequence += 1
    previewImageLoading.value = false
    previewLoadingInstance?.close()
    previewLoadingInstance = null
    releasePreviewImage()
  }

  const handleImagePreview = async (message: Api.CustomerService.Message) => {
    if (
      previewImageLoading.value ||
      isLocalImageUploading(message) ||
      !isPersistedCustomerServiceMessageId(message.messageId)
    )
      return
    const requestId = ++previewRequestSequence
    previewImageLoading.value = true
    previewLoadingInstance = ElLoading.service({
      fullscreen: true,
      lock: true,
      text: '正在加载原图…',
      background: 'rgb(15 23 42 / 38%)'
    })
    try {
      const original = await fetchCustomerServiceImage(message.messageId)
      if (
        requestId !== previewRequestSequence ||
        !currentDetail.value?.messages.some(
          (currentMessage) => currentMessage.messageId === message.messageId
        )
      )
        return
      releasePreviewImage()
      previewImageUrl.value = URL.createObjectURL(original)
    } catch {
      if (requestId === previewRequestSequence) ElMessage.error('原图加载失败，请重试')
    } finally {
      if (requestId === previewRequestSequence) {
        previewImageLoading.value = false
        previewLoadingInstance?.close()
        previewLoadingInstance = null
      }
    }
  }

  const ensureImageLoaded = (messageId: number) => {
    if (!isPersistedCustomerServiceMessageId(messageId)) return
    const message = currentDetail.value?.messages.find(
      (candidate) => candidate.messageId === messageId
    )
    if (!message || message.messageType !== 'IMAGE') return
    const cachedUrl = getCustomerServiceImageUrl(messageId)
    if (cachedUrl) {
      imageUrls.value = { ...imageUrls.value, [messageId]: cachedUrl }
      return
    }
    if (imageLoadStates.value[messageId] === 'loading') return
    loadAuthenticatedImage(message)
  }

  const observeImageTargets = () => {
    imageObserver?.disconnect()
    imageObserver = null
    if (typeof IntersectionObserver === 'undefined') {
      imageTargets.forEach((_target, messageId) => ensureImageLoaded(messageId))
      return
    }
    imageObserver = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return
          const messageId = Number((entry.target as HTMLElement).dataset.messageId)
          if (Number.isSafeInteger(messageId)) ensureImageLoaded(messageId)
        })
      },
      {
        root: messageListRef.value,
        rootMargin: '600px 0px'
      }
    )
    imageTargets.forEach((target) => imageObserver?.observe(target))
  }

  const registerImageTarget = (
    messageId: number,
    target: Element | ComponentPublicInstance | null
  ) => {
    const previous = imageTargets.get(messageId)
    if (previous) imageObserver?.unobserve(previous)
    const element =
      target instanceof Element
        ? target
        : target && '$el' in target && target.$el instanceof Element
          ? target.$el
          : null
    if (!element) {
      imageTargets.delete(messageId)
      return
    }
    ;(element as HTMLElement).dataset.messageId = String(messageId)
    imageTargets.set(messageId, element)
    if (isPersistedCustomerServiceMessageId(messageId)) imageObserver?.observe(element)
  }

  const detailWithPendingMessages = (detail: Api.CustomerService.ConversationDetail) => {
    const persistedClientMessageIds = new Set(
      detail.messages
        .map((message) => message.clientMessageId)
        .filter((clientMessageId): clientMessageId is string => Boolean(clientMessageId))
    )
    pendingTextSends.forEach((pending, messageId) => {
      if (
        pending.message.clientMessageId &&
        persistedClientMessageIds.has(pending.message.clientMessageId)
      ) {
        pendingTextSends.delete(messageId)
      }
    })
    const pendingMessages = [
      ...Array.from(pendingTextSends.values()),
      ...Array.from(pendingImageUploads.values())
    ]
      .filter((pending) => pending.conversationId === detail.conversationId)
      .map((pending) => pending.message)
      .sort((left, right) => right.messageId - left.messageId)
    return {
      ...detail,
      messages: [...detail.messages, ...pendingMessages]
    }
  }

  const syncImageUrls = (messages: Api.CustomerService.Message[]) => {
    const imageMessages = messages.filter((message) => message.messageType === 'IMAGE')
    const nextUrls: Record<number, string> = {}
    imageMessages.forEach((message) => {
      if (!isPersistedCustomerServiceMessageId(message.messageId)) return
      const cachedUrl = getCustomerServiceImageUrl(message.messageId)
      if (cachedUrl) nextUrls[message.messageId] = cachedUrl
    })
    pendingImageUploads.forEach((pending, messageId) => {
      if (pending.conversationId === selectedConversationId.value) {
        nextUrls[messageId] = pending.previewUrl
      }
    })
    imageUrls.value = nextUrls
  }

  const loadConversations = async (selectFirst = false) => {
    const requestId = ++listRequestSequence
    let firstConversationId: number | null = null
    if (!conversationListLoaded) listLoading.value = true
    try {
      const page = await fetchCustomerServiceConversations({
        current: 1,
        size: 50,
        status: undefined
      })
      if (requestId !== listRequestSequence) return
      conversationPage.value = page
      conversationListLoaded = true
      if (selectFirst && !selectedConversationId.value && filteredConversations.value.length) {
        firstConversationId = filteredConversations.value[0].conversationId
      }
    } finally {
      if (requestId === listRequestSequence) listLoading.value = false
    }
    if (firstConversationId) await selectConversation(firstConversationId)
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
    detailLoading.value = !currentDetail.value
    try {
      const detail = await fetchCustomerServiceConversation(conversationId)
      if (requestId !== detailRequestSequence || selectedConversationId.value !== conversationId)
        return
      detailCache.set(conversationId, detail)
      currentDetail.value = detailWithPendingMessages(detail)
      syncImageUrls(currentDetail.value.messages)
      await nextTick()
      if (messageListRef.value) messageListRef.value.scrollTop = messageListRef.value.scrollHeight
      observeImageTargets()
    } finally {
      if (requestId === detailRequestSequence) detailLoading.value = false
    }
  }

  const selectConversation = async (conversationId: number) => {
    const conversationChanged = selectedConversationId.value !== conversationId
    if (conversationChanged) detachCurrentImageUrls()
    selectedConversationId.value = conversationId
    const cachedDetail = detailCache.get(conversationId)
    if (conversationChanged) {
      currentDetail.value = cachedDetail ? detailWithPendingMessages(cachedDetail) : null
      if (currentDetail.value) {
        syncImageUrls(currentDetail.value.messages)
        await nextTick()
        observeImageTargets()
      }
    }
    await loadDetail(conversationId)
    const record = conversationPage.value.records.find(
      (conversation) => conversation.conversationId === conversationId
    )
    if (record) record.adminUnreadCount = 0
  }

  const refreshAll = async () => {
    const selectedId = selectedConversationId.value
    await Promise.all([
      loadConversations(),
      loadAgentState(),
      loadPendingTransfers(),
      selectedId ? loadDetail(selectedId) : Promise.resolve()
    ])
  }

  const handleStatusChange = async () => {
    detachCurrentImageUrls()
    selectedConversationId.value = null
    currentDetail.value = null
    await loadConversations(true)
  }

  const setStatus = (status: CustomerServiceStatusFilter) => {
    if (activeStatus.value === status) return
    activeStatus.value = status
    void router.replace({
      path: '/customer-service',
      query: { ...route.query, status: status === 'ALL' ? undefined : status }
    })
    void handleStatusChange()
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

  const handleSend = () => {
    const content = messageDraft.value.trim()
    if (!selectedConversationId.value || !content || !canSend.value) return
    const conversationId = selectedConversationId.value
    const pendingMessageId = nextPendingMessageId--
    const pendingMessage: LocalMessage = {
      messageId: pendingMessageId,
      conversationId,
      consultationNo: currentDetail.value?.consultationNo || 1,
      senderType: 'ADMIN',
      senderId: String(currentAdminId.value || ''),
      senderName: userStore.info.userName || '客服',
      senderAvatar: userStore.info.avatar || '',
      messageType: 'TEXT',
      content,
      resourceId: null,
      order: null,
      product: null,
      image: null,
      clientMessageId: createClientMessageId(),
      createdAt: new Date().toISOString(),
      localSendState: 'sending'
    }
    pendingTextSends.set(pendingMessageId, {
      conversationId,
      message: pendingMessage
    })
    messageDraft.value = ''
    updateConversationSummary(pendingMessage)
    if (currentDetail.value?.conversationId === conversationId) {
      currentDetail.value.messages = [...currentDetail.value.messages, pendingMessage]
      void nextTick(() => {
        if (messageListRef.value) messageListRef.value.scrollTop = messageListRef.value.scrollHeight
      })
    }
    void deliverPendingText(pendingMessageId)
  }

  const deliverPendingText = async (pendingMessageId: number) => {
    const pending = pendingTextSends.get(pendingMessageId)
    if (!pending || pendingTextRequests.has(pendingMessageId)) return
    pendingTextRequests.add(pendingMessageId)
    pending.message.localSendState = 'sending'
    syncPendingTextMessage(pending.message)
    beginLocalMutation(pending.conversationId)
    try {
      const message = await sendCustomerServiceMessage(pending.conversationId, {
        content: pending.message.content,
        clientMessageId: pending.message.clientMessageId || createClientMessageId()
      })
      pendingTextSends.delete(pendingMessageId)
      applyLocallySentMessage(pending.conversationId, message, pendingMessageId)
    } catch {
      pending.message.localSendState = 'failed'
      syncPendingTextMessage(pending.message)
    } finally {
      pendingTextRequests.delete(pendingMessageId)
      endLocalMutation(pending.conversationId)
    }
  }

  const syncPendingTextMessage = (message: LocalMessage) => {
    if (currentDetail.value?.conversationId !== message.conversationId) return
    currentDetail.value.messages = currentDetail.value.messages.map((currentMessage) =>
      currentMessage.messageId === message.messageId ? { ...message } : currentMessage
    )
  }

  const retryTextMessage = (message: Api.CustomerService.Message) => {
    const pending = pendingTextSends.get(message.messageId)
    if (!pending || pending.message.localSendState !== 'failed') return
    void deliverPendingText(message.messageId)
  }

  const openImagePicker = () => imageInputRef.value?.click()

  const handleImageSelected = async (event: Event) => {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    input.value = ''
    if (!selectedConversationId.value || !file || !canSend.value) return
    const conversationId = selectedConversationId.value
    const pendingMessageId = nextPendingMessageId--
    const previewUrl = URL.createObjectURL(file)
    const pendingMessage: LocalMessage = {
      messageId: pendingMessageId,
      conversationId,
      consultationNo: currentDetail.value?.consultationNo || 1,
      senderType: 'ADMIN',
      senderId: String(currentAdminId.value || ''),
      senderName: userStore.info.userName || '客服',
      senderAvatar: userStore.info.avatar || '',
      messageType: 'IMAGE',
      content: '[图片]',
      resourceId: null,
      order: null,
      product: null,
      image: {
        originalFilename: file.name,
        contentType: file.type || 'image/jpeg',
        width: null,
        height: null,
        accessMode: 'AUTHENTICATED_BLOB',
        thumbnailStatus: 'NONE'
      },
      clientMessageId: null,
      createdAt: new Date().toISOString(),
      localUploadState: 'uploading'
    }
    pendingImageUploads.set(pendingMessageId, {
      conversationId,
      file,
      previewUrl,
      message: pendingMessage
    })
    if (currentDetail.value?.conversationId === conversationId) {
      currentDetail.value.messages = [...currentDetail.value.messages, pendingMessage]
      imageUrls.value = { ...imageUrls.value, [pendingMessageId]: previewUrl }
      await nextTick()
      if (messageListRef.value) messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    }
    beginLocalMutation(conversationId)
    uploadingImage.value = true
    try {
      const message = await uploadCustomerServiceImage(conversationId, file)
      const localImageUrl = cacheCustomerServiceImage(message.messageId, file)
      removePendingImage(pendingMessageId)
      imageUrls.value = { ...imageUrls.value, [message.messageId]: localImageUrl }
      applyLocallySentMessage(conversationId, message)
    } catch (error) {
      removePendingImage(pendingMessageId)
      throw error
    } finally {
      uploadingImage.value = false
      endLocalMutation(conversationId)
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

  const removePendingImage = (messageId: number) => {
    const pending = pendingImageUploads.get(messageId)
    if (!pending) return
    pendingImageUploads.delete(messageId)
    URL.revokeObjectURL(pending.previewUrl)
    if (currentDetail.value?.conversationId === pending.conversationId) {
      currentDetail.value.messages = currentDetail.value.messages.filter(
        (message) => message.messageId !== messageId
      )
    }
    const nextUrls = { ...imageUrls.value }
    delete nextUrls[messageId]
    imageUrls.value = nextUrls
  }

  const updateConversationSummary = (message: Api.CustomerService.Message) => {
    const index = conversationPage.value.records.findIndex(
      (conversation) => conversation.conversationId === message.conversationId
    )
    if (index < 0) return
    const record = conversationPage.value.records[index]
    const updated = {
      ...record,
      lastMessagePreview: message.messageType === 'IMAGE' ? '[图片]' : message.content,
      lastMessageAt: message.createdAt,
      updatedAt: message.createdAt
    }
    conversationPage.value.records = [
      updated,
      ...conversationPage.value.records.filter(
        (conversation) => conversation.conversationId !== message.conversationId
      )
    ]
  }

  const pruneHandledMessages = () => {
    const now = Date.now()
    locallyHandledMessageIds.forEach((expiresAt, messageId) => {
      if (expiresAt <= now) locallyHandledMessageIds.delete(messageId)
    })
  }

  const markMessageHandledLocally = (messageId: number) => {
    if (messageId > 0) locallyHandledMessageIds.set(messageId, Date.now() + 15_000)
  }

  const applyLocallySentMessage = (
    conversationId: number,
    message: Api.CustomerService.Message,
    pendingMessageId?: number
  ) => {
    markMessageHandledLocally(message.messageId)
    updateConversationSummary(message)
    const cachedDetail = detailCache.get(conversationId)
    if (cachedDetail) {
      detailCache.set(conversationId, {
        ...cachedDetail,
        messages: [
          ...cachedDetail.messages.filter(
            (currentMessage) => currentMessage.messageId !== message.messageId
          ),
          message
        ]
      })
    }
    if (currentDetail.value?.conversationId !== conversationId) return
    const messages = currentDetail.value.messages.filter(
      (currentMessage) =>
        currentMessage.messageId !== message.messageId &&
        currentMessage.messageId !== pendingMessageId
    )
    currentDetail.value.messages = [...messages, message]
    void nextTick(() => {
      if (messageListRef.value) messageListRef.value.scrollTop = messageListRef.value.scrollHeight
      observeImageTargets()
    })
  }

  const beginLocalMutation = (conversationId: number) => {
    localMutationCounts.set(conversationId, (localMutationCounts.get(conversationId) || 0) + 1)
  }

  const endLocalMutation = (conversationId: number) => {
    const nextCount = (localMutationCounts.get(conversationId) || 1) - 1
    if (nextCount > 0) {
      localMutationCounts.set(conversationId, nextCount)
      return
    }
    localMutationCounts.delete(conversationId)
    scheduleRealtimeRefresh(0)
  }

  const stopFallbackPolling = () => {
    if (pollTimer) clearInterval(pollTimer)
    pollTimer = null
  }

  const startFallbackPolling = () => {
    if (pollTimer || !initialLoadComplete) return
    pollTimer = setInterval(() => {
      if (!document.hidden) void refreshAll()
    }, 15000)
  }

  const handleRealtimeState = (state: RealtimeConnectionState) => {
    if (state === realtimeState) return
    realtimeState = state
    if (state === 'CONNECTED') {
      stopFallbackPolling()
      if (initialLoadComplete) void refreshAll()
      return
    }
    startFallbackPolling()
  }

  const handleVisibilityChange = () => {
    if (!document.hidden && initialLoadComplete) void refreshAll()
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
    const conversationId = Number(event.data.conversationId || 0)
    if (Number.isSafeInteger(conversationId) && conversationId > 0) {
      const messageId = Number(event.data.messageId || 0)
      const messageIds = pendingRealtimeMessages.get(conversationId) || new Set<number>()
      messageIds.add(Number.isSafeInteger(messageId) && messageId > 0 ? messageId : 0)
      pendingRealtimeMessages.set(conversationId, messageIds)
    }
    scheduleRealtimeRefresh(250)
  }

  const scheduleRealtimeRefresh = (delay: number) => {
    if (realtimeRefreshTimer || !pendingRealtimeMessages.size) return
    realtimeRefreshTimer = setTimeout(() => {
      realtimeRefreshTimer = null
      pruneHandledMessages()
      const refreshConversationIds = new Set<number>()
      let hasDeferredMutation = false
      pendingRealtimeMessages.forEach((messageIds, conversationId) => {
        if (localMutationCounts.has(conversationId)) {
          hasDeferredMutation = true
          return
        }
        pendingRealtimeMessages.delete(conversationId)
        const needsRefresh = Array.from(messageIds).some(
          (messageId) => messageId === 0 || !locallyHandledMessageIds.has(messageId)
        )
        if (needsRefresh) refreshConversationIds.add(conversationId)
      })
      if (hasDeferredMutation) scheduleRealtimeRefresh(100)
      if (!refreshConversationIds.size) return
      const selectedId = selectedConversationId.value
      void Promise.all([
        loadConversations(),
        selectedId && refreshConversationIds.has(selectedId)
          ? loadDetail(selectedId)
          : Promise.resolve()
      ])
    }, delay)
  }

  onMounted(async () => {
    pageMounted = true
    unsubscribeRealtimeState = realtimeClient.subscribeConnectionState(handleRealtimeState)
    unsubscribeRealtime = realtimeClient.subscribe(handleRealtimeEvent)
    await Promise.all([loadConversations(true), loadAgentState(), loadPendingTransfers()])
    if (!pageMounted) return
    initialLoadComplete = true
    if (realtimeState !== 'CONNECTED') startFallbackPolling()
    document.addEventListener('visibilitychange', handleVisibilityChange)
  })

  onBeforeUnmount(() => {
    detailRequestSequence += 1
    pageMounted = false
    initialLoadComplete = false
    unsubscribeRealtimeState?.()
    unsubscribeRealtime?.()
    stopFallbackPolling()
    if (realtimeRefreshTimer) clearTimeout(realtimeRefreshTimer)
    pendingRealtimeMessages.clear()
    locallyHandledMessageIds.clear()
    localMutationCounts.clear()
    pendingImageUploads.forEach((pending) => URL.revokeObjectURL(pending.previewUrl))
    pendingImageUploads.clear()
    pendingTextSends.clear()
    pendingTextRequests.clear()
    detailCache.clear()
    document.removeEventListener('visibilitychange', handleVisibilityChange)
    imageObserver?.disconnect()
    imageObserver = null
    imageTargets.clear()
    detachCurrentImageUrls()
  })
</script>

<style scoped lang="scss">
  .customer-service-page {
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
    gap: 14px;
    height: 100vh;
    min-height: 640px;
    padding: 16px;
    color: #0f172a;
    background: #f4f7fb;
  }

  :deep(.toolbar-card),
  :deep(.conversation-panel),
  :deep(.chat-panel),
  :deep(.context-panel) {
    overflow: hidden;
    border: 1px solid #e4e9f0;
    border-radius: 16px;
    box-shadow: 0 1px 3px rgb(15 23 42 / 4%);
  }

  :deep(.toolbar-card .el-card__body) {
    padding: 14px 17px;
  }

  .toolbar {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
  }

  .toolbar__title {
    display: flex;
    gap: 9px;
    align-items: center;
    font-size: 18px;
    font-weight: 700;
    letter-spacing: -0.02em;
  }

  .toolbar__title svg {
    color: #2563eb;
  }

  .toolbar__subtitle {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .toolbar__actions,
  .agent-state,
  .panel-header,
  .chat-header__actions,
  .composer__footer {
    display: flex;
    gap: 10px;
    align-items: center;
  }

  .agent-state {
    padding-right: 10px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
    border-right: 1px solid var(--el-border-color-lighter);
  }

  .panel-header,
  .composer__footer {
    justify-content: space-between;
  }

  .workspace {
    display: grid;
    flex: 1;
    grid-template-columns: minmax(270px, 320px) minmax(520px, 1fr) minmax(260px, 320px);
    gap: 14px;
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
    padding: 15px 16px;
    color: inherit;
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-bottom: 1px solid #edf0f4;
  }

  .conversation-item:hover,
  .conversation-item.is-active {
    background: #f2f7ff;
  }

  .conversation-item.is-active {
    box-shadow: inset 3px 0 #2563eb;
  }

  .conversation-item__top,
  .conversation-item__meta {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .conversation-item__top strong {
    flex: 1;
  }

  .conversation-item__message {
    margin: 8px 0;
    overflow: hidden;
    font-size: 13px;
    color: var(--el-text-color-regular);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .conversation-item__context {
    margin-bottom: 8px;
    overflow: hidden;
    font-size: 12px;
    color: var(--el-color-primary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .conversation-item__meta {
    justify-content: space-between;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .chat-header {
    justify-content: space-between;
  }

  .panel-header > span:first-child {
    display: flex;
    gap: 7px;
    align-items: center;
    font-weight: 650;
  }

  :deep(.el-card__header) {
    box-sizing: border-box;
    min-height: 58px;
    padding: 15px 17px;
    background: #fff;
    border-bottom-color: #edf0f4;
  }

  .chat-header__status {
    margin-left: 10px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  :deep(.chat-panel__body) {
    display: flex;
    flex-direction: column;
  }

  .chat-content {
    flex: 1;
    min-height: 0;
  }

  .chat-content__loading {
    display: flex;
    flex-direction: column;
    gap: 10px;
    align-items: center;
    justify-content: center;
    height: 100%;
    font-size: 13px;
    color: var(--el-text-color-secondary);
    background: #f8fafc;
  }

  .chat-content__loading svg {
    color: var(--el-color-primary);
    animation: chat-loading-spin 900ms linear infinite;
  }

  @keyframes chat-loading-spin {
    to {
      transform: rotate(360deg);
    }
  }

  .message-list {
    height: 100%;
    padding: 20px;
    overflow-y: auto;
    background: radial-gradient(circle at 10% 5%, rgb(219 234 254 / 36%), transparent 25%), #f8fafc;
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
    display: flex;
    gap: 6px;
    align-items: center;
    margin-bottom: 5px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .message-avatar {
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
    overflow: hidden;
    color: #64748b;
    background: #e2e8f0;
    border-radius: 6px;
  }

  .message-avatar img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .message-bubble {
    max-width: 76%;
    padding: 10px 13px;
    line-height: 1.6;
    white-space: pre-wrap;
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 4px 14px 14px;
    box-shadow: 0 1px 2px rgb(15 23 42 / 4%);
  }

  .message-delivery {
    display: flex;
    gap: 7px;
    align-items: center;
    max-width: 76%;
  }

  .message-delivery .message-bubble {
    max-width: 100%;
  }

  .message-send-error {
    display: grid;
    flex: 0 0 auto;
    place-items: center;
    width: 24px;
    height: 24px;
    padding: 0;
    color: #ef4444;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-radius: 50%;
  }

  .message-send-error:hover,
  .message-send-error:focus-visible {
    color: #dc2626;
    background: #fee2e2;
    outline: none;
  }

  .message-image {
    width: 220px;
    max-width: 76%;
    min-height: 120px;
    overflow: hidden;
    background: var(--el-fill-color);
    border-radius: 10px;
    transition:
      filter 220ms ease,
      opacity 220ms ease;
  }

  .message-image.is-uploading {
    filter: grayscale(1);
    opacity: 0.48;
  }

  .message-image-target {
    width: fit-content;
    max-width: 100%;
    cursor: pointer;
    outline: none;
  }

  .message-image-target:focus-visible {
    border-radius: 10px;
    box-shadow: 0 0 0 2px var(--el-color-primary-light-5);
  }

  .message-image__error {
    display: grid;
    place-items: center;
    min-height: 120px;
    color: var(--el-text-color-secondary);
  }

  .message-image__status {
    display: grid;
    place-items: center;
    color: var(--el-text-color-secondary);
    background: #d8dce2;
  }

  .message-card {
    display: flex;
    gap: 12px;
    width: min(360px, 76%);
    padding: 12px;
    color: inherit;
    text-align: left;
    cursor: pointer;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-light);
    border-radius: 10px;
  }

  .message-card img {
    width: 72px;
    height: 72px;
    object-fit: cover;
    border-radius: 8px;
  }

  .message-card span {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: 6px;
    justify-content: center;
    min-width: 0;
  }

  .message-card small {
    overflow: hidden;
    color: var(--el-text-color-secondary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .is-admin .message-bubble {
    color: #fff;
    background: #2563eb;
    border-color: #2563eb;
    border-radius: 14px 4px 14px 14px;
    box-shadow: 0 4px 12px rgb(37 99 235 / 16%);
  }

  .system-message {
    padding: 4px 10px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    background: var(--el-fill-color);
    border-radius: 12px;
  }

  .composer {
    padding: 14px 16px;
    background: #fff;
    border-top: 1px solid #e8edf3;
  }

  .composer :deep(.el-textarea__inner) {
    border: 0;
    box-shadow: none;
  }

  .composer :deep(.el-textarea) {
    border: 1px solid #e2e8f0;
    border-radius: 11px;
  }

  .composer :deep(.el-textarea:focus-within) {
    border-color: #93c5fd;
    box-shadow: 0 0 0 3px rgb(37 99 235 / 8%);
  }

  .composer__footer {
    margin-top: 10px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .composer__tools,
  .product-search,
  .product-cell {
    display: flex;
    gap: 8px;
    align-items: center;
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
    cursor: pointer;
    background: #f8fafc;
    border: 1px solid #e5eaf1;
    border-radius: 10px;
  }

  .linked-order span {
    font-size: 12px;
    color: var(--el-text-color-secondary);
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
    gap: 16px;
    align-items: center;
    justify-content: space-between;
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

  /* 独立客服工作台：保持参考界面的轻量分栏与低装饰密度。 */
  /* stylelint-disable no-duplicate-selectors -- 此处集中覆盖旧工作台的视觉层，不改变业务结构。 */
  .customer-service-page {
    gap: 0;
    height: 100%;
    min-height: 640px;
    padding: 0;
    background: #f3f3f3;
  }

  .workspace {
    grid-template-columns: minmax(310px, 360px) minmax(520px, 1fr) minmax(310px, 360px);
    gap: 0;
    height: 100%;
  }

  :deep(.conversation-panel),
  :deep(.chat-panel),
  :deep(.context-panel) {
    border: 0;
    border-right: 1px solid #e4e4e4;
    border-radius: 0;
    box-shadow: none;
  }

  :deep(.context-panel) {
    border-right: 0;
  }

  :deep(.conversation-panel .el-card__header) {
    min-height: 150px;
    padding: 23px 20px 15px;
  }

  :deep(.chat-panel .el-card__header),
  :deep(.context-panel .el-card__header) {
    min-height: 70px;
    padding: 16px 22px;
  }

  :deep(.conversation-panel .panel-body) {
    height: calc(100% - 150px);
  }

  :deep(.chat-panel .chat-panel__body),
  :deep(.context-panel .context-body) {
    height: calc(100% - 70px);
  }

  .conversation-heading,
  .queue-title,
  .chat-user {
    display: flex;
    align-items: center;
  }

  .conversation-heading {
    justify-content: space-between;
  }

  .queue-title {
    gap: 17px;
  }

  .queue-title strong {
    font-size: 17px;
    font-weight: 650;
  }

  .queue-title button,
  .filter-tabs button,
  .icon-button {
    padding: 0;
    color: #777;
    cursor: pointer;
    background: transparent;
    border: 0;
  }

  .queue-title button.active {
    color: #0abb60;
  }

  .transfer-entry {
    font-size: 13px;
  }

  .icon-button {
    display: grid;
    place-items: center;
    width: 30px;
    height: 30px;
    border-radius: 6px;
  }

  .icon-button:hover {
    color: #09b95e;
    background: #f1f7f3;
  }

  .conversation-search {
    box-sizing: border-box;
    display: flex;
    gap: 8px;
    align-items: center;
    height: 38px;
    padding: 0 11px;
    margin-top: 19px;
    color: #999;
    background: #f6f6f6;
    border: 1px solid #ebebeb;
    border-radius: 5px;
  }

  .conversation-search input {
    flex: 1;
    min-width: 0;
    color: #333;
    background: transparent;
    border: 0;
    outline: none;
  }

  .filter-tabs {
    display: flex;
    gap: 18px;
    margin-top: 14px;
  }

  .filter-tabs button {
    font-size: 12px;
  }

  .filter-tabs button.active {
    font-weight: 650;
    color: #0bbf62;
  }

  .conversation-item {
    display: flex;
    gap: 12px;
    padding: 15px 18px;
    border-bottom-color: #ededed;
  }

  .conversation-item:hover {
    background: #f5f5f5;
  }

  .conversation-item.is-active {
    background: #e7e7e7;
    box-shadow: none;
  }

  .conversation-avatar,
  .chat-user__avatar {
    position: relative;
    display: grid;
    flex: 0 0 42px;
    place-items: center;
    width: 42px;
    height: 42px;
    color: #6f6f6f;
    background: #f0f0f0;
    border-radius: 4px;
  }

  .conversation-avatar i {
    position: absolute;
    top: -3px;
    right: -3px;
    width: 8px;
    height: 8px;
    background: #ff5a62;
    border: 2px solid #fff;
    border-radius: 50%;
  }

  .conversation-summary {
    flex: 1;
    min-width: 0;
  }

  .conversation-item__top time {
    font-size: 11px;
    color: #aaa;
  }

  .conversation-item__message {
    margin: 6px 0;
    color: #666;
  }

  .conversation-item__meta {
    justify-content: flex-end;
    min-height: 17px;
    color: #08b85d;
  }

  .conversation-item__context {
    margin-bottom: 6px;
    color: #7a7a7a;
  }

  .chat-user {
    gap: 11px;
  }

  .chat-user__avatar {
    flex-basis: 38px;
    width: 38px;
    height: 38px;
  }

  .chat-user > div {
    display: grid;
    gap: 5px;
  }

  .chat-header__status {
    margin-left: 0;
    font-size: 12px;
  }

  .message-list {
    padding: 26px 22px;
    background: #f3f3f3;
  }

  .message-bubble {
    padding: 10px 13px;
    background: #fff;
    border: 0;
    border-radius: 5px;
    box-shadow: none;
  }

  .is-admin .message-bubble {
    color: #126526;
    background: #8bea63;
    border: 0;
    border-radius: 5px;
    box-shadow: none;
  }

  .message-sender {
    color: #a0a0a0;
  }

  .system-message {
    color: #aaa;
    background: transparent;
  }

  .composer {
    box-sizing: border-box;
    min-height: 180px;
    padding: 12px 20px 16px;
    border-top-color: #dedede;
  }

  .composer :deep(.el-textarea) {
    border: 0;
    border-radius: 0;
  }

  .composer :deep(.el-textarea:focus-within) {
    border-color: transparent;
    box-shadow: none;
  }

  .composer :deep(.el-textarea__inner) {
    min-height: 88px !important;
    padding: 10px 0;
  }

  .composer__tools :deep(.el-button) {
    padding: 8px;
    border: 0;
  }

  .composer__footer > :deep(.el-button--primary) {
    background: #0bc666;
    border-color: #0bc666;
  }

  .context-panel :deep(.context-body) {
    padding: 22px;
  }

  .context-panel :deep(.el-descriptions__body),
  .context-panel :deep(.el-descriptions__table),
  .context-panel :deep(.el-descriptions__cell) {
    background: transparent;
    border: 0;
  }

  .context-panel :deep(.el-descriptions__label) {
    width: 84px;
    color: #999;
  }

  .linked-order {
    background: #fafafa;
    border-color: #ececec;
    border-radius: 6px;
  }
  /* stylelint-enable no-duplicate-selectors */

  @media (width <= 1280px) {
    .workspace {
      grid-template-columns: 280px 1fr;
    }

    .context-panel {
      display: none;
    }
  }

  @media (width <= 900px) {
    .customer-service-page {
      height: auto;
      min-height: 100vh;
      padding: 10px;
    }

    .toolbar {
      flex-direction: column;
      align-items: stretch;
    }

    .toolbar__actions {
      flex-wrap: wrap;
    }

    .workspace {
      grid-template-columns: 1fr;
      min-height: 1000px;
    }

    .conversation-panel {
      min-height: 360px;
    }
  }
</style>
