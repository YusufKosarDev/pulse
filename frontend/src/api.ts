export const API_BASE: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8081'

export interface MetricPoint {
  time: string
  value: number
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`)
  if (!response.ok) {
    throw new Error(`API request failed: ${response.status}`)
  }
  return response.json() as Promise<T>
}

export function fetchMetricNames(): Promise<string[]> {
  return getJson<string[]>('/api/metrics/names')
}

export function fetchRecent(metric: string, minutes: number): Promise<MetricPoint[]> {
  const params = new URLSearchParams({ metric, minutes: String(minutes) })
  return getJson<MetricPoint[]>(`/api/metrics/recent?${params}`)
}

export type Severity = 'warning' | 'critical'

export interface Anomaly {
  time: string
  metricName: string
  sensorId: string
  value: number
  zScore: number
  severity: Severity
}

export function fetchRecentAnomalies(metric: string, minutes: number): Promise<Anomaly[]> {
  const params = new URLSearchParams({ metric, minutes: String(minutes) })
  return getJson<Anomaly[]>(`/api/anomalies/recent?${params}`)
}

export type AlertStatus = 'open' | 'acknowledged' | 'resolved'

export interface Alert {
  id: number
  metricName: string
  sensorId: string
  severity: Severity
  status: AlertStatus
  anomalyCount: number
  firstSeen: string
  lastSeen: string
  lastValue: number
  maxZScore: number
  acknowledgedAt: string | null
  resolvedAt: string | null
}

export function fetchAlerts(limit: number): Promise<Alert[]> {
  const params = new URLSearchParams({ limit: String(limit) })
  return getJson<Alert[]>(`/api/alerts?${params}`)
}

async function post(path: string): Promise<void> {
  const response = await fetch(`${API_BASE}${path}`, { method: 'POST' })
  if (!response.ok) {
    throw new Error(`API request failed: ${response.status}`)
  }
}

export function acknowledgeAlert(id: number): Promise<void> {
  return post(`/api/alerts/${id}/acknowledge`)
}

export function resolveAlert(id: number): Promise<void> {
  return post(`/api/alerts/${id}/resolve`)
}

export interface ForecastSeries {
  metricName: string
  sensorId: string | null
  generatedAt: string | null
  threshold: number | null
  points: MetricPoint[]
}

export interface PredictedAlert {
  metricName: string
  sensorId: string
  threshold: number
  predictedValue: number
  predictedCrossingAt: string
  updatedAt: string
}

export function fetchForecast(metric: string): Promise<ForecastSeries> {
  const params = new URLSearchParams({ metric })
  return getJson<ForecastSeries>(`/api/forecasts?${params}`)
}

export function fetchPredictedAlerts(): Promise<PredictedAlert[]> {
  return getJson<PredictedAlert[]>('/api/predicted-alerts')
}

export type OutcomeKind = 'hit' | 'miss' | 'unwarned'

export interface ForecastOutcome {
  id: number
  metricName: string
  sensorId: string
  threshold: number
  outcome: OutcomeKind
  predictedCrossingAt: string | null
  actualCrossingAt: string | null
  errorMinutes: number | null
  leadMinutes: number | null
  closedAt: string
}

export interface ForecastOutcomeStats {
  hits: number
  misses: number
  unwarned: number
  hitRate: number | null
  avgAbsErrorMinutes: number | null
  avgLeadMinutes: number | null
}

export function fetchForecastOutcomes(limit: number): Promise<ForecastOutcome[]> {
  const params = new URLSearchParams({ limit: String(limit) })
  return getJson<ForecastOutcome[]>(`/api/forecast-outcomes?${params}`)
}

export function fetchForecastOutcomeStats(hours: number): Promise<ForecastOutcomeStats> {
  const params = new URLSearchParams({ hours: String(hours) })
  return getJson<ForecastOutcomeStats>(`/api/forecast-outcomes/stats?${params}`)
}
