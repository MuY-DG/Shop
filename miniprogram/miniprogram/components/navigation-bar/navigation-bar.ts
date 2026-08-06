interface WindowMetrics {
  windowWidth: number
  statusBarHeight?: number
  safeArea?: {
    top: number
  }
  platform?: string
}

interface WindowInfoApi {
  getWindowInfo?: () => WindowMetrics
}

interface NavigationLayout {
  statusBarHeight: number
  navigationHeight: number
  totalHeight: number
  sideWidth: number
}

const DEFAULT_HOME_PATH = '/pages/index/index'
const DEFAULT_WINDOW_WIDTH = 375
const DEFAULT_STATUS_BAR_HEIGHT = 20
const DEFAULT_NAVIGATION_HEIGHT = 44

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(Math.max(value, minimum), maximum)
}

function readWindowMetrics(): WindowMetrics {
  const windowApi = wx as typeof wx & WindowInfoApi
  const getWindowInfo = windowApi.getWindowInfo

  if (typeof getWindowInfo === 'function') {
    try {
      return getWindowInfo()
    } catch (_error) {
      // Older base libraries and a few development-tool versions may throw here.
    }
  }

  return wx.getSystemInfoSync()
}

function calculateLayout(): NavigationLayout {
  let windowMetrics: WindowMetrics

  try {
    windowMetrics = readWindowMetrics()
  } catch (_error) {
    windowMetrics = {
      windowWidth: DEFAULT_WINDOW_WIDTH,
      statusBarHeight: DEFAULT_STATUS_BAR_HEIGHT,
    }
  }

  const windowWidth = isFiniteNumber(windowMetrics.windowWidth) && windowMetrics.windowWidth > 0
    ? windowMetrics.windowWidth
    : DEFAULT_WINDOW_WIDTH
  const statusBarCandidate = windowMetrics.statusBarHeight ?? windowMetrics.safeArea?.top
  const statusBarHeight = isFiniteNumber(statusBarCandidate) && statusBarCandidate >= 0
    ? statusBarCandidate
    : DEFAULT_STATUS_BAR_HEIGHT
  const fallbackNavigationHeight = windowMetrics.platform === 'android'
    ? 48
    : DEFAULT_NAVIGATION_HEIGHT
  const fallbackSideWidth = clamp(Math.round(windowWidth * 0.26), 72, 112)

  try {
    const capsule = wx.getMenuButtonBoundingClientRect()
    const capsuleIsValid = isFiniteNumber(capsule.left)
      && isFiniteNumber(capsule.top)
      && isFiniteNumber(capsule.width)
      && isFiniteNumber(capsule.height)
      && capsule.left > windowWidth / 2
      && capsule.left < windowWidth
      && capsule.top >= statusBarHeight
      && capsule.width > 0
      && capsule.height > 0

    if (capsuleIsValid) {
      const verticalGap = clamp(capsule.top - statusBarHeight, 0, 12)
      const navigationHeight = clamp(capsule.height + verticalGap * 2, 40, 56)
      const sideWidth = clamp(windowWidth - capsule.left, 72, windowWidth * 0.45)

      return {
        statusBarHeight,
        navigationHeight,
        totalHeight: statusBarHeight + navigationHeight,
        sideWidth,
      }
    }
  } catch (_error) {
    // Invalid menu-button data is expected on some simulators; use safe defaults.
  }

  return {
    statusBarHeight,
    navigationHeight: fallbackNavigationHeight,
    totalHeight: statusBarHeight + fallbackNavigationHeight,
    sideWidth: fallbackSideWidth,
  }
}

function normalizeDelta(value: number): number {
  return isFiniteNumber(value) && value > 0 ? Math.max(1, Math.floor(value)) : 1
}

function normalizeHomePath(value: string): string {
  const path = value.trim() || DEFAULT_HOME_PATH
  return path.startsWith('/') ? path : `/${path}`
}

Component({
  options: {
    multipleSlots: true,
    styleIsolation: 'isolated',
  },

  properties: {
    title: {
      type: String,
      value: '',
    },
    back: {
      type: Boolean,
      value: false,
    },
    background: {
      type: String,
      value: '#ffffff',
    },
    color: {
      type: String,
      value: '',
    },
    showDivider: {
      type: Boolean,
      value: true,
    },
    roundBack: {
      type: Boolean,
      value: false,
    },
    lightBack: {
      type: Boolean,
      value: false,
    },
    wideCenter: {
      type: Boolean,
      value: false,
    },
    delta: {
      type: Number,
      value: 1,
    },
    homePath: {
      type: String,
      value: DEFAULT_HOME_PATH,
    },
  },

  data: calculateLayout(),

  lifetimes: {
    attached() {
      this.updateLayout()
    },
  },

  pageLifetimes: {
    show() {
      this.updateLayout()
    },
    resize() {
      this.updateLayout()
    },
  },

  methods: {
    updateLayout() {
      this.setData(calculateLayout())
    },

    handleBack() {
      const delta = normalizeDelta(this.data.delta)
      const shouldReturnHome = getCurrentPages().length <= delta

      this.triggerEvent('back', {
        delta,
        fallback: shouldReturnHome,
      })

      if (shouldReturnHome) {
        this.reLaunchHome()
        return
      }

      wx.navigateBack({
        delta,
        fail: () => {
          this.reLaunchHome()
        },
      })
    },

    reLaunchHome() {
      wx.reLaunch({
        url: normalizeHomePath(this.data.homePath),
      })
    },
  },
})
