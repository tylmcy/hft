import { useEffect } from 'react'
import { Row, Col, Typography } from 'antd'
import { IndexBar, TopGainers, TopLosers, TopVolume, ExchangeRanking } from '../components'
import { useMarketStore, useAuthStore } from '../stores'
import { useNavigate } from 'react-router-dom'

const { Title } = Typography

export default function Overview() {
  const navigate = useNavigate()
  const { isLoggedIn } = useAuthStore()
  const { setSymbols } = useMarketStore()

  // Redirect to login if not authenticated
  useEffect(() => {
    if (!isLoggedIn) {
      navigate('/login')
    }
  }, [isLoggedIn, navigate])

  // Load symbols on mount
  useEffect(() => {
    // Mock data for now
    setSymbols([
      { symbol: '600000', name: '浦发银行', type: 'STOCK', prevClose: '10.00', limitUpRatio: 0.1, limitDownRatio: 0.1, lotSize: '100', minPriceIncrement: '0.01' },
      { symbol: '600519', name: '贵州茅台', type: 'STOCK', prevClose: '1687.00', limitUpRatio: 0.1, limitDownRatio: 0.1, lotSize: '100', minPriceIncrement: '0.01' },
    ])
  }, [setSymbols])

  const handleSymbolSelect = (symbol: string) => {
    navigate(`/trading?symbol=${symbol}`)
  }

  return (
    <div style={{ padding: 24 }}>
      {/* Index Bar */}
      <div style={{ marginBottom: 24 }}>
        <Title level={5} style={{ color: '#fff', marginBottom: 12 }}>
          指数行情
        </Title>
        <IndexBar onSelectSymbol={handleSymbolSelect} />
      </div>

      {/* Top Rankings */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col xs={24} sm={24} md={8}>
          <TopGainers onSymbolClick={handleSymbolSelect} />
        </Col>
        <Col xs={24} sm={24} md={8}>
          <TopLosers onSymbolClick={handleSymbolSelect} />
        </Col>
        <Col xs={24} sm={24} md={8}>
          <TopVolume onSymbolClick={handleSymbolSelect} />
        </Col>
      </Row>

      {/* Exchange Rankings */}
      <ExchangeRanking onSymbolClick={handleSymbolSelect} />
    </div>
  )
}