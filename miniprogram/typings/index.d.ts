import type { AppGlobalData } from "../miniprogram/types/app";

declare global {
  interface IAppOption {
    globalData: AppGlobalData;
  }
}

export {};
