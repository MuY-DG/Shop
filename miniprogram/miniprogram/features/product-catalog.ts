import { adaptProductFact, type ProductFactView } from "./product-facts";
import type { HomeProductFeature } from "../types/home";
import type {
  ProductCategory,
  ProductDetail,
  ProductListItem,
  ProductListQuery,
  ProductParameterValue,
  ProductSku,
  WholesaleTier
} from "../types/product";

export const PRODUCT_PAGE_SIZE = 10;

export interface CategoryTabView {
  id: number;
  name: string;
  selected: boolean;
}

export interface CatalogProductCardView {
  navigationPath: string;
  spuId: number;
  title: string;
  subtitle: string;
  imageUrl: string;
  hasImage: boolean;
  placeholder: string;
  priceText: string;
  hasPrice: boolean;
  priceIntegerText: string;
  priceDecimalText: string;
  rangePriceIntegerText: string;
  rangePriceDecimalText: string;
  hasPriceRange: boolean;
  priceSuffixText: string;
  originalPriceText: string;
  hasOriginalPrice: boolean;
  badgeText: string;
  badgeTone: "brand" | "orange" | "success" | "neutral" | "gold";
  features: ProductFactView[];
  wholesaleText: string;
  salesText: string;
}

export interface GalleryImageView {
  key: string;
  url: string;
  hasImage: boolean;
}

export interface ProductParameterView {
  id: number;
  name: string;
  value: string;
  fact: ProductFactView;
}

export interface SkuOptionView {
  id: number;
  name: string;
  priceText: string;
  stockText: string;
  imageUrl: string;
  hasImage: boolean;
  selected: boolean;
  disabled: boolean;
}

export interface SkuSpecificationOptionView {
  key: string;
  value: string;
  imageUrl: string;
  hasImage: boolean;
  selected: boolean;
  disabled: boolean;
}

export interface SkuSpecificationGroupView {
  key: string;
  name: string;
  hasImages: boolean;
  options: SkuSpecificationOptionView[];
}

export interface WholesaleTierView {
  minQuantity: number;
  priceText: string;
  active: boolean;
}

export interface PurchaseSelectionView {
  selectedSkuId: number;
  quantity: number;
  quantityMax: number;
  priceText: string;
  priceIntegerText: string;
  priceDecimalText: string;
  originalPriceText: string;
  hasOriginalPrice: boolean;
  stockText: string;
  wholesaleApplied: boolean;
  wholesaleHint: string;
  wholesaleTiers: WholesaleTierView[];
}

interface PriceParts {
  integerText: string;
  decimalText: string;
}

function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function positiveInteger(value: unknown): number | undefined {
  return typeof value === "number" &&
    Number.isSafeInteger(value) &&
    value > 0
    ? value
    : undefined;
}

function nonNegativeInteger(value: unknown): number | undefined {
  return typeof value === "number" &&
    Number.isSafeInteger(value) &&
    value >= 0
    ? value
    : undefined;
}

function priceParts(cent: number | undefined): PriceParts {
  if (cent === undefined) {
    return { integerText: "", decimalText: "" };
  }
  const [integerText, fraction = "00"] = (cent / 100).toFixed(2).split(".");
  return { integerText, decimalText: `.${fraction}` };
}

export function formatMoney(cent: unknown): string {
  const normalized = nonNegativeInteger(cent);
  return normalized === undefined ? "" : (normalized / 100).toFixed(2);
}

export function parsePositiveId(value: unknown): number {
  if (typeof value === "string" && !/^\d+$/.test(value.trim())) {
    return 0;
  }
  return positiveInteger(Number(value)) ?? 0;
}

export function normalizeProductKeyword(value: unknown): string {
  return cleanText(value).replace(/\s+/g, " ").slice(0, 80);
}

export function buildProductListQuery(
  categoryId: unknown,
  keyword: unknown,
  current = 1
): ProductListQuery {
  const normalizedCategoryId = parsePositiveId(categoryId);
  const normalizedKeyword = normalizeProductKeyword(keyword);
  return {
    current: positiveInteger(current) ?? 1,
    size: PRODUCT_PAGE_SIZE,
    ...(normalizedCategoryId ? { categoryId: normalizedCategoryId } : {}),
    ...(normalizedKeyword ? { keyword: normalizedKeyword } : {})
  };
}

