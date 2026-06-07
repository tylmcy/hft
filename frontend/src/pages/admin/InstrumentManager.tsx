import { useEffect, useState } from 'react'
import { Card, Table, Tag, Button, Space, Typography, Select, message } from 'antd'
import { ExportOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'

const { Title, Text } = Typography

interface Instrument {
  symbol: string
  name: string
  type: 'STOCK' | 'FUTURE' | 'OPTION'
  exchange: string
  minPriceIncrement: string
  lotSize: string
  marginRatio: string
  limitUp: string
  limitDown: string
  status: 'TRADING' | 'SUSPENDED' | 'EXPIRED'
  lastUpdate: string
  prevClose: string
  todayOpen: string
  volume: string
}

export default function InstrumentManager() {
  const [instruments, setInstruments] = useState<Instrument[]>([])
  const [searchText, setSearchText] = useState('')
  const [typeFilter, setTypeFilter] = useState<string>('all')
  const [statusFilter, setStatusFilter] = useState<string>('all')

  useEffect(() => {
    setInstruments([
      { symbol: '600000', name: '浦发银行', type: 'STOCK', exchange: '上交所', minPriceIncrement: '0.01', lotSize: '1', marginRatio: '-', limitUp: '+10%', limitDown: '-10%', status: 'TRADING', lastUpdate: '2026-05-20', prevClose: '10.00', todayOpen: '10.05', volume: '1,234,567' },
      { symbol: '600519', name: '贵州茅台', type: 'STOCK', exchange: '上交所', minPriceIncrement: '0.01', lotSize: '1', marginRatio: '-', limitUp: '+10%', limitDown: '-10%', status: 'TRADING', lastUpdate: '2026-05-20', prevClose: '1687.00', todayOpen: '1687.00', volume: '456,789' },
      { symbol: 'IF2406', name: '沪深300股指期货', type: 'FUTURE', exchange: '中金所', minPriceIncrement: '0.2', lotSize: '300', marginRatio: '12%', limitUp: '+10%', limitDown: '-10%', status: 'TRADING', lastUpdate: '2026-05-20', prevClose: '3850.0', todayOpen: '3855.0', volume: '123,456' },
      { symbol: 'IC2406', name: '中证500股指期货', type: 'FUTURE', exchange: '中金所', minPriceIncrement: '0.2', lotSize: '200', marginRatio: '12%', limitUp: '+10%', limitDown: '-10%', status: 'TRADING', lastUpdate: '2026-05-20', prevClose: '5234.0', todayOpen: '5235.0', volume: '45,678' },
      { symbol: '10003720', name: '50ETF购6月2500', type: 'OPTION', exchange: '上交所', minPriceIncrement: '0.0001', lotSize: '10000', marginRatio: '-', limitUp: '-', limitDown: '-', status: 'TRADING', lastUpdate: '2026-05-20', prevClose: '0.0856', todayOpen: '0.0856', volume: '12,345' },
      { symbol: '600001', name: '邯郸钢铁', type: 'STOCK', exchange: '上交所', minPriceIncrement: '0.01', lotSize: '1', marginRatio: '-', limitUp: '+10%', limitDown: '-10%', status: 'SUSPENDED', lastUpdate: '2026-05-19', prevClose: '5.50', todayOpen: '-', volume: '-' },
    ])
  }, [])

  const handleSuspend = (symbol: string) => {
    message.success(`品种 ${symbol} 已停牌`)
    setInstruments(instruments.map(i => i.symbol === symbol ? { ...i, status: 'SUSPENDED' as Instrument['status'] } : i))
  }

  const handleResume = (symbol: string) => {
    message.success(`品种 ${symbol} 已复牌`)
    setInstruments(instruments.map(i => i.symbol === symbol ? { ...i, status: 'TRADING' as Instrument['status'] } : i))
  }

  const filteredData = instruments.filter(i => {
    if (searchText && !i.symbol.includes(searchText) && !i.name.includes(searchText)) return false
    if (typeFilter !== 'all' && i.type !== typeFilter) return false
    if (statusFilter !== 'all' && i.status !== statusFilter) return false
    return true
  })

  const columns: ColumnsType<Instrument> = [
    {
      title: '代码',
      dataIndex: 'symbol',
      width: 100,
      render: (s: string) => <span style={{ color: '#22d3ee', fontWeight: 600 }}>{s}</span>,
    },
    {
      title: '名称',
      dataIndex: 'name',
      width: 150,
      render: (n: string) => <span style={{ color: '#fff' }}>{n}</span>,
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 80,
      render: (t: string) => {
        const colors: Record<string, string> = { STOCK: '#22d3ee', FUTURE: '#a78bfa', OPTION: '#fbbf24' }
        const labels: Record<string, string> = { STOCK: '股票', FUTURE: '期货', OPTION: '期权' }
        return <Tag color={colors[t]}>{labels[t]}</Tag>
      },
      filters: [
        { text: '股票', value: 'STOCK' },
        { text: '期货', value: 'FUTURE' },
        { text: '期权', value: 'OPTION' },
      ],
      onFilter: (value, record) => record.type === value,
    },
    {
      title: '交易所',
      dataIndex: 'exchange',
      width: 80,
    },
    {
      title: '最小价位',
      dataIndex: 'minPriceIncrement',
      width: 80,
    },
    {
      title: '合约乘数',
      dataIndex: 'lotSize',
      width: 80,
    },
    {
      title: '保证金率',
      dataIndex: 'marginRatio',
      width: 80,
    },
    {
      title: '涨跌停',
      width: 100,
      render: (_, record) => <span style={{ color: '#94a3b8' }}>{record.limitUp} / {record.limitDown}</span>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (s: string) => {
        const colors: Record<string, string> = { TRADING: '#34d399', SUSPENDED: '#fb7185', EXPIRED: '#64748b' }
        const labels: Record<string, string> = { TRADING: '交易中', SUSPENDED: '停牌', EXPIRED: '到期' }
        return <Tag color={colors[s]}>{labels[s]}</Tag>
      },
    },
    {
      title: '昨收/今开',
      width: 120,
      render: (_, record) => (
        <div>
          <span style={{ color: '#94a3b8' }}>{record.prevClose}</span>
          <span style={{ color: '#64748b' }}> / </span>
          <span style={{ color: '#fff' }}>{record.todayOpen}</span>
        </div>
      ),
    },
    {
      title: '最后更新',
      dataIndex: 'lastUpdate',
      width: 100,
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_, record) => (
        <Space>
          <Button size="small">编辑</Button>
          {record.status === 'TRADING' && (
            <Button size="small" danger onClick={() => handleSuspend(record.symbol)}>
              停牌
            </Button>
          )}
          {record.status === 'SUSPENDED' && (
            <Button size="small" type="primary" onClick={() => handleResume(record.symbol)}>
              复牌
            </Button>
          )}
        </Space>
      ),
    },
  ]

  return (
    <div style={{ padding: 24 }}>
      <Title level={4} style={{ color: '#fbbf24', marginBottom: 16 }}>
        品种管理 (InstrumentManager.tsx)
      </Title>

      <Card style={{ background: 'rgba(8, 51, 68, 0.4)', borderColor: '#fbbf24' }}>
        {/* Toolbar */}
        <Space style={{ marginBottom: 16 }}>
          <Button type="primary">+ 新增品种</Button>
          <Button>导入CSV</Button>
          <Button icon={<ExportOutlined />} onClick={() => message.info('导出功能开发中')}>
            导出CSV
          </Button>
          <input
            placeholder="搜索代码/名称"
            style={{ padding: '4px 8px', borderRadius: 4, border: '1px solid #334155', background: '#1e293b', color: '#fff' }}
            value={searchText}
            onChange={e => setSearchText(e.target.value)}
          />
          <Select value={typeFilter} onChange={setTypeFilter} style={{ width: 100 }}>
            <Select.Option value="all">全部类型</Select.Option>
            <Select.Option value="STOCK">股票</Select.Option>
            <Select.Option value="FUTURE">期货</Select.Option>
            <Select.Option value="OPTION">期权</Select.Option>
          </Select>
          <Select value={statusFilter} onChange={setStatusFilter} style={{ width: 100 }}>
            <Select.Option value="all">全部状态</Select.Option>
            <Select.Option value="TRADING">交易中</Select.Option>
            <Select.Option value="SUSPENDED">停牌</Select.Option>
            <Select.Option value="EXPIRED">到期</Select.Option>
          </Select>
        </Space>

        <Table
          columns={columns}
          dataSource={filteredData}
          size="small"
          pagination={{ pageSize: 20 }}
          rowKey="symbol"
          expandable={{
            expandedRowRender: (record) => (
              <div style={{ padding: 8 }}>
                <Text style={{ color: '#64748b', fontSize: 11 }}>
                  昨收: {record.prevClose} | 今日开: {record.todayOpen} | 成交量: {record.volume}
                  {record.type === 'OPTION' && ' | 行权价: 2.50 | 到期日: 2026-06-28'}
                </Text>
              </div>
            ),
          }}
        />
      </Card>
    </div>
  )
}