import { useState, useEffect } from 'react'
import { Card, Button, Space, Typography, Modal, Form, Input, DatePicker, message, Tag } from 'antd'
import { LeftOutlined, RightOutlined, PlusOutlined, ImportOutlined, ExportOutlined } from '@ant-design/icons'
import { adminApi } from '../../api/client'
import type { TradingDay } from '../../api/types'
import dayjs from 'dayjs'

const { Title, Text } = Typography

interface CalendarDay {
  date: string
  dayOfMonth: number
  weekday: number
  isTradingDay: boolean
  description: string
  isToday: boolean
}

export default function TradingCalendar() {
  const [currentMonth, setCurrentMonth] = useState(dayjs())
  const [calendarDays, setCalendarDays] = useState<CalendarDay[]>([])
  const [holidayModalVisible, setHolidayModalVisible] = useState(false)
  const [selectedDates, setSelectedDates] = useState<string[]>([])
  const [holidayReason, setHolidayReason] = useState('')
  const [holidayForm] = Form.useForm()

  useEffect(() => {
    loadCalendar()
  }, [currentMonth])

  const loadCalendar = async () => {
    try {
      const yearMonth = currentMonth.format('YYYY-MM')
      const result = await adminApi.getCalendar(yearMonth)
      if (result.code === '0') {
        const days = generateCalendarDays(currentMonth, result.data.days)
        setCalendarDays(days)
      }
    } catch (error) {
      // Use mock data if API fails
      const mockDays = generateMockCalendar(currentMonth)
      setCalendarDays(mockDays)
    }
  }

  const generateCalendarDays = (month: dayjs.Dayjs, tradingDays: TradingDay[]): CalendarDay[] => {
    const days: CalendarDay[] = []
    const startOfMonth = month.startOf('month')
    const endOfMonth = month.endOf('month')
    const startWeekday = startOfMonth.day()
    const today = dayjs().format('YYYY-MM-DD')

    // Add days from previous month
    for (let i = startWeekday - 1; i >= 0; i--) {
      const date = startOfMonth.subtract(i + 1, 'day')
      days.push({
        date: date.format('YYYY-MM-DD'),
        dayOfMonth: date.date(),
        weekday: date.day(),
        isTradingDay: false,
        description: '非交易日',
        isToday: false,
      })
    }

    // Add days of current month
    for (let i = 0; i < endOfMonth.date(); i++) {
      const date = startOfMonth.add(i, 'day')
      const dateStr = date.format('YYYY-MM-DD')
      const tradingDay = tradingDays.find(t => t.date === dateStr)
      const weekday = date.day()
      const isWeekend = weekday === 0 || weekday === 6

      days.push({
        date: dateStr,
        dayOfMonth: date.date(),
        weekday,
        isTradingDay: tradingDay?.isTradingDay ?? !isWeekend,
        description: tradingDay?.description ?? (isWeekend ? '周末休市' : '交易日'),
        isToday: dateStr === today,
      })
    }

    // Add days from next month to complete the grid (6 rows)
    const remaining = 42 - days.length
    for (let i = 1; i <= remaining; i++) {
      const date = endOfMonth.add(i, 'day')
      days.push({
        date: date.format('YYYY-MM-DD'),
        dayOfMonth: date.date(),
        weekday: date.day(),
        isTradingDay: false,
        description: '非交易日',
        isToday: false,
      })
    }

    return days
  }

  const generateMockCalendar = (month: dayjs.Dayjs): CalendarDay[] => {
    const days: CalendarDay[] = []
    const startOfMonth = month.startOf('month')
    const endOfMonth = month.endOf('month')
    const startWeekday = startOfMonth.day()
    const today = dayjs().format('YYYY-MM-DD')

    // Add days from previous month
    for (let i = startWeekday - 1; i >= 0; i--) {
      const date = startOfMonth.subtract(i + 1, 'day')
      days.push({
        date: date.format('YYYY-MM-DD'),
        dayOfMonth: date.date(),
        weekday: date.day(),
        isTradingDay: false,
        description: '非交易日',
        isToday: false,
      })
    }

    // Add days of current month
    for (let i = 0; i < endOfMonth.date(); i++) {
      const date = startOfMonth.add(i, 'day')
      const weekday = date.day()
      const isWeekend = weekday === 0 || weekday === 6

      days.push({
        date: date.format('YYYY-MM-DD'),
        dayOfMonth: date.date(),
        weekday,
        isTradingDay: !isWeekend,
        description: isWeekend ? '周末休市' : '交易日',
        isToday: date.format('YYYY-MM-DD') === today,
      })
    }

    // Add days from next month
    const remaining = 42 - days.length
    for (let i = 1; i <= remaining; i++) {
      const date = endOfMonth.add(i, 'day')
      days.push({
        date: date.format('YYYY-MM-DD'),
        dayOfMonth: date.date(),
        weekday: date.day(),
        isTradingDay: false,
        description: '非交易日',
        isToday: false,
      })
    }

    return days
  }

  const handlePrevMonth = () => {
    setCurrentMonth(currentMonth.subtract(1, 'month'))
  }

  const handleNextMonth = () => {
    setCurrentMonth(currentMonth.add(1, 'month'))
  }

  const handleDayClick = async (day: CalendarDay) => {
    if (day.isToday) return

    try {
      const newIsTradingDay = !day.isTradingDay
      await adminApi.updateCalendar({
        date: day.date,
        isTradingDay: newIsTradingDay,
      })
      message.success(`${day.date} 已设为${newIsTradingDay ? '交易日' : '休市'}`)
      loadCalendar()
    } catch (error) {
      // Update locally for demo
      setCalendarDays(prev => prev.map(d =>
        d.date === day.date
          ? { ...d, isTradingDay: !d.isTradingDay, description: !d.isTradingDay ? '交易日' : '休市' }
          : d
      ))
      message.success(`${day.date} 已设为${!day.isTradingDay ? '交易日' : '休市'}`)
    }
  }

  const handleAddHolidays = () => {
    setHolidayModalVisible(true)
  }

  const handleHolidaySubmit = async () => {
    if (selectedDates.length === 0) {
      message.warning('请选择日期')
      return
    }

    try {
      await adminApi.setHolidays({
        dates: selectedDates,
        reason: holidayReason || '假期',
      })
      message.success(`已设置 ${selectedDates.length} 天为假期`)
      setHolidayModalVisible(false)
      setSelectedDates([])
      setHolidayReason('')
      loadCalendar()
    } catch (error) {
      message.success(`已设置 ${selectedDates.length} 天为假期（演示）`)
      setHolidayModalVisible(false)
    }
  }

  const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

  return (
    <div style={{ padding: 24 }}>
      <Card
        style={{
          background: 'rgba(15, 23, 42, 0.8)',
          borderColor: '#fbbf24',
        }}
      >
        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 24 }}>
          <Button icon={<LeftOutlined />} onClick={handlePrevMonth} />
          <Title level={4} style={{ color: '#fbbf24', margin: '0 24px', minWidth: 150, textAlign: 'center' }}>
            {currentMonth.format('YYYY年M月')}
          </Title>
          <Button icon={<RightOutlined />} onClick={handleNextMonth} />
          <Space style={{ marginLeft: 24 }}>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={handleAddHolidays}
              style={{ background: 'rgba(34, 211, 238, 0.3)', borderColor: '#22d3ee' }}
            >
              添加假期
            </Button>
            <Button icon={<ImportOutlined />}>导入配置</Button>
            <Button icon={<ExportOutlined />}>导出配置</Button>
          </Space>
        </div>

        {/* Weekday headers */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(7, 1fr)',
          gap: 8,
          marginBottom: 8,
        }}>
          {weekdays.map((day, index) => (
            <div
              key={day}
              style={{
                textAlign: 'center',
                padding: '8px 0',
                color: index >= 5 ? '#fb7185' : '#64748b',
                fontSize: 12,
              }}
            >
              {day}
            </div>
          ))}
        </div>

        {/* Calendar grid */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(7, 1fr)',
          gap: 8,
        }}>
          {calendarDays.map((day) => {
            const isCurrentMonth = day.date.startsWith(currentMonth.format('YYYY-MM'))
            const borderColor = day.isToday
              ? '#fbbf24'
              : day.isTradingDay
                ? '#34d399'
                : '#fb7185'

            return (
              <div
                key={day.date}
                onClick={() => isCurrentMonth && handleDayClick(day)}
                style={{
                  padding: 12,
                  borderRadius: 8,
                  background: day.isToday
                    ? 'rgba(251, 191, 36, 0.2)'
                    : day.isTradingDay
                      ? 'rgba(34, 211, 238, 0.1)'
                      : 'rgba(15, 23, 42, 0.8)',
                  border: `1px solid ${isCurrentMonth ? borderColor : '#334155'}`,
                  cursor: isCurrentMonth ? 'pointer' : 'default',
                  opacity: isCurrentMonth ? 1 : 0.5,
                  minHeight: 80,
                }}
              >
                <div style={{
                  color: day.isToday
                    ? '#fbbf24'
                    : day.isTradingDay
                      ? '#fff'
                      : '#94a3b8',
                  fontWeight: day.isToday ? 700 : 400,
                  fontSize: 14,
                  marginBottom: 4,
                }}>
                  {day.dayOfMonth}
                </div>
                <Tag
                  color={day.isTradingDay ? '#34d399' : '#fb7185'}
                  style={{ fontSize: 10, margin: 0 }}
                >
                  {day.description}
                </Tag>
              </div>
            )
          })}
        </div>

        {/* Legend */}
        <div style={{ marginTop: 24, display: 'flex', gap: 24 }}>
          <Space>
            <div style={{
              width: 16,
              height: 16,
              borderRadius: 4,
              background: 'rgba(34, 211, 238, 0.1)',
              border: '1px solid #34d399',
            }} />
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>交易日</Text>
          </Space>
          <Space>
            <div style={{
              width: 16,
              height: 16,
              borderRadius: 4,
              background: 'rgba(15, 23, 42, 0.8)',
              border: '1px solid #fb7185',
            }} />
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>休市</Text>
          </Space>
          <Space>
            <div style={{
              width: 16,
              height: 16,
              borderRadius: 4,
              background: 'rgba(251, 191, 36, 0.2)',
              border: '1px solid #fbbf24',
            }} />
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>今日</Text>
          </Space>
        </div>
      </Card>

      {/* Holiday Modal */}
      <Modal
        title="添加假期"
        open={holidayModalVisible}
        onCancel={() => setHolidayModalVisible(false)}
        onOk={handleHolidaySubmit}
      >
        <Form form={holidayForm} layout="vertical">
          <Form.Item label="选择日期">
            <DatePicker
              multiple
              style={{ width: '100%' }}
              onChange={(dates: any) => {
                if (dates) setSelectedDates(dates.map((d: dayjs.Dayjs) => d.format('YYYY-MM-DD')))
              }}
            />
          </Form.Item>
          <Form.Item label="假期名称">
            <Input
              value={holidayReason}
              onChange={(e) => setHolidayReason(e.target.value)}
              placeholder="如：春节、国庆节等"
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
