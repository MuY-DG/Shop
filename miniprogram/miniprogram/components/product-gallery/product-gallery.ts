interface ProductGalleryImage {
  key: string;
  url: string;
  hasImage: boolean;
}

interface ProductGalleryChangeEvent {
  detail: {
    current: number;
  };
}

interface ProductGalleryErrorEvent {
  currentTarget: {
    dataset: {
      index?: number | string;
    };
  };
}

Component({
  options: {
    styleIsolation: "isolated"
  },

  properties: {
    images: {
      type: Array,
      value: [],
      observer(value: ProductGalleryImage[]) {
        const displayImages = Array.isArray(value)
          ? value.map((image) => ({ ...image }))
          : [];
        this.setData({ displayImages, current: 0 });
      }
    }
  },

  data: {
    displayImages: [] as ProductGalleryImage[],
    current: 0
  },

  methods: {
    onChange(event: ProductGalleryChangeEvent) {
      const current = Number(event.detail.current);
      if (
        Number.isSafeInteger(current) &&
        current >= 0 &&
        current < this.data.displayImages.length
      ) {
        this.setData({ current });
      }
    },

    onImageError(event: ProductGalleryErrorEvent) {
      const index = Number(event.currentTarget.dataset.index);
      if (
        !Number.isSafeInteger(index) ||
        index < 0 ||
        index >= this.data.displayImages.length
      ) {
        return;
      }
      const displayImages = this.data.displayImages.map((image, itemIndex) => (
        itemIndex === index ? { ...image, hasImage: false } : image
      ));
      this.setData({ displayImages });
    }
  }
});
