// End-to-end test of BetterVoiceChat against real servers. See README.md in this folder.
//
//   node e2e.js <proxy port> <minecraft version>                        basic transport scenario
//   node e2e.js <proxy port> <minecraft version> zones <config.yml>     zone rules scenario
//   node e2e.js <proxy port> <minecraft version> switch <a,b,...>       server switch scenario
//
'use strict'
const mineflayer = require('mineflayer')
const crypto = require('crypto')
const net = require('net')

const HOST = process.env.BVC_HOST || '127.0.0.1'
const PORT = Number(process.argv[2] || 25599)
const VERSION = process.argv[3] || '1.21.4'
const PROTOCOL = 1
const RCON_PORT = Number(process.env.BVC_RCON_PORT || 25611)
const RCON_PASSWORD = process.env.BVC_RCON_PASSWORD
// Name of the proxy's server entry the bots land on.
const SERVER = process.env.BVC_SERVER || 'lobby'
// The Nether's key in config.yml: Bukkit uses the world folder, server mods the dimension id.
const NETHER = process.env.BVC_NETHER_WORLD || 'world_nether'
// A height in the Nether with room to stand (the roof, y=129, is always clear).
const NETHER_Y = process.env.BVC_NETHER_Y || '70'
if (!RCON_PASSWORD) {
    console.error('Set BVC_RCON_PASSWORD to the test server rcon.password')
    process.exit(2)
}

// ---------------------------------------------------------------- encoding
class Writer {
  constructor () { this.parts = [] }
  varint (v) {
    const out = []
    v >>>= 0
    do { let b = v & 0x7f; v >>>= 7; if (v) b |= 0x80; out.push(b) } while (v)
    this.parts.push(Buffer.from(out)); return this
  }
  byte (b) { this.parts.push(Buffer.from([b & 0xff])); return this }
  short (s) { const b = Buffer.alloc(2); b.writeUInt16BE(s); this.parts.push(b); return this }
  long (v) { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(v)); this.parts.push(b); return this }
  bytes (buf) { this.varint(buf.length); this.parts.push(Buffer.from(buf)); return this }
  string (s) { return this.bytes(Buffer.from(s, 'utf8')) }
  build () { return Buffer.concat(this.parts) }
}

class Reader {
  constructor (buf) { this.b = buf; this.i = 0 }
  varint () {
    let v = 0; let shift = 0
    for (;;) { const b = this.b[this.i++]; v |= (b & 0x7f) << shift; if (!(b & 0x80)) return v >>> 0; shift += 7 }
  }
  byte () { return this.b[this.i++] }
  bool () { return this.byte() !== 0 }
  float () { const v = this.b.readFloatBE(this.i); this.i += 4; return v }
  long () { const v = this.b.readBigInt64BE(this.i); this.i += 8; return v }
  bytes () { const n = this.varint(); const v = this.b.subarray(this.i, this.i + n); this.i += n; return Buffer.from(v) }
  string () { return this.bytes().toString('utf8') }
  uuid () { const v = this.b.subarray(this.i, this.i + 16).toString('hex'); this.i += 16; return v }
}

const frame = (payload) => Buffer.concat([new Writer().varint(payload.length).build(), payload])

const C2P = {
  hello: () => new Writer().varint(0).varint(PROTOCOL).string('0.1.0-e2e').string(VERSION).string('node-e2e').varint(3).build(),
  auth: (id, pub, mac) => new Writer().varint(1).varint(id).bytes(pub).bytes(mac).build(),
  selectTransport: (t) => new Writer().varint(2).byte(t).build(),
  mic: (seq, opus) => new Writer().varint(3).varint(seq).byte(0).bytes(opus).build(),
  micEnd: (seq) => new Writer().varint(4).varint(seq).build(),
  ping: (id) => new Writer().varint(6).long(id).build()
}

