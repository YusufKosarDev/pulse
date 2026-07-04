import type { Anomaly } from '../api'

interface Props {
  alerts: Anomaly[]
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour12: false })
}

export default function AlertList({ alerts }: Props) {
  if (alerts.length === 0) {
    return <p className="empty">No anomalies detected yet.</p>
  }

  return (
    <ul className="alert-list">
      {alerts.map((a) => (
        <li key={`${a.time}-${a.metricName}-${a.sensorId}`} className="alert-row">
          <span className={`badge ${a.severity}`}>{a.severity}</span>
          <span className="alert-metric">{a.metricName}</span>
          <span className="alert-detail">
            value {a.value.toFixed(2)} · z {a.zScore.toFixed(2)}
          </span>
          <span className="alert-time">{formatTime(a.time)}</span>
        </li>
      ))}
    </ul>
  )
}
