interface SkuSelectorTapEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
    };
  };
}

Component({
  options: {
    styleIsolation: "isolated"
  },
  properties: {
    options: { type: Array, value: [] }
  },
  methods: {
    onSelect(event: SkuSelectorTapEvent) {
      this.triggerEvent("select", { id: event.currentTarget.dataset.id });
    }
  }
});
