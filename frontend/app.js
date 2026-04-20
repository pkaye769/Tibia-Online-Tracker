'use strict';

const STORAGE_BACKEND   = 'altfinder_backend_url';
const STORAGE_DISTANCE  = 'altfinder_distance';
const STORAGE_CHARS     = 'altfinder_saved_chars';
const STORAGE_GUILDS    = 'altfinder_saved_guilds';
const TIMEOUT_SEARCH    = 90000;
const TIMEOUT_STATUS    = 10000;
const TIMEOUT_CHAR      = 20000;

const el = {
  backendUrl:         document.getElementById('backendUrl'),
  mode:               document.getElementById('mode'),
  distance:           document.getElementById('distance'),
  includeClashes:     document.getElementById('includeClashes'),
  strictMode:         document.getElementById('strictMode'),
  characters:         document.getElementById('characters'),
  runBtn:             document.getElementById('runBtn'),
  clearBtn:           document.getElementById('clearBtn'),
  errorBox:           document.getElementById('errorBox'),
  errorMsg:           document.getElementById('errorMsg'),
  backendLink:        document.getElementById('backendLink'),
  summary:            document.getElementById('summary'),
  matchesArea:        document.getElementById('matchesArea'),
  apiBadge:           document.getElementById('apiBadge'),
  savedCharInput:     document.getElementById('savedCharInput'),
  savedCharList:      document.getElementById('savedCharList'),
  addCharBtn:         document.getElementById('addCharBtn'),
  useCharBtn:         document.getElementById('useCharBtn'),
  removeCharBtn:      document.getElementById('removeCharBtn'),
  savedCharDisplay:   document.getElementById('savedCharDisplay'),
  savedGuildInput:    document.getElementById('savedGuildInput'),
  savedGuildList:     document.getElementById('savedGuildList'),
  refreshGuildBtn:    document.getElementById('refreshGuildBtn'),
  loadGuildBtn:       document.getElementById('loadGuildBtn'),
  removeGuildBtn:     document.getElementById('removeGuildBtn'),
  savedGuildDisplay:  document.getElementById('savedGuildDisplay'),
  // Currently Online
  refreshOnlineBtn:   document.getElementById('refreshOnlineBtn'),
  onlineArea:         document.getElementById('onlineArea'),
  // Watched Characters
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
      const text = await res.text();
      if (/response timed out/i.test(text)) throw new Error('Backend timed out. Try narrowing your search.');
      try { return JSON.parse(text); } catch(_) {
        if (!res.ok) throw new Error('HTTP ' + res.status + ': ' + text.slice(0, 200));
        throw new Error('Unexpected response: ' + text.slice(0, 200));
      }
    } catch(err) {
      clearTimeout(t);
      lastErr = err;
      if (i < maxAttempts) await sleep(1200);
    }
  }
  const msg = lastErr ? (lastErr.name === 'AbortError' ? 'Request timed out.' : lastErr.message) : 'Unknown error';
  throw new Error(msg);
}

// ── Health check ──────────────────────────────────────────────────────────────
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

// ── Strict mode ───────────────────────────────────────────────────────────────
el.strictMode.addEventListener('change', function() {
  if (el.strictMode.checked) {
    el.distance.value = '0';
    el.includeClashes.value = 'false';
  }
});

// ── Matches rendering ─────────────────────────────────────────────────────────
const HIGHLIGHT_AFTER = new Set(['Searched characters', 'Possible matches']);
function renderMatches(matches) {
  if (!matches || matches.length === 0) {
    el.matchesArea.innerHTML = '<pre>No matches found.</pre>';
    return;
  }
  const colOrder = ['name','confidence','adjacencies','clashes','logins','hidden','traded'];
  const headers  = colOrder.map(k => '<th>' + esc(k.charAt(0).toUpperCase()+k.slice(1)) + '</th>').join('');
  const rows = matches.map(function(m) {
    const conf = m.confidence != null ? Number(m.confidence) : null;
    const cls  = conf == null ? '' : conf >= 70 ? 'conf-hi' : conf >= 50 ? 'conf-mid' : 'conf-lo';
    const pill = conf != null ? '<span class="conf-pill ' + cls + '">' + conf + '</span>' : '-';
    const traded = m.traded ? '<span class="trade-badge">Traded</span>' : (m.traded === false ? 'none' : '-');
    return '<tr data-name="' + esc(m.name||'') + '">'
      + '<td class="name-cell">' + esc(m.name||'-') + '</td>'
      + '<td>' + pill + '</td>'
      + '<td>' + esc(String(m.adjacencies != null ? m.adjacencies : '-')) + '</td>'
      + '<td>' + esc(String(m.clashes != null ? m.clashes : '-')) + '</td>'
      + '<td>' + esc(String(m.logins != null ? m.logins : '-')) + '</td>'
      + '<td>' + esc(String(m.hidden != null ? m.hidden : '-')) + '</td>'
      + '<td>' + traded + '</td>'
      + '</tr>';
  }).join('');
  el.matchesArea.innerHTML =
    '<div class="table-wrap"><table><thead><tr>' + headers + '</tr></thead><tbody>' + rows + '</tbody></table></div>';
  el.matchesArea.querySelectorAll('tr[data-name]').forEach(function(row) {
    row.addEventListener('click', function() { openCharPanel(row.dataset.name); });
  });
}

// ── Character panel ───────────────────────────────────────────────────────────
function showCharPanel() { el.charPanel.classList.add('open'); }
function hideCharPanel() { el.charPanel.classList.remove('open'); }
el.charPanelClose.addEventListener('click', hideCharPanel);

async function openCharPanel(name) {
  el.charPanelName.textContent = name;
  el.charPanelContent.innerHTML = '<div style="color:#8b949e;font-size:.85rem">Loading\u2026</div>';
  showCharPanel();
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
    const data = await fetchJson('/api/altfinder/alts?' + params.toString().replace(/\+/g, '%20'), TIMEOUT_SEARCH, 3);
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

// ── Watched Characters ────────────────────────────────────────────────────────
async function loadWatchlist() {
  const guildId = el.watchGuildId.value.trim();
  if (!guildId) { el.watchListArea.textContent = 'Enter a Discord Guild ID first.'; return; }
  el.watchListArea.textContent = 'Loading\u2026';
  try {
    const data = await fetchJson('/api/altfinder/watches?guildId=' + encodeURIComponent(guildId), TIMEOUT_STATUS, 1);
    const watches = data.watches || [];
    el.watchListArea.textContent = watches.length === 0
      ? 'No watched characters.'
      : watches.map(function(w){ return w.characterName + (w.channelId ? ' (#' + w.channelId + ')' : ''); }).join('\n');
  } catch(err) { el.watchListArea.textContent = 'Error: ' + (err.message||String(err)); }
}

async function addWatch() {
  const guildId = el.watchGuildId.value.trim();
  const channelId = el.watchChannelId.value.trim();
  const char = el.watchCharInput.value.trim();
  if (!guildId || !char) { el.watchListArea.textContent = 'Enter Guild ID and character name.'; return; }
  try {
    await fetchJson('/api/altfinder/watches', TIMEOUT_STATUS, 1);
    el.watchListArea.textContent = 'Added. Refreshing\u2026';
    await loadWatchlist();
  } catch(_) {
    // POST not available from static site; inform user
    el.watchListArea.textContent = 'Watch management requires the backend board.';
  }
}

el.addWatchBtn.addEventListener('click', addWatch);
el.loadWatchBtn.addEventListener('click', loadWatchlist);

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
refreshOnlineNames();
// Auto-refresh online names every 30 seconds
setInterval(refreshOnlineNames, 30000);