function decode (buf) {
  const r = new Reader(buf)
  const id = r.varint()
  switch (id) {
    case 0: return { type: 'Welcome', protocol: r.varint(), sessionId: r.varint(), token: r.bytes(), policy: r.byte(), host: r.string(), port: r.varint(), timeout: r.varint() }
    case 1: return { type: 'AuthResult', success: r.bool(), pub: r.bytes(), message: r.string() }
    case 2: return { type: 'TransportAck', transport: r.byte() }
    case 3: return { type: 'VoiceState', server: r.string(), room: r.string(), mode: r.byte(), activation: r.byte(), radius: r.float(), bitrate: r.varint(), ducking: r.float(), priority: r.byte(), flags: r.byte() }
    case 4: return { type: 'VoiceAudio', speaker: r.varint(), seq: r.varint(), category: r.byte(), priority: r.byte(), opus: r.bytes() }
    case 5: return { type: 'VoiceEnd', speaker: r.varint(), seq: r.varint() }
    case 6: { const n = r.varint(); const entries = []; for (let i = 0; i < n; i++) entries.push({ id: r.varint(), uuid: r.uuid(), name: r.string() }); return { type: 'PlayerInfo', entries } }
    case 7: { const n = r.varint(); const ids = []; for (let i = 0; i < n; i++) ids.push(r.varint()); return { type: 'PlayerRemove', ids } }
    case 8: return { type: 'MuteStatus', muted: r.bool(), kind: r.byte(), scope: r.string(), reason: r.string(), expires: r.long() }
    case 9: return { type: 'Pong', id: r.long() }
    case 10: return { type: 'Notice', message: r.string() }
    default: return { type: 'Unknown', id }
  }
}

// ---------------------------------------------------------------- bot
class Bot {
  constructor (name) {
    this.name = name
    this.packets = []
    this.waiters = []
    Bot.all = (Bot.all || []).concat([this])
    // mineflayer behaves like a vanilla client (teleport confirms, chunk acks, settings...);
    // BVC traffic goes through its underlying protocol client.
    this.bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, version: VERSION, auth: 'offline' })
    this.client = this.bot._client
    this.client.registerChannel('bvc:voice', null, false)
    this.client.on('bvc:voice', (data) => this.receive(decode(data), 'B'))
    this.bot.on('error', (e) => console.log(name, 'error', e.message))
    this.bot.on('kicked', (reason) => console.log(name, 'kicked', JSON.stringify(reason)))
    this.joined = new Promise((resolve, reject) => {
      this.bot.once('spawn', resolve)
      setTimeout(() => reject(new Error(name + ': never finished joining')), 20000)
    })
  }

  receive (packet, via) {
    packet.via = via
    this.packets.push(packet)
    for (const w of this.waiters.slice()) {
      if (w.match(packet)) { this.waiters.splice(this.waiters.indexOf(w), 1); w.resolve(packet) }
    }
  }

  waitFor (match, ms = 8000, what = 'packet') {
    const found = this.packets.find(match)
    if (found) return Promise.resolve(found)
    return new Promise((resolve, reject) => {
      const w = { match, resolve }
      this.waiters.push(w)
      setTimeout(() => { if (this.waiters.includes(w)) reject(new Error(`${this.name}: timed out waiting for ${what}`)) }, ms)
    })
  }

  sendB (payload) { this.client.write('custom_payload', { channel: 'bvc:voice', data: payload }) }

  async hello () {
    await this.joined
    this.client.writeChannel('minecraft:register', ['bvc:voice'])
    this.sendB(C2P.hello())
    this.welcome = await this.waitFor((p) => p.type === 'Welcome', 8000, 'Welcome')
    return this.welcome
  }

  // ---- transport A
  connectStream () {
    return new Promise((resolve, reject) => {
      const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
      const pub = publicKey.export({ type: 'spki', format: 'der' })
      const token = this.welcome.token
      const mac = crypto.createHmac('sha256', token).update(Buffer.concat([Buffer.from('bvc-auth-v1'), pub])).digest()
      const handshake = new Writer().varint(0).varint(769).string(HOST + '\0BVC\0' + PROTOCOL).short(PORT).varint(2).build()
      const sock = net.connect(PORT, HOST)
      this.sock = sock
      let buffer = Buffer.alloc(0)
      let decipher = null
      sock.on('connect', () => sock.write(Buffer.concat([frame(handshake), frame(C2P.auth(this.welcome.sessionId, pub, mac))])))
      sock.on('error', reject)
      sock.on('data', (chunk) => {
        buffer = Buffer.concat([buffer, decipher ? decipher.update(chunk) : chunk])
        for (;;) {
          const r = new Reader(buffer)
          let length
          try { length = r.varint() } catch (e) { return }
          if (r.i > buffer.length || buffer.length < r.i + length) return
          const body = buffer.subarray(r.i, r.i + length)
          const rest = buffer.subarray(r.i + length)
          const packet = decode(body)
          if (!decipher) {
            // First frame is the plaintext AuthResult; everything after it is encrypted.
            if (packet.type !== 'AuthResult' || !packet.success) { reject(new Error('auth failed: ' + JSON.stringify(packet))); return }
            const serverKey = crypto.createPublicKey({ key: packet.pub, format: 'der', type: 'spki' })
            const secret = crypto.diffieHellman({ privateKey, publicKey: serverKey })
            const prk = crypto.createHmac('sha256', token).update(secret).digest()
            const expand = (info) => crypto.createHmac('sha256', prk).update(Buffer.concat([Buffer.from(info), Buffer.from([1])])).digest().subarray(0, 16)
            this.cipher = crypto.createCipheriv('aes-128-ctr', expand('bvc c2s key'), expand('bvc c2s iv'))
            decipher = crypto.createDecipheriv('aes-128-ctr', expand('bvc s2c key'), expand('bvc s2c iv'))
            buffer = decipher.update(rest)
            resolve(packet)
            continue
          }
          buffer = Buffer.from(rest)
          this.receive(packet, 'A')
        }
      })
    })
  }

  sendA (payload) { this.sock.write(this.cipher.update(frame(payload))) }

  quit () {
    if (this.sock) this.sock.destroy()
    this.bot.quit()
  }
}

