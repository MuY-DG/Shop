import { getSessionState } from "./session";
import { rawRequest } from "../utils/http";

export const ANALYTICS_VISITOR_KEY = "shop_analytics_visitor_v1";
export const ANALYTICS_QUEUE_KEY = "shop_analytics_queue_v1";
export const MAX_ANALYTICS_QUEUE_SIZE = 200;
export const MAX_ANALYTICS_BATCH_SIZE = 50;
export const MAX_ANALYTICS_EVENT_AGE_MS = 7 * 24 * 60 * 60 * 1000;
export const MAX_ANALYTICS_FUTURE_SKEW_MS = 5 * 60 * 1000;

export type AnalyticsEventType =
  | "APP_LAUNCH"
  | "PAGE_VIEW"
  | "PRODUCT_VIEW"
  | "SEARCH"
  | "CHECKOUT_START";

export interface AnalyticsContext {
  visitorId: string;
  sessionId: string;
  entryScene: string;
}

export interface AnalyticsEventInput {
  eventType: AnalyticsEventType;
  pagePath?: string;
  sourcePage?: string;
  entryScene?: string;
  searchKeyword?: string;
  checkoutSource?: "CART" | "DIRECT";
  spuId?: number;
  skuId?: number;
  quantity?: number;
}

export interface QueuedAnalyticsEvent extends AnalyticsEventInput {
  visitorId: string;
  clientEventId: string;
  sessionId: string;
  occurredAt: string;
}

export interface AnalyticsStorage {
  get(key: string): unknown;
  set(key: string, value: unknown): void;
}

export interface AnalyticsBatchResult {
  acceptedCount: number;
  duplicateCount: number;
}

export class AnalyticsBatchHttpError extends Error {
  constructor(readonly statusCode: number) {
    super(`Analytics batch failed with HTTP ${statusCode}`);
  }
}

export interface AnalyticsDependencies {
  storage: AnalyticsStorage;
  now: () => number;
  uuid: () => string;
  accessToken: () => string | null;
  send: (
    visitorId: string,
    events: Omit<QueuedAnalyticsEvent, "visitorId">[],
    accessToken: string | null
  ) => Promise<AnalyticsBatchResult>;
}

