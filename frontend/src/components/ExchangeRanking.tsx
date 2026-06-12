import { useEffect, useState, useCallback } from 'react'
import { Card, Row, Col, Typography } from 'antd'
import { marketApi } from '../api/client'
import type { SinaQuote } from '../api/types'

const { Text } = Typography

interface ExchangeRankingProps {
  onSymbolClick?: (symbol: string) => void
}

export default function ExchangeRanking({ onSymbolClick: _onSymbolClick }: ExchangeRankingProps) {
  const [gainers, setGainers] = useState<SinaQuote[]>([])
  const [losers, setLosers] = useState<SinaQuote[]>([])
  const [loading, setLoading] = useState(true)

  const fetchData = useCallback(async () => {
    try {
      const res = await marketApi.getRankings()
      if (res.code === '0' && res.data) {
        setGainers(res.data.gainers || [])
        setLosers(res.data.losers || [])
      }
    } catch (e) {
      console.warn('Failed to fetch exchange rankings:', e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchData()
    const timer = setInterval(fetchData, 15000)
    return () => clearInterval(timer)
  }, [fetchData])

  return (
    <Card
      loading={loading}
      style={{
        background: 'rgba(8, 51, 68, 0.4)',
        borderColor: '#22d3ee',
      }}
    >
      <div style={{ marginBottom: 16 }}>
        <Text style={{ color: '#fff', fontSize: 14, fontWeight: 600 }}>
          全市场涨幅/跌幅排行
        </Text>
        <Text style={{ color: '#22d3ee', fontSize: 10, marginLeft: 8 }}>
          数据来自新浪财经，每15秒更新
        </Text>
      </div>

      <Row gutter={[16, 16]}>
        {/* 涨幅榜 */}
        <Col xs={24} sm={12} md={12}>
          <Card
            size="small"
            title={<span style={{ color: '#34d399', fontSize: 12 }}>涨幅榜 Top 10</span>}
            style={{
              background: 'rgba(15, 23, 42, 0.8)',
              borderColor: '#34d399',
            }}
            styles={{
              header: { borderBottom: '1px solid #34d399' },
            }}
          >
            <Row style={{ fontSize: 10, color: '#64748b', marginBottom: 4 }}>
              <Col span={6}>名称</Col>
              <Col span={6}>最新价</Col>
              <Col span={6}>涨跌额</Col>
              <Col span={6}>涨跌幅</Col>
            </Row>
            {gainers.map((item, idx) => (
              <Row
                key={idx}
                style={{
                  padding: '4px 0',
                  background: idx % 2 === 0 ? 'rgba(52, 211, 153, 0.05)' : 'transparent',
                  borderRadius: 2,
                }}
              >
                <Col span={6}>
                  <Text style={{ color: '#fff', fontSize: 11 }}>{item.name}</Text>
                </Col>
                <Col span={6}>
                  <Text style={{ color: '#fff', fontSize: 11 }}>{item.price}</Text>
                </Col>
                <Col span={6}>
                  <Text style={{ color: '#34d399', fontSize: 11 }}>{item.change}</Text>
                </Col>
                <Col span={6}>
                  <Text style={{ color: '#34d399', fontSize: 11, fontWeight: 600 }}>
                    {item.changePercent}
                  </Text>
                </Col>
              </Row>
            ))}
            {gainers.length === 0 && (
              <Text style={{ color: '#64748b', fontSize: 11, display: 'block', textAlign: 'center', padding: 16 }}>
                暂无数据
              </Text>
            )}
          </Card>
        </Col>

        {/* 跌幅榜 */}
        <Col xs={24} sm={12} md={12}>
          <Card
            size="small"
            title={<span style={{ color: '#fb7185', fontSize: 12 }}>跌幅榜 Top 10</span>}
            style={{
              background: 'rgba(15, 23, 42, 0.8)',
              borderColor: '#fb7185',
            }}
            styles={{
              header: { borderBottom: '1px solid #fb7185' },
            }}
          >
            <Row style={{ fontSize: 10, color: '#64748b', marginBottom: 4 }}>
              <Col span={6}>名称</Col>
              <Col span={6}>最新价</Col>
              <Col span={6}>涨跌额</Col>
              <Col span={6}>涨跌幅</Col>
            </Row>
            {losers.map((item, idx) => (
              <Row
                key={idx}
                style={{
                  padding: '4px 0',
                  background: idx % 2 === 0 ? 'rgba(251, 113, 133, 0.05)' : 'transparent',
                  borderRadius: 2,
                }}
              >
                <Col span={6}>
                  <Text style={{ color: '#fff', fontSize: 11 }}>{item.name}</Text>
                </Col>
                <Col span={6}>
                  <Text style={{ color: '#fff', fontSize: 11 }}>{item.price}</Text>
                </Col>
                <Col span={6}>
                  <Text style={{ color: '#fb7185', fontSize: 11 }}>{item.change}</Text>
                </Col>
                <Col span={6}>
                  <Text style={{ color: '#fb7185', fontSize: 11, fontWeight: 600 }}>
                    {item.changePercent}
                  </Text>
                </Col>
              </Row>
            ))}
            {losers.length === 0 && (
              <Text style={{ color: '#64748b', fontSize: 11, display: 'block', textAlign: 'center', padding: 16 }}>
                暂无数据
              </Text>
            )}
          </Card>
        </Col>
      </Row>
    </Card>
  )
}
