import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Menu, Layout as AntLayout, Button, Dropdown, Space, Typography } from 'antd'
import {
  DashboardOutlined,
  StockOutlined,
  UnorderedListOutlined,
  FileTextOutlined,
  HistoryOutlined,
  UserOutlined,
  LogoutOutlined,
  StarOutlined,
  LineChartOutlined,
  FunctionOutlined,
} from '@ant-design/icons'
import { useAuthStore, useAccountStore } from '../stores'

const { Header, Content, Sider } = AntLayout
const { Text } = Typography

function Layout() {
  const location = useLocation()
  const navigate = useNavigate()
  const { user, logout } = useAuthStore()
  const { account } = useAccountStore()

  const handleLogout = () => {
    logout()
    window.location.href = '/login'
  }

  const menuItems = [
    { key: '/', icon: <DashboardOutlined />, label: '总览' },
    { key: '/trading', icon: <StockOutlined />, label: '股票' },
    { key: '/futures', icon: <LineChartOutlined />, label: '期货' },
    { key: '/options', icon: <FunctionOutlined />, label: '期权' },
    { key: '/watchlist', icon: <StarOutlined />, label: '自选' },
    { key: '/orders', icon: <FileTextOutlined />, label: '订单' },
    { key: '/positions', icon: <UnorderedListOutlined />, label: '持仓' },
    { key: '/history', icon: <HistoryOutlined />, label: '历史' },
    { key: '/profile', icon: <UserOutlined />, label: '个人中心' },
  ]

  const handleMenuClick = ({ key }: { key: string }) => {
    navigate(key)
  }

  const userMenuItems = [
    { key: 'profile', icon: <UserOutlined />, label: '个人中心' },
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' },
  ]

  const handleUserMenuClick = ({ key }: { key: string }) => {
    if (key === 'logout') {
      handleLogout()
    } else if (key === 'profile') {
      navigate('/profile')
    }
  }

  return (
    <AntLayout style={{ minHeight: '100vh', background: '#020617' }}>
      <Header
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          background: 'rgba(8, 51, 68, 0.4)',
          borderBottom: '1px solid #22d3ee',
          padding: '0 24px',
        }}
      >
        {/* Logo */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <StockOutlined style={{ fontSize: 24, color: '#22d3ee' }} />
          <Text style={{ fontSize: 18, fontWeight: 700, color: '#22d3ee' }}>
            HFT Trading
          </Text>
        </div>

        {/* Account Info */}
        {account && (
          <Space size={24} style={{ margin: '0 auto' }}>
            <div style={{ textAlign: 'center' }}>
              <Text style={{ color: '#94a3b8', fontSize: 12 }}>总资产</Text>
              <br />
              <Text style={{ color: '#fff', fontWeight: 600 }}>
                {parseFloat(account.balance).toLocaleString()}
              </Text>
            </div>
            <div style={{ textAlign: 'center' }}>
              <Text style={{ color: '#22d3ee', fontSize: 12 }}>可用</Text>
              <br />
              <Text style={{ color: '#22d3ee', fontWeight: 600 }}>
                {parseFloat(account.available).toLocaleString()}
              </Text>
            </div>
            <div style={{ textAlign: 'center' }}>
              <Text style={{ color: '#fbbf24', fontSize: 12 }}>冻结</Text>
              <br />
              <Text style={{ color: '#fbbf24', fontWeight: 600 }}>
                {parseFloat(account.frozen).toLocaleString()}
              </Text>
            </div>
          </Space>
        )}

        {/* User Menu */}
        <Dropdown menu={{ items: userMenuItems, onClick: handleUserMenuClick }}>
          <Button
            icon={<UserOutlined />}
            style={{
              background: 'rgba(34, 211, 238, 0.2)',
              borderColor: '#22d3ee',
              color: '#22d3ee',
            }}
          >
            {user?.username || '用户'}
          </Button>
        </Dropdown>
      </Header>

      <AntLayout>
        <Sider
          width={200}
          style={{
            background: 'rgba(15, 23, 42, 0.5)',
            borderRight: '1px solid #1e293b',
          }}
        >
          <Menu
            mode="inline"
            selectedKeys={[location.pathname]}
            items={menuItems}
            onClick={handleMenuClick}
            style={{
              height: '100%',
              background: 'transparent',
              color: '#94a3b8',
            }}
            theme="dark"
          />
        </Sider>

        <Content
          style={{
            padding: 24,
            background: '#020617',
            minHeight: 'calc(100vh - 64px)',
            overflow: 'auto',
          }}
        >
          <Outlet />
        </Content>
      </AntLayout>
    </AntLayout>
  )
}

export default Layout