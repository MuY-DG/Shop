export interface DisplayConfigDependencies {
  defaultName: string;
  load: () => Promise<unknown>;
  readCache: () => unknown;
  writeCache: (name: string) => void;
  now?: () => number;
}

export function normalizeDisplayName(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const name = value.trim();
  return name && name.length <= 32 && !/[\u0000-\u001f\u007f-\u009f]/.test(name)
    ? name
    : undefined;
}

export function privacyContractDisplayName(displayName: string, wechatName: string): string {
  return wechatName.trim() || `《${displayName}隐私保护指引》`;
}

export class DisplayConfigStore {
  private name: string;
  private restored = false;
  private lastAttempt: number | undefined;
  private flight: Promise<string> | undefined;
  private readonly listeners = new Set<(name: string) => void>();

  constructor(private readonly dependencies: DisplayConfigDependencies) {
    this.name = dependencies.defaultName;
  }

  getDisplayName(): string {
    if (!this.restored) {
      this.restored = true;
      try {
        this.name = normalizeDisplayName(this.dependencies.readCache()) || this.name;
      } catch {
        // 存储不可用时仍可使用默认名称和后台响应。
      }
    }
    return this.name;
  }

  subscribe(listener: (name: string) => void): () => void {
    this.listeners.add(listener);
    listener(this.getDisplayName());
    return () => this.listeners.delete(listener);
  }

  refresh(force = false): Promise<string> {
    this.getDisplayName();
    if (this.flight) return this.flight;
    const now = (this.dependencies.now || Date.now)();
    if (!force && this.lastAttempt !== undefined && now - this.lastAttempt < 60_000) {
      return Promise.resolve(this.name);
    }
    this.lastAttempt = now;
    this.flight = Promise.resolve().then(() => this.dependencies.load()).then((response) => {
      const name = normalizeDisplayName(
        response && typeof response === "object" && "displayName" in response
          ? (response as { displayName: unknown }).displayName
          : undefined
      );
      if (!name) return this.name;
      try {
        this.dependencies.writeCache(name);
      } catch {
        // 缓存写入失败不影响本次页面更新。
      }
      if (name !== this.name) {
        this.name = name;
        this.listeners.forEach((listener) => listener(name));
      }
      return this.name;
    }).catch(() => this.name).finally(() => {
      this.flight = undefined;
    });
    return this.flight;
  }
}
