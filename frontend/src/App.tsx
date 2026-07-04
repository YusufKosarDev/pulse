import { useEffect, useState } from 'react'
import { fetchLatestAnomalies, fetchMetricNames, fetchRecent, fetchRecentAnomalies } from './api'
import type { Anomaly, MetricPoint } from './api'
import AlertList from './components/AlertList'
import LiveChart from './components/LiveChart'

const POLL_INTERVAL_MS = 3000
const WINDOW_MINUTES = 10
const ALERT_LIMIT = 20

function App() {
  const [metricNames, setMetricNames] = useState<string[]>([])
  const [selected, setSelected] = useState('')
  const [points, setPoints] = useState<MetricPoint[]>([])
  const [anomalies, setAnomalies] = useState<Anomaly[]>([])
  const [alerts, setAlerts] = useState<Anomaly[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchMetricNames()
      .then((names) => {
        setMetricNames(names)
        setSelected((prev) => prev || names[0] || '')
      })
      .catch(() => setError('Cannot reach the metrics API'))
  }, [])

  useEffect(() => {
    if (!selected) return
    let cancelled = false

    const load = () => {
      Promise.all([
        fetchRecent(selected, WINDOW_MINUTES),
        fetchRecentAnomalies(selected, WINDOW_MINUTES),
        fetchLatestAnomalies(ALERT_LIMIT),
      ])
        .then(([metricData, anomalyData, alertData]) => {
          if (!cancelled) {
            setPoints(metricData)
            setAnomalies(anomalyData)
            setAlerts(alertData)
            setError(null)
          }
        })
        .catch(() => {
          if (!cancelled) setError('Cannot reach the metrics API')
        })
    }

    load()
    const timer = setInterval(load, POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [selected])

  const last = points.length > 0 ? points[points.length - 1] : undefined

  return (
    <div className="page">
      <header className="header">
        <h1>Pulse</h1>
        <span className="subtitle">Live operational telemetry</span>
      </header>

      <div className="controls">
        <label htmlFor="metric-select">Metric</label>
        <select
          id="metric-select"
          value={selected}
          onChange={(e) => setSelected(e.target.value)}
        >
          {metricNames.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>

        {last && (
          <div className="last-value">
            <span className="value">{last.value.toFixed(2)}</span>
            <span className="at">
              at {new Date(last.time).toLocaleTimeString([], { hour12: false })}
            </span>
          </div>
        )}
      </div>

      {error && <div className="error">{error}</div>}

      <section className="chart-card">
        {points.length > 0 ? (
          <LiveChart points={points} anomalies={anomalies} />
        ) : (
          !error && <p className="empty">Waiting for data…</p>
        )}
      </section>

      <section className="alerts-card">
        <h2>Recent alerts</h2>
        <AlertList alerts={alerts} />
      </section>
    </div>
  )
}

export default App
