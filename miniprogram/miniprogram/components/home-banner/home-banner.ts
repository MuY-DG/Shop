interface IndexedTapEvent {
  currentTarget: {
    dataset: {
      index?: number | string;
    };
  };
}

interface SwiperChangeEvent {
  detail: {
    current: number;
  };
}

interface BannerValue {
  navigationPath?: string;
}

function homeBannerEventIndex(event: IndexedTapEvent, length: number): number | undefined {
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
    banners: {
      type: Array,
      value: [],
      observer(value: unknown[]) {
        if (this.data.currentBanner >= value.length) {
          this.setData({ currentBanner: 0 });
        }
      }
    }
  },

  data: {
    currentBanner: 0
  },

  methods: {
    onBannerChange(event: SwiperChangeEvent) {
      const current = Number(event.detail.current);
      if (Number.isSafeInteger(current) && current >= 0) {
        this.setData({ currentBanner: current });
      }
    },

    onBannerTap(event: IndexedTapEvent) {
      const index = homeBannerEventIndex(event, this.data.banners.length);
      if (index === undefined) {
        return;
      }
      const banner = this.data.banners[index] as BannerValue | undefined;
      this.triggerEvent("select", { path: banner?.navigationPath ?? "" });
    },

    onBannerImageError(event: IndexedTapEvent) {
      const index = homeBannerEventIndex(event, this.data.banners.length);
      if (index !== undefined) {
        this.triggerEvent("imageerror", { index });
      }
    }
  }
});
