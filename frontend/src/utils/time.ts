import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';

dayjs.extend(utc);
dayjs.extend(timezone);

// 时间戳转本地时间字符串
export function formatTime(timestamp: number, format = 'YYYY-MM-DD HH:mm:ss'): string {
  return dayjs(timestamp).format(format);
}

// 时间戳转时分秒 (用于成交明细)
export function formatTimeOnly(timestamp: number): string {
  return dayjs(timestamp).format('HH:mm:ss');
}

// 时间戳转日期 (用于K线图)
export function formatDate(timestamp: number): string {
  return dayjs(timestamp).format('YYYY-MM-DD');
}

// 相对时间 (用于订单列表)
export function formatRelativeTime(timestamp: number): string {
  const now = Date.now();
  const diff = now - timestamp;

  if (diff < 60000) return '刚刚';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}小时前`;
  return dayjs(timestamp).format('MM-DD HH:mm');
}
