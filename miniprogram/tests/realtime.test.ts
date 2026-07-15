import assert from "node:assert/strict";
import test from "node:test";

import { buildRealtimeUrl } from "../services/realtime";

test("realtime URL upgrades HTTPS and safely encodes the one-time ticket", () => {
  assert.equal(
    buildRealtimeUrl("https://pay-dev.muybaby6.icu/", "ticket/with+symbols="),
    "wss://pay-dev.muybaby6.icu/realtime?ticket=ticket%2Fwith%2Bsymbols%3D"
  );
});

test("realtime URL keeps local HTTP development on WebSocket", () => {
  assert.equal(
    buildRealtimeUrl("http://127.0.0.1:8080", "local-ticket"),
    "ws://127.0.0.1:8080/realtime?ticket=local-ticket"
  );
});
