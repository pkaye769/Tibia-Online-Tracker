const $ = (id) => document.getElementById(id);

// ── UI refs ──────────────────────────────────────────────────────────────────
const ui = {
  characters:       $("characters"),
  distance:         $("distance"),
  includeClashes:   $("includeClashes"),
  runBtn:           $("runBtn"),
  clearBtn:         $("clearBtn"),
  status:           $("status"),
  error:            $("error"),
  health:           $("health"),
  summary:          $("summary"),
  resultsBody:      $("resultsBody"),
  statTotalLogins:  $("statTotalLogins"),
  statMatches:      $("statMatches"),
  statDateRange:    $("statDateRange"),
  statLastWorldSave:$("statLastWorldSave"),
  charPanel:        $("charPanel"),
  charPanelName:    $("charPanelName"),
  charPanelContent: $("charPanelContent"),
  charPanelClose:   $("charPanelClose"),
};

// ── Config ───────────────────────────────────────────────────────────────────
const DEFAULT_DISTANCE_KEY = "altfinder_default_distance_v1";
const API_BASE_KEY         = "altfinder_api_base";
const DEFAULT_API_BASE     = "https://tibia-alt-finder-api.onrender.com";

const apiFromQuery  = new URLSearchParams(window.location.search).get("api");
const storedApi     = localStorage.getItem(API_BASE_KEY);
let   apiBase       = (apiFromQuery || storedApi || DEFAULT_API_BASE).trim();
if (apiFromQuery) localStorage.setItem(API_BASE_KEY, apiBase);

const storedDist = localStorage.getItem(DEFAULT_DISTANCE_KEY);
if (storedDist !== null && storedDist !== "") ui.distance.value = storedDist;

// ── Helpers ──────────────────────────────────────────────────────────────────
function baseUrl()         { return apiBase.replace(/\/+$/, ""); }
function setStatus(t)      { ui.status.textContent = t || ""; }
function setError(t)       { ui.error.textContent  = t || ""; }
function parseNames(raw)   { return raw.split(",").map(x => x.trim()).filter(Boolean); }
function sleep(ms)         { return new Promise(r => setTimeout(r, ms)); }

function escapeHtml(v) {
  return String(v)
    .replaceAll("&", "&amp;").replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;").replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

// ── API fetch with retry ─────────────────────────────────────────────────────
async function fetchJson(path, timeoutMs = 15000, maxAttempts = 3) {
  const url = baseUrl() + path;
  let lastErr = null;
  let res = null;

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      res = await fetch(url, { signal: ctrl.signal });
      clearTimeout(timer);
      if (res.status < 500) break;
      lastErr = new Error(`HTTP ${res.status}`);
    } catch (e) {
      clearTimeout(timer);
      lastErr = e;
    }
    if (attempt < maxAttempts) await sleep(Math.min(6000, 1000 * 2 ** (attempt - 1)));
  }

  if (!res) {
    const hint = lastErr?.name === "AbortError" ? "Request timed out." : "Could not connect to API.";
    throw new Error(`${hint} (${url})`);
  }

  const raw = await res.text();
  if (/response timed out/i.test(raw))
    throw new Error("Backend timed out. Try narrowing your search.");

  const ct = res.headers.get("content-type") || "";
  if (!ct.includes("application/json"))
    throw new Error(`Expected JSON (HTTP ${res.status}): ${raw.slice(0, 200)}`);

  let body;
  try { body = JSON.parse(raw); } catch (_) {
    throw new Error(`Invalid JSON (HTTP ${res.status}): ${raw.slice(0, 200)}`);
  }

  if (!res.ok) {
    const details = Array.isArray(body.details) && body.details.length
      ? ` (${body.details.join("; ")})` : "";
    throw new Error(body.message || body.error || `HTTP ${res.status}${details}`);
  }
  return body;
}

