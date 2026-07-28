import {
  normalizeAddressForm,
  parseAddressId,
  validateAddressForm,
  type AddressFormValue
} from "../../../../features/account-center";
import {
  createAddress,
  deleteAddress,
  getAddress,
  updateAddress
} from "../../../../services/address";
import { isApiError } from "../../../../utils/api-error";

type AddressTextField = "receiverName" | "receiverPhone";

interface InputEvent {
  detail: {
    value: string;
  };
  currentTarget: {
    dataset: {
      field?: string;
    };
  };
}

interface SwitchEvent {
  detail: {
    value: boolean;
  };
}

interface RegionPickerEvent {
  detail: {
    value: string[];
  };
}

interface RegionValue {
  province: string;
  city: string;
  district: string;
}

interface LocationCoordinate {
  longitude: number;
  latitude: number;
}

const EMPTY_FORM: AddressFormValue = {
  receiverName: "",
  receiverPhone: "",
  province: "",
  city: "",
  district: "",
  detailAddress: "",
  locationName: "",
  doorplate: "",
  isDefault: false
};

let latestRequest = 0;

function text(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function isTextField(value: unknown): value is AddressTextField {
  return value === "receiverName" || value === "receiverPhone";
}

function formRegion(value: AddressFormValue): RegionValue {
  return {
    province: text(value.province),
    city: text(value.city),
    district: text(value.district)
  };
}

function regionPickerValue(region: RegionValue): string[] {
  return [region.province, region.city, region.district];
}

function regionDisplay(region: RegionValue): string {
  return regionPickerValue(region)
    .filter((part, index, parts) => Boolean(part) && parts.indexOf(part) === index)
    .join(" ");
}

function completeRegion(region: RegionValue): boolean {
  return Boolean(region.province && region.city && region.district);
}

function removePrefix(value: string, prefix: string): string {
  return prefix && value.startsWith(prefix)
    ? value.slice(prefix.length).trim()
    : value;
}

function stripRegionPrefix(value: string, region: RegionValue): string {
  let result = text(value).replace(/^中国/, "").trim();
  regionPickerValue(region)
    .filter((part, index, parts) => Boolean(part) && parts.indexOf(part) === index)
    .forEach((part) => {
      result = removePrefix(result, part);
    });
  return result;
}

function fullAddressDisplay(value: AddressFormValue): string {
  const region = formRegion(value);
  const regionText = regionPickerValue(region)
    .filter((part, index, parts) => Boolean(part) && parts.indexOf(part) === index)
    .join("");
  const detail = stripRegionPrefix(value.detailAddress, region);
  return `${regionText}${detail}`.trim();
}

function parseRegionFromLocation(address: string): RegionValue {
  const normalized = text(address).replace(/^中国/, "").trim();
  let remaining = normalized;
  let province = "";
  let city = "";
  let district = "";

  const municipality = ["北京市", "天津市", "上海市", "重庆市"]
    .find((name) => remaining.startsWith(name));
  if (municipality) {
    province = municipality;
    city = municipality;
    remaining = removePrefix(remaining, municipality);
  } else {
    const provinceMatch = remaining.match(/^(.+?(?:特别行政区|自治区|省))/);
    if (provinceMatch) {
      province = provinceMatch[1];
      remaining = removePrefix(remaining, province);
    }
    const cityMatch = remaining.match(/^(.+?(?:自治州|地区|盟|市))/);
    if (cityMatch) {
      city = cityMatch[1];
      remaining = removePrefix(remaining, city);
    }
  }

  const districtMatch = remaining.match(/^(.+?(?:自治县|自治旗|区|县|旗|市))/);
  if (districtMatch) {
    district = districtMatch[1];
  }
  if (
    (province === "香港特别行政区" || province === "澳门特别行政区") &&
    !city
  ) {
    city = province;
  }
  return { province, city, district };
}

function locationDetail(
  address: string,
  name: string,
  region: RegionValue
): string {
  const rawAddress = text(address);
  const placeName = text(name);
  const baseAddress = stripRegionPrefix(rawAddress, region) || rawAddress;
  return baseAddress || placeName;
}

function validCoordinate(value: LocationCoordinate): boolean {
  return Number.isFinite(value.longitude) &&
    value.longitude >= -180 &&
    value.longitude <= 180 &&
    Number.isFinite(value.latitude) &&
    value.latitude >= -90 &&
    value.latitude <= 90;
}

function isCancelled(error: unknown): boolean {
  return Boolean(
    error &&
    typeof error === "object" &&
    "errMsg" in error &&
    String(error.errMsg || "").includes("cancel")
  );
}

function wechatAddressErrorText(error: unknown): string {
  if (!error || typeof error !== "object" || !("errMsg" in error)) {
    return "微信地址暂不可用，请手动填写";
  }
  const message = String(error.errMsg || "").toLowerCase();
  if (message.includes("need to be declared")) {
    return "微信地址能力配置尚未生效，请退出小程序后重新进入再试";
  }
  if (
    message.includes("api permission") ||
    message.includes("permission not open") ||
    message.includes("no permission")
  ) {
    return "请先在小程序后台开通“收货地址”接口权限，再重新尝试";
  }
  if (
    message.includes("auth deny") ||
    message.includes("authorize") ||
    message.includes("permission") ||
    message.includes("privacy")
  ) {
    return "请在微信设置中允许使用通讯地址后重试，也可手动填写";
  }
  if (message.includes("not support") || message.includes("unsupported")) {
    return "当前微信环境不支持地址导入，请手动填写";
  }
  return "请确认微信中已保存通讯地址后重试，也可手动填写";
}

function showWechatAddressError(error: unknown): void {
  const content = wechatAddressErrorText(error);
  const needsSetting = /设置中允许/.test(content);
  wx.showModal({
    title: "暂时无法导入",
    content,
    confirmText: needsSetting ? "去设置" : "我知道了",
    cancelText: "手动填写",
    showCancel: needsSetting,
    success: (result) => {
      if (needsSetting && result.confirm) {
        wx.openSetting({});
      }
    }
  });
}

function wechatLocationErrorText(error: unknown): string {
  if (!error || typeof error !== "object" || !("errMsg" in error)) {
    return "微信地图暂不可用，请稍后重试";
  }
  const message = String(error.errMsg || "").toLowerCase();
  if (message.includes("need to be declared")) {
    return "地图选址能力配置尚未生效，请退出小程序后重新进入再试";
  }
  if (
    message.includes("api permission") ||
    message.includes("permission not open") ||
    message.includes("no permission")
  ) {
    return "请先在小程序后台开通“选择地理位置”接口权限";
  }
  if (
    message.includes("auth deny") ||
    message.includes("authorize") ||
    message.includes("permission") ||
    message.includes("privacy")
  ) {
    return "请在微信设置中允许使用位置信息后重试";
  }
  if (message.includes("not support") || message.includes("unsupported")) {
    return "当前微信环境不支持地图选址";
  }
  return "微信地图暂不可用，请稍后重试";
}

function showWechatLocationError(error: unknown): void {
  const content = wechatLocationErrorText(error);
  const needsSetting = /设置中允许/.test(content);
  wx.showModal({
    title: "暂时无法打开地图",
    content,
    confirmText: needsSetting ? "去设置" : "我知道了",
    showCancel: needsSetting,
    success: (result) => {
      if (needsSetting && result.confirm) {
        wx.openSetting({});
      }
    }
  });
}

function chooseWechatAddress(): Promise<WechatMiniprogram.ChooseAddressSuccessCallbackResult> {
  return new Promise((resolve, reject) => {
    if (!wx.canIUse("chooseAddress")) {
      reject({ errMsg: "chooseAddress:fail not support" });
      return;
    }
    wx.chooseAddress({ success: resolve, fail: reject });
  });
}

function chooseWechatLocation(
  coordinate?: LocationCoordinate
): Promise<WechatMiniprogram.ChooseLocationSuccessCallbackResult> {
  return new Promise((resolve, reject) => {
    if (!wx.canIUse("chooseLocation")) {
      reject({ errMsg: "chooseLocation:fail not support" });
      return;
    }
    wx.chooseLocation({
      ...(coordinate && validCoordinate(coordinate) ? coordinate : {}),
      success: resolve,
      fail: reject
    });
  });
}

function confirmDelete(): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title: "删除收货地址",
      content: "删除后无法恢复，确定继续吗？",
      confirmText: "删除",
      confirmColor: "#B72B22",
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false)
    });
  });
}

