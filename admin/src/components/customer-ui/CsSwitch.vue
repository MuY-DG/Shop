<template>
  <button
    type="button"
    role="switch"
    class="cs-switch"
    :class="{ 'cs-switch--checked': modelValue }"
    :aria-checked="modelValue"
    @click="$emit('update:modelValue', !modelValue)"
  >
    <span class="cs-switch__thumb" />
  </button>
</template>

<script setup lang="ts">
  defineProps<{ modelValue: boolean }>()
  defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<style scoped>
  .cs-switch {
    position: relative;
    width: 42px;
    height: 24px;
    padding: 2px;
    cursor: pointer;
    background: #cbd5e1;
    border: 0;
    border-radius: 999px;
    transition: background 0.18s ease;
  }

  .cs-switch:focus-visible {
    outline: 3px solid rgb(37 99 235 / 18%);
    outline-offset: 2px;
  }

  .cs-switch--checked {
    background: #2563eb;
  }

  .cs-switch__thumb {
    display: block;
    width: 20px;
    height: 20px;
    background: #fff;
    border-radius: 50%;
    box-shadow: 0 1px 3px rgb(15 23 42 / 24%);
    transition: transform 0.18s ease;
  }

  .cs-switch--checked .cs-switch__thumb {
    transform: translateX(18px);
  }
</style>
