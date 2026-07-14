import type AssetPicker from './index.vue'

type AssetPickerProps = InstanceType<typeof AssetPicker>['$props']

const mediaKindPropContract = {
  mediaKind: 'IMAGE'
} satisfies Pick<AssetPickerProps, 'mediaKind'>

void mediaKindPropContract
