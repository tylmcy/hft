import { Card, Table } from 'antd'

function Positions() {
  const columns = [
    { title: '股票代码', dataIndex: 'symbol', key: 'symbol' },
    { title: '股票名称', dataIndex: 'symbolName', key: 'symbolName' },
    { title: '持仓数量', dataIndex: 'quantity', key: 'quantity' },
    { title: '可用数量', dataIndex: 'available', key: 'available' },
    { title: '成本价', dataIndex: 'costPrice', key: 'costPrice' },
    { title: '当前价', dataIndex: 'currentPrice', key: 'currentPrice' },
    { title: '盈亏', dataIndex: 'profit', key: 'profit' },
  ]

  return (
    <div>
      <h2 style={{ marginBottom: '24px' }}>持仓查询</h2>
      <Card>
        <Table columns={columns} dataSource={[]} rowKey="symbol" />
      </Card>
    </div>
  )
}

export default Positions