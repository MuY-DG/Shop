interface ProductSelectEvent {
  detail: {
    path?: string;
    spuId?: number;
    title?: string;
  };
}

Component({
  options: {
    styleIsolation: "isolated"
  },

  properties: {
    title: {
      type: String,
      value: ""
    },
    subtitle: {
      type: String,
      value: ""
    },
    iconPath: {
      type: String,
      value: ""
    },
    presentation: {
      type: String,
      value: "compact"
    },
    products: {
      type: Array,
      value: []
    },
    separated: {
      type: Boolean,
      value: false
    },
    addingSpuId: {
      type: Number,
      value: 0
    }
  },

  methods: {
    onProductSelect(event: ProductSelectEvent) {
      this.triggerEvent("select", event.detail);
    },

    onProductAdd(event: ProductSelectEvent) {
      this.triggerEvent("add", event.detail);
    }
  }
});
