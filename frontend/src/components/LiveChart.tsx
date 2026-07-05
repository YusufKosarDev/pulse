import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceDot,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { Anomaly, ForecastSeries, MetricPoint, PredictedAlert } from '../api'

const LINE_COLOR = '#2563eb'
const FORECAST_COLOR = '#7c93c4'
const THRESHOLD_COLOR = '#dc2626'
const CROSSING_COLOR = '#d97706'
export const SEVERITY_COLORS = {
  warning: '#d97706',
  critical: '#dc2626',
} as const

interface Props {
  points: MetricPoint[]
  anomalies: Anomaly[]
  forecast: ForecastSeries | null
  predictedAlert: PredictedAlert | null
}

function formatClock(t: number): string {
  return new Date(t).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

export default function LiveChart({ points, anomalies, forecast, predictedAlert }: Props) {
  const data: Array<{ t: number; value?: number; forecast?: number }> = points.map((p) => ({
    t: new Date(p.time).getTime(),
    value: p.value,
  }))
  for (const p of forecast?.points ?? []) {
    data.push({ t: new Date(p.time).getTime(), forecast: p.value })
  }
  const threshold = forecast?.threshold ?? null

  return (
    <ResponsiveContainer width="100%" height={360}>
      <LineChart data={data} margin={{ top: 8, right: 16, bottom: 4, left: 0 }}>
        <CartesianGrid stroke="var(--grid)" vertical={false} />
        <XAxis
          dataKey="t"
          type="number"
          scale="time"
          domain={['dataMin', 'dataMax']}
          tickFormatter={formatClock}
          tick={{ fill: 'var(--text-muted)', fontSize: 12 }}
          tickLine={false}
          axisLine={false}
          minTickGap={48}
        />
        <YAxis
          domain={['auto', 'auto']}
          tick={{ fill: 'var(--text-muted)', fontSize: 12 }}
          tickLine={false}
          axisLine={false}
          width={56}
          tickFormatter={(v: number) => v.toFixed(1)}
        />
        <Tooltip
          isAnimationActive={false}
          labelFormatter={(t) => formatClock(Number(t))}
          formatter={(value, name) => [Number(value).toFixed(2), name]}
          contentStyle={{
            background: 'var(--surface)',
            border: '1px solid var(--border)',
            borderRadius: 8,
            color: 'var(--text)',
          }}
        />
        {threshold !== null && (
          <ReferenceLine
            y={threshold}
            stroke={THRESHOLD_COLOR}
            strokeDasharray="4 4"
            ifOverflow="extendDomain"
            label={{
              value: `limit ${threshold}`,
              position: 'insideTopRight',
              fill: THRESHOLD_COLOR,
              fontSize: 12,
            }}
          />
        )}
        <Line
          type="monotone"
          dataKey="value"
          name="value"
          stroke={LINE_COLOR}
          strokeWidth={2}
          dot={false}
          activeDot={{ r: 4 }}
          isAnimationActive={false}
        />
        <Line
          type="monotone"
          dataKey="forecast"
          name="forecast"
          stroke={FORECAST_COLOR}
          strokeWidth={2}
          strokeDasharray="6 5"
          dot={false}
          isAnimationActive={false}
        />
        {predictedAlert && (
          <ReferenceDot
            x={new Date(predictedAlert.predictedCrossingAt).getTime()}
            y={predictedAlert.threshold}
            r={6}
            fill={CROSSING_COLOR}
            stroke="var(--surface)"
            strokeWidth={2}
            ifOverflow="extendDomain"
          />
        )}
        {anomalies.map((a) => (
          <ReferenceDot
            key={`${a.time}-${a.sensorId}`}
            x={new Date(a.time).getTime()}
            y={a.value}
            r={5}
            fill={SEVERITY_COLORS[a.severity] ?? SEVERITY_COLORS.warning}
            stroke="var(--surface)"
            strokeWidth={2}
          />
        ))}
      </LineChart>
    </ResponsiveContainer>
  )
}