// ---------------------------------------------------------------- test
const results = []
function check (name, ok, detail = '') {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`)
}

async function main () {
  const a = new Bot('BotA')
  const b = new Bot('BotB')
  try {
    const [wa, wb] = await Promise.all([a.hello(), b.hello()])
    check('Welcome for both bots', wa.sessionId > 0 && wb.sessionId > 0 && wa.token.length === 32, `ids ${wa.sessionId}/${wb.sessionId}`)

    // BotA: transport B
    a.sendB(C2P.selectTransport(1))
    const ackA = await a.waitFor((p) => p.type === 'TransportAck', 5000, 'TransportAck')
    check('BotA connected over plugin messages (B)', ackA.transport === 1 && ackA.via === 'B')

    // BotB: transport A on the same port
    const auth = await b.connectStream()
    check('BotB authenticated on the stream (A)', auth.success)
    const ackB = await b.waitFor((p) => p.type === 'TransportAck', 5000, 'TransportAck')
    check('BotB connected over the encrypted stream (A)', ackB.transport === 0 && ackB.via === 'A')

    // The sub-server reports the zone; wait until both see proximity mode.
    const stateA = await a.waitFor((p) => p.type === 'VoiceState' && p.mode === 0, 8000, 'VoiceState PROXIMITY')
    const stateB = await b.waitFor((p) => p.type === 'VoiceState' && p.mode === 0, 8000, 'VoiceState PROXIMITY')
    check('Zone from the sub-server reached both clients', stateA.server === SERVER && stateB.radius === 48,
      `server=${stateA.server} mode=PROXIMITY radius=${stateB.radius} bitrate=${stateA.bitrate}`)
    const mutedA = await a.waitFor((p) => p.type === 'MuteStatus', 3000, 'MuteStatus')
    check('Mute status pushed (not muted)', mutedA.muted === false)

    // Paper remembers positions between runs: put both bots side by side first.
    const rcon = new Rcon(RCON_PORT, RCON_PASSWORD)
    await rcon.connect()
    await rcon.command('tp BotA 0 -60 0')
    await rcon.command('tp BotB 2 -60 0')
    rcon.close()
    // Give the planner a few passes to publish neighbour tables.
    await new Promise((r) => setTimeout(r, 1500))

    // A speaks over B, B must hear over A.
    const opusA = Buffer.from([0xf8, 0xff, 0xfe, 1, 2, 3])
    for (let i = 1; i <= 3; i++) { a.sendB(C2P.mic(i, opusA)); await new Promise((r) => setTimeout(r, 20)) }
    const infoAtB = await b.waitFor((p) => p.type === 'PlayerInfo', 4000, 'PlayerInfo')
    const audioAtB = await b.waitFor((p) => p.type === 'VoiceAudio', 4000, 'VoiceAudio')
    check('BotB learns who is speaking', infoAtB.entries[0].name === 'BotA' && infoAtB.entries[0].id === wa.sessionId)
    check('BotB hears BotA (B -> proxy -> A)', audioAtB.speaker === wa.sessionId && audioAtB.category === 1 &&
      audioAtB.opus.equals(opusA), `category=${audioAtB.category === 1 ? 'PROXIMITY' : audioAtB.category} via ${audioAtB.via}`)
    const countAtB = b.packets.filter((p) => p.type === 'VoiceAudio').length
    check('Every frame arrived in order', countAtB === 3 &&
      b.packets.filter((p) => p.type === 'VoiceAudio').map((p) => p.seq).join(',') === '1,2,3')
    a.sendB(C2P.micEnd(3))
    await b.waitFor((p) => p.type === 'VoiceEnd', 3000, 'VoiceEnd')
    check('End of speech forwarded', true)

    // B speaks over A, A must hear over B.
    const opusB = Buffer.from([0xf8, 9, 9, 9])
    b.sendA(C2P.mic(1, opusB))
    const audioAtA = await a.waitFor((p) => p.type === 'VoiceAudio', 4000, 'VoiceAudio')
    check('BotA hears BotB (A -> proxy -> B)', audioAtA.speaker === wb.sessionId && audioAtA.opus.equals(opusB), `via ${audioAtA.via}`)

    // Ping over the stream.
    b.sendA(C2P.ping(424242))
    const pong = await b.waitFor((p) => p.type === 'Pong', 3000, 'Pong')
    check('Ping/pong over the stream', pong.id === 424242n)

    // A speaker never hears themself.
    check('No echo to the speaker', !a.packets.some((p) => p.type === 'VoiceAudio' && p.speaker === wa.sessionId))
  } catch (e) {
    check('scenario', false, e.message)
  } finally {
    a.quit()
    b.quit()
  }
  const failed = results.filter((r) => !r.ok).length
  console.log(`\n${results.length - failed}/${results.length} checks passed`)
  setTimeout(() => process.exit(failed ? 1 : 0), 300)
}

// ---------------------------------------------------------------- zones scenario
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const audioFrom = (bot, speaker) => bot.packets.filter((p) => p.type === 'VoiceAudio' && p.speaker === speaker)

/** Minimal RCON client: runs console commands on the Paper server. */
class Rcon {
  constructor (port, password) { this.port = port; this.password = password; this.id = 1; this.pending = new Map(); this.buffer = Buffer.alloc(0) }
  connect () {
    return new Promise((resolve, reject) => {
      this.sock = net.connect(this.port, HOST, () => this.send(3, this.password).then(resolve, reject))
      this.sock.on('error', reject)
      this.sock.on('data', (chunk) => {
        this.buffer = Buffer.concat([this.buffer, chunk])
        while (this.buffer.length >= 4) {
          const length = this.buffer.readInt32LE(0)
          if (this.buffer.length < 4 + length) return
          const id = this.buffer.readInt32LE(4)
          const body = this.buffer.subarray(12, 4 + length - 2).toString('utf8')
          this.buffer = this.buffer.subarray(4 + length)
          const waiter = this.pending.get(id)
          if (waiter) { this.pending.delete(id); id === -1 ? waiter.reject(new Error('RCON auth failed')) : waiter.resolve(body) }
          else if (id === -1) { for (const w of this.pending.values()) w.reject(new Error('RCON auth failed')) }
        }
      })
    })
  }
  send (type, body) {
    const id = this.id++
    const payload = Buffer.from(body, 'utf8')
    const packet = Buffer.alloc(14 + payload.length)
    packet.writeInt32LE(10 + payload.length, 0)
    packet.writeInt32LE(id, 4)
    packet.writeInt32LE(type, 8)
    payload.copy(packet, 12)
    return new Promise((resolve, reject) => { this.pending.set(id, { resolve, reject }); this.sock.write(packet) })
  }
  command (text) { return this.send(2, text) }
  close () { this.sock.destroy() }
}

/** Speaks a few frames and reports what the listener received from that speaker. */
async function speakAndListen (speaker, listener, speakerId, waitMs = 1000) {
  const before = audioFrom(listener, speakerId).length
  for (let i = 0; i < 3; i++) { speaker.sendB(C2P.mic(Date.now() & 0xffff, Buffer.from([0xf8, i]))); await sleep(20) }
  await sleep(waitMs)
  return audioFrom(listener, speakerId).slice(before)
}

async function zones (configPath) {
  const fs = require('fs')
  const original = fs.readFileSync(configPath, 'utf8')
  const setMode = (mode) => fs.writeFileSync(configPath, original.replace(/^ {2}mode: \w+/m, '  mode: ' + mode))
  const rcon = new Rcon(RCON_PORT, RCON_PASSWORD)
  await rcon.connect()
  const run = async (command) => { const out = await rcon.command(command); console.log('  > ' + command + (out ? '  => ' + out.replace(/§./g, '').trim().slice(0, 120) : '')) }
  const a = new Bot('BotA')
  const b = new Bot('BotB')
  try {
    const [wa] = await Promise.all([a.hello(), b.hello()])
    a.sendB(C2P.selectTransport(1))
    b.sendB(C2P.selectTransport(1))
    await a.waitFor((p) => p.type === 'VoiceState' && p.mode === 0, 8000, 'PROXIMITY state')
    await b.waitFor((p) => p.type === 'VoiceState' && p.mode === 0, 8000, 'PROXIMITY state')
    await run('tp BotA 0 -60 0')
    await run('tp BotB 3 -60 0')
    await sleep(1500)

    let heard = await speakAndListen(a, b, wa.sessionId)
    check('Proximity: 3 blocks apart -> heard', heard.length === 3 && heard[0].category === 1)

    await run('tp BotB 300 -60 300')
    await sleep(1000)
    heard = await speakAndListen(a, b, wa.sessionId)
    check('Proximity: 300 blocks apart -> silent', heard.length === 0)

    await run('tp BotB 40 -60 0')
    await sleep(1000)
    heard = await speakAndListen(a, b, wa.sessionId)
    check('Proximity: 40 blocks (radius 48) -> heard', heard.length === 3)

    await run('tp BotB 0 -60 60')
    await sleep(1000)
    heard = await speakAndListen(a, b, wa.sessionId)
    check('Proximity: 60 blocks (radius 48) -> silent', heard.length === 0)

    setMode('TEAM')
    await run('bvcs reload')
    const teamState = await b.waitFor((p) => p.type === 'VoiceState' && p.mode === 1, 5000, 'TEAM state')
    check('Config reload switches clients to team mode', teamState.mode === 1)
    await run('tp BotB 1 -60 0')
    await sleep(1000)
    heard = await speakAndListen(a, b, wa.sessionId)
    check('Team mode, no teams -> silent even side by side', heard.length === 0)

    await run('team add red')
    await run('team join red BotA')
    await run('team join red BotB')
    await sleep(1000)
    heard = await speakAndListen(a, b, wa.sessionId)
    check('Team mode, same scoreboard team -> heard as group', heard.length === 3 && heard[0].category === 0)

    await run('tp BotB 5000 -60 5000')
    await sleep(1000)
    heard = await speakAndListen(a, b, wa.sessionId)
    check('Team mode: teammates hear each other at any distance', heard.length === 3)

    await run('team leave BotB')
    await sleep(1000)
    heard = await speakAndListen(a, b, wa.sessionId)
    check('Team mode: leaving the team -> silent', heard.length === 0)

    setMode('FREE')
    await run('bvcs reload')
    await b.waitFor((p) => p.type === 'VoiceState' && p.mode === 2, 5000, 'FREE state')
    await sleep(500)
    heard = await speakAndListen(a, b, wa.sessionId)
    check('Free mode: 5000 blocks apart, no team -> heard', heard.length === 3 && heard[0].category === 0)

    // ---- spectators
    setMode('PROXIMITY')
    fs.writeFileSync(configPath, original.replace(/^spectators: \w+/m, 'spectators: SEPARATE'))
    await run('bvcs reload')
    await run('tp BotA 0 -60 0')
    await run('tp BotB 2 -60 0')
    await run('gamemode spectator BotB')
    await sleep(1200)
    const wb = b.welcome
    heard = await speakAndListen(a, b, wa.sessionId)
    check('Spectators SEPARATE: the living are not heard by spectators', heard.length === 0)
    heard = await speakAndListen(b, a, wb.sessionId)
    check('Spectators SEPARATE: spectators are not heard by the living', heard.length === 0)

    fs.writeFileSync(configPath, original.replace(/^spectators: \w+/m, 'spectators: LISTEN_ONLY'))
    await run('bvcs reload')
    await sleep(1200)
    heard = await speakAndListen(a, b, wa.sessionId)
    check('Spectators LISTEN_ONLY: spectator hears the living', heard.length === 3)
    heard = await speakAndListen(b, a, wb.sessionId)
    check('Spectators LISTEN_ONLY: spectator cannot speak', heard.length === 0)
    await run('gamemode survival BotB')
    await sleep(1200)
    heard = await speakAndListen(b, a, wb.sessionId)
    check('Back to survival: speaking works again', heard.length === 3)

    // ---- per-world zone: the Nether is free mode, the overworld stays proximity
    fs.writeFileSync(configPath, original.replace(/^worlds: \{\}/m, 'worlds:\n  "' + NETHER + '":\n    mode: FREE'))
    await run('bvcs reload')
    await run('execute in minecraft:the_nether run tp BotB 0 ' + NETHER_Y + ' 0')
    // The first visit generates Nether chunks and can stall the server: wait until BotB is
    // planned in the Nether zone instead of sleeping a fixed time.
    await b.waitFor((p) => p.type === 'VoiceState' && p.room === NETHER, 15000, 'Nether VoiceState')
    await sleep(500)
    heard = await speakAndListen(a, b, wa.sessionId)
    check('World zones: overworld player not heard in the Nether', heard.length === 0, 'heard ' + heard.length)
    await run('execute in minecraft:the_nether run tp BotA 3000 ' + NETHER_Y + ' 3000')
    await sleep(1200)
    heard = await speakAndListen(a, b, wa.sessionId)
    check('World zones: free mode inside the Nether, 3000 blocks apart -> heard', heard.length === 3 && heard[0].category === 0)
    const netherState = b.packets.filter((p) => p.type === 'VoiceState').pop()
    check('World zones: client sees the Nether zone', netherState.mode === 2 && netherState.room === NETHER,
      'room=' + netherState.room)
    await run('tp BotA 0 -60 0')
    await run('tp BotB 2 -60 0')
  } catch (e) {
    check('zones scenario', false, e.message)
  } finally {
    fs.writeFileSync(configPath, original)
    try { await run('team remove red'); await run('bvcs reload') } catch (e) { /* best effort */ }
    rcon.close()
    a.quit()
    b.quit()
  }
  const failed = results.filter((r) => !r.ok).length
  console.log(`
${results.length - failed}/${results.length} checks passed`)
  setTimeout(() => process.exit(failed ? 1 : 0), 300)
}

// ---------------------------------------------------------------- server switch scenario
// One bot hops between sub-servers with /server; the zone it receives must come from whichever
// sub-server it is on (e.g. a Bukkit one and the server mods side by side).
async function serverSwitch (route) {
  const bot = new Bot('BotS')
  try {
    await bot.hello()
    bot.sendB(C2P.selectTransport(1))
    for (const server of route) {
      if (server !== route[0]) bot.bot.chat('/server ' + server)
      const state = await bot.waitFor((p) => p.type === 'VoiceState' && p.server === server && p.mode === 0,
        15000, 'zone from ' + server).catch(() => null)
      check('Zone from ' + server, state !== null && state.radius === 48, state ? 'radius=' + state.radius : 'none')
      await sleep(1500)
    }
  } catch (e) {
    check('switch scenario', false, e.message)
  } finally {
    bot.quit()
  }
  const failed = results.filter((r) => !r.ok).length
  console.log(`\n${results.length - failed}/${results.length} checks passed`)
  setTimeout(() => process.exit(failed ? 1 : 0), 300)
}

// Other scenarios (report.js) load this file as a library.
module.exports = { Bot, Rcon, C2P, decode, sleep }
const LIBRARY = process.argv[4] === 'library'

if (LIBRARY) {
  // nothing to run
} else if (process.argv[4] === 'zones') {
  zones(process.argv[5])
} else if (process.argv[4] === 'switch') {
  serverSwitch((process.argv[5] || 'lobby').split(','))
} else {
  main()
}

// Watchdog: never hang silently.
if (!LIBRARY) setTimeout(() => {
  console.log('WATCHDOG: still running after 100s')
  for (const bot of Bot.all || []) {
    console.log(bot.name, 'state', bot.client.state, 'packets', bot.packets.map((p) => p.type).join(','))
  }
  process.exit(2)
}, 100000).unref()