// ── Health check ─────────────────────────────────────────────────────────────
async function checkHealth() {
  try {
    const data = await fetchJson("/api/altfinder/health", 10000, 2);
    ui.health.textContent = `API health: ${data.status || "ok"}`;
    ui.health.className = "health ok";
  } catch (_) {
    ui.health.textContent = "API health: unavailable";
    ui.health.className = "health bad";
  }
  // Also grab Last World Save from status endpoint
  try {
    const s = await fetchJson("/api/altfinder/status", 10000, 1);
    if (s.latestWorldSave && ui.statLastWorldSave.textContent === "-") {
      ui.statLastWorldSave.textContent = s.latestWorldSave.slice(0, 10);
    }
  } catch (_) {}
}

// ── Render results table ─────────────────────────────────────────────────────
function renderResults(matches) {
  if (!matches || matches.length === 0) {
    ui.resultsBody.innerHTML = '<tr><td colspan="7" class="muted-cell">No matches found.</td></tr>';
    return;
  }

  ui.resultsBody.innerHTML = matches.map(m => {
    const conf = Number(m.confidence ?? 0);
    const pillClass = conf >= 70 ? "" : conf >= 40 ? " mid" : " low";
    const hidden = m.hiddenLikely ? `yes (${m.hiddenScore})` : `no (${m.hiddenScore})`;
    const traded = Array.isArray(m.recentTradeDates) && m.recentTradeDates.length > 0;
    const tradeCell = traded
      ? `${escapeHtml(m.recentTradeDates[0])}<span class="trade-badge">TRADED</span>`
      : "none";

    return "<tr>" +
      `<td style="cursor:pointer;font-weight:600" data-name="${escapeHtml(m.name || "")}">${escapeHtml(m.name || "Unknown")}</td>` +
      `<td><span class="score-pill${pillClass}">${escapeHtml(String(conf))}</span></td>` +
      `<td>${escapeHtml(String(m.adjacencies ?? "-"))}</td>` +
      `<td>${escapeHtml(String(m.clashes ?? "-"))}</td>` +
      `<td>${escapeHtml(String(m.logins ?? "-"))}</td>` +
      `<td>${escapeHtml(hidden)}</td>` +
      `<td>${tradeCell}</td>` +
      "</tr>";
  }).join("");
}

// ── Run search ───────────────────────────────────────────────────────────────
async function run() {
  setError("");
  setStatus("Searching…");
  hideCharPanel();

  const names = parseNames(ui.characters.value || "");
  if (names.length === 0) { setStatus(""); setError("Enter at least one character."); return; }

  const distance      = Math.max(0, Number(ui.distance.value || 0));
  const inclClashes   = ui.includeClashes.value === "true";

  localStorage.setItem(DEFAULT_DISTANCE_KEY, String(distance));

  const params = new URLSearchParams({
    characters:    names.join(","),
    distance:      String(distance),
    includeClashes: inclClashes ? "true" : "false",
    format:        "detailed",
  });

  try {
    const data = await fetchJson(`/api/altfinder/alts?${params}`, 90000, 2);

    // Stat boxes
    ui.statTotalLogins.textContent  = String(data.totalLogins ?? "-");
    ui.statMatches.textContent      = String((data.possibleMatches || []).length);
    ui.statDateRange.textContent    = data.dateRange || "-";

    // Last World Save from status (if not already loaded)
    if (ui.statLastWorldSave.textContent === "-") {
      fetchJson("/api/altfinder/status", 10000, 1)
        .then(s => { if (s.latestWorldSave) ui.statLastWorldSave.textContent = s.latestWorldSave.slice(0, 10); })
        .catch(() => {});
    }

    // Results
    renderResults(data.possibleMatches || []);
    ui.summary.textContent = data.formattedText || JSON.stringify(data, null, 2);
    setStatus("Done.");
  } catch (err) {
    setStatus("");
    setError(err instanceof Error ? err.message : String(err));
    ui.resultsBody.innerHTML = '<tr><td colspan="7" class="muted-cell">Search failed.</td></tr>';
  }
}

