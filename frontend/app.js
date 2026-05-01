'use strict';

const STORAGE_BACKEND   = 'altfinder_backend_url';
const STORAGE_DISTANCE  = 'altfinder_distance';
const STORAGE_CHARS     = 'altfinder_saved_chars';
const STORAGE_GUILDS    = 'altfinder_saved_guilds';
const STORAGE_PRESETS   = 'altfinder_presets';
const STORAGE_IGNORE    = 'altfinder_ignore_list';
const TIMEOUT_SEARCH    = 90000;
const TIMEOUT_STATUS    = 10000;
const TIMEOUT_CHAR      = 20000;

const el = {
  backendUrl:         document.getElementById('backendUrl'),
  distance:           document.getElementById('distance'),
  includeClashes:     document.getElementById('includeClashes'),
  altFormat:          document.getElementById('altFormat'),
  altFrom:            document.getElementById('altFrom'),
  altTo:              document.getElementById('altTo'),
  strictMode:         document.getElementById('strictMode'),
  characters:         document.getElementById('characters'),
  runBtn:             document.getElementById('runBtn'),
  clearBtn:           document.getElementById('clearBtn'),
  errorBox:           document.getElementById('errorBox'),
  errorMsg:           document.getElementById('errorMsg'),
  backendLink:        document.getElementById('backendLink'),
  summary:            document.getElementById('summary'),
  matchesArea:        document.getElementById('matchesArea'),
  filterInput:        document.getElementById('filterInput'),
  csvBtn:             document.getElementById('csvBtn'),
  apiBadge:           document.getElementById('apiBadge'),
  statusBar:          document.getElementById('statusBar'),
  // Trades
  tradesWorld:        document.getElementById('tradesWorld'),
  tradesCharacters:   document.getElementById('tradesCharacters'),
  lookbackDays:       document.getElementById('lookbackDays'),
  tradesRunBtn:       document.getElementById('tradesRunBtn'),
  tradesClearBtn:     document.getElementById('tradesClearBtn'),
  tradesErrorBox:     document.getElementById('tradesErrorBox'),
  tradesErrorMsg:     document.getElementById('tradesErrorMsg'),
  tradesArea:         document.getElementById('tradesArea'),
  transferWorld:      document.getElementById('transferWorld'),
  transferLookback:   document.getElementById('transferLookback'),
  transferRunBtn:     document.getElementById('transferRunBtn'),
  transferErrorBox:   document.getElementById('transferErrorBox'),
  transferErrorMsg:   document.getElementById('transferErrorMsg'),
  transferToArea:     document.getElementById('transferToArea'),
  transferFromArea:   document.getElementById('transferFromArea'),
  // Clashes
  clashCharacters:    document.getElementById('clashCharacters'),
  clashTargets:       document.getElementById('clashTargets'),
  clashFrom:          document.getElementById('clashFrom'),
  clashTo:            document.getElementById('clashTo'),
  clashDistance:      document.getElementById('clashDistance'),
  clashesRunBtn:      document.getElementById('clashesRunBtn'),
  clashesClearBtn:    document.getElementById('clashesClearBtn'),
  clashesErrorBox:    document.getElementById('clashesErrorBox'),
  clashesErrorMsg:    document.getElementById('clashesErrorMsg'),
  clashesSummary:     document.getElementById('clashesSummary'),
  clashesArea:        document.getElementById('clashesArea'),
  // Research
  researchLimit:      document.getElementById('researchLimit'),
  researchLoadBtn:    document.getElementById('researchLoadBtn'),
  researchArea:       document.getElementById('researchArea'),
  // Presets
  presetNameInput:    document.getElementById('presetNameInput'),
  presetList:         document.getElementById('presetList'),
  savePresetBtn:      document.getElementById('savePresetBtn'),
  loadPresetBtn:      document.getElementById('loadPresetBtn'),
  deletePresetBtn:    document.getElementById('deletePresetBtn'),
  // Ignore list
  ignoreInput:        document.getElementById('ignoreInput'),
  ignoreList:         document.getElementById('ignoreList'),
  removeIgnoreBtn:    document.getElementById('removeIgnoreBtn'),
  clearIgnoreBtn:     document.getElementById('clearIgnoreBtn'),
  showIgnored:        document.getElementById('showIgnored'),
  // Saved chars
  savedCharInput:     document.getElementById('savedCharInput'),
  savedCharList:      document.getElementById('savedCharList'),
  addCharBtn:         document.getElementById('addCharBtn'),
  useCharBtn:         document.getElementById('useCharBtn'),
  removeCharBtn:      document.getElementById('removeCharBtn'),
  savedCharDisplay:   document.getElementById('savedCharDisplay'),
  // Saved guilds
  savedGuildInput:    document.getElementById('savedGuildInput'),
  savedGuildList:     document.getElementById('savedGuildList'),
  refreshGuildBtn:    document.getElementById('refreshGuildBtn'),
  loadGuildBtn:       document.getElementById('loadGuildBtn'),
  removeGuildBtn:     document.getElementById('removeGuildBtn'),
  savedGuildDisplay:  document.getElementById('savedGuildDisplay'),
  // Currently online
  refreshOnlineBtn:   document.getElementById('refreshOnlineBtn'),
  onlineArea:         document.getElementById('onlineArea'),
  // Watchlist
  watchGuildId:       document.getElementById('watchGuildId'),
  watchChannelId:     document.getElementById('watchChannelId'),
  watchCharInput:     document.getElementById('watchCharInput'),
  addWatchBtn:        document.getElementById('addWatchBtn'),
  loadWatchBtn:       document.getElementById('loadWatchBtn'),
  watchListArea:      document.getElementById('watchListArea'),
  // Character panel
  charPanel:          document.getElementById('charPanel'),
  charPanelName:      document.getElementById('charPanelName'),
  charPanelContent:   document.getElementById('charPanelContent'),
  charPanelClose:     document.getElementById('charPanelClose'),
  // Searched character info
  searchedCharInfo:   document.getElementById('searchedCharInfo'),
  searchedCharCards:  document.getElementById('searchedCharCards'),
};

