<template>
  <button
    type="button"
    class="resource-card"
    :class="{ 'resource-card--message': variant === 'message' }"
    @click="open"
  >
    <span v-if="variant === 'message' && !product" class="resource-card__heading">
      <span
        >{{ afterSale ? '售后单' : '订单号' }}：{{ afterSale?.afterSaleNo || order?.orderNo }}</span
      >
      <small>{{ resourceStatus(afterSale?.status || order?.status || '') }}</small>
    </span>
    <span class="resource-card__main">
      <img v-if="image && !imageFailed" :src="image" alt="" @error="imageFailed = true" />
      <span v-else class="resource-card__placeholder"><Package :size="24" /></span>
      <span class="resource-card__body">
        <strong>{{ title }}</strong>
        <span v-if="variant !== 'message' && !product" class="resource-card__number">{{
          afterSale?.afterSaleNo || order?.orderNo
        }}</span>
        <span class="resource-card__footer">
          <span class="resource-card__price" :class="{ 'is-product': product }">{{ price }}</span>
          <small v-if="variant !== 'message'">{{
            resourceStatus(afterSale?.status || order?.status || product?.status || '')
          }}</small>
          <small v-else-if="order">共 {{ order.itemCount }} 件</small>
          <small v-else-if="afterSale">申请退款</small>
          <span v-else class="resource-card__action">查看商品</span>
        </span>
      </span>
      <ChevronRight v-if="variant !== 'message'" class="resource-card__chevron" :size="16" />
    </span>
  </button>
</template>

<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import { ChevronRight, Package } from '@lucide/vue'
  import { resourceMoney, resourceProductPrice, resourceStatus } from './resource-display'
  const props = withDefaults(
    defineProps<{
      order?: Api.CustomerService.LinkedOrder | null
      product?: Api.CustomerService.LinkedProduct | null
      afterSale?: Api.CustomerService.LinkedAfterSale | null
      variant?: 'list' | 'message'
    }>(),
    { variant: 'list' }
  )
  const emit = defineEmits<{ open: [target: Api.CustomerService.ResourceTarget] }>()
  const image = computed(
    () =>
      props.product?.image ||
      props.afterSale?.primaryProductImage ||
      props.order?.primaryProductImage
  )
  const title = computed(
    () =>
      props.product?.title ||
      props.afterSale?.primaryProductTitle ||
      props.order?.primaryProductTitle ||
      '商品信息'
  )
  const imageFailed = ref(false)
  watch(image, () => {
    imageFailed.value = false
  })
  const price = computed(() =>
    props.product
      ? resourceProductPrice(props.product)
      : resourceMoney(props.afterSale?.requestedAmountCent ?? props.order?.payableAmountCent)
  )
  const open = () => {
    if (props.afterSale) emit('open', { kind: 'afterSale', id: props.afterSale.afterSaleId })
    else if (props.order) emit('open', { kind: 'order', id: props.order.orderId })
    else if (props.product) emit('open', { kind: 'product', id: props.product.productId })
  }
</script>

<style scoped lang="scss">
  .resource-card {
    display: block;
    width: 100%;
    padding: 14px 0;
    color: #24262b;
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-bottom: 1px solid #f0f1f3;
  }
  .resource-card:hover {
    background: #f8f9fb;
  }
  .resource-card:focus-visible {
    outline: 2px solid #07a95a;
    outline-offset: 2px;
  }
  .resource-card__main {
    display: flex;
    gap: 12px;
    align-items: center;
    min-width: 0;
  }
  .resource-card__main > img,
  .resource-card__placeholder {
    display: grid;
    flex: none;
    width: 58px;
    height: 58px;
    place-items: center;
    object-fit: cover;
    background: #f5f6f8;
    border-radius: 6px;
  }
  .resource-card__placeholder {
    color: #a4a8b1;
  }
  .resource-card__body {
    flex: 1;
    min-width: 0;
  }
  .resource-card__body > strong {
    display: -webkit-box;
    overflow: hidden;
    font-size: 13px;
    font-weight: 500;
    line-height: 1.5;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }
  .resource-card__number {
    display: block;
    margin-top: 4px;
    overflow: hidden;
    font-size: 11px;
    color: #989ca5;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .resource-card__footer {
    display: flex;
    flex-wrap: wrap;
    gap: 5px 10px;
    align-items: center;
    justify-content: space-between;
    margin-top: 7px;
  }
  .resource-card__footer small {
    font-size: 11px;
    color: #8c919b;
  }
  .resource-card__price {
    font-size: 13px;
    font-weight: 600;
  }
  .resource-card__price.is-product {
    color: #eb3547;
  }
  .resource-card__chevron {
    flex: none;
    color: #b2b5be;
  }
  .resource-card--message {
    width: min(360px, 100%);
    padding: 16px;
    background: #fff;
    border: 0;
    border-radius: 14px;
  }
  .resource-card--message:hover {
    background: #fff;
    box-shadow: 0 2px 12px rgb(30 35 45 / 5%);
  }
  .resource-card--message .resource-card__main > img,
  .resource-card--message .resource-card__placeholder {
    width: 88px;
    height: 88px;
  }
  .resource-card--message .resource-card__body > strong {
    font-size: 15px;
  }
  .resource-card--message .resource-card__footer {
    margin-top: 16px;
  }
  .resource-card--message .resource-card__price {
    font-size: 18px;
  }
  .resource-card__heading {
    display: flex;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 13px;
    font-size: 12px;
    color: #979da7;
  }
  .resource-card__heading > span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .resource-card__heading small {
    flex: none;
    padding: 2px 6px;
    color: #6c727d;
    background: #f0f2f6;
    border-radius: 3px;
  }
  .resource-card__action {
    padding: 5px 10px;
    font-size: 12px;
    color: #ed3548;
    background: #fff2f3;
    border-radius: 5px;
  }
</style>
