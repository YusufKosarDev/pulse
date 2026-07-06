import { useEffect, useState } from 'react'
import {
  acknowledgeAlert,
  fetchAlerts,
  fetchForecast,
  fetchMetricNames,
  fetchPredictedAlerts,
  fetchRecent,
  fetchRecentAnomalies,
  resolveAlert,
} from './api'
import type { Alert, Anomaly, ForecastSeries, MetricPoint, PredictedAlert } from './api'
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
  const [alerts, setAlerts] = useState<Alert[]>([])
  const [forecast, setForecast] = useState<ForecastSeries | null>(null)
  const [predictedAlerts, setPredictedAlerts] = useState<PredictedAlert[]>([])
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
        fetchAlerts(ALERT_LIMIT),
        fetchForecast(selected),
        fetchPredictedAlerts(),
      ])
        .then(([metricData, anomalyData, alertData, forecastData, predictedData]) => {
          if (!cancelled) {
            setPoints(metricData)
            setAnomalies(anomalyData)
            setAlerts(alertData)
            setForecast(forecastData)
            setPredictedAlerts(predictedData)
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

  // A 409 means the alert changed state under us; the refetch resyncs either way.
  const applyAlertAction = (action: (id: number) => Promise<void>) => (id: number) => {
    action(id)
      .catch(() => undefined)
      .then(() => fetchAlerts(ALERT_LIMIT))
      .then(setAlerts)
      .catch(() => setError('Cannot reach the metrics API'))
  }

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

      {predictedAlerts.map((p) => {
        const minutes = Math.max(0, Math.round(
          (new Date(p.predictedCrossingAt).getTime() - Date.now()) / 60000))
        const at = new Date(p.predictedCrossingAt).toLocaleTimeString([], {
          hour: '2-digit', minute: '2-digit', hour12: false,
        })
        return (
          <div key={`${p.metricName}-${p.sensorId}`} className="forecast-banner">
            <span className="badge predicted">forecast</span>
            <span>
              <strong>{p.metricName}</strong> is expected to reach {p.threshold.toFixed(1)} around{' '}
              {at} (~{minutes} min)
            </span>
          </div>
        )
      })}

      <section className="chart-card">
        {points.length > 0 ? (
          <LiveChart
            points={points}
            anomalies={anomalies}
            forecast={forecast}
            predictedAlert={predictedAlerts.find((p) => p.metricName === selected) ?? null}
          />
        ) : (
          !error && <p className="empty">Waiting for data…</p>
        )}
      </section>

      <section className="alerts-card">
        <h2>Alerts</h2>
        <AlertList
          alerts={alerts}
          onAcknowledge={applyAlertAction(acknowledgeAlert)}
          onResolve={applyAlertAction(resolveAlert)}
        />
      </section>
    </div>
  )
}

export default App