let savedChars   = [];
let savedGuilds  = [];
let savedPresets = {};
let ignoreList   = [];
let guildOnlineMap = {};
let lastMatches  = [];
let transfersLoaded = false;

// ── Persistence ───────────────────────────────────────────────────────────────
function loadStorage() {
  const url = localStorage.getItem(STORAGE_BACKEND);
  if (url) el.backendUrl.value = url;
  const dist = localStorage.getItem(STORAGE_DISTANCE);
  if (dist !== null) el.distance.value = dist;
  try { savedChars   = JSON.parse(localStorage.getItem(STORAGE_CHARS)    || '[]'); } catch(_) {}
  try { savedGuilds  = JSON.parse(localStorage.getItem(STORAGE_GUILDS)   || '[]'); } catch(_) {}
  try { savedPresets = JSON.parse(localStorage.getItem(STORAGE_PRESETS)  || '{}'); } catch(_) {}
  try { ignoreList   = JSON.parse(localStorage.getItem(STORAGE_IGNORE)   || '[]'); } catch(_) {}
  renderSavedChars();
  renderSavedGuilds();
  renderPresetList();
  renderIgnoreList();
}

function persist() {
  localStorage.setItem(STORAGE_BACKEND,  el.backendUrl.value.trim());
  localStorage.setItem(STORAGE_DISTANCE, el.distance.value);
  localStorage.setItem(STORAGE_CHARS,    JSON.stringify(savedChars));
  localStorage.setItem(STORAGE_GUILDS,   JSON.stringify(savedGuilds));
  localStorage.setItem(STORAGE_PRESETS,  JSON.stringify(savedPresets));
  localStorage.setItem(STORAGE_IGNORE,   JSON.stringify(ignoreList));
}

