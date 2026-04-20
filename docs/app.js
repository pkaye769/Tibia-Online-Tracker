'use strict';

const STORAGE_BACKEND  = 'altfinder_backend_url';
const STORAGE_DISTANCE = 'altfinder_distance';
const TIMEOUT_SEARCH   = 90000;
const TIMEOUT_STATUS   = 10000;
const TIMEOUT_CHAR     = 20000;

const el = {
  backendUrl:      document.getElementById('backendUrl'),
  distance:        document.getElementById('distance'),
  includeClashes:  document.getElementById('includeClashes'),
  altFormat:       document.getElementById('altFormat'),
  altFrom:         document.getElementById('altFrom'),
  altTo:           document.getElementById('altTo'),
  strictMode:      document.getElementById('strictMode'),
  characters:      document.getElementById('characters'),
  runBtn:          document.getElementById('runBtn'),
  clearBtn:        document.getElementById('clearBtn'),
  errorBox:        document.getElementById('errorBox'),
  errorMsg:        document.getElementById('errorMsg'),
  backendLink:     document.getElementById('backendLink'),
  summary:         document.getElementById('summary'),
  matchesArea:     document.getElementById('matchesArea'),
  filterInput:     document.getElementById('filterInput'),
  csvBtn:          document.getElementById('csvBtn'),
  apiBadge:        document.getElementById('apiBadge'),
  statusBar:       document.getElementById('statusBar'),
  charPanel:       document.getElementById('charPanel'),
  charPanelName:   document.getElementById('charPanelName'),
  charPanelContent:document.getElementById('charPanelContent'),
  charPanelClose:  document.getElementById('charPanelClose'),
};

let lastMatches = [];

// ── Persistence ───────────────────────────────────────────────────────────────
function loadStorage() {
  const url = localStorage.getItem(STORAGE_BACKEND);
  if (url) el.backendUrl.value = url;
  const dist = localStorage.getItem(STORAGE_DISTANCE);
  if (dist !== null) el.distance.value = dist;
}

function persist() {
  localStorage.setItem(STORAGE_BACKEND,  el.backendUrl.value.trim());
  localStorage.setItem(STORAGE_DISTANCE, el.distance.value);
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

// ── Error ─────────────────────────────────────────────────────────────────────
function showError(msg) {
  el.errorMsg.textContent = msg || '';
  el.errorBox.classList.toggle('visible', !!msg);
  el.backendLink.href = safeBoardUrl();
}
function clearError() { el.errorBox.classList.remove('visible'); }

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
  if (/response timed out/i.test(text)) throw new Error('Backend timed out. Try narrowing your search.');
  const ct = res.headers.get('content-type') || '';
  if (!ct.includes('application/json')) throw new Error('Non-JSON response (HTTP ' + res.status + '): ' + text.slice(0,200));
  const body = JSON.parse(text);
  if (!res.ok) throw new Error(body.error || body.message || 'HTTP ' + res.status);
  return body;
}

// ── Health + Status check ─────────────────────────────────────────────────────
async function checkHealth() {
  const MAX_HEALTH_ATTEMPTS = 3;
  const HEALTH_RETRY_DELAY  = 5000;
  el.apiBadge.textContent = 'API: connecting\u2026';
  el.apiBadge.className = 'api-badge';
  for (let i = 1; i <= MAX_HEALTH_ATTEMPTS; i++) {
    try {
      await fetchJson('/api/altfinder/health', TIMEOUT_STATUS, 1);
      el.apiBadge.textContent = 'API: ok';
      el.apiBadge.className = 'api-badge ok';
      return;
    } catch(_) {
      if (i < MAX_HEALTH_ATTEMPTS) await sleep(HEALTH_RETRY_DELAY);
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

// ── Strict mode ───────────────────────────────────────────────────────────────
el.strictMode.addEventListener('change', function() {
  if (this.checked) {
    el.distance.value = '0'; el.includeClashes.value = 'false';
    el.distance.disabled = true; el.includeClashes.disabled = true;
  } else {
    el.distance.disabled = false; el.includeClashes.disabled = false;
  }
});

// ── Filter ────────────────────────────────────────────────────────────────────
function applyFilter() {
  const filterText = el.filterInput.value.trim().toLowerCase();
  const wrap = el.matchesArea.querySelector('.table-wrap');
  if (!wrap) return;
  wrap.querySelectorAll('tbody tr').forEach(function(row) {
    const nameTd = row.querySelector('[data-name]');
    const name = nameTd ? nameTd.dataset.name.toLowerCase() : '';
    row.style.display = (filterText && !name.includes(filterText)) ? 'none' : '';
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
    return '<tr>'
      + '<td class="name-cell" data-name="' + esc(m.name||'') + '">' + esc(m.name||'Unknown') + '</td>'
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
  const NAME_AFTER = new Set(['Searched characters', 'Checked against']);
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

// ── Run alt search ────────────────────────────────────────────────────────────
async function runSearch() {
  clearError();
  hideCharPanel();
  const chars = el.characters.value.trim();
  if (!chars) { showError('Enter at least one character name.'); return; }
  const distance = Number(el.distance.value || 0);
  const clashes  = el.includeClashes.value;
  const format   = el.altFormat.value;
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
  lastMatches = [];
}

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
el.characters.addEventListener('keydown', function(e) { if (e.key === 'Enter') runSearch(); });
el.backendUrl.addEventListener('change', function() {
  localStorage.setItem(STORAGE_BACKEND, el.backendUrl.value.trim());
  checkHealth();
  refreshStatus();
});

// ── Init ──────────────────────────────────────────────────────────────────────
loadStorage();
checkHealth();
refreshStatus();
setInterval(refreshStatus, 60000);
