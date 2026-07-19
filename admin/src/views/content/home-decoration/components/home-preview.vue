<template>
  <aside class="preview-card">
    <header class="preview-card__header">
      <div>
        <div class="preview-card__eyebrow">展示效果</div>
        <div class="preview-card__title">小程序首页预览</div>
      </div>
      <ElButton circle plain :loading="loading" aria-label="刷新预览" @click="emit('refresh')">
        <ArtSvgIcon icon="ri:refresh-line" />
      </ElButton>
    </header>

    <div class="phone-shell">
      <div class="phone-speaker" />
      <div class="phone-screen" v-loading="loading">
        <div class="mini-status-bar" aria-hidden="true">
          <span>09:41</span>
          <span class="mini-capsule"><i /><i /><i /><b /></span>
        </div>

        <main class="phone-content">
          <button
            class="preview-section hero-section"
            type="button"
            aria-label="编辑首页轮播图"
            @click="emit('select', 'banner')"
          >
            <template v-if="banners.length">
              <img :src="banners[0].imageUrl" :alt="banners[0].title || '首页轮播图'" />
              <div class="hero-top-wash" />
              <div class="hero-fade" />
              <div class="banner-dots" aria-hidden="true">
                <span
                  v-for="banner in banners.slice(0, 5)"
                  :key="banner.id"
                  :class="{ 'is-current': banner.id === banners[0].id }"
                />
              </div>
            </template>
            <div v-else class="preview-placeholder preview-placeholder--banner">
              <ArtSvgIcon icon="ri:image-add-line" />
              <span>添加首页轮播图</span>
            </div>
          </button>

          <div class="content-layer">
            <i class="ink-halo ink-halo--top" />
            <i class="ink-halo ink-halo--bottom" />

            <button
              class="preview-section category-section"
              type="button"
              aria-label="编辑首页分类"
              @click="emit('select', 'category')"
            >
              <i class="category-wash" />
              <div v-if="categories.length" class="category-grid">
                <div
                  v-for="category in categories.slice(0, 8)"
                  :key="category.id"
                  class="category-item"
                >
                  <img :src="category.imageUrl" :alt="category.categoryName" />
                  <span>{{ category.categoryName }}</span>
                </div>
              </div>
              <div v-else class="preview-placeholder preview-placeholder--compact">
                <ArtSvgIcon icon="ri:function-add-line" />
                <span>添加首页分类</span>
              </div>
            </button>

            <button
              class="preview-section product-section popular-section"
              type="button"
              aria-label="编辑首页热门商品"
              @click="emit('select', 'hot')"
            >
              <div class="section-heading">
                <div class="section-heading__left">
                  <span class="flame-mark"><i /></span>
                  <span class="section-copy">
                    <span class="section-title-row">
                      <strong>热门商品</strong>
                      <i class="title-rule" />
                    </span>
                    <small>大家都在买的品质好物</small>
                  </span>
                </div>
                <span class="section-more">查看全部 <b>›</b></span>
              </div>

              <div v-if="hotProducts.length" class="popular-grid">
                <div
                  v-for="(product, index) in hotProducts.slice(0, 3)"
                  :key="product.id"
                  class="popular-card"
                >
                  <div class="popular-image-wrap">
                    <img :src="product.displayImageUrl" :alt="product.productTitle" />
                    <i class="image-fade" />
                    <span class="marketing-tag" :class="`marketing-tag--${index}`">
                      {{ popularTags[index] }}
                    </span>
                  </div>
                  <div class="popular-info">
                    <strong>{{ product.productTitle }}</strong>
                    <small>{{ product.productSubtitle || product.categoryName }}</small>
                    <span class="product-spec">{{ product.categoryName }}</span>
                    <div class="price-row">
                      <span class="price">
                        <small>¥</small>{{ formatPreviewPrice(product.minPriceCent) }}
                      </span>
                      <span class="add-button"><i /><b /></span>
                    </div>
                  </div>
                </div>
              </div>
              <div v-else class="preview-placeholder preview-placeholder--compact">
                <ArtSvgIcon icon="ri:shopping-bag-3-line" />
                <span>添加热门商品</span>
              </div>
            </button>

            <button
              class="preview-section product-section recommend-section"
              type="button"
              aria-label="编辑首页推荐商品"
              @click="emit('select', 'recommended')"
            >
              <div class="section-heading">
                <div class="section-heading__left">
                  <span class="recommend-mark">荐</span>
                  <span class="section-copy">
                    <span class="section-title-row">
                      <strong>为你推荐</strong>
                      <i class="title-rule" />
                    </span>
                    <small>更多精选品质好物</small>
                  </span>
                </div>
                <span class="section-more">查看全部 <b>›</b></span>
              </div>

              <div v-if="recommendedProducts.length" class="recommend-grid">
                <div
                  v-for="(product, index) in recommendedProducts.slice(0, 4)"
                  :key="product.id"
                  class="recommend-card"
                >
                  <div class="recommend-image-wrap">
                    <img :src="product.displayImageUrl" :alt="product.productTitle" />
                    <i class="image-fade" />
                    <span class="campaign-tag" :class="`campaign-tag--${index}`">
                      {{ recommendTags[index] }}
                    </span>
                  </div>
                  <div class="recommend-info">
                    <strong>{{ product.productTitle }}</strong>
                    <small>{{ product.productSubtitle || product.categoryName }}</small>
                    <span class="product-spec">{{ product.categoryName }}</span>
                    <div class="price-row">
                      <span class="price">
                        <small>¥</small>{{ formatPreviewPrice(product.minPriceCent) }}
                      </span>
                      <span class="add-button add-button--small"><i /><b /></span>
                    </div>
                  </div>
                </div>
              </div>
              <div v-else class="preview-placeholder preview-placeholder--compact">
                <ArtSvgIcon icon="ri:star-smile-line" />
                <span>添加推荐商品</span>
              </div>
            </button>

            <div class="bottom-ornament" aria-hidden="true">
              <i />
              <span>匠心甄选 · 品质生活</span>
              <i />
            </div>
          </div>
        </main>

        <nav class="mini-tabbar" aria-label="小程序底部导航">
          <span class="is-current"><ArtSvgIcon icon="ri:home-5-fill" />首页</span>
          <span><ArtSvgIcon icon="ri:apps-2-line" />分类</span>
          <span><ArtSvgIcon icon="ri:shopping-cart-2-line" />购物车</span>
          <span><ArtSvgIcon icon="ri:user-3-line" />我的</span>
        </nav>
      </div>
    </div>

    <div class="preview-card__tip">
      <ArtSvgIcon icon="ri:cursor-line" />
      点击预览中的模块，可快速滚动到对应编辑区
    </div>
  </aside>
