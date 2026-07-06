import { request } from "../../utils/request";

interface HealthStatus {
  status: string;
  service: string;
}

Page({
  data: {
    healthText: "正在连接后端..."
  },
  async onLoad() {
    try {
      const health = await request<HealthStatus>({ url: "/app/health", auth: false });
      this.setData({
        healthText: `${health.service}: ${health.status}`
      });
    } catch (error) {
      this.setData({
        healthText: error instanceof Error ? error.message : "后端暂不可用"
      });
    }
  }
});
