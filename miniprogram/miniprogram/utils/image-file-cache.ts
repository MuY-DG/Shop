interface CachedImageRecord {
  key: string;
  filePath: string;
  savedAt: number;
  lastAccessAt: number;
}

const CACHE_STORAGE_KEY = "customer-service-image-cache-v1";
const MAX_CACHE_ENTRIES = 80;
const MAX_CACHE_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const activeDownloads = new Map<string, Promise<string>>();

function cacheRecords(storageKey: string): CachedImageRecord[] {
  try {
    const value: unknown = wx.getStorageSync(storageKey);
    if (!Array.isArray(value)) {
      return [];
    }
    return value.filter((item): item is CachedImageRecord => {
      if (!item || typeof item !== "object" || Array.isArray(item)) {
        return false;
      }
      const record = item as Partial<CachedImageRecord>;
      return (
        typeof record.key === "string" &&
        typeof record.filePath === "string" &&
        typeof record.savedAt === "number" &&
        typeof record.lastAccessAt === "number"
      );
    });
  } catch {
    return [];
  }
}

function saveCacheRecords(records: CachedImageRecord[], storageKey: string): void {
  try {
    wx.setStorageSync(storageKey, records);
  } catch {
    // 缓存元数据写入失败不应影响图片展示。
  }
}

function fileExists(filePath: string): boolean {
  try {
    wx.getFileSystemManager().accessSync(filePath);
    return true;
  } catch {
    return false;
  }
}

function removeCachedFile(filePath: string): void {
  if (!filePath) {
    return;
  }
  wx.getFileSystemManager().unlink({
    filePath,
    fail: () => {
      // 文件可能已被微信清理。
    }
  });
}

function normalizedRecords(records: CachedImageRecord[]): CachedImageRecord[] {
  const now = Date.now();
  const retained = records
    .filter((record) => {
      const keep = now - record.savedAt <= MAX_CACHE_AGE_MS && fileExists(record.filePath);
      if (!keep) {
        removeCachedFile(record.filePath);
      }
      return keep;
    })
    .sort((left, right) => right.lastAccessAt - left.lastAccessAt);
  const removed = retained.slice(MAX_CACHE_ENTRIES);
  removed.forEach((record) => removeCachedFile(record.filePath));
  return retained.slice(0, MAX_CACHE_ENTRIES);
}

export function getCachedImageFile(key: string, storageKey = CACHE_STORAGE_KEY): string | null {
  const normalizedKey = key.trim();
  if (!normalizedKey) {
    return null;
  }
  const records = normalizedRecords(cacheRecords(storageKey));
  const record = records.find((item) => item.key === normalizedKey);
  if (!record) {
    saveCacheRecords(records, storageKey);
    return null;
  }
  record.lastAccessAt = Date.now();
  saveCacheRecords(records, storageKey);
  return record.filePath;
}

function persistImageFile(key: string, tempFilePath: string, storageKey: string): Promise<string> {
  return new Promise((resolve) => {
    wx.getFileSystemManager().saveFile({
      tempFilePath,
      success: (result) => {
        const records = normalizedRecords(cacheRecords(storageKey));
        const previous = records.find((item) => item.key === key);
        if (previous && previous.filePath !== result.savedFilePath) {
          removeCachedFile(previous.filePath);
        }
        const now = Date.now();
        const nextRecords = normalizedRecords([
          {
            key,
            filePath: result.savedFilePath,
            savedAt: now,
            lastAccessAt: now
          },
          ...records.filter((item) => item.key !== key)
        ]);
        saveCacheRecords(nextRecords, storageKey);
        resolve(result.savedFilePath);
      },
      fail: () => resolve(tempFilePath)
    });
  });
}

export function loadCachedImageFile(
  key: string,
  loader: () => Promise<string>,
  storageKey = CACHE_STORAGE_KEY
): Promise<string> {
  const cached = getCachedImageFile(key, storageKey);
  if (cached) {
    return Promise.resolve(cached);
  }
  const downloadKey = `${storageKey}:${key}`;
  const existing = activeDownloads.get(downloadKey);
  if (existing) {
    return existing;
  }
  const download = loader()
    .then((tempFilePath) => persistImageFile(key, tempFilePath, storageKey))
    .finally(() => {
      activeDownloads.delete(downloadKey);
    });
  activeDownloads.set(downloadKey, download);
  return download;
}