</template>

<script setup lang="ts">
  type HomeDecorationSection = 'banner' | 'category' | 'hot' | 'recommended'

  defineProps<{
    loading: boolean
    banners: Api.Content.BannerItem[]
    categories: Api.Content.HomeCategoryItem[]
    hotProducts: Api.Content.HomeProductItem[]
    recommendedProducts: Api.Content.HomeProductItem[]
  }>()

  const emit = defineEmits<{
    refresh: []
    select: [section: HomeDecorationSection]
  }>()

  const popularTags = ['TOP 1', '人气爆款', '回购推荐']
  const recommendTags = ['新品', '限时优惠', '精选好物', '组合推荐']
  const formatPreviewPrice = (priceCent?: number | null) =>
    priceCent == null ? '-' : (priceCent / 100).toFixed(2)
</script>

<style scoped lang="scss">
  .preview-card {
    position: sticky;
    top: 12px;
    padding: 18px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 18px;
    box-shadow: 0 12px 36px rgb(24 40 72 / 8%);

    &__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 16px;
    }

    &__eyebrow {
      margin-bottom: 3px;
      font-size: 11px;
      font-weight: 700;
      color: var(--el-color-primary);
      letter-spacing: 0.12em;
    }

    &__title {
      font-size: 17px;
      font-weight: 700;
      color: var(--el-text-color-primary);
    }

    &__tip {
      display: flex;
      gap: 6px;
      align-items: center;
      justify-content: center;
      margin-top: 14px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  .phone-shell {
    width: 100%;
    max-width: 352px;
    padding: 10px;
    margin: 0 auto;
    background: #17191e;
    border: 1px solid rgb(255 255 255 / 16%);
    border-radius: 34px;
    box-shadow:
      0 18px 36px rgb(41 25 16 / 23%),
      inset 0 0 0 1px rgb(255 255 255 / 5%);
  }

  .phone-speaker {
    width: 58px;
    height: 4px;
    margin: 2px auto 8px;
    background: #343a47;
    border-radius: 999px;
  }

  .phone-screen {
    position: relative;
    height: min(716px, calc(100vh - 210px));
    min-height: 560px;
    overflow: hidden;
    color: #2e1d16;
    background: #f7eddf;
    border-radius: 24px;
  }

  .mini-status-bar {
    position: absolute;
    top: 0;
    right: 0;
    left: 0;
    z-index: 12;
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 34px;
    padding: 0 13px;
    font-size: 9px;
    font-weight: 700;
    color: #34241c;
    pointer-events: none;
  }

  .mini-capsule {
    display: flex;
    gap: 2px;
    align-items: center;
    height: 20px;
    padding: 0 7px;
    background: rgb(255 251 244 / 72%);
    backdrop-filter: blur(6px);
    border: 1px solid rgb(91 62 43 / 10%);
    border-radius: 999px;

    i {
      width: 2px;
      height: 2px;
      background: #34241c;
      border-radius: 50%;
    }

    b {
      width: 5px;
      height: 5px;
      margin-left: 5px;
      border: 1px solid #34241c;
      border-radius: 50%;
    }
  }

  .phone-content {
    height: calc(100% - 54px);
    overflow: auto;
    background:
      radial-gradient(circle at 94% 4%, rgb(183 43 34 / 6%), transparent 29%),
      radial-gradient(circle at 2% 54%, rgb(115 80 48 / 4.5%), transparent 27%),
      linear-gradient(180deg, #faf2e4 0%, #f5eadb 50%, #f8f0e5 100%);
    scrollbar-width: none;

    &::-webkit-scrollbar {
      display: none;
    }
  }

  .preview-section {
    position: relative;
    display: block;
    padding: 0;
    overflow: hidden;
    font: inherit;
    color: inherit;
    text-align: left;
    cursor: pointer;
    border: 0;

    &::after {
      position: absolute;
      inset: 0;
      z-index: 10;
      pointer-events: none;
      content: '';
      border: 2px solid transparent;
      border-radius: inherit;
      transition:
        border-color 0.18s ease,
        box-shadow 0.18s ease;
    }

    &:hover::after {
      border-color: var(--el-color-primary);
      box-shadow: inset 0 0 0 1px rgb(255 255 255 / 60%);
    }

    &:focus-visible {
      outline: 2px solid var(--el-color-primary);
      outline-offset: -2px;
    }
  }

  .hero-section {
    width: 100%;
    height: 290px;
    background: #f4dfba;

    > img {
      display: block;
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    &::after {
      border-radius: 0;
    }
  }

  .hero-top-wash,
  .hero-fade {
    position: absolute;
    right: 0;
    left: 0;
    z-index: 2;
    pointer-events: none;
  }

  .hero-top-wash {
    top: 0;
    height: 40px;
    background: linear-gradient(180deg, rgb(255 247 225 / 22%), transparent);
  }

  .hero-fade {
    bottom: 0;
    height: 32px;
    background: linear-gradient(180deg, rgb(246 237 223 / 0%), #f6eddf 96%);
  }

  .banner-dots {
    position: absolute;
    bottom: 18px;
    left: 50%;
    z-index: 4;
    display: flex;
    gap: 6px;
    align-items: center;
    height: 18px;
    padding: 0 8px;
    background: rgb(255 249 238 / 62%);
    backdrop-filter: blur(6px);
    border: 1px solid rgb(255 255 255 / 28%);
    border-radius: 999px;
    box-shadow: 0 3px 10px rgb(72 38 20 / 8%);
    transform: translateX(-50%);

    span {
      width: 4px;
      height: 4px;
      background: rgb(71 44 31 / 32%);
      border-radius: 999px;

      &.is-current {
        width: 6px;
        height: 6px;
        background: #b72b22;
        box-shadow: 0 1px 5px rgb(183 43 34 / 25%);
      }
    }
  }

  .content-layer {
    position: relative;
    z-index: 4;
    min-height: 400px;
    padding-bottom: 28px;
    margin-top: -23px;
  }

  .ink-halo {
    position: absolute;
    z-index: -1;
    width: 90px;
    height: 90px;
    pointer-events: none;
    background: radial-gradient(circle, rgb(176 112 66 / 8%), transparent 70%);
    border-radius: 50%;

    &--top {
      top: 28px;
      right: -38px;
    }

    &--bottom {
      top: 380px;
      left: -44px;
      background: radial-gradient(circle, rgb(103 94 57 / 8%), transparent 70%);
    }
  }

  .category-section {
    width: calc(100% - 20px);
    min-height: 82px;
    padding: 9px 6px 8px;
    margin: 0 10px;
    background: rgb(255 251 244 / 97%);
    border: 1px solid rgb(184 139 75 / 14%);
    border-radius: 19px;
    box-shadow: 0 10px 29px rgb(78 43 20 / 9.5%);
  }

  .category-wash {
    position: absolute;
    top: -31px;
    right: -21px;
    width: 90px;
    height: 90px;
    pointer-events: none;
    background: radial-gradient(circle, rgb(183 43 34 / 6.5%), transparent 68%);
    border-radius: 50%;
  }

  .category-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    row-gap: 6px;
  }

  .category-item {
    position: relative;
    z-index: 1;
    display: grid;
    gap: 1px;
    justify-items: center;
    min-width: 0;

    img {
      display: block;
      width: 44px;
      height: 44px;
      object-fit: contain;
    }

    span {
      max-width: 100%;
      overflow: hidden;
      font-size: 9px;
      font-weight: 500;
      color: #3d2920;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .product-section {
    width: calc(100% - 20px);
    margin-right: 10px;
    margin-left: 10px;
    overflow: visible;
    background: transparent;
    border-radius: 12px;
  }

  .popular-section {
    margin-top: 18px;
  }

  .recommend-section {
    margin-top: 24px;
  }

  .section-heading,
  .section-heading__left,
  .section-title-row,
  .section-more,
  .price-row {
    display: flex;
    align-items: center;
  }

  .section-heading {
    justify-content: space-between;
    height: 42px;
    padding: 0 2px;
  }

  .section-copy {
    display: grid;
    gap: 1px;
    margin-left: 7px;

    strong {
      font-size: 15px;
      line-height: 18px;
    }

    > small {
      font-size: 9px;
      color: #8d7464;
      letter-spacing: 0.1px;
    }
  }

  .title-rule {
    width: 21px;
    height: 1px;
    margin-left: 6px;
    background: linear-gradient(90deg, rgb(184 139 75 / 58%), transparent);
  }

  .flame-mark {
    position: relative;
    width: 14px;
    height: 17px;
    margin: 0 1px 2px 3px;
    background: linear-gradient(135deg, #ef6a38, #b71918 72%);
    border-radius: 12px 3px 12px 12px;
    box-shadow: 0 2px 5px rgb(183 43 34 / 20%);
    transform: rotate(-45deg);

    i {
      position: absolute;
      right: 3px;
      bottom: 3px;
      width: 5px;
      height: 8px;
      background: #ffd38a;
      border-radius: 6px 2px 6px 6px;
    }
  }

  .recommend-mark {
    display: grid;
    place-items: center;
    width: 18px;
    height: 18px;
    font-family: 'Songti SC', STSong, serif;
    font-size: 10px;
    font-weight: 700;
    color: #b72b22;
    background: rgb(255 251 244 / 72%);
    border: 1px solid rgb(183 43 34 / 50%);
    border-radius: 50% 50% 46% 54%;
    transform: rotate(-5deg);
  }

  .section-more {
    justify-content: flex-end;
    min-width: 68px;
    height: 36px;
    font-size: 9px;
    font-weight: 500;
    color: #85644d;

    b {
      margin-left: 4px;
      font-family: Arial, sans-serif;
      font-size: 16px;
      font-weight: 300;
      color: #b72b22;
    }
  }

  .popular-grid {
    display: grid;
    grid-auto-columns: calc((100% - 14px) / 3);
    grid-auto-flow: column;
    gap: 7px;
    margin-top: 5px;
    overflow: hidden;
  }

  .popular-card,
  .recommend-card {
    min-width: 0;
    overflow: hidden;
    background: #fffbf4;
    border: 1px solid rgb(184 139 75 / 12%);
    border-radius: 14px;
    box-shadow: 0 6px 17px rgb(78 43 20 / 8.5%);
  }

  .popular-image-wrap,
  .recommend-image-wrap {
    position: relative;
    width: 100%;
    overflow: hidden;
    background: #f4e8d5;

    img {
      display: block;
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  .popular-image-wrap {
    height: 92px;
  }

  .image-fade {
    position: absolute;
    right: 0;
    bottom: 0;
    left: 0;
    height: 18px;
    pointer-events: none;
    background: linear-gradient(180deg, rgb(255 251 244 / 0%), rgb(255 251 244 / 94%));
  }

  .marketing-tag,
  .campaign-tag {
    position: absolute;
    top: 6px;
    left: 5px;
    display: flex;
    align-items: center;
    height: 17px;
    padding: 0 6px;
    font-size: 7px;
    font-weight: 600;
    line-height: 17px;
    color: #fffdf8;
    background: linear-gradient(100deg, #6e1713, #bd3a25 68%, #ba8b4d);
    border: 1px solid rgb(255 255 255 / 34%);
    border-radius: 999px;
    box-shadow: 0 2px 6px rgb(76 24 16 / 17%);

    &--1 {
      background: linear-gradient(100deg, #a81617, #db442e);
    }

    &--2 {
      background: linear-gradient(100deg, #733025, #aa5540);
    }
  }

  .popular-info,
  .recommend-info {
    display: grid;

    > strong,
    > small,
    > .product-spec {
      overflow: hidden;
      text-overflow: ellipsis;
    }

    > strong {
      font-size: 10px;
      font-weight: 650;
      line-height: 14px;
      color: #2f211a;
    }

    > small,
    > .product-spec {
      white-space: nowrap;
    }

    > small {
      margin-top: 2px;
      font-size: 8px;
      color: #7d6557;
    }

    > .product-spec {
      margin-top: 5px;
      font-size: 7px;
      color: #9a8477;
    }
  }

  .popular-info {
    padding: 7px;

    > strong {
      display: -webkit-box;
      height: 28px;
      white-space: normal;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }
  }

  .price-row {
    justify-content: space-between;
    min-width: 0;
    margin-top: 4px;
  }

  .price {
    font-family: 'DIN Alternate', 'Arial Narrow', Arial, sans-serif;
    font-size: 15px;
    font-weight: 700;
    color: #c92d22;
    letter-spacing: -0.4px;
    white-space: nowrap;

    small {
      margin-right: 1px;
      font-size: 9px;
    }
  }

  .add-button {
    position: relative;
    flex: 0 0 auto;
    width: 20px;
    height: 20px;
    background: linear-gradient(145deg, #d33c2c, #a91717);
    border-radius: 50%;
    box-shadow: 0 3px 8px rgb(183 43 34 / 24%);

    i,
    b {
      position: absolute;
      top: 50%;
      left: 50%;
      background: #fffdf7;
      border-radius: 999px;
      transform: translate(-50%, -50%);
    }

    i {
      width: 9px;
      height: 1px;
    }

    b {
      width: 1px;
      height: 9px;
    }

    &--small {
      width: 22px;
      height: 22px;
    }
  }

  .recommend-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
    margin-top: 5px;
  }

  .recommend-image-wrap {
    height: 122px;
  }

  .campaign-tag {
    top: 7px;
    left: 7px;
    height: 19px;
    padding: 0 7px;
    font-size: 8px;
    background: linear-gradient(100deg, #476143, #718161);

    &--1 {
      background: linear-gradient(100deg, #b91f1c, #dd4a2e);
    }

    &--2 {
      background: linear-gradient(100deg, #a33c25, #cf6c39);
    }

    &--3 {
      background: linear-gradient(100deg, #7b351d, #b88b4b);
    }
  }

  .recommend-info {
    padding: 8px 9px;

    > strong {
      white-space: nowrap;
    }
  }

  .bottom-ornament {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 58px;
    font-family: 'Songti SC', STSong, serif;
    font-size: 8px;
    color: rgb(104 76 59 / 50%);
    letter-spacing: 1.5px;

    i {
      width: 27px;
      height: 1px;
      margin: 0 9px;
      background: linear-gradient(90deg, transparent, rgb(184 139 75 / 42%));

      &:last-child {
        transform: rotate(180deg);
      }
    }
  }

  .preview-placeholder {
    display: grid;
    gap: 7px;
    place-items: center;
    align-content: center;
    min-height: 82px;
    font-size: 10px;
    color: #9a8477;
    background: repeating-linear-gradient(135deg, #fffaf2 0 8px, #f9efe2 8px 16px);

    :deep(.art-svg-icon) {
      font-size: 23px;
    }

    &--banner {
      height: 100%;
    }

    &--compact {
      min-height: 74px;
      border-radius: 12px;
    }
  }

  .mini-tabbar {
    position: absolute;
    right: 0;
    bottom: 0;
    left: 0;
    z-index: 20;
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    height: 54px;
    padding-top: 5px;
    background: rgb(255 251 244 / 88%);
    backdrop-filter: blur(10px);
    border-top: 1px solid rgb(139 96 57 / 13%);
    box-shadow: 0 -6px 17px rgb(68 37 22 / 6.5%);

    span {
      display: grid;
      gap: 1px;
      justify-items: center;
      font-size: 9px;
      font-weight: 500;
      color: #8d8179;

      :deep(.art-svg-icon) {
        font-size: 18px;
      }

      &.is-current {
        font-weight: 650;
        color: #b72b22;
      }
    }
  }

  @media (width <= 1260px) {
    .preview-card {
      position: static;
    }

    .phone-screen {
      height: 680px;
    }
  }
</style>
