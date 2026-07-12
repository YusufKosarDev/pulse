// Records a short, self-contained demo of the Pulse dashboard: a live metric
// stream, an injected spike that raises a critical anomaly, a demo ramp that
// makes the forecast predict a threshold breach, and the forecast-accuracy
// scorecard. Output is a webm video plus a README-ready GIF.
//
// Prerequisites: the full stack running (docker compose up -d) and the
// dashboard dev server on http://localhost:5173.
//
//   cd tools/demo-recording && npm install && npm run record
//
// Everything it triggers is a normal demo action (XADD a reading, HSET the
// demo ramp key) against the local Redis — the same commands the README
// documents by hand.

import { execSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, renameSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { chromium } from 'playwright'

const DASHBOARD_URL = process.env.DASHBOARD_URL ?? 'http://localhost:5173'
const API_BASE = process.env.API_BASE ?? 'http://localhost:8081'
const METRIC = 'temperature_c'
const SENSOR = 'sensor-lobby-1'
const REDIS = 'pulse-redis'
const RAMP_SLOPE = 0.05 // drift per 2 s tick: enough to trigger a forecast
                        // breach without pushing the y-axis off the chart
const OUT_DIR = join(process.cwd(), 'output')
const VIDEO_SIZE = { width: 1280, height: 800 }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function redis(args) {
  execSync(`docker exec ${REDIS} redis-cli ${args}`, { stdio: 'pipe' })
}

function startRamp() {
  redis(`HSET forecast:demo ${METRIC} ${RAMP_SLOPE}`)
}

function stopRamp() {
  redis(`DEL forecast:demo`)
}

function injectSpike(value) {
  // Unquoted * — execSync runs via cmd.exe, which passes it literally (a
  // quoted '*' would reach redis-cli with the quotes and be an invalid ID).
  redis(`XADD metrics * metric ${METRIC} sensor_id ${SENSOR} value ${value} timestamp ${Date.now()}`)
}

async function predictedAlertActive() {
  try {
    const res = await fetch(`${API_BASE}/api/predicted-alerts`)
    const alerts = await res.json()
    return Array.isArray(alerts) && alerts.some((a) => a.metricName === METRIC)
  } catch {
    return false
  }
}

// A full ffmpeg is needed for GIF output — Playwright's bundled build records
// webm but has no gif muxer. Prefer one on PATH, then a winget install.
function resolveFfmpeg() {
  try {
    execSync('ffmpeg -version', { stdio: 'ignore' })
    return 'ffmpeg'
  } catch { /* not on PATH */ }
  const pkgs = join(process.env.LOCALAPPDATA ?? '', 'Microsoft', 'WinGet', 'Packages')
  try {
    const found = execSync(`where /r "${pkgs}" ffmpeg.exe`, { stdio: 'pipe' })
      .toString().split(/\r?\n/).find((p) => p.toLowerCase().includes('gyan.ffmpeg'))
    if (found) return found.trim()
  } catch { /* not installed */ }
  throw new Error(
    'A full ffmpeg is required for GIF output. Install it with:\n' +
    '  winget install --id Gyan.FFmpeg -e\n' +
    'The webm video was still recorded in output/.')
}

function cleanupOutput() {
  // Windows can hold a transient lock on freshly written media; retry rather
  // than delete the whole directory (which fails if anything has a handle).
  mkdirSync(OUT_DIR, { recursive: true })
  for (const name of ['pulse-demo.webm', 'pulse-demo.gif']) {
    try {
      rmSync(join(OUT_DIR, name), { force: true, maxRetries: 5, retryDelay: 300 })
    } catch { /* stale lock — a new recording overwrites it anyway */ }
  }
  for (const f of readdirSync(OUT_DIR)) {
    if (f.endsWith('.webm')) {
      try { rmSync(join(OUT_DIR, f), { force: true, maxRetries: 5, retryDelay: 300 }) } catch {}
    }
  }
}

async function main() {
  cleanupOutput()

  // Warm the ramp until the forecaster raises a predicted-breach alert, so the
  // banner and the crossing forecast line are already on screen when we record.
  console.log('Starting demo ramp and waiting for a predicted alert…')
  startRamp()
  const warmupDeadline = Date.now() + 180_000
  while (Date.now() < warmupDeadline) {
    if (await predictedAlertActive()) break
    await sleep(3000)
  }
  const banner = await predictedAlertActive()
  console.log(banner ? 'Predicted alert active — recording.' : 'No predicted alert yet — recording anyway (forecast line still shows).')

  const browser = await chromium.launch()
  const context = await browser.newContext({
    viewport: VIDEO_SIZE,
    recordVideo: { dir: OUT_DIR, size: VIDEO_SIZE },
    deviceScaleFactor: 1,
  })
  const page = await context.newPage()

  // Not 'networkidle': the dashboard holds an SSE connection open, so the
  // network never goes idle. Wait for the chart to render instead.
  await page.goto(DASHBOARD_URL, { waitUntil: 'domcontentloaded' })
  await page.selectOption('#metric-select', METRIC)
  await page.waitForSelector('.chart-card svg', { timeout: 15_000 })
  await sleep(1500)

  // Scene 1: live chart climbing, dashed forecast crossing the threshold, banner.
  await sleep(6000)

  // Scene 2: inject a spike -> red anomaly marker + a critical alert row.
  console.log('Injecting spike…')
  injectSpike(30)
  await page.waitForTimeout(9000)

  // Scene 3: reveal the forecast-accuracy scorecard at the bottom.
  await page.evaluate(() => {
    const cards = document.querySelectorAll('.alerts-card')
    cards[cards.length - 1]?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
  await sleep(7000)

  // Scene 4: back to the live chart to close on motion.
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
  await sleep(4000)

  await context.close() // flushes the video file
  await browser.close()
  stopRamp()

  // Name the video deterministically.
  const webm = readdirSync(OUT_DIR).find((f) => f.endsWith('.webm'))
  const videoPath = join(OUT_DIR, 'pulse-demo.webm')
  renameSync(join(OUT_DIR, webm), videoPath)

  // Video -> GIF with a generated palette; tuned to stay under GitHub's 10 MB.
  const ffmpeg = resolveFfmpeg()
  const gifPath = join(OUT_DIR, 'pulse-demo.gif')
  // fps/width tuned to keep the GIF comfortably under GitHub's 10 MB limit.
  const filters = 'fps=10,scale=820:-1:flags=lanczos,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=5'
  execSync(`"${ffmpeg}" -y -i "${videoPath}" -vf "${filters}" "${gifPath}"`, { stdio: 'inherit' })

  console.log(`\nVideo: ${videoPath}`)
  console.log(`GIF:   ${gifPath}`)
  if (existsSync(gifPath)) {
    const bytes = execSync(`node -e "process.stdout.write(String(require('fs').statSync(process.argv[1]).size))" "${gifPath}"`).toString()
    console.log(`GIF size: ${(Number(bytes) / 1_048_576).toFixed(2)} MB`)
  }
}

main().catch((err) => {
  console.error(err)
  try { stopRamp() } catch {}
  process.exit(1)
})
