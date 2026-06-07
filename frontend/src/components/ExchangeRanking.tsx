import { Card, Row, Col, Typography } from 'antd'

const { Text } = Typography

interface RankItem {
  symbol: string
  name: string
  price: string
  changeRatio: string
}

interface ExchangeData {
  name: string
  color: string
  data: RankItem[]
}

const exchanges: Record<string, ExchangeData> = {
  SSE: {
    name: '上交所A股',
    color: '#34d399',
    data: [
      { symbol: '600519', name: '贵州茅台', price: '1,856.00', changeRatio: '+10.00%' },
      { symbol: '601318', name: '中国平安', price: '45.67', changeRatio: '+8.45%' },
      { symbol: '600036', name: '招商银行', price: '32.45', changeRatio: '+7.23%' },
      { symbol: '600900', name: '长江电力', price: '25.34', changeRatio: '-7.23%' },
      { symbol: '601012', name: '隆基绿能', price: '18.67', changeRatio: '-6.45%' },
    ],
  },
  SZSE: {
    name: '深交所A股',
    color: '#22d3ee',
    data: [
      { symbol: '000858', name: '五粮液', price: '156.78', changeRatio: '+9.98%' },
      { symbol: '000333', name: '美的集团', price: '67.89', changeRatio: '+6.78%' },
      { symbol: '000651', name: '格力电器', price: '35.78', changeRatio: '-5.89%' },
      { symbol: '002415', name: '海康威视', price: '28.56', changeRatio: '-9.87%' },
      { symbol: '000001', name: '平安银行', price: '11.23', changeRatio: '+2.34%' },
    ],
  },
  CFFEX: {
    name: '中金所期货',
    color: '#a78bfa',
    data: [
      { symbol: 'IF2406', name: '沪深300', price: '3,850.0', changeRatio: '+0.45%' },
      { symbol: 'IC2406', name: '中证500', price: '5,234.0', changeRatio: '+0.32%' },
      { symbol: 'IH2406', name: '上证50', price: '2,456.0', changeRatio: '-0.12%' },
      { symbol: 'IM2406', name: '中证1000', price: '5,678.0', changeRatio: '+0.67%' },
      { symbol: 'TS2406', name: '2年国债', price: '101.25', changeRatio: '+0.02%' },
    ],
  },
  SSE_OPTIONS: {
    name: '上交所期权',
    color: '#fbbf24',
    data: [
      { symbol: '10003720', name: '50ETF购6月2500', price: '0.0856', changeRatio: '+12.34%' },
      { symbol: '10003721', name: '50ETF沽6月2500', price: '0.0234', changeRatio: '-8.56%' },
      { symbol: '10003722', name: '50ETF购6月2600', price: '0.0423', changeRatio: '+5.67%' },
      { symbol: '10003723', name: '50ETF沽6月2600', price: '0.0567', changeRatio: '-3.21%' },
      { symbol: '10003724', name: '50ETF购6月2700', price: '0.0189', changeRatio: '+2.34%' },
    ],
  },
}

interface ExchangeRankingProps {
  onSymbolClick?: (symbol: string) => void
}

export default function ExchangeRanking({ onSymbolClick }: ExchangeRankingProps) {
  return (
    <Card
      style={{
        background: 'rgba(8, 51, 68, 0.4)',
        borderColor: '#22d3ee',
      }}
    >
      <div style={{ marginBottom: 16 }}>
        <Text style={{ color: '#fff', fontSize: 14, fontWeight: 600 }}>
          各交易所品种涨幅排序
        </Text>
        <Text style={{ color: '#22d3ee', fontSize: 10, marginLeft: 8 }}>
          点击跳转详情
        </Text>
      </div>

      <Row gutter={[16, 16]}>
        {Object.entries(exchanges).map(([key, exchange]) => (
          <Col xs={24} sm={12} md={6} key={key}>
            <Card
              size="small"
              style={{
                background: 'rgba(15, 23, 42, 0.8)',
                borderColor: exchange.color,
              }}
              bodyStyle={{ padding: 12 }}
            >
              <Text style={{ color: exchange.color, fontSize: 12, fontWeight: 600, marginBottom: 8, display: 'block' }}>
                {exchange.name}
              </Text>

              {/* Header */}
              <Row style={{ fontSize: 10, color: '#64748b', marginBottom: 4 }}>
                <Col span={6}>代码</Col>
                <Col span={6}>名称</Col>
                <Col span={6}>价格</Col>
                <Col span={6}>涨幅</Col>
              </Row>

              {/* Data rows */}
              {exchange.data.map((item, idx) => (
                <Row
                  key={item.symbol}
                  style={{
                    padding: '4px 0',
                    cursor: 'pointer',
                    background: idx === 0 ? `rgba(${exchange.color === '#34d399' ? '52, 211, 153' : exchange.color === '#22d3ee' ? '34, 211, 238' : '167, 139, 250'}, 0.1)` : 'transparent',
                    borderRadius: 2,
                  }}
                  onClick={() => onSymbolClick?.(item.symbol)}
                >
                  <Col span={6}>
                    <Text style={{ color: '#fff', fontSize: 11 }}>{item.symbol}</Text>
                  </Col>
                  <Col span={6}>
                    <Text style={{ color: idx === 0 ? '#fff' : '#94a3b8', fontSize: 11 }}>{item.name}</Text>
                  </Col>
                  <Col span={6}>
                    <Text style={{ color: '#fff', fontSize: 11 }}>{item.price}</Text>
                  </Col>
                  <Col span={6}>
                    <Text style={{
                      color: parseFloat(item.changeRatio) >= 0 ? '#34d399' : '#fb7185',
                      fontSize: 11,
                    }}>
                      {item.changeRatio}
                    </Text>
                  </Col>
                </Row>
              ))}

              <Text style={{ color: '#64748b', fontSize: 10, display: 'block', textAlign: 'center', marginTop: 8 }}>
                ... 更多 ...
              </Text>
            </Card>
          </Col>
        ))}
      </Row>
    </Card>
  )
}