export function buildCategoryTabs(
  categories: ProductCategory[],
  activeCategoryId: number
): CategoryTabView[] {
  const seen = new Set<number>();
  const normalized = (Array.isArray(categories) ? categories : [])
    .filter((category) => {
      const id = positiveInteger(category?.id);
      const name = cleanText(category?.name);
      if (!id || !name || seen.has(id)) {
        return false;
      }
      seen.add(id);
      return true;
    })
    .map((category) => ({
      id: category.id,
      name: cleanText(category.name),
      selected: category.id === activeCategoryId
    }));
  return [
    { id: 0, name: "全部", selected: activeCategoryId === 0 },
    ...normalized
  ];
}

function toFact(parameter: ProductParameterValue): ProductFactView | undefined {
  const displayText = cleanText(parameter?.displayText);
  if (!displayText) {
    return undefined;
  }
  const level = parameter.selectedOptions
    ?.map((option) => nonNegativeInteger(option?.displayLevel))
    .find((value): value is number => value !== undefined);
  const feature: HomeProductFeature = {
    code: cleanText(parameter.parameterCode),
    name: cleanText(parameter.parameterName),
    displayText,
    renderer: cleanText(parameter.cardRenderer),
    ...(level !== undefined ? { level } : {})
  };
  return adaptProductFact(feature);
}

function productFacts(parameters: ProductParameterValue[]): ProductFactView[] {
  const seen = new Set<string>();
  return (Array.isArray(parameters) ? parameters : [])
    .slice()
    .sort((left, right) => {
      const roleOrder = (left.cardRole === "HIGHLIGHT" ? 0 : 1) -
        (right.cardRole === "HIGHLIGHT" ? 0 : 1);
      return roleOrder || (left.cardPriority ?? 0) - (right.cardPriority ?? 0);
    })
    .map(toFact)
    .filter((fact): fact is ProductFactView => {
      if (!fact || seen.has(fact.text)) {
        return false;
      }
      seen.add(fact.text);
      return true;
    })
    .slice(0, 3);
}

export function buildCatalogProductCard(
  product: ProductListItem
): CatalogProductCardView | undefined {
  const spuId = positiveInteger(product?.id);
  const title = cleanText(product?.title);
  if (!spuId || !title) {
    return undefined;
  }
  const minPrice = nonNegativeInteger(product.minPriceCent);
  const maxCandidate = nonNegativeInteger(product.maxPriceCent);
  const maxPrice = maxCandidate !== undefined && minPrice !== undefined && maxCandidate < minPrice
    ? undefined
    : maxCandidate;
  const primaryPrice = minPrice ?? maxPrice;
  const rangePrice = minPrice !== undefined && maxPrice !== undefined && maxPrice > minPrice
    ? maxPrice
    : undefined;
  const primaryParts = priceParts(primaryPrice);
  const rangeParts = priceParts(rangePrice);
  const imageUrl = cleanText(product.mainImage);
  const stock = nonNegativeInteger(product.totalStock) ?? 0;
  return {
    navigationPath: `/pages/product/detail/detail?id=${spuId}`,
    spuId,
    title,
    subtitle: cleanText(product.subtitle),
    imageUrl,
    hasImage: Boolean(imageUrl),
    placeholder: title.slice(0, 1),
    priceText: primaryPrice === undefined
      ? ""
      : rangePrice === undefined
        ? formatMoney(primaryPrice)
        : `${formatMoney(primaryPrice)}–${formatMoney(rangePrice)}`,
    hasPrice: primaryPrice !== undefined,
    priceIntegerText: primaryParts.integerText,
    priceDecimalText: primaryParts.decimalText,
    rangePriceIntegerText: rangeParts.integerText,
    rangePriceDecimalText: rangeParts.decimalText,
    hasPriceRange: rangePrice !== undefined,
    priceSuffixText: minPrice !== undefined && maxPrice === undefined ? "起" : "",
    originalPriceText: "",
    hasOriginalPrice: false,
    badgeText: stock > 0 ? "" : "暂时售罄",
    badgeTone: "neutral",
    features: productFacts(product.parameters),
    wholesaleText: "",
    salesText: stock > 0 ? `库存 ${stock}` : "补货中"
  };
}

