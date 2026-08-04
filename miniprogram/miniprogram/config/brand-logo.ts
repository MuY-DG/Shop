const BRAND_LOGO_SRC = "/assets/images/zaoxiangji-login-emblem18.png";
const BRAND_LOGO_ASPECT_RATIO = 216 / 288;

export interface BrandLogoView {
  src: string;
  style: string;
}

export function createBrandLogoView(
  slotSize: number,
  contentSize: number
): BrandLogoView {
  const width = contentSize;
  const height = contentSize * BRAND_LOGO_ASPECT_RATIO;
  const left = (slotSize - width) / 2;
  const top = (slotSize - height) / 2;

  return {
    src: BRAND_LOGO_SRC,
    style: [
      `width:${width.toFixed(2)}rpx`,
      `height:${height.toFixed(2)}rpx`,
      `left:${left.toFixed(2)}rpx`,
      `top:${top.toFixed(2)}rpx`
    ].join(";")
  };
}
