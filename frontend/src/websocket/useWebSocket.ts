import { useEffect, useRef } from 'react'
import { useAuthStore } from '../stores/authStore'
import {
  WebSocketMessage,
  ChannelType,
  KlineInterval,
  WS_CONFIG,
} from './types'

type MessageHandler = (data: any) => void

interface UseWebSocketOptions {
  url?: string
  onMessage?: (message: WebSocketMessage) => void
  onConnect?: () => void
  onDisconnect?: () => void
  onError?: (error: string) => void
}

// Generate UUID v4 for messageId
function generateMessageId(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

export function useWebSocket(options: UseWebSocketOptions = {}) {
  const { token } = useAuthStore()
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectAttemptsRef = useRef(0)
  const heartbeatTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const subscribeRateRef = useRef<{ count: number; resetTime: number }>({
    count: 0,
    resetTime: Date.now() + 1000,
  })

  const getWebSocketUrl = () => {
    if (options.url) return options.url
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    return `${protocol}//${host}/ws?token=${token}`
  }

  const startHeartbeat = () => {
    if (heartbeatTimerRef.current) {
      clearInterval(heartbeatTimerRef.current)
    }
    heartbeatTimerRef.current = setInterval(() => {
      if (wsRef.current?.readyState === WebSocket.OPEN) {
        wsRef.current.send(JSON.stringify({ type: 'ping' }))
      }
    }, WS_CONFIG.HEARTBEAT_INTERVAL)
  }

  const stopHeartbeat = () => {
    if (heartbeatTimerRef.current) {
      clearInterval(heartbeatTimerRef.current)
      heartbeatTimerRef.current = null
    }
  }

  const connect = () => {
    if (!token) return

    const url = getWebSocketUrl()
    wsRef.current = new WebSocket(url)

    wsRef.current.onopen = () => {
      console.log('[WebSocket] Connected')
      reconnectAttemptsRef.current = 0
      startHeartbeat()
      options.onConnect?.()
    }

    wsRef.current.onmessage = (event) => {
      try {
        const message: WebSocketMessage = JSON.parse(event.data)

        // Handle pong response
        if (message.type === 'pong') {
          return
        }

        // Handle error
        if (message.type === 'error') {
          console.error('[WebSocket] Error:', message.errorCode, message.message)
          options.onError?.(message.message || 'Unknown error')
          return
        }

        options.onMessage?.(message)
      } catch (err) {
        console.error('[WebSocket] Parse error:', err)
      }
    }

    wsRef.current.onclose = () => {
      console.log('[WebSocket] Disconnected')
      stopHeartbeat()
      options.onDisconnect?.()

      // Auto reconnect
      if (reconnectAttemptsRef.current < WS_CONFIG.MAX_RECONNECT_ATTEMPTS) {
        reconnectAttemptsRef.current++
        console.log(
          `[WebSocket] Reconnecting in ${WS_CONFIG.RECONNECT_INTERVAL}ms (attempt ${reconnectAttemptsRef.current})`
        )
        setTimeout(connect, WS_CONFIG.RECONNECT_INTERVAL)
      }
    }

    wsRef.current.onerror = () => {
      console.error('[WebSocket] Error')
      options.onError?.('WebSocket connection error')
    }
  }

  const disconnect = () => {
    stopHeartbeat()
    reconnectAttemptsRef.current = WS_CONFIG.MAX_RECONNECT_ATTEMPTS // Prevent auto reconnect
    wsRef.current?.close()
    wsRef.current = null
  }

  const checkRateLimit = (): boolean => {
    const now = Date.now()
    if (now > subscribeRateRef.current.resetTime) {
      subscribeRateRef.current = { count: 0, resetTime: now + 1000 }
    }
    if (subscribeRateRef.current.count >= WS_CONFIG.MAX_SUBSCRIBE_RATE) {
      console.warn('[WebSocket] Rate limit exceeded')
      return false
    }
    subscribeRateRef.current.count++
    return true
  }

  const subscribe = (channel: ChannelType, symbols: string[], interval?: KlineInterval) => {
    if (wsRef.current?.readyState !== WebSocket.OPEN) {
      console.error('[WebSocket] Not connected')
      return false
    }

    if (!checkRateLimit()) {
      return false
    }

    const message = {
      type: 'subscribe' as const,
      messageId: generateMessageId(),
      channel,
      symbols,
      ...(interval && { interval }),
    }

    wsRef.current.send(JSON.stringify(message))
    return true
  }

  const unsubscribe = (channel: ChannelType, symbols: string[], interval?: KlineInterval) => {
    if (wsRef.current?.readyState !== WebSocket.OPEN) {
      console.error('[WebSocket] Not connected')
      return false
    }

    if (!checkRateLimit()) {
      return false
    }

    const message = {
      type: 'unsubscribe' as const,
      messageId: generateMessageId(),
      channel,
      symbols,
      ...(interval && { interval }),
    }

    wsRef.current.send(JSON.stringify(message))
    return true
  }

  const isConnected = () => {
    return wsRef.current?.readyState === WebSocket.OPEN
  }

  // Auto connect when token changes
  useEffect(() => {
    if (token) {
      connect()
    } else {
      disconnect()
    }

    return () => {
      disconnect()
    }
  }, [token])

  return {
    subscribe,
    unsubscribe,
    connect,
    disconnect,
    isConnected,
  }
}

// Singleton WebSocket manager for non-hook usage
class WebSocketManager {
  private ws: WebSocket | null = null
  private handlers: Map<string, MessageHandler[]> = new Map()
  private reconnectAttempts = 0
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null

  connect(token: string, baseUrl?: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const host = baseUrl || window.location.host
      const url = `${protocol}//${host}/ws?token=${token}`

      this.ws = new WebSocket(url)

      this.ws.onopen = () => {
        this.reconnectAttempts = 0
        this.startHeartbeat()
        resolve()
      }

      this.ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data)
          if (message.type === 'pong') return

          const handlers = this.handlers.get(message.type) || []
          handlers.forEach((handler) => handler(message.data || message))
        } catch (err) {
          console.error('[WebSocketManager] Parse error:', err)
        }
      }

      this.ws.onclose = () => {
        this.stopHeartbeat()
        if (this.reconnectAttempts < WS_CONFIG.MAX_RECONNECT_ATTEMPTS) {
          this.reconnectAttempts++
          setTimeout(() => this.connect(token, baseUrl), WS_CONFIG.RECONNECT_INTERVAL)
        }
      }

      this.ws.onerror = (err) => {
        reject(err)
      }
    })
  }

  disconnect() {
    this.stopHeartbeat()
    this.reconnectAttempts = WS_CONFIG.MAX_RECONNECT_ATTEMPTS
    this.ws?.close()
    this.ws = null
  }

  private startHeartbeat() {
    this.heartbeatTimer = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: 'ping' }))
      }
    }, WS_CONFIG.HEARTBEAT_INTERVAL)
  }

  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  on(type: string, handler: MessageHandler) {
    const handlers = this.handlers.get(type) || []
    handlers.push(handler)
    this.handlers.set(type, handlers)
  }

  off(type: string, handler: MessageHandler) {
    const handlers = this.handlers.get(type) || []
    const index = handlers.indexOf(handler)
    if (index > -1) {
      handlers.splice(index, 1)
    }
  }

  subscribe(channel: ChannelType, symbols: string[], interval?: KlineInterval) {
    if (this.ws?.readyState !== WebSocket.OPEN) return false

    const message = {
      type: 'subscribe' as const,
      messageId: generateMessageId(),
      channel,
      symbols,
      ...(interval && { interval }),
    }

    this.ws.send(JSON.stringify(message))
    return true
  }

  unsubscribe(channel: ChannelType, symbols: string[], interval?: KlineInterval) {
    if (this.ws?.readyState !== WebSocket.OPEN) return false

    const message = {
      type: 'unsubscribe' as const,
      messageId: generateMessageId(),
      channel,
      symbols,
      ...(interval && { interval }),
    }

    this.ws.send(JSON.stringify(message))
    return true
  }

  isConnected() {
    return this.ws?.readyState === WebSocket.OPEN
  }
}

export const wsManager = new WebSocketManager()