<template>
  <main class="guest-page">
    <section class="guest-card">
      <div class="guest-mark" aria-hidden="true">
        <Store :size="30" />
      </div>

      <p class="eyebrow"><Sparkles :size="15" /> 欢迎访问</p>
      <h1>商城管理后台</h1>
      <p class="introduction">
        当前账号为游客，暂未开通后台管理权限。如需加入客服团队，请联系管理员在客服管理中添加。
      </p>

      <div class="account-card">
        <span class="account-avatar">
          <img v-if="avatar" :src="avatar" alt="" />
          <CircleUserRound v-else :size="24" />
        </span>
        <span class="account-copy">
          <small>当前登录账号</small>
          <strong>{{ accountName }}</strong>
          <span v-if="email">{{ email }}</span>
        </span>
      </div>

      <button type="button" class="logout-button" :disabled="loggingOut" @click="logout">
        <LoaderCircle v-if="loggingOut" class="spin" :size="17" />
        <LogOut v-else :size="17" />
        退出登录
      </button>
    </section>
  </main>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { CircleUserRound, LoaderCircle, LogOut, Sparkles, Store } from '@lucide/vue'
  import { useUserStore } from '@/store/modules/user'
  import { logoutAdminSession } from '@/utils/auth-session'

  defineOptions({ name: 'GuestIntroduction' })

  const userStore = useUserStore()
  const loggingOut = ref(false)
  const accountName = computed(() => userStore.info.userName || '游客')
  const email = computed(() => userStore.info.email || '')
  const avatar = computed(() => userStore.info.avatar || '')

  async function logout() {
    try {
      await ElMessageBox.confirm('确定退出当前账号吗？', '退出登录', {
        confirmButtonText: '退出',
        cancelButtonText: '取消',
        type: 'warning'
      })
      loggingOut.value = true
      await logoutAdminSession()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
    } finally {
      loggingOut.value = false
    }
  }
</script>

<style scoped>
  .guest-page {
    box-sizing: border-box;
    display: grid;
    place-items: center;
    width: 100%;
    min-height: 100%;
    padding: 48px 24px;
    color: #172033;
    background:
      radial-gradient(circle at 12% 12%, rgb(37 99 235 / 12%), transparent 30%),
      radial-gradient(circle at 88% 88%, rgb(14 165 233 / 10%), transparent 32%), #f5f7fb;
  }

  .guest-card {
    box-sizing: border-box;
    width: min(520px, 100%);
    padding: 44px;
    text-align: center;
    background: rgb(255 255 255 / 94%);
    backdrop-filter: blur(14px);
    border: 1px solid rgb(226 232 240 / 88%);
    border-radius: 24px;
    box-shadow: 0 24px 70px rgb(15 23 42 / 10%);
  }

  .guest-mark {
    display: grid;
    place-items: center;
    width: 64px;
    height: 64px;
    margin: 0 auto 22px;
    color: #fff;
    background: linear-gradient(145deg, #2563eb, #0ea5e9);
    border-radius: 19px;
    box-shadow: 0 12px 28px rgb(37 99 235 / 25%);
  }

  .eyebrow {
    display: inline-flex;
    gap: 6px;
    align-items: center;
    margin: 0 0 10px;
    font-size: 13px;
    font-weight: 700;
    color: #2563eb;
  }

  h1 {
    margin: 0;
    font-size: 30px;
    letter-spacing: -0.04em;
  }

  .introduction {
    max-width: 390px;
    margin: 16px auto 28px;
    font-size: 14px;
    line-height: 1.8;
    color: #64748b;
  }

  .account-card {
    display: flex;
    gap: 13px;
    align-items: center;
    padding: 15px 17px;
    text-align: left;
    background: #f8fafc;
    border: 1px solid #e2e8f0;
    border-radius: 14px;
  }

  .account-avatar {
    display: grid;
    flex: 0 0 auto;
    place-items: center;
    width: 44px;
    height: 44px;
    overflow: hidden;
    color: #64748b;
    background: #e2e8f0;
    border-radius: 50%;
  }

  .account-avatar img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .account-copy {
    display: grid;
    min-width: 0;
  }

  .account-copy small,
  .account-copy span {
    overflow: hidden;
    font-size: 12px;
    color: #94a3b8;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .account-copy strong {
    margin: 3px 0;
    overflow: hidden;
    font-size: 15px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .logout-button {
    display: inline-flex;
    gap: 8px;
    align-items: center;
    justify-content: center;
    min-width: 132px;
    height: 42px;
    margin-top: 28px;
    font-size: 14px;
    font-weight: 650;
    color: #475569;
    cursor: pointer;
    background: #fff;
    border: 1px solid #cbd5e1;
    border-radius: 10px;
    transition:
      color 0.16s ease,
      border-color 0.16s ease,
      background 0.16s ease;
  }

  .logout-button:hover:not(:disabled) {
    color: #b91c1c;
    background: #fef2f2;
    border-color: #fecaca;
  }

  .logout-button:disabled {
    cursor: not-allowed;
    opacity: 0.6;
  }

  .spin {
    animation: spin 0.9s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (width <= 600px) {
    .guest-card {
      padding: 34px 24px;
      border-radius: 20px;
    }

    h1 {
      font-size: 26px;
    }
  }
</style>
