import { Card, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons'

interface RankingItem {
  key: string
  rank: number
  symbol: string
  name: string
  price: string
  changePercent: string
  volume?: string
}

interface TopRankingProps {
  type: 'gainers' | 'losers' | 'volume'
  limit?: number
  onSymbolClick?: (symbol: string) => void
}

// Mock data for demonstration
const MOCK_GAINERS: RankingItem[] = [
  { key: '1', rank: 1, symbol: '600519', name: '贵州茅台', price: '1856.00', changePercent: '+10.00%' },
  { key: '2', rank: 2, symbol: '000858', name: '五粮液', price: '156.78', changePercent: '+9.98%' },
  { key: '3', rank: 3, symbol: '601318', name: '中国平安', price: '45.67', changePercent: '+8.45%' },
  { key: '4', rank: 4, symbol: '600036', name: '招商银行', price: '32.45', changePercent: '+7.23%' },
  { key: '5', rank: 5, symbol: '000333', name: '美的集团', price: '67.89', changePercent: '+6.78%' },
]

const MOCK_LOSERS: RankingItem[] = [
  { key: '1', rank: 1, symbol: '002415', name: '海康威视', price: '28.56', changePercent: '-9.87%' },
  { key: '2', rank: 2, symbol: '300750', name: '宁德时代', price: '178.90', changePercent: '-8.56%' },
  { key: '3', rank: 3, symbol: '600900', name: '长江电力', price: '25.34', changePercent: '-7.23%' },
  { key: '4', rank: 4, symbol: '601012', name: '隆基绿能', price: '18.67', changePercent: '-6.45%' },
  { key: '5', rank: 5, symbol: '000651', name: '格力电器', price: '35.78', changePercent: '-5.89%' },
]

const MOCK_VOLUME: RankingItem[] = [
  { key: '1', rank: 1, symbol: '600519', name: '贵州茅台', price: '1856.00', changePercent: '+10.00%', volume: '125.6亿' },
  { key: '2', rank: 2, symbol: '000858', name: '五粮液', price: '156.78', changePercent: '+9.98%', volume: '98.3亿' },
  { key: '3', rank: 3, symbol: '601318', name: '中国平安', price: '45.67', changePercent: '+8.45%', volume: '87.5亿' },
  { key: '4', rank: 4, symbol: '600036', name: '招商银行', price: '32.45', changePercent: '+7.23%', volume: '76.2亿' },
  { key: '5', rank: 5, symbol: '000333', name: '美的集团', price: '67.89', changePercent: '+6.78%', volume: '65.8亿' },
]

const TYPE_CONFIG = {
  gainers: {
    title: '上涨Top5',
    borderColor: '#34d399',
    bgColor: 'rgba(6, 78, 59, 0.3)',
    data: MOCK_GAINERS,
  },
  losers: {
    title: '下跌Top5',
    borderColor: '#fb7185',
    bgColor: 'rgba(120, 53, 15, 0.3)',
    data: MOCK_LOSERS,
  },
  volume: {
    title: '成交量Top5',
    borderColor: '#a78bfa',
    bgColor: 'rgba(8, 51, 68, 0.4)',
    data: MOCK_VOLUME,
  },
}

export function TopRanking({ type, limit = 5, onSymbolClick }: TopRankingProps) {
  const config = TYPE_CONFIG[type]
  const data = config.data.slice(0, limit)

  const columns: ColumnsType<RankingItem> = [
    {
      title: '排名',
      dataIndex: 'rank',
      width: 40,
      render: (rank: number) => (
        <span style={{
          color: rank === 1 ? '#fbbf24' : '#94a3b8',
          fontWeight: rank === 1 ? 600 : 400,
        }}>
          {rank}
        </span>
      ),
    },
    {
      title: '代码',
      dataIndex: 'symbol',
      width: 70,
      render: (symbol: string) => (
        <span
          style={{ color: '#fff', cursor: 'pointer' }}
          onClick={() => onSymbolClick?.(symbol)}
        >
          {symbol}
        </span>
      ),
    },
    {
      title: '名称',
      dataIndex: 'name',
      width: 80,
      render: (name: string) => <span style={{ color: '#94a3b8' }}>{name}</span>,
    },
    {
      title: '最新价',
      dataIndex: 'price',
      width: 80,
      render: (price: string) => <span style={{ color: '#fff' }}>{price}</span>,
    },
    ...(type === 'volume'
      ? [{
          title: '成交额',
          dataIndex: 'volume',
          width: 70,
          render: (volume: string) => (
            <span style={{ color: '#a78bfa' }}>{volume}</span>
          ),
        }]
      : []),
    {
      title: '涨跌幅',
      dataIndex: 'changePercent',
      width: 80,
      render: (change: string) => {
        const isUp = change.startsWith('+')
        const color = isUp ? '#34d399' : '#fb7185'
        return (
          <span style={{ color, fontWeight: 600 }}>
            {isUp && <ArrowUpOutlined style={{ marginRight: 4 }} />}
            {!isUp && <ArrowDownOutlined style={{ marginRight: 4 }} />}
            {change}
          </span>
        )
      },
    },
  ]

  return (
    <Card
      title={
        <span style={{ color: '#fff', fontWeight: 600 }}>
          {config.title}
        </span>
      }
      size="small"
      style={{
        background: config.bgColor,
        borderColor: config.borderColor,
      }}
      styles={{
        header: {
          borderBottom: `1px solid ${config.borderColor}`,
        },
      }}
    >
      <Table
        columns={columns}
        dataSource={data}
        pagination={false}
        size="small"
        showHeader
      />
    </Card>
  )
}

export function TopGainers(props: Omit<TopRankingProps, 'type'>) {
  return <TopRanking type="gainers" {...props} />
}

export function TopLosers(props: Omit<TopRankingProps, 'type'>) {
  return <TopRanking type="losers" {...props} />
}

export function TopVolume(props: Omit<TopRankingProps, 'type'>) {
  return <TopRanking type="volume" {...props} />
}

export default TopRanking