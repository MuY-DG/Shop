import assert from "node:assert/strict";
import test from "node:test";

import {
  ANALYTICS_QUEUE_KEY,
  ANALYTICS_VISITOR_KEY,
  AnalyticsBatchHttpError,
  createAnalyticsManager,
  type AnalyticsDependencies,
  type AnalyticsStorage,
  type QueuedAnalyticsEvent
} from "../services/analytics";

const VISITOR_ID = "00000000-0000-4000-8000-000000000001";
const SESSION_ID = "00000000-0000-4000-8000-000000000002";
const EVENT_ID = "00000000-0000-4000-8000-000000000003";

function clone<T>(value: T): T {
  return value === undefined ? value : structuredClone(value);
}

function storage(initial: Record<string, unknown> = {}): AnalyticsStorage & {
  peek(key: string): unknown;
} {
  const values = new Map(Object.entries(initial));
  return {
    get: (key) => clone(values.get(key)),
    set: (key, value) => values.set(key, clone(value)),
    peek: (key) => clone(values.get(key))
  };
}

function uuidSequence(values: string[]): () => string {
  let index = 0;
  return () => values[index++] ?? `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`;
}

function dependencies(overrides: Partial<AnalyticsDependencies> = {}): AnalyticsDependencies {
  return {
    storage: storage(),
    now: () => 1_700_000_000_000,
    uuid: uuidSequence([VISITOR_ID, SESSION_ID, EVENT_ID]),
    accessToken: () => null,
    send: async (_visitorId, events) => ({
      acceptedCount: events.length,
      duplicateCount: 0
    }),
    ...overrides
  };
}

test("restores a valid persisted queue while constructing the manager", () => {
  const persisted: QueuedAnalyticsEvent = {
    visitorId: VISITOR_ID,
    clientEventId: EVENT_ID,
    sessionId: SESSION_ID,
    eventType: "PAGE_VIEW",
    pagePath: "/pages/home/home",
    occurredAt: "2023-11-14T22:13:20.000Z"
  };
  const persistedStorage = storage({ [ANALYTICS_QUEUE_KEY]: [persisted] });

  const manager = createAnalyticsManager(dependencies({ storage: persistedStorage }));

  assert.deepEqual(manager.queuedEvents(), [persisted]);
});

test("persists one anonymous visitor and creates a new session for each launch", () => {
  const persistedStorage = storage();
  const ids = uuidSequence([
    VISITOR_ID,
    SESSION_ID,
    EVENT_ID,
    "00000000-0000-4000-8000-000000000004",
    "00000000-0000-4000-8000-000000000005"
  ]);
  const first = createAnalyticsManager(dependencies({ storage: persistedStorage, uuid: ids }));
  const firstContext = first.initialize("1001");
  const second = createAnalyticsManager(dependencies({ storage: persistedStorage, uuid: ids }));
  const secondContext = second.initialize("1007");

  assert.equal(firstContext.visitorId, VISITOR_ID);
  assert.equal(secondContext.visitorId, VISITOR_ID);
  assert.notEqual(firstContext.sessionId, secondContext.sessionId);
  assert.equal(persistedStorage.peek(ANALYTICS_VISITOR_KEY), VISITOR_ID);
  assert.equal(second.queuedEvents().length, 2);
});

test("keeps queued events after a transport failure and never asks for a login", async () => {
  let tokenReads = 0;
  let sends = 0;
  const manager = createAnalyticsManager(dependencies({
    accessToken: () => {
      tokenReads += 1;
      return null;
    },
    send: async () => {
      sends += 1;
      throw new Error("offline");
    }
  }));
  manager.initialize("1001");

  await manager.flush();

  assert.equal(sends, 1);
  assert.equal(tokenReads, 1);
  assert.equal(manager.queuedEvents().length, 1);
});

