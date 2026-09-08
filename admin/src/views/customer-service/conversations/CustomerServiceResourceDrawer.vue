<template>
  <ElDrawer
    :model-value="Boolean(modelValue)"
    :title="title"
    size="min(840px, 92vw)"
    append-to-body
    destroy-on-close
    class="cs-resource-drawer"
    @update:model-value="close"
  >
    <div class="resource-detail">
      <button v-if="history.length" class="detail-back" type="button" @click="back"
        ><ArrowLeft :size="16" />返回上一层详情</button
      >
      <div v-if="loading" class="detail-state"
        ><LoaderCircle class="is-spinning" :size="24" /><span>正在加载详情</span></div
      >
      <div v-else-if="error" class="detail-state"
        ><span>{{ error }}</span
        ><ElButton @click="load">重新加载</ElButton></div
      >
      <template v-else-if="order">
        <header class="detail-summary">
          <div
            ><span class="detail-kicker">订单详情</span><h2>{{ resourceStatus(order.status) }}</h2
            ><button class="detail-number" @click="copy(order.orderNo)"
              >{{ order.orderNo }}<Copy :size="14" /></button
          ></div>
          <div class="summary-amount"
            ><span>实付金额</span><strong>{{ money(order.paidAmountCent) }}</strong></div
          >
        </header>
        <section class="detail-section">
          <h3>订单信息</h3>
          <dl class="detail-facts">
            <div
              ><dt>下单时间</dt><dd>{{ date(order.createdAt) }}</dd></div
            >
            <div
              ><dt>支付时间</dt><dd>{{ date(order.paidAt) }}</dd></div
            >
            <div
              ><dt>客户</dt><dd>{{ order.userNickname || '未设置昵称' }}</dd></div
            >
            <div
              ><dt>商品数量</dt><dd>{{ order.itemCount }} 件</dd></div
            >
            <div v-if="order.closedAt"
              ><dt>关闭时间</dt><dd>{{ date(order.closedAt) }}</dd></div
            >
            <div v-if="order.closeReason"
              ><dt>关闭原因</dt><dd>{{ order.closeReason }}</dd></div
            >
          </dl>
        </section>
        <section class="detail-section">
          <h3>收货信息</h3>
          <dl class="detail-facts">
            <div
              ><dt>收货人</dt><dd>{{ order.receiverName || '—' }}</dd></div
            >
            <div
              ><dt>联系电话</dt><dd>{{ order.receiverPhone || '—' }}</dd></div
            >
            <div class="detail-fact--wide"
              ><dt>收货地址</dt><dd>{{ order.receiverAddress || '—' }}</dd></div
            >
          </dl>
        </section>
        <section class="detail-section">
          <h3
            >订单商品 <span>{{ order.items.length }}</span></h3
          >
          <button
            v-for="item in order.items"
            :key="item.orderItemId"
            type="button"
            class="detail-product"
            @click="navigate('product', item.spuId)"
          >
            <img
              v-if="item.displayImage || item.skuImage || item.mainImage"
              :src="item.displayImage || item.skuImage || item.mainImage"
              alt=""
            />
            <span class="detail-product__body"
              ><strong>{{ item.productTitle }}</strong
              ><span>{{ item.specText || '默认规格' }}</span
              ><small v-if="item.afterSale?.refundedQuantity"
                >已退款 {{ item.afterSale.refundedQuantity }} 件 ·
                {{ money(item.afterSale.refundedAmountCent) }}</small
              ></span
            >
            <span class="detail-product__amount"
              ><strong>{{ money(item.lineAmountCent) }}</strong
              ><span>数量 {{ item.quantity }}</span></span
            ><ChevronRight :size="16" />
          </button>
          <dl class="detail-totals"
            ><div
              ><dt>商品金额</dt><dd>{{ money(order.productAmountCent) }}</dd></div
            ><div
              ><dt>运费</dt><dd>{{ money(order.freightCent) }}</dd></div
            ><div
              ><dt>优惠抵扣</dt><dd>−{{ money(order.couponDiscountCent) }}</dd></div
            ><div v-if="order.refundedAmountCent"
              ><dt>已退款</dt><dd>{{ money(order.refundedAmountCent) }}</dd></div
            ><div class="detail-totals__paid"
              ><dt>实付金额</dt><dd>{{ money(order.paidAmountCent) }}</dd></div
            ></dl
          >
        </section>
        <section class="detail-section">
          <h3>配送信息</h3>
          <div v-for="shipment in shipments" :key="shipment.shipmentId" class="shipment-details">
            <p class="detail-subtitle">包裹 {{ shipment.packageNo || 1 }}</p>
            <dl class="detail-facts"
              ><div
                ><dt>配送方式</dt
                ><dd>{{
                  shipment.expressCompanyName || logisticsTypeLabel(shipment.logisticsType)
                }}</dd></div
              ><div
                ><dt>发货时间</dt><dd>{{ date(shipment.shippedAt) }}</dd></div
              ><div v-if="shipment.trackingNo" class="detail-fact--wide"
                ><dt>物流单号</dt
                ><dd
                  ><button class="detail-number" @click="copy(shipment.trackingNo)"
                    >{{ shipment.trackingNo }}<Copy :size="14" /></button></dd></div
              ><div v-if="shipment.shipmentNote" class="detail-fact--wide"
                ><dt>发货备注</dt><dd>{{ shipment.shipmentNote }}</dd></div
              ></dl
            >
          </div>
          <p v-if="!shipments.length" class="detail-muted">暂无发货信息</p>
        </section>
        <section v-if="orderSales.length" class="detail-section">
          <h3>相关售后</h3>
          <button
            v-for="sale in orderSales"
            :key="sale.afterSaleId"
            class="detail-related"
            @click="navigate('afterSale', sale.afterSaleId)"
            ><span>{{ sale.afterSaleNo }}</span
            ><span>{{ resourceStatus(sale.status) }}<ChevronRight :size="16" /></span
          ></button>
        </section>
      </template>
      <template v-else-if="sale">
        <header class="detail-summary"
          ><div
            ><span class="detail-kicker">{{
              sale.afterSaleType === 'RETURN_REFUND' ? '退货退款' : '仅退款'
            }}</span
            ><h2>{{ resourceStatus(sale.status) }}</h2
            ><button class="detail-number" @click="copy(sale.afterSaleNo)"
              >{{ sale.afterSaleNo }}<Copy :size="14" /></button></div
          ><div class="summary-amount"
            ><span>申请退款</span><strong>{{ money(sale.requestedAmountCent) }}</strong></div
          ></header
        >
        <section class="detail-section"
          ><h3>申请信息</h3
          ><dl class="detail-facts"
            ><div
              ><dt>申请时间</dt><dd>{{ date(sale.createdAt) }}</dd></div
            ><div
              ><dt>售后类型</dt
              ><dd>{{ sale.afterSaleType === 'RETURN_REFUND' ? '退货退款' : '仅退款' }}</dd></div
            ><div class="detail-fact--wide"
              ><dt>关联订单</dt
              ><dd
                ><button
                  class="detail-number detail-number--link"
                  @click="navigate('order', sale.orderId)"
                  >{{ sale.orderNo }}<ChevronRight :size="14" /></button></dd></div
            ><div class="detail-fact--wide"
              ><dt>申请原因</dt><dd>{{ sale.reason }}</dd></div
            ><div v-if="sale.description" class="detail-fact--wide"
              ><dt>问题描述</dt><dd>{{ sale.description }}</dd></div
            ><div v-if="sale.auditNote" class="detail-fact--wide"
              ><dt>审核说明</dt><dd>{{ sale.auditNote }}</dd></div
            ><div v-if="sale.approvedAmountCent != null"
              ><dt>核准退款</dt><dd>{{ money(sale.approvedAmountCent) }}</dd></div
            ><div v-if="sale.reviewedAt"
              ><dt>审核时间</dt><dd>{{ date(sale.reviewedAt) }}</dd></div
            ></dl
          ></section
        >
        <section class="detail-section"
          ><h3>售后商品</h3
          ><button
            v-for="item in sale.items"
            :key="item.id"
            class="detail-product"
            :disabled="!saleProductId(item.orderItemId)"
            @click="navigate('product', saleProductId(item.orderItemId))"
            ><img v-if="item.image" :src="item.image" alt="" /><span class="detail-product__body"
              ><strong>{{ item.productTitle }}</strong
              ><span>{{ item.specText || '默认规格' }}</span></span
            ><span class="detail-product__amount"
              ><strong>{{ money(item.requestedAmountCent) }}</strong
              ><span>申请 {{ item.requestedQuantity }} 件</span></span
            ><ChevronRight :size="16" /></button
        ></section>
        <section v-if="sale.evidenceFiles?.length" class="detail-section"
          ><h3>问题凭证</h3
          ><div class="evidence-list"
            ><button
              v-for="file in sale.evidenceFiles"
              :key="file.fileId"
              @click="previewEvidence(file)"
              ><ImageIcon :size="18" /><span>{{ file.originalFilename || '查看凭证' }}</span
              ><LoaderCircle
                v-if="evidenceLoadingId === file.fileId"
                class="is-spinning"
                :size="15" /><ChevronRight v-else :size="15" /></button></div
        ></section>
        <section v-if="sale.returnInfo" class="detail-section"
          ><h3>退货信息</h3
          ><dl class="detail-facts"
            ><div
              ><dt>退货快递</dt><dd>{{ sale.returnInfo.deliveryCompanyName || '—' }}</dd></div
            ><div
              ><dt>退货单号</dt><dd>{{ sale.returnInfo.trackingNo || '—' }}</dd></div
            ><div
              ><dt>寄回时间</dt><dd>{{ date(sale.returnInfo.userShippedAt) }}</dd></div
            ><div
              ><dt>验收时间</dt><dd>{{ date(sale.returnInfo.inspectedAt) }}</dd></div
            ><div class="detail-fact--wide"
              ><dt>验收说明</dt><dd>{{ sale.returnInfo.inspectionNote || '—' }}</dd></div
            ></dl
          ></section
        >
        <section v-if="sale.refundOrder" class="detail-section"
          ><h3>退款进度</h3
          ><dl class="detail-facts"
            ><div
              ><dt>退款状态</dt><dd>{{ refundStatus(sale.refundOrder.status) }}</dd></div
            ><div
              ><dt>退款金额</dt><dd>{{ money(sale.refundOrder.refundAmountCent) }}</dd></div
            ><div
              ><dt>申请时间</dt><dd>{{ date(sale.refundOrder.requestedAt) }}</dd></div
            ><div
              ><dt>完成时间</dt><dd>{{ date(sale.refundOrder.successAt) }}</dd></div
            ></dl
          ></section
        >
      </template>
      <template v-else-if="product">
        <header class="detail-summary product-summary"
          ><ElImage
            v-if="product.mainImage"
            :src="product.mainImage"
            :preview-src-list="productImages"
            preview-teleported
            fit="cover"
          /><div
            ><span class="detail-kicker"
              >{{ product.categoryName }} · {{ resourceStatus(product.status) }}</span
            ><h2>{{ product.title }}</h2
            ><p>{{ product.subtitle }}</p
            ><strong class="product-summary__price">{{ productPrice }}</strong></div
          ></header
        >
        <section v-if="productImages.length > 1" class="detail-section"
          ><h3>商品图片</h3
          ><div class="product-gallery"
            ><ElImage
              v-for="(src, index) in productImages"
              :key="src"
              :src="src"
              :preview-src-list="productImages"
              :initial-index="index"
              preview-teleported
              fit="cover" /></div
        ></section>
        <section class="detail-section"
          ><h3
            >规格与价格 <span>{{ product.skus.length }}</span></h3
          ><div v-for="sku in product.skus" :key="sku.id" class="detail-product"
            ><img v-if="sku.image" :src="sku.image" alt="" /><span class="detail-product__body"
              ><strong>{{ sku.specText || '默认规格' }}</strong
              ><span>编码 {{ sku.skuCode || '—' }}</span
              ><small>{{ resourceStatus(sku.status) }} · 库存 {{ sku.stockAvailable }}</small></span
            ><span class="detail-product__amount"
              ><strong>{{ money(sku.priceCent) }}</strong></span
            ></div
          ></section
        >
        <section v-if="product.sellingPoints" class="detail-section"
          ><h3>商品卖点</h3><p class="detail-copy">{{ product.sellingPoints }}</p></section
        >
        <section class="detail-section"
          ><h3>商品介绍</h3
          ><iframe
            v-if="product.detailHtml"
            class="product-description"
            title="商品图文详情"
            sandbox=""
            referrerpolicy="no-referrer"
            :srcdoc="productDocument"
          /><p v-else class="detail-muted">暂无图文介绍</p></section
        >
      </template>
    </div>
    <ElDialog
      v-model="evidenceVisible"
      title="售后凭证"
      width="min(760px, 90vw)"
      append-to-body
      @closed="clearEvidence"
    >
      <video
        v-if="evidenceType.startsWith('video/')"
        class="evidence-preview"
        :src="evidenceUrl"
        controls
      />
      <img v-else class="evidence-preview" :src="evidenceUrl" alt="售后凭证" />
    </ElDialog>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { ElMessage } from 'element-plus'
  import { ArrowLeft, ChevronRight, Copy, Image as ImageIcon, LoaderCircle } from '@lucide/vue'
  import {
    fetchCustomerServiceAfterSaleDetail,
    fetchCustomerServiceAfterSaleEvidence,
    fetchCustomerServiceOrderDetail,
    fetchCustomerServiceProductDetail
  } from '@/api/customer-service'
  import { formatLocalDateTime } from '@/utils/date-time'
  import { logisticsTypeLabel } from '@/views/order/list/shipping-form'
  import { resourceMoney as money, resourceStatus } from './resource-display'
  const props = defineProps<{
    modelValue: Api.CustomerService.ResourceTarget | null
    conversationId: number | null
  }>()
  const emit = defineEmits<{
    'update:modelValue': [target: Api.CustomerService.ResourceTarget | null]
  }>()
  const order = ref<Api.Order.OrderDetail | null>(null)
  const sale = ref<Api.AfterSale.Detail | null>(null)
  const product = ref<Api.CustomerService.ProductDetail | null>(null)
  const history = ref<Api.CustomerService.ResourceTarget[]>([])
  const loading = ref(false)
  const error = ref('')
  const evidenceLoadingId = ref<number | null>(null)
  const evidenceUrl = ref('')
  const evidenceType = ref('')
  const evidenceVisible = ref(false)
  let requestSequence = 0
  let evidenceSequence = 0
  const title = computed(
    () =>
      ({ order: '订单详情', afterSale: '售后详情', product: '商品详情' })[
        props.modelValue?.kind || 'order'
      ]
  )
  const date = (value?: string | null) => (value ? formatLocalDateTime(value) : '—')
  const shipments = computed(() =>
    order.value?.shipments?.length
      ? order.value.shipments
      : order.value?.shipment
        ? [order.value.shipment]
        : []
  )
  const orderSales = computed(() => {
    const records = new Map<number, { afterSaleId: number; afterSaleNo: string; status: string }>()
    if (order.value?.activeAfterSale)
      records.set(order.value.activeAfterSale.afterSaleId, order.value.activeAfterSale)
    order.value?.items.forEach((item) =>
      item.afterSale?.records.forEach((record) => records.set(record.afterSaleId, record))
    )
    return [...records.values()]
  })
  const productImages = computed(() => [
    ...new Set(
      [product.value?.mainImage, ...(product.value?.images || [])].filter((src): src is string =>
        Boolean(src)
      )
    )
  ])
  const productPrice = computed(() => {
    const prices = product.value?.skus.map((sku) => sku.priceCent) || []
    if (!prices.length) return '价格待确认'
    const min = Math.min(...prices),
      max = Math.max(...prices)
    return min === max ? money(min) : `${money(min)}–${money(max)}`
  })
  const productDocument = computed(
    () =>
      `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; form-action 'none';"><style>body{margin:0;color:#343942;font:14px/1.7 -apple-system,BlinkMacSystemFont,sans-serif;overflow-wrap:anywhere}img,video{max-width:100%;height:auto}table{max-width:100%;width:100%;border-collapse:collapse}a{pointer-events:none;color:inherit}</style></head><body>${product.value?.detailHtml || ''}</body></html>`
  )
  const saleProductId = (itemId: number) =>
    sale.value?.orderContext.items.find((item) => item.orderItemId === itemId)?.spuId || 0
  const refundStatus = (status: string) =>
    ({ PROCESSING: '退款处理中', SUCCESS: '退款成功', FAILED: '退款异常' })[status] || status
  const clearEvidence = () => {
    evidenceSequence++
    if (evidenceUrl.value) URL.revokeObjectURL(evidenceUrl.value)
    evidenceUrl.value = ''
    evidenceLoadingId.value = null
  }
  const close = () => emit('update:modelValue', null)
  const navigate = (kind: Api.CustomerService.ResourceKind, id: number) => {
    if (!id || !props.modelValue) return
    history.value.push(props.modelValue)
    emit('update:modelValue', { kind, id })
  }
  const back = () => emit('update:modelValue', history.value.pop() || null)
  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value)
      ElMessage.success('已复制')
    } catch {
      ElMessage.warning('复制失败，请手动复制')
    }
  }
  const load = async () => {
    const sequence = ++requestSequence
    const target = props.modelValue,
      conversationId = props.conversationId
    order.value = null
    sale.value = null
    product.value = null
    error.value = ''
    evidenceVisible.value = false
    clearEvidence()
    if (!target || !conversationId) {
      loading.value = false
      history.value = []
      return
    }
    loading.value = true
    try {
      if (target.kind === 'order') {
        const detail = await fetchCustomerServiceOrderDetail(conversationId, target.id)
        if (sequence === requestSequence) order.value = detail
      } else if (target.kind === 'afterSale') {
        const detail = await fetchCustomerServiceAfterSaleDetail(conversationId, target.id)
        if (sequence === requestSequence) sale.value = detail
      } else {
        const detail = await fetchCustomerServiceProductDetail(conversationId, target.id)
        if (sequence === requestSequence) product.value = detail
      }
    } catch (cause) {
      if (sequence === requestSequence)
        error.value = cause instanceof Error ? cause.message : '详情暂时无法加载，请重试'
    } finally {
      if (sequence === requestSequence) loading.value = false
    }
  }
  const previewEvidence = async (file: Api.AfterSale.EvidenceFile) => {
    if (!sale.value || !props.conversationId || evidenceLoadingId.value) return
    const sequence = ++evidenceSequence
    evidenceLoadingId.value = file.fileId
    try {
      const blob = await fetchCustomerServiceAfterSaleEvidence(
        props.conversationId,
        sale.value.id,
        file.fileId
      )
      if (sequence !== evidenceSequence) return
      if (evidenceUrl.value) URL.revokeObjectURL(evidenceUrl.value)
      evidenceUrl.value = URL.createObjectURL(blob)
      evidenceType.value = file.contentType
      evidenceVisible.value = true
    } catch {
      if (sequence === evidenceSequence) ElMessage.error('凭证加载失败，请重试')
    } finally {
      if (sequence === evidenceSequence) evidenceLoadingId.value = null
    }
  }
  watch(() => [props.modelValue?.kind, props.modelValue?.id, props.conversationId], load, {
    immediate: true
  })
  watch(
    () => props.conversationId,
    () => {
      history.value = []
      close()
    }
  )
  onBeforeUnmount(() => {
    requestSequence++
    clearEvidence()
  })
