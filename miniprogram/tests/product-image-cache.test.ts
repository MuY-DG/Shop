import assert from "node:assert/strict";
import { test } from "node:test";
import { getCachedProductImage, loadProductImage, invalidateProductImage } from "../miniprogram/utils/product-image-cache";
import { loadCachedImageFile } from "../miniprogram/utils/image-file-cache";

const tick = () => new Promise<void>(resolve => setImmediate(resolve));
function imageRuntime(options: { saveFails?: boolean; downloadFails?: boolean; statusCode?: number } = {}) {
  const storage = new Map<string, unknown>();
  const files = new Set<string>();
  const requests: string[] = [];
  const pending: Array<() => void> = [];
  const calls = { reads: 0, writes: 0, access: 0, sync: 0, maxConcurrent: 0 };
  let savedCount = 0;
  const runtimeGlobal = globalThis as unknown as { wx: typeof wx };
  const previousWx = runtimeGlobal.wx;
  runtimeGlobal.wx = {
    getStorage: ({ key, success, complete }: any) => { calls.reads++; success({ data: storage.get(key) }); complete(); },
    setStorage: ({ key, data, complete }: any) => { calls.writes++; storage.set(key, data); complete(); },
    getStorageSync: (key: string) => { calls.sync++; return storage.get(key); },
    setStorageSync: (key: string, value: unknown) => { calls.sync++; storage.set(key, value); },
    getFileSystemManager: () => ({
      access: ({ path, success, fail }: any) => { calls.access++; files.has(path) ? success() : fail(); },
      accessSync: (path: string) => { calls.sync++; if (!files.has(path)) throw new Error("file missing"); },
      unlink: ({ filePath }: any) => files.delete(filePath),
      saveFile: ({ tempFilePath, success, fail }: any) => {
        if (options.saveFails) { fail(); return; }
        const savedFilePath = `wxfile://store/image-${++savedCount}`;
        files.delete(tempFilePath);
        files.add(savedFilePath);
        success({ savedFilePath });
      }
    }),
    getImageInfo: () => { throw new Error("图片缓存无需解码获取图片信息"); },
    downloadFile: ({ url, success, fail }: any) => {
      requests.push(url);
      const path = `wxfile://tmp/image-${requests.length}`;
      pending.push(() => {
        if (options.downloadFails) { fail(new Error("network unavailable")); return; }
        files.add(path);
        success({ tempFilePath: path, statusCode: options.statusCode ?? 200 });
      });
      calls.maxConcurrent = Math.max(calls.maxConcurrent, pending.length);
    }
  } as unknown as typeof wx;
  return {
    storage, files, requests, calls,
    finishDownloads: async () => {
      await tick();
      while (pending.length) {
        pending.splice(0).forEach(finish => finish());
        await tick();
      }
    },
    flushWrites: () => new Promise<void>(resolve => setTimeout(resolve, 220)),
    restore: async () => { await new Promise(resolve => setTimeout(resolve, 220)); runtimeGlobal.wx = previousWx; }
  };
}

test("并发请求复用同一次下载，图片缓存异步持久化并与客服缓存分离", async () => {
  const runtime = imageRuntime();
  try {
    const url = "https://example.test/product/shared.webp";
    const thumbnail = loadProductImage(url);
    const preview = loadProductImage(url);
    await tick();
    assert.deepEqual(runtime.requests, [url]);
    await runtime.finishDownloads();
    const local = await thumbnail;
    assert.equal(await preview, local);
    assert.ok(local.startsWith("wxfile://store/"));
    assert.equal(getCachedProductImage(url), local);
    assert.equal(await loadProductImage(url), local);
    assert.equal(runtime.requests.length, 1);
    assert.equal(runtime.calls.sync, 0);
    await runtime.flushWrites();
    assert.ok(runtime.storage.has("product-image-cache-v1"));
    assert.equal(runtime.storage.has("customer-service-image-cache-v1"), false);
  } finally { await runtime.restore(); }
});

