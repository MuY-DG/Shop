interface IndexedTapEvent {
  currentTarget: {
    dataset: {
      index?: number | string;
    };
  };
}

interface CategoryValue {
  navigationPath?: string;
}

function homeCategoryEventIndex(event: IndexedTapEvent, length: number): number | undefined {
  const index = Number(event.currentTarget.dataset.index);
  return Number.isSafeInteger(index) && index >= 0 && index < length
    ? index
    : undefined;
}

Component({
  options: {
    styleIsolation: "isolated"
  },

  properties: {
    categories: {
      type: Array,
      value: []
    }
  },

  methods: {
    onCategoryTap(event: IndexedTapEvent) {
      const index = homeCategoryEventIndex(event, this.data.categories.length);
      if (index === undefined) {
        return;
      }
      const category = this.data.categories[index] as CategoryValue | undefined;
      this.triggerEvent("select", { path: category?.navigationPath ?? "" });
    },

    onCategoryImageError(event: IndexedTapEvent) {
      const index = homeCategoryEventIndex(event, this.data.categories.length);
      if (index !== undefined) {
        this.triggerEvent("imageerror", { index });
      }
    }
  }
});
