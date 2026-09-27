import { io, Socket } from 'socket.io-client'

type EventHandler = (data: unknown) => void

class RealtimeClient {
  private socket: Socket | null = null
  private handlers: Map<string, EventHandler[]> = new Map()

  connect() {
    const token = localStorage.getItem('token')
    this.socket = io('/ws', {
      auth: { token },
      transports: ['websocket'],
      reconnectionDelay: 2000,
      reconnectionDelayMax: 30000
    })
    this.socket.onAny((event, data) => {
      this.handlers.get(event)?.forEach(h => h(data))
    })
  }

  on(event: string, handler: EventHandler) {
    if (!this.handlers.has(event)) this.handlers.set(event, [])
    this.handlers.get(event)!.push(handler)
  }

  off(event: string, handler: EventHandler) {
    const h = this.handlers.get(event)
    if (!h) return
    this.handlers.set(event, h.filter(x => x !== handler))
  }

  disconnect() { this.socket?.disconnect() }
}

export const realtime = new RealtimeClient()
