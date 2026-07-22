import { AMapWX } from "../../../../libs/amap-wx";
import type {
  AMapClient,
  AMapFailure,
  AMapPoi,
  AMapRegeocodeData
} from "../../../../libs/amap-wx-types";
import { getAmapClientConfig } from "../../../../services/location";
import type { AddressLocationSelection } from "../../../../types/location";
import { isApiError } from "../../../../utils/api-error";

interface Coordinate {
  longitude: number;
  latitude: number;
}

interface PlaceItem extends Coordinate {
  id: string;
  name: string;
  address: string;
  location: string;
  distanceText: string;
}

interface SearchInputEvent {
  detail: {
    value: string;
  };
}

interface IndexedTapEvent {
  currentTarget: {
    dataset: {
      index?: number | string;
    };
  };
}

interface RegionChangeEvent {
  type: "begin" | "end";
  causedBy?: "drag" | "scale" | "gesture" | "update";
}

interface ListScrollEvent {
  detail: {
    scrollTop: number;
  };
}

interface ListTouchEvent {
  touches: Array<{
    clientY?: number;
    pageY?: number;
  }>;
}

interface WindowMetrics {
  windowWidth?: number;
  statusBarHeight?: number;
  safeArea?: {
    top: number;
  };
  platform?: string;
}

interface WindowInfoApi {
  getWindowInfo?: () => WindowMetrics;
}

interface NavigationOverlayMetrics {
  navigationTotalHeight: number;
  mapHeight: number;
  expandedMapHeight: number;
  mapBackTop: number;
  mapTipTop: number;
  mapControlInset: number;
}

const FALLBACK_COORDINATE: Coordinate = {
  longitude: 116.397499,
  latitude: 39.908722
};
const POI_TYPES = "商务住宅|生活服务|公司企业|交通设施服务";
const NEARBY_PAGE_SIZE = 8;
const SEARCH_PAGE_SIZE = 8;
const AMapWXConstructor = AMapWX as unknown as new (options: {
  key: string;
}) => AMapClient;

let amapClient: AMapClient | null = null;
let mapContext: WechatMiniprogram.MapContext | null = null;
let searchTimer: ReturnType<typeof setTimeout> | undefined;
let mapRefreshTimer: ReturnType<typeof setTimeout> | undefined;
let expandLoadTimer: ReturnType<typeof setTimeout> | undefined;
let latestLocationRequest = 0;
let latestSearchRequest = 0;
let pageUnloaded = false;
let listTouchStartY: number | null = null;
let listScrollTop = 0;
let listTouchStartHeight = 0;
let listTouchStartedExpanded = false;
let listPanelGestureActive = false;

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(Math.max(value, minimum), maximum);
}

function navigationOverlayMetrics(): NavigationOverlayMetrics {
  const windowApi = wx as typeof wx & WindowInfoApi;
  let windowMetrics: WindowMetrics;
  try {
    windowMetrics = typeof windowApi.getWindowInfo === "function"
      ? windowApi.getWindowInfo()
      : wx.getSystemInfoSync();
  } catch (_error) {
    windowMetrics = { windowWidth: 375, statusBarHeight: 20, platform: "ios" };
  }
  const windowWidth = Math.max(1, Number(windowMetrics.windowWidth ?? 375));
  const statusBarHeight = Math.max(
    0,
    Number(windowMetrics.statusBarHeight ?? windowMetrics.safeArea?.top ?? 20)
  );
  const fallbackNavigationHeight = windowMetrics.platform === "android" ? 48 : 44;
  let navigationHeight = fallbackNavigationHeight;
  try {
    const capsule = wx.getMenuButtonBoundingClientRect();
    if (
      Number.isFinite(capsule.top) &&
      Number.isFinite(capsule.height) &&
      capsule.top >= statusBarHeight &&
      capsule.height > 0
    ) {
      const verticalGap = clamp(capsule.top - statusBarHeight, 0, 12);
      navigationHeight = clamp(capsule.height + verticalGap * 2, 40, 56);
    }
  } catch (_error) {
    // Some simulator versions do not expose valid capsule metrics.
  }
  const navigationTotalHeight = statusBarHeight + navigationHeight;
  const mapHeight = navigationTotalHeight + windowWidth * 500 / 750;
  const expandedMapHeight = Math.max(
    navigationTotalHeight + 88,
    mapHeight - Math.min(180, windowWidth * 0.42)
  );
  return {
    navigationTotalHeight,
    mapHeight,
    expandedMapHeight,
    mapBackTop: statusBarHeight + Math.max(0, (navigationHeight - 30) / 2),
    mapTipTop: navigationTotalHeight + 8,
    mapControlInset: windowWidth * 16 / 750
  };
}

