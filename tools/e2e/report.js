// Report scenario: Target speaks real Opus next to Reporter, Reporter runs /vreport, then the
// evidence is checked through the web panel API. See README.md in this folder.
//
//   node report.js <proxy port> <minecraft version>
'use strict'
const path = require('path')
const fs = require('fs')
const http = require('http')
process.argv[4] = 'library'
const { Bot, Rcon, C2P, sleep } = require(path.join(__dirname, 'e2e.js'))

const WEB = process.env.BVC_WEB_URL || 'http://127.0.0.1:8790'
const results = []
function check (name, ok, detail = '') {
  results.push(ok)
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`)
}

/** Opus packets out of an Ogg file (one packet per page segment run). */
function oggPackets (file) {
  const data = fs.readFileSync(file)
  const packets = []
  let pos = 0
  let current = []
  while (pos < data.length) {
    const segments = data[pos + 26]
    let body = pos + 27 + segments
    for (let i = 0; i < segments; i++) {
      const lace = data[pos + 27 + i]
      current.push(data.subarray(body, body + lace))
      body += lace
      if (lace < 255) { packets.push(Buffer.concat(current)); current = [] }
    }
    pos = body
  }
  return packets.slice(2) // OpusHead, OpusTags
}

function request (method, url, body, cookie) {
  return new Promise((resolve, reject) => {
    const req = http.request(WEB + url, { method, headers: { 'Content-Type': 'application/json', ...(cookie ? { Cookie: cookie } : {}) } }, (res) => {
      const chunks = []
      res.on('data', (c) => chunks.push(c))
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }))
    })
    req.on('error', reject)
    if (body) req.write(JSON.stringify(body))
    req.end()
  })
}

/** Sends one frame per 20 ms of wall-clock time, like a client's audio clock (setTimeout alone drifts). */
async function speak (bot, packets) {
  const start = Date.now()
  let seq = 1
  for (let i = 0; i < packets.length; i++) {
    const wait = start + i * 20 - Date.now()
    if (wait > 0) await sleep(wait)
    bot.sendB(C2P.mic(seq++, packets[i]))
  }
  bot.sendB(C2P.micEnd(seq))
}

async function main () {
  const password = process.env.BVC_WEB_PASSWORD
  const reporter = new Bot('Reporter')
  const target = new Bot('Target')
  try {
    await Promise.all([reporter.hello(), target.hello()])
    reporter.sendB(C2P.selectTransport(1))
    target.sendB(C2P.selectTransport(1))
    await reporter.waitFor((p) => p.type === 'VoiceState' && p.mode === 0, 10000, 'zone')
    await target.waitFor((p) => p.type === 'VoiceState' && p.mode === 0, 10000, 'zone')
    const rcon = new Rcon(Number(process.env.BVC_RCON_PORT || 25611), process.env.BVC_RCON_PASSWORD)
    await rcon.connect()
    await rcon.command('tp Reporter 0 -60 0')
    await rcon.command('tp Target 2 -60 0')
    rcon.close()
    await sleep(1500)

    const tone440 = oggPackets(path.join(__dirname, 'tones', 'tone440.ogg'))
    const tone880 = oggPackets(path.join(__dirname, 'tones', 'tone880.ogg'))
    await speak(target, tone440)
    await sleep(2500)
    await speak(reporter, tone880)
    await sleep(500)
    const heard = reporter.packets.filter((p) => p.type === 'VoiceAudio').length
    check('Reporter heard Target', heard >= tone440.length - 2, heard + ' frames')

    reporter.bot.chat('/vreport Target insults')
    await sleep(1000)

    const login = await request('POST', '/api/login', { username: process.env.BVC_WEB_USER || 'e2eadmin', password })
    check('Admin logs in to the panel', login.status === 200)
    const cookie = (login.headers['set-cookie'] || [''])[0].split(';')[0]

    let report = null
    for (let i = 0; i < 30; i++) {
      const list = JSON.parse((await request('GET', '/api/reports?status=', null, cookie)).body)
      report = list.find((r) => r.target === 'Target' && r.reporter === 'Reporter')
      if (report && report.status === 'OPEN') break
      await sleep(1000)
    }
    check('Report filed and evidence built', report && report.status === 'OPEN', report ? '#' + report.id + ' ' + report.status : 'none')
    if (!report) return

    const detail = JSON.parse((await request('GET', '/api/reports/' + report.id, null, cookie)).body)
    const targetClip = detail.clips.find((c) => c.role === 'target')
    const reporterClip = detail.clips.find((c) => c.role === 'reporter')
    check('Evidence holds Target\'s speech, heard by Reporter', targetClip && targetClip.heard,
      targetClip ? ((targetClip.to - targetClip.from) / 1000).toFixed(2) + ' s' : 'missing')
    check('Evidence holds Reporter\'s own speech', !!reporterClip)

    const audio = await request('GET', `/api/reports/${report.id}/clip/${targetClip.index}`, null, cookie)
    check('Clip served as Ogg Opus', audio.status === 200 && audio.headers['content-type'] === 'audio/ogg' &&
      audio.body.subarray(0, 4).toString() === 'OggS', audio.body.length + ' bytes')

    const search = JSON.parse((await request('GET', '/api/recordings?player=Target', null, cookie)).body)
    check('Recording search finds Target', search.length >= 1)

    const status = await request('POST', `/api/reports/${report.id}/status`, { status: 'ACCEPTED', note: 'e2e' }, cookie)
    check('Report can be closed', status.status === 200 && JSON.parse(status.body).status === 'ACCEPTED')

    const audit = (await request('GET', '/api/audit', null, cookie)).body.toString()
    check('Audit log records viewing and closing', audit.includes('view-report') && audit.includes('report-accepted'))
  } catch (e) {
    check('report scenario', false, e.message)
  } finally {
    reporter.quit()
    target.quit()
  }
  const failed = results.filter((ok) => !ok).length
  console.log(`\n${results.length - failed}/${results.length} checks passed`)
  setTimeout(() => process.exit(failed ? 1 : 0), 300)
}

main()
setTimeout(() => { console.log('WATCHDOG'); process.exit(2) }, 120000).unref()
