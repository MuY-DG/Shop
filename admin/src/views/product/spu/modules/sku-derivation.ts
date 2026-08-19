import type { ProductEditorSku } from './editor-model'

const MASS_UNIT_PATTERN = /^\s*(\d+(?:\.\d+)?)\s*(kg|g|千克|公斤|克)\s*$/i

/**
 * 从净含量文案中提取克数，仅当整个文案是单一质量单位时生效。
 * 例如 "500g"、"1.5 kg"、"300克"；"500ml"、"500g×2袋" 等非质量或组合文案返回 null。
 */
export function parseNetContentGrams(text: string): number | null {
  const match = MASS_UNIT_PATTERN.exec(text.trim())
  if (!match) return null
  const amount = Number(match[1])
  if (!Number.isFinite(amount) || amount <= 0) return null
  const unit = match[2].toLowerCase()
  const grams = unit === 'kg' || unit === '千克' || unit === '公斤' ? amount * 1000 : amount
  return Math.round(grams)
}

/** 单规格商品的自动对外规格文案：跟随净含量，净含量为空则不展示。 */
export function autoSingleSpecText(sku: Pick<ProductEditorSku, 'netContentText'>): string {
  return sku.netContentText.trim()
}

/** 保存时解析单规格 specText：自定义过则用填写的值，否则使用自动文案。 */
export function resolveSingleSpecText(
  sku: Pick<ProductEditorSku, 'specText' | 'specTextCustomized' | 'netContentText'>
): string {
  if (sku.specTextCustomized) return sku.specText.trim()
  return autoSingleSpecText(sku)
}
