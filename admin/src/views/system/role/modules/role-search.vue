<template>
  <ArtSearchBar
    ref="searchBarRef"
    v-model="formData"
    :items="formItems"
    @reset="emit('reset')"
    @search="handleSearch"
  />
</template>

<script setup lang="ts">
  type RoleSearchFormParams = Api.SystemManage.RoleSearchParams & { daterange?: string[] }

  const props = defineProps<{ modelValue: RoleSearchFormParams }>()
  const emit = defineEmits<{
    (e: 'update:modelValue', value: RoleSearchFormParams): void
    (e: 'search', params: RoleSearchFormParams): void
    (e: 'reset'): void
  }>()

  const searchBarRef = ref()
  const formData = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
  })

  const formItems = [
    {
      label: '角色名称',
      key: 'name',
      type: 'input',
      props: { placeholder: '请输入角色名称', clearable: true }
    },
    {
      label: '角色编码',
      key: 'code',
      type: 'input',
      props: { placeholder: '请输入角色编码', clearable: true }
    },
    {
      label: '角色状态',
      key: 'enabled',
      type: 'select',
      props: {
        placeholder: '请选择状态',
        clearable: true,
        options: [
          { label: '启用', value: true },
          { label: '禁用', value: false }
        ]
      }
    },
    {
      label: '创建日期',
      key: 'daterange',
      type: 'datetime',
      props: {
        type: 'daterange',
        valueFormat: 'YYYY-MM-DD',
        rangeSeparator: '至',
        startPlaceholder: '开始日期',
        endPlaceholder: '结束日期'
      }
    }
  ]

  const handleSearch = async (params: RoleSearchFormParams) => {
    await searchBarRef.value?.validate?.()
    emit('search', params)
  }
</script>
