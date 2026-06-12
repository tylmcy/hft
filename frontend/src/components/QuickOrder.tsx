import { Button, message } from 'antd'
import { useAccountStore, useOrderStore, useMarketStore, useAuthStore } from '../stores'
import { orderApi } from '../api/client'
import type { SubmitOrderRequest } from '../api/types'

interface QuickOrderProps {
  symbol: string
  bidPrice: string  // 买一价
  askPrice: string  // 卖一价
  onOrderSuccess?: (orderId: string) => void
}

type OrderSide = 'BUY' | 'SELL'

// Generate idempotency key
function generateIdempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
}

export function QuickOrder({ symbol, bidPrice, askPrice, onOrderSuccess }: QuickOrderProps) {
  const { account } = useAccountStore()
  const { submitting, setSubmitting } = useOrderStore()
  const { symbols } = useMarketStore()
  const { user } = useAuthStore()

  // Get symbol info
  const symbolInfo = symbols.find((s) => s.symbol === symbol)

  // Default quantity (1 lot)
  const defaultQuantity = symbolInfo ? symbolInfo.lotSize : '100'

  // Submit quick order
  const handleSubmit = async (side: OrderSide, price: string) => {
    if (!symbol || !user) {
      message.error('请选择交易标的')
      return
    }

    if (!price || parseFloat(price) <= 0) {
      message.error('价格无效')
      return
    }

    setSubmitting(true)

    try {
      const request: SubmitOrderRequest = {
        symbol,
        side,
        orderType: 'LIMIT',
        price,
        quantity: defaultQuantity,
        idempotencyKey: generateIdempotencyKey(),
      }

      const response = await orderApi.submit(request)

      if (response.code === '0') {
        message.success(`${side === 'BUY' ? '买入' : '卖出'}下单成功: ${response.data.orderId}`)
        onOrderSuccess?.(response.data.orderId)
      } else {
        message.error(response.message || '下单失败')
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || '下单失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div style={{
      padding: 12,
      background: 'rgba(15, 23, 42, 0.8)',
      borderRadius: 4,
      border: '1px solid #34d399',
    }}>
      <div style={{
        color: '#fff',
        fontSize: 12,
        fontWeight: 600,
        marginBottom: 8,
        textAlign: 'center',
      }}>
        快捷下单
      </div>
      <div style={{
        color: '#22d3ee',
        fontSize: 10,
        textAlign: 'center',
        marginBottom: 8,
      }}>
        QuickOrder.tsx
      </div>

      {/* Buy at bid price */}
      <Button
        block
        size="small"
        loading={submitting}
        onClick={() => handleSubmit('BUY', askPrice)}
        style={{
          background: 'rgba(52, 211, 153, 0.2)',
          borderColor: '#34d399',
          color: '#34d399',
          marginBottom: 8,
        }}
      >
        <div style={{ fontSize: 10 }}>买入</div>
        <div style={{ fontSize: 14, fontWeight: 700 }}>{askPrice}</div>
      </Button>

      {/* Sell at ask price */}
      <Button
        block
        size="small"
        loading={submitting}
        onClick={() => handleSubmit('SELL', bidPrice)}
        style={{
          background: 'rgba(251, 113, 133, 0.2)',
          borderColor: '#fb7185',
          color: '#fb7185',
        }}
      >
        <div style={{ fontSize: 10 }}>卖出</div>
        <div style={{ fontSize: 14, fontWeight: 700 }}>{bidPrice}</div>
      </Button>

      {/* Available funds */}
      {account && (
        <div style={{
          marginTop: 8,
          padding: '4px 8px',
          background: '#1e293b',
          borderRadius: 4,
          fontSize: 10,
        }}>
          <span style={{ color: '#94a3b8' }}>可用: </span>
          <span style={{ color: '#22d3ee' }}>
            {parseFloat(account.available).toLocaleString()}
          </span>
        </div>
      )}
    </div>
  )
}

export default QuickOrder
