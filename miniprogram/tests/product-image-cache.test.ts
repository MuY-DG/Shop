import assert from "node:assert/strict";
import { test } from "node:test";

import { getCachedProductImage, loadProductImage } from "../miniprogram/utils/product-image-cache";
import { loadCachedImageFile } from "../miniprogram/utils/image-file-cache";

function imageRuntime(options: { saveFails?: boolean; downloadFails?: boolean; statusCode?: number } = {}) {
  const storage = new Map<string, unknown>();
  const files = new Set<string>();
  const requests: string[] = [];
  const pending: Array<() => void> = [];
  let savedCount = 0;
  const runtimeGlobal = globalThis as unknown as { wx: typeof wx };
  const previousWx = runtimeGlobal.wx;
  runtimeGlobal.wx = {
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: unknown) => storage.set(key, value),
    getFileSystemManager: () => ({
      accessSync: (path: string) => {
        if (!files.has(path)) {
          throw new Error("file missing");
        }
      },
      unlink: ({ filePath }: { filePath: string }) => files.delete(filePath),
      saveFile: ({ tempFilePath, success, fail }: {
        tempFilePath: string;
        success: (value: { savedFilePath: string }) => void;
        fail: () => void;
      }) => {
        if (options.saveFails) {
          fail();
          return;
        }
        const savedFilePath = `wxfile://store/image-${++savedCount}`;
        files.delete(tempFilePath);
        files.add(savedFilePath);
        success({ savedFilePath });
      }
    }),
    getImageInfo: () => { throw new Error("图片缓存无需解码获取图片信息"); },
    downloadFile: ({ url, success, fail }: {
      url: string;
      success: (value: { tempFilePath: string; statusCode: number }) => void;
      fail: (error: Error) => void;
    }) => {
      requests.push(url);
      const path = `wxfile://tmp/image-${requests.length}`;
      pending.push(() => {
        if (options.downloadFails) {
          fail(new Error("network unavailable"));
          return;
        }
        files.add(path);
        success({ tempFilePath: path, statusCode: options.statusCode ?? 200 });
      });
    }
  } as unknown as typeof wx;
  return {
    storage,
    files,
    requests,
    finishDownloads: () => pending.splice(0).forEach((finish) => finish()),
    restore: () => { runtimeGlobal.wx = previousWx; }
  };
}

test("弹层展示和并发图片预览复用同一次下载及持久缓存", async () => {
  const runtime = imageRuntime();
  try {
    const url = "https://example.test/product/shared.webp";
    const thumbnail = loadProductImage(url);
    const preview = loadProductImage(url);
    assert.deepEqual(runtime.requests, [url]);
    runtime.finishDownloads();
    const local = await thumbnail;
    assert.equal(await preview, local);
    assert.ok(local.startsWith("wxfile://store/"));
    assert.equal(getCachedProductImage(url), local);
    assert.equal(await loadProductImage(url), local);
    assert.equal(runtime.requests.length, 1);
    assert.ok(runtime.storage.has("product-image-cache-v1"));
    assert.equal(runtime.storage.has("customer-service-image-cache-v1"), false);
  } finally {
    runtime.restore();
  }
});

test("微信清理本地图片后重新下载，不复用失效路径", async () => {
  const runtime = imageRuntime();
  try {
    const url = "https://example.test/product/deleted.webp";
    const first = loadProductImage(url);
    runtime.finishDownloads();
    runtime.files.delete(await first);
    assert.equal(getCachedProductImage(url), null);
    const second = loadProductImage(url);
    assert.equal(runtime.requests.length, 2);
    runtime.finishDownloads();
    assert.notEqual(await second, await first);
  } finally {
    runtime.restore();
  }
});

test("持久保存失败时仍复用本次运行的临时图片", async () => {
  const runtime = imageRuntime({ saveFails: true });
  try {
    const url = "https://example.test/product/temporary.webp";
    const first = loadProductImage(url);
    runtime.finishDownloads();
    const path = await first;
    assert.ok(path.startsWith("wxfile://tmp/"));
    assert.equal(await loadProductImage(url), path);
    assert.equal(runtime.requests.length, 1);
  } finally {
    runtime.restore();
  }
});

test("图片下载失败回退原地址且允许重试，不把失败结果当作缓存", async () => {
  const runtime = imageRuntime({ downloadFails: true });
  try {
    const url = "https://example.test/product/retry.webp";
    const first = loadProductImage(url);
    runtime.finishDownloads();
    assert.equal(await first, url);
    assert.equal(getCachedProductImage(url), null);
    const second = loadProductImage(url);
    runtime.finishDownloads();
    assert.equal(await second, url);
    assert.equal(runtime.requests.length, 2);
  } finally {
    runtime.restore();
  }
});

test("商品缓存和原有客服缓存独立保存，即使资源 key 相同也不混用", async () => {
  const runtime = imageRuntime();
  try {
    const loader = async () => {
      const path = "wxfile://tmp/namespace";
      runtime.files.add(path);
      return path;
    };
    const product = await loadCachedImageFile("same-key", loader, "product-image-cache-v1");
    const chat = await loadCachedImageFile("same-key", loader);
    assert.notEqual(product, chat);
    assert.ok(runtime.storage.has("product-image-cache-v1"));
    assert.ok(runtime.storage.has("customer-service-image-cache-v1"));
  } finally {
    runtime.restore();
  }
});

test("HTTP 错误响应不作为商品图片持久保存", async () => {
  const runtime = imageRuntime({ statusCode: 404 });
  try {
    const url = "https://example.test/product/not-found.webp";
    const image = loadProductImage(url);
    runtime.finishDownloads();
    assert.equal(await image, url);
    assert.equal(getCachedProductImage(url), null);
    assert.equal([...runtime.files].some((path) => path.startsWith("wxfile://store/")), false);
  } finally {
    runtime.restore();
  }
});
