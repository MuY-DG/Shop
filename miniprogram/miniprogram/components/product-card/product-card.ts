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
        this.setData({ imageFailed: false });
      }
    },
    variant: {
      type: String,
      value: "compact"
    },
    adding: {
      type: Boolean,
      value: false
    }
  },

  data: {
    imageFailed: false
  },

  methods: {
    handleImageError() {
      this.setData({ imageFailed: true });
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
      if (!product.spuId) {
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
