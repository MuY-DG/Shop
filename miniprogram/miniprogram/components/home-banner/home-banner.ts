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

interface SwiperTransitionEvent {
  detail: {
    dx: number;
    dy: number;
  };
}

interface BannerValue {
  navigationPath?: string;
}

interface HomeBannerRuntime {
  pendingCurrent: number;
  transitioning: boolean;
  pageVisible: boolean;
  resumeTimer?: ReturnType<typeof setTimeout>;
  settleTimer?: ReturnType<typeof setTimeout>;
}

interface HomeBannerInstance extends HomeBannerRuntime {
  data: {
    autoplayEnabled: boolean;
    banners: unknown[];
    currentBanner: number;
    swiperVisible: boolean;
  };
  setData(
    data: Partial<HomeBannerInstance["data"]>,
    callback?: () => void
  ): void;
}

function bannerRuntime(instance: unknown): HomeBannerInstance {
  return instance as HomeBannerInstance;
}

function clearResumeTimer(instance: unknown): void {
  const runtime = bannerRuntime(instance);
  if (runtime.resumeTimer !== undefined) {
    clearTimeout(runtime.resumeTimer);
    runtime.resumeTimer = undefined;
  }
}

function clearBannerSettleTimer(instance: unknown): void {
  const runtime = bannerRuntime(instance);
  if (runtime.settleTimer !== undefined) {
    clearTimeout(runtime.settleTimer);
    runtime.settleTimer = undefined;
  }
}

function validBannerIndex(index: number, length: number): boolean {
  return Number.isSafeInteger(index) && index >= 0 && index < length;
}

function scheduleAutoplayResume(instance: unknown): void {
  const runtime = bannerRuntime(instance);
  clearResumeTimer(runtime);
  if (!runtime.pageVisible || runtime.data.banners.length <= 1) {
    return;
  }
  // Re-enabling on a later task makes the native swiper start a fresh interval
  // instead of continuing an autoplay timer that was suspended in background.
  runtime.resumeTimer = setTimeout(() => {
    runtime.resumeTimer = undefined;
    if (
      runtime.pageVisible &&
      runtime.data.swiperVisible &&
      runtime.data.banners.length > 1 &&
      !runtime.data.autoplayEnabled
    ) {
      runtime.setData({ autoplayEnabled: true });
    }
  }, 120);
}

function homeBannerEventIndex(event: IndexedTapEvent, length: number): number | undefined {
  const index = Number(event.currentTarget.dataset.index);
  return validBannerIndex(index, length)
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
        const length = Array.isArray(value) ? value.length : 0;
        if (!validBannerIndex(this.data.currentBanner, length)) {
          bannerRuntime(this).pendingCurrent = 0;
          this.setData({ currentBanner: 0 });
        }
        if (
          length > 1 &&
          bannerRuntime(this).pageVisible &&
          !this.data.autoplayEnabled
        ) {
          scheduleAutoplayResume(this);
        }
      }
    }
  },

  data: {
    currentBanner: 0,
    autoplayEnabled: true,
    swiperVisible: true
  },

  lifetimes: {
    attached() {
      const runtime = bannerRuntime(this);
      runtime.pendingCurrent = this.data.currentBanner;
      runtime.transitioning = false;
      runtime.pageVisible = true;
    },

    detached() {
      const runtime = bannerRuntime(this);
      runtime.pageVisible = false;
      clearResumeTimer(runtime);
      clearBannerSettleTimer(runtime);
    }
  },

  pageLifetimes: {
    hide() {
      const runtime = bannerRuntime(this);
      runtime.pageVisible = false;
      runtime.transitioning = false;
      clearResumeTimer(runtime);
      clearBannerSettleTimer(runtime);
      const pendingCurrent = runtime.pendingCurrent;
      const currentBanner = validBannerIndex(
        pendingCurrent,
        this.data.banners.length
      )
        ? pendingCurrent
        : this.data.currentBanner;
      // Tear down the native swiper while the page is hidden. This drops any
      // queued autoplay animation instead of letting it replay after foregrounding.
      this.setData({
        autoplayEnabled: false,
        currentBanner,
        swiperVisible: false
      });
    },

    show() {
      const runtime = bannerRuntime(this);
      runtime.pageVisible = true;
      clearResumeTimer(runtime);
      if (!this.data.swiperVisible) {
        this.setData({
          autoplayEnabled: false,
          swiperVisible: true
        }, () => {
          scheduleAutoplayResume(this);
        });
      }
    }
  },

  methods: {
    onBannerChange(event: SwiperChangeEvent) {
      const current = Number(event.detail.current);
      const runtime = bannerRuntime(this);
      if (
        runtime.pageVisible &&
        validBannerIndex(current, this.data.banners.length)
      ) {
        // Do not write `current` back while the native animation is running.
        // Doing so interrupts touch gestures and can trigger repeated changes.
        runtime.pendingCurrent = current;
      }
    },

    onBannerTransition(event: SwiperTransitionEvent) {
      const dx = Number(event.detail.dx);
      const dy = Number(event.detail.dy);
      const runtime = bannerRuntime(this);
      if (!runtime.pageVisible) {
        return;
      }
      runtime.transitioning =
        (Number.isFinite(dx) && Math.abs(dx) > 1) ||
        (Number.isFinite(dy) && Math.abs(dy) > 1);
    },

    onBannerAnimationFinish(event: SwiperChangeEvent) {
      const runtime = bannerRuntime(this);
      clearBannerSettleTimer(runtime);
      runtime.transitioning = false;
      const current = Number(event.detail.current);
      if (
        !runtime.pageVisible ||
        !validBannerIndex(current, this.data.banners.length)
      ) {
        return;
      }
      runtime.pendingCurrent = current;
      if (current !== this.data.currentBanner) {
        this.setData({ currentBanner: current });
      }
    },

    scheduleBannerSettleGuard() {
      clearBannerSettleTimer(this);
      const runtime = bannerRuntime(this);
      runtime.settleTimer = setTimeout(() => {
        runtime.settleTimer = undefined;
        if (!runtime.pageVisible) {
          return;
        }
        runtime.transitioning = false;
        const pendingCurrent = runtime.pendingCurrent;
        const currentBanner = validBannerIndex(
          pendingCurrent,
          this.data.banners.length
        )
          ? pendingCurrent
          : this.data.currentBanner;
        // A remount is only needed when touchend/touchcancel is not followed by
        // animationfinish, which otherwise leaves the native swiper half-settled.
        this.setData({
          autoplayEnabled: false,
          currentBanner,
          swiperVisible: false
        }, () => {
          if (!runtime.pageVisible) {
            return;
          }
          this.setData({ swiperVisible: true }, () => {
            scheduleAutoplayResume(this);
          });
        });
      }, 720);
    },

    onBannerTouchEnd() {
      if (bannerRuntime(this).transitioning) {
        this.scheduleBannerSettleGuard();
      }
    },

    onBannerTouchCancel() {
      if (bannerRuntime(this).transitioning) {
        this.scheduleBannerSettleGuard();
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
