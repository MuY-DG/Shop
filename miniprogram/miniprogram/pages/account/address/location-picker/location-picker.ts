import { AMapWX } from "../../../../libs/amap-wx";
import type {
  AMapClient,
  AMapFailure,
  AMapInputTip,
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

const FALLBACK_COORDINATE: Coordinate = {
  longitude: 116.397499,
  latitude: 39.908722
};
const POI_TYPES = "商务住宅|生活服务|公司企业|交通设施服务";
const AMapWXConstructor = AMapWX as unknown as new (options: {
  key: string;
}) => AMapClient;

let amapClient: AMapClient | null = null;
let mapContext: WechatMiniprogram.MapContext | null = null;
let searchTimer: ReturnType<typeof setTimeout> | undefined;
let mapRefreshTimer: ReturnType<typeof setTimeout> | undefined;
let latestLocationRequest = 0;
let latestSearchRequest = 0;
let pageUnloaded = false;

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

function combinedDetail(baseDetail: string, preferred?: AMapPoi): string {
  if (!preferred) {
    return baseDetail;
  }
  const address = text(preferred.address);
  const name = text(preferred.name);
  if (!name || baseDetail.includes(name)) {
    return address || baseDetail;
  }
  if (address && !address.includes(name)) {
    return `${address}${name}`;
  }
  return `${baseDetail}${name}`;
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
    detailAddress: combinedDetail(baseDetail, preferred),
    formattedAddress,
    adcode: text(component.adcode),
    longitude: coordinate.longitude,
    latitude: coordinate.latitude,
    poiName: text(preferred?.name)
  };
}

function placeFromPoi(poi: AMapPoi, index: number): PlaceItem | null {
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
    address: text(poi.address) || [text(poi.pname), text(poi.cityname), text(poi.adname)]
      .filter(Boolean)
      .join(""),
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

function nearbyPlaces(client: AMapClient, coordinate: Coordinate): Promise<AMapPoi[]> {
  return new Promise((resolve, reject) => {
    client.getPoiAround({
      location: coordinateText(coordinate),
      querytypes: POI_TYPES,
      success: (data) => resolve(data.poisData || []),
      fail: reject
    });
  });
}

function inputTips(
  client: AMapClient,
  keywords: string,
  coordinate: Coordinate,
  city: string
): Promise<AMapInputTip[]> {
  return new Promise((resolve, reject) => {
    client.getInputtips({
      keywords,
      location: coordinateText(coordinate),
      city: city || undefined,
      citylimit: Boolean(city),
      success: (data) => resolve((data.tips || []).filter((item) => Boolean(parseCoordinate(item.location)))),
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
    searchTips: [] as AMapInputTip[],
    nearbyPlaces: [] as PlaceItem[],
    selected: null as AddressLocationSelection | null,
    selectedPlaceId: ""
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
    amapClient = null;
    mapContext = null;
  },

  async initialize() {
    this.setData({ loading: true, loadErrorText: "", locationHint: "" });
    try {
      const config = await getAmapClientConfig();
      if (!config.enabled || !config.miniProgramKey) {
        throw new Error("后台尚未启用高德地图选址，请联系管理员或稍后重试");
      }
      amapClient = new AMapWXConstructor({ key: config.miniProgramKey });
      this.setData({ loading: false, ready: true });
      try {
        const coordinate = await getCurrentLocation();
        await this.resolveCoordinate(coordinate, true);
      } catch (error) {
        this.setData({
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
      await this.resolveCoordinate(coordinate, true);
    } catch (error) {
      const message = providerErrorMessage(error);
      this.setData({ locationHint: message });
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
        this.setData({ dragging: true });
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
    this.setData({
      resolving: true,
      locationHint: "",
      searchTips: [],
      ...(moveMap
        ? { longitude: coordinate.longitude, latitude: coordinate.latitude }
        : {})
    });
    try {
      const [regeo, pois] = await Promise.all([
        reverseGeocode(client, coordinate),
        nearbyPlaces(client, coordinate)
      ]);
      if (pageUnloaded || requestId !== latestLocationRequest) {
        return;
      }
      const selected = selectionFromRegeo(regeo, coordinate, preferred);
      const places = pois
        .map(placeFromPoi)
        .filter((item): item is PlaceItem => item !== null)
        .slice(0, 20);
      this.setData({
        resolving: false,
        selected,
        selectedPlaceId: text(preferred?.id),
        nearbyPlaces: places,
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

  onSearchInput(event: SearchInputEvent) {
    const keyword = event.detail.value.trim();
    this.setData({ keyword });
    if (searchTimer) clearTimeout(searchTimer);
    if (!keyword) {
      latestSearchRequest += 1;
      this.setData({ searching: false, searchTips: [] });
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
    this.setData({ searching: true });
    try {
      const tips = await inputTips(
        client,
        keyword,
        this.data.selected
          ? {
              longitude: this.data.selected.longitude,
              latitude: this.data.selected.latitude
            }
          : { longitude: this.data.longitude, latitude: this.data.latitude },
        this.data.currentCity
      );
      if (!pageUnloaded && requestId === latestSearchRequest) {
        this.setData({ searching: false, searchTips: tips.slice(0, 20) });
      }
    } catch (error) {
      if (!pageUnloaded && requestId === latestSearchRequest) {
        const message = providerErrorMessage(error);
        this.setData({ searching: false, searchTips: [], locationHint: message });
      }
    }
  },

  onClearSearch() {
    latestSearchRequest += 1;
    if (searchTimer) clearTimeout(searchTimer);
    this.setData({ keyword: "", searching: false, searchTips: [] });
  },

  onTipTap(event: IndexedTapEvent) {
    const index = Number(event.currentTarget.dataset.index);
    const tip = this.data.searchTips[index];
    const coordinate = parseCoordinate(tip?.location);
    if (!tip || !coordinate) {
      wx.showToast({ title: "该地点暂无可用坐标", icon: "none" });
      return;
    }
    this.setData({ keyword: text(tip.name), searchTips: [] });
    void this.resolveCoordinate(coordinate, true, tip);
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
