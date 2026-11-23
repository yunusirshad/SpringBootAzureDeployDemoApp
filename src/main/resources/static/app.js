/* Personal Screener 2026 front end. Plain JS, no build step. */
(() => {
  'use strict';

  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];
  const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* private mode */ } },
  };

  let data = null;            // last /api/screen payload
  const openData = new Set(); // rows with the Data block expanded
  const seenAlerts = new Set(store.get('seenAlerts', []));

  // ---------- formatting ----------
  const isNum = (v) => typeof v === 'number' && isFinite(v);
  const money = (v, d = 2) => isNum(v) ? '$' + v.toFixed(d) : '—';
  const fix = (v, d = 2) => isNum(v) ? (Math.abs(v) < 0.5 * 10 ** -d ? 0 : v).toFixed(d) : '—';
  const signed = (v, d = 1) => isNum(v) ? (v > 0 ? '+' : '') + v.toFixed(d) + '%' : '—';
  const big = (v) => {
    if (!isNum(v)) return '—';
    const a = Math.abs(v);
    if (a >= 1e12) return (v / 1e12).toFixed(1) + 'T';
    if (a >= 1e9) return (v / 1e9).toFixed(1) + 'B';
    if (a >= 1e6) return (v / 1e6).toFixed(1) + 'M';
    if (a >= 1e3) return (v / 1e3).toFixed(1) + 'K';
    return v.toFixed(0);
  };
  const etTime = (iso) => iso ? new Date(iso).toLocaleTimeString('en-US', { timeZone: 'America/New_York', hour: 'numeric', minute: '2-digit', second: '2-digit' }) : '—';
  const etStamp = (iso) => iso ? new Date(iso).toLocaleString('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }) + ' ET' : '—';
  const arrow = (t) => t > 0 ? '↑' : t < 0 ? '↓' : '–';
  const zoyaLabel = { COMPLIANT: 'Compliant', NON_COMPLIANT: 'Not compliant', QUESTIONABLE: 'Questionable', NOT_COVERED: 'Not covered', NOT_CHECKED: 'Not checked' };

  async function api(path, opts = {}) {
    const r = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...opts });
    const body = r.headers.get('content-type')?.includes('json') ? await r.json() : await r.text();
    if (!r.ok) throw new Error(body?.error || ('HTTP ' + r.status));
    return body;
  }

  function toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.remove('hidden');
    clearTimeout(toast.h);
    toast.h = setTimeout(() => t.classList.add('hidden'), 6000);
  }

  // ---------- tabs ----------
  function showTab(name) {
    $$('.tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
    $$('.tab').forEach((s) => s.classList.toggle('active', s.id === 'tab-' + name));
    store.set('tab', name);
    if (name === 'alerts') loadAlerts();
  }
  $$('.tabs button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));
  document.addEventListener('click', (e) => {
    const g = e.target.closest('[data-goto]');
    if (g) { e.preventDefault(); showTab(g.dataset.goto); }
  });

  // ---------- controls (remembered per viewer) ----------
  const controls = ['showAll', 'search', 'stateFilter', 'sortBy', 'gexState', 'gexBias', 'gexSide'];
  const saved = store.get('controls', {});
  controls.forEach((id) => {
    const el = $('#' + id);
    if (saved[id] !== undefined) { if (el.type === 'checkbox') el.checked = saved[id]; else el.value = saved[id]; }
    el.addEventListener(el.tagName === 'INPUT' && el.type !== 'checkbox' ? 'input' : 'change', () => {
      const s = {};
      controls.forEach((c) => { const x = $('#' + c); s[c] = x.type === 'checkbox' ? x.checked : x.value; });
      store.set('controls', s);
      render();
    });
  });

  // ---------- screen ----------
  async function loadScreen() {
    try {
      data = await api('api/screen');
      render();
    } catch (e) {
      $('#statusLine').textContent = 'Could not load the screen: ' + e.message;
    }
  }

  function visible(rows) {
    const showAll = $('#showAll').checked;
    const q = $('#search').value.trim().toUpperCase();
    const st = $('#stateFilter').value;
    return rows.filter((r) => {
      if (!showAll && (r.zoya === 'NON_COMPLIANT' || r.zoya === 'QUESTIONABLE')) return false;
      if (q && !r.symbol.includes(q) && !(r.name || '').toUpperCase().includes(q)) return false;
      if (st && r.state !== st) return false;
      return true;
    });
  }

  function sorted(rows) {
    const by = $('#sortBy').value;
    const v = (x) => isNum(x) ? x : -Infinity;
    const cmp = {
      screen: (a, b) => (a.rank || 999) - (b.rank || 999),
      quality: (a, b) => b.qualityScore - a.qualityScore,
      breakout: (a, b) => (isNum(a.toBreakoutPct) ? Math.max(0, a.toBreakoutPct) : 1e9) - (isNum(b.toBreakoutPct) ? Math.max(0, b.toBreakoutPct) : 1e9),
      change: (a, b) => v(b.changePct) - v(a.changePct),
      rvol: (a, b) => v(b.relVol) - v(a.relVol),
      ticker: (a, b) => a.symbol.localeCompare(b.symbol),
    }[by] || (() => 0);
    return [...rows].sort(cmp);
  }

  function render() {
    if (!data) return;
    const s = data.snapshot;
    const rows = s.rows || [];
    renderChips(s.filters || {});
    const exch = (s.filters?.exchanges || ['NASDAQ', 'NYSE']).map((e) => e === 'NASDAQ' ? 'Nasdaq' : e).join(' and ');
    const run = data.running ? ' · screening now…' : '';
    $('#statusLine').textContent = `${exch} · ${s.compliant ?? 0} compliant of ${s.passedFilters ?? 0} · ${etTime(s.generatedAt)} · refreshes Monday 7:00 AM–Friday 8:00 PM ET${run}`;
    $('#statusLine').title = `${s.screened ?? 0} names from ${s.screenSource || '—'}; ${s.passedFilters ?? 0} passed every filter. Session: ${data.session}.`;
    const note = [];
    if (s.screenNote) note.push(s.screenNote);
    if (!s.zoyaConfigured) note.push('Zoya is set to the API (ZOYA_SOURCE=api) but ZOYA_API_KEY is empty, so every name shows "Not checked" and no name can be the super core.');
    $('#screenNote').textContent = note.join(' ');
    $('#screenNote').classList.toggle('hidden', note.length === 0);
    renderPulse(s.pulse);

    const vis = visible(rows);
    const top = sorted(vis.filter((r) => r.list === 'TOP' || (r.list === 'SUPER' && r.rank <= 10)));
    const core = sorted(vis.filter((r) => r.list === 'CORE' || (r.list === 'SUPER' && r.rank > 10)));
    const superRow = rows.find((r) => r.list === 'SUPER');
    $('#topCount').textContent = top.length;
    $('#coreCount').textContent = core.length;
    $('#superCount').textContent = superRow ? 1 : 0;

    // GEX levels: every listed name, narrowed by state / bias / side
    const gs = $('#gexState').value, gb = $('#gexBias').value, gd = $('#gexSide').value;
    const gex = sorted(vis.filter((r) => ['TOP', 'CORE', 'SUPER'].includes(r.list))
      .filter((r) => (!gs || (r.state || '').startsWith(gs)) && (!gb || r.bias === gb) && (!gd || r.side === gd)));
    $('#gexTable').innerHTML = table(gex, false, 'No names match this state, bias, or side.');

    $('#superBody').innerHTML = superRow ? superHtml(superRow) : `<p class="muted">${esc(superWhy(rows))}</p>`;
    $('#topTable').innerHTML = table(top, true, 'No uptrends yet.');
    $('#coreTable').innerHTML = table(core, true, 'No other names qualify.');

    const noUp = rows.filter((r) => r.userAdded && r.list === 'NO_UPTREND');
    const wlMissing = (data.watchlist || []).filter((t) => !rows.some((r) => r.symbol === t));
    $('#noUptrend').innerHTML = noUp.map((r) => `<div class="item"><span class="tkr">${esc(r.symbol)}</span>
        <span class="why">${r.error ? esc(r.error) : 'No uptrend, so it stays off both lists'}</span>
        <button class="ghost small" data-remove="${esc(r.symbol)}">Remove</button></div>`).join('')
      + wlMissing.map((t) => `<div class="item"><span class="tkr">${esc(t)}</span><span class="why">Waiting for the next screen</span>
        <button class="ghost small" data-remove="${esc(t)}">Remove</button></div>`).join('');
  }

  function superWhy(rows) {
    const listed = rows.filter((r) => ['TOP', 'CORE'].includes(r.list));
    if (!listed.length) return 'None of the core names are ready.';
    const n = (f) => listed.filter(f).length;
    return `None of the core names are ready. Of ${listed.length}: ${n((r) => r.zoya === 'COMPLIANT')} compliant, `
      + `${n((r) => r.above1hEma)} above the 1h 20 EMA, ${n((r) => r.relVol >= 1)} at 1× relative volume or more, `
      + `${n((r) => !r.earningsSoon)} clear of earnings, ${n((r) => r.qualityPass)} quality passes.`;
  }

  function renderChips(f) {
    const chips = [];
    if (isNum(f.priceMin)) chips.push(`Price $${f.priceMin}–$${f.priceMax}`);
    if (isNum(f.marketCapMin)) chips.push(`Market cap over $${big(f.marketCapMin)}`);
    if (isNum(f.targetUpsideMin)) chips.push(`Target upside over ${f.targetUpsideMin}%`);
    if (isNum(f.debtEquityMax)) chips.push(`Debt/equity under ${f.debtEquityMax}`);
    if (isNum(f.debtAssetsMax)) chips.push(`Debt/assets under ${f.debtAssetsMax}`);
    if (isNum(f.debtCapitalMax)) chips.push(`Debt/capital under ${f.debtCapitalMax}`);
    if (isNum(f.cashDebtMin)) chips.push(`Cash/debt over ${f.cashDebtMin}`);
    if (isNum(f.revenueGrowthMin)) chips.push(`Revenue growth over ${f.revenueGrowthMin}%`);
    $('#filterChips').innerHTML = chips.map((c) => `<span class="chip">${esc(c)}</span>`).join('');
  }

  function renderPulse(p) {
    if (!p) { $('#pulse').textContent = '—'; return; }
    const cls = p.regime === 'Bullish' ? 'up' : p.regime === 'Bearish' ? 'down' : '';
    const parts = [`<span class="${cls}">${esc(p.regime)}</span>`];
    (p.indexes || []).forEach((i) => {
      const dir = isNum(i.changePct) ? (i.changePct >= 0 ? '<span class="up">up</span>' : '<span class="down">down</span>') : '';
      parts.push(`${esc(i.symbol)} ${i.aboveVwap ? 'above' : 'below'} VWAP ${dir}`);
    });
    if (isNum(p.nasdaqUp)) parts.push(`Nasdaq ${p.nasdaqUp} <span class="up">up</span> / ${p.nasdaqDown} <span class="down">down</span>`);
    if (isNum(p.nyseUp)) parts.push(`NYSE ${p.nyseUp} <span class="up">up</span> / ${p.nyseDown} <span class="down">down</span>`);
    $('#pulse').innerHTML = parts.join('<span class="sep">·</span>');
  }

  const HEAD = ['Exchange', 'Stock', 'Zoya', 'Price', 'Range meter', 'Current state', 'From yday', 'Call wall', 'King', 'Put wall', 'Expiry', 'Bias'];

  function table(rows, withActions, empty) {
    const head = '<thead><tr>' + HEAD.map((h, i) => `<th class="${i >= 3 && i !== 5 && i !== 11 ? 'num' : ''}">${h}</th>`).join('') + (withActions ? '<th></th>' : '') + '</tr></thead>';
    if (!rows.length) return head + `<tbody><tr class="empty"><td colspan="${HEAD.length + (withActions ? 1 : 0)}">${esc(empty)}</td></tr></tbody>`;
    return head + '<tbody>' + rows.map((r) => rowHtml(r, withActions)).join('') + '</tbody>';
  }

  function rowHtml(r, withActions) {
    const q = `<span class="tag q ${r.qualityPass ? 'pass' : 'fail'}" title="${esc((r.qualityReasons || []).join('; '))}">Q ${r.qualityScore} ${r.qualityPass ? 'pass' : esc((r.qualityReasons || [])[0] || 'fail')}</span>`;
    const meta = [
      'ATR ' + (isNum(r.atrPct) ? r.atrPct.toFixed(1) + '%' : '—'),
      big(r.floatShares),
      isNum(r.spreadBps) ? r.spreadBps.toFixed(0) + ' bps' : 'no quote',
      '$' + big(r.dollarVolume),
    ].join(' · ');
    let earn = 'Earnings —';
    if (r.earningsDate) earn = r.earningsSoon ? `<span class="warnline">Earnings in ${r.earningsInDays} trading days</span>` : 'Earnings ' + esc(r.earningsDate);
    const open = openData.has(r.symbol);
    const dataBlock = open ? `<div class="datagrid">
        <div><small>Relative volume</small><b>${isNum(r.relVol) ? r.relVol.toFixed(2) + '×' : '—'}</b><small>today vs average</small></div>
        <div><small>1h trend</small><b class="${r.above1hEma ? 'up' : 'down'}">${r.above1hEma ? 'Above' : 'Below'}</b><small>1h 20 EMA</small></div>
        <div><small>Next earnings</small><b>${r.earningsSoon ? '<span class="down">Skip</span>' : esc(r.earningsDate || '—')}</b><small>${r.earningsDate ? (r.earningsSoon ? esc(r.earningsDate) + ' · inside 3 days' : 'outside 3 days') : 'no date'}</small></div>
        <div><small>To breakout</small><b>${isNum(r.toBreakoutPct) ? (r.toBreakoutPct <= 0 ? 'Broken out' : signed(r.toBreakoutPct)) : '—'}</b><small>${esc(r.breakoutSource || 'premarket or prior high')}</small></div>
      </div>
      <div class="extra">${r.uptrend ? 'Uptrend' : 'No uptrend'} ${signed(r.smaGapPct)} · 50-day ${r.uptrend ? 'above' : 'at or below'} 200-day</div>
      <div class="extra">RSI ${fix(r.rsi, 0)}${r.rsi < 30 ? ' washed' : r.rsi > 70 ? ' stretched' : ''} · MACD ${r.macdUp ? 'up' : 'down'} · ADX ${fix(r.adx, 0)} ${r.adxRising ? 'rising' : 'falling'} · Stoch ${fix(r.stoch, 0)} · Band ${fix(r.bandPct, 0)}%</div>
      <div class="extra">Quantum Shift 1h · energy ${fix(r.qsEnergy, 0)} · regime ${r.qsRegime > 0 ? '<span class="up">up</span>' : r.qsRegime < 0 ? '<span class="down">down</span>' : 'neutral'}${r.qsLastSignal ? ' · ' + esc(r.qsLastSignal) : ''}</div>
      <div class="extra">${esc(r.trendNote || '')}</div>
      ${r.screenFails?.length ? `<div class="extra warnline">Screen: ${esc(r.screenFails.join(', '))}</div>` : ''}
      ${r.error ? `<div class="extra warnline">${esc(r.error)}</div>` : ''}` : '';

    const used = r.rangeUsedPct;
    const meter = `<div>${fix(r.dayRange)} / ${fix(r.atr)}</div>
      <div class="meter"><i class="${used > 100 ? 'over' : ''}" style="width:${Math.min(100, isNum(used) ? used : 0)}%"></i></div>
      <div class="meta">${isNum(used) ? used.toFixed(0) + '% used' : '—'}</div>
      <div class="meta">${isNum(r.rangeLeft) ? (r.rangeLeft > 0 ? r.rangeLeft.toFixed(2) + ' left' : 'past ATR') : ''}</div>
      <div class="meta">RVol ${isNum(r.relVol) ? Math.round(r.relVol * 100) + '%' : '—'} / ${isNum(r.relVol30) ? Math.round(r.relVol30 * 100) + '%' : '—'}</div>`;

    const dir = (r.state || '').split(' ')[0];
    const g = r.gex;
    const lvl = (v, p) => isNum(v) ? `${money(v)}<span class="pct">${fix(p, 0)}%</span>` : '—';
    const biasCls = r.bias === 'Bullish' ? 'bull' : r.bias === 'Bearish' ? 'bear' : 'mixed';
    const book = g ? `book ${big(g.totalOi)} · calls ${big(g.callOi)} · puts ${big(g.putOi)} · L/S ${fix(g.callPutRatio)}` : 'book – · calls – · puts – · L/S –';
    return `<tr data-symbol="${esc(r.symbol)}">
      <td>${esc(r.exchange || '')}</td>
      <td><div><span class="tkr">${esc(r.symbol)}</span>${r.userAdded ? '<span class="added">You added</span>' : ''}</div>
        <div class="cname">${esc(r.name || '')}</div>
        <div><span class="tag ${r.side === 'Long' ? 'long' : 'short'}">${esc(r.side || '—')}</span>${q}</div>
        <div class="meta">${meta}</div>
        <div class="meta">${earn}</div>
        <button class="ghost small datatoggle" data-toggle="${esc(r.symbol)}">${open ? 'Hide data' : 'Data'}</button>
        ${dataBlock}</td>
      <td><a class="zoya ${esc(r.zoya)}" href="https://zoya.finance/stocks/${encodeURIComponent(String(r.symbol).toLowerCase())}" target="_blank" rel="noopener" title="Open on Zoya">${esc(zoyaLabel[r.zoya] || r.zoya)}</a><div class="meta">${r.zoyaCheckedAt ? 'Last scanned ' + esc(etStamp(r.zoyaCheckedAt)) : ''}</div></td>
      <td class="num"><b data-f="price">${money(r.price)}</b></td>
      <td class="num">${meter}</td>
      <td><span class="state ${esc(dir)}">${esc(r.state || '—')}</span></td>
      <td class="num"><span class="${r.changePct >= 0 ? 'up' : 'down'}">${signed(r.changePct)}</span><div class="meta">vs close</div></td>
      <td class="num">${g ? lvl(g.callWall, g.callWallPct) : '—'}</td>
      <td class="num">${g ? lvl(g.king, g.kingPct) : '—'}</td>
      <td class="num">${g ? lvl(g.putWall, g.putWallPct) : '—'}</td>
      <td class="num">${g ? esc(g.expiry) : 'No chain'}</td>
      <td><span class="tag ${biasCls}">${esc(r.bias || '—')}</span>
        <div class="arrows">15m ${arrow(r.trend15m)} · 1h ${arrow(r.trend1h)} · 4h ${arrow(r.trend4h)}</div>
        <div class="book">${book}</div></td>
      ${withActions ? `<td>${r.userAdded ? `<button class="ghost small" data-remove="${esc(r.symbol)}">Remove</button>` : ''}
        <div><a href="#" class="link small" data-tech="${esc(r.symbol)}">Chart</a></div></td>` : ''}
    </tr>`;
  }

  function superHtml(r) {
    return `<div class="superpick">
      <span class="big">${esc(r.symbol)}</span><span class="muted">${esc(r.name || '')}</span>
      <span><b>${money(r.price)}</b> <span class="${r.changePct >= 0 ? 'up' : 'down'}">${signed(r.changePct)}</span></span>
      <span>To breakout <b>${isNum(r.toBreakoutPct) && r.toBreakoutPct <= 0 ? 'broken out' : signed(r.toBreakoutPct)}</b> (${money(r.breakoutLevel)})</span>
      <span>RVol <b>${fix(r.relVol)}×</b></span><span>Q <b>${r.qualityScore}</b></span>
      <span class="state ${esc((r.state || '').split(' ')[0])}">${esc(r.state || '')}</span>
    </div>`;
  }

  // row actions
  document.addEventListener('click', async (e) => {
    const t = e.target.closest('[data-toggle]');
    if (t) {
      const s = t.dataset.toggle;
      openData.has(s) ? openData.delete(s) : openData.add(s);
      render();
      return;
    }
    const rm = e.target.closest('[data-remove]');
    if (rm) {
      await api('api/watchlist/' + encodeURIComponent(rm.dataset.remove), { method: 'DELETE' });
      toast('Removed ' + rm.dataset.remove);
      await loadWatchlist();
      loadScreen();
      return;
    }
    const tech = e.target.closest('[data-tech]');
    if (tech) {
      e.preventDefault();
      $('#techInput').value = tech.dataset.tech;
      showTab('technicals');
      loadTechnicals(tech.dataset.tech);
    }
  });

  async function loadWatchlist() {
    try { if (data) data.watchlist = await api('api/watchlist'); } catch { /* ignore */ }
  }

  $('#addForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const v = $('#addInput').value.trim();
    if (!v) return;
    try {
      const r = await api('api/watchlist', { method: 'POST', body: JSON.stringify({ tickers: v }) });
      $('#addInput').value = '';
      toast(r.added.length ? `Added ${r.added.join(', ')}. Screening them now.` : 'Already on the list.');
      if (data) data.watchlist = r.watchlist;
      render();
    } catch (err) { toast(err.message); }
  });

  $('#importFile').addEventListener('change', async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    const text = await f.text();
    const r = await api('api/watchlist', { method: 'POST', body: JSON.stringify({ tickers: text }) });
    toast(`Imported ${r.added.length} tickers.`);
    e.target.value = '';
    if (data) data.watchlist = r.watchlist;
    render();
  });

  $('#refreshBtn').addEventListener('click', async () => {
    const r = await api('api/refresh', { method: 'POST' });
    toast(r.started ? 'Screening now. This takes a minute or two.' : 'A screen is already running.');
    setTimeout(loadScreen, 1500);
  });

  // ---------- 10-second price poll ----------
  async function pollPrices() {
    try {
      const p = await api('api/prices');
      if (!data) return;
      const map = new Map(p.rows.map((r) => [r.symbol, r]));
      data.snapshot.rows.forEach((r) => Object.assign(r, map.get(r.symbol) || {}));
      data.snapshot.pulse = p.pulse;
      data.session = p.session;
      const finished = data.running && !p.running;
      data.running = p.running;
      if (finished || p.generatedAt !== data.snapshot.generatedAt) { loadScreen(); } else if (!document.activeElement?.closest('.toolbar,.gexfilters,#addForm')) { render(); }
      handleRang(p.alerts || []);
    } catch { /* keep the last view */ }
  }

  function handleRang(list) {
    const fresh = list.filter((a) => !seenAlerts.has(a.id));
    if (!fresh.length) return;
    fresh.forEach((a) => {
      seenAlerts.add(a.id);
      const msg = `${a.symbol} ${describe(a)} hit at ${money(a.triggeredPrice)}${a.note ? ' — ' + a.note : ''}`;
      toast(msg);
      try { if ('Notification' in window && Notification.permission === 'granted') new Notification('Price alert', { body: msg }); } catch { /* ignore */ }
    });
    store.set('seenAlerts', [...seenAlerts].slice(-200));
    const b = $('#alertBadge');
    b.textContent = fresh.length;
    b.classList.remove('hidden');
    if ($('#tab-alerts').classList.contains('active')) loadAlerts();
  }

  // ---------- alerts ----------
  const describe = (a) => ({ ABOVE: `at or above ${money(a.level)}`, BELOW: `at or below ${money(a.level)}`, PCT_UP: `up ${a.level}%`, PCT_DOWN: `down ${a.level}%` }[a.type] || a.type);

  async function loadAlerts() {
    $('#alertBadge').classList.add('hidden');
    const list = await api('api/alerts');
    const rows = list.slice().reverse().map((a) => `<tr>
      <td class="tkr">${esc(a.symbol)}</td><td>${esc(describe(a))}</td><td>${esc(a.note || '')}</td>
      <td>${a.triggeredAt ? `<span class="up">Rang ${esc(etStamp(a.triggeredAt))} at ${money(a.triggeredPrice)}</span>` : '<span class="muted">Watching</span>'}</td>
      <td><button class="ghost small" data-del-alert="${esc(a.id)}">Delete</button></td></tr>`).join('');
    $('#alertTable').innerHTML = '<thead><tr><th>Ticker</th><th>Condition</th><th>Note</th><th>Status</th><th></th></tr></thead><tbody>'
      + (rows || '<tr class="empty"><td colspan="5">No alerts yet.</td></tr>') + '</tbody>';
  }

  $('#alertForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    const msg = $('#alertMsg');
    try {
      const a = await api('api/alerts', { method: 'POST', body: JSON.stringify(Object.fromEntries(f)) });
      msg.className = 'msg';
      msg.textContent = `Watching ${a.symbol} ${describe(a)}.`;
      e.target.reset();
      loadAlerts();
    } catch (err) { msg.className = 'msg err'; msg.textContent = err.message; }
  });

  document.addEventListener('click', async (e) => {
    const d = e.target.closest('[data-del-alert]');
    if (d) { await api('api/alerts/' + d.dataset.delAlert, { method: 'DELETE' }); loadAlerts(); }
  });

  $('#notifyBtn').addEventListener('click', async () => {
    if (!('Notification' in window)) { toast('This browser has no notifications.'); return; }
    const p = await Notification.requestPermission();
    toast(p === 'granted' ? 'Notifications are on.' : 'Notifications are blocked in this browser.');
  });

  // ---------- analyze ----------
  $('#analyzeForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const t = $('#analyzeInput').value.trim();
    const out = $('#analyzeOut');
    out.innerHTML = '<p class="muted">Reading SEC filings…</p>';
    try {
      const a = await api('api/analyze/' + encodeURIComponent(t));
      const pctCell = (v) => isNum(v) ? `<span class="${v >= 0 ? 'up' : 'down'}">${signed(v)}</span>` : '—';
      out.innerHTML = `<h3>${esc(a.company)} <span class="muted">(${esc(a.ticker)})</span></h3>
        <ul class="summary">${a.summary.map((s) => `<li>${esc(s)}</li>`).join('')}</ul>
        <p class="muted">Trailing four quarters: revenue $${big(a.ttmRevenue)}, net income $${big(a.ttmNetIncome)}${isNum(a.ttmNetMargin) ? `, net margin ${a.ttmNetMargin}%` : ''}.</p>
        <div class="tablewrap"><table class="grid"><thead><tr><th>Quarter end</th><th class="num">Revenue</th><th class="num">YoY</th><th class="num">Gross margin</th><th class="num">Op. margin</th><th class="num">Net income</th><th class="num">Net margin</th><th class="num">Diluted EPS</th><th class="num">EPS YoY</th></tr></thead><tbody>
        ${a.quarters.map((q) => `<tr><td>${esc(q.end)}</td><td class="num">$${big(q.revenue)}</td><td class="num">${pctCell(q.revenueYoY)}</td>
          <td class="num">${isNum(q.grossMargin) ? q.grossMargin + '%' : '—'}</td><td class="num">${isNum(q.operatingMargin) ? q.operatingMargin + '%' : '—'}</td>
          <td class="num">$${big(q.netIncome)}</td><td class="num">${isNum(q.netMargin) ? q.netMargin + '%' : '—'}</td>
          <td class="num">${fix(q.eps)}</td><td class="num">${pctCell(q.epsYoY)}</td></tr>`).join('')}
        </tbody></table></div>`;
    } catch (err) { out.innerHTML = `<p class="msg err">${esc(err.message)}</p>`; }
  });

  // ---------- technicals ----------
  $('#techForm').addEventListener('submit', (e) => { e.preventDefault(); loadTechnicals($('#techInput').value.trim()); });

  async function loadTechnicals(t) {
    const out = $('#techOut');
    out.innerHTML = '<p class="muted">Loading candles…</p>';
    try {
      const d = await api('api/technicals/' + encodeURIComponent(t));
      const cell = (f, k, dd = 2) => isNum(f[k]) ? f[k].toFixed(dd) : '—';
      out.innerHTML = `<h3>${esc(d.symbol)}</h3>
        <div class="legend"><span><i style="background:var(--text)"></i>Close</span><span><i style="background:var(--accent)"></i>50-day</span><span><i style="background:var(--warn)"></i>200-day</span></div>
        <canvas class="chart" id="techChart"></canvas>
        <div class="tablewrap"><table class="grid"><thead><tr><th>Timeframe</th><th class="num">Close</th><th class="num">20 EMA</th><th>Trend</th><th class="num">RSI</th><th>MACD</th><th class="num">ADX</th><th class="num">+DI / −DI</th><th class="num">Stoch</th><th class="num">Band</th><th class="num">ATR</th><th class="num">Candles</th></tr></thead><tbody>
        ${d.frames.map((f) => `<tr><td>${esc(f.timeframe)}</td><td class="num">${cell(f, 'close')}</td>
          <td class="num"><span class="${f.aboveEma20 ? 'up' : 'down'}">${cell(f, 'ema20')}</span></td>
          <td>${arrow(f.trend)}</td><td class="num">${cell(f, 'rsi', 0)}</td><td>${f.macdUp === undefined ? '—' : f.macdUp ? '<span class="up">up</span>' : '<span class="down">down</span>'}</td>
          <td class="num">${cell(f, 'adx', 0)}</td><td class="num">${cell(f, 'plusDi', 0)} / ${cell(f, 'minusDi', 0)}</td>
          <td class="num">${cell(f, 'stoch', 0)}</td><td class="num">${isNum(f.bandPct) ? fix(f.bandPct, 0) + '%' : '—'}</td>
          <td class="num">${cell(f, 'atr')}</td><td class="num">${f.bars}</td></tr>`).join('')}
        </tbody></table></div>`;
      drawChart($('#techChart'), d.chart);
    } catch (err) { out.innerHTML = `<p class="msg err">${esc(err.message)}</p>`; }
  }

  function drawChart(canvas, pts) {
    if (!pts?.length) return;
    const css = getComputedStyle(document.documentElement);
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, h = canvas.clientHeight;
    canvas.width = w * dpr; canvas.height = h * dpr;
    const ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);
    const vals = pts.flatMap((p) => [p.c, p.s50, p.s200]).filter(isNum);
    const lo = Math.min(...vals), hi = Math.max(...vals), pad = 8, left = 48;
    const x = (i) => left + (i / (pts.length - 1)) * (w - left - pad);
    const y = (v) => pad + (1 - (v - lo) / (hi - lo || 1)) * (h - 2 * pad - 14);
    ctx.font = '11px sans-serif';
    ctx.fillStyle = css.getPropertyValue('--muted');
    ctx.strokeStyle = css.getPropertyValue('--border');
    for (let k = 0; k <= 4; k++) {
      const v = lo + (hi - lo) * k / 4, yy = y(v);
      ctx.beginPath(); ctx.moveTo(left, yy); ctx.lineTo(w - pad, yy); ctx.stroke();
      ctx.fillText(v.toFixed(2), 4, yy + 4);
    }
    const line = (key, color, width) => {
      ctx.strokeStyle = color; ctx.lineWidth = width; ctx.beginPath();
      let started = false;
      pts.forEach((p, i) => { if (!isNum(p[key])) return; started ? ctx.lineTo(x(i), y(p[key])) : ctx.moveTo(x(i), y(p[key])); started = true; });
      ctx.stroke();
    };
    line('s200', css.getPropertyValue('--warn'), 1.5);
    line('s50', css.getPropertyValue('--accent'), 1.5);
    line('c', css.getPropertyValue('--text'), 1.5);
    const first = new Date(pts[0].t * 1000), last = new Date(pts[pts.length - 1].t * 1000);
    const f = (d) => d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
    ctx.fillStyle = css.getPropertyValue('--muted');
    ctx.fillText(f(first), left, h - 2);
    ctx.fillText(f(last), w - pad - 40, h - 2);
  }

  // ---------- backtest ----------
  $('#btForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    const q = new URLSearchParams();
    for (const k of ['tickers', 'days', 'interval', 'threshold', 'maxBars', 'costBps']) if (f.get(k)) q.set(k, f.get(k));
    q.set('shorts', f.get('shorts') ? 'true' : 'false');
    $('#btOut').innerHTML = '<p class="muted">Running on every name… this takes a few seconds per dozen tickers.</p>';
    try { renderBacktest(await api('api/backtest?' + q)); } catch (err) { $('#btOut').innerHTML = `<p class="msg err">${esc(err.message)}</p>`; }
  });

  function renderBacktest(d) {
    if (!d || !d.summary) return;
    const S = d.summary, pc = (v) => isNum(v) ? `<span class="${v >= 0 ? 'up' : 'down'}">${signed(v, 2)}</span>` : '—';
    const tile = (label, val, sub = '') => `<div><small>${label}</small><b>${val}</b><small>${sub}</small></div>`;
    const side = (x, n) => `${n}: ${x.trades} trades · win ${fix(x.winRate, 1)}% · avg ${signed(x.avgReturnPct, 2)} · PF ${fix(x.profitFactor)} · avg R ${fix(x.avgR)}`;
    $('#btOut').innerHTML = `
      <p class="muted">${d.tested} of ${d.tickers} tickers tested · last ${d.params.days} days · ${esc(d.params.interval)} bars · energy ≥ ${d.params.threshold} · run ${esc(etStamp(d.generatedAt))}</p>
      <div class="datagrid" style="grid-template-columns:repeat(auto-fit,minmax(120px,1fr))">
        ${tile('Trades', S.trades, `${S.stocksWithTrades ?? 0} stocks traded`)}
        ${tile('Win rate', fix(S.winRate, 1) + '%', `${S.wins} wins`)}
        ${tile('Avg trade', pc(S.avgReturnPct), `avg R ${fix(S.avgR)}`)}
        ${tile('Profit factor', fix(S.profitFactor), 'gross win / loss')}
        ${tile('Strategy, equal weight', pc(S.equalWeightReturnPct), `${S.stocksPositive ?? 0} stocks positive`)}
        ${tile('Buy and hold', pc(S.buyHoldReturnPct), 'same window')}
        ${tile('Avg hold', fix(S.avgBarsHeld, 1) + ' bars', `best ${signed(S.bestPct, 1)} · worst ${signed(S.worstPct, 1)}`)}
      </div>
      <p class="extra">${side(d.longs, 'Longs')}<br>${side(d.shorts, 'Shorts')}</p>
      <h3>By stock</h3>
      <div class="tablewrap"><table class="grid"><thead><tr><th>Stock</th><th class="num">Trades</th><th class="num">Win rate</th><th class="num">Avg trade</th><th class="num">Avg R</th><th class="num">Strategy</th><th class="num">Longs</th><th class="num">Shorts</th><th class="num">Max DD</th><th class="num">Buy &amp; hold</th><th class="num">Energy now</th><th>Regime</th><th>Last shift</th></tr></thead><tbody>
      ${d.stocks.map((r) => r.error ? `<tr><td class="tkr">${esc(r.symbol)}</td><td colspan="12" class="muted">${esc(r.error)}</td></tr>` : `<tr>
        <td class="tkr">${esc(r.symbol)}</td><td class="num">${r.trades}</td><td class="num">${isNum(r.winRate) ? r.winRate + '%' : '—'}</td>
        <td class="num">${pc(r.avgReturnPct)}</td><td class="num">${fix(r.avgR)}</td><td class="num"><b>${pc(r.totalReturnPct)}</b></td>
        <td class="num">${pc(r.longReturnPct)}</td><td class="num">${pc(r.shortReturnPct)}</td>
        <td class="num">${isNum(r.maxDrawdownPct) ? '-' + r.maxDrawdownPct.toFixed(1) + '%' : '—'}</td><td class="num">${pc(r.buyHoldPct)}</td>
        <td class="num">${fix(r.lastEnergy, 0)}</td><td>${r.regime > 0 ? '<span class="tag bull">Up</span>' : r.regime < 0 ? '<span class="tag bear">Down</span>' : '<span class="muted">Neutral</span>'}</td>
        <td class="meta">${esc(r.lastSignal || '—')}</td></tr>`).join('')}
      </tbody></table></div>
      <details class="howto" style="margin-top:12px"><summary>All ${d.trades.length} trades</summary>
      <div class="tablewrap"><table class="grid"><thead><tr><th>Stock</th><th>Side</th><th>Entry (ET)</th><th class="num">Entry</th><th>Exit (ET)</th><th class="num">Exit</th><th class="num">Return</th><th class="num">R</th><th class="num">Bars</th><th>Exit reason</th><th class="num">Energy</th></tr></thead><tbody>
      ${d.trades.map((t) => `<tr><td class="tkr">${esc(t.symbol)}</td><td><span class="tag ${t.side === 'Long' ? 'long' : 'short'}">${t.side}</span></td><td>${esc(t.entryTime)}</td><td class="num">${fix(t.entry)}</td><td>${esc(t.exitTime)}</td><td class="num">${fix(t.exit)}</td><td class="num">${pc(t.returnPct)}</td><td class="num">${fix(t.r)}</td><td class="num">${t.bars}</td><td>${esc(t.exitReason)}</td><td class="num">${fix(t.energy, 0)}</td></tr>`).join('')}
      </tbody></table></div></details>`;
  }
  api('api/backtest/last').then(renderBacktest).catch(() => {});

  // ---------- sessions ----------
  const PHASES = [['accumulation', 'Accumulation', 'acc', 'A'], ['manipulation', 'Manipulation', 'man', 'M'], ['absorption', 'Absorption', 'abs', 'Ab'], ['distribution', 'Distribution', 'dist', 'D']];
  const SHORT_SESSION = { 'Asia': 'Asia', 'London': 'London', 'New York': 'NY' };
  let ses = null, sesLayout = null;
  let sesTf = store.get('sesTf', '15m');
  $('#sesInput').value = store.get('sesSym', 'ES=F');
  $('#sesDays').value = String(store.get('sesDays', 3));
  const markTf = () => $$('#sesTf button').forEach((b) => b.classList.toggle('on', b.dataset.tf === sesTf));
  markTf();
  $$('#sesTf button').forEach((b) => b.addEventListener('click', () => { sesTf = b.dataset.tf; store.set('sesTf', sesTf); markTf(); loadSessions(); }));
  $('#sesDays').addEventListener('change', () => { store.set('sesDays', +$('#sesDays').value); loadSessions(); });
  $('#sesForm').addEventListener('submit', (e) => { e.preventDefault(); loadSessions(); });

  async function loadSessions() {
    const sym = $('#sesInput').value.trim().toUpperCase();
    if (!sym) return;
    store.set('sesSym', sym);
    $('#sesOut').innerHTML = '<p class="muted">Reading sessions…</p>';
    try {
      ses = await api(`api/sessions/${encodeURIComponent(sym)}?tf=${sesTf}&days=${$('#sesDays').value}`);
      drawSessions();
      renderSessionTable();
    } catch (err) { $('#sesOut').innerHTML = `<p class="msg err">${esc(err.message)}</p>`; }
  }

  function renderSessionTable() {
    const s = ses;
    $('#sesStamp').textContent = `${s.symbol} · ${s.timeframe} candles · scored on ${s.baseTimeframe} · ${etStamp(s.generatedAt)}`;
    const cell = (p) => `<td class="num"><span class="${p.dir === 'Bull' ? 'up' : 'down'}">${p.pct}%</span><span class="pct">${p.dir}</span></td>`;
    const rows = s.sessions.slice().reverse().map((x) => `<tr>
      <td>${esc(x.tradeDate)}</td><td><b>${esc(x.session)}</b>${x.live ? ' <span class="tag mixed">live</span>' : ''}</td>
      <td><span class="tag ${x.dir === 'Bull' ? 'bull' : 'bear'}">${esc(x.label)} ${x.pct}%</span></td>
      ${PHASES.map((p) => cell(x[p[0]])).join('')}
      <td class="num">${fix(x.rangeVsUsual)}×</td><td class="num">${fix(x.effort)}×</td><td class="meta">${esc(x.sweep || '—')}</td></tr>`).join('');
    $('#sesOut').innerHTML = `<div class="tablewrap"><table class="grid"><thead><tr><th>Date</th><th>Session</th><th>Reading</th>
      <th class="num">Accum.</th><th class="num">Manip.</th><th class="num">Absorb.</th><th class="num">Distrib.</th>
      <th class="num">Range vs usual</th><th class="num">Effort</th><th>Sweep</th></tr></thead><tbody>
      ${rows || '<tr class="empty"><td colspan="10">No sessions in this window.</td></tr>'}</tbody></table></div>`;
  }

  function drawSessions() {
    const canvas = $('#sesChart'), d = ses;
    if (!d) return;
    const css = getComputedStyle(document.documentElement), col = (v) => css.getPropertyValue(v).trim();
    const dpr = window.devicePixelRatio || 1, w = canvas.clientWidth, h = canvas.clientHeight;
    canvas.width = w * dpr; canvas.height = h * dpr;
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    const C = d.candles, S = d.sessions, dur = d.candleSeconds;
    if (!C.length) { ctx.fillStyle = col('--muted'); ctx.fillText('No candles in this window.', 20, 30); return; }
    const left = 8, right = 64, top = 8, bottom = 26;
    const slot = (w - left - right) / C.length;
    // time -> x on a candle-index axis, so session boxes line up on any timeframe
    const tx = (t) => {
      let lo = 0, hi = C.length - 1;
      if (t <= C[0].t) return left;
      while (lo < hi) { const m = (lo + hi + 1) >> 1; if (C[m].t <= t) lo = m; else hi = m - 1; }
      const c = C[lo], frac = Math.min(1, (t - c.t) / dur);
      if (t >= c.t + dur && lo + 1 < C.length) return left + (lo + 1) * slot; // in a gap: snap to the next candle
      return left + (lo + frac) * slot;
    };
    let lo = Infinity, hi = -Infinity;
    C.forEach((c) => { lo = Math.min(lo, c.l); hi = Math.max(hi, c.h); });
    S.forEach((s) => { lo = Math.min(lo, s.low); hi = Math.max(hi, s.high); });
    const pad = (hi - lo) * 0.08 || 1; lo -= pad; hi += pad * 2.2;
    const py = (v) => top + (1 - (v - lo) / (hi - lo)) * (h - top - bottom);
    const dec = hi < 2 ? 5 : hi < 20 ? 3 : 2;

    // grid + price axis
    ctx.font = '11px -apple-system, Segoe UI, sans-serif';
    ctx.strokeStyle = col('--border'); ctx.fillStyle = col('--muted'); ctx.lineWidth = 1;
    for (let k = 0; k <= 5; k++) {
      const v = lo + (hi - lo) * k / 5, y = py(v);
      ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(w - right, y); ctx.stroke();
      ctx.fillText(v.toFixed(dec), w - right + 6, y + 4);
    }

    // session boxes underneath the candles
    const phaseColor = { Accumulation: col('--acc'), Manipulation: col('--man'), Absorption: col('--abs'), Distribution: col('--dist') };
    sesLayout = [];
    S.forEach((s) => {
      const x0 = tx(s.start), x1 = Math.max(x0 + 2, tx(s.end)), y0 = py(s.high), y1 = py(s.low);
      const phase = s.label.split(' ')[1], fill = phaseColor[phase];
      ctx.globalAlpha = 0.10 + 0.22 * (s.pct / 100);
      ctx.fillStyle = fill; ctx.fillRect(x0, y0, x1 - x0, y1 - y0);
      ctx.globalAlpha = 1;
      ctx.strokeStyle = s.dir === 'Bull' ? col('--bull') : col('--bear');
      ctx.lineWidth = 1.5; ctx.setLineDash(s.live ? [4, 3] : []);
      ctx.strokeRect(x0 + 0.5, y0 + 0.5, x1 - x0 - 1, y1 - y0 - 1);
      ctx.setLineDash([]);
      sesLayout.push({ s, x0, x1, y0, y1 });
    });

    // candles
    const bw = Math.max(1, Math.min(14, slot * 0.7));
    C.forEach((c, i) => {
      const x = left + (i + 0.5) * slot, up = c.c >= c.o;
      ctx.strokeStyle = ctx.fillStyle = up ? col('--bull') : col('--bear');
      ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(x, py(c.h)); ctx.lineTo(x, py(c.l)); ctx.stroke();
      const yo = py(c.o), yc = py(c.c);
      ctx.fillRect(x - bw / 2, Math.min(yo, yc), bw, Math.max(1, Math.abs(yc - yo)));
    });

    // labels on top of each box: session, dominant phase %, then all four
    sesLayout.forEach(({ s, x0, x1, y0 }) => {
      const width = x1 - x0, phase = s.label.split(' ')[1];
      const lines = width > 150
        ? [`${SHORT_SESSION[s.session]} · ${s.pct}% ${s.dir} ${phase}`, PHASES.map((p) => `${p[3]} ${s[p[0]].pct}%`).join(' · ')]
        : width > 70 ? [`${SHORT_SESSION[s.session]} ${s.pct}% ${s.dir}`, phase] : [`${s.pct}%`, PHASES.find((p) => p[1] === phase)[3]];
      ctx.font = '600 11px -apple-system, Segoe UI, sans-serif';
      const lh = 13, boxH = lines.length * lh + 4, y = Math.max(top + boxH, y0 - 3);
      ctx.fillStyle = s.dir === 'Bull' ? col('--bull') : col('--bear');
      ctx.fillText(lines[0], x0 + 3, y - boxH + lh, width - 4);
      ctx.font = '11px -apple-system, Segoe UI, sans-serif';
      ctx.fillStyle = col('--muted');
      if (lines[1]) ctx.fillText(lines[1], x0 + 3, y - boxH + 2 * lh, width - 4);
    });

    // date ticks at each New York session start
    ctx.fillStyle = col('--muted');
    let lastX = -999;
    S.filter((s) => s.session === 'Asia').forEach((s) => {
      const x = tx(s.start);
      if (x - lastX < 60) return;
      lastX = x;
      ctx.fillText(s.tradeDate.slice(5), x, h - 8);
      ctx.strokeStyle = col('--border'); ctx.setLineDash([2, 3]);
      ctx.beginPath(); ctx.moveTo(x, top); ctx.lineTo(x, h - bottom); ctx.stroke(); ctx.setLineDash([]);
    });
  }

  $('#sesChart').addEventListener('mousemove', (e) => {
    const tip = $('#sesTip');
    if (!sesLayout) return;
    const r = e.currentTarget.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
    const hit = sesLayout.find((b) => x >= b.x0 && x <= b.x1 && y >= b.y0 - 30 && y <= b.y1);
    if (!hit) { tip.classList.add('hidden'); return; }
    const s = hit.s, colr = { acc: 'var(--acc)', man: 'var(--man)', abs: 'var(--abs)', dist: 'var(--dist)' };
    tip.innerHTML = `<b>${esc(s.session)} · ${esc(s.tradeDate)}</b>${s.live ? ' <span class="tag mixed">live</span>' : ''}
      <div class="${s.dir === 'Bull' ? 'up' : 'down'}" style="font-weight:700;margin:2px 0">${s.pct}% ${esc(s.label)}</div>
      ${PHASES.map((p) => `<div class="pbar"><span>${p[1]}</span><span class="b"><i style="width:${s[p[0]].pct}%;background:${colr[p[2]]}"></i></span><span class="${s[p[0]].dir === 'Bull' ? 'up' : 'down'}">${s[p[0]].pct}%</span></div>`).join('')}
      <div class="meta" style="margin-top:4px">H ${fix(s.high, 4)} · L ${fix(s.low, 4)} · range ${fix(s.rangeVsUsual)}× usual · effort ${fix(s.effort)}×</div>
      ${s.sweep ? `<div class="meta">${esc(s.sweep)}</div>` : ''}`;
    tip.classList.remove('hidden');
    tip.style.left = Math.min(x + 14, r.width - 240) + 'px';
    tip.style.top = (y + 14) + 'px';
  });
  $('#sesChart').addEventListener('mouseleave', () => $('#sesTip').classList.add('hidden'));
  window.addEventListener('resize', () => { if (ses && $('#tab-sessions').classList.contains('active')) drawSessions(); });
  $$('.tabs button').forEach((b) => b.addEventListener('click', () => {
    if (b.dataset.tab === 'sessions') { if (ses) drawSessions(); else loadSessions(); }
  }));
  if (store.get('tab', 'screen') === 'sessions') setTimeout(loadSessions, 0);

  // ---------- details ----------
  async function loadDetails() {
    let cfg = null;
    try { cfg = await api('api/config'); } catch { /* ignore */ }
    const q = cfg?.quality || {};
    const qualityText = `Quality is shown on each name and does not remove it. A pass is a score of ${q.passScore ?? 60} or more, at least $${big(q.minDollarVolume ?? 2e6)} of dollar volume, a spread of ${q.maxSpreadBps ?? 50} bps or tighter when a quote exists, and a known float. Clips: relative volume ${q.relVolMin ?? 0.5}–${q.relVolMax ?? 2}, dollar volume $${big(q.dollarVolMin ?? 5e5)}–$${big(q.dollarVolMax ?? 2e7)}, spread ${q.spreadMin ?? 5}–${q.spreadMax ?? 80} bps, float ${big(q.floatMin ?? 2e6)}–${big(q.floatMax ?? 5e7)}, ATR% sweet spot ${q.atrPctMin ?? 3}–${q.atrPctMax ?? 12}.`;
    $('#qualityHelp').textContent = qualityText;
    $('#detailsBody').innerHTML = `
      <h2>How the app works</h2>
      <h3>1. The screen</h3>
      <p>Every 30 minutes from Monday 7:00 AM to Friday 8:00 PM ET, the app asks TradingView's screener for Nasdaq and NYSE common stocks that pass price, market cap, debt/equity and revenue growth. It then checks target upside, debt/assets, debt/capital and cash/debt itself from the same columns. Names you add are screened every run whether or not they pass.</p>
      <h3>2. Compliance</h3>
      <p>Each name is looked up on its Zoya page (for example zoya.finance/stocks/mitk, which reads "MITK stock is Shariah-compliant") and the result is cached for 24 hours, so Zoya is asked about each ticker at most once a day. The cache is saved to disk and survives restarts. A ticker Zoya doesn't cover reads "Not covered"; a failed lookup reads "Not checked" and is retried after 15 minutes. "Show all" also shows names Zoya marks not compliant or questionable.</p>
      <h3>3. Quality</h3><p>${esc(qualityText)} Each clip is worth 20 points, with the score fading the further outside the clip a value sits (zero at ten times away). A missing quote scores the spread clip at half.</p>
      <h3>4. The lists</h3>
      <ul>
        <li><b>Top 10</b>: the ten names where the 50-day average is furthest above the 200-day, as a percent of the 200-day.</li>
        <li><b>Core</b>: every other name with the 50-day above the 200-day. Names you added without an uptrend sit underneath.</li>
        <li><b>Super core</b>: of those, names that are compliant, above the 1-hour 20 EMA, at 1× relative volume or more, more than 3 trading days from earnings and a quality pass. The one closest to its breakout level wins.</li>
      </ul>
      <h3>5. Readings on each row</h3>
      <ul>
        <li><b>Long / Short</b>: Long when the daily +DI is above −DI (ADX "rising"), Short otherwise.</li>
        <li><b>Range meter</b>: today's high-low range against the 14-day ATR, how much of it is used and what is left. RVol is today's volume against the 10-day and 30-day average.</li>
        <li><b>Current state</b>: initiation is a move of 0.6 ATR or more on 1.3× volume; accumulation (up) or distribution (down) is a move of 0.45 ATR or more, or volume of 1× with a close in the third of the range that agrees; anything smaller is manipulation.</li>
        <li><b>To breakout</b>: the distance to the higher of today's premarket high and the prior day's high.</li>
        <li><b>Bias</b>: close against a rising or falling 20 EMA on completed 15-minute, 1-hour and 4-hour candles. More up than down is Bullish, more down is Bearish, otherwise Mixed.</li>
        <li><b>GEX levels</b>: from the next listed option expiry. Call wall and put wall are the strikes with the most call and put open interest; King is the strike with the largest net gamma exposure. The percent is the strike's share of open interest on that expiry. Chains load at 8:00 AM ET each weekday.</li>
        <li><b>Session phases</b> (Sessions tab): Asia 6 PM–2 AM, London 2–8 AM, New York 8 AM–5 PM ET, each scored 0–100% for accumulation (tight, quiet balance), manipulation (sweeps the prior session's high or low and closes back inside), absorption (heavy effort, no progress) and distribution (a one-way expansion). Scored on 5-minute candles only, so every timeframe shows the same boxes. Bull/Bear comes from the close location, the swept side or the body direction.</li>
        <li><b>Market line</b>: SPY, QQQ and IWM against today's VWAP, and advancers/decliners on Nasdaq and NYSE.</li>
      </ul>
      <h3>API guide</h3>
      <ul>
        <li><code>GET /api/screen</code>: the full snapshot, every row and the market line.</li>
        <li><code>GET /api/prices</code>: the light payload the page polls every 10 seconds.</li>
        <li><code>POST /api/refresh</code>: run the screen now.</li>
        <li><code>GET /api/watchlist</code>, <code>POST /api/watchlist {"tickers":"SRTA, AUR"}</code>, <code>DELETE /api/watchlist/{ticker}</code>, <code>GET /api/watchlist/export</code>.</li>
        <li><code>GET /api/alerts</code>, <code>POST /api/alerts {"symbol","type":"ABOVE|BELOW|PCT_UP|PCT_DOWN","level","note"}</code>, <code>DELETE /api/alerts/{id}</code>.</li>
        <li><code>GET /api/analyze/{ticker}</code>: quarterly results from SEC EDGAR.</li>
        <li><code>GET /api/backtest?tickers=&amp;days=60&amp;interval=60m&amp;threshold=60&amp;shorts=true&amp;stopAtr=2&amp;trailAtr=2.5&amp;maxBars=35&amp;costBps=5</code>: Quantum Shift backtest; blank tickers means every name in the app.</li>
        <li><code>GET /api/sessions/{symbol}?tf=5m|15m|1h|4h|1d&amp;days=3</code>: Asia, London and New York session phases (scored on 5-minute candles) plus candles for the chosen timeframe.</li>
        <li><code>GET /api/technicals/{ticker}</code>: indicators on four timeframes and 180 daily closes.</li>
        <li><code>GET /api/config</code>, <code>GET /actuator/health</code>.</li>
      </ul>
      <h3>Data sources</h3>
      <p>TradingView screener (fundamentals, float, earnings dates, breadth), Yahoo Finance (candles, quotes, option chains; delayed), Zoya (compliance), SEC EDGAR (reported results). Nothing here is advice, and nothing places an order.</p>`;
  }

  // ---------- boot ----------
  showTab(store.get('tab', 'screen'));
  loadDetails();
  loadScreen().then(loadWatchlist).then(render);
  setInterval(pollPrices, 10_000);
})();
