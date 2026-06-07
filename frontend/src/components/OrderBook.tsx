import { Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'

interface OrderBookProps {
  symbol?: string
  onPriceClick?: (price: string) => void
}

interface OrderBookRow {
  key: string
  level: string
  price: string
  volume: string
  percent: number
  type: 'bid' | 'ask'
}

export function OrderBook({ onPriceClick }: OrderBookProps) {
  // Mock data for demonstration
  const mockBids: OrderBookRow[] = [
    { key: 'bid-1', level: '买一', price: '10.00', volume: '1890', percent: 80, type: 'bid' },
    { key: 'bid-2', level: '买二', price: '9.99', volume: '1456', percent: 60, type: 'bid' },
    { key: 'bid-3', level: '买三', price: '9.98', volume: '1123', percent: 50, type: 'bid' },
    { key: 'bid-4', level: '买四', price: '9.97', volume: '890', percent: 40, type: 'bid' },
    { key: 'bid-5', level: '买五', price: '9.96', volume: '1678', percent: 70, type: 'bid' },
  ]

  const mockAsks: OrderBookRow[] = [
    { key: 'ask-1', level: '卖一', price: '10.01', volume: '2345', percent: 90, type: 'ask' },
    { key: 'ask-2', level: '卖二', price: '10.02', volume: '756', percent: 35, type: 'ask' },
    { key: 'ask-3', level: '卖三', price: '10.03', volume: '1567', percent: 65, type: 'ask' },
    { key: 'ask-4', level: '卖四', price: '10.04', volume: '987', percent: 45, type: 'ask' },
    { key: 'ask-5', level: '卖五', price: '10.05', volume: '1234', percent: 55, type: 'ask' },
  ]

  const columns: ColumnsType<OrderBookRow> = [
    {
      title: '档位',
      dataIndex: 'level',
      width: 50,
      render: (text: string, record: OrderBookRow) => (
        <span style={{ color: record.type === 'bid' ? '#34d399' : '#fb7185' }}>
          {text}
        </span>
      ),
    },
    {
      title: '价格',
      dataIndex: 'price',
      width: 80,
      render: (text: string, record: OrderBookRow) => (
        <span
          style={{
            color: record.type === 'bid' ? '#34d399' : '#fb7185',
            cursor: 'pointer',
          }}
          onClick={() => onPriceClick?.(text)}
        >
          {text}
        </span>
      ),
    },
    {
      title: '数量',
      dataIndex: 'volume',
      width: 80,
      render: (text: string, record: OrderBookRow) => (
        <div style={{ position: 'relative' }}>
          <div
            style={{
              position: 'absolute',
              right: 0,
              top: 0,
              bottom: 0,
              width: `${record.percent}%`,
              background: record.type === 'bid'
                ? 'rgba(52, 211, 153, 0.2)'
                : 'rgba(251, 113, 133, 0.2)',
              borderRadius: 2,
            }}
          />
          <span style={{ position: 'relative', color: '#94a3b8' }}>
            {parseInt(text).toLocaleString()}
          </span>
        </div>
      ),
    },
  ]

  return (
    <div style={{ background: '#0f172a', borderRadius: 8, overflow: 'hidden' }}>
      {/* Header */}
      <div style={{ padding: '8px 16px', borderBottom: '1px solid #1e293b' }}>
        <span style={{ color: '#fff', fontWeight: 600 }}>订单簿 (Level-2)</span>
        <span style={{ marginLeft: 8, color: '#22d3ee', fontSize: 12 }}>
          点击价格快捷下单
        </span>
      </div>

      {/* Ask orders (sells) */}
      <div style={{ borderBottom: '1px solid #1e293b' }}>
        <Table
          columns={columns}
          dataSource={mockAsks.reverse()}
          pagination={false}
          size="small"
          showHeader={false}
          style={{ background: 'transparent' }}
        />
      </div>

      {/* Current price */}
      <div
        style={{
          padding: '8px 16px',
          background: 'rgba(34, 211, 238, 0.1)',
          borderBottom: '1px solid #1e293b',
          borderTop: '1px solid #1e293b',
        }}
      >
        <span style={{ color: '#22d3ee', fontSize: 16, fontWeight: 700 }}>
          10.00
        </span>
      </div>

      {/* Bid orders (buys) */}
      <Table
        columns={columns}
        dataSource={mockBids}
        pagination={false}
        size="small"
        showHeader={false}
        style={{ background: 'transparent' }}
      />
    </div>
  )
}

export default OrderBook