test("微信清理本地图片后异步检查目标文件并重新下载", async () => {
  const runtime = imageRuntime();
  try {
    const url = "https://example.test/product/deleted.webp";
    const first = loadProductImage(url);
    await runtime.finishDownloads();
    const oldPath = await first;
    runtime.files.delete(oldPath);
    const second = loadProductImage(url);
    await runtime.finishDownloads();
    assert.notEqual(await second, oldPath);
    assert.equal(runtime.requests.length, 2);
    invalidateProductImage(url);
    assert.equal(getCachedProductImage(url), null);
    assert.equal(runtime.calls.sync, 0);
  } finally { await runtime.restore(); }
});

test("持久保存失败时仍复用本次运行的临时图片", async () => {
  const runtime = imageRuntime({ saveFails: true });
  try {
    const url = "https://example.test/product/temporary.webp";
    const first = loadProductImage(url);
    await runtime.finishDownloads();
    const path = await first;
    assert.ok(path.startsWith("wxfile://tmp/"));
    assert.equal(await loadProductImage(url), path);
    assert.equal(runtime.requests.length, 1);
    await runtime.flushWrites();
    assert.deepEqual(runtime.storage.get("product-image-cache-v1"), []);
  } finally { await runtime.restore(); }
});

for (const options of [{ downloadFails: true }, { statusCode: 404 }]) {
  test(`图片失败不保存失败响应且允许重试 ${JSON.stringify(options)}`, async () => {
    const runtime = imageRuntime(options);
    try {
      const url = "https://example.test/product/retry.webp";
      const first = loadProductImage(url);
      await runtime.finishDownloads();
      assert.equal(await first, url);
      assert.equal(getCachedProductImage(url), null);
      const second = loadProductImage(url);
      await runtime.finishDownloads();
      assert.equal(await second, url);
      assert.equal(runtime.requests.length, 2);
      assert.equal([...runtime.files].some(path => path.startsWith("wxfile://store/")), false);
    } finally { await runtime.restore(); }
  });
}

test("原有客服缓存接口仍独立工作", async () => {
  const runtime = imageRuntime();
  try {
    const loader = async () => {
      const path = "wxfile://tmp/namespace";
      runtime.files.add(path);
      return path;
    };
    const chat = await loadCachedImageFile("same-key", loader);
    assert.ok(chat.startsWith("wxfile://store/"));
    assert.ok(runtime.storage.has("customer-service-image-cache-v1"));
    assert.equal(runtime.storage.has("product-image-cache-v1"), false);
  } finally { await runtime.restore(); }
});

test("80 条缓存只读取一次，25 张命中只异步检查 25 个目标文件且合并写入", async () => {
  const runtime = imageRuntime();
  try {
    const now = Date.now();
    const records = Array.from({ length: 80 }, (_, index) => ({ key: `https://example.test/${index}.webp`,
      filePath: `wxfile://store/${index}`, savedAt: now, lastAccessAt: now }));
    records.forEach(record => runtime.files.add(record.filePath));
    runtime.storage.set("product-image-cache-v1", records);
    await Promise.all(records.slice(0, 25).map(record => loadProductImage(record.key)));
    assert.equal(runtime.calls.reads, 1);
    assert.equal(runtime.calls.access, 25);
    assert.equal(runtime.calls.sync, 0);
    assert.equal(runtime.requests.length, 0);
    await runtime.flushWrites();
    assert.equal(runtime.calls.writes, 1);
  } finally { await runtime.restore(); }
});

test("图片下载队列最大并发为 3，所有排队图片都能完成", async () => {
  const runtime = imageRuntime();
  try {
    const tasks = Array.from({ length: 8 }, (_, i) => loadProductImage(`https://example.test/queue/${i}.webp`));
    await tick();
    assert.equal(runtime.requests.length, 3);
    await runtime.finishDownloads();
    assert.equal((await Promise.all(tasks)).length, 8);
    assert.equal(runtime.requests.length, 8);
    assert.equal(runtime.calls.maxConcurrent, 3);
  } finally { await runtime.restore(); }
});
