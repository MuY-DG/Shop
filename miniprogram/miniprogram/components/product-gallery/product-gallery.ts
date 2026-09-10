import { invalidateProductImage, loadProductImage } from "../../utils/product-image-cache";

interface ProductGalleryImage {
  key: string;
  url: string;
  hasImage: boolean;
  displayUrl?: string;
  previewUrl?: string;
}

interface ProductGalleryAnimationFinishEvent {
  detail: {
    current: number;
    source?: string;
  };
}

interface ProductGalleryTransitionEvent {
  detail: {
    dx: number;
    dy: number;
  };
}

interface ProductGalleryRuntime {
  pendingCurrent: number;
  transitioning: boolean;
  imageVersion?: number;
  previewPending?: boolean;
  pendingImages?: Set<string>;
  settleTimer?: ReturnType<typeof setTimeout>;
}

interface ProductGalleryErrorEvent {
  currentTarget: {
    dataset: {
      index?: number | string;
    };
  };
}

function galleryRuntime(instance: unknown): ProductGalleryRuntime {
  return instance as ProductGalleryRuntime;
}

function clearSettleTimer(instance: unknown): void {
  const runtime = galleryRuntime(instance);
  if (runtime.settleTimer !== undefined) {
    clearTimeout(runtime.settleTimer);
    runtime.settleTimer = undefined;
  }
}

function validGalleryIndex(index: number, length: number): boolean {
  return Number.isSafeInteger(index) && index >= 0 && index < length;
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
        clearSettleTimer(this);
        const runtime = galleryRuntime(this);
        const imageVersion = (runtime.imageVersion ?? 0) + 1;
        runtime.imageVersion = imageVersion;
        runtime.pendingImages = new Set();
        const displayImages = Array.isArray(value)
          ? value.map((image) => ({
              ...image,
              displayUrl: ""
            }))
          : [];
        galleryRuntime(this).pendingCurrent = 0;
        galleryRuntime(this).transitioning = false;
        this.setData({ displayImages, current: 0, swiperVisible: true }, () => {
          this.loadNearbyImages(0);
        });
      }
    }
  },

  data: {
    displayImages: [] as ProductGalleryImage[],
    current: 0,
    swiperVisible: true
  },

  lifetimes: {
    detached() {
      clearSettleTimer(this);
      const runtime = galleryRuntime(this);
      runtime.imageVersion = (runtime.imageVersion ?? 0) + 1;
    }
  },

  methods: {
    loadNearbyImages(current: number) {
      const runtime = galleryRuntime(this);
      const version = runtime.imageVersion;
      const length = this.data.displayImages.length;
      const indexes = new Set([current, (current + 1) % length]);
      if (current > 0) indexes.add(current - 1);
      for (const index of indexes) {
        const item = this.data.displayImages[index];
        if (!item?.hasImage || item.displayUrl || runtime.pendingImages?.has(item.url)) continue;
        runtime.pendingImages?.add(item.url);
        void loadProductImage(item.url).then(displayUrl => {
          if (runtime.imageVersion === version) {
            runtime.pendingImages?.delete(item.url);
            this.setData({ [`displayImages[${index}].displayUrl`]: displayUrl });
          }
        });
      }
    },

    onImagePreview(event: ProductGalleryErrorEvent) {
      const index = Number(event.currentTarget.dataset.index);
      if (galleryRuntime(this).transitioning || !validGalleryIndex(index, this.data.displayImages.length)) return;
      const current = this.data.displayImages[index];
      if (!current.hasImage) return;
      const urls = this.data.displayImages.filter(image => image.hasImage)
        .map(image => image.previewUrl || image.url);
      wx.previewImage({ current: current.previewUrl || current.url, urls });
    },

    onChange(event: ProductGalleryAnimationFinishEvent) {
      const current = Number(event.detail.current);
      if (validGalleryIndex(current, this.data.displayImages.length)) {
        // Only remember the native target here. Writing current back during change
        // can interrupt the gesture and leave the swiper between two items.
        galleryRuntime(this).pendingCurrent = current;
        this.loadNearbyImages(current);
      }
    },

    onTransition(event: ProductGalleryTransitionEvent) {
      const dx = Number(event.detail.dx);
      const dy = Number(event.detail.dy);
      galleryRuntime(this).transitioning =
        (Number.isFinite(dx) && Math.abs(dx) > 1) ||
        (Number.isFinite(dy) && Math.abs(dy) > 1);
    },

    onAnimationFinish(event: ProductGalleryAnimationFinishEvent) {
      clearSettleTimer(this);
      galleryRuntime(this).transitioning = false;
      const current = Number(event.detail.current);
      if (!validGalleryIndex(current, this.data.displayImages.length)) {
        return;
      }
      galleryRuntime(this).pendingCurrent = current;
      if (current !== this.data.current) {
        this.setData({ current });
      }
    },

    scheduleSettleGuard() {
      clearSettleTimer(this);
      const runtime = galleryRuntime(this);
      runtime.settleTimer = setTimeout(() => {
        runtime.settleTimer = undefined;
        runtime.transitioning = false;
        const pendingCurrent = runtime.pendingCurrent;
        const current = validGalleryIndex(pendingCurrent, this.data.displayImages.length)
          ? pendingCurrent
          : this.data.current;
        // Remounting is reserved for the abnormal path where native swiper never
        // reports animationfinish after touchend/touchcancel.
        this.setData({ swiperVisible: false, current }, () => {
          this.setData({ swiperVisible: true });
        });
      }, 480);
    },

    onTouchEnd() {
      if (galleryRuntime(this).transitioning) {
        this.scheduleSettleGuard();
      }
    },

    onTouchCancel() {
      if (galleryRuntime(this).transitioning) {
        this.scheduleSettleGuard();
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
      const item = this.data.displayImages[index];
      invalidateProductImage(item.url);
      if (item.previewUrl && item.url !== item.previewUrl) {
        this.setData({ [`displayImages[${index}].url`]: item.previewUrl,
          [`displayImages[${index}].displayUrl`]: "" }, () => this.loadNearbyImages(index));
      } else if (item.displayUrl && item.displayUrl !== item.url) {
        this.setData({ [`displayImages[${index}].displayUrl`]: item.url });
      } else {
        this.setData({ [`displayImages[${index}].hasImage`]: false });
      }
    }
  }
});
