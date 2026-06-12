import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Menu, Layout as AntLayout, Button, Dropdown, Space, Typography } from 'antd'
import {
  DashboardOutlined,
  SettingOutlined,
  DollarOutlined,
  FileTextOutlined,
  UserOutlined,
  LogoutOutlined,
  StockOutlined,
  CalendarOutlined,
} from '@ant-design/icons'
import { useAuthStore } from '../stores'

const { Header, Content } = AntLayout
const { Text } = Typography

function AdminLayout() {
  const location = useLocation()
  const navigate = useNavigate()
  const { user, logout } = useAuthStore()

  const handleLogout = () => {
    logout()
    window.location.href = '/login'
  }

  const menuItems = [
    { key: '/admin', icon: <DashboardOutlined />, label: '系统概览' },
    { key: '/admin/accounts', icon: <UserOutlined />, label: '账户管理' },
    { key: '/admin/deposits', icon: <DollarOutlined />, label: '入金管理' },
    { key: '/admin/orders', icon: <FileTextOutlined />, label: '订单监控' },
    { key: '/admin/trades', icon: <StockOutlined />, label: '成交查询' },
    { key: '/admin/cashflow', icon: <DollarOutlined />, label: '资金流水' },
    { key: '/admin/instruments', icon: <StockOutlined />, label: '品种管理' },
    { key: '/admin/calendar', icon: <CalendarOutlined />, label: '交易日历' },
    { key: '/', icon: <FileTextOutlined />, label: '交易端' },
  ]

  const handleMenuClick = ({ key }: { key: string }) => {
    navigate(key)
  }

  const userMenuItems = [
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' },
  ]

  const handleUserMenuClick = ({ key }: { key: string }) => {
    if (key === 'logout') {
      handleLogout()
    }
  }

  return (
    <AntLayout style={{ minHeight: '100vh', background: '#020617' }}>
      <Header
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          background: 'rgba(120, 53, 15, 0.3)',
          borderBottom: '1px solid #fbbf24',
          padding: '0 24px',
          height: 56,
          lineHeight: '56px',
        }}
      >
        {/* Left: Logo + Menu */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, minWidth: 0 }}>
          <SettingOutlined style={{ fontSize: 24, color: '#fbbf24' }} />
          <Text
            style={{
              fontSize: 18,
              fontWeight: 700,
              color: '#fbbf24',
              marginRight: 24,
              whiteSpace: 'nowrap',
            }}
          >
            HFT Admin
          </Text>
          <Text style={{ fontSize: 12, color: '#fbbf24', marginRight: 16 }}>
            管理后台
          </Text>
          <Menu
            mode="horizontal"
            selectedKeys={[location.pathname]}
            items={menuItems}
            onClick={handleMenuClick}
            style={{
              flex: 1,
              minWidth: 0,
              background: 'transparent',
              borderBottom: 'none',
            }}
            theme="dark"
          />
        </div>

        {/* Right: Admin Info + User Menu */}
        <Space style={{ flexShrink: 0 }}>
          <Text style={{ color: '#94a3b8', fontSize: 13 }}>管理员: {user?.username}</Text>
          <Dropdown menu={{ items: userMenuItems, onClick: handleUserMenuClick }}>
            <Button
              icon={<UserOutlined />}
              style={{
                background: 'rgba(251, 191, 36, 0.2)',
                borderColor: '#fbbf24',
                color: '#fbbf24',
              }}
            >
              {user?.username || '管理员'}
            </Button>
          </Dropdown>
        </Space>
      </Header>

      <Content
        style={{
          background: '#020617',
          minHeight: 'calc(100vh - 56px)',
        }}
      >
        <Outlet />
      </Content>
    </AntLayout>
  )
}

export default AdminLayout
