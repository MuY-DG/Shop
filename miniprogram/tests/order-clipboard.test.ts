import assert from "node:assert/strict";
import test from "node:test";

import { copyOrderNo } from "../miniprogram/features/order-center";

interface ToastRecord {
  title: string;
  icon: "success" | "none";
}

function clipboardRuntime(options: {
  setFailure?: { errMsg?: string; errno?: number | string };
} = {}) {
  const written: string[] = [];
  const toasts: ToastRecord[] = [];
  return {
    written,
    toasts,
    runtime: {
      setClipboardData(callbacks: {
        data: string;
        success?: () => void;
        fail?: (error: { errMsg?: string; errno?: number | string }) => void;
      }) {
        written.push(callbacks.data);
        if (options.setFailure) callbacks.fail?.(options.setFailure);
        else callbacks.success?.();
      },
      showToast(toast: ToastRecord) {
        toasts.push(toast);
      }
    }
  };
}

test("复制订单号会规范文本并写入剪贴板", () => {
  const fixture = clipboardRuntime();
  copyOrderNo("  ORD-20260807  ", fixture.runtime);
  assert.deepEqual(fixture.written, ["ORD-20260807"]);
  assert.deepEqual(fixture.toasts, [{ title: "订单号已复制", icon: "success" }]);
});

test("复制订单号对空值和普通写入失败给出明确反馈", () => {
  const empty = clipboardRuntime();
  copyOrderNo("  ", empty.runtime);
  assert.deepEqual(empty.written, []);
  assert.deepEqual(empty.toasts, [{ title: "订单号暂不可用", icon: "none" }]);

  const writeFailure = clipboardRuntime({ setFailure: { errMsg: "setClipboardData:fail" } });
  copyOrderNo("ORD-1", writeFailure.runtime);
  assert.deepEqual(writeFailure.toasts, [{ title: "复制失败，请稍后重试", icon: "none" }]);
});

test("剪贴板隐私声明和用户授权失败时显示可操作提示", () => {
  const undeclared = clipboardRuntime({
    setFailure: {
      errMsg: "setClipboardData:fail api scope is not declared in the privacy agreement",
      errno: 112
    }
  });
  copyOrderNo("ORD-1", undeclared.runtime);
  assert.deepEqual(undeclared.toasts, [{ title: "请先在后台声明剪切板用途", icon: "none" }]);

  const denied = clipboardRuntime({
    setFailure: { errMsg: "setClipboardData:fail user deny", errno: 103 }
  });
  copyOrderNo("ORD-1", denied.runtime);
  assert.deepEqual(denied.toasts, [{ title: "请同意隐私保护指引后重试", icon: "none" }]);
});
