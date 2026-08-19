interface ProductCardValue {
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
  badgeTone: string;
  soldOut: boolean;
  features: Array<{
    text: string;
    tone: string;
    kind: string;
    spiceTone: string;
    iconPath: string;
    spiceIconIndexes?: number[];
    servingText?: string;
  }>;
  wholesaleText: string;
  salesText: string;
}

interface RectResult {
  width?: number;
}

const EMPTY_PRODUCT: ProductCardValue = {
  navigationPath: "",
  spuId: 0,
  title: "",
  subtitle: "",
  imageUrl: "",
  hasImage: false,
  placeholder: "灶",
  priceText: "",
  hasPrice: false,
  priceIntegerText: "",
  priceDecimalText: "",
  rangePriceIntegerText: "",
  rangePriceDecimalText: "",
  hasPriceRange: false,
  priceSuffixText: "",
  originalPriceText: "",
  hasOriginalPrice: false,
  badgeText: "",
  badgeTone: "neutral",
  soldOut: false,
  features: [],
  wholesaleText: "",
  salesText: ""
};

Component({
  options: {
    styleIsolation: "isolated"
  },

  properties: {
    product: {
      type: Object,
      value: EMPTY_PRODUCT,
      observer() {
        const product = this.data.product as ProductCardValue;
        const source = Array.isArray(product.features) ? product.features : [];
        const featureViews = [
          ...source.filter((feature) => feature.kind === "weight"),
          ...source.filter((feature) => feature.kind !== "weight")
        ];
        this.setData({
          imageFailed: false,
          titleExpanded: false,
          titleExpandable: false,
          showServing: false,
          featureViews
        }, () => {
          this.measureTitleOverflow();
          this.measureFeaturesFit();
        });
      }
    },
    variant: {
      type: String,
      value: "compact",
      observer() {
        this.setData({
          titleExpanded: false,
          titleExpandable: false
        }, () => {
          this.measureTitleOverflow();
          this.measureFeaturesFit();
        });
      }
    },
    flat: {
      type: Boolean,
      value: false
    },
    adding: {
      type: Boolean,
      value: false
    }
  },

  data: {
    imageFailed: false,
    titleExpanded: false,
    titleExpandable: false,
    featureViews: [] as Array<{
      text: string;
      kind: string;
      servingText?: string;
    }>,
    showServing: false
  },

  lifetimes: {
    ready() {
      this.measureTitleOverflow();
      this.measureFeaturesFit();
    }
  },

  methods: {
    handleImageError() {
      this.setData({ imageFailed: true });
    },

    measureTitleOverflow() {
      const product = this.data.product as ProductCardValue;
      const measuredTitle = product.title;
      const measuredVariant = this.data.variant;
      if (!measuredTitle) {
        return;
      }
      const query = this.createSelectorQuery();
      query.select(".product-card__title-row").boundingClientRect();
      query.select(".product-card__title-measure").boundingClientRect();
      query.exec((results) => {
        const currentProduct = this.data.product as ProductCardValue;
        if (
          currentProduct.title !== measuredTitle ||
          this.data.variant !== measuredVariant
        ) {
          return;
        }
        const rowRect = results[0] as RectResult | null;
        const titleRect = results[1] as RectResult | null;
        const rowWidth = Number(rowRect?.width);
        const titleWidth = Number(titleRect?.width);
        if (!Number.isFinite(rowWidth) || !Number.isFinite(titleWidth)) {
          return;
        }
        const titleExpandable = titleWidth > rowWidth + 1;
        this.setData({
          titleExpandable,
          titleExpanded: titleExpandable && this.data.titleExpanded
        });
      });
    },

    /**
     * 克数与辣度同行展示：按当前容器宽度判断「适合N人」提示是否放得下，
     * 放不下时隐藏，保证克数、辣度与辣椒图标始终在一行。
     */
    measureFeaturesFit() {
      const featureViews = this.data.featureViews as Array<{
        kind: string;
        servingText?: string;
      }>;
      const hasMeasurableServing = featureViews.some(
        (feature) => feature.kind === "weight" && Boolean(feature.servingText)
      );
      if (!hasMeasurableServing) {
        return;
      }
      const query = this.createSelectorQuery();
      query.select(".product-card__features").boundingClientRect();
      query.selectAll(".product-card__fact, .product-card__fact-group").boundingClientRect();
      query.select(".product-card__serving-measure").boundingClientRect();
      query.exec((results) => {
        const containerRect = results[0] as RectResult | null;
        const factRects = (results[1] as Array<RectResult> | null) ?? [];
        const servingRect = results[2] as RectResult | null;
        const containerWidth = Number(containerRect?.width);
        const servingWidth = Number(servingRect?.width);
        if (
          !Number.isFinite(containerWidth) ||
          !Number.isFinite(servingWidth) ||
          containerWidth <= 0 ||
          factRects.length === 0
        ) {
          return;
        }
        const factsWidth = factRects.reduce(
          (sum, rect) => sum + Number(rect?.width ?? 0),
          0
        );
        if (!Number.isFinite(factsWidth) || factsWidth <= 0) {
          return;
        }
        const systemInfo =
          typeof wx.getWindowInfo === "function"
            ? wx.getWindowInfo()
            : wx.getSystemInfoSync();
        const windowWidth = Number(systemInfo.windowWidth) || 375;
        const gapPx = (6 / 750) * windowWidth;
        const gapsWidth = gapPx * Math.max(0, factRects.length - 1);
        const showingServing = this.data.showServing === true;
        const neededWidth =
          factsWidth + (showingServing ? 0 : servingWidth) + gapsWidth;
        const shouldShowServing = neededWidth <= containerWidth + 0.5;
        if (shouldShowServing !== showingServing) {
          this.setData({ showServing: shouldShowServing });
        }
      });
    },

    handleTitleToggle() {
      if (!this.data.titleExpandable) {
        return;
      }
      this.setData({ titleExpanded: !this.data.titleExpanded });
    },

    handleTap() {
      const product = this.data.product as ProductCardValue;
      this.triggerEvent("select", {
        path: product.navigationPath || "",
        spuId: product.spuId
      });
    },

    handleCartTap() {
      if (this.data.adding) {
        return;
      }
      const product = this.data.product as ProductCardValue;
      if (!product.spuId || product.soldOut) {
        return;
      }
      this.triggerEvent("add", {
        path: product.navigationPath || "",
        spuId: product.spuId,
        title: product.title
      });
    }
  }
});
