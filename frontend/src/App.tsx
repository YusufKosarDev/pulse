import { useEffect, useState } from 'react'
import {
  API_BASE,
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

const WINDOW_MINUTES = 10
const ALERT_LIMIT = 20

function trimWindow<T extends { time: string }>(items: T[]): T[] {
  const cutoff = Date.now() - WINDOW_MINUTES * 60_000
  return items.filter((item) => new Date(item.time).getTime() >= cutoff)
}

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

    // Backfill the window over REST; from then on the SSE stream keeps it live.
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
    const source = new EventSource(`${API_BASE}/api/stream`)
    let reconnect = false

    source.onopen = () => {
      if (cancelled) return
      setError(null)
      // Refill whatever the dashboard missed while the stream was down.
      if (reconnect) load()
      reconnect = true
    }
    source.onerror = () => {
      if (!cancelled) setError('Live stream interrupted — reconnecting…')
    }

    source.addEventListener('metric', (e) => {
      if (cancelled) return
      const m = JSON.parse((e as MessageEvent).data) as {
        metricName: string
        time: string
        value: number
      }
      if (m.metricName !== selected) return
      setPoints((prev) => trimWindow([...prev, { time: m.time, value: m.value }]))
      setAnomalies(trimWindow)
    })

    source.addEventListener('anomaly', (e) => {
      if (cancelled) return
      const a = JSON.parse((e as MessageEvent).data) as Anomaly & { metricName: string }
      if (a.metricName !== selected) return
      setAnomalies((prev) => trimWindow([...prev, a]))
    })

    source.addEventListener('alerts-changed', () => {
      if (cancelled) return
      fetchAlerts(ALERT_LIMIT)
        .then((data) => {
          if (!cancelled) setAlerts(data)
        })
        .catch(() => undefined)
    })

    source.addEventListener('forecast-changed', (e) => {
      if (cancelled) return
      const f = JSON.parse((e as MessageEvent).data) as { metricName: string }
      if (f.metricName !== selected) return
      Promise.all([fetchForecast(selected), fetchPredictedAlerts()])
        .then(([forecastData, predictedData]) => {
          if (!cancelled) {
            setForecast(forecastData)
            setPredictedAlerts(predictedData)
          }
        })
        .catch(() => undefined)
    })

    return () => {
      cancelled = true
      source.close()
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
