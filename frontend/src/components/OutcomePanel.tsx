import type { ForecastOutcome, ForecastOutcomeStats } from '../api'

interface Props {
  stats: ForecastOutcomeStats | null
  outcomes: ForecastOutcome[]
}

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleTimeString([], { hour12: false })
}

function describe(o: ForecastOutcome): string {
  if (o.outcome === 'hit') {
    const sign = (o.errorMinutes ?? 0) >= 0 ? '+' : ''
    return `crossed ${formatTime(o.actualCrossingAt)} · predicted ${formatTime(
      o.predictedCrossingAt,
    )} (${sign}${(o.errorMinutes ?? 0).toFixed(1)} min)`
  }
  if (o.outcome === 'miss') {
    return `no crossing · predicted ${formatTime(o.predictedCrossingAt)}`
  }
  return `crossed ${formatTime(o.actualCrossingAt)} without warning`
}

export default function OutcomePanel({ stats, outcomes }: Props) {
  if (!stats || stats.hits + stats.misses + stats.unwarned === 0) {
    return <p className="empty">No graded predictions yet.</p>
  }

  return (
    <>
      <div className="stats-row">
        <div className="stat">
          <span className="stat-value">
            {stats.hitRate === null ? '—' : `${Math.round(stats.hitRate * 100)}%`}
          </span>
          <span className="stat-label">
            hit rate ({stats.hits}/{stats.hits + stats.misses})
          </span>
        </div>
        <div className="stat">
          <span className="stat-value">
            {stats.medianAbsErrorMinutes === null
              ? '—'
              : stats.medianAbsErrorMinutes.toFixed(1)}
          </span>
          <span className="stat-label">median |error| (min)</span>
          <span className="stat-sub">
            avg {stats.avgAbsErrorMinutes === null ? '—' : stats.avgAbsErrorMinutes.toFixed(1)}
          </span>
        </div>
        <div className="stat">
          <span className="stat-value">
            {stats.medianLeadMinutes === null ? '—' : stats.medianLeadMinutes.toFixed(1)}
          </span>
          <span className="stat-label">median lead (min)</span>
          <span className="stat-sub">
            avg {stats.avgLeadMinutes === null ? '—' : stats.avgLeadMinutes.toFixed(1)}
          </span>
        </div>
        <div className="stat">
          <span className="stat-value">{stats.unwarned}</span>
          <span className="stat-label">unwarned crossings</span>
        </div>
      </div>

      <ul className="alert-list">
        {outcomes.map((o) => (
          <li key={o.id} className="alert-row">
            <span className={`badge outcome-${o.outcome}`}>{o.outcome}</span>
            <span className="alert-metric">{o.metricName}</span>
            <span className="alert-detail">{describe(o)}</span>
            <span className="alert-time">{formatTime(o.closedAt)}</span>
          </li>
        ))}
      </ul>
    </>
  )
}
