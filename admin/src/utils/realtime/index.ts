import { issueAdminRealtimeTicket } from '@/api/customer-service'

export type RealtimeEvent = Api.Realtime.Event<Record<string, unknown>>
export type RealtimeEventHandler = (event: RealtimeEvent) => void

class RealtimeClient {
  private socket: WebSocket | null = null
  private subscribers = new Set<RealtimeEventHandler>()
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private closeTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempt = 0
  private generation = 0
  private seenEventIds = new Set<string>()

  subscribe(handler: RealtimeEventHandler) {
    this.subscribers.add(handler)
    if (this.closeTimer) {
      clearTimeout(this.closeTimer)
      this.closeTimer = null
    }
    void this.connect()
    return () => {
      this.subscribers.delete(handler)
      if (this.subscribers.size === 0) {
        this.closeTimer = setTimeout(() => this.stop(), 5000)
      }
    }
  }

  private async connect() {
    if (
      this.subscribers.size === 0 ||
      this.socket?.readyState === WebSocket.OPEN ||
      this.socket?.readyState === WebSocket.CONNECTING
    ) {
      return
    }
    const generation = ++this.generation
    try {
      const ticket = await issueAdminRealtimeTicket()
      if (generation !== this.generation || this.subscribers.size === 0) return
      const socket = new WebSocket(this.buildUrl(ticket.ticket))
      this.socket = socket
      socket.onopen = () => {
        if (generation !== this.generation) return
        this.reconnectAttempt = 0
        this.startHeartbeat(socket)
      }
      socket.onmessage = (message) => this.handleMessage(message)
      socket.onclose = () => this.handleDisconnect(generation)
      socket.onerror = () => socket.close()
    } catch {
      this.handleDisconnect(generation)
    }
  }

  private buildUrl(ticket: string) {
    const apiBase = import.meta.env.VITE_API_URL || '/'
    const base = new URL(apiBase, window.location.origin)
    const url = new URL('/realtime', base)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    url.searchParams.set('ticket', ticket)
    return url.toString()
  }

  private handleMessage(message: MessageEvent) {
    if (typeof message.data !== 'string') return
    try {
      const event = JSON.parse(message.data) as RealtimeEvent
      if (!event.eventId || !event.type || event.type === 'PONG') return
      if (this.seenEventIds.has(event.eventId)) return
      this.seenEventIds.add(event.eventId)
      if (this.seenEventIds.size > 500) {
        const oldest = this.seenEventIds.values().next().value
        if (oldest) this.seenEventIds.delete(oldest)
      }
      this.subscribers.forEach((subscriber) => subscriber(event))
    } catch {
      // Ignore malformed transport messages. REST remains the source of truth.
    }
  }

  private handleDisconnect(generation: number) {
    if (generation !== this.generation) return
    this.clearHeartbeat()
    this.socket = null
    if (this.subscribers.size === 0 || this.reconnectTimer) return
    const delay = Math.min(1000 * 2 ** this.reconnectAttempt, 30000)
    this.reconnectAttempt += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      void this.connect()
    }, delay)
  }

  private startHeartbeat(socket: WebSocket) {
    this.clearHeartbeat()
    this.heartbeatTimer = setInterval(() => {
      if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'PING' }))
    }, 25000)
  }

  private clearHeartbeat() {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
    this.heartbeatTimer = null
  }

  private stop() {
    this.generation += 1
    this.clearHeartbeat()
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    this.socket?.close(1000, 'No active subscribers')
    this.socket = null
  }
}

export const realtimeClient = new RealtimeClient()
