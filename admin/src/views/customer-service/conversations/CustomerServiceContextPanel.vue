<template>
  <aside class="consultation-context">
    <template v-if="detail">
      <section class="context-section context-section--current">
        <header
          ><h2>当前咨询</h2><span>第 {{ detail.consultationNo }} 次</span></header
        >
        <dl class="customer-facts">
          <div
            ><dt>客户</dt><dd>{{ detail.userNickname || '未设置昵称' }}</dd></div
          >
          <div
            ><dt>用户 ID</dt><dd class="customer-facts__id">{{ detail.appUserId }}</dd></div
          >
          <div
            ><dt>接待客服</dt><dd>{{ detail.assignedAdminDisplayName || '待接入' }}</dd></div
          >
        </dl>
        <CustomerServiceResourceCard
          v-if="detail.currentContext.type === 'AFTER_SALE' && detail.currentContext.afterSale"
          :after-sale="detail.currentContext.afterSale"
          @open="emit('open', $event)"
        />
        <CustomerServiceResourceCard
          v-else-if="detail.currentContext.type === 'ORDER' && detail.currentContext.order"
          :order="detail.currentContext.order"
          @open="emit('open', $event)"
        />
        <CustomerServiceResourceCard
          v-else-if="detail.currentContext.product"
          :product="detail.currentContext.product"
          @open="emit('open', $event)"
        />
        <p v-else class="context-empty">普通咨询</p>
      </section>
      <section class="context-section">
        <header
          ><h2>相关订单</h2><span>{{ detail.linkedOrders.length }}</span></header
        >
        <CustomerServiceResourceCard
          v-for="order in detail.linkedOrders"
          :key="order.orderId"
          :order="order"
          @open="emit('open', $event)"
        />
        <p v-if="!detail.linkedOrders.length" class="context-empty">暂无相关订单</p>
      </section>
      <section class="context-section">
        <header
          ><h2>相关商品</h2><span>{{ detail.linkedProducts.length }}</span></header
        >
        <CustomerServiceResourceCard
          v-for="product in detail.linkedProducts"
          :key="product.productId"
          :product="product"
          @open="emit('open', $event)"
        />
        <p v-if="!detail.linkedProducts.length" class="context-empty">暂无相关商品</p>
      </section>
      <section class="context-section">
        <header
          ><h2>相关售后</h2><span>{{ detail.linkedAfterSales?.length || 0 }}</span></header
        >
        <CustomerServiceResourceCard
          v-for="sale in detail.linkedAfterSales || []"
          :key="sale.afterSaleId"
          :after-sale="sale"
          @open="emit('open', $event)"
        />
        <p v-if="!detail.linkedAfterSales?.length" class="context-empty">暂无相关售后</p>
      </section>
    </template>
    <p v-else class="context-empty">选择会话后查看咨询信息</p>
  </aside>
</template>

<script setup lang="ts">
  import CustomerServiceResourceCard from './CustomerServiceResourceCard.vue'
  defineProps<{ detail: Api.CustomerService.ConversationDetail | null }>()
  const emit = defineEmits<{ open: [target: Api.CustomerService.ResourceTarget] }>()
</script>

<style scoped lang="scss">
  .consultation-context {
    min-width: 0;
    height: 100%;
    padding: 0 22px 28px;
    overflow-y: auto;
    background: #fff;
    border-left: 1px solid #eceef2;
  }
  .context-section {
    padding: 22px 0 8px;
  }
  .context-section + .context-section {
    border-top: 1px solid #eceef2;
  }
  .context-section > header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 7px;
  }
  .context-section h2 {
    margin: 0;
    font-size: 14px;
    font-weight: 600;
    color: #292c33;
  }
  .context-section > header > span {
    font-size: 12px;
    color: #a0a5af;
  }
  .context-section :deep(.resource-card:last-child) {
    border-bottom: 0;
  }
  .customer-facts {
    display: grid;
    gap: 11px;
    margin: 20px 0 12px;
    font-size: 12px;
  }
  .customer-facts > div {
    display: grid;
    grid-template-columns: 65px minmax(0, 1fr);
    gap: 8px;
  }
  .customer-facts dt {
    color: #959ba6;
  }
  .customer-facts dd {
    margin: 0;
    color: #545b67;
    overflow-wrap: anywhere;
  }
  .customer-facts__id {
    font-variant-numeric: tabular-nums;
  }
  .context-empty {
    padding: 12px 0;
    font-size: 12px;
    color: #a0a5af;
  }
</style>