Page({
  data: {
    addressId: "",
    navigationTitle: "新增地址",
    form: { ...EMPTY_FORM } as AddressFormValue,
    regionValue: [] as string[],
    regionDisplay: "",
    locationNameDisplay: "",
    locationAddressDisplay: "",
    needsRegionSelection: false,
    doorplate: "",
    hasSelectedLocation: false,
    locationLongitude: 0,
    locationLatitude: 0,
    loading: false,
    loaded: true,
    saving: false,
    deleting: false,
    importing: false,
    locating: false,
    locationStatusText: "",
    loadErrorText: "",
    validationErrorText: ""
  },

  onLoad(query: Record<string, string | undefined>) {
    if (query.id === undefined) {
      return;
    }
    const addressId = parseAddressId(query.id);
    if (!addressId) {
      this.setData({
        loaded: false,
        loadErrorText: "地址参数无效"
      });
      return;
    }
    this.setData({
      addressId,
      navigationTitle: "编辑地址",
      loading: true,
      loaded: false
    });
    void this.loadAddress();
  },

  onUnload() {
    latestRequest += 1;
  },

  onRetry() {
    void this.loadAddress();
  },

  async loadAddress() {
    const addressId = parseAddressId(this.data.addressId);
    if (!addressId) {
      return;
    }
    const requestId = ++latestRequest;
    this.setData({ loading: true, loadErrorText: "" });
    try {
      const address = await getAddress(addressId);
      if (requestId !== latestRequest) {
        return;
      }
      const form: AddressFormValue = {
        receiverName: address.receiverName,
        receiverPhone: address.receiverPhone,
        province: address.province,
        city: address.city,
        district: address.district,
        detailAddress: address.detailAddress,
        locationName: text(address.locationName),
        doorplate: text(address.doorplate),
        isDefault: address.isDefault
      };
      this.setData({
        form,
        regionValue: regionPickerValue(formRegion(form)),
        regionDisplay: regionDisplay(formRegion(form)),
        locationNameDisplay: form.locationName,
        locationAddressDisplay: fullAddressDisplay(form),
        needsRegionSelection: !completeRegion(formRegion(form)),
        doorplate: form.doorplate,
        loading: false,
        loaded: true,
        loadErrorText: ""
      });
    } catch (error) {
      if (requestId === latestRequest) {
        this.setData({
          loading: false,
          loaded: false,
          loadErrorText: actionError(error, "地址加载失败，请稍后重试")
        });
      }
    }
  },

  onInput(event: InputEvent) {
    const field = event.currentTarget.dataset.field;
    if (!isTextField(field)) {
      return;
    }
    this.setData({
      form: {
        ...this.data.form,
        [field]: event.detail.value
      },
      validationErrorText: ""
    });
  },

  onDefaultChange(event: SwitchEvent) {
    this.setData({
      form: {
        ...this.data.form,
        isDefault: event.detail.value
      }
    });
  },

  onRegionChange(event: RegionPickerEvent) {
    const [province = "", city = "", district = ""] =
      Array.isArray(event.detail.value) ? event.detail.value.map(text) : [];
    const region = { province, city, district };
    const form: AddressFormValue = {
      ...this.data.form,
      ...region,
      detailAddress: stripRegionPrefix(this.data.form.detailAddress, region)
    };
    this.setData({
      form,
      regionValue: regionPickerValue(region),
      regionDisplay: regionDisplay(region),
      locationNameDisplay: this.data.locationNameDisplay,
      locationAddressDisplay: fullAddressDisplay(form),
      needsRegionSelection: false,
      locationStatusText: "",
      validationErrorText: ""
    });
  },

  onLocateTap() {
    void this.openLocationPicker();
  },

  onWechatImportTap() {
    void this.importWechatAddress();
  },

  async importWechatAddress() {
    if (this.data.importing || this.data.saving || this.data.deleting) {
      return;
    }
    this.setData({ importing: true, validationErrorText: "" });
    try {
      const address = await chooseWechatAddress();
      const province = String(address.provinceName || "").trim();
      const city = String(address.cityName || "").trim() || province;
      const district = String(address.countyName || "").trim() || city;
      const receiverName = String(address.userName || "").trim();
      const receiverPhone = String(address.telNumber || "").trim();
      const detailAddress = String(
        address.detailInfoNew || address.detailInfo || address.streetName || ""
      ).trim();
      if (!receiverName || !receiverPhone || !province || !detailAddress) {
        throw new Error("微信地址信息不完整");
      }
      const form: AddressFormValue = {
        ...this.data.form,
        receiverName,
        receiverPhone,
        province,
        city,
        district,
        detailAddress,
        locationName: "",
        doorplate: ""
      };
      this.setData({
        form,
        regionValue: regionPickerValue(formRegion(form)),
        regionDisplay: regionDisplay(formRegion(form)),
        locationNameDisplay: "",
        locationAddressDisplay: fullAddressDisplay(form),
        needsRegionSelection: false,
        doorplate: "",
        hasSelectedLocation: false,
        locationLongitude: 0,
        locationLatitude: 0,
        importing: false,
        locationStatusText: ""
      });
      wx.showToast({ title: "微信地址已导入", icon: "success" });
    } catch (error) {
      this.setData({ importing: false });
      if (!isCancelled(error)) {
        showWechatAddressError(error);
      }
    }
  },

  onDoorplateInput(event: InputEvent) {
    this.setData({
      doorplate: event.detail.value,
      validationErrorText: ""
    });
  },

  async openLocationPicker() {
    if (
      this.data.locating ||
      this.data.saving ||
      this.data.importing ||
      this.data.deleting
    ) {
      return;
    }
    this.setData({ locating: true, locationStatusText: "" });
    try {
      const coordinate = this.data.hasSelectedLocation
        ? {
            longitude: this.data.locationLongitude,
            latitude: this.data.locationLatitude
          }
        : undefined;
      const location = await chooseWechatLocation(coordinate);
      const parsedRegion = parseRegionFromLocation(location.address);
      const region = parsedRegion;
      const detailAddress = locationDetail(location.address, location.name, region);
      const locationName = text(location.name);
      if (!detailAddress) {
        throw new Error("微信地图未返回有效地址，请重新选择");
      }
      const form: AddressFormValue = {
        ...this.data.form,
        ...region,
        detailAddress,
        locationName
      };
      const locationAddress =
        text(location.address) || (locationName ? "" : fullAddressDisplay(form));
      const needsRegion = !completeRegion(parsedRegion);
      this.setData({
        form,
        regionValue: regionPickerValue(region),
        regionDisplay: regionDisplay(region),
        locationNameDisplay: locationName,
        locationAddressDisplay: locationAddress,
        needsRegionSelection: needsRegion,
        hasSelectedLocation: true,
        locationLongitude: location.longitude,
        locationLatitude: location.latitude,
        locating: false,
        locationStatusText: needsRegion
          ? "位置已选择，请继续选择所在地区"
          : "",
        validationErrorText: ""
      });
      if (needsRegion) {
        wx.showToast({
          title: "请补充所在地区",
          icon: "none"
        });
      }
    } catch (error) {
      this.setData({ locating: false });
      if (!isCancelled(error)) {
        const message = error instanceof Error
          ? error.message
          : wechatLocationErrorText(error);
        this.setData({ locationStatusText: message });
        if (error instanceof Error) {
          wx.showToast({ title: message, icon: "none" });
        } else {
          showWechatLocationError(error);
        }
      }
    }
  },

  onSaveTap() {
    void this.saveAddress();
  },

  onDeleteTap() {
    void this.removeAddress();
  },

  async removeAddress() {
    const addressId = parseAddressId(this.data.addressId);
    if (!addressId || this.data.saving || this.data.deleting) {
      return;
    }
    if (!await confirmDelete()) {
      return;
    }
    this.setData({ deleting: true, validationErrorText: "" });
    try {
      await deleteAddress(addressId);
      this.setData({ deleting: false });
      wx.showToast({ title: "地址已删除", icon: "success" });
      wx.navigateBack({ delta: 1 });
    } catch (error) {
      const message = actionError(error, "地址删除失败，请稍后重试");
      this.setData({ deleting: false, validationErrorText: message });
      wx.showToast({ title: message, icon: "none" });
    }
  },

  async saveAddress() {
    if (
      this.data.locating ||
      this.data.saving ||
      this.data.importing ||
      this.data.deleting
    ) {
      return;
    }
    const formForSave: AddressFormValue = {
      ...this.data.form,
      locationName: this.data.locationNameDisplay,
      doorplate: this.data.doorplate
    };
    const validationErrorText = validateAddressForm(formForSave);
    if (validationErrorText) {
      this.setData({ validationErrorText });
      wx.showToast({ title: validationErrorText, icon: "none" });
      return;
    }
    this.setData({ saving: true, validationErrorText: "" });
    try {
      const payload = normalizeAddressForm(formForSave);
      const addressId = parseAddressId(this.data.addressId);
      if (addressId) {
        await updateAddress(addressId, payload);
      } else {
        await createAddress(payload);
      }
      this.setData({ saving: false });
      wx.showToast({ title: addressId ? "地址已更新" : "地址已保存", icon: "success" });
      wx.navigateBack({ delta: 1 });
    } catch (error) {
      const message = actionError(error, "地址保存失败，请稍后重试");
      this.setData({ saving: false, validationErrorText: message });
      wx.showToast({ title: message, icon: "none" });
    }
  }
});
