import { useEffect, useRef } from 'react'
import {
  createChart,
  ColorType,
  CrosshairMode,
  CandlestickSeries,
} from 'lightweight-charts'
import type { IChartApi, CandlestickData, CandlestickSeriesPartialOptions } from 'lightweight-charts'
import { useMarketStore } from '../stores'
import { KlineInterval } from '../websocket/types'

interface TradingViewChartProps {
  symbol: string
  interval?: KlineInterval
  height?: number
}

// Color scheme matching dark theme
const CHART_COLORS = {
  background: '#0f172a',
  grid: '#1e293b',
  text: '#94a3b8',
  upColor: '#34d399',
  downColor: '#fb7185',
  borderUpColor: '#34d399',
  borderDownColor: '#fb7185',
  wickUpColor: '#34d399',
  wickDownColor: '#fb7185',
}

export function TradingViewChart({
  symbol,
  interval = '1m',
  height = 400,
}: TradingViewChartProps) {
  const chartContainerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candlestickSeriesRef = useRef<any>(null)

  const { klines } = useMarketStore()

  // Initialize chart
  useEffect(() => {
    if (!chartContainerRef.current) return

    const chart = createChart(chartContainerRef.current, {
      width: chartContainerRef.current.clientWidth,
      height,
      layout: {
        background: { type: ColorType.Solid, color: CHART_COLORS.background },
        textColor: CHART_COLORS.text,
      },
      grid: {
        vertLines: { color: CHART_COLORS.grid },
        horzLines: { color: CHART_COLORS.grid },
      },
      crosshair: {
        mode: CrosshairMode.Normal,
      },
      rightPriceScale: {
        borderColor: CHART_COLORS.grid,
      },
      timeScale: {
        borderColor: CHART_COLORS.grid,
        timeVisible: true,
        secondsVisible: false,
      },
    })

    chartRef.current = chart

    // Create candlestick series using v5 API with CandlestickSeries import
    const options: CandlestickSeriesPartialOptions = {
      upColor: CHART_COLORS.upColor,
      downColor: CHART_COLORS.downColor,
      borderUpColor: CHART_COLORS.borderUpColor,
      borderDownColor: CHART_COLORS.borderDownColor,
      wickUpColor: CHART_COLORS.wickUpColor,
      wickDownColor: CHART_COLORS.wickDownColor,
    }

    const candlestickSeries = chart.addSeries(CandlestickSeries, options)
    candlestickSeriesRef.current = candlestickSeries

    // Handle resize
    const handleResize = () => {
      if (chartContainerRef.current && chartRef.current) {
        chartRef.current.applyOptions({
          width: chartContainerRef.current.clientWidth,
        })
      }
    }
    window.addEventListener('resize', handleResize)

    return () => {
      window.removeEventListener('resize', handleResize)
      chart.remove()
      chartRef.current = null
      candlestickSeriesRef.current = null
    }
  }, [height])

  // Update chart data when klines change
  useEffect(() => {
    if (!candlestickSeriesRef.current) return

    const symbolKlines = klines[symbol]?.[interval] || []

    const candleData: CandlestickData[] = symbolKlines.map((k) => ({
      time: Math.floor(k.openTime / 1000) as any,
      open: parseFloat(k.open),
      high: parseFloat(k.high),
      low: parseFloat(k.low),
      close: parseFloat(k.close),
    }))

    candlestickSeriesRef.current.setData(candleData)
  }, [symbol, interval, klines])

  return (
    <div
      ref={chartContainerRef}
      style={{ width: '100%', height }}
    />
  )
}

// Export a simpler price display component
export function PriceDisplay({
  symbol,
  showChange = true,
}: {
  symbol: string
  showChange?: boolean
}) {
  const { marketSnapshots, symbols } = useMarketStore()

  const snapshot = marketSnapshots[symbol]
  const symbolInfo = symbols.find((s) => s.symbol === symbol)

  if (!snapshot || !symbolInfo) {
    return <span style={{ color: CHART_COLORS.text }}>--</span>
  }

  const currentPrice = snapshot.bidPrices[0] || '0'
  const prevClose = symbolInfo.prevClose
  const change = parseFloat(currentPrice) - parseFloat(prevClose)
  const changePercent = (change / parseFloat(prevClose)) * 100

  const color = change >= 0 ? CHART_COLORS.upColor : CHART_COLORS.downColor

  return (
    <span style={{ color }}>
      {currentPrice}
      {showChange && (
        <span style={{ marginLeft: 8, fontSize: '0.85em' }}>
          {change >= 0 ? '+' : ''}{change.toFixed(2)} ({changePercent.toFixed(2)}%)
        </span>
      )}
    </span>
  )
}

export default TradingViewChart