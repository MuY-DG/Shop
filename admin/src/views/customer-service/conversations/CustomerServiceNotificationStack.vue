<template>
  <Teleport to="body">
    <div
      class="customer-service-notice-stack"
      role="region"
      aria-label="客服新消息"
      aria-live="polite"
    >
      <TransitionGroup name="customer-service-notice">
        <article
          v-for="notification in notifications"
          :key="notification.conversationId"
          class="customer-service-notice"
        >
          <button
            type="button"
            class="customer-service-notice__close"
            :aria-label="`关闭来自${notification.senderName}的通知`"
            @click="$emit('dismiss', notification.conversationId)"
          >
            <X :size="16" />
          </button>

          <div class="customer-service-notice__heading">
            <img :src="brandIconUrl" alt="" />
            <span>俊祥食品客服</span>
            <i>新消息</i>
          </div>

          <div class="customer-service-notice__content">
            <span class="customer-service-notice__avatar">
              <img v-if="notification.senderAvatar" :src="notification.senderAvatar" alt="" />
              <UserRound v-else :size="20" />
            </span>
            <span class="customer-service-notice__copy">
              <strong>{{ notification.senderName }}</strong>
              <span>{{ notification.body }}</span>
            </span>
          </div>

          <button
            type="button"
            class="customer-service-notice__action"
            @click="$emit('open', notification.conversationId)"
          >
            查看会话
            <ArrowUpRight :size="15" />
          </button>
        </article>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
  import { ArrowUpRight, UserRound, X } from '@lucide/vue'
  import type { CustomerServiceInAppNotification } from '@/utils/customer-service-notification-state'

  defineProps<{ notifications: CustomerServiceInAppNotification[] }>()
  defineEmits<{
    dismiss: [conversationId: number]
    open: [conversationId: number]
  }>()

  const brandIconUrl = `${import.meta.env.BASE_URL}pwa/icon-192.png`
</script>

<style scoped>
  .customer-service-notice-stack {
    position: fixed;
    top: 22px;
    right: 24px;
    z-index: 6000;
    display: grid;
    gap: 12px;
    width: min(380px, calc(100vw - 32px));
    pointer-events: none;
  }

  .customer-service-notice {
    position: relative;
    box-sizing: border-box;
    padding: 17px 18px 16px;
    overflow: hidden;
    color: var(--el-text-color-primary);
    pointer-events: auto;
    background: color-mix(in srgb, var(--el-bg-color-overlay) 96%, #fff 4%);
    border: 1px solid color-mix(in srgb, #a51f18 16%, var(--el-border-color-lighter));
    border-radius: 16px;
    box-shadow:
      0 22px 55px rgb(37 28 24 / 16%),
      0 4px 14px rgb(37 28 24 / 8%);
  }

  .customer-service-notice::before {
    position: absolute;
    inset: 0 auto 0 0;
    width: 4px;
    content: '';
    background: linear-gradient(180deg, #c82a21, #8f1712);
  }

  .customer-service-notice__close {
    position: absolute;
    top: 10px;
    right: 10px;
    display: grid;
    place-items: center;
    width: 30px;
    height: 30px;
    padding: 0;
    color: var(--el-text-color-secondary);
    cursor: pointer;
    background: transparent;
    border: 0;
    border-radius: 9px;
  }

  .customer-service-notice__close:hover {
    color: var(--el-text-color-primary);
    background: var(--el-fill-color-light);
  }

  .customer-service-notice__close:focus-visible,
  .customer-service-notice__action:focus-visible {
    outline: 3px solid rgb(10 198 102 / 22%);
    outline-offset: 2px;
  }

  .customer-service-notice__heading {
    display: flex;
    gap: 8px;
    align-items: center;
    padding-right: 32px;
    font-size: 12px;
    font-weight: 650;
    color: var(--el-text-color-regular);
  }

  .customer-service-notice__heading img {
    width: 24px;
    height: 24px;
    object-fit: contain;
  }

  .customer-service-notice__heading i {
    padding: 3px 7px;
    margin-left: 2px;
    font-size: 10px;
    font-style: normal;
    font-weight: 700;
    color: #087944;
    background: #e8f8ef;
    border-radius: 999px;
  }

  .customer-service-notice__content {
    display: flex;
    gap: 12px;
    align-items: center;
    margin-top: 14px;
  }

  .customer-service-notice__avatar {
    display: grid;
    flex: 0 0 auto;
    place-items: center;
    width: 44px;
    height: 44px;
    overflow: hidden;
    color: #8d211b;
    background: #fbefed;
    border: 1px solid #f0d2ce;
    border-radius: 50%;
  }

  .customer-service-notice__avatar img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .customer-service-notice__copy {
    display: grid;
    flex: 1;
    gap: 5px;
    min-width: 0;
  }

  .customer-service-notice__copy strong {
    overflow: hidden;
    font-size: 15px;
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .customer-service-notice__copy > span {
    display: -webkit-box;
    overflow: hidden;
    font-size: 13px;
    line-height: 1.5;
    color: var(--el-text-color-regular);
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  .customer-service-notice__action {
    display: inline-flex;
    gap: 5px;
    align-items: center;
    justify-content: center;
    height: 34px;
    padding: 0 13px;
    margin-top: 15px;
    margin-left: 56px;
    font-size: 12px;
    font-weight: 700;
    color: #fff;
    cursor: pointer;
    background: #0aad59;
    border: 0;
    border-radius: 9px;
  }

  .customer-service-notice__action:hover {
    background: #07974d;
  }

  .customer-service-notice-enter-active,
  .customer-service-notice-leave-active {
    transition:
      opacity 0.2s ease,
      transform 0.24s ease;
  }

  .customer-service-notice-enter-from,
  .customer-service-notice-leave-to {
    opacity: 0;
    transform: translate3d(28px, -6px, 0) scale(0.98);
  }

  @media (width <= 640px) {
    .customer-service-notice-stack {
      top: 12px;
      right: 16px;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .customer-service-notice-enter-active,
    .customer-service-notice-leave-active {
      transition: none;
    }
  }
</style>
