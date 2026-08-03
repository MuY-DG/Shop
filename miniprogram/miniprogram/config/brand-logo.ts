interface BrandLogoBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export type BrandLogoNumber =
  | 1 | 2 | 3 | 4 | 5
  | 6 | 7 | 8 | 9 | 10
  | 11 | 12 | 13 | 14 | 15
  | 16 | 17 | 18 | 19 | 20;

const SOURCE_SIZE = 1024;

const BRAND_LOGO_BOUNDS: Record<BrandLogoNumber, BrandLogoBounds> = {
  1: { x: 184, y: 147, width: 656, height: 628 },
  2: { x: 175, y: 238, width: 666, height: 442 },
  3: { x: 240, y: 148, width: 544, height: 670 },
  4: { x: 195, y: 205, width: 634, height: 500 },
  5: { x: 184, y: 203, width: 655, height: 502 },
  6: { x: 155, y: 237, width: 714, height: 451 },
  7: { x: 190, y: 192, width: 646, height: 571 },
  8: { x: 251, y: 213, width: 522, height: 535 },
  9: { x: 253, y: 116, width: 564, height: 677 },
  10: { x: 288, y: 165, width: 450, height: 607 },
  11: { x: 271, y: 246, width: 490, height: 457 },
  12: { x: 272, y: 147, width: 481, height: 662 },
  13: { x: 183, y: 182, width: 670, height: 622 },
  14: { x: 265, y: 224, width: 494, height: 494 },
  15: { x: 320, y: 280, width: 380, height: 411 },
  16: { x: 274, y: 177, width: 475, height: 588 },
  17: { x: 303, y: 254, width: 427, height: 447 },
  18: { x: 166, y: 223, width: 715, height: 536 },
  19: { x: 291, y: 268, width: 443, height: 449 },
  20: { x: 314, y: 250, width: 416, height: 417 }
};

// 只改这里的数字（1–20），登录页和购物车 Logo 会一起切换。
export const ACTIVE_BRAND_LOGO: BrandLogoNumber = 18;

export interface BrandLogoView {
  src: string;
  style: string;
}

/**
 * 放大透明画布中的实际图案，不裁剪、不修改原图文件。
 */
export function createBrandLogoView(
  slotSize: number,
  contentSize: number
): BrandLogoView {
  const bounds = BRAND_LOGO_BOUNDS[ACTIVE_BRAND_LOGO];
  const scale = contentSize / Math.max(bounds.width, bounds.height);
  const canvasSize = SOURCE_SIZE * scale;
  const left = (slotSize - bounds.width * scale) / 2 - bounds.x * scale;
  const top = (slotSize - bounds.height * scale) / 2 - bounds.y * scale;

  return {
    src: `/assets/images/zaoxiangji-login-emblem${ACTIVE_BRAND_LOGO}.png`,
    style: [
      `width:${canvasSize.toFixed(2)}rpx`,
      `height:${canvasSize.toFixed(2)}rpx`,
      `left:${left.toFixed(2)}rpx`,
      `top:${top.toFixed(2)}rpx`
    ].join(";")
  };
}