</script>

<style scoped lang="scss">
  .resource-detail {
    padding: 0 8px 36px;
    color: #333842;
  }
  .detail-back {
    display: inline-flex;
    gap: 7px;
    align-items: center;
    margin-bottom: 20px;
    padding: 0;
    font-size: 13px;
    color: #818895;
    cursor: pointer;
    background: none;
    border: 0;
  }
  .detail-summary {
    display: flex;
    gap: 24px;
    align-items: center;
    justify-content: space-between;
    padding: 12px 0 28px;
    border-bottom: 1px solid #eceef2;
  }
  .detail-summary > div {
    min-width: 0;
  }
  .detail-kicker {
    font-size: 12px;
    color: #9a9faa;
  }
  .detail-summary h2 {
    margin: 8px 0 14px;
    font-size: 24px;
    font-weight: 600;
    line-height: 1.4;
  }
  .detail-number {
    display: inline-flex;
    gap: 8px;
    align-items: center;
    max-width: 100%;
    padding: 0;
    font-size: 12px;
    color: #9097a3;
    text-align: left;
    overflow-wrap: anywhere;
    cursor: pointer;
    background: none;
    border: 0;
  }
  .detail-number svg {
    flex: none;
  }
  .detail-number--link {
    color: #15975b;
  }
  .summary-amount {
    flex: none;
    text-align: right;
  }
  .summary-amount span {
    display: block;
    margin-bottom: 9px;
    font-size: 12px;
    color: #969da8;
  }
  .summary-amount strong {
    font-size: 28px;
    font-weight: 600;
  }
  .detail-section {
    padding: 26px 0;
    border-bottom: 1px solid #eceef2;
  }
  .detail-section:last-child {
    border-bottom: 0;
  }
  .detail-section h3 {
    display: flex;
    gap: 9px;
    align-items: center;
    margin: 0 0 20px;
    font-size: 15px;
    font-weight: 600;
  }
  .detail-section h3 > span {
    font-size: 12px;
    font-weight: 400;
    color: #9ba1ac;
  }
  .detail-facts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 20px 32px;
    margin: 0;
  }
  .detail-facts > div {
    display: grid;
    grid-template-columns: 74px minmax(0, 1fr);
    gap: 12px;
    font-size: 13px;
    line-height: 1.6;
  }
  .detail-facts dt {
    color: #939aa6;
  }
  .detail-facts dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .detail-fact--wide {
    grid-column: 1 / -1;
  }
  .detail-product {
    display: flex;
    gap: 16px;
    align-items: center;
    width: 100%;
    padding: 16px 0;
    color: inherit;
    text-align: left;
    background: none;
    border: 0;
    border-bottom: 1px solid #f1f2f5;
  }
  button.detail-product {
    cursor: pointer;
  }
  button.detail-product:hover {
    background: #fafbfd;
  }
  .detail-product > img {
    flex: none;
    width: 68px;
    height: 68px;
    object-fit: cover;
    border-radius: 7px;
  }
  .detail-product > svg {
    flex: none;
    color: #a4aab3;
  }
  .detail-product__body {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
  }
  .detail-product strong {
    font-size: 14px;
    font-weight: 500;
  }
  .detail-product__body > span,
  .detail-product__body > small {
    font-size: 12px;
    color: #999fab;
  }
  .detail-product__amount {
    display: flex;
    flex: none;
    flex-direction: column;
    gap: 8px;
    text-align: right;
  }
  .detail-product__amount > span {
    font-size: 12px;
    color: #989fab;
  }
  .detail-totals {
    display: grid;
    gap: 12px;
    width: 260px;
    margin: 24px 0 0 auto;
    font-size: 13px;
  }
  .detail-totals > div {
    display: flex;
    justify-content: space-between;
  }
  .detail-totals dt {
    color: #929aa5;
  }
  .detail-totals dd {
    margin: 0;
  }
  .detail-totals__paid {
    padding-top: 15px;
    font-size: 16px;
    border-top: 1px solid #eceef2;
  }
  .detail-totals__paid dd {
    font-weight: 600;
  }
  .shipment-details + .shipment-details {
    margin-top: 26px;
  }
  .detail-subtitle {
    margin: 0 0 18px;
    font-size: 13px;
    font-weight: 500;
  }
  .detail-muted {
    font-size: 13px;
    color: #a0a6b0;
  }
  .detail-related {
    display: flex;
    justify-content: space-between;
    width: 100%;
    padding: 15px 0;
    font-size: 13px;
    color: #545d69;
    cursor: pointer;
    background: none;
    border: 0;
  }
  .detail-related > span:last-child {
    display: flex;
    gap: 10px;
    align-items: center;
    color: #959daa;
  }
  .product-summary {
    justify-content: flex-start;
  }
  .product-summary > .el-image {
    flex: none;
    width: 148px;
    height: 148px;
    border-radius: 12px;
  }
  .product-summary h2 {
    font-size: 22px;
  }
  .product-summary p {
    font-size: 13px;
    color: #959ca7;
  }
  .product-summary__price {
    display: block;
    margin-top: 18px;
    font-size: 24px;
    color: #e6384a;
  }
  .product-gallery {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
  }
  .product-gallery .el-image {
    width: 88px;
    height: 88px;
    border-radius: 8px;
  }
  .detail-copy {
    font-size: 14px;
    line-height: 1.8;
    white-space: pre-wrap;
  }
  .product-description {
    width: 100%;
    height: 520px;
    background: #fff;
    border: 0;
  }
  .evidence-list {
    display: grid;
    gap: 10px;
  }
  .evidence-list > button {
    display: flex;
    gap: 10px;
    align-items: center;
    padding: 12px 0;
    font-size: 13px;
    color: #667180;
    cursor: pointer;
    background: none;
    border: 0;
  }
  .evidence-list > button > span {
    flex: 1;
    text-align: left;
  }
  .evidence-preview {
    display: block;
    max-width: 100%;
    max-height: 70vh;
    margin: auto;
  }
  .detail-state {
    display: flex;
    min-height: 300px;
    flex-direction: column;
    gap: 16px;
    align-items: center;
    justify-content: center;
    font-size: 14px;
    color: #959ca7;
  }
  .is-spinning {
    animation: detail-spin 1s linear infinite;
  }
  @keyframes detail-spin {
    to {
      transform: rotate(360deg);
    }
  }
  @media (width <= 600px) {
    .detail-facts {
      grid-template-columns: 1fr;
    }
    .detail-summary {
      gap: 12px;
    }
    .detail-summary h2 {
      font-size: 20px;
    }
    .summary-amount strong {
      font-size: 22px;
    }
    .product-summary > .el-image {
      width: 90px;
      height: 90px;
    }
  }
</style>
