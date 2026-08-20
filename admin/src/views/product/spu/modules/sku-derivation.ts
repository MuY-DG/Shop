import type { ProductEditorSku } from './editor-model'

/** 常用包装单位选项；也允许运营自行输入其他单位。 */
export const PACK_UNIT_OPTIONS = ['袋', '盒', '箱', '瓶', '罐', '支', '枚', '包', '桶']

/** 包装单位未选择时的默认值。 */
export const DEFAULT_PACK_UNIT = '袋'

/**
 * 单规格商品的自动对外规格文案：净含量 + 包装单位（如 500g/袋）。
 * 净含量为空时不生成；包装单位未选择时默认使用「袋」。
 */
export function autoSingleSpecText(
  sku: Pick<ProductEditorSku, 'netContentText' | 'packUnitText'>
): string {
  const netContent = sku.netContentText.trim()
  if (!netContent) return ''
  const unit = (sku.packUnitText || '').trim() || DEFAULT_PACK_UNIT
  return `${netContent}/${unit}`
}

/** 保存时解析单规格 specText：自定义过则用填写的值，否则使用自动文案。 */
export function resolveSingleSpecText(
  sku: Pick<ProductEditorSku, 'specText' | 'specTextCustomized' | 'netContentText' | 'packUnitText'>
): string {
  if (sku.specTextCustomized) return sku.specText.trim()
  return autoSingleSpecText(sku)
}
