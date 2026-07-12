// Captures the still screenshots used in the README: a dashboard overview, the
// forecast hero (forecast line + threshold + breach banner), the accuracy
// scorecard, and the alert list with a lifecycle mix. Reuses the same live
// stack and demo actions as record-demo.mjs.
//
//   cd tools/demo-recording && npm install && node screenshot.mjs

import { execSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { chromium } from 'playwright'

const DASHBOARD_URL = process.env.DASHBOARD_URL ?? 'http://localhost:5173'
const API_BASE = process.env.API_BASE ?? 'http://localhost:8081'
const METRIC = 'temperature_c'
const REDIS = 'pulse-redis'
const RAMP_SLOPE = 0.05
const OUT_DIR = join(process.cwd(), 'output', 'screens')
const VIEWPORT = { width: 1280, height: 800 }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const redis = (args) => execSync(`docker exec ${REDIS} redis-cli ${args}`, { stdio: 'pipe' })

async function predictedAlertActive() {
  try {
    const res = await fetch(`${API_BASE}/api/predicted-alerts`)
    const a = await res.json()
    return Array.isArray(a) && a.some((x) => x.metricName === METRIC)
  } catch { return false }
}

async function acknowledgeOneOpenAlert() {
  try {
    const res = await fetch(`${API_BASE}/api/alerts?limit=20`)
    const alerts = await res.json()
    const open = alerts.find((a) => a.status === 'open')
    if (open) await fetch(`${API_BASE}/api/alerts/${open.id}/acknowledge`, { method: 'POST' })
  } catch { /* best effort — the panel still shows open/resolved */ }
}

async function main() {
  mkdirSync(OUT_DIR, { recursive: true })

  console.log('Warming the demo ramp for the forecast shot…')
  redis(`HSET forecast:demo ${METRIC} ${RAMP_SLOPE}`)
  const deadline = Date.now() + 180_000
  while (Date.now() < deadline) {
    if (await predictedAlertActive()) break
    await sleep(3000)
  }

  await acknowledgeOneOpenAlert()

  const browser = await chromium.launch()
  const context = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 2 })
  const page = await context.newPage()
  await page.goto(DASHBOARD_URL, { waitUntil: 'domcontentloaded' })
  await page.selectOption('#metric-select', METRIC)
  await page.waitForSelector('.chart-card svg', { timeout: 15_000 })
  await sleep(2500)

  // 1. Overview: header, controls, breach banner and the live chart.
  const chartBox = await page.locator('.chart-card').boundingBox()
  await page.screenshot({
    path: join(OUT_DIR, 'dashboard.png'),
    clip: { x: 0, y: 0, width: VIEWPORT.width, height: Math.ceil(chartBox.y + chartBox.height + 16) },
  })

  // 2. Forecast hero: breach banner + the forecast line crossing the threshold.
  const bannerBox = await page.locator('.forecast-banner').first().boundingBox()
  const top = bannerBox ? bannerBox.y : chartBox.y
  await page.screenshot({
    path: join(OUT_DIR, 'forecast.png'),
    clip: { x: chartBox.x, y: top, width: chartBox.width, height: Math.ceil(chartBox.y + chartBox.height - top + 16) },
  })

  // 3. Scorecard: the forecast-accuracy panel.
  await page.locator('.alerts-card').last().screenshot({ path: join(OUT_DIR, 'scorecard.png') })

  // 4. Alerts: the grouped-alert list with open / acknowledged / resolved rows.
  await page.locator('.alerts-card').first().screenshot({ path: join(OUT_DIR, 'alerts.png') })

  await context.close()
  await browser.close()
  redis(`DEL forecast:demo`)

  console.log(`Screenshots written to ${OUT_DIR}`)
}

main().catch((err) => {
  console.error(err)
  try { redis(`DEL forecast:demo`) } catch {}
  process.exit(1)
})