// ── Helpers ───────────────────────────────────────────────────────────────────
function baseUrl() { return el.backendUrl.value.trim().replace(/\/+$/, ''); }
function safeBoardUrl() {
  try {
    const u = new URL(baseUrl() + '/');
    if (u.protocol === 'http:' || u.protocol === 'https:') return u.href;
  } catch(_) {}
  return '#';
}
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function esc(v) {
  return String(v)
    .replace(/&/g,'&amp;').replace(/</g,'&lt;')
    .replace(/>/g,'&gt;').replace(/"/g,'&quot;')
    .replace(/'/g,'&#39;');
}
function fmtSeconds(sec) {
  if (sec == null || sec < 0) return 'n/a';
  if (sec < 60) return sec + 's';
  if (sec < 3600) return Math.round(sec / 60) + 'm';
  return (sec / 3600).toFixed(1) + 'h';
}
function fmtTimestamp(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return ts;
  return d.toLocaleString();
}

// ── Error ─────────────────────────────────────────────────────────────────────
function showError(msg, box, msgEl) {
  if (!box) { box = el.errorBox; msgEl = el.errorMsg; }
  if (msgEl) msgEl.textContent = msg || '';
  box.classList.toggle('visible', !!msg);
  if (box === el.errorBox) {
    el.backendLink.href = safeBoardUrl();
    el.backendLink.style.display = (msg && /backend timed out/i.test(msg)) ? 'none' : '';
  }
}
function clearError(box) {
  if (!box) box = el.errorBox;
  box.classList.remove('visible');
  if (box === el.errorBox) el.backendLink.style.display = '';
}

// ── API fetch ─────────────────────────────────────────────────────────────────
async function fetchJson(path, timeoutMs, maxAttempts) {
  if (maxAttempts === undefined) maxAttempts = 2;
  if (timeoutMs === undefined) timeoutMs = 15000;
  const url = baseUrl() + path;
  let lastErr = null, res = null;
  for (let i = 1; i <= maxAttempts; i++) {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      res = await fetch(url, { signal: ctrl.signal });
      clearTimeout(t);
      if (res.status < 500) break;
      lastErr = new Error('HTTP ' + res.status);
    } catch(e) { clearTimeout(t); lastErr = e; }
    if (i < maxAttempts) await sleep(3000);
  }
  if (!res) {
    const detail = lastErr && lastErr.name === 'AbortError'
      ? 'Request timed out.'
      : 'Browser could not establish a network connection. Check Backend URL, Render deploy health, VPN/firewall/proxy rules, and CORS/network access.';
    throw new Error('Could not reach API at ' + url + ' after ' + maxAttempts + ' attempts. ' + detail);
  }
  const text = await res.text();
  if (/response timed out/i.test(text)) throw new Error('Backend timed out. Try adding a From date to limit the search window (e.g. last 30 days).');
  const ct = res.headers.get('content-type') || '';
  if (!ct.includes('application/json')) throw new Error('Non-JSON response (HTTP ' + res.status + '): ' + text.slice(0,200));
  const body = JSON.parse(text);
  if (!res.ok) throw new Error(body.error || body.message || 'HTTP ' + res.status);
  return body;
}

// ── Health + Status check ─────────────────────────────────────────────────────
async function checkHealth() {
  const maxRetries = 3;
  const retryDelay = 5000;
  for (let i = 0; i < maxRetries; i++) {
    try {
      await fetchJson('/api/altfinder/health', TIMEOUT_STATUS, 1);
      el.apiBadge.textContent = 'API: ok';
      el.apiBadge.className = 'api-badge ok';
      return;
    } catch(_) { /* retry on any error */ }
    if (i < maxRetries - 1) {
      el.apiBadge.textContent = 'API: connecting\u2026';
      el.apiBadge.className = 'api-badge';
      await sleep(retryDelay);
    }
  }
  el.apiBadge.textContent = 'API: unavailable';
  el.apiBadge.className = 'api-badge bad';
}

async function refreshStatus() {
  try {
    const d = await fetchJson('/api/altfinder/status', TIMEOUT_STATUS, 1);
    const chips = [];
    if (d.onlineHistoryRows != null && d.onlineHistoryRows >= 0) {
      chips.push({ label: 'DB rows: ' + d.onlineHistoryRows.toLocaleString(), cls: 'ok' });
    }
    if (d.latestWorldSaveAgeSeconds != null) {
      const age = fmtSeconds(d.latestWorldSaveAgeSeconds);
      const cls = d.latestWorldSaveAgeSeconds > 7200 ? 'warn' : 'ok';
      chips.push({ label: 'World save age: ' + age, cls });
    }
    if (d.bazaarCooldownSeconds != null) {
      const cd = fmtSeconds(d.bazaarCooldownSeconds);
      const cls = d.bazaarCooldownSeconds > 0 ? 'warn' : 'ok';
      chips.push({ label: 'Bazaar cooldown: ' + cd, cls });
    }
    if (d.queryCacheSize != null) {
      chips.push({ label: 'Cache: ' + d.queryCacheSize + ' entries (TTL ' + fmtSeconds(d.queryCacheTtlSeconds) + ')', cls: '' });
    }
    el.statusBar.innerHTML = chips.map(function(c) {
      return '<span class="status-chip ' + c.cls + '">' + esc(c.label) + '</span>';
    }).join('');
  } catch(_) {
    el.statusBar.innerHTML = '';
  }
}

// ── Tab navigation ────────────────────────────────────────────────────────────
document.querySelectorAll('.tab').forEach(function(tab) {
  tab.addEventListener('click', function() {
    document.querySelectorAll('.tab').forEach(function(t) { t.classList.remove('active'); });
    document.querySelectorAll('.tab-panel').forEach(function(p) { p.classList.add('hidden'); });
    tab.classList.add('active');
    const panel = document.getElementById('tab-' + tab.dataset.tab);
    if (panel) panel.classList.remove('hidden');
  });
});

// ── Strict mode ───────────────────────────────────────────────────────────────
el.strictMode.addEventListener('change', function() {
  if (this.checked) {
    el.distance.value = '0'; el.includeClashes.value = 'false';
    el.distance.disabled = true; el.includeClashes.disabled = true;
  } else {
    el.distance.disabled = false; el.includeClashes.disabled = false;
  }
});

// ── Ignore list ───────────────────────────────────────────────────────────────
function renderIgnoreList() {
  el.ignoreList.innerHTML = ignoreList.length === 0
    ? '<option value="">No ignored names</option>'
    : ignoreList.map(function(n) { return '<option value="' + esc(n) + '">' + esc(n) + '</option>'; }).join('');
}

el.ignoreInput.addEventListener('keydown', function(e) {
  if (e.key !== 'Enter') return;
  const name = el.ignoreInput.value.trim();
  if (!name || ignoreList.includes(name)) return;
  ignoreList.push(name); persist(); renderIgnoreList(); el.ignoreInput.value = '';
  applyFilter();
});
el.removeIgnoreBtn.addEventListener('click', function() {
  const sel = el.ignoreList.value;
  if (!sel) return;
  ignoreList = ignoreList.filter(function(n) { return n !== sel; });
  persist(); renderIgnoreList(); applyFilter();
});
el.clearIgnoreBtn.addEventListener('click', function() {
  ignoreList = []; persist(); renderIgnoreList(); applyFilter();
});
el.showIgnored.addEventListener('change', applyFilter);

// ── Filter + ignore ───────────────────────────────────────────────────────────
function applyFilter() {
  const filterText = el.filterInput.value.trim().toLowerCase();
  const showIgnored = el.showIgnored.checked;
  const wrap = el.matchesArea.querySelector('.table-wrap');
  if (!wrap) return;
  const rows = wrap.querySelectorAll('tbody tr');
  rows.forEach(function(row) {
    const nameTd = row.querySelector('[data-name]');
    const name = nameTd ? nameTd.dataset.name : '';
    const isIgnored = ignoreList.some(function(n) { return n.toLowerCase() === name.toLowerCase(); });
    if (isIgnored && !showIgnored) { row.style.display = 'none'; return; }
    if (isIgnored) { row.classList.add('row-ignored'); } else { row.classList.remove('row-ignored'); }
    if (filterText && !name.toLowerCase().includes(filterText)) {
      row.style.display = 'none';
    } else {
      row.style.display = '';
    }
  });
}

el.filterInput.addEventListener('input', applyFilter);

// ── Render matches ────────────────────────────────────────────────────────────
function renderMatches(matches) {
  lastMatches = matches || [];
  if (!matches || matches.length === 0) {
    el.matchesArea.innerHTML = '<pre>No matches found.</pre>';
    return;
  }
  const rows = matches.map(function(m) {
    const conf = Number(m.confidence || 0);
    const cls = conf >= 70 ? 'conf-hi' : conf >= 40 ? 'conf-mid' : 'conf-lo';
    const hidden = m.hiddenLikely ? 'yes (' + m.hiddenScore + ')' : 'no (' + m.hiddenScore + ')';
    const traded = (m.recentTradeDates || []).length > 0;
    const tradeCell = traded
      ? esc(m.recentTradeDates[0]) + '<span class="trade-badge">TRADED</span>'
      : '<span style="color:#8b949e">none</span>';
    const isIgnored = ignoreList.some(function(n) { return n.toLowerCase() === (m.name||'').toLowerCase(); });
    const ignoredBadge = isIgnored ? '<span class="ignored-badge">IGNORED</span>' : '';
    return '<tr>'
      + '<td class="name-cell" data-name="' + esc(m.name||'') + '">' + esc(m.name||'Unknown') + ignoredBadge + '</td>'
      + '<td><span class="conf-pill ' + cls + '">' + conf + '</span></td>'
      + '<td>' + esc(String(m.adjacencies != null ? m.adjacencies : '-')) + '</td>'
      + '<td>' + esc(String(m.clashes != null ? m.clashes : '-')) + '</td>'
      + '<td>' + esc(String(m.logins != null ? m.logins : '-')) + '</td>'
      + '<td>' + esc(hidden) + '</td>'
      + '<td>' + tradeCell + '</td>'
      + '</tr>';
  }).join('');
  el.matchesArea.innerHTML = '<div class="table-wrap"><table>'
    + '<thead><tr><th>Name</th><th>Confidence</th><th>Adj</th><th>Clashes</th>'
    + '<th>Logins</th><th>Hidden</th><th>Trades</th></tr></thead>'
    + '<tbody>' + rows + '</tbody></table></div>';
  applyFilter();
}

// ── CSV Export ────────────────────────────────────────────────────────────────
el.csvBtn.addEventListener('click', function() {
  if (!lastMatches.length) return;
  const header = ['Name','Confidence','Adjacencies','Clashes','Logins','HiddenScore','HiddenLikely','RecentTrades'];
  const csvRows = [header.join(',')].concat(lastMatches.map(function(m) {
    return [
      '"' + (m.name||'').replace(/"/g,'""') + '"',
      m.confidence != null ? m.confidence : '',
      m.adjacencies != null ? m.adjacencies : '',
      m.clashes != null ? m.clashes : '',
      m.logins != null ? m.logins : '',
      m.hiddenScore != null ? m.hiddenScore : '',
      m.hiddenLikely ? 'true' : 'false',
      '"' + (m.recentTradeDates||[]).join(';').replace(/"/g,'""') + '"',
    ].join(',');
  }));
  const blob = new Blob([csvRows.join('\r\n')], { type: 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'altfinder-results.csv';
  a.click();
  URL.revokeObjectURL(a.href);
});

// ── Summary renderer ──────────────────────────────────────────────────────────
function renderSummary(text) {
  // Lines after these labels get the bright "chars" highlight (names).
  const NAME_AFTER = new Set(['Searched characters', 'Checked against']);
  // These lines are section labels – keep them in the muted grey colour.
  const LABEL_LINES = new Set([
    'Searched characters', 'Checked against',
    'Total logins', 'Date range', 'Adjacency distance', 'Include clashes',
    'Total clashes', 'Possible matches', 'Clash matches',
    'Traded character detected', "Couldn't check if traded", 'Candidate trade checks',
  ]);
  const lines = text.split('\n');
  let highlightNext = false;
  const html = lines.map(line => {
    const escaped = line.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const trimmed = line.trim();
    if (trimmed === '') { highlightNext = false; return escaped; }
    if (NAME_AFTER.has(trimmed)) { highlightNext = true; return escaped; }
    if (highlightNext && trimmed !== '') {
      highlightNext = false;
      return `<span class="summary-chars">${escaped}</span>`;
    }
    if (LABEL_LINES.has(trimmed)) return escaped;
    return `<span style="color:#e6edf3">${escaped}</span>`;
  }).join('\n');
  el.summary.innerHTML = html;
}

// ── Searched character info ───────────────────────────────────────────────────
async function fetchAndRenderSearchedChars(names) {
  el.searchedCharInfo.style.display = '';
  el.searchedCharCards.innerHTML = names.map(function(n, i) {
    return '<div class="searched-char-card" id="scc-' + i + '">'
      + '<div class="searched-char-card-name">' + esc(n) + '</div>'
      + '<div style="color:#8b949e;font-style:italic;font-size:.8rem">Fetching from TibiaData\u2026</div>'
      + '</div>';
  }).join('');
  await Promise.allSettled(names.map(async function(name, i) {
    const slot = document.getElementById('scc-' + i);
    if (!slot) return;
    try {
      const d = await fetchJson('/api/altfinder/character?name=' + encodeURIComponent(name), TIMEOUT_CHAR, 2);
      function f(lbl, val) {
        return '<div><div class="char-field-lbl">' + esc(lbl) + '</div><div class="char-field-val">' + esc(val || '-') + '</div></div>';
      }
      const guild = d.guild ? d.guild + (d.guildRank ? ' (' + d.guildRank + ')' : '') : '-';
      const lastLogin = d.lastLogin ? d.lastLogin.slice(0, 16).replace('T', ' ') : '-';
      const traded = (d.recentTradeDates || []).length > 0;
      const tradeHtml = traded
        ? '<div class="trade-alert-box" style="margin:6px 0">\u26a0 RECENTLY TRADED \u2014 ' + esc(d.recentTradeDates.join(', ')) + '</div>'
        : '';
      const tradeErr = d.tradedCheckError
        ? '<div class="trade-alert-box" style="margin:6px 0;border-color:#f85149;color:#ffa198">\u26a0 Trade check failed (may be rate-limited).</div>'
        : '';
      slot.innerHTML = '<div class="searched-char-card-name">' + esc(d.name || name) + '</div>'
        + '<div class="char-fields" style="grid-template-columns:repeat(2,1fr);margin-bottom:6px">'
        + f('Level', String(d.level || '-')) + f('Vocation', d.vocation) + f('World', d.world)
        + f('Guild', guild) + f('Last Login', lastLogin)
        + '</div>'
        + tradeHtml + tradeErr
        + '<div class="char-links">'
        + '<a class="char-link" href="' + esc(d.tibiaComUrl) + '" target="_blank" rel="noopener">Tibia.com \u2197</a>'
        + '<a class="char-link" href="' + esc(d.exevopanUrl) + '" target="_blank" rel="noopener">Exevopan \u2197</a>'
        + '</div>';
    } catch(err) {
      slot.innerHTML = '<div class="searched-char-card-name">' + esc(name) + '</div>'
        + '<div style="color:#f85149;font-size:.8rem">Could not fetch character info.</div>';
    }
  }));
}

// ── Run alt search ────────────────────────────────────────────────────────────
async function runSearch() {
  clearError();
  hideCharPanel();
  const chars = el.characters.value.trim();
  if (!chars) { showError('Enter at least one character name.'); return; }
  const searchedNames = chars.split(',').map(function(s) { return s.trim(); }).filter(Boolean);
  fetchAndRenderSearchedChars(searchedNames);
  const distance  = Number(el.distance.value || 0);
  const clashes   = el.includeClashes.value;
  const format    = el.altFormat.value;
  const params = new URLSearchParams({
    characters: chars, distance: String(distance),
    includeClashes: clashes, format: format,
  });
  const from = el.altFrom.value.trim();
  const to   = el.altTo.value.trim();
  if (from) params.set('from', from);
  if (to)   params.set('to', to);
  persist();
  el.runBtn.disabled = true; el.runBtn.textContent = 'Running\u2026';
  el.summary.innerHTML = 'Loading\u2026';
  el.matchesArea.innerHTML = '<pre>Loading\u2026</pre>';
  try {
    const data = await fetchJson('/api/altfinder/alts?' + params.toString().replace(/\+/g, '%20'), TIMEOUT_SEARCH, 3);
    renderSummary(data.formattedText || JSON.stringify(data, null, 2));
    renderMatches(data.possibleMatches || []);
  } catch(err) {
    showError(err.message || String(err));
    el.summary.innerHTML = 'Search failed.';
    el.matchesArea.innerHTML = '<pre>Search failed.</pre>';
  } finally {
    el.runBtn.disabled = false; el.runBtn.textContent = 'Run';
  }
}

function clearAll() {
  clearError(); hideCharPanel();
  el.characters.value = '';
  el.altFrom.value = ''; el.altTo.value = '';
  el.summary.innerHTML = 'No search yet.';
  el.matchesArea.innerHTML = '<pre>No search yet.</pre>';
  el.searchedCharInfo.style.display = 'none';
  el.searchedCharCards.innerHTML = '';
  lastMatches = [];
}

// ── Transfers ───────────────────────────────────────────────────────────────
function renderTransferTable(list, area, emptyText) {
  if (!list || list.length === 0) {
    area.innerHTML = '<pre>' + esc(emptyText) + '</pre>';
    return;
  }
  const rows = list.map(function(t) {
    return '<tr>'
      + '<td style="font-weight:600;color:#79c0ff">' + esc(t.characterName || '') + '</td>'
      + '<td>' + esc(t.fromWorld || '') + '</td>'
      + '<td>' + esc(t.toWorld || '') + '</td>'
      + '<td>' + esc(fmtTimestamp(t.transferTime)) + '</td>'
      + '</tr>';
  }).join('');
  area.innerHTML = '<div class="table-wrap"><table>'
    + '<thead><tr><th>Character</th><th>From</th><th>To</th><th>Transfer Time</th></tr></thead>'
    + '<tbody>' + rows + '</tbody></table></div>';
}

async function runTransfers() {
  clearError(el.transferErrorBox);
  const world = (el.transferWorld.value || '').trim() || 'Nefera';
  let lookback = parseInt(el.transferLookback.value || '7', 10);
  if (!Number.isFinite(lookback)) lookback = 7;
  lookback = Math.min(365, Math.max(1, lookback));
  el.transferLookback.value = String(lookback);
  const params = new URLSearchParams({ world, lookbackDays: String(lookback) });
  el.transferRunBtn.disabled = true; el.transferRunBtn.textContent = 'Loading\u2026';
  el.transferToArea.innerHTML = '<pre>Loading\u2026</pre>';
  el.transferFromArea.innerHTML = '<pre>Loading\u2026</pre>';
  try {
    const data = await fetchJson('/api/altfinder/transfers?' + params.toString().replace(/\+/g, '%20'), TIMEOUT_SEARCH, 3);
    const lookbackDays = data.lookbackDays != null ? data.lookbackDays : lookback;
    renderTransferTable(data.transfersTo || [], el.transferToArea, 'No transfers to ' + world + ' in the last ' + lookbackDays + ' days.');
    renderTransferTable(data.transfersFrom || [], el.transferFromArea, 'No transfers from ' + world + ' in the last ' + lookbackDays + ' days.');
    transfersLoaded = true;
  } catch(err) {
    showError(err.message || String(err), el.transferErrorBox, el.transferErrorMsg);
    el.transferToArea.innerHTML = '<pre>Search failed.</pre>';
    el.transferFromArea.innerHTML = '<pre>Search failed.</pre>';
  } finally {
    el.transferRunBtn.disabled = false; el.transferRunBtn.textContent = 'Load Transfers';
  }
}

const transferTab = document.querySelector('.tab[data-tab=\"transfers\"]');
if (transferTab) {
  transferTab.addEventListener('click', function() {
    if (!transfersLoaded) runTransfers();
  });
}
el.transferRunBtn.addEventListener('click', runTransfers);
el.transferLookback.addEventListener('keydown', function(e) { if (e.key === 'Enter') runTransfers(); });
el.transferWorld.addEventListener('keydown', function(e) { if (e.key === 'Enter') runTransfers(); });

// ── Trades ────────────────────────────────────────────────────────────────────
async function runTrades() {
  clearError(el.tradesErrorBox);
  const chars = el.tradesCharacters.value.trim();
  const world = (el.tradesWorld.value || '').trim() || 'Nefera';
  const days = parseInt(el.lookbackDays.value || '7', 10);
  const params = new URLSearchParams({ world, lookbackDays: String(days) });
  if (chars) params.set('characters', chars);
  el.tradesRunBtn.disabled = true; el.tradesRunBtn.textContent = 'Checking\u2026';
  el.tradesArea.innerHTML = '<pre>Loading\u2026</pre>';
  try {
    const data = await fetchJson('/api/altfinder/trades?' + params.toString().replace(/\+/g, '%20'), TIMEOUT_SEARCH, 3);
    const results = data.results || [];
    if (results.length === 0) { el.tradesArea.innerHTML = '<pre>No traded characters found.</pre>'; return; }
    const rows = results.map(function(r) {
      const traded = (r.recentTradeDates || []).length > 0;
      const dates = traded ? r.recentTradeDates.join(', ') : 'none';
      const badge = traded ? '<span class="trade-badge">TRADED</span>' : '';
      const error = r.hadError ? ' <span style="color:#f85149;font-size:.72rem">(check error)</span>' : '';
      return '<tr>'
        + '<td style="font-weight:600;color:#79c0ff">' + esc(r.characterName||'') + badge + error + '</td>'
        + '<td>' + esc(String(r.checkedNames ? r.checkedNames.join(', ') : '-')) + '</td>'
        + '<td>' + esc(dates) + '</td>'
        + '</tr>';
    }).join('');
    el.tradesArea.innerHTML = '<div class="table-wrap"><table>'
      + '<thead><tr><th>Character</th><th>Checked Names</th><th>Trade Dates</th></tr></thead>'
      + '<tbody>' + rows + '</tbody></table></div>';
  } catch(err) {
    showError(err.message || String(err), el.tradesErrorBox, el.tradesErrorMsg);
    el.tradesArea.innerHTML = '<pre>Search failed.</pre>';
  } finally {
    el.tradesRunBtn.disabled = false; el.tradesRunBtn.textContent = 'Check Trades';
  }
}

el.tradesRunBtn.addEventListener('click', runTrades);
el.tradesClearBtn.addEventListener('click', function() {
  clearError(el.tradesErrorBox);
  el.tradesCharacters.value = '';
  el.tradesArea.innerHTML = '<pre>No search yet.</pre>';
});
el.tradesCharacters.addEventListener('keydown', function(e) { if (e.key === 'Enter') runTrades(); });

// ── Clashes ───────────────────────────────────────────────────────────────────
async function runClashes() {
  clearError(el.clashesErrorBox);
  const chars   = el.clashCharacters.value.trim();
  const targets = el.clashTargets.value.trim();
  if (!chars)   { showError('Enter at least one character name.', el.clashesErrorBox, el.clashesErrorMsg); return; }
  if (!targets) { showError('Enter at least one target name.', el.clashesErrorBox, el.clashesErrorMsg); return; }
  const params = new URLSearchParams({
    characters: chars, targets: targets,
    distance: String(parseInt(el.clashDistance.value || '0', 10)),
  });
  const from = el.clashFrom.value.trim();
  const to   = el.clashTo.value.trim();
  if (from) params.set('from', from);
  if (to)   params.set('to', to);
  el.clashesRunBtn.disabled = true; el.clashesRunBtn.textContent = 'Searching\u2026';
  el.clashesSummary.textContent = 'Loading\u2026';
  el.clashesArea.innerHTML = '<pre>Loading\u2026</pre>';
  try {
    const data = await fetchJson('/api/altfinder/clashes?' + params.toString().replace(/\+/g, '%20'), TIMEOUT_SEARCH, 3);
    el.clashesSummary.textContent = data.formattedText || JSON.stringify(data, null, 2);
    const matches = data.clashes || [];
    if (matches.length === 0) { el.clashesArea.innerHTML = '<pre>No clash matches found.</pre>'; return; }
    const rows = matches.map(function(m) {
      return '<tr>'
        + '<td style="font-weight:600;color:#79c0ff">' + esc(m.name||'Unknown') + '</td>'
        + '<td>' + esc(String(m.adjacencies != null ? m.adjacencies : '-')) + '</td>'
        + '<td>' + esc(String(m.clashes != null ? m.clashes : '-')) + '</td>'
        + '<td>' + esc(String(m.logins != null ? m.logins : '-')) + '</td>'
        + '</tr>';
    }).join('');
    el.clashesArea.innerHTML = '<div class="table-wrap"><table>'
      + '<thead><tr><th>Name</th><th>Adjacencies</th><th>Clashes</th><th>Logins</th></tr></thead>'
      + '<tbody>' + rows + '</tbody></table></div>';
  } catch(err) {
    showError(err.message || String(err), el.clashesErrorBox, el.clashesErrorMsg);
    el.clashesSummary.textContent = 'Search failed.';
    el.clashesArea.innerHTML = '<pre>Search failed.</pre>';
  } finally {
    el.clashesRunBtn.disabled = false; el.clashesRunBtn.textContent = 'Find Clashes';
  }
}

el.clashesRunBtn.addEventListener('click', runClashes);
el.clashesClearBtn.addEventListener('click', function() {
  clearError(el.clashesErrorBox);
  el.clashCharacters.value = ''; el.clashTargets.value = '';
  el.clashFrom.value = ''; el.clashTo.value = '';
  el.clashesSummary.textContent = 'No search yet.';
  el.clashesArea.innerHTML = '<pre>No search yet.</pre>';
});

// ── Research History ──────────────────────────────────────────────────────────
el.researchLoadBtn.addEventListener('click', async function() {
  const limit = parseInt(el.researchLimit.value || '50', 10);
  el.researchLoadBtn.disabled = true; el.researchLoadBtn.textContent = 'Loading\u2026';
  el.researchArea.innerHTML = '<pre>Loading\u2026</pre>';
  try {
    const rows = await fetchJson('/api/altfinder/research?limit=' + limit, TIMEOUT_STATUS, 1);
    if (!rows || rows.length === 0) { el.researchArea.innerHTML = '<pre>No research runs found.</pre>'; return; }
    const tableRows = rows.map(function(r) {
      const chars = (r.searchedCharacters || []).join(', ') || '-';
      const date  = (r.createdAt || '').slice(0, 16).replace('T', ' ');
      return '<tr>'
        + '<td style="color:#8b949e;font-size:.72rem">' + esc(date) + '</td>'
        + '<td><span class="conf-pill ' + (r.runType === 'alts' ? 'conf-mid' : 'conf-lo') + '">' + esc(r.runType||'?') + '</span></td>'
        + '<td style="color:#79c0ff;max-width:220px;overflow:hidden;text-overflow:ellipsis">' + esc(chars) + '</td>'
        + '<td>' + esc(String(r.matchCount != null ? r.matchCount : '-')) + '</td>'
        + '<td>' + esc(String(r.totalLogins != null ? r.totalLogins : '-')) + '</td>'
        + '<td style="color:#8b949e">' + esc(r.distanceMinutes != null ? r.distanceMinutes + 'm' : '-') + '</td>'
        + '</tr>';
    }).join('');
    el.researchArea.innerHTML = '<div class="research-table-wrap"><table>'
      + '<thead><tr><th>Date</th><th>Type</th><th>Characters</th><th>Matches</th><th>Logins</th><th>Dist</th></tr></thead>'
      + '<tbody>' + tableRows + '</tbody></table></div>';
  } catch(err) {
    el.researchArea.innerHTML = '<pre>Error: ' + esc(err.message || String(err)) + '</pre>';
  } finally {
    el.researchLoadBtn.disabled = false; el.researchLoadBtn.textContent = 'Load History';
  }
});

// ── Presets ───────────────────────────────────────────────────────────────────
function renderPresetList() {
  const keys = Object.keys(savedPresets);
  el.presetList.innerHTML = keys.length === 0
    ? '<option value="">No saved presets</option>'
    : keys.map(function(k) { return '<option value="' + esc(k) + '">' + esc(k) + '</option>'; }).join('');
}

el.savePresetBtn.addEventListener('click', function() {
  const name = el.presetNameInput.value.trim();
  if (!name) return;
  savedPresets[name] = {
    characters:     el.characters.value.trim(),
    distance:       el.distance.value,
    includeClashes: el.includeClashes.value,
    altFormat:      el.altFormat.value,
    altFrom:        el.altFrom.value,
    altTo:          el.altTo.value,
    strictMode:     el.strictMode.checked,
  };
  persist(); renderPresetList(); el.presetNameInput.value = '';
  // select the newly saved preset
  el.presetList.value = name;
});

el.loadPresetBtn.addEventListener('click', function() {
  const name = el.presetList.value;
  const p = savedPresets[name];
  if (!p) return;
  el.characters.value     = p.characters || '';
  el.distance.value       = p.distance != null ? p.distance : '0';
  el.includeClashes.value = p.includeClashes || 'false';
  el.altFormat.value      = p.altFormat || 'detailed';
  el.altFrom.value        = p.altFrom || '';
  el.altTo.value          = p.altTo || '';
  el.strictMode.checked   = !!p.strictMode;
  el.strictMode.dispatchEvent(new Event('change'));
  // switch to alt finder tab
  document.querySelector('.tab[data-tab="alts"]').click();
});

el.deletePresetBtn.addEventListener('click', function() {
  const name = el.presetList.value;
  if (!name || !savedPresets[name]) return;
  delete savedPresets[name];
  persist(); renderPresetList();
});

// ── Saved Characters ──────────────────────────────────────────────────────────
function renderSavedChars() {
  el.savedCharList.innerHTML = savedChars.length === 0
    ? '<option value="">No saved characters</option>'
    : savedChars.map(function(c){ return '<option value="' + esc(c) + '">' + esc(c) + '</option>'; }).join('');
  el.savedCharDisplay.textContent = savedChars.length === 0 ? 'No saved characters yet.' : savedChars.join('\n');
}

el.addCharBtn.addEventListener('click', function() {
  const name = el.savedCharInput.value.trim();
  if (!name || savedChars.includes(name)) return;
  savedChars.push(name); persist(); renderSavedChars(); el.savedCharInput.value = '';
});
el.savedCharInput.addEventListener('keydown', function(e) { if (e.key === 'Enter') el.addCharBtn.click(); });
el.useCharBtn.addEventListener('click', function() {
  const sel = el.savedCharList.value; if (sel) el.characters.value = sel;
  document.querySelector('.tab[data-tab="alts"]').click();
});
el.removeCharBtn.addEventListener('click', function() {
  const sel = el.savedCharList.value;
  savedChars = savedChars.filter(function(c){ return c !== sel; });
  persist(); renderSavedChars();
});

// ── Saved Guilds ──────────────────────────────────────────────────────────────
function renderSavedGuilds() {
  el.savedGuildList.innerHTML = savedGuilds.length === 0
    ? '<option value="">No saved guilds</option>'
    : savedGuilds.map(function(g){ return '<option value="' + esc(g) + '">' + esc(g) + '</option>'; }).join('');
  el.savedGuildDisplay.textContent = savedGuilds.length === 0 ? 'No saved guilds yet.' : savedGuilds.join('\n');
}

el.savedGuildInput.addEventListener('keydown', function(e) {
  if (e.key !== 'Enter') return;
  const name = el.savedGuildInput.value.trim();
  if (!name || savedGuilds.includes(name)) return;
  savedGuilds.push(name); persist(); renderSavedGuilds(); el.savedGuildInput.value = '';
});
el.refreshGuildBtn.addEventListener('click', async function() {
  const sel = el.savedGuildList.value;
  if (!sel) { el.savedGuildDisplay.textContent = 'Select a guild first.'; return; }
  el.savedGuildDisplay.textContent = 'Refreshing\u2026';
  try {
    const data = await fetchJson('/api/altfinder/guild?name=' + encodeURIComponent(sel), TIMEOUT_STATUS, 1);
    const online = data.onlineCharacters || [];
    guildOnlineMap[sel] = online;
    el.savedGuildDisplay.textContent = (data.name || sel) + ' (' + (data.world||'?') + ') \u2014 '
      + (data.online||0) + '/' + (data.members||0) + ' online\n'
      + (online.length ? online.join(', ') : 'No one online');
  } catch(err) { el.savedGuildDisplay.textContent = 'Error: ' + (err.message || String(err)); }
});
el.loadGuildBtn.addEventListener('click', function() {
  const sel = el.savedGuildList.value;
  const online = guildOnlineMap[sel];
  if (!online || online.length === 0) { el.savedGuildDisplay.textContent = 'Refresh the guild first to get online members.'; return; }
  el.characters.value = online.join(', ');
  document.querySelector('.tab[data-tab="alts"]').click();
});
el.removeGuildBtn.addEventListener('click', function() {
  const sel = el.savedGuildList.value;
  savedGuilds = savedGuilds.filter(function(g){ return g !== sel; });
  delete guildOnlineMap[sel]; persist(); renderSavedGuilds();
});

// ── Watchlist ─────────────────────────────────────────────────────────────────
async function loadWatchlist() {
  const guildId = el.watchGuildId.value.trim();
  if (!guildId) { el.watchListArea.textContent = 'Enter a Discord Guild ID.'; return; }
  el.watchListArea.textContent = 'Loading\u2026';
  try {
    const data = await fetchJson('/api/altfinder/watchlist?guildId=' + encodeURIComponent(guildId), TIMEOUT_STATUS, 1);
    const watches = data.watches || [];
    el.watchListArea.textContent = watches.length === 0
      ? 'No watches configured for this guild.'
      : watches.map(function(w){ return w.characterName + ' (dist ' + w.distance + ', threshold ' + w.confidenceThreshold + '%)'; }).join('\n');
  } catch(err) { el.watchListArea.textContent = 'Error: ' + (err.message || String(err)); }
}

async function addWatch() {
  const guildId   = el.watchGuildId.value.trim();
  const channelId = el.watchChannelId.value.trim();
  const charName  = el.watchCharInput.value.trim();
  if (!guildId || !channelId || !charName) {
    el.watchListArea.textContent = 'Guild ID, Channel ID and character name are all required.'; return;
  }
  el.watchListArea.textContent = 'Adding\u2026';
  try {
    await fetchJson('/api/altfinder/watchlist/add?guildId=' + encodeURIComponent(guildId)
      + '&channelId=' + encodeURIComponent(channelId)
      + '&character=' + encodeURIComponent(charName), TIMEOUT_STATUS, 1);
    el.watchCharInput.value = '';
    await loadWatchlist();
  } catch(err) { el.watchListArea.textContent = 'Error: ' + (err.message || String(err)); }
}

el.addWatchBtn.addEventListener('click', addWatch);
el.loadWatchBtn.addEventListener('click', loadWatchlist);
el.watchGuildId.addEventListener('keydown', function(e){ if (e.key === 'Enter') loadWatchlist(); });

// ── Character detail panel ────────────────────────────────────────────────────
function hideCharPanel() { el.charPanel.classList.remove('open'); }
el.charPanelClose.addEventListener('click', hideCharPanel);
el.matchesArea.addEventListener('click', function(e) {
  const td = e.target.closest('[data-name]'); if (td) openCharPanel(td.dataset.name);
});

async function openCharPanel(name) {
  el.charPanelName.textContent = name;
  el.charPanelContent.innerHTML = '<div style="color:#8b949e;font-style:italic">Fetching from TibiaData &amp; Exevopan\u2026</div>';
  el.charPanel.classList.add('open');
  el.charPanel.scrollIntoView({ behavior:'smooth', block:'nearest' });
  try {
    const d = await fetchJson('/api/altfinder/character?name=' + encodeURIComponent(name), TIMEOUT_CHAR, 2);
    const guild = d.guild ? d.guild + (d.guildRank ? ' (' + d.guildRank + ')' : '') : '-';
    const former = (d.formerNames || []).join(', ') || '-';
    const lastLogin = (d.lastLogin || '-').slice(0, 16).replace('T', ' ');
    const traded = (d.recentTradeDates || []).length > 0;
    const tradeHtml = traded
      ? '<div class="trade-alert-box">\u26a0 RECENTLY TRADED \u2014 ' + esc((d.recentTradeDates||[]).join(', ')) + '</div>'
      : '';
    const tradeErr = d.tradedCheckError
      ? '<div class="trade-alert-box" style="border-color:#f85149;color:#ffa198">\u26a0 Exevopan trade check failed (may be rate-limited).</div>'
      : '';
    function f(lbl, val) {
      return '<div><div class="char-field-lbl">' + esc(lbl) + '</div><div class="char-field-val">' + esc(val||'-') + '</div></div>';
    }
    el.charPanelName.textContent = d.name || name;
    el.charPanelContent.innerHTML = '<div class="char-fields">'
      + f('Level', String(d.level||'-')) + f('Vocation', d.vocation) + f('World', d.world)
      + f('Sex', d.sex) + f('Guild', guild) + f('Last Login', lastLogin)
      + f('Former Names', former) + '</div>'
      + tradeHtml + tradeErr
      + '<div class="char-links">'
      + '<a class="char-link" href="' + esc(d.tibiaComUrl) + '" target="_blank" rel="noopener">Tibia.com \u2197</a>'
      + '<a class="char-link" href="' + esc(d.exevopanUrl) + '" target="_blank" rel="noopener">Exevopan \u2197</a>'
      + '</div>';
  } catch(err) {
    el.charPanelContent.innerHTML = '<div style="color:#f85149;font-size:.85rem">Failed: ' + esc(err.message||String(err)) + '</div>';
  }
}

// ── Event wiring ──────────────────────────────────────────────────────────────
el.runBtn.addEventListener('click', runSearch);
el.clearBtn.addEventListener('click', clearAll);
el.characters.addEventListener('keydown', function(e){ if (e.key === 'Enter') runSearch(); });
el.backendUrl.addEventListener('change', function() {
  localStorage.setItem(STORAGE_BACKEND, el.backendUrl.value.trim());
  checkHealth();
  refreshStatus();
});

// ── Currently Online ──────────────────────────────────────────────────────────
async function refreshOnlineNames() {
  try {
    const d = await fetchJson('/api/altfinder/online', TIMEOUT_STATUS, 1);
    const names = d.names || [];
    el.onlineArea.textContent = names.length === 0
      ? 'No characters currently online.'
      : names.length + ' online:\n' + names.join('\n');
  } catch(_) {
    el.onlineArea.textContent = 'Could not load.';
  }
}
el.refreshOnlineBtn.addEventListener('click', refreshOnlineNames);

// ── Init ──────────────────────────────────────────────────────────────────────
loadStorage();
checkHealth();
refreshStatus();
refreshOnlineNames();
// Auto-refresh status every 60 seconds
setInterval(refreshStatus, 60000);
// Auto-refresh online names every 30 seconds
setInterval(refreshOnlineNames, 30000);
