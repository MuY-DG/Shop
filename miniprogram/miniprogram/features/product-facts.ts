import type { HomeProductFeature } from "../types/home";
import { servingTextByWeight } from "./weight-servings";

export type ProductFactKind = "default" | "spice" | "weight";
export type ProductFactTone = "brand" | "orange" | "success" | "neutral" | "gold";
export type SpiceLevelTone = "mild" | "medium" | "hot" | "";

export interface ProductFactView {
  text: string;
  tone: ProductFactTone;
  kind: ProductFactKind;
  spiceTone: SpiceLevelTone;
  iconPath: string;
  spiceIconIndexes?: number[];
  servingText?: string;
}

type FactAdapter = (feature: HomeProductFeature) => ProductFactView | undefined;

const SPICE_ICON_PATH = "/assets/icons/chili-pepper-red.svg";
const MAX_SPICE_ICON_COUNT = 5;

function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function isSpiceFeature(feature: HomeProductFeature): boolean {
  const descriptor = [feature.code, feature.name, feature.renderer]
    .map(cleanText)
    .join(" ")
    .toUpperCase();
  return descriptor.includes("SPICE") || descriptor.includes("辣");
}

function isWeightFeature(feature: HomeProductFeature): boolean {
  const descriptor = [feature.code, feature.name]
    .map(cleanText)
    .join(" ")
    .toUpperCase();
  return descriptor.includes("WEIGHT") ||
    descriptor.includes("GRAM") ||
    descriptor.includes("NET_CONTENT") ||
    descriptor.includes("净含量") ||
    descriptor.includes("净重") ||
    descriptor.includes("重量") ||
    descriptor.includes("克重");
}

function spiceLevelFromText(displayText: string): number | undefined {
  const normalized = displayText.replace(/\s+/g, "").toUpperCase();
  if (/变态辣|EXTREME/.test(normalized)) {
    return 4;
  }
  if (/超辣|重辣|特辣|EXTRAHOT|HOT/.test(normalized)) {
    return 3;
  }
  if (/中辣|MEDIUM/.test(normalized)) {
    return 2;
  }
  if (/微辣|小辣|MILD|LIGHT/.test(normalized)) {
    return 1;
  }
  return undefined;
}

function normalizeSpiceLevel(level: unknown, displayText: string): number | undefined {
  if (typeof level === "number" && Number.isSafeInteger(level) && level > 0) {
    return Math.min(level, MAX_SPICE_ICON_COUNT);
  }
  return spiceLevelFromText(displayText);
}

function spiceTone(level: number | undefined): SpiceLevelTone {
  if (level === undefined) {
    return "";
  }
  return level === 1 ? "mild" : level === 2 ? "medium" : "hot";
}

function spiceIconIndexes(level: number | undefined): number[] {
  const count = level ?? 0;
  return Array.from({ length: count }, (_, index) => index);
}

/**
 * 辣度的统一展示适配。后续新增专用参数类型时，可增加新的 adapter，
 * 无需让首页、目录、详情或展示组件理解业务参数细节。
 */
export const adaptSpiceFact: FactAdapter = (feature) => {
  const displayText = cleanText(feature.displayText);
  if (!displayText || !isSpiceFeature(feature)) {
    return undefined;
  }
  const level = normalizeSpiceLevel(feature.level, displayText);
  const tone = spiceTone(level);
  return {
    text: displayText,
    tone: tone === "mild"
      ? "success"
      : tone === "medium"
        ? "orange"
        : tone === "hot"
          ? "brand"
          : "neutral",
    kind: "spice",
    spiceTone: tone,
    iconPath: level === undefined ? "" : SPICE_ICON_PATH,
    spiceIconIndexes: spiceIconIndexes(level)
  };
};

/** 重量参数的卡片展示适配，由重量工具统一计算建议人数。 */
export const adaptWeightFact: FactAdapter = (feature) => {
  const displayText = cleanText(feature.displayText);
  if (!displayText || !isWeightFeature(feature)) {
    return undefined;
  }
  return {
    text: displayText,
    tone: "neutral",
    kind: "weight",
    spiceTone: "",
    iconPath: "",
    spiceIconIndexes: [],
    servingText: servingTextByWeight(displayText)
  };
};

const FACT_ADAPTERS: FactAdapter[] = [adaptSpiceFact, adaptWeightFact];

export function adaptProductFact(
  feature: HomeProductFeature
): ProductFactView | undefined {
  const displayText = cleanText(feature?.displayText);
  if (!displayText) {
    return undefined;
  }
  for (const adapter of FACT_ADAPTERS) {
    const adapted = adapter(feature);
    if (adapted) {
      return adapted;
    }
  }
  return {
    text: displayText,
    tone: "neutral",
    kind: "default",
    spiceTone: "",
    iconPath: "",
    spiceIconIndexes: []
  };
}