// ── Clear ────────────────────────────────────────────────────────────────────
function clearAll() {
  setStatus(""); setError("");
  ui.summary.textContent = "No search yet.";
  ui.resultsBody.innerHTML = '<tr><td colspan="7" class="muted-cell">No search yet.</td></tr>';
  ui.statTotalLogins.textContent   = "-";
  ui.statMatches.textContent       = "-";
  ui.statDateRange.textContent     = "-";
  ui.statLastWorldSave.textContent = "-";
  hideCharPanel();
}

// ── Character detail panel ───────────────────────────────────────────────────
function hideCharPanel() {
  ui.charPanel.classList.remove("visible");
}

function showCharPanelLoading(name) {
  ui.charPanelName.textContent = name;
  ui.charPanelContent.innerHTML = '<span class="char-panel-loading">Fetching from TibiaData &amp; Exevopan…</span>';
  ui.charPanel.classList.add("visible");
  ui.charPanel.scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function field(label, value) {
  return `<div>
    <div class="char-field-label">${escapeHtml(label)}</div>
    <div class="char-field-value">${escapeHtml(value || "-")}</div>
  </div>`;
}

async function loadCharacterDetail(name) {
  showCharPanelLoading(name);
  try {
    const data = await fetchJson(`/api/altfinder/character?name=${encodeURIComponent(name)}`, 20000, 2);

    const guildText = data.guild
      ? `${data.guild}${data.guildRank ? ` (${data.guildRank})` : ""}`
      : "-";
    const formerText = Array.isArray(data.formerNames) && data.formerNames.length
      ? data.formerNames.join(", ") : "-";
    const lastLoginText = data.lastLogin ? data.lastLogin.slice(0, 16).replace("T", " ") : "-";

    const traded = Array.isArray(data.recentTradeDates) && data.recentTradeDates.length > 0;
    const tradeAlert = traded
      ? `<div class="trade-alert">⚠ RECENTLY TRADED — ${escapeHtml(data.recentTradeDates.join(", "))}</div>`
      : "";
    const tradeError = data.tradedCheckError
      ? `<div class="trade-alert" style="background:#fde8d0;border-color:#d06010;color:#6c3008">⚠ Trade check error — Exevopan may be rate-limited.</div>`
      : "";

    ui.charPanelName.textContent = data.name || name;
    ui.charPanelContent.innerHTML = `
      <div class="char-fields">
        ${field("Level",        String(data.level || "-"))}
        ${field("Vocation",     data.vocation)}
        ${field("World",        data.world)}
        ${field("Sex",          data.sex)}
        ${field("Guild",        guildText)}
        ${field("Last Login",   lastLoginText)}
        ${field("Former Names", formerText)}
      </div>
      ${tradeAlert}${tradeError}
      <div class="char-panel-links">
        <a class="char-link" href="${escapeHtml(data.tibiaComUrl)}" target="_blank" rel="noopener noreferrer">Tibia.com ↗</a>
        <a class="char-link" href="${escapeHtml(data.exevopanUrl)}" target="_blank" rel="noopener noreferrer">Exevopan ↗</a>
      </div>`;
  } catch (err) {
    ui.charPanelContent.innerHTML =
      `<span class="char-panel-error">Failed to load: ${escapeHtml(err instanceof Error ? err.message : String(err))}</span>`;
  }
}

// ── Event listeners ──────────────────────────────────────────────────────────
ui.runBtn.addEventListener("click", run);
ui.clearBtn.addEventListener("click", clearAll);
ui.charPanelClose.addEventListener("click", hideCharPanel);
ui.characters.addEventListener("keydown", e => { if (e.key === "Enter") run(); });
ui.distance.addEventListener("change", () => {
  localStorage.setItem(DEFAULT_DISTANCE_KEY, String(Math.max(0, Number(ui.distance.value || 0))));
});

// Click any result row to load character details
ui.resultsBody.addEventListener("click", e => {
  const td = e.target.closest("td[data-name]");
  if (td) loadCharacterDetail(td.dataset.name);
});

// ── Init ─────────────────────────────────────────────────────────────────────
checkHealth();