export function buildCatalogProductCards(
  products: ProductListItem[]
): CatalogProductCardView[] {
  return (Array.isArray(products) ? products : [])
    .map(buildCatalogProductCard)
    .filter((product): product is CatalogProductCardView => Boolean(product));
}

export function buildGalleryImages(detail: ProductDetail): GalleryImageView[] {
  const candidates = Array.isArray(detail.images) ? detail.images : [];
  const urls = candidates
    .slice()
    .sort((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0))
    .map((image) => cleanText(image?.url))
    .filter(Boolean);
  const mainImage = cleanText(detail.mainImage);
  if (!urls.length && mainImage) {
    urls.push(mainImage);
  }
  const seen = new Set<string>();
  return urls
    .filter((url) => {
      if (seen.has(url)) {
        return false;
      }
      seen.add(url);
      return true;
    })
    .map((url, index) => ({ key: `${index}-${url}`, url, hasImage: true }));
}

export function buildParameterViews(
  parameters: ProductParameterValue[]
): ProductParameterView[] {
  return (Array.isArray(parameters) ? parameters : [])
    .map((parameter) => {
      const id = positiveInteger(parameter?.parameterId);
      const name = cleanText(parameter?.parameterName);
      const value = cleanText(parameter?.displayText);
      const fact = toFact(parameter);
      return id && name && value && fact ? { id, name, value, fact } : undefined;
    })
    .filter((parameter): parameter is ProductParameterView => Boolean(parameter));
}

function validSku(sku: ProductSku): boolean {
  return Boolean(positiveInteger(sku?.id)) &&
    sku.status === "ENABLED" &&
    (nonNegativeInteger(sku.stockAvailable) ?? 0) > 0 &&
    nonNegativeInteger(sku.priceCent) !== undefined;
}

function skuSpecificationEntries(sku: ProductSku): Array<[string, string]> {
  const specJson = cleanText(sku?.specJson);
  if (specJson) {
    try {
      const parsed = JSON.parse(specJson) as unknown;
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        const entries = Object.entries(parsed as Record<string, unknown>)
          .map(([rawName, rawValue]) => {
            const name = cleanText(rawName);
            const value = typeof rawValue === "string" || typeof rawValue === "number"
              ? String(rawValue).trim()
              : "";
            return [name, value] as [string, string];
          })
          .filter(([name, value]) => Boolean(name && value));
        if (entries.length) {
          return entries;
        }
      }
    } catch {
      // Legacy SKUs may carry malformed JSON; fall back to their display text.
    }
  }
  const fallbackValue = cleanText(sku?.specText) || "默认规格";
  return [["规格", fallbackValue]];
}

function skuSpecificationMap(sku: ProductSku): Map<string, string> {
  return new Map(skuSpecificationEntries(sku));
}

function skuSpecificationImageGroup(
  normalizedSkus: Array<{ sku: ProductSku; values: Map<string, string> }>,
  groupNames: string[]
): string {
  let bestName = "";
  let bestScore = 0;
  groupNames.forEach((name) => {
    const imagesByValue = new Map<string, Set<string>>();
    normalizedSkus.forEach(({ sku, values }) => {
      const value = values.get(name);
      const imageUrl = cleanText(sku.image);
      if (!value || !imageUrl) {
        return;
      }
      const images = imagesByValue.get(value) ?? new Set<string>();
      images.add(imageUrl);
      imagesByValue.set(value, images);
    });
    const representativeImages = Array.from(imagesByValue.values())
      .map((images) => Array.from(images)[0])
      .filter(Boolean);
    if (!representativeImages.length) {
      return;
    }
    const stableValueCount = Array.from(imagesByValue.values())
      .filter((images) => images.size === 1).length;
    const distinctImageCount = new Set(representativeImages).size;
    const ambiguityPenalty = Array.from(imagesByValue.values())
      .reduce((total, images) => total + Math.max(0, images.size - 1), 0);
    const score = representativeImages.length * 10 + distinctImageCount * 3 +
      stableValueCount - ambiguityPenalty * 4;
    if (score > bestScore) {
      bestScore = score;
      bestName = name;
    }
  });
  return bestName;
}

