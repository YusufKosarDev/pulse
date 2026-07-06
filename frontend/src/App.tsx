import { useEffect, useState } from 'react'
import {
  API_BASE,
  acknowledgeAlert,
  fetchAlerts,
  fetchForecast,
  fetchForecastOutcomes,
  fetchForecastOutcomeStats,
  fetchMetricNames,
  fetchPredictedAlerts,
  fetchRecent,
  fetchRecentAnomalies,
  resolveAlert,
} from './api'
import type {
  Alert,
  Anomaly,
  ForecastOutcome,
  ForecastOutcomeStats,
  ForecastSeries,
  MetricPoint,
  PredictedAlert,
} from './api'
import AlertList from './components/AlertList'
import LiveChart from './components/LiveChart'
import OutcomePanel from './components/OutcomePanel'

const ALERT_LIMIT = 20
const OUTCOME_LIMIT = 8
const OUTCOME_STATS_HOURS = 24

const RANGES = [
  { label: '10 m', minutes: 10 },
  { label: '1 h', minutes: 60 },
  { label: '6 h', minutes: 360 },
  { label: '24 h', minutes: 1440 },
]

// Ranges beyond the raw one come back bucketed; live raw appends would mix
// resolutions forever, so those ranges are refreshed over REST instead.
const RAW_RANGE_MINUTES = 10
const BUCKETED_REFRESH_MS = 60_000

function trimWindow<T extends { time: string }>(items: T[], minutes: number): T[] {
  const cutoff = Date.now() - minutes * 60_000
  return items.filter((item) => new Date(item.time).getTime() >= cutoff)
}

function App() {
  const [metricNames, setMetricNames] = useState<string[]>([])
  const [selected, setSelected] = useState('')
  const [windowMinutes, setWindowMinutes] = useState(RAW_RANGE_MINUTES)
  const [points, setPoints] = useState<MetricPoint[]>([])
  const [anomalies, setAnomalies] = useState<Anomaly[]>([])
  const [alerts, setAlerts] = useState<Alert[]>([])
  const [forecast, setForecast] = useState<ForecastSeries | null>(null)
  const [predictedAlerts, setPredictedAlerts] = useState<PredictedAlert[]>([])
  const [outcomes, setOutcomes] = useState<ForecastOutcome[]>([])
  const [outcomeStats, setOutcomeStats] = useState<ForecastOutcomeStats | null>(null)
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
        fetchRecent(selected, windowMinutes),
        fetchRecentAnomalies(selected, windowMinutes),
        fetchAlerts(ALERT_LIMIT),
        fetchForecast(selected),
        fetchPredictedAlerts(),
        fetchForecastOutcomes(OUTCOME_LIMIT),
        fetchForecastOutcomeStats(OUTCOME_STATS_HOURS),
      ])
        .then(([metricData, anomalyData, alertData, forecastData, predictedData,
                outcomeData, statsData]) => {
          if (!cancelled) {
            setPoints(metricData)
            setAnomalies(anomalyData)
            setAlerts(alertData)
            setForecast(forecastData)
            setPredictedAlerts(predictedData)
            setOutcomes(outcomeData)
            setOutcomeStats(statsData)
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
      setPoints((prev) => trimWindow([...prev, { time: m.time, value: m.value }], windowMinutes))
      setAnomalies((prev) => trimWindow(prev, windowMinutes))
    })

    source.addEventListener('anomaly', (e) => {
      if (cancelled) return
      const a = JSON.parse((e as MessageEvent).data) as Anomaly & { metricName: string }
      if (a.metricName !== selected) return
      setAnomalies((prev) => trimWindow([...prev, a], windowMinutes))
    })

    source.addEventListener('alerts-changed', () => {
      if (cancelled) return
      fetchAlerts(ALERT_LIMIT)
        .then((data) => {
          if (!cancelled) setAlerts(data)
        })
        .catch(() => undefined)
    })

    source.addEventListener('forecast-outcomes-changed', () => {
      if (cancelled) return
      Promise.all([
        fetchForecastOutcomes(OUTCOME_LIMIT),
        fetchForecastOutcomeStats(OUTCOME_STATS_HOURS),
      ])
        .then(([outcomeData, statsData]) => {
          if (!cancelled) {
            setOutcomes(outcomeData)
            setOutcomeStats(statsData)
          }
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

    // Re-bucket long ranges periodically so the raw live tail gets folded in.
    let refresher: number | undefined
    if (windowMinutes > RAW_RANGE_MINUTES) {
      refresher = window.setInterval(() => {
        Promise.all([
          fetchRecent(selected, windowMinutes),
          fetchRecentAnomalies(selected, windowMinutes),
        ])
          .then(([metricData, anomalyData]) => {
            if (!cancelled) {
              setPoints(metricData)
              setAnomalies(anomalyData)
            }
          })
          .catch(() => undefined)
      }, BUCKETED_REFRESH_MS)
    }

    return () => {
      cancelled = true
      source.close()
      if (refresher !== undefined) clearInterval(refresher)
    }
  }, [selected, windowMinutes])

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

        <div className="range-picker">
          {RANGES.map((range) => (
            <button
              key={range.minutes}
              type="button"
              className={range.minutes === windowMinutes ? 'active' : ''}
              onClick={() => setWindowMinutes(range.minutes)}
            >
              {range.label}
            </button>
          ))}
        </div>

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

      <section className="alerts-card">
        <h2>Forecast accuracy (last 24 h)</h2>
        <OutcomePanel stats={outcomeStats} outcomes={outcomes} />
      </section>
    </div>
  )
}

export default App
