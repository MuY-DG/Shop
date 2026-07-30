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
        this.setData({
          imageFailed: false,
          titleExpanded: false,
          titleExpandable: false
        }, () => {
          this.measureTitleOverflow();
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
        });
      }
    },
    adding: {
      type: Boolean,
      value: false
    }
  },

  data: {
    imageFailed: false,
    titleExpanded: false,
    titleExpandable: false
  },

  lifetimes: {
    ready() {
      this.measureTitleOverflow();
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
