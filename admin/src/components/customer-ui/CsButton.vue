<template>
  <button
    :type="type"
    :disabled="disabled || loading"
    class="cs-button"
    :class="[`cs-button--${variant}`, `cs-button--${size}`]"
  >
    <LoaderCircle v-if="loading" class="cs-button__spinner" :size="16" />
    <slot />
  </button>
</template>

<script setup lang="ts">
  import { LoaderCircle } from '@lucide/vue'

  withDefaults(
    defineProps<{
      type?: 'button' | 'submit' | 'reset'
      variant?: 'primary' | 'outline' | 'ghost' | 'danger'
      size?: 'sm' | 'md' | 'icon'
      disabled?: boolean
      loading?: boolean
    }>(),
    {
      type: 'button',
      variant: 'primary',
      size: 'md',
      disabled: false,
      loading: false
    }
  )
</script>

<style scoped>
  .cs-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    border: 1px solid transparent;
    border-radius: 10px;
    font-size: 14px;
    font-weight: 600;
    line-height: 1;
    transition:
      color 0.16s ease,
      background 0.16s ease,
      border-color 0.16s ease,
      box-shadow 0.16s ease;
  }

  .cs-button:focus-visible {
    outline: 3px solid rgb(37 99 235 / 18%);
    outline-offset: 2px;
  }

  .cs-button:disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }

  .cs-button--md {
    min-height: 40px;
    padding: 0 16px;
  }

  .cs-button--sm {
    min-height: 34px;
    padding: 0 12px;
  }

  .cs-button--icon {
    width: 38px;
    height: 38px;
  }

  .cs-button--primary {
    color: #fff;
    background: #2563eb;
    box-shadow: 0 1px 2px rgb(15 23 42 / 8%);
  }

  .cs-button--primary:hover:not(:disabled) {
    background: #1d4ed8;
  }

  .cs-button--outline {
    color: #0f172a;
    background: #fff;
    border-color: #e2e8f0;
  }

  .cs-button--outline:hover:not(:disabled),
  .cs-button--ghost:hover:not(:disabled) {
    background: #f8fafc;
  }

  .cs-button--ghost {
    color: #475569;
    background: transparent;
  }

  .cs-button--danger {
    color: #b91c1c;
    background: #fef2f2;
    border-color: #fecaca;
  }

  .cs-button__spinner {
    animation: cs-spin 0.9s linear infinite;
  }

  @keyframes cs-spin {
    to {
      transform: rotate(360deg);
    }
  }
</style>
