<template>
  <div class="home-decoration-page">
    <div v-if="visibleSections.length" class="decoration-workspace">
      <section class="editor-column" aria-label="首页装修内容编辑区">
        <article v-if="canRead('banner')" id="home-decoration-banner" class="decoration-section">
          <header class="decoration-section__header">
            <span class="decoration-section__icon decoration-section__icon--banner">
              <ArtSvgIcon icon="ri:image-2-line" />
            </span>
            <span class="decoration-section__copy">
              <strong>轮播图</strong>
              <small>首屏广告与活动入口</small>
            </span>
            <span class="decoration-section__count">
              {{ getVisibleCount('banner') }}/{{ getTotalCount('banner') }} 展示
            </span>
          </header>
          <BannerEditor embedded @changed="loadPreview" />
        </article>

        <article
          v-if="canRead('category')"
          id="home-decoration-category"
          class="decoration-section"
        >
          <header class="decoration-section__header">
            <span class="decoration-section__icon decoration-section__icon--category">
              <ArtSvgIcon icon="ri:function-line" />
            </span>
            <span class="decoration-section__copy">
              <strong>首页分类</strong>
              <small>快捷分类导航</small>
            </span>
            <span class="decoration-section__count">
              {{ getVisibleCount('category') }}/{{ getTotalCount('category') }} 展示
            </span>
          </header>
          <HomeCategoryEditor embedded @changed="loadPreview" />
        </article>

        <article v-if="canRead('hot')" id="home-decoration-hot" class="decoration-section">
          <header class="decoration-section__header">
            <span class="decoration-section__icon decoration-section__icon--hot">
              <ArtSvgIcon icon="ri:fire-line" />
            </span>
            <span class="decoration-section__copy">
              <strong>热门商品</strong>
              <small>重点爆款陈列</small>
            </span>
            <span class="decoration-section__count">
              {{ getVisibleCount('hot') }}/{{ getTotalCount('hot') }} 展示
            </span>
          </header>
          <HomeProductPlacementPage
            section="HOT"
            title="首页热门商品"
            write-auth="content:home-hot:write"
            embedded
            @changed="loadPreview"
          />
        </article>

        <article
          v-if="canRead('recommended')"
          id="home-decoration-recommended"
          class="decoration-section"
        >
          <header class="decoration-section__header">
            <span class="decoration-section__icon decoration-section__icon--recommended">
              <ArtSvgIcon icon="ri:star-smile-line" />
            </span>
            <span class="decoration-section__copy">
              <strong>推荐商品</strong>
              <small>首页商品主列表</small>
            </span>
            <span class="decoration-section__count">
              {{ getVisibleCount('recommended') }}/{{ getTotalCount('recommended') }} 展示
            </span>
          </header>
          <HomeProductPlacementPage
            section="RECOMMENDED"
            title="首页推荐商品"
            write-auth="content:home-recommended:write"
            embedded
            @changed="loadPreview"
          />
        </article>
      </section>

      <HomePreview
        :loading="previewLoading"
        :banners="visibleBanners"
        :categories="visibleCategories"
        :hot-products="visibleHotProducts"
        :recommended-products="visibleRecommendedProducts"
        @refresh="loadPreview"
        @select="scrollToSection"
      />
    </div>

    <ElCard v-else>
      <ElEmpty description="当前账号暂无首页装修查看权限" />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue'
  import { useAuth } from '@/hooks'
  import { fetchHomeBanners, fetchHomeCategories, fetchHomeProducts } from '@/api/content'
  import BannerEditor from '../banner/index.vue'
  import HomeCategoryEditor from '../home-category/index.vue'
  import HomeProductPlacementPage from '../components/home-product-placement-page.vue'
  import HomePreview from './components/home-preview.vue'

  defineOptions({ name: 'ContentHomeDecoration' })

  type HomeDecorationSection = 'banner' | 'category' | 'hot' | 'recommended'

  interface SectionConfig {
    key: HomeDecorationSection
    readAuth: string
  }

  const { hasAuth } = useAuth()

  const sectionConfigs: SectionConfig[] = [
    { key: 'banner', readAuth: 'content:banner:read' },
    { key: 'category', readAuth: 'content:home-category:read' },
    { key: 'hot', readAuth: 'content:home-hot:read' },
    { key: 'recommended', readAuth: 'content:home-recommended:read' }
  ]

  const visibleSections = computed(() =>
    sectionConfigs.filter((section) => hasAuth(section.readAuth))
  )
  const canRead = (section: HomeDecorationSection) =>
    visibleSections.value.some((item) => item.key === section)

  const previewLoading = ref(false)
  const banners = ref<Api.Content.BannerItem[]>([])
  const categories = ref<Api.Content.HomeCategoryItem[]>([])
  const hotProducts = ref<Api.Content.HomeProductItem[]>([])
  const recommendedProducts = ref<Api.Content.HomeProductItem[]>([])

  const bySortOrder = <T extends { id: number; sortOrder: number }>(left: T, right: T) =>
    left.sortOrder - right.sortOrder || right.id - left.id

  const isBannerInEffect = (banner: Api.Content.BannerItem) => {
    const now = Date.now()
    const startAt = banner.startAt ? Date.parse(banner.startAt) : null
    const endAt = banner.endAt ? Date.parse(banner.endAt) : null
    return (
      banner.status === 'ENABLED' &&
      (startAt == null || Number.isNaN(startAt) || startAt <= now) &&
      (endAt == null || Number.isNaN(endAt) || endAt > now)
    )
  }

  const visibleBanners = computed(() => banners.value.filter(isBannerInEffect).sort(bySortOrder))
  const visibleCategories = computed(() =>
    categories.value
      .filter((item) => item.status === 'ENABLED' && item.categoryStatus === 'ENABLED')
      .sort(bySortOrder)
  )
  const visibleHotProducts = computed(() =>
    hotProducts.value
      .filter((item) => item.status === 'ENABLED' && item.productStatus === 'ON_SALE')
      .sort(bySortOrder)
  )
  const visibleRecommendedProducts = computed(() =>
    recommendedProducts.value
      .filter((item) => item.status === 'ENABLED' && item.productStatus === 'ON_SALE')
      .sort(bySortOrder)
  )

  const getTotalCount = (section: HomeDecorationSection) => {
    const map: Record<HomeDecorationSection, number> = {
      banner: banners.value.length,
      category: categories.value.length,
      hot: hotProducts.value.length,
      recommended: recommendedProducts.value.length
    }
    return map[section]
  }

  const getVisibleCount = (section: HomeDecorationSection) => {
    const map: Record<HomeDecorationSection, number> = {
      banner: visibleBanners.value.length,
      category: visibleCategories.value.length,
      hot: visibleHotProducts.value.length,
      recommended: visibleRecommendedProducts.value.length
    }
    return map[section]
  }

  const loadPreview = async () => {
    previewLoading.value = true
    try {
      const bannerPromise: Promise<Api.Content.BannerItem[]> = canRead('banner')
        ? fetchHomeBanners({ current: 1, size: 100 }).then((response) => response.records)
        : Promise.resolve([])
      const categoryPromise: Promise<Api.Content.HomeCategoryItem[]> = canRead('category')
        ? fetchHomeCategories()
        : Promise.resolve([])
      const hotPromise: Promise<Api.Content.HomeProductItem[]> = canRead('hot')
        ? fetchHomeProducts('HOT')
        : Promise.resolve([])
      const recommendedPromise: Promise<Api.Content.HomeProductItem[]> = canRead('recommended')
        ? fetchHomeProducts('RECOMMENDED')
        : Promise.resolve([])

      const [bannerItems, categoryItems, hotItems, recommendedItems] = await Promise.all([
        bannerPromise,
        categoryPromise,
        hotPromise,
        recommendedPromise
      ])
      banners.value = bannerItems
      categories.value = categoryItems
      hotProducts.value = hotItems
      recommendedProducts.value = recommendedItems
    } finally {
      previewLoading.value = false
    }
  }

  const scrollToSection = (section: HomeDecorationSection) => {
    if (!canRead(section)) return
    document
      .getElementById(`home-decoration-${section}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  onMounted(loadPreview)
</script>

<style scoped lang="scss">
  .home-decoration-page {
    min-height: var(--art-full-height);
  }

  .decoration-workspace {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 370px;
    gap: 16px;
    align-items: start;
  }

  .editor-column {
    display: grid;
    gap: 14px;
    min-width: 0;
  }

  .decoration-section {
    padding: 16px;
    overflow: hidden;
    scroll-margin-top: 12px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 18px;
    box-shadow: 0 8px 26px rgb(24 40 72 / 5%);

    &__header {
      display: grid;
      grid-template-columns: 42px minmax(0, 1fr) auto;
      gap: 11px;
      align-items: center;
      margin-bottom: 15px;
    }

    &__icon {
      display: grid;
      place-items: center;
      width: 42px;
      height: 42px;
      font-size: 20px;
      color: #3f72e6;
      background: #edf3ff;
      border-radius: 13px;

      &--category {
        color: #6f57d9;
        background: #f1eeff;
      }

      &--hot {
        color: #e95f3d;
        background: #fff0eb;
      }

      &--recommended {
        color: #d69525;
        background: #fff7df;
      }
    }

    &__copy {
      display: grid;
      gap: 2px;
      min-width: 0;

      strong {
        font-size: 15px;
        color: var(--el-text-color-primary);
      }

      small {
        overflow: hidden;
        font-size: 12px;
        color: var(--el-text-color-secondary);
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    &__count {
      padding: 4px 9px;
      font-size: 11px;
      color: var(--el-text-color-secondary);
      white-space: nowrap;
      background: var(--el-fill-color-light);
      border-radius: 999px;
    }
  }

  @media (width <= 1120px) {
    .decoration-workspace {
      grid-template-columns: 1fr;
    }
  }

  @media (width <= 640px) {
    .decoration-section {
      padding: 13px;

      &__header {
        grid-template-columns: 38px minmax(0, 1fr) auto;
      }

      &__icon {
        width: 38px;
        height: 38px;
      }
    }
  }
</style>
