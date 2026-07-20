interface ProductGalleryImage {
  key: string;
  url: string;
  hasImage: boolean;
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
        const displayImages = Array.isArray(value)
          ? value.map((image) => ({ ...image }))
          : [];
        galleryRuntime(this).pendingCurrent = 0;
        galleryRuntime(this).transitioning = false;
        this.setData({ displayImages, current: 0, swiperVisible: true });
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
    }
  },

  methods: {
    onChange(event: ProductGalleryAnimationFinishEvent) {
      const current = Number(event.detail.current);
      if (validGalleryIndex(current, this.data.displayImages.length)) {
        // Only remember the native target here. Writing current back during change
        // can interrupt the gesture and leave the swiper between two items.
        galleryRuntime(this).pendingCurrent = current;
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
      const displayImages = this.data.displayImages.map((image, itemIndex) => (
        itemIndex === index ? { ...image, hasImage: false } : image
      ));
      this.setData({ displayImages });
    }
  }
});
