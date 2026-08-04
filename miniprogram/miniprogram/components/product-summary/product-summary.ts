Component({
  options: {
    styleIsolation: "isolated"
  },

  properties: {
    detail: { type: Object, value: {} },
    priceText: { type: String, value: "" },
    priceIntegerText: { type: String, value: "" },
    priceDecimalText: { type: String, value: "" },
    originalPriceText: { type: String, value: "" },
    hasOriginalPrice: { type: Boolean, value: false },
    wholesaleApplied: { type: Boolean, value: false },
    salesText: { type: String, value: "" }
  }
});
