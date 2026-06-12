import { useEffect, useState } from 'react'
import { Card, Row, Col, Progress, Typography, Space, Tag } from 'antd'
import {
  DatabaseOutlined,
  ThunderboltOutlined,
  CloudServerOutlined,
} from '@ant-design/icons'
import { adminApi } from '../api/client'
import type { SystemStatus } from '../api/types'

const { Text } = Typography

export function SystemStatusPanel() {
  const [status, setStatus] = useState<SystemStatus | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    loadStatus()
    const interval = setInterval(loadStatus, 5000) // Refresh every 5 seconds
    return () => clearInterval(interval)
  }, [])

  const loadStatus = async () => {
    try {
      const result = await adminApi.getSystemStatus()
      if (result.code === '0') {
        setStatus(result.data)
      }
    } catch (error) {
      console.error('Failed to load system status:', error)
    } finally {
      setLoading(false)
    }
  }

  if (!status) {
    return <Card loading={loading} />
  }

  return (
    <div>
      {/* JVM Status */}
      <Card
        title={
          <Space>
            <DatabaseOutlined style={{ color: '#22d3ee' }} />
            <span style={{ color: '#fff' }}>JVM状态</span>
          </Space>
        }
        style={{
          background: 'rgba(15, 23, 42, 0.8)',
          borderColor: '#334155',
          marginBottom: 16,
        }}
        size="small"
      >
        <Row gutter={16}>
          <Col span={12}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>堆内存使用:</Text>
            <Progress
              percent={status.heapUsagePercent}
              strokeColor={status.heapUsagePercent > 80 ? '#fb7185' : '#34d399'}
              trailColor="#1e293b"
              size="small"
            />
            <Text style={{ color: '#fff', fontSize: 11 }}>
              {(status.heapUsed / 1024 / 1024).toFixed(0)} MB / {(status.heapMax / 1024 / 1024).toFixed(0)} MB
            </Text>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 8 }}>
              <Text style={{ color: '#94a3b8', fontSize: 12 }}>线程数: </Text>
              <Text style={{ color: '#fff' }}>{status.threadCount}</Text>
            </div>
          </Col>
        </Row>
      </Card>

      {/* System Status */}
      <Card
        title={
          <Space>
            <ThunderboltOutlined style={{ color: '#fbbf24' }} />
            <span style={{ color: '#fff' }}>系统状态</span>
          </Space>
        }
        style={{
          background: 'rgba(15, 23, 42, 0.8)',
          borderColor: '#334155',
          marginBottom: 16,
        }}
        size="small"
      >
        <Row gutter={16}>
          <Col span={8}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>状态</Text>
            <br />
            <Tag color={status.status === 'RUNNING' ? '#34d399' : '#fbbf24'}>
              {status.status === 'RUNNING' ? '运行中' : '维护中'}
            </Tag>
          </Col>
          <Col span={8}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>交易阶段</Text>
            <br />
            <Text style={{ color: '#34d399' }}>
              {status.phase === 'CONTINUOUS_TRADING' ? '连续竞价' : status.phase}
            </Text>
          </Col>
          <Col span={8}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>在线用户</Text>
            <br />
            <Text style={{ color: '#fff' }}>{status.activeUsers}</Text>
          </Col>
        </Row>
        <Row gutter={16} style={{ marginTop: 12 }}>
          <Col span={8}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>今日订单</Text>
            <br />
            <Text style={{ color: '#fff' }}>{status.todayOrders.toLocaleString()}</Text>
          </Col>
          <Col span={8}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>今日成交</Text>
            <br />
            <Text style={{ color: '#fff' }}>{status.todayTrades.toLocaleString()}</Text>
          </Col>
          <Col span={8}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>成交额</Text>
            <br />
            <Text style={{ color: '#a78bfa' }}>{status.todayTurnover}亿</Text>
          </Col>
        </Row>
        <Row gutter={16} style={{ marginTop: 12 }}>
          <Col span={12}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>系统TPS</Text>
            <br />
            <Text style={{ color: '#fbbf24', fontSize: 16, fontWeight: 700 }}>
              {status.tps.toLocaleString()}
            </Text>
          </Col>
          <Col span={12}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>P99延迟</Text>
            <br />
            <Text style={{ color: '#34d399' }}>{status.p99Latency} ms</Text>
          </Col>
        </Row>
      </Card>

      {/* Chronicle Status */}
      <Card
        title={
          <Space>
            <CloudServerOutlined style={{ color: '#a78bfa' }} />
            <span style={{ color: '#fff' }}>Chronicle状态</span>
          </Space>
        }
        style={{
          background: 'rgba(15, 23, 42, 0.8)',
          borderColor: '#334155',
          marginBottom: 16,
        }}
        size="small"
      >
        <Row gutter={16}>
          <Col span={12}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>Queue写入速率:</Text>
            <br />
            <Text style={{ color: '#34d399' }}>{status.queueWriteRate.toLocaleString()} msg/s</Text>
          </Col>
          <Col span={12}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>Map大小:</Text>
            <br />
            <Text style={{ color: '#fff' }}>{status.mapSize.toLocaleString()}</Text>
          </Col>
        </Row>
        <div style={{ marginTop: 12 }}>
          <Text style={{ color: '#94a3b8', fontSize: 12 }}>磁盘使用:</Text>
          <Progress
            percent={status.diskUsage}
            strokeColor={status.diskUsage > 80 ? '#fb7185' : '#22d3ee'}
            trailColor="#1e293b"
            size="small"
          />
        </div>
      </Card>
    </div>
  )
}

export default SystemStatusPanel