export function buildSkuSpecificationGroups(
  skus: ProductSku[],
  selectedSkuId: number
): SkuSpecificationGroupView[] {
  const normalizedSkus = (Array.isArray(skus) ? skus : [])
    .filter((sku) => positiveInteger(sku?.id))
    .map((sku) => ({ sku, values: skuSpecificationMap(sku) }));
  const selectedSku = normalizedSkus.find(({ sku }) => sku.id === selectedSkuId)?.sku
    ?? normalizedSkus.find(({ sku }) => validSku(sku))?.sku;
  const selectedValues = selectedSku ? skuSpecificationMap(selectedSku) : new Map<string, string>();
  const groupNames: string[] = [];
  normalizedSkus.forEach(({ values }) => {
    values.forEach((_value, name) => {
      if (!groupNames.includes(name)) {
        groupNames.push(name);
      }
    });
  });
  const imageGroupName = skuSpecificationImageGroup(normalizedSkus, groupNames);

  return groupNames.map((name, groupIndex) => {
    const values: string[] = [];
    normalizedSkus.forEach(({ values: specificationValues }) => {
      const value = specificationValues.get(name);
      if (value && !values.includes(value)) {
        values.push(value);
      }
    });
    return {
      key: `${groupIndex}-${name}`,
      name,
      hasImages: name === imageGroupName,
      options: values.map((value, optionIndex) => {
        const available = normalizedSkus.some(({ sku, values: candidateValues }) => {
          if (!validSku(sku) || candidateValues.get(name) !== value) {
            return false;
          }
          return groupNames.every((otherName) => (
            otherName === name ||
            !selectedValues.has(otherName) ||
            candidateValues.get(otherName) === selectedValues.get(otherName)
          ));
        });
        const imageUrl = name === imageGroupName
          ? cleanText(normalizedSkus.find(({ sku, values: candidateValues }) => (
            candidateValues.get(name) === value && cleanText(sku.image)
          ))?.sku.image)
          : "";
        return {
          key: `${groupIndex}-${optionIndex}-${value}`,
          value,
          imageUrl,
          hasImage: Boolean(imageUrl),
          selected: selectedValues.get(name) === value,
          disabled: !available
        };
      })
    };
  });
}

export function buildSpecificationPreviewUrls(
  groups: SkuSpecificationGroupView[],
  currentUrl: unknown = ""
): string[] {
  const imageGroup = (Array.isArray(groups) ? groups : [])
    .find((group) => group?.hasImages);
  if (!imageGroup) {
    return [];
  }
  const urls = imageGroup.options
    .filter((option) => option?.hasImage)
    .map((option) => cleanText(option.imageUrl))
    .filter((url, index, values) => Boolean(url) && values.indexOf(url) === index);
  const current = cleanText(currentUrl);
  return current && urls.includes(current)
    ? [current, ...urls.filter((url) => url !== current)]
    : urls;
}

export function resolveSkuSpecificationSelection(
  skus: ProductSku[],
  selectedSkuId: number,
  specificationName: unknown,
  specificationValue: unknown
): ProductSku | undefined {
  const name = cleanText(specificationName);
  const value = cleanText(specificationValue);
  if (!name || !value) {
    return undefined;
  }
  const normalizedSkus = (Array.isArray(skus) ? skus : [])
    .filter((sku) => positiveInteger(sku?.id));
  const selectedSku = normalizedSkus.find((sku) => sku.id === selectedSkuId)
    ?? normalizedSkus.find(validSku);
  const selectedValues = selectedSku ? skuSpecificationMap(selectedSku) : new Map<string, string>();
  return normalizedSkus.find((sku) => {
    if (!validSku(sku)) {
      return false;
    }
    const candidateValues = skuSpecificationMap(sku);
    if (candidateValues.get(name) !== value) {
      return false;
    }
    return Array.from(selectedValues.entries()).every(([otherName, otherValue]) => (
      otherName === name || candidateValues.get(otherName) === otherValue
    ));
  });
}

