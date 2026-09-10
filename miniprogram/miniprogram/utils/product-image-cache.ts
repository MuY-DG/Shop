import { getCachedImageFile, loadCachedImageFile } from "./image-file-cache";

const CACHE_STORAGE_KEY = "product-image-cache-v1";
const sessionFiles = new Map<string, string>();

export function getCachedProductImage(url: string): string | null {
  const cached = getCachedImageFile(url, CACHE_STORAGE_KEY);
  if (cached) {
    return cached;
  }
  const temporaryPath = sessionFiles.get(url);
  if (temporaryPath) {
    try {
      wx.getFileSystemManager().accessSync(temporaryPath);
      return temporaryPath;
    } catch {
      sessionFiles.delete(url);
    }
  }
  return null;
}

export function loadProductImage(url: string): Promise<string> {
  const source = url.trim();
  if (!/^https?:\/\//i.test(source)) {
    return Promise.resolve(source);
  }
  const cached = getCachedProductImage(source);
  if (cached) {
    return Promise.resolve(cached);
  }
  return loadCachedImageFile(source, () => new Promise<string>((resolve, reject) => {
    wx.downloadFile({
      url: source,
      timeout: 15000,
      success: (result) => {
        const path = result.tempFilePath?.trim();
        if (result.statusCode >= 200 && result.statusCode < 300 && path) {
          resolve(path);
        } else {
          reject(new Error("图片下载失败"));
        }
      },
      fail: reject
    });
  }), CACHE_STORAGE_KEY).then((path) => {
    sessionFiles.set(source, path);
    if (sessionFiles.size > 80) {
      const oldest = sessionFiles.keys().next().value;
      if (oldest) {
        sessionFiles.delete(oldest);
      }
    }
    return path;
  }).catch(() => source);
}
