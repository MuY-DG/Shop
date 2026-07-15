<template>
  <HomeProductPlacementPage
    section="HOT"
    title="首页热门商品"
    write-auth="content:home-hot:write"
  />
</template>

<script setup lang="ts">
  import HomeProductPlacementPage from '../components/home-product-placement-page.vue'

  defineOptions({ name: 'ContentHomeHot' })
</script>
