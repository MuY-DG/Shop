import {
  buildLegalDocumentUrl,
  COMPLIANCE_ROUTES
} from "../../../features/compliance";
import type { LegalDocumentType } from "../../../types/compliance";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      key?: string;
    };
  };
}

const DOCUMENT_TYPES: Readonly<Record<"agreement" | "afterSale", LegalDocumentType>> =
  Object.freeze({
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
          key: "accountCancellation",
          label: "注销账号",
          description: "查看注销后果并确认注销"
        }]
      },
      {
        title: "协议与政策",
        items: [
          {
            key: "privacy",
            label: "微信隐私保护指引",
            description: "隐私保护指引由微信小程序平台只读展示"
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
    if (key === "accountCancellation") {
      wx.navigateTo({ url: "/pages/account/cancellation/cancellation" });
      return;
    }
    if (key === "privacy") {
      wx.openPrivacyContract({
        fail: () => {
          wx.showToast({ title: "暂时无法打开微信隐私保护指引", icon: "none" });
        }
      });
      return;
    }
    if (key === "agreement" || key === "afterSale") {
      wx.navigateTo({ url: buildLegalDocumentUrl(DOCUMENT_TYPES[key]) });
    }
  }
});
