'use strict';

const STORAGE_BACKEND   = 'altfinder_backend_url';
const STORAGE_DISTANCE  = 'altfinder_distance';
const STORAGE_CHARS     = 'altfinder_saved_chars';
const STORAGE_GUILDS    = 'altfinder_saved_guilds';
const TIMEOUT_SEARCH    = 90000;
const TIMEOUT_STATUS    = 10000;
const TIMEOUT_CHAR      = 20000;

const el = {
  backendUrl:       document.getElementById('backendUrl'),
  mode:             document.getElementById('mode'),
  distance:         document.getElementById('distance'),
  includeClashes:   document.getElementById('includeClashes'),
  strictMode:       document.getElementById('strictMode'),
  characters:       document.getElementById('characters'),
  runBtn:           document.getElementById('runBtn'),
  clearBtn:         document.getElementById('clearBtn'),
  errorBox:         document.getElementById('errorBox'),
  errorMsg:         document.getElementById('errorMsg'),
  backendLink:      document.getElementById('backendLink'),
  summary:          document.getElementById('summary'),
  matchesArea:      document.getElementById('matchesArea'),
  apiBadge:         document.getElementById('apiBadge'),
  savedCharInput:   document.getElementById('savedCharInput'),
  savedCharList:    document.getElementById('savedCharList'),
  addCharBtn:       document.getElementById('addCharBtn'),
  useCharBtn:       document.getElementById('useCharBtn'),
  removeCharBtn:    document.getElementById('removeCharBtn'),
  savedCharDisplay: document.getElementById('savedCharDisplay'),
  savedGuildInput:  document.getElementById('savedGuildInput'),
  savedGuildList:   document.getElementById('savedGuildList'),
  refreshGuildBtn:  document.getElementById('refreshGuildBtn'),
  loadGuildBtn:     document.getElementById('loadGuildBtn'),
  removeGuildBtn:   document.getElementById('removeGuildBtn'),
  savedGuildDisplay:document.getElementById('savedGuildDisplay'),
  watchGuildId:     document.getElementById('watchGuildId'),
  watchChannelId:   document.getElementById('watchChannelId'),
  watchCharInput:   document.getElementById('watchCharInput'),
  addWatchBtn:      document.getElementById('addWatchBtn'),
  loadWatchBtn:     document.getElementById('loadWatchBtn'),
  watchListArea:    document.getElementById('watchListArea'),
  charPanel:        document.getElementById('charPanel'),
  charPanelName:    document.getElementById('charPanelName'),
  charPanelContent: document.getElementById('charPanelContent'),
  charPanelClose:   document.getElementById('charPanelClose'),
};

let savedChars  = [];
let savedGuilds = [];
let guildOnlineMap = {};

// ── Persistence ───────────────────────────────────────────────────────────────
function loadStorage() {
  const url = localStorage.getItem(STORAGE_BACKEND);
  if (url) el.backendUrl.value = url;
  const dist = localStorage.getItem(STORAGE_DISTANCE);
  if (dist !== null) el.distance.value = dist;
  try { savedChars  = JSON.parse(localStorage.getItem(STORAGE_CHARS)  || '[]'); } catch(_) {}
  try { savedGuilds = JSON.parse(localStorage.getItem(STORAGE_GUILDS) || '[]'); } catch(_) {}
  renderSavedChars();
  renderSavedGuilds();
}

function persist() {
  localStorage.setItem(STORAGE_BACKEND,  el.backendUrl.value.trim());
  localStorage.setItem(STORAGE_DISTANCE, el.distance.value);
  localStorage.setItem(STORAGE_CHARS,    JSON.stringify(savedChars));
  localStorage.setItem(STORAGE_GUILDS,   JSON.stringify(savedGuilds));
}

// ── Helpers ───────────────────────────────────────────────────────────────────
function baseUrl() { return el.backendUrl.value.trim().replace(/\/+$/, ''); }
// Return a safe http/https origin; falls back to '#' to prevent javascript: injection
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
    if (i < maxAttempts) await sleep(1200);
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

// ── Health check ──────────────────────────────────────────────────────────────
async function checkHealth() {
  try {
    const d = await fetchJson('/api/altfinder/health', TIMEOUT_STATUS, 1);
    el.apiBadge.textContent = 'API: ' + (d.status || 'ok');
    el.apiBadge.className = 'api-badge ok';
  } catch(_) {
    el.apiBadge.textContent = 'API: unavailable';
    el.apiBadge.className = 'api-badge bad';
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

// ── Render matches ────────────────────────────────────────────────────────────
function renderMatches(matches) {
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
}

// ── Run search ────────────────────────────────────────────────────────────────
async function runSearch() {
  clearError();
  hideCharPanel();
  const chars = el.characters.value.trim();
  if (!chars) { showError('Enter at least one character name.'); return; }
  const distance  = Number(el.distance.value || 0);
  const clashes   = el.includeClashes.value;
  const params = new URLSearchParams({
    characters: chars, distance: String(distance),
    includeClashes: clashes, format: 'detailed',
  });
  persist();
  el.runBtn.disabled = true; el.runBtn.textContent = 'Running\u2026';
  el.summary.textContent = 'Loading\u2026';
  el.matchesArea.innerHTML = '<pre>Loading\u2026</pre>';
  try {
    const data = await fetchJson('/api/altfinder/alts?' + params, TIMEOUT_SEARCH, 2);
    el.summary.textContent = data.formattedText || JSON.stringify(data, null, 2);
    renderMatches(data.possibleMatches || []);
  } catch(err) {
    showError(err.message || String(err));
    el.summary.textContent = 'Search failed.';
    el.matchesArea.innerHTML = '<pre>Search failed.</pre>';
  } finally {
    el.runBtn.disabled = false; el.runBtn.textContent = 'Run';
  }
}

function clearAll() {
  clearError(); hideCharPanel();
  el.characters.value = '';
  el.summary.textContent = 'No search yet.';
  el.matchesArea.innerHTML = '<pre>No search yet.</pre>';
}

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
});

// ── Init ──────────────────────────────────────────────────────────────────────
loadStorage();
checkHealth();
