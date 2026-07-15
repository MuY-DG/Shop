<template>
  <HomeProductPlacementPage
    section="RECOMMENDED"
    title="首页推荐商品"
    write-auth="content:home-recommended:write"
  />
</template>

<script setup lang="ts">
  import HomeProductPlacementPage from '../components/home-product-placement-page.vue'

  defineOptions({ name: 'ContentHomeRecommend' })
</script>
