<template>
  <div class="customer-service-page art-full-height">
    <div class="workspace">
      <ElCard class="conversation-panel" shadow="never" body-class="panel-body">
        <div v-loading="listLoading" class="conversation-groups">
          <details class="conversation-group waiting-group" open>
            <summary>
              <span><i class="group-dot is-waiting" />待接入</span>
              <strong>{{ waitingCount }}</strong>
              <ChevronDown :size="15" />
            </summary>
            <div v-if="waitingConversations.length" class="waiting-grid">
              <article
                v-for="conversation in waitingConversations"
                :key="conversation.conversationId"
                class="waiting-user"
              >
                <ElTooltip
                  :content="conversation.lastMessagePreview || '用户正在等待接入'"
                  placement="bottom"
                  effect="light"
                  popper-class="waiting-message-tooltip"
                >
                  <span class="waiting-user__trigger">
                    <button
                      v-auth="'customer-service:conversation:claim'"
                      type="button"
                      class="waiting-user__avatar"
                      :disabled="!agentState?.canReceive || claimingConversationId !== null"
                      :aria-label="`接入 ${conversation.userNickname || `用户 ${conversation.appUserId}`}`"
                      @click="handleClaim(conversation.conversationId)"
                    >
                      <img v-if="conversation.userAvatar" :src="conversation.userAvatar" alt="" />
                      <UserRound v-else :size="18" />
                      <span class="waiting-user__claim">
                        {{
                          claimingConversationId === conversation.conversationId ? '接入中' : '接入'
                        }}
                      </span>
                      <i v-if="conversation.adminUnreadCount > 0" />
                    </button>
                  </span>
                </ElTooltip>
              </article>
            </div>
            <div v-else class="group-empty">暂时没有等待接入的用户</div>
          </details>

          <details class="conversation-group active-group" open>
            <summary>
              <span><i class="group-dot is-active" />接待中</span>
              <strong>{{ activeCount }}</strong>
              <ChevronDown :size="15" />
            </summary>
            <div v-if="activeConversations.length" class="active-conversation-list">
              <button
                v-for="conversation in activeConversations"
                :key="conversation.conversationId"
                type="button"
                class="conversation-item"
                :class="{ 'is-active': selectedConversationId === conversation.conversationId }"
                @click="selectConversation(conversation.conversationId)"
              >
                <span class="conversation-avatar">
                  <img v-if="conversation.userAvatar" :src="conversation.userAvatar" alt="" />
                  <UserRound v-else :size="18" />
                  <i v-if="conversation.adminUnreadCount > 0" />
                </span>
                <div class="conversation-summary">
                  <div class="conversation-item__top">
                    <strong>{{
                      conversation.userNickname || `用户 ${conversation.appUserId}`
                    }}</strong>
                    <time v-if="conversation.lastMessageAt">{{
                      shortTime(conversation.lastMessageAt)
                    }}</time>
                  </div>
                  <div class="conversation-item__message">
                    {{ conversation.lastMessagePreview || '已接入，等待回复' }}
                  </div>
                </div>
              </button>
            </div>
            <div v-else class="group-empty">当前没有接待中的会话</div>
          </details>

          <details class="conversation-group closed-group">
            <summary>
              <span><i class="group-dot is-closed" />最近结束</span>
              <strong>{{ closedCount }}</strong>
              <ChevronDown :size="15" />
            </summary>
            <div v-if="closedConversations.length" class="closed-conversation-list">
              <button
                v-for="conversation in closedConversations"
                :key="conversation.conversationId"
                type="button"
                class="conversation-item is-closed"
                :class="{ 'is-active': selectedConversationId === conversation.conversationId }"
                @click="selectConversation(conversation.conversationId)"
              >
                <span class="conversation-avatar">
                  <img v-if="conversation.userAvatar" :src="conversation.userAvatar" alt="" />
                  <UserRound v-else :size="18" />
                </span>
                <div class="conversation-summary">
                  <div class="conversation-item__top">
                    <strong>{{
                      conversation.userNickname || `用户 ${conversation.appUserId}`
                    }}</strong>
                    <time>{{ shortTime(conversation.closedAt || conversation.updatedAt) }}</time>
                  </div>
                  <div class="conversation-item__message">
                    {{ conversation.lastMessagePreview || '会话已结束' }}
                  </div>
                </div>
              </button>
            </div>
            <div v-else class="group-empty">暂无已结束会话</div>
          </details>
        </div>
      </ElCard>

      <ElCard
        v-if="selectedConversationId !== null"
        class="chat-panel"
        shadow="never"
        body-class="chat-panel__body"
      >
        <template #header>
          <div class="panel-header chat-header">
            <div class="chat-user">
              <span class="chat-user__avatar">
                <img v-if="currentDetail?.userAvatar" :src="currentDetail.userAvatar" alt="" />
                <UserRound v-else :size="20" />
              </span>
              <strong>{{ currentDetail?.userNickname || '正在加载会话' }}</strong>
            </div>
            <div v-if="currentDetail" class="chat-header__actions">
              <ElButton
                v-if="currentDetail.status === 'WAITING'"
                v-auth="'customer-service:conversation:claim'"
                :disabled="!agentState?.canReceive"
                :loading="actionLoading"
                @click="handleClaim()"
              >
                <UserCheck :size="14" />认领会话
              </ElButton>
              <template v-else-if="currentDetail.status === 'ACTIVE'">
                <ElButton
                  v-if="isCurrentAgent"
                  v-auth="'customer-service:conversation:transfer'"
                  :loading="actionLoading"
                  @click="openTransferDialog"
                >
                  <ArrowRightLeft :size="14" />转接
                </ElButton>
                <ElButton
                  v-else-if="hasAgentManage"
                  :loading="actionLoading"
                  @click="openTransferDialog"
                >
                  <ArrowRightLeft :size="14" />管理转接
                </ElButton>
                <ElButton
                  v-if="isCurrentAgent"
                  v-auth="'customer-service:conversation:transfer'"
                  :loading="actionLoading"
                  @click="handleRelease"
                >
                  <RotateCcw :size="14" />退回待接待
                </ElButton>
                <ElButton
                  v-else-if="hasAgentManage"
                  :loading="actionLoading"
                  @click="handleRelease"
                >
                  <RotateCcw :size="14" />退回待接待
                </ElButton>
                <ElButton
                  v-if="isCurrentAgent"
                  v-auth="'customer-service:conversation:close'"
                  :loading="actionLoading"
                  @click="handleClose"
                >
                  <CircleX :size="14" />结束会话
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
            <div v-if="hasEarlierMessages" class="message-history-loader">
              <ElButton link :loading="loadingEarlierMessages" @click="loadEarlierMessages">
                加载更早的消息
              </ElButton>
            </div>
            <template
              v-for="(message, messageIndex) in currentDetail.messages"
              :key="message.messageId"
            >
              <div
                v-if="shouldShowMessageTime(currentDetail.messages, messageIndex)"
                class="message-time"
              >
                {{ formatMessageTime(message.createdAt) }}
              </div>
              <div class="message-row" :class="`is-${message.senderType.toLowerCase()}`">
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
                      <template #error
                        ><div class="message-image__error">图片加载失败</div></template
                      >
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
            </template>
            <ElEmpty
              v-if="currentDetail.messages.length === 0"
              description="暂无消息，认领后即可开始回复"
              :image-size="72"
            />
          </div>
          <ElEmpty v-else-if="!detailLoading" description="暂无会话内容" />
        </div>

        <div class="chat-tools">
          <input
            ref="imageInputRef"
            class="image-input"
            type="file"
            accept="image/jpeg,image/png,image/webp,image/gif,.jpg,.jpeg,.png,.webp,.gif"
            @change="handleImageSelected"
          />
          <ElButton
            v-auth="'customer-service:message:send'"
            :disabled="!uploadingImage && !canSend"
            :type="uploadingImage ? 'danger' : 'default'"
            @click="uploadingImage ? cancelImageUpload() : openImagePicker()"
            ><ImageIcon :size="15" />{{
              uploadingImage ? `取消上传 ${imageUploadPercent}%` : '图片'
            }}</ElButton
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
            <span class="composer__shortcut">Ctrl / ⌘ + Enter 发送</span>
          </div>
        </div>
      </ElCard>

      <ElCard
        v-if="selectedConversationId !== null"
        class="context-panel"
        shadow="never"
        body-class="context-body"
      >
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
      <div v-else class="workspace-empty">暂无会话内容</div>
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
    type ComponentPublicInstance
  } from 'vue'
  import { ElLoading, ElMessageBox } from 'element-plus'
  import {
    ArrowRightLeft,
    ChevronDown,
    CircleAlert,
    CircleX,
    Image as ImageIcon,
    LoaderCircle,
    PackageSearch,
    RotateCcw,
    ShoppingBag,
    UserCheck,
    UserRound
  } from '@lucide/vue'
  import { useRouter } from 'vue-router'
  import {
    acceptCustomerServiceTransfer,
    claimCustomerServiceConversation,
    closeCustomerServiceConversation,
    fetchCustomerServiceAgents,
    fetchCustomerServiceAgentState,
    fetchCustomerServiceConversation,
    fetchCustomerServiceImageAccess,
    fetchCustomerServiceMessages,
    fetchCustomerServiceWorkspace,
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

  const router = useRouter()
  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const conversationPage = ref<Api.CustomerService.ConversationPage>({
    records: [],
    current: 1,
    size: 100,
    total: 0
  })
  const conversationTotals = ref<Record<'WAITING' | 'ACTIVE' | 'CLOSED', number>>({
    WAITING: 0,
    ACTIVE: 0,
    CLOSED: 0
  })
  const selectedConversationId = ref<number | null>(null)
  const currentDetail = ref<Api.CustomerService.ConversationDetail | null>(null)
  const listLoading = ref(false)
  const detailLoading = ref(false)
  const loadingEarlierMessages = ref(false)
  const hasEarlierMessages = ref(false)
  const actionLoading = ref(false)
  const claimingConversationId = ref<number | null>(null)
  const uploadingImage = ref(false)
  const imageUploadPercent = ref(0)
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
  let displayClockTimer: ReturnType<typeof setInterval> | null = null
  let realtimeRefreshTimer: ReturnType<typeof setTimeout> | null = null
  let unsubscribeRealtime: (() => void) | null = null
  let unsubscribeRealtimeState: (() => void) | null = null
  let realtimeState: RealtimeConnectionState = 'DISCONNECTED'
  let initialLoadComplete = false
  let pageMounted = false
  let previewRequestSequence = 0
  let previewLoadingInstance: ReturnType<typeof ElLoading.service> | null = null
  let imageUploadAbortController: AbortController | null = null
  const pendingRealtimeMessages = new Map<number, Set<number>>()
  const pendingRealtimeFullRefresh = new Set<number>()
  const locallyHandledMessageIds = new Map<number, number>()
  const localMutationCounts = new Map<number, number>()
  const displayNow = ref(Date.now())
  const MESSAGE_TIME_GAP_MS = 5 * 60 * 1000
  const ONE_DAY_MS = 24 * 60 * 60 * 1000
  const weekdayLabels = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']

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
  const waitingCount = computed(() => conversationTotals.value.WAITING)
  const activeCount = computed(() => conversationTotals.value.ACTIVE)
  const closedCount = computed(() => conversationTotals.value.CLOSED)
  const waitingConversations = computed(() =>
    conversationPage.value.records.filter((conversation) => conversation.status === 'WAITING')
  )
  const activeConversations = computed(() =>
    conversationPage.value.records.filter((conversation) => conversation.status === 'ACTIVE')
  )
  const closedConversations = computed(() =>
    conversationPage.value.records.filter((conversation) => conversation.status === 'CLOSED')
  )

  const formatDateTime = (value?: string | null) => {
    if (!value) return '-'
    return value.replace('T', ' ').slice(0, 19)
  }
  const calendarDayOrdinal = (date: Date) =>
    Math.floor(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / ONE_DAY_MS)
  const shortTime = (value?: string | null) => {
    if (!value) return ''
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return ''
    const now = new Date(displayNow.value)
    const elapsed = now.getTime() - date.getTime()
    if (Math.abs(elapsed) < 60 * 1000) return '刚刚'
    if (date.toDateString() === now.toDateString()) {
      return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
    }
    if (calendarDayOrdinal(now) - calendarDayOrdinal(date) === 1) return '昨天'
    if (date.getFullYear() !== now.getFullYear()) {
      return `${date.getFullYear()}/${date.getMonth() + 1}/${date.getDate()}`
    }
    return `${date.getMonth() + 1}/${date.getDate()}`
  }
  const formatPeriodClock = (date: Date) => {
    const period = date.getHours() < 12 ? '上午' : '下午'
    const hour = date.getHours() % 12 || 12
    const minute = String(date.getMinutes()).padStart(2, '0')
    return `${period} ${hour}:${minute}`
  }
  const formatMessageTime = (value?: string | null) => {
    if (!value) return ''
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return ''
    const now = new Date(displayNow.value)
    const dayDifference = calendarDayOrdinal(now) - calendarDayOrdinal(date)
    const clock = formatPeriodClock(date)

    if (dayDifference <= 0) return clock
    if (dayDifference === 1) return `昨天 ${clock}`
    if (dayDifference < 7) return `${weekdayLabels[date.getDay()]} ${clock}`
    return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${clock}`
  }
  const shouldShowMessageTime = (messages: Api.CustomerService.Message[], messageIndex: number) => {
    const currentTime = new Date(messages[messageIndex]?.createdAt || '').getTime()
    if (Number.isNaN(currentTime)) return false
    if (messageIndex === 0) return true
    const previousTime = new Date(messages[messageIndex - 1]?.createdAt || '').getTime()
    if (Number.isNaN(previousTime)) return false
    const currentDate = new Date(currentTime)
    const previousDate = new Date(previousTime)
    return (
      calendarDayOrdinal(currentDate) !== calendarDayOrdinal(previousDate) ||
      currentTime - previousTime >= MESSAGE_TIME_GAP_MS
    )
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
    const capacity = agent.maxActiveConversations ?? '∞'
    return `${agent.displayName}（${agent.username}）· ${status} ${agent.activeConversationCount}/${capacity}`
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

  const usableSignedUrl = (
    accessMode: Api.CustomerService.ImageMessage['accessMode'] | null | undefined,
    accessUrl: string | null | undefined,
    expiresAt: string | null | undefined
  ) => {
    if (accessMode !== 'SIGNED_URL' || !accessUrl) return ''
    if (!expiresAt) return accessUrl
    const expiration = Date.parse(expiresAt)
    return Number.isFinite(expiration) && expiration > Date.now() + 5_000 ? accessUrl : ''
  }

  const preferredMessageImageUrl = (image: Api.CustomerService.ImageMessage | null) => {
    if (!image) return ''
    if (image.thumbnailStatus === 'READY') {
      const thumbnailUrl = usableSignedUrl(
        image.thumbnailAccessMode,
        image.thumbnailAccessUrl,
        image.thumbnailAccessExpiresAt
      )
      if (thumbnailUrl) return thumbnailUrl
    }
    return usableSignedUrl(image.accessMode, image.accessUrl, image.accessExpiresAt)
  }

  const updateMessageImageAccess = (messageId: number, image: Api.CustomerService.ImageMessage) => {
    if (!currentDetail.value) return
    currentDetail.value.messages = currentDetail.value.messages.map((message) =>
      message.messageId === messageId ? { ...message, image } : message
    )
  }

  const loadAuthenticatedImage = (message: Api.CustomerService.Message, refreshAccess = false) => {
    if (!isPersistedCustomerServiceMessageId(message.messageId) || isLocalImageUploading(message))
      return
    if (imageRefreshRequests.has(message.messageId)) return

    const existingSignedUrl = preferredMessageImageUrl(message.image)
    if (existingSignedUrl && !refreshAccess) {
      imageUrls.value = { ...imageUrls.value, [message.messageId]: existingSignedUrl }
      return
    }

    imageRefreshRequests.add(message.messageId)
    imageLoadStates.value = {
      ...imageLoadStates.value,
      [message.messageId]: 'loading'
    }
    void (async () => {
      let image = message.image
      let accessRefreshed = false
      if (refreshAccess) {
        try {
          image = await fetchCustomerServiceImageAccess(message.messageId)
          accessRefreshed = true
          updateMessageImageAccess(message.messageId, image)
        } catch {
          // The authenticated blob endpoint remains a compatibility fallback.
        }
      }

      const signedUrl = !refreshAccess || accessRefreshed ? preferredMessageImageUrl(image) : ''
      if (signedUrl) return signedUrl

      const blob =
        image?.thumbnailStatus === 'READY'
          ? await fetchCustomerServiceThumbnail(message.messageId)
          : await fetchCustomerServiceImage(message.messageId)
      return cacheCustomerServiceImage(message.messageId, blob)
    })()
      .then((url) => {
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
    loadAuthenticatedImage(message, true)
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
      let image = message.image
      let originalUrl = image
        ? usableSignedUrl(image.accessMode, image.accessUrl, image.accessExpiresAt)
        : ''
      if (!originalUrl) {
        try {
          image = await fetchCustomerServiceImageAccess(message.messageId)
          updateMessageImageAccess(message.messageId, image)
          originalUrl = usableSignedUrl(image.accessMode, image.accessUrl, image.accessExpiresAt)
        } catch {
          // Fall through to the authenticated endpoint for older storage responses.
        }
      }
      const original = originalUrl ? null : await fetchCustomerServiceImage(message.messageId)
      if (
        requestId !== previewRequestSequence ||
        !currentDetail.value?.messages.some(
          (currentMessage) => currentMessage.messageId === message.messageId
        )
      )
        return
      releasePreviewImage()
      previewImageUrl.value = originalUrl || URL.createObjectURL(original!)
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
      const signedUrl = preferredMessageImageUrl(message.image)
      if (cachedUrl || signedUrl) nextUrls[message.messageId] = cachedUrl || signedUrl
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
      const workspace = await fetchCustomerServiceWorkspace()
      if (requestId !== listRequestSequence) return
      conversationPage.value = {
        records: [...workspace.waiting, ...workspace.active, ...workspace.closed],
        current: 1,
        size: 100,
        total: workspace.waitingTotal + workspace.activeTotal + workspace.closedTotal
      }
      conversationTotals.value = {
        WAITING: workspace.waitingTotal,
        ACTIVE: workspace.activeTotal,
        CLOSED: workspace.closedTotal
      }
      const selectedId = selectedConversationId.value
      if (
        selectedId &&
        !conversationPage.value.records.some(
          (conversation) => conversation.conversationId === selectedId
        )
      ) {
        detailRequestSequence += 1
        detailCache.delete(selectedId)
        detachCurrentImageUrls()
        selectedConversationId.value = null
        currentDetail.value = null
        hasEarlierMessages.value = false
      }
      conversationListLoaded = true
      if (selectFirst && !selectedConversationId.value && workspace.active.length) {
        firstConversationId = workspace.active[0].conversationId
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
      hasEarlierMessages.value = detail.messages.length >= 50
      syncImageUrls(currentDetail.value.messages)
      await nextTick()
      if (messageListRef.value) messageListRef.value.scrollTop = messageListRef.value.scrollHeight
      observeImageTargets()
    } finally {
      if (requestId === detailRequestSequence) detailLoading.value = false
    }
  }

  const mergePersistedMessages = (
    existing: Api.CustomerService.Message[],
    incoming: Api.CustomerService.Message[]
  ) => {
    const messages = new Map<number, Api.CustomerService.Message>()
    existing.forEach((message) => messages.set(message.messageId, message))
    incoming.forEach((message) => messages.set(message.messageId, message))
    return Array.from(messages.values()).sort((left, right) => left.messageId - right.messageId)
  }

  const loadEarlierMessages = async () => {
    if (
      !currentDetail.value ||
      !selectedConversationId.value ||
      loadingEarlierMessages.value ||
      !hasEarlierMessages.value
    )
      return
    const firstMessageId = currentDetail.value.messages.find((message) =>
      isPersistedCustomerServiceMessageId(message.messageId)
    )?.messageId
    if (!firstMessageId) {
      hasEarlierMessages.value = false
      return
    }
    const conversationId = selectedConversationId.value
    const list = messageListRef.value
    const previousHeight = list?.scrollHeight || 0
    loadingEarlierMessages.value = true
    try {
      const messages = await fetchCustomerServiceMessages(conversationId, {
        beforeId: firstMessageId,
        limit: 50
      })
      if (selectedConversationId.value !== conversationId || !currentDetail.value) return
      currentDetail.value.messages = mergePersistedMessages(currentDetail.value.messages, messages)
      hasEarlierMessages.value = messages.length >= 50
      const cachedDetail = detailCache.get(conversationId)
      if (cachedDetail) {
        detailCache.set(conversationId, {
          ...cachedDetail,
          messages: mergePersistedMessages(cachedDetail.messages, messages)
        })
      }
      syncImageUrls(currentDetail.value.messages)
      await nextTick()
      if (list) list.scrollTop += list.scrollHeight - previousHeight
      observeImageTargets()
    } finally {
      loadingEarlierMessages.value = false
    }
  }

  const loadNewMessages = async (conversationId: number) => {
    if (selectedConversationId.value !== conversationId || !currentDetail.value) return
    const persistedMessages = currentDetail.value.messages.filter((message) =>
      isPersistedCustomerServiceMessageId(message.messageId)
    )
    const lastMessageId = persistedMessages.at(-1)?.messageId
    if (!lastMessageId) {
      await loadDetail(conversationId)
      return
    }
    const list = messageListRef.value
    const shouldFollow = !list || list.scrollHeight - list.scrollTop - list.clientHeight < 96
    const messages = await fetchCustomerServiceMessages(conversationId, {
      afterId: lastMessageId,
      limit: 100
    })
    if (!messages.length || selectedConversationId.value !== conversationId || !currentDetail.value)
      return
    const pendingMessages = currentDetail.value.messages.filter(
      (message) => !isPersistedCustomerServiceMessageId(message.messageId)
    )
    currentDetail.value.messages = [
      ...mergePersistedMessages(persistedMessages, messages),
      ...pendingMessages
    ]
    const cachedDetail = detailCache.get(conversationId)
    if (cachedDetail) {
      detailCache.set(conversationId, {
        ...cachedDetail,
        messages: mergePersistedMessages(cachedDetail.messages, messages)
      })
    }
    messages.forEach(updateConversationSummary)
    syncImageUrls(currentDetail.value.messages)
    await nextTick()
    if (shouldFollow && list) list.scrollTop = list.scrollHeight
    observeImageTargets()
  }

  const selectConversation = async (conversationId: number) => {
    const conversationChanged = selectedConversationId.value !== conversationId
    if (conversationChanged) detachCurrentImageUrls()
    selectedConversationId.value = conversationId
    const cachedDetail = detailCache.get(conversationId)
    if (conversationChanged) {
      currentDetail.value = cachedDetail ? detailWithPendingMessages(cachedDetail) : null
      hasEarlierMessages.value = Boolean(cachedDetail && cachedDetail.messages.length >= 50)
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

  const handleClaim = async (conversationId?: number) => {
    const targetConversationId = conversationId || selectedConversationId.value
    if (!targetConversationId || claimingConversationId.value !== null) return
    claimingConversationId.value = targetConversationId
    actionLoading.value = true
    try {
      const detail = await claimCustomerServiceConversation(targetConversationId)
      selectedConversationId.value = targetConversationId
      currentDetail.value = detailWithPendingMessages(detail)
      detailCache.set(targetConversationId, detail)
      syncImageUrls(currentDetail.value.messages)
      await Promise.all([loadConversations(), loadAgentState()])
      ElMessage.success('会话已认领')
    } finally {
      claimingConversationId.value = null
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
  const cancelImageUpload = () => imageUploadAbortController?.abort()

  const isSupportedChatImage = (file: File) => {
    const extension = file.name.split('.').pop()?.toLowerCase()
    const contentType = file.type.toLowerCase() === 'image/jpg' ? 'image/jpeg' : file.type
    return (
      ['jpg', 'jpeg', 'png', 'webp', 'gif'].includes(extension || '') &&
      (!contentType || ['image/jpeg', 'image/png', 'image/webp', 'image/gif'].includes(contentType))
    )
  }

  const handleImageSelected = async (event: Event) => {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    input.value = ''
    if (!selectedConversationId.value || !file || !canSend.value) return
    if (!isSupportedChatImage(file)) {
      ElMessage.warning('聊天图片仅支持 JPG、PNG、WebP 或 GIF')
      return
    }
    if (file.size > 5 * 1024 * 1024) {
      ElMessage.warning('聊天图片不能超过 5 MB')
      return
    }
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
    const abortController = new AbortController()
    imageUploadAbortController = abortController
    imageUploadPercent.value = 0
    uploadingImage.value = true
    try {
      const message = await uploadCustomerServiceImage(conversationId, file, {
        signal: abortController.signal,
        onProgress: ({ percent }) => {
          imageUploadPercent.value = percent
        }
      })
      const localImageUrl = cacheCustomerServiceImage(message.messageId, file)
      removePendingImage(pendingMessageId)
      imageUrls.value = { ...imageUrls.value, [message.messageId]: localImageUrl }
      applyLocallySentMessage(conversationId, message)
    } catch (error) {
      removePendingImage(pendingMessageId)
      if (error instanceof Error && error.name === 'AbortError') {
        ElMessage.info('已取消图片上传')
      } else if (!(error instanceof Error) || error.name !== 'HttpError') {
        ElMessage.error(error instanceof Error ? error.message : '图片上传失败，请重试')
      }
    } finally {
      if (imageUploadAbortController === abortController) {
        imageUploadAbortController = null
        uploadingImage.value = false
        imageUploadPercent.value = 0
      }
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
      const releasedConversationId = selectedConversationId.value
      await releaseCustomerServiceConversation(releasedConversationId)
      detailCache.delete(releasedConversationId)
      detachCurrentImageUrls()
      selectedConversationId.value = null
      currentDetail.value = null
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
    if (!document.hidden) displayNow.value = Date.now()
    if (!document.hidden && initialLoadComplete) void refreshAll()
  }

  const handleRealtimeEvent = (event: RealtimeEvent) => {
    if (event.type === 'CUSTOMER_SERVICE_QUEUE_UPDATED') {
      void loadConversations()
      return
    }
    if (event.type.startsWith('CUSTOMER_SERVICE_TRANSFER_')) {
      void Promise.all([loadPendingTransfers(), loadAgentState(), loadConversations()])
      const targetAdminUserId = Number(event.data.toAdminUserId || 0)
      const sourceAdminUserId = Number(event.data.fromAdminUserId || 0)
      const transferredConversationId = Number(event.data.conversationId || 0)
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
      if (
        sourceAdminUserId === currentAdminId.value &&
        (event.type === 'CUSTOMER_SERVICE_TRANSFER_ACCEPTED' ||
          event.type === 'CUSTOMER_SERVICE_TRANSFER_FORCED') &&
        selectedConversationId.value === transferredConversationId
      ) {
        detailCache.delete(transferredConversationId)
        detachCurrentImageUrls()
        selectedConversationId.value = null
        currentDetail.value = null
        ElMessage.info('会话已完成转接')
      }
    }
    if (event.type !== 'CUSTOMER_SERVICE_CONVERSATION_UPDATED') return
    const conversationId = Number(event.data.conversationId || 0)
    if (Number.isSafeInteger(conversationId) && conversationId > 0) {
      const messageId = Number(event.data.messageId || 0)
      const messageIds = pendingRealtimeMessages.get(conversationId) || new Set<number>()
      messageIds.add(Number.isSafeInteger(messageId) && messageId > 0 ? messageId : 0)
      pendingRealtimeMessages.set(conversationId, messageIds)
      if (event.data.changeType !== 'MESSAGE_CREATED') {
        pendingRealtimeFullRefresh.add(conversationId)
      }
    }
    scheduleRealtimeRefresh(250)
  }

  const scheduleRealtimeRefresh = (delay: number) => {
    if (realtimeRefreshTimer || !pendingRealtimeMessages.size) return
    realtimeRefreshTimer = setTimeout(() => {
      realtimeRefreshTimer = null
      pruneHandledMessages()
      const refreshConversationIds = new Set<number>()
      const fullRefreshConversationIds = new Set<number>()
      let hasDeferredMutation = false
      pendingRealtimeMessages.forEach((messageIds, conversationId) => {
        if (localMutationCounts.has(conversationId)) {
          hasDeferredMutation = true
          return
        }
        pendingRealtimeMessages.delete(conversationId)
        const needsFullRefresh = pendingRealtimeFullRefresh.delete(conversationId)
        const needsRefresh = Array.from(messageIds).some(
          (messageId) => messageId === 0 || !locallyHandledMessageIds.has(messageId)
        )
        if (needsFullRefresh) fullRefreshConversationIds.add(conversationId)
        if (needsRefresh || needsFullRefresh) refreshConversationIds.add(conversationId)
      })
      if (hasDeferredMutation) scheduleRealtimeRefresh(100)
      if (!refreshConversationIds.size) return
      const selectedId = selectedConversationId.value
      void Promise.all([
        loadConversations(),
        selectedId && refreshConversationIds.has(selectedId)
          ? fullRefreshConversationIds.has(selectedId)
            ? loadDetail(selectedId)
            : loadNewMessages(selectedId)
          : Promise.resolve()
      ])
    }, delay)
  }

  onMounted(async () => {
    pageMounted = true
    displayClockTimer = setInterval(() => {
      displayNow.value = Date.now()
    }, 30 * 1000)
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
    if (displayClockTimer) clearInterval(displayClockTimer)
    displayClockTimer = null
    if (realtimeRefreshTimer) clearTimeout(realtimeRefreshTimer)
    pendingRealtimeMessages.clear()
    pendingRealtimeFullRefresh.clear()
    locallyHandledMessageIds.clear()
    localMutationCounts.clear()
    imageUploadAbortController?.abort()
    imageUploadAbortController = null
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

  .conversation-item__top {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .conversation-item__top strong {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .conversation-item__message {
    margin: 8px 0;
    overflow: hidden;
    font-size: 13px;
    color: var(--el-text-color-regular);
    text-overflow: ellipsis;
    white-space: nowrap;
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
    scrollbar-width: none;
    background: radial-gradient(circle at 10% 5%, rgb(219 234 254 / 36%), transparent 25%), #f8fafc;
  }

  .message-list::-webkit-scrollbar {
    display: none;
  }

  .message-history-loader {
    display: flex;
    justify-content: center;
    min-height: 32px;
    margin-bottom: 10px;
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

  .message-time {
    margin: 8px 0 14px;
    font-size: 11px;
    line-height: 1.4;
    color: #aaa;
    text-align: center;
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

  .chat-tools,
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
    grid-template-columns: minmax(270px, 320px) minmax(520px, 1fr) minmax(310px, 360px);
    gap: 0;
    height: 100%;
  }

  .workspace-empty {
    display: grid;
    grid-column: 2 / -1;
    place-items: center;
    min-width: 0;
    font-size: 14px;
    color: #999;
    background: #eee;
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

  :deep(.chat-panel .el-card__header),
  :deep(.context-panel .el-card__header) {
    min-height: 70px;
    padding: 16px 22px;
  }

  :deep(.chat-panel .el-card__header) {
    background: #f0f0f0;
  }

  :deep(.conversation-panel .panel-body) {
    height: 100%;
  }

  :deep(.chat-panel .chat-panel__body),
  :deep(.context-panel .context-body) {
    height: calc(100% - 70px);
  }

  .chat-user {
    display: flex;
    align-items: center;
  }

  .conversation-item {
    display: flex;
    gap: 10px;
    padding: 10px 14px;
    border-bottom-color: #ededed;
  }

  .conversation-groups {
    height: 100%;
    overflow-y: auto;
    background: #f7f7f7;
  }

  .conversation-group {
    display: block;
  }

  .conversation-group > summary {
    display: flex;
    gap: 6px;
    align-items: center;
    justify-content: flex-start;
    min-height: 44px;
    padding: 0 16px;
    color: #555;
    list-style: none;
    cursor: pointer;
    user-select: none;
    background: #f7f7f7;
  }

  .conversation-group > summary::-webkit-details-marker {
    display: none;
  }

  .conversation-group > summary > span {
    display: flex;
    gap: 8px;
    align-items: center;
    font-size: 13px;
    font-weight: 650;
  }

  .conversation-group > summary > strong {
    min-width: 22px;
    padding: 1px 7px;
    font-size: 11px;
    font-weight: 600;
    color: #777;
    text-align: center;
    background: #ededed;
    border-radius: 10px;
  }

  .conversation-group > summary svg {
    color: #999;
    transition: transform 160ms ease;
  }

  .conversation-group[open] > summary svg {
    transform: rotate(180deg);
  }

  .group-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
  }

  .group-dot.is-waiting {
    background: #f3a72d;
    box-shadow: 0 0 0 3px rgb(243 167 45 / 13%);
  }

  .group-dot.is-active {
    background: #0abb60;
    box-shadow: 0 0 0 3px rgb(10 187 96 / 12%);
  }

  .group-dot.is-closed {
    background: #a0a0a0;
  }

  .group-empty {
    padding: 18px 16px;
    font-size: 12px;
    color: #aaa;
    text-align: center;
  }

  .waiting-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px 8px;
    padding: 14px 12px 16px;
  }

  .waiting-user {
    min-width: 0;
    text-align: center;
  }

  .waiting-user__trigger {
    display: inline-flex;
  }

  .waiting-user__avatar {
    position: relative;
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    padding: 0;
    margin: 0 auto;
    overflow: hidden;
    color: #7a7a7a;
    cursor: pointer;
    background: #efefef;
    border: 0;
    border-radius: 50%;
  }

  .waiting-user__avatar img,
  .conversation-avatar img,
  .chat-user__avatar img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .waiting-user__avatar > i {
    position: absolute;
    top: 2px;
    right: 2px;
    width: 8px;
    height: 8px;
    background: #ff5a62;
    border: 2px solid #fff;
    border-radius: 50%;
  }

  .waiting-user__claim {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    font-size: 11px;
    font-weight: 650;
    color: #fff;
    background: rgb(4 157 79 / 88%);
    opacity: 0;
    transition: opacity 150ms ease;
  }

  .waiting-user__avatar:hover .waiting-user__claim,
  .waiting-user__avatar:focus-visible .waiting-user__claim {
    opacity: 1;
  }

  .waiting-user__avatar:focus-visible {
    outline: 2px solid rgb(10 187 96 / 28%);
    outline-offset: 2px;
  }

  .waiting-user__avatar:disabled {
    cursor: not-allowed;
    opacity: 0.58;
  }

  :global(.waiting-message-tooltip) {
    max-width: 220px;
    line-height: 1.5;
    word-break: break-word;
  }

  .active-conversation-list,
  .closed-conversation-list {
    background: #f7f7f7;
  }

  .conversation-item.is-closed {
    opacity: 0.78;
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

  .conversation-avatar {
    flex-basis: 36px;
    width: 36px;
    height: 36px;
  }

  .conversation-summary {
    flex: 1;
    min-width: 0;
  }

  .conversation-item__top time {
    flex: none;
    font-size: 11px;
    color: #aaa;
  }

  .conversation-item__message {
    margin: 4px 0 0;
    font-size: 12px;
    color: #666;
  }

  .chat-user {
    gap: 11px;
  }

  .chat-user__avatar {
    flex-basis: 38px;
    width: 38px;
    height: 38px;
  }

  .chat-user > strong {
    display: flex;
    align-items: center;
    min-height: 38px;
  }

  .chat-header__actions {
    gap: 4px;
  }

  .chat-header__actions :deep(.el-button) {
    gap: 5px;
    min-height: 30px;
    padding: 0 8px;
    font-size: 12px;
    color: #202124;
    background: transparent;
    border: 0;
    border-radius: 4px;
    box-shadow: none;
  }

  .chat-header__actions :deep(.el-button + .el-button) {
    margin-left: 0;
  }

  .chat-header__actions :deep(.el-button:hover:not(.is-disabled)),
  .chat-header__actions :deep(.el-button:focus-visible:not(.is-disabled)) {
    color: #202124;
    background: #e7e7e7;
  }

  .chat-header__actions :deep(.el-button.is-disabled) {
    color: #aaa;
    background: transparent;
  }

  .chat-content,
  .chat-content__loading {
    background: #f0f0f0;
  }

  .message-list {
    padding: 26px 22px;
    background: #f0f0f0;
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

  .system-message {
    color: #aaa;
    background: transparent;
  }

  .chat-tools {
    flex: 0 0 auto;
    padding: 6px 14px 0;
    background: #f0f0f0;
  }

  .chat-tools :deep(.el-button) {
    padding: 8px;
    color: #202124;
    background: transparent;
    border: 0;
  }

  .chat-tools :deep(.el-button:hover:not(.is-disabled)),
  .chat-tools :deep(.el-button:focus-visible:not(.is-disabled)) {
    color: #202124;
    background: #e7e7e7;
  }

  .composer {
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
    min-height: 180px;
    padding: 8px 20px 12px;
    background: #f0f0f0;
    border-top-color: #dedede;
  }

  .composer :deep(.el-textarea) {
    flex: 1;
    border: 0;
    border-radius: 0;
  }

  .composer :deep(.el-textarea:focus-within) {
    border-color: transparent;
    box-shadow: none;
  }

  .composer :deep(.el-textarea__inner) {
    height: 100%;
    min-height: 112px !important;
    padding: 10px 0;
    background: transparent;
  }

  .composer :deep(.el-input__count),
  .composer :deep(.el-input__count-inner) {
    background: transparent;
  }

  .composer__footer {
    flex: 0 0 auto;
  }

  .composer__shortcut {
    margin-left: auto;
    white-space: nowrap;
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

    .workspace-empty {
      grid-column: 1;
      min-height: 640px;
    }

    .conversation-panel {
      min-height: 360px;
    }
  }
</style>
