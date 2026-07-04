const API_BASE: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8081'

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
