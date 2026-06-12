import { useState, useCallback } from 'react'
import { Input, Button, Radio, message, Tooltip, Space } from 'antd'
import {
  CaretUpOutlined,
  CaretDownOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons'
import { useAccountStore, useOrderStore, useMarketStore, useAuthStore } from '../stores'
import { orderApi } from '../api/client'
import type { SubmitOrderRequest } from '../api/types'

type OrderSide = 'BUY' | 'SELL'
type OrderType = 'LIMIT' | 'MARKET' | 'FAK' | 'FOK'
type PositionEffect = 'OPEN' | 'CLOSE'  // For futures
type OptionType = 'CALL' | 'PUT'  // For options

interface OrderPanelProps {
  symbol: string
  instrumentType?: 'STOCK' | 'FUTURE' | 'OPTION'
  onOrderSuccess?: (orderId: string) => void
}

// Generate idempotency key
function generateIdempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
}

export function OrderPanel({ symbol, instrumentType = 'STOCK', onOrderSuccess }: OrderPanelProps) {
  const { account } = useAccountStore()
  const { submitting, setSubmitting } = useOrderStore()
  const { symbols } = useMarketStore()
  const { user } = useAuthStore()

  const [side, setSide] = useState<OrderSide>('BUY')
  const [orderType, setOrderType] = useState<OrderType>('LIMIT')
  const [price, setPrice] = useState<string>('')
  const [quantity, setQuantity] = useState<string>('100')
  const [positionEffect, setPositionEffect] = useState<PositionEffect>('OPEN')
  const [optionType, setOptionType] = useState<OptionType>('CALL')
  const [contract, setContract] = useState<string>('')  // For futures/options

  // Get symbol info
  const symbolInfo = symbols.find((s) => s.symbol === symbol)

  // Calculate max quantity based on available funds
  const maxBuyQuantity = useCallback(() => {
    if (!account || !symbolInfo || !price) return 0
    const available = parseFloat(account.available)
    const priceNum = parseFloat(price)
    const lotSize = parseFloat(symbolInfo.lotSize)
    const maxLots = Math.floor(available / (priceNum * lotSize))
    return maxLots * lotSize
  }, [account, symbolInfo, price])

  // Validate price (limit orders only)
  const validatePrice = useCallback((value: string): boolean => {
    if (orderType === 'MARKET') return true
    if (!symbolInfo) return false

    const priceNum = parseFloat(value)
    const prevClose = parseFloat(symbolInfo.prevClose)
    const limitUp = prevClose * (1 + symbolInfo.limitUpRatio)
    const limitDown = prevClose * (1 - symbolInfo.limitDownRatio)

    return priceNum >= limitDown && priceNum <= limitUp
  }, [orderType, symbolInfo])

  // Handle price input
  const handlePriceChange = (value: string) => {
    // Allow empty, numbers, and decimal point
    if (value === '' || /^\d*\.?\d*$/.test(value)) {
      setPrice(value)
    }
  }

  // Handle quantity input
  const handleQuantityChange = (value: string) => {
    if (value === '' || /^\d+$/.test(value)) {
      setQuantity(value)
    }
  }

  // Quick set quantity percentages
  const setQuickQuantity = (percent: number) => {
    if (side === 'BUY') {
      const maxQty = maxBuyQuantity()
      setQuantity(Math.floor(maxQty * percent / 100).toString())
    }
  }

  // Submit order
  const handleSubmit = async () => {
    if (!symbol || !user) {
      message.error('请选择交易标的')
      return
    }

    if (orderType !== 'MARKET' && !validatePrice(price)) {
      message.error('价格超出涨跌停限制')
      return
    }

    const qty = parseInt(quantity)
    if (!qty || qty <= 0) {
      message.error('请输入有效数量')
      return
    }

    if (symbolInfo) {
      const lotSize = parseInt(symbolInfo.lotSize)
      if (qty % lotSize !== 0) {
        message.error(`数量必须是${lotSize}的整数倍`)
        return
      }
    }

    setSubmitting(true)

    try {
      const request: SubmitOrderRequest = {
        symbol,
        side,
        orderType,
        price: orderType === 'MARKET' ? '0' : price,
        quantity,
        idempotencyKey: generateIdempotencyKey(),
      }

      // Add limit price for market orders
      if (orderType === 'MARKET' && symbolInfo) {
        const prevClose = parseFloat(symbolInfo.prevClose)
        request.maxPrice = (prevClose * (1 + symbolInfo.limitUpRatio)).toFixed(0)
        request.minPrice = (prevClose * (1 - symbolInfo.limitDownRatio)).toFixed(0)
      }

      const response = await orderApi.submit(request)

      if (response.code === '0') {
        message.success(`下单成功: ${response.data.orderId}`)
        setPrice('')
        setQuantity('100')
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

  // Get price placeholder based on order type
  const pricePlaceholder = orderType === 'MARKET'
    ? '市价（自动匹配对手价）'
    : '请输入限价'

  return (
    <div style={{ padding: 16, background: '#0f172a', borderRadius: 8 }}>
      {/* Order type selector */}
      <div style={{ marginBottom: 16 }}>
        <Radio.Group
          value={orderType}
          onChange={(e) => setOrderType(e.target.value)}
          optionType="button"
          buttonStyle="solid"
          size="small"
        >
          <Radio.Button value="LIMIT">限价</Radio.Button>
          <Radio.Button value="MARKET">市价</Radio.Button>
          <Radio.Button value="FAK">FAK</Radio.Button>
          <Radio.Button value="FOK">FOK</Radio.Button>
        </Radio.Group>
      </div>

      {/* Buy/Sell selector */}
      <div style={{ marginBottom: 16 }}>
        <Radio.Group
          value={side}
          onChange={(e) => setSide(e.target.value)}
          style={{ width: '100%' }}
        >
          <Radio.Button
            value="BUY"
            style={{
              width: '50%',
              textAlign: 'center',
              background: side === 'BUY' ? 'rgba(52, 211, 153, 0.2)' : undefined,
              borderColor: side === 'BUY' ? '#34d399' : undefined,
            }}
          >
            <CaretUpOutlined style={{ color: side === 'BUY' ? '#34d399' : undefined }} />
            买入
          </Radio.Button>
          <Radio.Button
            value="SELL"
            style={{
              width: '50%',
              textAlign: 'center',
              background: side === 'SELL' ? 'rgba(251, 113, 133, 0.2)' : undefined,
              borderColor: side === 'SELL' ? '#fb7185' : undefined,
            }}
          >
            <CaretDownOutlined style={{ color: side === 'SELL' ? '#fb7185' : undefined }} />
            卖出
          </Radio.Button>
        </Radio.Group>
      </div>

      {/* Price input (not for market orders) */}
      {orderType !== 'MARKET' && (
        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', marginBottom: 8, color: '#94a3b8', fontSize: 12 }}>
            价格 {symbolInfo && (
              <Tooltip title={`涨跌停范围`}>
                <InfoCircleOutlined style={{ marginLeft: 4 }} />
              </Tooltip>
            )}
          </label>
          <Input
            value={price}
            onChange={(e) => handlePriceChange(e.target.value)}
            placeholder={pricePlaceholder}
            suffix="元"
            style={{ background: '#1e293b', borderColor: '#334155', color: 'white' }}
          />
        </div>
      )}

      {/* Quantity input */}
      <div style={{ marginBottom: 16 }}>
        <label style={{ display: 'block', marginBottom: 8, color: '#94a3b8', fontSize: 12 }}>
          数量 {symbolInfo && <span style={{ color: '#64748b' }}>(每手 {symbolInfo.lotSize} 股)</span>}
        </label>
        <Input
          value={quantity}
          onChange={(e) => handleQuantityChange(e.target.value)}
          placeholder="请输入数量"
          suffix="股"
          style={{ background: '#1e293b', borderColor: '#334155', color: 'white' }}
        />
        <div style={{ marginTop: 8, display: 'flex', gap: 8 }}>
          <Button size="small" onClick={() => setQuickQuantity(50)}>1/2</Button>
          <Button size="small" onClick={() => setQuickQuantity(33)}>1/3</Button>
          <Button size="small" onClick={() => setQuickQuantity(100)}>全部</Button>
        </div>
      </div>

      {/* Available funds */}
      {account && (
        <div style={{ marginBottom: 16, padding: 8, background: '#1e293b', borderRadius: 4 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
            <span style={{ color: '#94a3b8' }}>可用资金</span>
            <span style={{ color: '#22d3ee' }}>{parseFloat(account.available).toFixed(2)} 元</span>
          </div>
        </div>
      )}

      {/* Futures options */}
      {instrumentType === 'FUTURE' && (
        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', marginBottom: 8, color: '#a78bfa', fontSize: 12 }}>
            期货选项
          </label>
          <Radio.Group
            value={positionEffect}
            onChange={(e) => setPositionEffect(e.target.value)}
            size="small"
          >
            <Radio.Button value="OPEN">开仓</Radio.Button>
            <Radio.Button value="CLOSE">平仓</Radio.Button>
          </Radio.Group>
        </div>
      )}

      {/* Options options */}
      {instrumentType === 'OPTION' && (
        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', marginBottom: 8, color: '#fbbf24', fontSize: 12 }}>
            期权选项
          </label>
          <Space>
            <Radio.Group
              value={optionType}
              onChange={(e) => setOptionType(e.target.value)}
              size="small"
            >
              <Radio.Button value="CALL">Call</Radio.Button>
              <Radio.Button value="PUT">Put</Radio.Button>
            </Radio.Group>
            <Input
              value={contract}
              onChange={(e) => setContract(e.target.value)}
              placeholder="合约代码"
              size="small"
              style={{ background: '#1e293b', borderColor: '#334155', color: 'white', width: 100 }}
            />
          </Space>
        </div>
      )}

      {/* Submit button */}
      <Button
        type="primary"
        block
        size="large"
        onClick={handleSubmit}
        loading={submitting}
        style={{
          background: side === 'BUY' ? '#34d399' : '#fb7185',
          borderColor: side === 'BUY' ? '#34d399' : '#fb7185',
          fontWeight: 600,
        }}
      >
        {side === 'BUY' ? '买入' : '卖出'}
      </Button>
    </div>
  )
}

export default OrderPanel