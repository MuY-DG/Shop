import type { RealtimeEvent } from "../types/api";
import { issueAppRealtimeTicket } from "./customer-service";

export interface AppRealtimeConnection {
  start(): Promise<void>;
  stop(): void;
}

export interface AppRealtimeConnectionOptions {
  onEvent(event: RealtimeEvent): void;
  onStatusChange?(connected: boolean): void;
}

export function buildRealtimeUrl(apiBaseUrl: string, ticket: string): string {
  const base = apiBaseUrl.replace(/\/$/, "").replace(/^http:/, "ws:").replace(/^https:/, "wss:");
  return `${base}/realtime?ticket=${encodeURIComponent(ticket)}`;
}

class WechatRealtimeConnection implements AppRealtimeConnection {
  private socket: WechatMiniprogram.SocketTask | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectAttempt = 0;
  private generation = 0;
  private active = false;
  private seenEventIds = new Set<string>();

  constructor(private readonly options: AppRealtimeConnectionOptions) {}

  async start(): Promise<void> {
    if (this.active) {
      return;
    }
    this.active = true;
    await this.connect();
  }

  stop(): void {
    this.active = false;
    this.generation += 1;
    this.options.onStatusChange?.(false);
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.clearHeartbeat();
    this.socket?.close({ code: 1000, reason: "Page hidden" });
    this.socket = null;
  }

  private async connect(): Promise<void> {
    if (!this.active || this.socket) {
      return;
    }
    const generation = ++this.generation;
    try {
      const issued = await issueAppRealtimeTicket();
      if (!this.active || generation !== this.generation) {
        return;
      }
      const apiBaseUrl = getApp<{ globalData: { apiBaseUrl: string } }>().globalData.apiBaseUrl;
      const socket = wx.connectSocket({ url: buildRealtimeUrl(apiBaseUrl, issued.ticket) });
      this.socket = socket;
      socket.onOpen(() => {
        if (generation !== this.generation) {
          return;
        }
        this.reconnectAttempt = 0;
        this.options.onStatusChange?.(true);
        this.startHeartbeat(socket);
      });
      socket.onMessage((message) => this.handleMessage(message.data));
      socket.onError(() => socket.close({ code: 1001, reason: "Socket error" }));
      socket.onClose(() => this.handleDisconnect(generation));
    } catch {
      this.handleDisconnect(generation);
    }
  }

  private handleMessage(data: string | ArrayBuffer): void {
    if (typeof data !== "string") {
      return;
    }
    try {
      const event = JSON.parse(data) as RealtimeEvent;
      if (!event.eventId || !event.type || event.type === "PONG") {
        return;
      }
      if (this.seenEventIds.has(event.eventId)) {
        return;
      }
      this.seenEventIds.add(event.eventId);
      if (this.seenEventIds.size > 200) {
        const oldest = this.seenEventIds.values().next().value;
        if (oldest) {
          this.seenEventIds.delete(oldest);
        }
      }
      this.options.onEvent(event);
    } catch {
      // REST data remains authoritative when a malformed transport event is ignored.
    }
  }

  private handleDisconnect(generation: number): void {
    if (generation !== this.generation) {
      return;
    }
    this.socket = null;
    this.clearHeartbeat();
    this.options.onStatusChange?.(false);
    if (!this.active || this.reconnectTimer) {
      return;
    }
    const delay = Math.min(1000 * 2 ** this.reconnectAttempt, 30000);
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      void this.connect();
    }, delay);
  }

  private startHeartbeat(socket: WechatMiniprogram.SocketTask): void {
    this.clearHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      socket.send({ data: JSON.stringify({ type: "PING" }) });
    }, 25000);
  }

  private clearHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }
}

export function createAppRealtimeConnection(
  options: AppRealtimeConnectionOptions
): AppRealtimeConnection {
  return new WechatRealtimeConnection(options);
}
