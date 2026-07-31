import { APP_CONFIG } from "../config/app-config";
import { issueCustomerServiceRealtimeTicket } from "./customer-service";

interface CustomerServiceRealtimeEvent {
  eventId: string;
  type: string;
  occurredAt: string;
  data?: Record<string, unknown>;
}

type CustomerServiceRealtimeHandler = (event: CustomerServiceRealtimeEvent) => void;
export type CustomerServiceRealtimeState =
  | "CONNECTING"
  | "CONNECTED"
  | "DISCONNECTED";
type CustomerServiceRealtimeStateHandler = (
  state: CustomerServiceRealtimeState
) => void;

class CustomerServiceRealtimeClient {
  private socket: WechatMiniprogram.SocketTask | null = null;
  private subscribers = new Set<CustomerServiceRealtimeHandler>();
  private stateSubscribers = new Set<CustomerServiceRealtimeStateHandler>();
  private connectionState: CustomerServiceRealtimeState = "DISCONNECTED";
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private heartbeatTimeoutTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempt = 0;
  private generation = 0;
  private socketOpen = false;
  private seenEventIds = new Set<string>();

  subscribe(handler: CustomerServiceRealtimeHandler): () => void {
    this.subscribers.add(handler);
    void this.connect();
    return () => {
      this.subscribers.delete(handler);
      if (!this.subscribers.size) {
        this.stop();
      }
    };
  }

  subscribeState(handler: CustomerServiceRealtimeStateHandler): () => void {
    this.stateSubscribers.add(handler);
    handler(this.connectionState);
    return () => {
      this.stateSubscribers.delete(handler);
    };
  }

  private async connect(): Promise<void> {
    if (
      this.subscribers.size === 0 ||
      this.socket ||
      this.connectionState === "CONNECTING"
    ) {
      return;
    }
    this.setConnectionState("CONNECTING");
    const generation = ++this.generation;
    try {
      const grant = await issueCustomerServiceRealtimeTicket();
      if (generation !== this.generation || this.subscribers.size === 0) {
        return;
      }
      const socket = wx.connectSocket({
        url: this.socketUrl(grant.ticket)
      });
      this.socket = socket;
      socket.onOpen(() => {
        if (generation !== this.generation) {
          return;
        }
        this.socketOpen = true;
        this.reconnectAttempt = 0;
        this.setConnectionState("CONNECTED");
        this.startHeartbeat(socket);
      });
      socket.onMessage((message) => {
        this.handleMessage(message.data);
      });
      socket.onClose(() => {
        this.handleDisconnect(generation);
      });
      socket.onError(() => {
        socket.close({});
      });
    } catch {
      this.handleDisconnect(generation);
    }
  }

  private socketUrl(ticket: string): string {
    const base = APP_CONFIG.apiBaseUrl
      .replace(/^https:/i, "wss:")
      .replace(/^http:/i, "ws:")
      .replace(/\/$/, "");
    return `${base}/realtime?ticket=${encodeURIComponent(ticket)}`;
  }

  private handleMessage(raw: string | ArrayBuffer): void {
    if (typeof raw !== "string") {
      return;
    }
    try {
      const event = JSON.parse(raw) as Partial<CustomerServiceRealtimeEvent>;
      if (event.type === "PONG") {
        this.clearHeartbeatTimeout();
        return;
      }
      if (!event.eventId || !event.type) {
        return;
      }
      if (this.seenEventIds.has(event.eventId)) {
        return;
      }
      this.seenEventIds.add(event.eventId);
      if (this.seenEventIds.size > 300) {
        const oldest = this.seenEventIds.values().next().value;
        if (oldest) {
          this.seenEventIds.delete(oldest);
        }
      }
      const normalized: CustomerServiceRealtimeEvent = {
        eventId: event.eventId,
        type: event.type,
        occurredAt: typeof event.occurredAt === "string" ? event.occurredAt : "",
        data: event.data
      };
      this.subscribers.forEach((subscriber) => subscriber(normalized));
    } catch {
      // REST 轮询仍是消息真相来源，忽略格式异常的传输事件。
    }
  }

  private handleDisconnect(generation: number): void {
    if (generation !== this.generation) {
      return;
    }
    this.clearHeartbeat();
    this.socket = null;
    this.socketOpen = false;
    this.setConnectionState("DISCONNECTED");
    if (this.subscribers.size === 0 || this.reconnectTimer) {
      return;
    }
    const delay = Math.min(1000 * 2 ** this.reconnectAttempt, 30_000);
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      void this.connect();
    }, delay);
  }

  private startHeartbeat(socket: WechatMiniprogram.SocketTask): void {
    this.clearHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.socketOpen) {
        socket.send({ data: JSON.stringify({ type: "PING" }) });
        this.clearHeartbeatTimeout();
        this.heartbeatTimeoutTimer = setTimeout(() => {
          socket.close({});
        }, 10_000);
      }
    }, 25_000);
  }

  private clearHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
    }
    this.heartbeatTimer = null;
    this.clearHeartbeatTimeout();
  }

  private clearHeartbeatTimeout(): void {
    if (this.heartbeatTimeoutTimer) {
      clearTimeout(this.heartbeatTimeoutTimer);
    }
    this.heartbeatTimeoutTimer = null;
  }

  private setConnectionState(state: CustomerServiceRealtimeState): void {
    if (this.connectionState === state) {
      return;
    }
    this.connectionState = state;
    this.stateSubscribers.forEach((subscriber) => subscriber(state));
  }

  private stop(): void {
    this.generation += 1;
    this.clearHeartbeat();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
    }
    this.reconnectTimer = null;
    this.socket?.close({ code: 1000, reason: "customer service page hidden" });
    this.socket = null;
    this.socketOpen = false;
    this.reconnectAttempt = 0;
    this.setConnectionState("DISCONNECTED");
  }
}

const realtimeClient = new CustomerServiceRealtimeClient();

export function subscribeCustomerServiceRealtime(
  handler: CustomerServiceRealtimeHandler
): () => void {
  return realtimeClient.subscribe(handler);
}

export function subscribeCustomerServiceRealtimeState(
  handler: CustomerServiceRealtimeStateHandler
): () => void {
  return realtimeClient.subscribeState(handler);
}
