'use strict'
// BetterVoiceChat web panel. No build step, no dependencies.

const $ = (selector, root = document) => root.querySelector(selector)
let me = null

// ------------------------------------------------------------------ helpers

async function api (path, options = {}) {
  const init = { method: options.method || 'GET', headers: {}, credentials: 'same-origin' }
  if (options.body !== undefined) {
    init.method = options.method || 'POST'
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(options.body)
  }
  const response = await fetch('/api/' + path, init)
  if (response.status === 401 && path !== 'login') {
    me = null
    showLogin()
    throw new Error('请先登录')
  }
  const data = response.headers.get('Content-Type')?.includes('json') ? await response.json() : null
  if (!response.ok) throw new Error((data && data.error) || response.statusText)
  return data
}

function el (tag, attrs = {}, ...children) {
  const node = document.createElement(tag)
  for (const [key, value] of Object.entries(attrs)) {
    if (key === 'class') node.className = value
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value)
    else if (value !== undefined && value !== null) node.setAttribute(key, value)
  }
  for (const child of children) {
    if (child !== null && child !== undefined) node.append(child instanceof Node ? child : String(child))
  }
  return node
}

const pad = (n) => String(n).padStart(2, '0')
function time (ms) {
  if (!ms) return '-'
  const d = new Date(ms)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
function clock (seconds) {
  const s = Math.max(0, Math.floor(seconds))
  return `${pad(Math.floor(s / 60))}:${pad(s % 60)}`
}
function localInput (ms) {
  const d = new Date(ms)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
const STATUS = { OPEN: '待处理', COLLECTING: '收集中', ACCEPTED: '成立', REJECTED: '不成立' }

function mount (templateId) {
  const view = $('#view')
  view.replaceChildren($('#' + templateId).content.cloneNode(true))
  return view
}

function message (node, text, error = false) {
  node.textContent = text
  node.className = error ? 'error' : 'hint'
}

// ------------------------------------------------------------------ login + routing

function showLogin () {
  stopPlayback()
  $('#nav').hidden = true
  $('#whoami').textContent = ''
  $('#view').replaceChildren()
  $('#login').hidden = false
  $('#login-expired').hidden = location.hash !== '#expired'
}

$('#login-form').addEventListener('submit', async (event) => {
  event.preventDefault()
  const form = new FormData(event.target)
  try {
    await api('login', { body: { username: form.get('username'), password: form.get('password') } })
    location.hash = '#reports'
    await boot()
  } catch (e) {
    $('#login-error').textContent = e.message
  }
})

async function boot () {
  try {
    me = await api('me')
  } catch (e) {
    return
  }
  $('#login').hidden = true
  $('#nav').hidden = false
  $('#whoami').textContent = me.username
  for (const link of document.querySelectorAll('#nav a[data-perm]')) {
    link.hidden = !me[link.dataset.perm]
  }
  route()
}

window.addEventListener('hashchange', () => { if (me) route() })

function route () {
  stopPlayback()
  const [page, arg] = location.hash.replace(/^#/, '').split('/')
  for (const link of document.querySelectorAll('#nav a')) {
    link.classList.toggle('active', link.getAttribute('href') === '#' + page)
  }
  const pages = { reports, report: () => report(arg), recordings, mutes, users, audit, account }
  const target = pages[page] || (me.canReview ? reports : account)
  target().catch((e) => { $('#view').replaceChildren(el('p', { class: 'error' }, e.message)) })
}

// ------------------------------------------------------------------ reports

async function reports () {
  const view = mount('tpl-reports')
  const select = $('#report-status', view)
  select.value = sessionStorage.getItem('reportStatus') ?? 'OPEN'
  async function load () {
    sessionStorage.setItem('reportStatus', select.value)
    const rows = await api('reports?status=' + encodeURIComponent(select.value))
    const body = $('#report-rows', view)
    body.replaceChildren(...rows.map((r) => el('tr', { class: 'link', onclick: () => { location.hash = '#report/' + r.id } },
      el('td', {}, r.id), el('td', {}, time(r.createdAt)), el('td', {}, r.server), el('td', {}, r.reporter),
      el('td', {}, r.target), el('td', {}, r.reason || '-'),
      el('td', {}, el('span', { class: 'status ' + r.status }, STATUS[r.status])))))
    if (!rows.length) body.append(el('tr', {}, el('td', { colspan: 7, class: 'hint' }, '没有举报')))
  }
  select.addEventListener('change', load)
  $('#report-refresh', view).addEventListener('click', load)
  await load()
}

async function report (id) {
  const view = mount('tpl-report')
  const r = await api('reports/' + id)
  $('#r-title', view).textContent = `举报 #${r.id}：${r.reporter} → ${r.target}`
  const facts = [['状态', STATUS[r.status]], ['子服', r.server], ['原因', r.reason || '-'], ['举报时间', time(r.createdAt)],
    ['证据范围', `${time(r.from)} 至 ${time(r.to)}`]]
  if (r.handledBy) facts.push(['处理人', `${r.handledBy}（${time(r.handledAt)}）`])
  if (r.note) facts.push(['备注', r.note])
  $('#r-facts', view).replaceChildren(...facts.flatMap(([k, v]) => [el('dt', {}, k), el('dd', {}, v)]))

  setupPlayer(view, r)

  const msg = $('#r-msg', view)
  const setStatus = async (status) => {
    try {
      await api(`reports/${r.id}/status`, { body: { status, note: $('#r-note', view).value } })
      message(msg, '已保存')
      setTimeout(() => report(id), 500)
    } catch (e) { message(msg, e.message, true) }
  }
  $('#r-accept', view).addEventListener('click', () => setStatus('ACCEPTED'))
  $('#r-reject', view).addEventListener('click', () => setStatus('REJECTED'))
  $('#r-reopen', view).addEventListener('click', () => setStatus('OPEN'))
  const collecting = r.status === 'COLLECTING'
  for (const b of ['#r-accept', '#r-reject', '#r-reopen']) $(b, view).disabled = collecting
  if (collecting) message(msg, '证据还在收集中（举报后会继续录一段时间），稍后刷新。')

  if (me.canMute) {
    $('#r-mute', view).hidden = false
    const scope = $('#r-mute-scope', view)
    scope.append(el('option', { value: '*' }, '全服'), el('option', { value: r.server }, '子服 ' + r.server))
    $('#r-mute-reason', view).value = r.reason ? '举报 #' + r.id + '：' + r.reason : '举报 #' + r.id
    $('#r-mute-go', view).addEventListener('click', async () => {
      try {
        await api('mutes', { body: { player: r.target, scope: scope.value, minutes: Number($('#r-mute-minutes', view).value), reason: $('#r-mute-reason', view).value } })
        message(msg, `已禁麦 ${r.target}`)
      } catch (e) { message(msg, e.message, true) }
    })
  }
}

// ------------------------------------------------------------------ multi-track player

let playback = null

function stopPlayback () {
  if (playback) {
    playback.stop()
    playback = null
  }
}

function setupPlayer (view, r) {
  const clips = r.clips
  const timeline = $('#p-timeline', view)
  if (!clips.length) {
    timeline.replaceChildren(el('p', { class: 'hint', style: 'padding:8px' }, r.status === 'COLLECTING' ? '证据收集中…' : '这段时间没有录到语音。'))
    $('#p-play', view).disabled = true
    return
  }
  const start = Math.min(r.from, ...clips.map((c) => c.from))
  const end = Math.max(r.to, ...clips.map((c) => c.to))
  const span = end - start
  // one track per speaker: target, reporter, then everyone else
  const order = { target: 0, reporter: 1, other: 2 }
  const speakers = [...new Map(clips.map((c) => [c.speaker, c])).values()]
    .sort((a, b) => order[a.role] - order[b.role] || a.name.localeCompare(b.name))
  const state = { muted: new Set(), solo: null }
  const lanes = []
  for (const s of speakers) {
    const lane = el('div', { class: 'lane' })
    const row = el('div', { class: 'track' })
    const mute = el('button', { class: 'secondary', title: '静音' }, '静')
    const solo = el('button', { class: 'secondary', title: '独听' }, '独')
    const roleText = s.role === 'target' ? '（被举报）' : s.role === 'reporter' ? '（举报人）' : ''
    row.append(el('div', { class: 'label' }, mute, solo, el('span', { title: s.name }, s.name + roleText)), lane)
    mute.addEventListener('click', () => {
      state.muted.has(s.speaker) ? state.muted.delete(s.speaker) : state.muted.add(s.speaker)
      mute.classList.toggle('on', state.muted.has(s.speaker))
      refresh()
    })
    solo.addEventListener('click', () => {
      state.solo = state.solo === s.speaker ? null : s.speaker
      refresh()
    })
    lanes.push({ speaker: s.speaker, row, solo, lane })
    timeline.append(row)
    for (const c of clips.filter((c) => c.speaker === s.speaker)) {
      lane.append(el('div', {
        class: `clip ${c.role}${c.role === 'other' || c.heard ? '' : ' unheard'}`,
        style: `left:${(c.from - start) / span * 100}%;width:${Math.max(0.15, (c.to - c.from) / span * 100)}%`,
        title: `${c.name} ${time(c.from)} (${((c.to - c.from) / 1000).toFixed(1)} 秒)${c.heard ? '，举报人听到了' : ''}`
      }))
    }
    lane.addEventListener('click', (event) => {
      const rect = lane.getBoundingClientRect()
      seek(start + (event.clientX - rect.left) / rect.width * span)
    })
  }
  const cursors = lanes.map((l) => { const c = el('div', { class: 'cursor', style: 'left:0' }); l.lane.append(c); return c })
  const heardOnly = $('#p-heard-only', view)
  const timeLabel = $('#p-time', view)

  function audible (clip) {
    if (state.solo) return clip.speaker === state.solo
    if (state.muted.has(clip.speaker)) return false
    if (heardOnly.checked && !clip.heard) return false
    return true
  }
  function refresh () {
    for (const l of lanes) {
      l.solo.classList.toggle('on', state.solo === l.speaker)
      l.row.classList.toggle('muted', state.solo ? state.solo !== l.speaker : state.muted.has(l.speaker))
    }
    if (playback) seek(playback.position())
  }
  heardOnly.addEventListener('change', refresh)

  const buffers = new Map()
  let context = null
  async function buffer (clip) {
    if (!buffers.has(clip.index)) {
      buffers.set(clip.index, fetch(`/api/reports/${r.id}/clip/${clip.index}`, { credentials: 'same-origin' })
        .then((res) => { if (!res.ok) throw new Error('无法加载音频'); return res.arrayBuffer() })
        .then((data) => context.decodeAudioData(data)))
    }
    return buffers.get(clip.index)
  }

  let cursorAt = start
  function draw (at) {
    cursorAt = at
    const left = (at - start) / span * 100 + '%'
    for (const c of cursors) c.style.left = left
    timeLabel.textContent = `${clock((at - start) / 1000)} / ${clock(span / 1000)}  ${time(at)}`
  }
  draw(start)

  async function seek (at) {
    const wasPlaying = !!playback
    stopPlayback()
    draw(Math.max(start, Math.min(end, at)))
    if (wasPlaying) await play(cursorAt)
  }

  async function play (from) {
    stopPlayback()
    context = context || new AudioContext()
    await context.resume()
    const sources = []
    const t0 = context.currentTime + 0.15
    const session = {
      stopped: false,
      stop () {
        this.stopped = true
        for (const s of sources) { try { s.stop() } catch (e) { /* not started */ } }
        clearInterval(this.timer)
      },
      position () { return Math.min(end, from + (context.currentTime - t0) * 1000) }
    }
    playback = session
    // A timer, not requestAnimationFrame: the cursor keeps up even in a background tab.
    session.timer = setInterval(() => {
      if (session.stopped) return
      const at = session.position()
      draw(at)
      if (at >= end) stopPlayback()
    }, 100)
    for (const clip of clips) {
      if (clip.to < from || !audible(clip)) continue
      buffer(clip).then((audio) => {
        if (session.stopped) return
        const source = context.createBufferSource()
        source.buffer = audio
        source.connect(context.destination)
        const offset = Math.max(0, (from - clip.from) / 1000)
        const when = t0 + Math.max(0, (clip.from - from) / 1000)
        if (offset < audio.duration) {
          source.start(Math.max(when, context.currentTime), offset)
          sources.push(source)
        }
      }).catch((e) => message($('#r-msg', view), e.message, true))
    }
  }

  $('#p-play', view).addEventListener('click', () => play(cursorAt >= end ? start : cursorAt))
  $('#p-stop', view).addEventListener('click', () => { const at = playback ? playback.position() : cursorAt; stopPlayback(); draw(at) })
  // Jump to the first thing the reported player said.
  const first = clips.find((c) => c.role === 'target')
  if (first) draw(Math.max(start, first.from - 2000))
}

// ------------------------------------------------------------------ recordings

async function recordings () {
  const view = mount('tpl-recordings')
  const form = $('#rec-form', view)
  const now = Date.now()
  form.from.value = localInput(now - 3600_000)
  form.to.value = localInput(now)
  let audio = null
  form.addEventListener('submit', async (event) => {
    event.preventDefault()
    const query = new URLSearchParams({
      player: form.player.value, server: form.server.value,
      from: new Date(form.from.value).getTime(), to: new Date(form.to.value).getTime()
    })
    const body = $('#rec-rows', view)
    try {
      const rows = await api('recordings?' + query)
      body.replaceChildren(...rows.map((u) => el('tr', {},
        el('td', {}, time(u.from)), el('td', {}, u.server), el('td', {}, u.name),
        el('td', {}, ((u.to - u.from) / 1000).toFixed(1) + ' 秒'),
        el('td', {}, el('button', {
          class: 'secondary',
          onclick: () => {
            if (audio) audio.pause()
            audio = new Audio(`/api/recordings/${u.id}/audio`)
            audio.play().catch((e) => alert('无法播放：' + e.message))
          }
        }, '▶')))))
      if (!rows.length) body.append(el('tr', {}, el('td', { colspan: 5, class: 'hint' }, '没有找到录音（可能已被滚动删除）')))
    } catch (e) {
      body.replaceChildren(el('tr', {}, el('td', { colspan: 5, class: 'error' }, e.message)))
    }
  })
}

// ------------------------------------------------------------------ mutes

async function mutes () {
  const view = mount('tpl-mutes')
  const msg = $('#mute-msg', view)
  $('#mute-form-box', view).hidden = !me.canMute
  const describe = (m) => `${m.scope === '*' ? '全服' : '子服 ' + m.scope}，${m.expiresAt ? '到 ' + time(m.expiresAt) : '永久'}，原因：${m.reason || '-'}，操作人：${m.operator}，${time(m.createdAt)}`
  async function show (player) {
    const data = await api('mutes?player=' + encodeURIComponent(player))
    const box = $('#mute-result', view)
    box.replaceChildren(el('h3', {}, data.player + ' 当前的禁麦'))
    if (!data.active.length) box.append(el('p', { class: 'hint' }, '没有生效中的禁麦'))
    for (const m of data.active) {
      const row = el('p', {}, describe(m), ' ')
      if (me.canMute) {
        row.append(el('button', {
          class: 'secondary',
          onclick: async () => {
            try { await api('mutes/unmute', { body: { player: data.player, scope: m.scope } }); message(msg, '已解除'); show(data.player) } catch (e) { message(msg, e.message, true) }
          }
        }, '解除'))
      }
      box.append(row)
    }
    box.append(el('h3', {}, '历史记录'))
    box.append(el('ul', {}, ...data.history.map((m) => el('li', {}, describe(m)))))
  }
  $('#mute-search', view).addEventListener('submit', async (event) => {
    event.preventDefault()
    try { await show(event.target.player.value.trim()) } catch (e) { message(msg, e.message, true) }
  })
  $('#mute-form', view).addEventListener('submit', async (event) => {
    event.preventDefault()
    const f = event.target
    try {
      await api('mutes', { body: { player: f.player.value.trim(), scope: f.scope.value.trim(), minutes: Number(f.minutes.value), reason: f.reason.value } })
      message(msg, '已禁麦 ' + f.player.value)
      await show(f.player.value.trim())
    } catch (e) { message(msg, e.message, true) }
  })
}

// ------------------------------------------------------------------ users, audit, account

async function users () {
  const view = mount('tpl-users')
  const rows = await api('users')
  const perms = ['REVIEW', 'MUTE', 'ADMIN']
  $('#user-rows', view).replaceChildren(...rows.map((u) => {
    const boxes = perms.map((p) => el('input', { type: 'checkbox', ...(u.permissions.includes(p) ? { checked: '' } : {}) }))
    const save = async () => {
      await api(`users/${encodeURIComponent(u.username)}/permissions`, { body: { permissions: perms.filter((p, i) => boxes[i].checked) } })
    }
    boxes.forEach((b) => b.addEventListener('change', () => save().catch((e) => alert(e.message))))
    return el('tr', {}, el('td', {}, u.username), ...boxes.map((b) => el('td', {}, b)), el('td', {}, u.hasPassword ? '已设置' : '-'),
      el('td', {}, u.username === me.username ? '' : el('button', {
        class: 'secondary',
        onclick: async () => {
          if (confirm('删除账号 ' + u.username + '？对方仍可通过游戏里的 /bvc web 重新获得账号。')) {
            await api(`users/${encodeURIComponent(u.username)}/delete`, { body: {} })
            users()
          }
        }
      }, '删除')))
  }))
}

async function audit () {
  const view = mount('tpl-audit')
  let offset = 0
  const body = $('#audit-rows', view)
  async function more () {
    const rows = await api('audit?limit=100&offset=' + offset)
    offset += rows.length
    body.append(...rows.map((a) => el('tr', {}, el('td', {}, time(a.at)), el('td', {}, a.user), el('td', {}, a.action), el('td', {}, a.detail))))
    $('#audit-more', view).hidden = rows.length < 100
  }
  $('#audit-more', view).addEventListener('click', more)
  await more()
}

async function account () {
  const view = mount('tpl-account')
  const msg = $('#acc-msg', view)
  const perms = [me.canReview && '查看举报和录音', me.canMute && '禁麦', me.isAdmin && '管理'].filter(Boolean)
  $('#acc-info', view).textContent = `${me.username}，权限：${perms.join('、') || '无'}；${me.hasPassword ? '已设置密码' : '未设置密码（只能用游戏链接登录）'}`
  $('#acc-form', view).addEventListener('submit', async (event) => {
    event.preventDefault()
    try {
      await api('password', { body: { password: event.target.password.value } })
      me = await api('me')
      message(msg, '密码已设置')
      event.target.reset()
    } catch (e) { message(msg, e.message, true) }
  })
  $('#acc-clear', view).addEventListener('click', async () => {
    try { await api('password', { body: { password: '' } }); me = await api('me'); message(msg, '密码已删除') } catch (e) { message(msg, e.message, true) }
  })
  $('#logout', view).addEventListener('click', async () => {
    await api('logout', { body: {} }).catch(() => {})
    me = null
    showLogin()
  })
}

// ------------------------------------------------------------------ start

showLogin()
boot()
