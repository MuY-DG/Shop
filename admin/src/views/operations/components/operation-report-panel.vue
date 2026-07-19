<template>
  <section
    class="art-card operation-report-panel"
    :class="{ 'is-empty': !loading && (availability !== 'AVAILABLE' || empty) }"
    v-loading="loading"
  >
    <header class="operation-report-panel__header">
      <div>
        <h3>{{ title }}</h3>
        <p v-if="subtitle">{{ subtitle }}</p>
      </div>
      <slot name="actions"></slot>
    </header>

    <ElSkeleton v-if="loading" :rows="5" animated class="operation-report-panel__skeleton" />
    <ElEmpty
      v-else-if="availability !== 'AVAILABLE'"
      :description="unavailableDescription"
      :image-size="56"
    />
    <ElEmpty v-else-if="empty" description="当前周期暂无数据" :image-size="56" />
    <slot v-else></slot>
  </section>
</template>

<script setup lang="ts">
  defineOptions({ name: 'OperationReportPanel' })

  const props = withDefaults(
    defineProps<{
      title: string
      subtitle?: string
      loading?: boolean
      availability?: Api.Operations.Availability
      message?: string | null
      empty?: boolean
    }>(),
    {
      subtitle: '',
      loading: false,
      availability: 'AVAILABLE',
      message: '',
      empty: false
    }
  )

  const unavailableDescription = computed(() => {
    if (props.message) return props.message
    if (props.availability === 'NOT_COLLECTED') return '该数据尚未开始采集'
    if (props.availability === 'DELAYED') return '数据仍在汇总，请稍后刷新'
    return '该统计暂不适用'
  })
</script>

<style scoped lang="scss">
  .operation-report-panel {
    min-height: 12rem;
    padding: 20px;
    margin-bottom: 20px;

    &.is-empty {
      min-height: 14rem;
    }

    :deep(.el-empty) {
      padding: 22px 0 12px;
    }
  }

  .operation-report-panel__header {
    display: flex;
    gap: 16px;
    align-items: flex-start;
    justify-content: space-between;
    padding-bottom: 14px;
    margin-bottom: 14px;
    border-bottom: 1px solid var(--el-border-color-lighter);

    h3 {
      margin: 0;
      font-size: 16px;
      font-weight: 600;
    }

    p {
      margin: 4px 0 0;
      font-size: 12px;
      color: var(--art-gray-500);
    }
  }

  .operation-report-panel__skeleton {
    padding: 12px 0 4px;
  }
</style>
