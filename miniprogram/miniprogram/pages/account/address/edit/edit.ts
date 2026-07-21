import {
  composeAddressDetail,
  normalizeAddressForm,
  parseAddressId,
  validateAddressForm,
  type AddressFormValue
} from "../../../../features/account-center";
import {
  createAddress,
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
  const region = [value.province, value.city, value.district]
    .map((item) => item.trim())
    .filter((item, index, values) => item && values.indexOf(item) === index)
    .join(" ");
  return [region, value.detailAddress.trim()].filter(Boolean).join(" ");
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
      locationStatusText: complete
        ? "已通过地图选择，可继续填写楼栋、单元或房间号"
        : "地址信息不完整，请重新选择地图位置",
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

  async saveAddress() {
    if (this.data.saving) {
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