export function findDefaultSku(skus: ProductSku[]): ProductSku | undefined {
  return (Array.isArray(skus) ? skus : []).find(validSku);
}

export function buildSkuOptions(
  skus: ProductSku[],
  selectedSkuId: number,
  fallbackImage = ""
): SkuOptionView[] {
  return (Array.isArray(skus) ? skus : [])
    .filter((sku) => positiveInteger(sku?.id))
    .map((sku) => {
      const stock = nonNegativeInteger(sku.stockAvailable) ?? 0;
      return {
        id: sku.id,
        name: cleanText(sku.specText) || "默认规格",
        priceText: formatMoney(sku.priceCent),
        stockText: stock > 0 ? `库存 ${stock}` : "已售罄",
        imageUrl: cleanText(sku.image) || cleanText(fallbackImage),
        hasImage: Boolean(cleanText(sku.image) || cleanText(fallbackImage)),
        selected: sku.id === selectedSkuId,
        disabled: !validSku(sku)
      };
    });
}

function validWholesaleTiers(tiers: WholesaleTier[]): WholesaleTier[] {
  return (Array.isArray(tiers) ? tiers : [])
    .filter((tier) => positiveInteger(tier?.minQuantity) && nonNegativeInteger(tier?.unitPriceCent) !== undefined)
    .slice()
    .sort((left, right) => left.minQuantity - right.minQuantity);
}

export function resolvePurchaseSelection(
  sku: ProductSku | undefined,
  requestedQuantity: number
): PurchaseSelectionView {
  if (!sku || !validSku(sku)) {
    return {
      selectedSkuId: 0,
      quantity: 1,
      quantityMax: 0,
      priceText: "",
      priceIntegerText: "",
      priceDecimalText: "",
      originalPriceText: "",
      hasOriginalPrice: false,
      stockText: "暂无可售规格",
      wholesaleApplied: false,
      wholesaleHint: "",
      wholesaleTiers: []
    };
  }
  const stock = nonNegativeInteger(sku.stockAvailable) ?? 0;
  const quantityMax = Math.min(stock, 999);
  const quantity = Math.min(Math.max(positiveInteger(requestedQuantity) ?? 1, 1), quantityMax);
  const tiers = validWholesaleTiers(sku.wholesaleTiers);
  const eligibleTiers = tiers.filter((tier) => tier.minQuantity <= quantity);
  const appliedTier = eligibleTiers[eligibleTiers.length - 1];
  const nextTier = tiers.find((tier) => tier.minQuantity > quantity);
  const unitPrice = appliedTier?.unitPriceCent ?? sku.priceCent;
  const originalPrice = nonNegativeInteger(sku.originalPriceCent);
  const parts = priceParts(unitPrice);
  const hints = [
    appliedTier ? `已享 ${appliedTier.minQuantity} 件起批发价` : "",
    nextTier ? `再买 ${nextTier.minQuantity - quantity} 件，每件 ¥${formatMoney(nextTier.unitPriceCent)}` : ""
  ].filter(Boolean);
  return {
    selectedSkuId: sku.id,
    quantity,
    quantityMax,
    priceText: formatMoney(unitPrice),
    priceIntegerText: parts.integerText,
    priceDecimalText: parts.decimalText,
    originalPriceText: originalPrice !== undefined && originalPrice > unitPrice
      ? formatMoney(originalPrice)
      : "",
    hasOriginalPrice: originalPrice !== undefined && originalPrice > unitPrice,
    stockText: `库存 ${stock}`,
    wholesaleApplied: Boolean(appliedTier),
    wholesaleHint: hints.join(" · "),
    wholesaleTiers: tiers.map((tier) => ({
      minQuantity: tier.minQuantity,
      priceText: formatMoney(tier.unitPriceCent),
      active: tier.minQuantity === appliedTier?.minQuantity
    }))
  };
}
