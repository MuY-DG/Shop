import type { AddressUpsertRequest } from "../../../types/api";
import { ensureAppLogin } from "../../../services/auth";
import {
  createAddress,
  getAddress,
  updateAddress
} from "../../../services/address";

type AddressTextField =
  | "receiverName"
  | "receiverPhone"
  | "province"
  | "city"
  | "district"
  | "detailAddress";

interface InputEvent {
  currentTarget: {
    dataset: Record<string, string | undefined>;
  };
  detail: {
    value: string;
  };
}

interface SwitchEvent {
  detail: {
    value: boolean;
  };
}

interface AddressEditData extends AddressUpsertRequest {
  addressId: number;
  loading: boolean;
  submitting: boolean;
  errorText: string;
}

const TEXT_FIELDS = new Set<AddressTextField>([
  "receiverName",
  "receiverPhone",
  "province",
  "city",
  "district",
  "detailAddress"
]);

function positiveId(value: string | undefined): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0;
}

Page<AddressEditData, WechatMiniprogram.Page.CustomOption>({
  data: {
    addressId: 0,
    receiverName: "",
    receiverPhone: "",
    province: "",
    city: "",
    district: "",
    detailAddress: "",
    isDefault: false,
    loading: false,
    submitting: false,
    errorText: ""
  },
  async onLoad(options: Record<string, string | undefined>) {
    const addressId = positiveId(options.id);
    this.setData({ addressId });
    if (addressId > 0) {
      wx.setNavigationBarTitle({ title: "编辑收货地址" });
      await this.loadAddress();
    }
  },
  async loadAddress() {
    if (this.data.addressId <= 0) {
      return;
    }
    this.setData({ loading: true, errorText: "" });
    try {
      await ensureAppLogin();
      const address = await getAddress(this.data.addressId);
      this.setData({
        receiverName: address.receiverName,
        receiverPhone: address.receiverPhone,
        province: address.province,
        city: address.city,
        district: address.district,
        detailAddress: address.detailAddress,
        isDefault: address.isDefault
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "地址加载失败"
      });
    } finally {
      this.setData({ loading: false });
    }
  },
  onRetryTap() {
    void this.loadAddress();
  },
  onInput(event: InputEvent) {
    const field = event.currentTarget.dataset.field as AddressTextField | undefined;
    if (!field || !TEXT_FIELDS.has(field)) {
      return;
    }
    this.setData({ [field]: event.detail.value } as Pick<AddressEditData, AddressTextField>);
  },
  onDefaultChange(event: SwitchEvent) {
    this.setData({ isDefault: event.detail.value });
  },
  async onSubmitTap() {
    if (this.data.loading || this.data.submitting) {
      return;
    }
    const payload: AddressUpsertRequest = {
      receiverName: this.data.receiverName.trim(),
      receiverPhone: this.data.receiverPhone.trim(),
      province: this.data.province.trim(),
      city: this.data.city.trim(),
      district: this.data.district.trim(),
      detailAddress: this.data.detailAddress.trim(),
      isDefault: this.data.isDefault
    };
    if (
      !payload.receiverName ||
      !payload.receiverPhone ||
      !payload.province ||
      !payload.city ||
      !payload.district ||
      !payload.detailAddress
    ) {
      this.setData({ errorText: "请完整填写收货地址" });
      return;
    }

    this.setData({ submitting: true, errorText: "", ...payload });
    try {
      await ensureAppLogin();
      if (this.data.addressId > 0) {
        await updateAddress(this.data.addressId, payload);
      } else {
        await createAddress(payload);
      }
    } catch (error) {
      const errorText = error instanceof Error ? error.message : "保存失败";
      this.setData({ errorText, submitting: false });
      wx.showToast({ title: errorText, icon: "none" });
      return;
    }

    wx.showToast({ title: "保存成功", icon: "success" });
    try {
      await wx.navigateBack();
    } catch {
      try {
        await wx.redirectTo({ url: "/pages/address/list/list" });
      } catch {
        this.setData({ errorText: "地址已保存，请返回地址列表" });
        wx.showToast({ title: "地址已保存", icon: "none" });
      }
    }
  }
});
