import {
  buildLegalDocumentUrl,
  COMPLIANCE_ROUTES
} from "../../../features/compliance";
import type { LegalDocumentType } from "../../../types/compliance";

type SettingsItemKey =
  | "merchant"
  | "privacy"
  | "agreement"
  | "afterSale"
  | "accountRights";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      key?: string;
    };
  };
}

const DOCUMENT_TYPES: Readonly<
  Record<Exclude<SettingsItemKey, "merchant" | "accountRights">, LegalDocumentType>
> =
  Object.freeze({
    privacy: "PRIVACY_POLICY",
    agreement: "USER_AGREEMENT",
    afterSale: "AFTER_SALE_POLICY"
  });

Page({
  data: {
    sections: [
      {
        title: "商家信息",
        items: [{
          key: "merchant",
          label: "商家经营资质",
          description: "查看经营主体、营业执照与食品经营许可"
        }]
      },
      {
        title: "账户与个人信息",
        items: [{
          key: "accountRights",
          label: "账户注销与个人信息权利",
          description: "提交、查看或撤回账户与个人信息处理申请"
        }]
      },
      {
        title: "协议与政策",
        items: [
          {
            key: "privacy",
            label: "个人信息保护政策",
            description: "查看当前生效版本"
          },
          {
            key: "agreement",
            label: "用户协议",
            description: "查看账户与服务约定"
          },
          {
            key: "afterSale",
            label: "售后服务政策",
            description: "查看退换货与退款规则"
          }
        ]
      }
    ]
  },

  onItemTap(event: DatasetEvent) {
    const key = event.currentTarget.dataset.key;
    if (key === "merchant") {
      wx.navigateTo({ url: COMPLIANCE_ROUTES.merchant });
      return;
    }
    if (key === "accountRights") {
      wx.navigateTo({ url: "/pages/account/rights/rights" });
      return;
    }
    if (key === "privacy" || key === "agreement" || key === "afterSale") {
      wx.navigateTo({ url: buildLegalDocumentUrl(DOCUMENT_TYPES[key]) });
    }
  }
});
