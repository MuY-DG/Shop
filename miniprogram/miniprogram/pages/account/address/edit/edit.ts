import {
  composeAddressDetail,
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
import type { AddressLocationSelection } from "../../../../types/location";
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

const EMPTY_FORM: AddressFormValue = {
  receiverName: "",
  receiverPhone: "",
  province: "",
  city: "",
  district: "",
  detailAddress: "",
  isDefault: false
};

let latestRequest = 0;

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

function addressDisplay(value: AddressFormValue): string {
  return value.detailAddress.trim();
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

function chooseWechatAddress(): Promise<WechatMiniprogram.ChooseAddressSuccessCallbackResult> {
  return new Promise((resolve, reject) => {
    if (!wx.canIUse("chooseAddress")) {
      reject({ errMsg: "chooseAddress:fail not support" });
      return;
    }
    wx.chooseAddress({ success: resolve, fail: reject });
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
    addressDisplay: "",
    doorplate: "",
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
        isDefault: address.isDefault
      };
      this.setData({
        form,
        addressDisplay: addressDisplay(form),
        doorplate: "",
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

  onLocateTap() {
    this.openLocationPicker();
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
        detailAddress
      };
      this.setData({
        form,
        addressDisplay: addressDisplay(form),
        doorplate: "",
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

  openLocationPicker() {
    if (this.data.locating) {
      return;
    }
    this.setData({ locating: true, locationStatusText: "" });
    wx.navigateTo({
      url: "/pages/account/address/location-picker/location-picker",
      success: ({ eventChannel }) => {
        eventChannel.on("addressSelected", (selected: AddressLocationSelection) => {
          this.applySelectedAddress(selected);
        });
      },
      fail: (error) => {
        const message = error.errMsg || "无法打开地图选址";
        this.setData({ locationStatusText: message });
        wx.showToast({ title: message, icon: "none" });
      },
      complete: () => {
        this.setData({ locating: false });
      }
    });
  },

  applySelectedAddress(selected: AddressLocationSelection) {
    const complete = Boolean(
      selected.province && selected.city && selected.district && selected.detailAddress
    );
    const form: AddressFormValue = {
      ...this.data.form,
      province: selected.province,
      city: selected.city,
      district: selected.district,
      detailAddress: selected.detailAddress
    };
    this.setData({
      form,
      addressDisplay: addressDisplay(form),
      locationStatusText: "",
      validationErrorText: ""
    });
    wx.showToast({
      title: complete ? "地址已回填" : "请补充详细地址",
      icon: "none"
    });
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
    if (this.data.saving || this.data.importing || this.data.deleting) {
      return;
    }
    const formForSave: AddressFormValue = {
      ...this.data.form,
      detailAddress: composeAddressDetail(
        this.data.form.detailAddress,
        this.data.doorplate
      )
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
