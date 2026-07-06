import type { Alert } from '../api'

interface Props {
  alerts: Alert[]
  onAcknowledge: (id: number) => void
  onResolve: (id: number) => void
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour12: false })
}

function timeRange(alert: Alert): string {
  const first = formatTime(alert.firstSeen)
  const last = formatTime(alert.lastSeen)
  return first === last ? first : `${first} – ${last}`
}

export default function AlertList({ alerts, onAcknowledge, onResolve }: Props) {
  if (alerts.length === 0) {
    return <p className="empty">No alerts yet.</p>
  }

  return (
    <ul className="alert-list">
      {alerts.map((a) => (
        <li key={a.id} className={`alert-row ${a.status === 'resolved' ? 'resolved' : ''}`}>
          <span className={`badge status-${a.status}`}>{a.status}</span>
          <span className={`badge ${a.severity}`}>{a.severity}</span>
          <span className="alert-metric">{a.metricName}</span>
          <span className="alert-detail">
            {a.sensorId} · {a.anomalyCount} detection{a.anomalyCount === 1 ? '' : 's'} · last{' '}
            {a.lastValue.toFixed(2)} · z {a.maxZScore.toFixed(2)}
          </span>
          <span className="alert-time">{timeRange(a)}</span>
          {a.status !== 'resolved' && (
            <span className="alert-actions">
              {a.status === 'open' && (
                <button type="button" onClick={() => onAcknowledge(a.id)}>
                  Acknowledge
                </button>
              )}
              <button type="button" onClick={() => onResolve(a.id)}>
                Resolve
              </button>
            </span>
          )}
        </li>
      ))}
    </ul>
  )
}
