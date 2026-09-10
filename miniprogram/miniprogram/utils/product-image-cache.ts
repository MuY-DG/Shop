interface ImageRecord {
  key: string;
  filePath: string;
  savedAt: number;
  lastAccessAt: number;
  temporary?: boolean;
}

const STORAGE_KEY = "product-image-cache-v1";
const MAX_ENTRIES = 80;
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_DOWNLOADS = 3;

interface CacheState {
  records: Map<string, ImageRecord>;
  loading?: Promise<void>;
  active: Map<string, Promise<string>>;
  downloadCount: number;
  queue: Array<() => void>;
  flushTimer?: ReturnType<typeof setTimeout>;
  writing: boolean;
  dirty: boolean;
}

const states = new WeakMap<object, CacheState>();
function cacheState(): CacheState {
  let state = states.get(wx);
  if (!state) {
    state = { records: new Map(), active: new Map(), downloadCount: 0, queue: [], writing: false, dirty: false };
    states.set(wx, state);
  }
  return state;
}

function removeFile(filePath: string): void {
  wx.getFileSystemManager().unlink({ filePath, fail: () => {} });
}

function scheduleFlush(state: CacheState): void {
  state.dirty = true;
  if (state.flushTimer || state.writing) return;
  state.flushTimer = setTimeout(() => {
    state.flushTimer = undefined;
    state.dirty = false;
    state.writing = true;
    wx.setStorage({
      key: STORAGE_KEY,
      data: [...state.records.values()].filter(record => !record.temporary),
      complete: () => {
        state.writing = false;
        if (state.dirty) scheduleFlush(state);
      }
    });
  }, 200);
}

function trimCache(state: CacheState): void {
  const now = Date.now();
  const records = [...state.records.values()].sort((a, b) => b.lastAccessAt - a.lastAccessAt);
  records.forEach((record, index) => {
    if (index >= MAX_ENTRIES || now - record.savedAt > MAX_AGE_MS) {
      state.records.delete(record.key);
      if (!record.temporary) removeFile(record.filePath);
    }
  });
}

function initialize(state: CacheState): Promise<void> {
  if (!state.loading) {
    state.loading = new Promise(resolve => {
      wx.getStorage({
        key: STORAGE_KEY,
        success: ({ data }) => {
          if (!Array.isArray(data)) return;
          for (const value of data) {
            if (value && typeof value.key === "string" && typeof value.filePath === "string" &&
                Number.isFinite(value.savedAt) && Number.isFinite(value.lastAccessAt)) {
              state.records.set(value.key, { key: value.key, filePath: value.filePath,
                savedAt: value.savedAt, lastAccessAt: value.lastAccessAt });
            }
          }
          trimCache(state);
          scheduleFlush(state);
        },
        complete: () => resolve()
      });
    });
  }
  return state.loading;
}

/** Memory-only peek. Rendering must handle a file removed by WeChat using invalidateProductImage. */
export function getCachedProductImage(url: string): string | null {
  const record = cacheState().records.get(url.trim());
  return record && Date.now() - record.savedAt <= MAX_AGE_MS ? record.filePath : null;
}

export function invalidateProductImage(url: string): void {
  const state = cacheState();
  const record = state.records.get(url.trim());
  if (record) {
    state.records.delete(url.trim());
    if (!record.temporary) removeFile(record.filePath);
    scheduleFlush(state);
  }
}

async function withDownloadSlot<T>(state: CacheState, action: () => Promise<T>): Promise<T> {
  if (state.downloadCount >= MAX_DOWNLOADS) {
    await new Promise<void>(resolve => state.queue.push(resolve));
  } else {
    state.downloadCount++;
  }
  try {
    return await action();
  } finally {
    const next = state.queue.shift();
    if (next) next();
    else state.downloadCount--;
  }
}

function exists(path: string): Promise<boolean> {
  return new Promise(resolve => wx.getFileSystemManager().access({
    path, success: () => resolve(true), fail: () => resolve(false)
  }));
}

function download(url: string): Promise<string> {
  return new Promise((resolve, reject) => wx.downloadFile({
    url, timeout: 15000,
    success: result => {
      if (result.statusCode >= 200 && result.statusCode < 300 && result.tempFilePath?.trim()) {
        resolve(result.tempFilePath);
      } else reject(new Error("图片下载失败"));
    },
    fail: reject
  }));
}

function persist(path: string): Promise<{ filePath: string; temporary: boolean }> {
  return new Promise(resolve => wx.getFileSystemManager().saveFile({
    tempFilePath: path,
    success: result => resolve({ filePath: result.savedFilePath, temporary: false }),
    fail: () => resolve({ filePath: path, temporary: true })
  }));
}

export function loadProductImage(url: string): Promise<string> {
  const source = url.trim();
  if (!/^https?:\/\//i.test(source)) return Promise.resolve(source);
  const state = cacheState();
  const existing = state.active.get(source);
  if (existing) return existing;
  const task = (async () => {
    await initialize(state);
    const record = state.records.get(source);
    if (record && Date.now() - record.savedAt <= MAX_AGE_MS && await exists(record.filePath)) {
      record.lastAccessAt = Date.now();
      scheduleFlush(state);
      return record.filePath;
    }
    state.records.delete(source);
    return withDownloadSlot(state, async () => {
      const saved = await persist(await download(source));
      const now = Date.now();
      state.records.set(source, { key: source, ...saved, savedAt: now, lastAccessAt: now });
      trimCache(state);
      scheduleFlush(state);
      return saved.filePath;
    });
  })().catch(() => source).finally(() => state.active.delete(source));
  state.active.set(source, task);
  return task;
}
