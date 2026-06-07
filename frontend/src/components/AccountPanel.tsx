import { Card, Typography, Descriptions, Button, Table } from 'antd'
import { useAccountStore } from '../stores'
import type { ColumnsType } from 'antd/es/table'

const { Text, Title } = Typography

interface Position {
  symbol: string
  name: string
  quantity: number
  available: number
  frozen: number
  avgPrice: number
  currentPrice: number
  profit: number
  profitRatio: number
}

export default function AccountPanel() {
  const { account } = useAccountStore()

  const positions: Position[] = [
    { symbol: '600000', name: '浦发银行', quantity: 1000, available: 1000, frozen: 0, avgPrice: 10.00, currentPrice: 10.50, profit: 500, profitRatio: 5 },
    { symbol: 'IF2406', name: '沪深300期货', quantity: 2, available: 2, frozen: 0, avgPrice: 3850, currentPrice: 3870, profit: 6000, profitRatio: 0.78 },
  ]

  const positionColumns: ColumnsType<Position> = [
    {
      title: '代码',
      dataIndex: 'symbol',
      render: (s: string) => <span style={{ color: '#22d3ee' }}>{s}</span>,
    },
    {
      title: '名称',
      dataIndex: 'name',
      render: (n: string) => <span style={{ color: '#fff' }}>{n}</span>,
    },
    {
      title: '持仓',
      dataIndex: 'quantity',
      render: (q: number, record) => (
        <div>
          <span style={{ color: '#fff' }}>{q}</span>
          {record.frozen > 0 && <span style={{ color: '#fbbf24', fontSize: 10 }}> ({record.frozen}冻结)</span>}
        </div>
      ),
    },
    {
      title: '成本',
      dataIndex: 'avgPrice',
      render: (p: number) => <span style={{ color: '#94a3b8' }}>{p.toFixed(2)}</span>,
    },
    {
      title: '盈亏',
      dataIndex: 'profit',
      render: (p: number, record) => (
        <div>
          <span style={{ color: p >= 0 ? '#34d399' : '#fb7185' }}>
            {p >= 0 ? '+' : ''}{p}
          </span>
          <span style={{ color: p >= 0 ? '#34d399' : '#fb7185', fontSize: 10 }}>
            ({record.profitRatio >= 0 ? '+' : ''}{record.profitRatio}%)
          </span>
        </div>
      ),
    },
  ]

  return (
    <Card
      size="small"
      style={{
        background: 'rgba(15, 23, 42, 0.8)',
        borderColor: '#34d399',
      }}
      bodyStyle={{ padding: 12 }}
    >
      {/* Title */}
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <Title level={5} style={{ color: '#fff', margin: 0 }}>账户详情</Title>
        <Text style={{ color: '#22d3ee', fontSize: 10 }}>AccountPanel.tsx</Text>
      </div>

      {/* Fund Details */}
      <div style={{ marginBottom: 16 }}>
        <Text style={{ color: '#fbbf24', fontSize: 12, marginBottom: 8, display: 'block' }}>资金明细</Text>
        <Descriptions column={1} size="small">
          <Descriptions.Item label="总资产" labelStyle={{ color: '#94a3b8', fontSize: 11 }}>
            <span style={{ color: '#fff', fontWeight: 600 }}>
              {account ? parseFloat(account.balance).toLocaleString() : '1,234,567'}
            </span>
          </Descriptions.Item>
          <Descriptions.Item label="可用资金" labelStyle={{ color: '#94a3b8', fontSize: 11 }}>
            <span style={{ color: '#22d3ee' }}>
              {account ? parseFloat(account.available).toLocaleString() : '567,890'}
            </span>
          </Descriptions.Item>
          <Descriptions.Item label="冻结资金" labelStyle={{ color: '#94a3b8', fontSize: 11 }}>
            <span style={{ color: '#fbbf24' }}>
              {account ? parseFloat(account.frozen).toLocaleString() : '0'}
            </span>
          </Descriptions.Item>
        </Descriptions>
      </div>

      {/* Position Details */}
      <div>
        <Text style={{ color: '#a78bfa', fontSize: 12, marginBottom: 8, display: 'block' }}>持仓明细</Text>
        <Table
          columns={positionColumns}
          dataSource={positions}
          size="small"
          pagination={false}
          rowKey="symbol"
        />
      </div>

      {/* Quick Actions */}
      <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
        <Button size="small" block>
          查看流水
        </Button>
        <Button size="small" block>
          查看订单
        </Button>
      </div>
    </Card>
  )
}