export interface AnalyticsManager {
  initialize(entryScene?: string): AnalyticsContext;
  track(event: AnalyticsEventInput): void;
  flush(): Promise<void>;
  context(): AnalyticsContext | null;
  queuedEvents(): QueuedAnalyticsEvent[];
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const EVENT_TYPES = new Set<AnalyticsEventType>([
  "APP_LAUNCH",
  "PAGE_VIEW",
  "PRODUCT_VIEW",
  "SEARCH",
  "CHECKOUT_START"
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function safeString(value: unknown, maxLength: number): string | undefined {
  if (typeof value !== "string") {
    return undefined;
  }
  const normalized = value.trim();
  return normalized.length <= maxLength ? normalized : undefined;
}

function normalizeQueuedEvent(value: unknown): QueuedAnalyticsEvent | null {
  if (!isRecord(value)) {
    return null;
  }
  const eventType = safeString(value.eventType, 32) as AnalyticsEventType | undefined;
  const visitorId = safeString(value.visitorId, 64);
  const clientEventId = safeString(value.clientEventId, 64);
  const sessionId = safeString(value.sessionId, 64);
  const occurredAt = safeString(value.occurredAt, 40);
  if (
    !eventType ||
    !EVENT_TYPES.has(eventType) ||
    !visitorId ||
    !clientEventId ||
    !sessionId ||
    !occurredAt ||
    !UUID_PATTERN.test(visitorId) ||
    !UUID_PATTERN.test(clientEventId) ||
    !UUID_PATTERN.test(sessionId) ||
    !Number.isFinite(Date.parse(occurredAt))
  ) {
    return null;
  }
  const event: QueuedAnalyticsEvent = {
    visitorId,
    clientEventId,
    sessionId,
    occurredAt,
    eventType
  };
  const stringFields = [
    ["pagePath", 160],
    ["sourcePage", 160],
    ["entryScene", 32],
    ["searchKeyword", 80],
    ["checkoutSource", 20]
  ] as const;
  for (const [field, maxLength] of stringFields) {
    const normalized = safeString(value[field], maxLength);
    if (normalized !== undefined) {
      Object.assign(event, { [field]: normalized });
    }
  }
  for (const field of ["spuId", "skuId", "quantity"] as const) {
    const numeric = value[field];
    if (typeof numeric === "number" && Number.isSafeInteger(numeric) && numeric > 0) {
      Object.assign(event, { [field]: numeric });
    }
  }
  return hasRequiredDimensions(event) ? event : null;
}

function hasRequiredDimensions(event: AnalyticsEventInput): boolean {
  if (event.eventType === "SEARCH") {
    return typeof event.searchKeyword === "string" && event.searchKeyword.trim().length > 0;
  }
  if (event.eventType === "PRODUCT_VIEW") {
    return typeof event.spuId === "number" && Number.isSafeInteger(event.spuId) && event.spuId > 0;
  }
  if (event.eventType === "CHECKOUT_START") {
    return event.checkoutSource === "CART" || event.checkoutSource === "DIRECT";
  }
  return true;
}

function normalizeInput(input: AnalyticsEventInput): AnalyticsEventInput | null {
  if (!EVENT_TYPES.has(input.eventType)) {
    return null;
  }
  const normalized: AnalyticsEventInput = { eventType: input.eventType };
  const stringFields = [
    ["pagePath", 160],
    ["sourcePage", 160],
    ["entryScene", 32],
    ["searchKeyword", 80],
    ["checkoutSource", 20]
  ] as const;
  for (const [field, maxLength] of stringFields) {
    const value = safeString(input[field], maxLength);
    if (value !== undefined && value !== "") {
      Object.assign(normalized, { [field]: value });
    }
  }
  for (const field of ["spuId", "skuId", "quantity"] as const) {
    const value = input[field];
    if (typeof value === "number" && Number.isSafeInteger(value) && value > 0) {
      Object.assign(normalized, { [field]: value });
    }
  }
  return hasRequiredDimensions(normalized) ? normalized : null;
}

export function createAnalyticsManager(dependencies: AnalyticsDependencies): AnalyticsManager {
  let currentContext: AnalyticsContext | null = null;
  let queue: QueuedAnalyticsEvent[] = [];
  let flushFlight: Promise<void> | null = null;

  function safeGet(key: string): unknown {
    try {
      return dependencies.storage.get(key);
    } catch {
      return undefined;
    }
  }

  function safeSet(key: string, value: unknown): void {
    try {
      dependencies.storage.set(key, value);
    } catch {
      // Analytics is best-effort and must never block the user flow.
    }
  }

  function readQueue(): QueuedAnalyticsEvent[] {
    const stored = safeGet(ANALYTICS_QUEUE_KEY);
    if (!Array.isArray(stored)) {
      return [];
    }
    const now = dependencies.now();
    return stored
      .map(normalizeQueuedEvent)
      .filter((event): event is QueuedAnalyticsEvent => event !== null)
      .filter((event) => {
        const occurredAt = Date.parse(event.occurredAt);
        return occurredAt >= now - MAX_ANALYTICS_EVENT_AGE_MS &&
          occurredAt <= now + MAX_ANALYTICS_FUTURE_SKEW_MS;
      })
      .slice(-MAX_ANALYTICS_QUEUE_SIZE);
  }

  queue = readQueue();

  function persistQueue(): void {
    safeSet(ANALYTICS_QUEUE_KEY, queue);
  }

  function pruneExpiredEvents(): void {
    const now = dependencies.now();
    const retained = queue.filter((event) => {
      const occurredAt = Date.parse(event.occurredAt);
      return occurredAt >= now - MAX_ANALYTICS_EVENT_AGE_MS &&
        occurredAt <= now + MAX_ANALYTICS_FUTURE_SKEW_MS;
    });
    if (retained.length !== queue.length) {
      queue = retained;
      persistQueue();
    }
  }

  function visitorId(): string {
    const stored = safeGet(ANALYTICS_VISITOR_KEY);
    if (typeof stored === "string" && UUID_PATTERN.test(stored)) {
      return stored.toLowerCase();
    }
    const created = dependencies.uuid().toLowerCase();
    if (!UUID_PATTERN.test(created)) {
      throw new Error("Analytics UUID generator returned an invalid ID");
    }
    safeSet(ANALYTICS_VISITOR_KEY, created);
    return created;
  }

  function initialize(entryScene = ""): AnalyticsContext {
    currentContext = {
      visitorId: visitorId(),
      sessionId: dependencies.uuid().toLowerCase(),
      entryScene: entryScene.trim().slice(0, 32)
    };
    track({
      eventType: "APP_LAUNCH",
      pagePath: "/app",
      entryScene: currentContext.entryScene
    });
    return { ...currentContext };
  }

  function track(input: AnalyticsEventInput): void {
    try {
      if (!currentContext) {
        return;
      }
      const normalized = normalizeInput(input);
      if (!normalized) {
        return;
      }
      queue.push({
        ...normalized,
        visitorId: currentContext.visitorId,
        clientEventId: dependencies.uuid().toLowerCase(),
        sessionId: currentContext.sessionId,
        occurredAt: new Date(dependencies.now()).toISOString(),
        entryScene: normalized.entryScene ?? currentContext.entryScene
      });
      queue = queue.slice(-MAX_ANALYTICS_QUEUE_SIZE);
      persistQueue();
    } catch {
      // Analytics is best-effort and must never block the user flow.
    }
  }

  function removeEvents(events: QueuedAnalyticsEvent[]): void {
    const ids = new Set(events.map((event) => event.clientEventId));
    queue = queue.filter((event) => !ids.has(event.clientEventId));
    persistQueue();
  }

  function isDeterministicPayloadFailure(error: unknown): boolean {
    return error instanceof AnalyticsBatchHttpError &&
      (error.statusCode === 400 || error.statusCode === 422);
  }

  async function sendBatch(visitorId: string, batch: QueuedAnalyticsEvent[]): Promise<void> {
    try {
      const ids = new Set(batch.map((event) => event.clientEventId));
      const payload = batch.map(({ visitorId: ignored, ...event }) => event);
      const result = await dependencies.send(visitorId, payload, dependencies.accessToken());
      if (result.acceptedCount + result.duplicateCount !== batch.length) {
        throw new Error("Analytics batch was not fully acknowledged");
      }
      queue = queue.filter((event) => !ids.has(event.clientEventId));
      persistQueue();
    } catch (error) {
      if (!isDeterministicPayloadFailure(error)) {
        throw error;
      }
      if (batch.length === 1) {
        removeEvents(batch);
        return;
      }
      const midpoint = Math.floor(batch.length / 2);
      await sendBatch(visitorId, batch.slice(0, midpoint));
      await sendBatch(visitorId, batch.slice(midpoint));
    }
  }

  async function runFlush(): Promise<void> {
    pruneExpiredEvents();
    while (queue.length > 0) {
      const visitorId = queue[0].visitorId;
      const batch = queue
        .filter((event) => event.visitorId === visitorId)
        .slice(0, MAX_ANALYTICS_BATCH_SIZE);
      await sendBatch(visitorId, batch);
    }
  }

  function flush(): Promise<void> {
    if (flushFlight) {
      return flushFlight;
    }
    const operation = runFlush().catch(() => undefined);
    let flight: Promise<void>;
    flight = operation.finally(() => {
      if (flushFlight === flight) {
        flushFlight = null;
      }
    });
    flushFlight = flight;
    return flight;
  }

  return {
    initialize,
    track,
    flush,
    context: () => currentContext ? { ...currentContext } : null,
    queuedEvents: () => queue.map((event) => ({ ...event }))
  };
}

function randomUuid(): string {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (character) => {
    const random = Math.floor(Math.random() * 16);
    const value = character === "x" ? random : (random & 0x3) | 0x8;
    return value.toString(16);
  });
}

const defaultAnalyticsManager = createAnalyticsManager({
  storage: {
    get: (key) => wx.getStorageSync(key),
    set: (key, value) => wx.setStorageSync(key, value)
  },
  now: () => Date.now(),
  uuid: randomUuid,
  accessToken: () => getSessionState().accessToken || null,
  send: async (visitorId, events, accessToken) => {
    const result = await rawRequest<AnalyticsBatchResult>({
      url: "/app/analytics/events/batch",
      method: "POST",
      authToken: accessToken,
      data: { visitorId, events }
    });
    if (
      result.statusCode < 200 ||
      result.statusCode >= 300 ||
      result.body?.code !== 200 ||
      !result.body.data
    ) {
      throw new AnalyticsBatchHttpError(result.statusCode);
    }
    return result.body.data;
  }
});

function enqueue(event: AnalyticsEventInput): void {
  defaultAnalyticsManager.track(event);
  void defaultAnalyticsManager.flush();
}

export function initializeAnalytics(entryScene?: string): AnalyticsContext {
  const context = defaultAnalyticsManager.initialize(entryScene);
  void defaultAnalyticsManager.flush();
  return context;
}

export const flushAnalytics = (): Promise<void> => defaultAnalyticsManager.flush();
export const getAnalyticsContext = (): AnalyticsContext | null => defaultAnalyticsManager.context();
export const trackPageView = (pagePath: string, sourcePage?: string): void =>
  enqueue({ eventType: "PAGE_VIEW", pagePath, sourcePage });
export const trackProductView = (spuId: number, pagePath: string): void =>
  enqueue({ eventType: "PRODUCT_VIEW", spuId, pagePath });
export const trackSearch = (searchKeyword: string, pagePath: string): void =>
  enqueue({ eventType: "SEARCH", searchKeyword, pagePath });
export const trackCheckoutStart = (
  checkoutSource: "CART" | "DIRECT",
  pagePath: string,
  skuId?: number,
  quantity?: number
): void => enqueue({ eventType: "CHECKOUT_START", checkoutSource, pagePath, skuId, quantity });