const NAVIGATION_OVERLAY_METRICS = navigationOverlayMetrics();

function touchY(event: ListTouchEvent): number | null {
  const touch = event.touches[0];
  const value = touch?.clientY ?? touch?.pageY;
  return Number.isFinite(value) ? Number(value) : null;
}

function text(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function coordinateText(coordinate: Coordinate): string {
  return `${coordinate.longitude.toFixed(6)},${coordinate.latitude.toFixed(6)}`;
}

function parseCoordinate(value: unknown): Coordinate | null {
  const raw = text(value);
  if (!raw) {
    return null;
  }
  const [longitudeText, latitudeText] = raw.split(",");
  const longitude = Number(longitudeText);
  const latitude = Number(latitudeText);
  if (
    !Number.isFinite(longitude) ||
    !Number.isFinite(latitude) ||
    longitude < -180 ||
    longitude > 180 ||
    latitude < -90 ||
    latitude > 90
  ) {
    return null;
  }
  return { longitude, latitude };
}

function removePrefix(value: string, prefix: string): string {
  return prefix && value.startsWith(prefix) ? value.slice(prefix.length) : value;
}

function detailFromFormattedAddress(
  formattedAddress: string,
  province: string,
  rawCity: string,
  district: string
): string {
  let detail = removePrefix(formattedAddress, province);
  detail = removePrefix(detail, rawCity);
  detail = removePrefix(detail, district);
  return detail;
}

function selectedDetail(baseDetail: string, preferred?: AMapPoi): string {
  return text(preferred?.name) || baseDetail;
}

function selectionFromRegeo(
  data: AMapRegeocodeData,
  coordinate: Coordinate,
  preferred?: AMapPoi
): AddressLocationSelection {
  const component = data.addressComponent || {};
  const province = text(component.province);
  const rawCity = text(component.city);
  const city = rawCity || province;
  const district = text(component.district);
  const formattedAddress = text(data.formatted_address);
  const baseDetail = detailFromFormattedAddress(
    formattedAddress,
    province,
    rawCity,
    district
  );
  return {
    province,
    city,
    district,
    detailAddress: selectedDetail(baseDetail, preferred),
    formattedAddress,
    adcode: text(component.adcode),
    longitude: coordinate.longitude,
    latitude: coordinate.latitude,
    poiName: text(preferred?.name)
  };
}

function placeAddress(poi: AMapPoi, includeRegion: boolean): string {
  const rawAddress = text(poi.address);
  const regionParts = [text(poi.pname), text(poi.cityname), text(poi.adname)]
    .filter((part, index, parts) => Boolean(part) && parts.indexOf(part) === index);
  if (!includeRegion) {
    return rawAddress || regionParts.join("");
  }
  let detail = rawAddress;
  regionParts.forEach((part) => {
    detail = removePrefix(detail, part);
  });
  return `${regionParts.join("")}${detail}`;
}

function placeFromPoi(
  poi: AMapPoi,
  index: number,
  includeRegion = false
): PlaceItem | null {
  const coordinate = parseCoordinate(poi.location);
  const name = text(poi.name);
  if (!coordinate || !name) {
    return null;
  }
  const distance = Number(text(poi.distance));
  return {
    ...coordinate,
    id: text(poi.id) || `${coordinateText(coordinate)}-${index}`,
    name,
    address: placeAddress(poi, includeRegion),
    location: coordinateText(coordinate),
    distanceText: Number.isFinite(distance) && distance >= 0
      ? `${Math.round(distance)}m`
      : ""
  };
}

function getCurrentLocation(): Promise<Coordinate> {
  return new Promise((resolve, reject) => {
    wx.getLocation({
      type: "gcj02",
      isHighAccuracy: true,
      highAccuracyExpireTime: 5000,
      success: ({ longitude, latitude }) => resolve({ longitude, latitude }),
      fail: reject
    });
  });
}

function getMapCenter(): Promise<Coordinate> {
  return new Promise((resolve, reject) => {
    if (!mapContext) {
      reject(new Error("地图尚未准备完成"));
      return;
    }
    mapContext.getCenterLocation({
      success: ({ longitude, latitude }) => resolve({ longitude, latitude }),
      fail: reject
    });
  });
}

function reverseGeocode(client: AMapClient, coordinate: Coordinate): Promise<AMapRegeocodeData> {
  return new Promise((resolve, reject) => {
    client.getRegeo({
      location: coordinateText(coordinate),
      success: (data) => {
        const regeo = data[0]?.regeocodeData;
        if (regeo) {
          resolve(regeo);
          return;
        }
        reject(new Error("高德地图未返回地址信息"));
      },
      fail: reject
    });
  });
}

function nearbyPlaces(
  client: AMapClient,
  coordinate: Coordinate,
  pageNumber: number
): Promise<AMapPoi[]> {
  return new Promise((resolve, reject) => {
    client.getPoiAround({
      location: coordinateText(coordinate),
      querytypes: POI_TYPES,
      pageNumber,
      pageSize: NEARBY_PAGE_SIZE,
      success: (data) => resolve(data.poisData || []),
      fail: reject
    });
  });
}

function searchPlaces(
  client: AMapClient,
  keywords: string,
  preferredCity: string,
  pageNumber: number
): Promise<AMapPoi[]> {
  return new Promise((resolve, reject) => {
    client.getPoiKeywords({
      keywords,
      ...(preferredCity ? { city: preferredCity } : {}),
      citylimit: false,
      pageNumber,
      pageSize: SEARCH_PAGE_SIZE,
      success: (data) => resolve(data.poisData || []),
      fail: reject
    });
  });
}

function providerErrorMessage(error: unknown): string {
  if (isApiError(error)) {
    return error.message;
  }
  const providerError = error as Partial<AMapFailure> | null;
  const code = String(providerError?.errCode || "");
  if (code === "10001") {
    return "高德小程序 Key 无效或已过期";
  }
  if (code === "10002") {
    return "当前 Key 不是微信小程序类型，或未开通相应服务";
  }
  if (code === "10003") {
    return "高德地图当日调用量已超限";
  }
  if (error && typeof error === "object" && "errMsg" in error) {
    const message = String(error.errMsg || "");
    if (message.includes("auth deny") || message.includes("authorize:fail")) {
      return "请允许使用位置信息，或通过搜索选择地址";
    }
  }
  return error instanceof Error ? error.message : "地址解析失败，请重试";
}

Page({
  data: {
    ...NAVIGATION_OVERLAY_METRICS,
    mapVisibleHeight: NAVIGATION_OVERLAY_METRICS.mapHeight,
    mapLocateBottom: NAVIGATION_OVERLAY_METRICS.mapControlInset,
    panelExpanded: false,
    panelDragging: false,
    loading: true,
    ready: false,
    locating: false,
    dragging: false,
    resolving: false,
    searching: false,
    loadErrorText: "",
    locationHint: "",
    keyword: "",
    latitude: FALLBACK_COORDINATE.latitude,
    longitude: FALLBACK_COORDINATE.longitude,
    scale: 16,
    currentCity: "",
    showSearchResults: false,
    searchTips: [] as PlaceItem[],
    searchPage: 0,
    searchHasMore: false,
    searchLoadingMore: false,
    nearbyPlaces: [] as PlaceItem[],
    selected: null as AddressLocationSelection | null,
    selectedPlaceId: "",
    atCurrentLocation: false,
    nearbyPage: 0,
    nearbyHasMore: false,
    nearbyLoadingMore: false,
    nearbyLongitude: FALLBACK_COORDINATE.longitude,
    nearbyLatitude: FALLBACK_COORDINATE.latitude
  },

  onLoad() {
    pageUnloaded = false;
    void this.initialize();
  },

  onReady() {
    mapContext = wx.createMapContext("address-map", this);
  },

  onUnload() {
    pageUnloaded = true;
    latestLocationRequest += 1;
    latestSearchRequest += 1;
    if (searchTimer) clearTimeout(searchTimer);
    if (mapRefreshTimer) clearTimeout(mapRefreshTimer);
    if (expandLoadTimer) clearTimeout(expandLoadTimer);
    amapClient = null;
    mapContext = null;
    listTouchStartY = null;
    listScrollTop = 0;
    listTouchStartHeight = 0;
    listTouchStartedExpanded = false;
    listPanelGestureActive = false;
  },

  async initialize() {
    this.setData({
      loading: true,
      loadErrorText: "",
      locationHint: "",
      atCurrentLocation: false
    });
    try {
      const config = await getAmapClientConfig();
      if (!config.enabled || !config.miniProgramKey) {
        throw new Error("后台尚未启用高德地图选址，请联系管理员或稍后重试");
      }
      amapClient = new AMapWXConstructor({ key: config.miniProgramKey });
      this.setData({ loading: false, ready: true });
      try {
        const coordinate = await getCurrentLocation();
        this.setData({ atCurrentLocation: true });
        await this.resolveCoordinate(coordinate, true);
      } catch (error) {
        this.setData({
          atCurrentLocation: false,
          locationHint: providerErrorMessage(error)
        });
      }
    } catch (error) {
      this.setData({
        loading: false,
        ready: false,
        loadErrorText: providerErrorMessage(error)
      });
    }
  },

  onRetry() {
    void this.initialize();
  },

  onBackTap() {
    if (getCurrentPages().length > 1) {
      wx.navigateBack({ delta: 1 });
      return;
    }
    wx.reLaunch({ url: "/pages/index/index" });
  },

  onListScroll(event: ListScrollEvent) {
    listScrollTop = Math.max(0, Number(event.detail.scrollTop) || 0);
  },

  onListTouchStart(event: ListTouchEvent) {
    listTouchStartY = touchY(event);
    listTouchStartHeight = this.data.mapVisibleHeight;
    listTouchStartedExpanded = this.data.panelExpanded;
    listPanelGestureActive = false;
  },

  onListTouchMove(event: ListTouchEvent) {
    if (listTouchStartY === null) {
      return;
    }
    const currentY = touchY(event);
    if (currentY === null) {
      return;
    }
    let distance = currentY - listTouchStartY;
    if (!listTouchStartedExpanded && distance >= 0) {
      return;
    }
    if (listTouchStartedExpanded) {
      if (listScrollTop > 6 || distance <= 0) {
        return;
      }
      if (!listPanelGestureActive) {
        listTouchStartY = currentY;
        listTouchStartHeight = this.data.mapVisibleHeight;
        listPanelGestureActive = true;
        return;
      }
      distance = currentY - listTouchStartY;
    } else {
      listPanelGestureActive = true;
    }
    const mapVisibleHeight = clamp(
      listTouchStartHeight + distance,
      this.data.expandedMapHeight,
      this.data.mapHeight
    );
    if (Math.abs(mapVisibleHeight - this.data.mapVisibleHeight) < 0.5) {
      return;
    }
    this.setData({
      panelDragging: true,
      mapVisibleHeight,
      mapLocateBottom:
        this.data.mapHeight - mapVisibleHeight + this.data.mapControlInset
    });
  },

  onListTouchEnd() {
    const wasDragging = this.data.panelDragging;
    const startedExpanded = listTouchStartedExpanded;
    const travelled = Math.abs(this.data.mapVisibleHeight - listTouchStartHeight);
    listTouchStartY = null;
    listTouchStartHeight = 0;
    listPanelGestureActive = false;
    if (!wasDragging) {
      return;
    }
    if (startedExpanded) {
      if (travelled >= 32) {
        this.collapseResultPanel();
      } else {
        this.expandResultPanel();
      }
    } else if (travelled >= 32) {
      this.expandResultPanel();
      this.scheduleLoadMoreAfterExpand();
    } else {
      this.collapseResultPanel();
    }
    listTouchStartedExpanded = false;
  },

  expandResultPanel() {
    this.setData({
      panelExpanded: true,
      panelDragging: false,
      mapVisibleHeight: this.data.expandedMapHeight,
      mapLocateBottom:
        this.data.mapHeight - this.data.expandedMapHeight + this.data.mapControlInset
    });
  },

  collapseResultPanel() {
    this.setData({
      panelExpanded: false,
      panelDragging: false,
      mapVisibleHeight: this.data.mapHeight,
      mapLocateBottom: this.data.mapControlInset
    });
  },

  scheduleLoadMoreAfterExpand() {
    if (expandLoadTimer) clearTimeout(expandLoadTimer);
    expandLoadTimer = setTimeout(() => {
      if (pageUnloaded || !this.data.panelExpanded) {
        return;
      }
      if (this.data.showSearchResults) {
        void this.loadMoreSearchPlaces();
      } else {
        void this.loadMoreNearbyPlaces();
      }
    }, 420);
  },

  onLocateTap() {
    void this.locateAgain();
  },

  async locateAgain() {
    if (this.data.locating) {
      return;
    }
    this.setData({ locating: true, locationHint: "" });
    try {
      const coordinate = await getCurrentLocation();
      this.setData({ atCurrentLocation: true });
      await this.resolveCoordinate(coordinate, true);
    } catch (error) {
      const message = providerErrorMessage(error);
      this.setData({ locationHint: message, atCurrentLocation: false });
      wx.showToast({ title: message, icon: "none" });
    } finally {
      if (!pageUnloaded) {
        this.setData({ locating: false });
      }
    }
  },

  onMapRegionChange(event: RegionChangeEvent) {
    if (event.type === "begin") {
      if (event.causedBy !== "update") {
        if (mapRefreshTimer) clearTimeout(mapRefreshTimer);
        this.setData({ dragging: true, atCurrentLocation: false });
      }
      return;
    }
    if (event.causedBy === "update") {
      return;
    }
    this.setData({ dragging: false });
    if (mapRefreshTimer) clearTimeout(mapRefreshTimer);
    mapRefreshTimer = setTimeout(() => {
      void getMapCenter()
        .then((coordinate) => this.resolveCoordinate(coordinate, false))
        .catch((error) => {
          this.setData({ locationHint: providerErrorMessage(error) });
        });
    }, 350);
  },

  async resolveCoordinate(
    coordinate: Coordinate,
    moveMap: boolean,
    preferred?: AMapPoi
  ) {
    const client = amapClient;
    if (!client) {
      return;
    }
    const requestId = ++latestLocationRequest;
    listScrollTop = 0;
    this.setData({
      resolving: true,
      locationHint: "",
      showSearchResults: false,
      searchTips: [],
      searching: false,
      searchPage: 0,
      searchHasMore: false,
      searchLoadingMore: false,
      selectedPlaceId: text(preferred?.id),
      nearbyLoadingMore: false,
      ...(moveMap
        ? { longitude: coordinate.longitude, latitude: coordinate.latitude }
        : {})
    });
    try {
      const [regeo, pois] = await Promise.all([
        reverseGeocode(client, coordinate),
        nearbyPlaces(client, coordinate, 1)
      ]);
      if (pageUnloaded || requestId !== latestLocationRequest) {
        return;
      }
      const selected = selectionFromRegeo(regeo, coordinate, preferred);
      let places = pois
        .map((poi, index) => placeFromPoi(poi, index))
        .filter((item): item is PlaceItem => item !== null);
      const preferredPlace = preferred ? placeFromPoi(preferred, -1) : null;
      if (preferredPlace) {
        places = [preferredPlace, ...places.filter((item) => item.id !== preferredPlace.id)];
      }
      this.setData({
        resolving: false,
        selected,
        selectedPlaceId: text(preferred?.id),
        nearbyPlaces: places,
        nearbyPage: 1,
        nearbyHasMore: pois.length === NEARBY_PAGE_SIZE,
        nearbyLoadingMore: false,
        nearbyLongitude: coordinate.longitude,
        nearbyLatitude: coordinate.latitude,
        currentCity: selected.adcode || selected.city,
        locationHint: selected.formattedAddress
          ? "拖动地图或选择附近地点，确认后可继续补充门牌号"
          : "未识别到完整地址，请换个位置重试"
      });
    } catch (error) {
      if (pageUnloaded || requestId !== latestLocationRequest) {
        return;
      }
      const message = providerErrorMessage(error);
      this.setData({ resolving: false, locationHint: message });
      wx.showToast({ title: message, icon: "none" });
    }
  },

  onPlaceTap(event: IndexedTapEvent) {
    const index = Number(event.currentTarget.dataset.index);
    const place = this.data.nearbyPlaces[index];
    if (!place) {
      return;
    }
    this.setData({
      atCurrentLocation: false,
      selectedPlaceId: place.id
    });
    void this.resolveCoordinate(
      { longitude: place.longitude, latitude: place.latitude },
      true,
      {
        id: place.id,
        name: place.name,
        address: place.address,
        location: place.location
      }
    );
  },

  onNearbyLoadMore() {
    void this.loadMoreNearbyPlaces();
  },

  async loadMoreNearbyPlaces() {
    const client = amapClient;
    if (
      !client ||
      this.data.resolving ||
      this.data.nearbyLoadingMore ||
      !this.data.nearbyHasMore
    ) {
      return;
    }
    const locationRequestId = latestLocationRequest;
    const nextPage = this.data.nearbyPage + 1;
    const coordinate = {
      longitude: this.data.nearbyLongitude,
      latitude: this.data.nearbyLatitude
    };
    this.setData({ nearbyLoadingMore: true });
    try {
      const pois = await nearbyPlaces(client, coordinate, nextPage);
      if (pageUnloaded || locationRequestId !== latestLocationRequest) {
        return;
      }
      const knownIds = new Set(this.data.nearbyPlaces.map((place) => place.id));
      const places = pois
        .map((poi, index) => placeFromPoi(poi, this.data.nearbyPlaces.length + index))
        .filter((item): item is PlaceItem => item !== null && !knownIds.has(item.id));
      this.setData({
        nearbyPlaces: [...this.data.nearbyPlaces, ...places],
        nearbyPage: nextPage,
        nearbyHasMore: pois.length === NEARBY_PAGE_SIZE,
        nearbyLoadingMore: false
      });
    } catch (error) {
      if (!pageUnloaded && locationRequestId === latestLocationRequest) {
        const message = providerErrorMessage(error);
        this.setData({ nearbyLoadingMore: false, locationHint: message });
        wx.showToast({ title: message, icon: "none" });
      }
    }
  },

  onSearchInput(event: SearchInputEvent) {
    const keyword = event.detail.value.trim();
    latestSearchRequest += 1;
    listScrollTop = 0;
    this.setData({
      keyword,
      showSearchResults: Boolean(keyword),
      searching: Boolean(keyword),
      searchTips: [],
      searchPage: 0,
      searchHasMore: false,
      searchLoadingMore: false
    });
    if (searchTimer) clearTimeout(searchTimer);
    if (!keyword) {
      this.setData({
        showSearchResults: false,
        searching: false,
        searchTips: [],
        searchPage: 0,
        searchHasMore: false,
        searchLoadingMore: false
      });
      return;
    }
    searchTimer = setTimeout(() => {
      void this.search(keyword);
    }, 300);
  },

  async search(keyword: string) {
    const client = amapClient;
    if (!client) {
      return;
    }
    const requestId = ++latestSearchRequest;
    this.setData({
      showSearchResults: true,
      searching: true,
      searchTips: [],
      searchPage: 0,
      searchHasMore: false,
      searchLoadingMore: false
    });
    try {
      const pois = await searchPlaces(client, keyword, this.data.currentCity, 1);
      if (!pageUnloaded && requestId === latestSearchRequest) {
        const places = pois
          .map((poi, index) => placeFromPoi(poi, index, true))
          .filter((item): item is PlaceItem => item !== null);
        this.setData({
          searching: false,
          searchTips: places,
          searchPage: 1,
          searchHasMore: pois.length === SEARCH_PAGE_SIZE,
          searchLoadingMore: false
        });
      }
    } catch (error) {
      if (!pageUnloaded && requestId === latestSearchRequest) {
        const message = providerErrorMessage(error);
        this.setData({
          searching: false,
          searchTips: [],
          searchPage: 0,
          searchHasMore: false,
          searchLoadingMore: false,
          locationHint: message
        });
      }
    }
  },

  onSearchLoadMore() {
    void this.loadMoreSearchPlaces();
  },

  async loadMoreSearchPlaces() {
    const client = amapClient;
    const keyword = this.data.keyword.trim();
    if (
      !client ||
      !keyword ||
      !this.data.showSearchResults ||
      this.data.searching ||
      this.data.searchLoadingMore ||
      !this.data.searchHasMore
    ) {
      return;
    }
    const requestId = latestSearchRequest;
    const nextPage = this.data.searchPage + 1;
    this.setData({ searchLoadingMore: true });
    try {
      const pois = await searchPlaces(
        client,
        keyword,
        this.data.currentCity,
        nextPage
      );
      if (pageUnloaded || requestId !== latestSearchRequest) {
        return;
      }
      const knownIds = new Set(this.data.searchTips.map((place) => place.id));
      const places = pois
        .map((poi, index) =>
          placeFromPoi(poi, this.data.searchTips.length + index, true)
        )
        .filter((item): item is PlaceItem => item !== null && !knownIds.has(item.id));
      this.setData({
        searchTips: [...this.data.searchTips, ...places],
        searchPage: nextPage,
        searchHasMore: pois.length === SEARCH_PAGE_SIZE,
        searchLoadingMore: false
      });
    } catch (error) {
      if (!pageUnloaded && requestId === latestSearchRequest) {
        const message = providerErrorMessage(error);
        this.setData({ searchLoadingMore: false, locationHint: message });
        wx.showToast({ title: message, icon: "none" });
      }
    }
  },

  onClearSearch() {
    latestSearchRequest += 1;
    listScrollTop = 0;
    if (searchTimer) clearTimeout(searchTimer);
    this.setData({
      keyword: "",
      showSearchResults: false,
      searching: false,
      searchTips: [],
      searchPage: 0,
      searchHasMore: false,
      searchLoadingMore: false
    });
  },

  onTipTap(event: IndexedTapEvent) {
    const index = Number(event.currentTarget.dataset.index);
    const tip = this.data.searchTips[index];
    if (!tip) {
      wx.showToast({ title: "该地点暂无可用坐标", icon: "none" });
      return;
    }
    latestSearchRequest += 1;
    listScrollTop = 0;
    if (searchTimer) clearTimeout(searchTimer);
    this.setData({
      keyword: text(tip.name),
      showSearchResults: false,
      searching: false,
      searchTips: [],
      searchPage: 0,
      searchHasMore: false,
      searchLoadingMore: false,
      selectedPlaceId: tip.id,
      atCurrentLocation: false
    });
    void this.resolveCoordinate(
      { longitude: tip.longitude, latitude: tip.latitude },
      true,
      {
        id: tip.id,
        name: tip.name,
        address: tip.address,
        location: tip.location
      }
    );
  },

  onConfirmTap() {
    const selected = this.data.selected;
    if (!selected || !selected.province || !selected.formattedAddress) {
      wx.showToast({ title: "请先选择有效地址", icon: "none" });
      return;
    }
    this.getOpenerEventChannel().emit?.("addressSelected", selected);
    wx.navigateBack({ delta: 1 });
  }
});
