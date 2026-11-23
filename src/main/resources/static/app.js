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
  const openChain = new Map(); // symbol -> selected expiry ('ALL' or a date) for rows with the option chain open
  const chainData = new Map(); // `${symbol}|${expiry}` -> chain view, or { loading: true }
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
  const SIGNAL = { BUY_NOW: 'Buy now', READY: 'Ready to buy', READY_SELL: 'Ready to sell', SELL_NOW: 'Sell now', HOLD: 'Hold', WAIT: 'Wait' };
  const TREND = { STRONG_UPTREND: 'Strong Uptrend', EARLY_BULLISH_REVERSAL: 'Early Bullish Reversal', EARLY_BEARISH_REVERSAL: 'Early Bearish Reversal', NO_UPTREND: 'No Uptrend' };
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
  const controls = ['search', 'signalFilter', 'stateFilter', 'sortBy', 'gexState', 'gexBias'];
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
    const q = $('#search').value.trim().toUpperCase();
    const st = $('#stateFilter').value;
    const sf = $('#signalFilter').value;
    const sigOk = (sig) => !sf || (sf === 'act' ? sig !== 'HOLD' && sig !== 'WAIT'
      : sf === 'buy' ? sig === 'BUY_NOW' || sig === 'READY'
      : sf === 'sell' ? sig === 'SELL_NOW' || sig === 'READY_SELL' : sig === sf);
    return rows.filter((r) => {
      if (r.list === 'CORE' && !sigOk(r.signal || 'WAIT')) return false;
      if (r.zoya !== 'COMPLIANT') return false;     // non-compliant names are never displayed
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
      long: (a, b) => (b.longScore || 0) - (a.longScore || 0) || (a.rank || 999) - (b.rank || 999),
      range: (a, b) => (isNum(a.rangeUsedPct) ? a.rangeUsedPct : 1e9) - (isNum(b.rangeUsedPct) ? b.rangeUsedPct : 1e9),
      rr: (a, b) => v(b.rewardRisk) - v(a.rewardRisk),
      phase: (a, b) => (a.sessionGroup || 9) - (b.sessionGroup || 9) || (b.sessionPct || 0) - (a.sessionPct || 0),
      quality: (a, b) => b.qualityScore - a.qualityScore,
      breakout: (a, b) => (isNum(a.toBreakoutPct) ? Math.max(0, a.toBreakoutPct) : 1e9) - (isNum(b.toBreakoutPct) ? Math.max(0, b.toBreakoutPct) : 1e9),
      change: (a, b) => v(b.changePct) - v(a.changePct),
      rvol: (a, b) => v(b.relVol) - v(a.relVol),
      ticker: (a, b) => a.symbol.localeCompare(b.symbol),
    }[by] || (() => 0);
    // the super stock stays on top whatever the sort
    return [...rows].sort((a, b) => (b.superStock ? 1 : 0) - (a.superStock ? 1 : 0) || cmp(a, b));
  }

  function render() {
    if (!data) return;
    const s = data.snapshot;
    const rows = s.rows || [];
    renderChips(s.filters || {});
    const exch = (s.filters?.exchanges || ['NASDAQ', 'NYSE']).map((e) => e === 'NASDAQ' ? 'Nasdaq' : e).join(' and ');
    const run = data.running ? ' · screening now…' : '';
    $('#statusLine').textContent = `${exch} · ${s.compliant ?? 0} compliant of ${s.passedFilters ?? 0} · ${etTime(s.generatedAt)} · refreshes every 15 min, 7:00 AM–4:00 PM ET trading days${run}`;
    $('#statusLine').title = `${s.screened ?? 0} names from ${s.screenSource || '—'}; ${s.passedFilters ?? 0} passed every filter. Session: ${data.session}.`;
    const note = [];
    if (s.screenNote) note.push(s.screenNote);
    if (s.chainNote) note.push(s.chainNote);
    if (!s.zoyaConfigured) note.push('Zoya is set to the API (ZOYA_SOURCE=api) but ZOYA_API_KEY is empty, so every name shows "Not checked".');
    $('#screenNote').textContent = note.join(' ');
    $('#screenNote').classList.toggle('hidden', note.length === 0);
    renderPulse(s.pulse);

    const vis = visible(rows);
    const core = sorted(vis.filter((r) => r.list === 'CORE'));
    $('#coreCount').textContent = core.length;
    const allCore = rows.filter((r) => r.list === 'CORE');
    $('#signalSummary').innerHTML = Object.keys(SIGNAL).map((k) => {
      const n = allCore.filter((r) => (r.signal || 'WAIT') === k).length;
      return n ? `<span class="sig ${k}">${SIGNAL[k]} ${n}</span>` : '';
    }).join('');

    // GEX levels: every listed name, narrowed by state / bias / side
    const gs = $('#gexState').value, gb = $('#gexBias').value;
    const gex = sorted(vis.filter((r) => r.list === 'CORE')
      .filter((r) => (!gs || (r.state || '').startsWith(gs)) && (!gb || r.bias === gb)));
    $('#gexTable').innerHTML = table(gex, false, 'No names match this state, bias, or side.');

    $('#coreTable').innerHTML = table(core, true, $('#signalFilter').value
      ? 'No names have this signal right now. Pick "Signals: all" in the toolbar to see every name.'
      : 'No names qualify yet.');

    const noUp = rows.filter((r) => r.userAdded && r.list === 'NO_UPTREND');
    const wlMissing = (data.watchlist || []).filter((t) => !rows.some((r) => r.symbol === t));
    $('#noUptrend').innerHTML = noUp.map((r) => `<div class="item"><a href="#" class="tkr" data-ses="${esc(r.symbol)}">${esc(r.symbol)}</a>
        <span class="why">${r.error ? esc(r.error) : esc(r.offReason || 'Not a Strong Uptrend or Early Bullish Reversal, so it stays off Core')}</span>
        <button class="ghost small" data-remove="${esc(r.symbol)}">Remove</button></div>`).join('')
      + wlMissing.map((t) => `<div class="item"><span class="tkr">${esc(t)}</span><span class="why">Waiting for the next screen</span>
        <button class="ghost small" data-remove="${esc(t)}">Remove</button></div>`).join('');
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
      <div class="extra">${esc(TREND[r.trendClass] || 'No Uptrend')}: ${esc(r.trendClassWhy || 'no structure')} · 50-day ${r.uptrend ? 'above' : 'at or below'} 200-day by ${signed(r.smaGapPct)} · 20 EMA ${money(r.ema20d)}</div>
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
    const lvl = (v, p, oi) => isNum(v) ? `${money(v)}<span class="pct">${isNum(oi) && oi > 0 ? big(oi) + ' OI · ' : ''}${fix(p, 0)}%</span>` : '—';
    const today = new Date().toLocaleDateString('en-CA', { timeZone: 'America/New_York' });
    const expiry = g ? `${esc(g.expiry || '—')}<span class="pct">${g.expiries > 1 ? g.expiries + ' expiries' : '1 expiry'}${g.asOf ? ' · ' + (g.asOf === today ? 'today' : '<span class="warnline">as of ' + esc(g.asOf) + '</span>') : ''}</span>` : 'No chain';
    const biasCls = r.bias === 'Bullish' ? 'bull' : r.bias === 'Bearish' ? 'bear' : 'mixed';
    const book = g ? `book ${big(g.totalOi)} · calls ${big(g.callOi)} · puts ${big(g.putOi)} · L/S ${fix(g.callPutRatio)}` : 'book – · calls – · puts – · L/S –';
    return `<tr data-symbol="${esc(r.symbol)}" class="tc ${esc(r.trendClass || 'NO_UPTREND')}${r.superStock ? ' superrow' : ''}" title="${esc(TREND[r.trendClass] || 'No Uptrend')}">
      <td>${esc(r.exchange || '')}</td>
      <td><div><a href="#" class="tkr" data-ses="${esc(r.symbol)}" title="Open the session analysis for ${esc(r.symbol)}">${esc(r.symbol)}</a>${r.userAdded ? '<span class="added">You added</span>' : ''}</div>
        <div class="cname">${esc(r.name || '')}</div>
        <div>${r.side === 'Long' ? '<span class="tag long" title="+DI above −DI">Long</span>' : '<span class="tag flat" title="−DI above +DI: no long edge yet">Flat</span>'}${q}</div>
        <div><span class="tcbadge">${esc(TREND[r.trendClass] || 'No Uptrend')}</span></div>
        ${r.list === 'CORE' ? `<div class="sigline"><span class="sig ${esc(r.signal || 'WAIT')}">${esc(SIGNAL[r.signal] || 'Wait')}</span>${(r.signalNotes || []).length ? `<span class="signotes">${esc(r.signalNotes.join(' · '))}</span>` : ''}</div>` : ''}
        ${r.signal === 'WAIT' && isNum(r.expBuy) ? `<div class="expected" title="Expected buy at the breakout level (premarket or prior-day high); stop at today's low or the put wall; target buy + 1 ATR, capped at the call wall">Expected: <b>buy ${money(r.expBuy)}</b> · stop ${money(r.expStop)} · target ${money(r.expTarget)}</div>` : ''}
        ${r.superStock ? '<div class="superbadge">★ Super stock · lowest risk, highest reward</div>' : ''}
        ${r.list === 'CORE' ? `<div class="setup"><span class="lscore" title="${esc((r.setupParts || []).join(' · '))}">#${r.rank} · Setup ${r.setupScore}</span>
          <span title="${esc((r.longScoreParts || []).join(' · '))}">Long ${r.longScore}</span>
          ${isNum(r.rewardRisk) ? `<span title="Risk to ${money(r.setupStop)} (today's low or the put wall), target ${money(r.setupTarget)} (low + 1 ATR, or the call wall)">R:R <b class="${r.rewardRisk >= 2 ? 'up' : r.rewardRisk < 1 ? 'down' : ''}">${fix(r.rewardRisk, 1)}</b> · risk ${fix(r.riskPct, 1)}% · reward ${fix(r.rewardPct, 1)}%</span>` : ''}</div>` : ''}
        <div class="sphase ${esc((r.sessionPhase || '').split(' ')[0])}" data-ses="${esc(r.symbol)}" title="${esc(r.sessionScores || '')} · click for the session analysis">${r.sessionPhase
          ? `${esc(r.sessionName)}${r.sessionLive ? ' (live)' : ''} · <b>${r.sessionPct}% ${esc(r.sessionPhase)}</b>`
          : 'Session phase —'}</div>
        <div class="readings" title="Daily readings from completed candles. They are readings, not filters.">
          <span><small>RSI</small>${fix(r.rsi, 0)}${r.rsi < 30 ? ' <i>washed</i>' : r.rsi > 70 ? ' <i>stretched</i>' : ''}</span>
          <span><small>MACD</small><b class="${r.macdUp ? 'up' : 'down'}">${r.macdUp ? 'up' : 'down'}</b></span>
          <span><small>ADX</small>${fix(r.adx, 0)} <b class="${r.adxRising ? 'up' : 'down'}">${r.adxRising ? '↑' : '↓'}</b></span>
          <span><small>Stoch</small>${fix(r.stoch, 0)}</span>
          <span><small>Band</small>${fix(r.bandPct, 0)}%</span>
        </div>
        <div class="meta">${meta}</div>
        <div class="meta">${earn}</div>
        <button class="ghost small datatoggle" data-toggle="${esc(r.symbol)}">${open ? 'Hide data' : 'Data'}</button>
        ${withActions ? `<button class="ghost small datatoggle" data-chain="${esc(r.symbol)}" aria-expanded="${openChain.has(r.symbol)}">${openChain.has(r.symbol) ? 'Hide chain' : 'Chain'}</button>` : ''}
        ${dataBlock}</td>
      <td><a class="zoya ${esc(r.zoya)}" href="https://zoya.finance/stocks/${encodeURIComponent(String(r.symbol).toLowerCase())}" target="_blank" rel="noopener" title="Open on Zoya">${esc(zoyaLabel[r.zoya] || r.zoya)}</a><div class="meta">${r.zoyaCheckedAt ? 'Last scanned ' + esc(etStamp(r.zoyaCheckedAt)) : ''}</div></td>
      <td class="num"><b data-f="price">${money(r.price)}</b></td>
      <td class="num">${meter}</td>
      <td><span class="state ${esc(dir)}">${esc(r.state || '—')}</span></td>
      <td class="num"><span class="${r.changePct >= 0 ? 'up' : 'down'}">${signed(r.changePct)}</span><div class="meta">vs close</div></td>
      <td class="num">${g ? lvl(g.callWall, g.callWallPct, g.callWallOi) : '—'}</td>
      <td class="num">${g ? lvl(g.king, g.kingPct) : '—'}</td>
      <td class="num">${g ? lvl(g.putWall, g.putWallPct, g.putWallOi) : '—'}</td>
      <td class="num">${expiry}</td>
      <td><span class="tag ${biasCls}">${esc(r.bias || '—')}</span>
        <div class="arrows">15m ${arrow(r.trend15m)} · 1h ${arrow(r.trend1h)} · 4h ${arrow(r.trend4h)}</div>
        <div class="book">${book}</div></td>
      ${withActions ? `<td>${r.userAdded ? `<button class="ghost small" data-remove="${esc(r.symbol)}">Remove</button>` : ''}
        <div><a href="#" class="link small" data-tech="${esc(r.symbol)}">Chart</a></div></td>` : ''}
    </tr>${withActions && openChain.has(r.symbol) ? `<tr class="chainrow"><td colspan="${HEAD.length + 1}">${chainHtml(r.symbol)}</td></tr>` : ''}`;
  }

  // ---------- option chain (inline under a row) ----------
  async function loadChain(sym, exp) {
    const key = `${sym}|${exp}`;
    if (chainData.has(key) && !chainData.get(key).loading) return;
    chainData.set(key, { loading: true });
    try { chainData.set(key, await api(`api/chain/${encodeURIComponent(sym)}?expiry=${encodeURIComponent(exp)}`)); }
    catch (err) { chainData.set(key, { error: err.message }); }
    render();
  }

  function chainHtml(sym) {
    const exp = openChain.get(sym) || 'ALL';
    const d = chainData.get(`${sym}|${exp}`);
    if (!d || d.loading) return '<div class="chainpanel"><p class="muted">Reading the option chain…</p></div>';
    if (d.error) return `<div class="chainpanel"><p class="msg err">${esc(d.error)}</p><p class="meta">GET /api/chains/status has the details.</p></div>`;
    const g = d.levels || {};
    const spot = d.spot;
    const strikes = d.strikes || [];
    // keep the 30 strikes nearest the price
    const near = strikes.slice().sort((a, b) => Math.abs(a.strike - spot) - Math.abs(b.strike - spot)).slice(0, 30).sort((a, b) => b.strike - a.strike);
    const max = Math.max(1, ...near.map((k) => Math.max(k.callOi, k.putOi)));
    const tags = (k) => [k.strike === g.callWall ? '<span class="ctag call">Call wall</span>' : '', k.strike === g.putWall ? '<span class="ctag put">Put wall</span>' : '',
      k.strike === g.king ? '<span class="ctag king">King</span>' : ''].join('');
    let spotDone = false;
    const rows = near.map((k) => {
      let line = '';
      if (!spotDone && k.strike < spot) { spotDone = true; line = `<div class="cspot" role="presentation"><span>Price ${money(spot)}</span></div>`; }
      return line + `<div class="crow${k.strike === g.callWall || k.strike === g.putWall || k.strike === g.king ? ' key' : ''}">
        <div class="cput"><span class="cnum">${k.putOi ? big(k.putOi) : ''}</span><i style="width:${(k.putOi / max * 100).toFixed(1)}%"></i></div>
        <div class="cstrike">${money(k.strike)}${tags(k)}</div>
        <div class="ccall"><i style="width:${(k.callOi / max * 100).toFixed(1)}%"></i><span class="cnum">${k.callOi ? big(k.callOi) : ''}</span></div>
        <div class="cmeta">IV ${isNum(k.callIv) ? (k.callIv * 100).toFixed(0) + '%' : '—'} / ${isNum(k.putIv) ? (k.putIv * 100).toFixed(0) + '%' : '—'} · γ ${signedBig(k.netGex)}</div>
      </div>`;
    }).join('') + (spotDone ? '' : `<div class="cspot"><span>Price ${money(spot)}</span></div>`);
    const chips = [['ALL', 'All ≤45 days']].concat((d.expiries || []).map((e) => [e.date, `${e.date} · ${big(e.oi)}`]))
      .map(([v, label]) => `<button type="button" class="chip pvchip${d.selected === v ? ' on' : ''}" data-chain-exp="${esc(sym)}|${esc(v)}">${esc(label)}</button>`).join('');
    return `<div class="chainpanel">
      <div class="chainhead"><b>${esc(sym)} option chain</b><span class="meta">${esc(d.source || '')} · read ${esc(etStamp(d.readAt))} · open interest by strike, puts left, calls right</span></div>
      <div class="chips pvchips">${chips}</div>
      <div class="chainsum">Call wall <b>${money(g.callWall)}</b> (${big(g.callWallOi)} OI) · King <b>${money(g.king)}</b> · Put wall <b>${money(g.putWall)}</b> (${big(g.putWallOi)} OI) · calls ${big(g.callOi)} / puts ${big(g.putOi)} · C/P ${fix(g.callPutRatio)}</div>
      <div class="chainhdr"><span>Put OI</span><span>Strike</span><span>Call OI</span><span></span></div>
      <div class="chaingrid">${rows || '<p class="muted">No open interest near the price.</p>'}</div>
    </div>`;
  }

  const signedBig = (v) => !isNum(v) ? '—' : (v < 0 ? '−' : '+') + big(Math.abs(v));


  // row actions
  document.addEventListener('click', async (e) => {
    const ch = e.target.closest('[data-chain]');
    if (ch) {
      const s = ch.dataset.chain;
      if (openChain.has(s)) openChain.delete(s); else { openChain.set(s, 'ALL'); loadChain(s, 'ALL'); }
      render();
      return;
    }
    const ce = e.target.closest('[data-chain-exp]');
    if (ce) {
      const [s, exp] = ce.dataset.chainExp.split('|');
      openChain.set(s, exp);
      loadChain(s, exp);
      render();
      return;
    }
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
    const sesLink = e.target.closest('[data-ses]');
    if (sesLink) {
      e.preventDefault();
      openSessions(sesLink.dataset.ses);
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
      handlePvAlerts(p.pvAlerts || []);
      handleBias(p.bias);
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



  // ---------- market bias ----------
  let biasData = null;
  async function loadBias(refresh = false) {
    try { biasData = await api('api/bias' + (refresh ? '?refresh=true' : '')); renderBias(); }
    catch (err) { $('#biasBody').innerHTML = `<p class="msg err">${esc(err.message)}</p>`; }
  }

  function renderBias() {
    const d = biasData;
    if (!d) return;
    const r = d.today || d.preview;
    const live = !!d.today;
    $('#biasStamp').textContent = live ? `Called ${etStamp(r.at)}${r.late ? ' (late: the app started after 9:30)' : ''}` : `Preview ${r ? etStamp(r.at) : ''} · the call is made at 9:30 AM ET`;
    if (!r) { $('#biasBody').innerHTML = '<p class="muted">No reading yet.</p>'; return; }
    const cls = r.bias === 'Bullish' ? 'up' : r.bias === 'Bearish' ? 'down' : 'mixed';
    const factors = (r.factors || []).map((f) => `<tr><td>${esc(f.name)}</td><td>${esc(f.reading)}</td>
      <td class="num"><b class="${f.points > 0 ? 'up' : f.points < 0 ? 'down' : 'muted'}">${f.points > 0 ? '+' : ''}${f.points}</b><span class="muted"> / ±${f.max}</span></td></tr>`).join('');
    const acc = d.accuracy || {};
    const hist = (d.history || []).slice(0, 10).map((h) => `<tr><td>${esc(h.date)}</td>
      <td><span class="biasmini ${h.bias === 'Bullish' ? 'up' : h.bias === 'Bearish' ? 'down' : 'mixed'}">${esc(h.bias)}</span> <span class="muted">${h.score > 0 ? '+' : ''}${h.score}</span></td>
      <td class="num">${isNum(h.spyChangePct) ? signed(h.spyChangePct) : '<span class="muted">—</span>'}</td>
      <td>${h.correct === true ? '<span class="up">✓ right</span>' : h.correct === false ? '<span class="down">✗ wrong</span>' : '<span class="muted">—</span>'}</td></tr>`).join('');
    const channels = (d.channels || []).length ? `Phone alerts: ${esc(d.channels.join(', '))}` : 'Phone alerts: off (set SCREENER_NTFY_TOPIC, or SCREENER_TELEGRAM_TOKEN and SCREENER_TELEGRAM_CHAT_ID)';
    $('#biasBody').innerHTML = `
      <div class="biasbig ${cls}"><span class="biaslabel">${esc(r.bias.toUpperCase())}</span>
        <span class="biassub">${esc(r.summary)}${live ? '' : ' · preview'}</span></div>
      ${intradayHtml(d, r)}
      <div class="biasgrid">
        <div class="tablewrap"><table class="grid"><thead><tr><th>Factor</th><th>Reading</th><th class="num">Points</th></tr></thead><tbody>${factors}</tbody></table></div>
        <div>
          <div class="muted small">Last calls · SPY open to close${isNum(acc.pct) ? ` · right ${acc.right} of ${acc.judged} (${acc.pct}%)` : ''}</div>
          <div class="tablewrap"><table class="grid"><thead><tr><th>Date</th><th>Call</th><th class="num">SPY</th><th>Result</th></tr></thead>
            <tbody>${hist || '<tr class="empty"><td colspan="4">The first call is made at 9:30 AM ET on the next trading day.</td></tr>'}</tbody></table></div>
          <p class="meta">${channels}. It is a read of the open, not a forecast; Neutral days are not scored.</p>
        </div>
      </div>`;
  }

  function intradayHtml(d, r) {
    const list = (d.today && d.today.intraday) || [];
    const now = d.intradayNow;
    if (!list.length && !now) return '<p class="meta">Hourly session readings start at 10:00 AM ET.</p>';
    const pill = (x, label, extra = '') => {
      const c = x.bias === 'Bullish' ? 'up' : x.bias === 'Bearish' ? 'down' : 'mixed';
      return `<div class="ibpill ${c}${x.flippedFrom ? ' flip' : ''}" title="${esc(x.summary)}${x.flippedFrom ? ' · flipped from ' + esc(x.flippedFrom) : ''}">
        <span class="t">${esc(label)}</span><b>${esc(x.bias)}</b><span class="s">${x.score > 0 ? '+' : ''}${x.score}</span>${x.flippedFrom ? '<span class="f">flip</span>' : ''}${extra}</div>`;
    };
    const latest = now || list[list.length - 1];
    const rows = (latest.factors || []).map((f) => `<tr><td>${esc(f.name)}</td><td>${esc(f.reading)}</td>
      <td class="num"><b class="${f.points > 0 ? 'up' : f.points < 0 ? 'down' : 'muted'}">${f.points > 0 ? '+' : ''}${f.points}</b><span class="muted"> / ±${f.max}</span></td></tr>`).join('');
    return `<div class="intraday">
      <div class="muted small">During the session · on the hour, 10:00 AM–4:00 PM ET · a phone alert when it flips</div>
      <div class="ibline">${pill(r, '9:30')}${list.map((x) => pill(x, x.time)).join('')}${now ? pill(now, 'now') : ''}</div>
      <details class="ibdetails"><summary>${esc(now ? 'Now' : latest.time)}: ${esc(latest.summary)} — factors</summary>
        <div class="tablewrap"><table class="grid"><thead><tr><th>Factor</th><th>Reading</th><th class="num">Points</th></tr></thead><tbody>${rows}</tbody></table></div></details>
    </div>`;
  }

  $('#biasRefresh').addEventListener('click', () => { $('#biasBody').insertAdjacentHTML('afterbegin', '<p class="muted small">Checking…</p>'); loadBias(true); });
  $('#biasTest').addEventListener('click', async () => {
    try { const r = await api('api/bias/test', { method: 'POST' }); toast(r.result.join(' · ')); } catch (err) { toast(err.message); }
  });

  function handleBias(b) {
    if (!b) return;
    const seen = store.get('seenBias', '');
    if (seen === b.id) return;
    store.set('seenBias', b.id);
    toast(`${b.title} — ${b.summary}`);
    try { if ('Notification' in window && Notification.permission === 'granted') new Notification(b.title, { body: b.summary }); } catch { /* ignore */ }
    loadBias();
  }

  loadBias();
  setInterval(() => loadBias(), 5 * 60_000);

  // ---------- PV Confirm ----------
  const PV_LABEL = { READY_TO_BUY: 'READY TO BUY', BUY: 'BUY', IN_TRADE: 'IN TRADE', SELL: 'SELL', WAIT: 'WAIT', NA_DOWNTREND: 'N/A – Downtrend' };
  const PV_CHIPS = [['ALL', 'All'], ['READY_TO_BUY', 'Ready to Buy'], ['BUY', 'Buy'], ['IN_TRADE', 'In Trade'], ['SELL', 'Sell'], ['NEAR', 'Near Setup']];
  const PV_ORDER = { BUY: 0, READY_TO_BUY: 1, IN_TRADE: 2, SELL: 3, WAIT: 4, NA_DOWNTREND: 5 };
  let pv = null;
  let pvChip = store.get('pvChip', 'ALL');

  async function loadPv() {
    try { pv = await api('api/pv'); renderPv(); } catch { /* keep the last view */ }
  }

  function pvChipMatch(r, chip) {
    if (chip === 'ALL') return true;
    if (chip === 'NEAR') return r.nearSetup && r.status !== 'IN_TRADE' && r.status !== 'SELL' && r.status !== 'BUY';
    return r.status === chip;
  }

  function renderPv() {
    if (!pv) return;
    const st = pv.state || {};
    const rows = st.rows || [];
    if (pv.settings && !document.querySelector('.pvsettings').open) fillPvForm(pv.settings);
    $('#pvStamp').textContent = st.generatedAt ? `Bar ${st.barDate || '—'} · ${st.dayComplete ? 'daily bar complete' : 'today still forming'} · run ${etStamp(st.generatedAt)}${pv.running ? ' · running…' : ''}` : (pv.running ? 'Running…' : 'Waiting for the first run');
    $('#pvSpy').innerHTML = isNum(st.spyClose)
      ? `SPY ${money(st.spyClose)} vs SMA200 ${money(st.spySma200)}: <b class="${st.spyAbove ? 'up' : 'down'}">${st.spyAbove ? 'above, the market rule passes' : 'below, no new BUY signals'}</b>${pv.settings && !pv.settings.spyFilter ? ' <span class="muted">(SPY rule switched off)</span>' : ''}`
      : '';
    const up = rows.filter((r) => r.section === 'UPTREND');
    const down = rows.filter((r) => r.section !== 'UPTREND');
    $('#pvUpCount').textContent = up.length;
    $('#pvDownCount').textContent = down.length;
    $('#pvChips').innerHTML = PV_CHIPS.map(([k, label]) => {
      const n = up.filter((r) => pvChipMatch(r, k)).length;
      return `<button type="button" role="tab" class="chip pvchip ${k}${pvChip === k ? ' on' : ''}" aria-selected="${pvChip === k}" data-pvchip="${k}">${label} <span class="n">${n}</span></button>`;
    }).join('');
    const sortRows = (list) => list.slice().sort((a, b) => (PV_ORDER[a.status] ?? 9) - (PV_ORDER[b.status] ?? 9)
      || (b.nearSetup ? 1 : 0) - (a.nearSetup ? 1 : 0) || (a.rsi2 ?? 999) - (b.rsi2 ?? 999));
    const upShown = sortRows(up.filter((r) => pvChipMatch(r, pvChip)));
    $('#pvUpTable').innerHTML = pvTable(upShown, true, pvChip === 'ALL' ? 'No uptrend names yet.' : 'No uptrend names in this group right now.');
    $('#pvDownTable').innerHTML = pvTable(sortRows(down), false, 'No downtrend names.');
  }

  function pvTable(rows, upSection, empty) {
    const head = '<thead><tr><th>Stock</th><th class="num">Price</th><th>PV Status</th><th class="num">Entry</th><th class="num">Stop</th><th class="num">Target</th><th class="num">RSI(2)</th><th class="num">Volume vs Avg</th><th>Rules Passed</th></tr></thead>';
    if (!rows.length) return head + `<tbody><tr class="empty"><td colspan="9">${esc(empty)}</td></tr></tbody>`;
    const set = pv.settings || {};
    return head + '<tbody>' + rows.map((r) => {
      const na = r.status === 'NA_DOWNTREND';
      const ru = r.rules || {};
      const rule = (ok, label) => `<li class="${ok ? 'ok' : 'no'}">${ok ? '✓' : '✗'} ${esc(label)}</li>`;
      const rulesCell = na ? '<span class="muted">—</span>' : `<b>${r.rulesPassed ?? 0}/4</b><ul class="pvrules">
          ${rule(ru.closeAboveSma, 'Close > SMA' + (set.smaLength || 200))}
          ${rule(ru.spyAboveSma, 'SPY > SMA200')}
          ${rule(ru.rsiBelow, `RSI(${set.rsiLength || 2}) < ${set.rsiBuyBelow ?? 10}`)}
          ${rule(ru.volBelowAvg, `Vol < ${set.volMaxPct ?? 100}% of ${set.volLength || 50}d avg`)}</ul>`;
      const status = `<span class="pvst ${esc(r.status)}">${esc(PV_LABEL[r.status] || r.status)}</span>${r.exitReason ? `<div class="pvreason">${esc(r.exitReason)}${isNum(r.exit) ? ' at ' + money(r.exit) : ''}</div>` : ''}${r.nearSetup && r.status === 'WAIT' ? '<div class="pvreason near">Near setup</div>' : ''}`;
      const ex = r.expected;
      const pctFrom = (a, b) => isNum(a) && isNum(b) && b > 0 ? ((a / b - 1) * 100) : NaN;
      let entry, stop, target;
      if (isNum(r.entry)) {
        entry = `${money(r.entry)}<span class="pct">${r.entryEstimated ? 'est. next open' : esc(r.entryDate || '')}${isNum(r.barsHeld) ? ' · bar ' + r.barsHeld : ''}</span>`;
        stop = isNum(r.stop) ? `${money(r.stop)}<span class="pct">${signed(pctFrom(r.stop, r.entry), 1)}</span>` : '—';
        target = r.status === 'SELL' ? '—' : `&gt; ${money(r.entry)}<span class="pct">first close above entry</span>`;
      } else if (ex && !na) {
        entry = `<span class="expect">${money(ex.buy)}</span><span class="pct">${ex.dropPct > 0 ? 'expected buy: a close ≤ this (' + signed(-ex.dropPct, 1) + ')' : 'expected buy: last close (RSI already under the level)'}</span>`;
        stop = `<span class="expect">${money(ex.stop)}</span><span class="pct">expected · ${signed(pctFrom(ex.stop, ex.buy), 1)}</span>`;
        target = `<span class="expect">${money(ex.target)}</span><span class="pct">expected · ${signed(pctFrom(ex.target, ex.buy), 1)}</span>`;
      } else { entry = stop = target = '—'; }
      const vol = isNum(r.volPct) ? `<span class="${r.volPct < (set.volMaxPct ?? 100) ? 'up' : ''}">${r.volPct.toFixed(0)}%</span>` : '—';
      const rsi = isNum(r.rsi2) ? `<span class="${r.rsi2 < (set.rsiBuyBelow ?? 10) ? 'up' : r.rsi2 < (set.rsiReadyBelow ?? 20) ? 'warnline' : ''}">${r.rsi2.toFixed(1)}</span>` : '—';
      return `<tr class="pvrow ${esc(r.status)}">
        <td><a href="#" class="tkr" data-ses="${esc(r.symbol)}">${esc(r.symbol)}</a>${r.onScreen ? '' : ' <span class="added">Off screen</span>'}
          <div class="cname">${esc(r.name || '')}</div>
          ${r.error ? `<div class="meta warnline">${esc(r.error)}</div>`
            : r.expected && !isNum(r.entry) && r.status !== 'READY_TO_BUY' ? `<div class="meta">Expected BUY also needs: ${esc(r.expected.needs.join('; '))}</div>`
            : r.note ? `<div class="meta">${esc(r.note)}</div>` : ''}</td>
        <td class="num">${money(r.price)}</td>
        <td>${status}</td>
        <td class="num">${entry}</td>
        <td class="num">${stop}</td>
        <td class="num">${target}</td>
        <td class="num">${rsi}</td>
        <td class="num">${vol}</td>
        <td>${rulesCell}</td>
      </tr>`;
    }).join('') + '</tbody>';
  }

  document.addEventListener('click', (e) => {
    const c = e.target.closest('[data-pvchip]');
    if (!c) return;
    pvChip = c.dataset.pvchip;
    store.set('pvChip', pvChip);
    renderPv();
  });

  $('#pvRefresh').addEventListener('click', async () => {
    try { await api('api/pv/refresh', { method: 'POST' }); toast('PV Confirm is re-running.'); setTimeout(loadPv, 4000); } catch (err) { toast(err.message); }
  });

  const PV_DEFAULTS = { rsiLength: 2, rsiBuyBelow: 10, rsiReadyBelow: 20, smaLength: 200, volLength: 50, volMaxPct: 100, atrLength: 14, atrMult: 2.5, maxBars: 10, spyFilter: true, alerts: true };
  function fillPvForm(sv) {
    const f = $('#pvSettingsForm');
    Object.entries(sv).forEach(([k, v]) => { const el = f.elements[k]; if (!el) return; if (el.type === 'checkbox') el.checked = !!v; else el.value = v; });
  }
  $('#pvDefaults').addEventListener('click', () => fillPvForm(PV_DEFAULTS));
  $('#pvSettingsForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target, body = {};
    Object.keys(PV_DEFAULTS).forEach((k) => { const el = f.elements[k]; body[k] = el.type === 'checkbox' ? el.checked : Number(el.value); });
    const msg = $('#pvSettingsMsg');
    try {
      await api('api/pv/settings', { method: 'PUT', body: JSON.stringify(body) });
      msg.className = 'msg ok'; msg.textContent = 'Saved. Re-running…';
      setTimeout(loadPv, 4000);
    } catch (err) { msg.className = 'msg err'; msg.textContent = err.message; }
  });

  const seenPv = new Set(store.get('seenPv', []));
  function handlePvAlerts(list) {
    const fresh = list.filter((a) => !seenPv.has(a.id));
    if (!fresh.length) return;
    fresh.forEach((a) => {
      seenPv.add(a.id);
      const msg = `PV Confirm ${a.symbol}: ${PV_LABEL[a.status] || a.status}${a.reason ? ' — ' + a.reason : ''}`;
      toast(msg);
      try { if ('Notification' in window && Notification.permission === 'granted') new Notification('PV Confirm', { body: msg }); } catch { /* ignore */ }
    });
    store.set('seenPv', [...seenPv].slice(-300));
    const b = $('#alertBadge');
    b.textContent = fresh.length;
    b.classList.remove('hidden');
    loadPv();
  }

  async function loadPvAlerts() {
    try {
      const list = await api('api/pv/alerts');
      $('#pvAlertTable').innerHTML = '<thead><tr><th>Date</th><th>Ticker</th><th>Signal</th><th class="num">Price</th><th class="num">Entry</th><th class="num">Stop</th><th>Why</th></tr></thead><tbody>'
        + (list.map((a) => `<tr><td>${esc(etStamp(a.at))}</td><td><a href="#" class="tkr" data-ses="${esc(a.symbol)}">${esc(a.symbol)}</a></td>
          <td><span class="pvst ${esc(a.status)}">${esc(PV_LABEL[a.status] || a.status)}</span></td><td class="num">${money(a.price)}</td>
          <td class="num">${money(a.entry)}</td><td class="num">${money(a.stop)}</td><td class="meta">${esc(a.reason || '')}</td></tr>`).join('')
          || '<tr class="empty"><td colspan="7">No PV Confirm alerts yet. They go out at 4:15 PM ET on trading days.</td></tr>') + '</tbody>';
    } catch { /* ignore */ }
  }

  loadPv();
  setInterval(loadPv, 60_000);

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
    loadPvAlerts();
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
      <p class="extra">${side(d.longs, 'Longs')} · long only</p>
      <h3>By stock</h3>
      <div class="tablewrap"><table class="grid"><thead><tr><th>Stock</th><th class="num">Trades</th><th class="num">Win rate</th><th class="num">Avg trade</th><th class="num">Avg R</th><th class="num">Strategy</th><th class="num">Longs</th><th class="num">Max DD</th><th class="num">Buy &amp; hold</th><th class="num">Energy now</th><th>Regime</th><th>Last shift</th></tr></thead><tbody>
      ${d.stocks.map((r) => r.error ? `<tr><td class="tkr">${esc(r.symbol)}</td><td colspan="12" class="muted">${esc(r.error)}</td></tr>` : `<tr>
        <td class="tkr">${esc(r.symbol)}</td><td class="num">${r.trades}</td><td class="num">${isNum(r.winRate) ? r.winRate + '%' : '—'}</td>
        <td class="num">${pc(r.avgReturnPct)}</td><td class="num">${fix(r.avgR)}</td><td class="num"><b>${pc(r.totalReturnPct)}</b></td>
        <td class="num">${pc(r.longReturnPct)}</td>
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

  /** Opens the Sessions tab on a ticker and loads it straight away. */
  function openSessions(sym) {
    $('#sesInput').value = sym;
    showTab('sessions');
    window.scrollTo({ top: 0 });
    loadSessions();
  }

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
      <p>Every 15 minutes on trading days from 7:00 AM to 4:00 PM ET (7:00, 7:15, … 4:00), the app asks TradingView's screener for Nasdaq and NYSE common stocks that pass price, market cap, debt/equity and revenue growth. It then checks target upside, debt/assets, debt/capital and cash/debt itself from the same columns. Names you add are screened every run whether or not they pass.</p>
      <h3>2. Compliance</h3>
      <p>Each name is looked up on its Zoya page (for example zoya.finance/stocks/mitk, which reads "MITK stock is Shariah-compliant") and the result is cached for 24 hours, so Zoya is asked about each ticker at most once a day. The cache is saved to disk and survives restarts. A ticker Zoya doesn't cover reads "Not covered"; a failed lookup reads "Not checked" and is retried after 15 minutes. Only compliant names are ever displayed: a name Zoya marks not compliant, questionable or not covered never appears in Core, GEX levels or PV Confirm. A name you added yourself shows under Core with the reason.</p>
      <h3>3. Quality</h3><p>${esc(qualityText)} Each clip is worth 20 points, with the score fading the further outside the clip a value sits (zero at ten times away). A missing quote scores the spread clip at half.</p>
      <h3>4. The lists</h3>
      <ul>
        <li><b>Core</b>: long only. Every name that passes the screen, is Zoya compliant, and is in a Strong Uptrend or Early Bullish Reversal, with no limit on how many, sorted by the <b>setup score</b> (0–100, low risk and high reward), re-ranked on every price tick:
          <b>range meter</b> 25 (0% of the ATR used scores 25, 100% or more scores 0);
          <b>reward to risk</b> 25 (risk from the price to support, the higher of today's low and the put wall, at least ¼ ATR; reward up to today's low + 1 ATR, capped at the call wall; 3:1 or better scores 25);
          <b>session phase</b> 20 (the latest pre-market or New York session from the Sessions indicator: Bull Distribution 20, Bull Absorption 16, Bear Absorption 12, Bull Manipulation 10, Bull Accumulation 8, times its percent);
          <b>long score</b> 30 (the long score × 0.3).
          Names are grouped by signal first (Buy now, Ready to buy, Ready to sell, Sell now, Hold, Wait), then by the setup score. A Wait shows the expected buy (the breakout level), its stop (today's low or the put wall, at least ¼ ATR under) and target (buy + 1 ATR, capped at the call wall). The <b>super stock</b> is the best Buy now name (or Ready to buy when there is no Buy now). It stays first whatever the sort.</li>
        <li><b>Signals</b>. Sell signals are about leaving a long and are checked first.
          <b>Sell now</b>: an Early Bearish Reversal below the 1h 20 EMA with a bias that is not Bullish; or the price at or above the call wall; or 100% of the ATR range used into a Bear session of 60% or more.
          <b>Ready to sell</b>: an Early Bearish Reversal; below the 1h 20 EMA with a Bearish bias; 100% of the ATR range used; earnings inside 3 trading days; or a Bear Distribution or Bear Manipulation session of 70% or more.
          <b>Buy now</b>: Strong Uptrend or Early Bullish Reversal, Zoya compliant, bias not Bearish, through the breakout level, above the 1h 20 EMA, the latest session a Bull phase, reward to risk 1.5 or better and under 80% of the range used.
          <b>Ready to buy</b>: the same trend, compliance and bias, reward to risk 1 or better, and above the 1h 20 EMA or within 3% of the breakout.
          <b>Hold</b>: a Strong Uptrend or Early Bullish Reversal still intact but not a fresh entry. <b>Wait</b>: anything else. The note next to each signal says why, or what a Buy now still needs.
          The long score (0–100) is: trend class (Strong Uptrend 35, Early Bullish Reversal 25, Early Bearish Reversal 5), 15m/1h/4h bias (Bullish 10, Mixed 5), above the 1h 20 EMA 5, +DI over −DI 10, MACD up 5, RSI 50–70 5 (40–50 or 70–80: 2), quality score ÷ 10, relative volume 1× or more 5, Zoya compliant 5, room to the call wall 10 (5 if under it by less than 1 ATR), and −15 with earnings inside 3 trading days. Ties go to the wider 50-day-over-200-day gap. Names you added without an uptrend sit underneath.</li>
      </ul>
      <h3>5. Readings on each row</h3>
      <ul>
        <li><b>Long / Flat</b>: Long when the daily +DI is above −DI, Flat otherwise. The app is long only: there is no short side anywhere, and the Quantum Shift backtest takes longs only.</li>
        <li><b>Range meter</b>: today's high-low range against the 14-day ATR, how much of it is used and what is left. RVol is today's volume against the 10-day and 30-day average.</li>
        <li><b>Current state</b>: initiation is a move of 0.6 ATR or more on 1.3× volume; accumulation (up) or distribution (down) is a move of 0.45 ATR or more, or volume of 1× with a close in the third of the range that agrees; anything smaller is manipulation.</li>
        <li><b>To breakout</b>: the distance to the higher of today's premarket high and the prior day's high.</li>
        <li><b>Bias</b>: close against a rising or falling 20 EMA on completed 15-minute, 1-hour and 4-hour candles. More up than down is Bullish, more down is Bearish, otherwise Mixed.</li>
        <li><b>Trend class</b> (the row colour): from completed daily candles, using the 20 EMA and the 50- and 200-day averages only. <b>Strong Uptrend</b>: 50-day above the 200-day, close above the 50-day and the 20 EMA. <b>Early Bullish Reversal</b>: the 50-day crossed above the 200-day in the last 15 sessions, or the close reclaimed the 50-day and a rising 20 EMA while the 50-day is still below the 200-day. <b>Early Bearish Reversal</b>: 50-day above the 200-day but the close lost the 50-day, or sits under a falling 20 EMA. <b>No Uptrend</b>: anything else; fewer than 200 daily candles means no structure, so it stays No Uptrend.</li>
        <li><b>Market bias</b> (card at the top): at 9:30 AM ET each trading day the app calls the day Bullish, Bearish or Neutral and alerts in the page and, when set up, on your phone (Telegram or ntfy). Points: S&amp;P futures overnight from the prior 4:00 PM (±2 beyond 0.3%, ±1 beyond 0.1%), Nasdaq futures overnight (±1 beyond 0.3%), SPY's premarket gap (±1 beyond 0.25%), SPY against its 20-day EMA (±1), SPY's regime (+1 above its SMA200 with SMA50 over SMA200, −1 below its SMA200), SPY's 5-day change (±1 beyond 1%), VIX (−1 above 25 or 10% over its 10-day average; +1 under 16 and falling), and the ES London and Asia session phases from the Sessions indicator (±1 for a Bull or Bear phase of 60% or more). +2 or more is Bullish, −2 or less Bearish, else Neutral with its lean; 6+ is strong, 4–5 moderate. After the close it records SPY's open-to-close move and whether the call was right. Before 9:30 the card shows a preview.
          <b>During the session</b>, on the hour from 10:00 AM to 4:00 PM ET, it reads the tape again (±10): SPY against its opening range, the first 30 minutes (±2); SPY, QQQ and IWM against today's VWAP (±1 each); SPY from the open beyond 0.3% (±1); SPY's 15-minute close against a rising or falling 20 EMA (±1); breadth on Nasdaq + NYSE, 1.5 up per down or better / 0.67 or worse (±1); VIX against its own open beyond 5% (±1); and the ES New York session phase (±1). The 9:30 call stays as it was; the hourly readings show as a timeline on the card, and the phone alert goes out only when the bias flips.</li>
        <li><b>PV Confirm Signals</b>: nested under the trend check (SMA50 above SMA200, unchanged), on daily bars, long only.
          <b>Uptrend</b>: the four rules are close above SMA200, SPY above its SMA200, RSI(2) under 10 and volume under its 50-day average (the average includes the day). All four on a completed bar is a <b>BUY</b>; the trade enters at the next open, with the stop at entry − 2.5 × ATR(14) of the signal bar. <b>IN TRADE</b> until the first exit, checked on each completed bar from the entry bar on: <b>STOP</b> when the low touches the stop (filled at the stop, or the open on a gap below), <b>TAKE PROFIT</b> on the first close above the entry, <b>TIME EXIT</b> at the close of the 10th bar. The bar it exits on shows <b>SELL</b> with the reason; when a new BUY fires on the same bar, the SELL shows first and the note says so. <b>READY TO BUY</b>: the other three rules pass and RSI(2) is under 20 but not yet under 10. WAIT and READY TO BUY show the <b>expected buy</b> (the highest next close that would put RSI(2) under 10, solved from the Wilder averages; today's close when RSI(2) is already there), the expected stop (that − 2.5 × ATR) and an expected target (the 5-day average including that close, where an RSI(2) dip usually reverts; the trade itself still exits on the first close above the entry). <b>Near Setup</b>: 3 of the 4 rules pass while flat. Everything else is <b>WAIT</b>.
          <b>Downtrend</b>: no new entries, shown as N/A – Downtrend. A trade opened in the uptrend is still tracked to its SELL after the flip, and stays listed even if the name drops off the screen.
          The state is rebuilt by replaying the last 40 daily bars. Today's bar counts as complete from 4:15 PM ET; before that the live bar's open is only used to fill a BUY from the day before. It runs a few minutes after each screen and at 4:15 PM ET, when every BUY, SELL and READY TO BUY is sent once as an alert. The settings panel changes the thresholds.</li>
        <li><b>RSI, MACD, ADX, Stoch, Band</b>: daily readings on every row (14-day RSI, 12/26/9 MACD, 14-day ADX with its direction, 14/3 stochastic, position inside the 20-day 2σ Bollinger band). They are readings, not filters.</li>
        <li><b>Option chains</b> come from Cboe's free delayed quotes (cdn.cboe.com, every expiry in one file, no login), with Yahoo as the fallback; set SCREENER_OPTIONS_SOURCE=yahoo to use Yahoo only. A name whose chain could not be read is tried again on the next screen; when chains are missing, the note above the lists says why and <code>GET /api/chains/status</code> has the details.</li>
        <li><b>Chain</b> (button on each Core row): the stock's option chain under its row. Put open interest to the left, call open interest to the right, by strike, for the 30 strikes nearest the price, with the call wall, put wall, King and the price marked, and IV and net gamma per strike. Pick all expiries in the 45-day window or one expiry. Read when you open it and kept for 15 minutes.</li>
        <li><b>GEX levels</b>: every listed stock's option chain is read once a day (8:00 AM ET on weekdays, and on the first screen for a name that is new that day). Open interest is summed by strike across the expiries in the next 45 days. Call wall: the strike with the most call open interest. Put wall: the strike with the most put open interest. King: the strike with the largest net gamma exposure. Each shows its open interest and its share of all open interest in the window. Expiry is the date holding the most open interest; "as of" flags a chain that could not be refreshed today.</li>
        <li><b>Session phases</b> (Sessions tab): Asia 6 PM–2 AM, London 2–8 AM, New York 8 AM–5 PM ET, each scored 0–100% for accumulation (tight, quiet balance), manipulation (sweeps the prior session's high or low and closes back inside), absorption (heavy effort, no progress) and distribution (a one-way expansion). Scored on 5-minute candles only, so every timeframe shows the same boxes. Bull/Bear comes from the close location, the swept side or the body direction.</li>
        <li><b>Market line</b>: SPY, QQQ and IWM against today's VWAP, and advancers/decliners on Nasdaq and NYSE.</li>
      </ul>
      <h3>API guide</h3>
      <ul>
        <li><code>GET /api/screen</code>: the full snapshot, every row and the market line.</li>
        <li><code>GET /api/prices</code>: the light payload the page polls every 10 seconds.</li>
        <li><code>GET /api/chain/{ticker}?expiry=ALL</code>: one stock's option chain by strike (call and put open interest, IV, net gamma) with its walls and King; the <b>Chain</b> button on each Core row shows it.</li>
        <li><code>GET /api/chains/status</code>: why option chains are or are not loading (source, Cboe and Yahoo errors, last run's counts). <code>POST /api/chains/refresh</code> reads them all again now.</li>
        <li><code>GET /api/bias</code>: today's market-bias call (or a preview), the last calls with SPY's result and the accuracy; <code>?refresh=true</code> reads it again now. <code>POST /api/bias/test</code> sends a test phone alert.</li>
        <li><code>GET /api/pv</code>: PV Confirm rows (uptrend and downtrend), SPY's state and the settings. <code>POST /api/pv/refresh</code> re-runs it; <code>GET|PUT /api/pv/settings</code> reads or saves the settings; <code>GET /api/pv/alerts</code> lists the 4:15 PM alerts.</li>
        <li><code>POST /api/refresh</code>: run the screen now.</li>
        <li><code>GET /api/watchlist</code>, <code>POST /api/watchlist {"tickers":"SRTA, AUR"}</code>, <code>DELETE /api/watchlist/{ticker}</code>, <code>GET /api/watchlist/export</code>.</li>
        <li><code>GET /api/alerts</code>, <code>POST /api/alerts {"symbol","type":"ABOVE|BELOW|PCT_UP|PCT_DOWN","level","note"}</code>, <code>DELETE /api/alerts/{id}</code>.</li>
        <li><code>GET /api/analyze/{ticker}</code>: quarterly results from SEC EDGAR.</li>
        <li><code>GET /api/backtest?tickers=&amp;days=60&amp;interval=60m&amp;threshold=60&amp;stopAtr=2&amp;trailAtr=2.5&amp;maxBars=35&amp;costBps=5</code>: Quantum Shift backtest; blank tickers means every name in the app.</li>
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
