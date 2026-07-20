Component({
  options: {
    styleIsolation: "isolated"
  },
  properties: {
    quantity: { type: Number, value: 1 },
    maximum: { type: Number, value: 0 },
    hint: { type: String, value: "" }
  },
  methods: {
    onMinus() {
      if (this.data.quantity > 1) {
        this.triggerEvent("minus");
      }
    },
    onPlus() {
      if (this.data.maximum > 0 && this.data.quantity < this.data.maximum) {
        this.triggerEvent("plus");
      }
    }
  }
});
