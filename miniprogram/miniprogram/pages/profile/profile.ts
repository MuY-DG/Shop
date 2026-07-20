import { syncCustomTabBar } from "../../utils/tab-bar";

Page({
  onShow() {
    syncCustomTabBar(this, 4);
  }
});