test("removes only acknowledged events and sends the current access token without recovery", async () => {
  const sentIds: string[] = [];
  const manager = createAnalyticsManager(dependencies({
    accessToken: () => "app_existing",
    send: async (visitorId, events, accessToken) => {
      assert.equal(visitorId, VISITOR_ID);
      assert.equal(accessToken, "app_existing");
      sentIds.push(...events.map((event) => event.clientEventId));
      return { acceptedCount: events.length, duplicateCount: 0 };
    }
  }));
  manager.initialize("1001");
  manager.track({ eventType: "PAGE_VIEW", pagePath: "/pages/home/home" });

  await manager.flush();

  assert.equal(sentIds.length, 2);
  assert.deepEqual(manager.queuedEvents(), []);
});

test("drops events older than the server window so they cannot block newer events", async () => {
  const oldEvent: QueuedAnalyticsEvent = {
    visitorId: VISITOR_ID,
    clientEventId: "00000000-0000-4000-8000-000000000099",
    sessionId: SESSION_ID,
    eventType: "PAGE_VIEW",
    pagePath: "/pages/old/old",
    occurredAt: "2023-11-06T22:13:20.000Z"
  };
  const persistedStorage = storage({
    [ANALYTICS_VISITOR_KEY]: VISITOR_ID,
    [ANALYTICS_QUEUE_KEY]: [oldEvent]
  });
  const sentIds: string[] = [];
  const manager = createAnalyticsManager(dependencies({
    storage: persistedStorage,
    uuid: uuidSequence([SESSION_ID, EVENT_ID]),
    send: async (_visitorId, events) => {
      sentIds.push(...events.map((event) => event.clientEventId));
      return { acceptedCount: events.length, duplicateCount: 0 };
    }
  }));
  manager.initialize("1001");

  await manager.flush();

  assert.deepEqual(sentIds, [EVENT_ID]);
  assert.deepEqual(manager.queuedEvents(), []);
});

test("rejects event-type-specific invalid inputs before they enter the durable queue", () => {
  const manager = createAnalyticsManager(dependencies());
  manager.initialize("1001");

  manager.track({ eventType: "SEARCH", searchKeyword: "   ", pagePath: "/pages/product/list/list" });
  manager.track({ eventType: "PRODUCT_VIEW", spuId: 0, pagePath: "/pages/product/detail/detail" });
  manager.track({
    eventType: "CHECKOUT_START",
    checkoutSource: "OTHER" as unknown as "CART",
    pagePath: "/pages/cart/cart"
  });

  assert.deepEqual(manager.queuedEvents().map((event) => event.eventType), ["APP_LAUNCH"]);
});

test("isolates and drops a deterministic poison event without blocking valid events", async () => {
  const acceptedTypes: string[] = [];
  const manager = createAnalyticsManager(dependencies({
    send: async (_visitorId, events) => {
      if (events.some((event) => event.eventType === "PRODUCT_VIEW")) {
        throw new AnalyticsBatchHttpError(400);
      }
      acceptedTypes.push(...events.map((event) => event.eventType));
      return { acceptedCount: events.length, duplicateCount: 0 };
    }
  }));
  manager.initialize("1001");
  manager.track({ eventType: "PRODUCT_VIEW", spuId: 999_999, pagePath: "/pages/product/detail/detail" });
  manager.track({ eventType: "PAGE_VIEW", pagePath: "/pages/cart/cart" });

  await manager.flush();

  assert.deepEqual(acceptedTypes, ["APP_LAUNCH", "PAGE_VIEW"]);
  assert.deepEqual(manager.queuedEvents(), []);
});

test("keeps the whole queue when the server asks the client to retry later", async () => {
  const manager = createAnalyticsManager(dependencies({
    send: async () => {
      throw new AnalyticsBatchHttpError(429);
    }
  }));
  manager.initialize("1001");
  manager.track({ eventType: "PAGE_VIEW", pagePath: "/pages/home/home" });

  await manager.flush();

  assert.equal(manager.queuedEvents().length, 2);
});
