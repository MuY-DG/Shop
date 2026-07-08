import type AssetPicker from './index.vue'

type AssetPickerProps = InstanceType<typeof AssetPicker>['$props']

const visibilityPropContract = {
  visibility: 'PRIVATE'
} satisfies Pick<AssetPickerProps, 'visibility'>

void visibilityPropContract
