const $ = (id) => document.getElementById(id);

const ui = {
  apiBase: $("apiBase"),
  status: $("status"),
  error: $("error"),
  output: $("output"),
  healthOutput: $("healthOutput"),
  researchOutput: $("researchOutput"),
  presetName: $("presetName"),
  presetSelect: $("presetSelect"),
  allowNames: $("allowNames"),
  ignoreNames: $("ignoreNames"),
  historyOutput: $("historyOutput"),
  guildName: $("guildName"),
  guildStatus: $("guildStatus"),
  guildOutput: $("guildOutput"),
  guildSavedOutput: $("guildSavedOutput"),
  watchCharacter: $("watchCharacter"),
  watchStatus: $("watchStatus"),
  watchOutput: $("watchOutput"),
  clashCharacters: $("clashCharacters"),
  clashTargets: $("clashTargets"),
  clashFrom: $("clashFrom"),
  clashTo: $("clashTo"),
  clashDistance: $("clashDistance"),
  clashStatus: $("clashStatus"),
  clashOutput: $("clashOutput"),
  healthBadge: $("healthBadge"),
  resultsBody: $("resultsBody"),
  kpiLogins: $("kpiLogins"),
  kpiMatches: $("kpiMatches"),
  kpiRange: $("kpiRange"),
  kpiSave: $("kpiSave")
};

const query = new URLSearchParams(window.location.search);
const queryApi = query.get("api");
const storedApi = localStorage.getItem("altfinder_api_base");
const defaultApiBase = "https://tibia-alt-finder-api.onrender.com";
const initialApiBase = queryApi || storedApi || defaultApiBase;
const WEB_WATCH_GUILD_ID = "web";
const WEB_WATCH_CHANNEL_ID = "web-ui";
const PRESETS_KEY = "altfinder_presets_v1";
const CONF_HISTORY_KEY = "altfinder_conf_history_v1";
const SAVED_GUILDS_KEY = "altfinder_saved_guilds_v1";
let latestMatches = [];
let latestRawMatches = [];
ui.apiBase.value = initialApiBase;

function baseUrl() {
  return ui.apiBase.value.trim().replace(/\/+$/, "");
}

function setStatus(message) {
  ui.status.textContent = message || "";
}

function setError(message) {
  ui.error.textContent = message || "";
}

function saveApiBase() {
  const base = baseUrl();
  localStorage.setItem("altfinder_api_base", base);
}

function parseNameList(raw) {
  return raw.split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);
}

function applyAllowIgnore(matches) {
  const allow = parseNameList(ui.allowNames.value || "");
  const ignore = parseNameList(ui.ignoreNames.value || "");
  return matches.filter((m) => {
    const n = String(m.name || "").trim().toLowerCase();
    if (ignore.includes(n)) return false;
    if (allow.length > 0 && !allow.includes(n)) return false;
    return true;
  });
}

function loadPresets() {
  try {
    return JSON.parse(localStorage.getItem(PRESETS_KEY) || "{}");
  } catch (_) {
    return {};
  }
}

function savePresets(obj) {
  localStorage.setItem(PRESETS_KEY, JSON.stringify(obj));
}

function refreshPresetSelect() {
  const presets = loadPresets();
  const names = Object.keys(presets).sort();
  ui.presetSelect.innerHTML = names.map((n) => "<option>" + escapeHtml(n) + "</option>").join("");
}

function loadConfidenceHistory() {
  try {
    return JSON.parse(localStorage.getItem(CONF_HISTORY_KEY) || "{}");
  } catch (_) {
    return {};
  }
}

function saveConfidenceHistory(obj) {
  localStorage.setItem(CONF_HISTORY_KEY, JSON.stringify(obj));
}

function loadSavedGuilds() {
  try {
    return JSON.parse(localStorage.getItem(SAVED_GUILDS_KEY) || "[]");
  } catch (_) {
    return [];
  }
}

function saveSavedGuilds(list) {
  localStorage.setItem(SAVED_GUILDS_KEY, JSON.stringify(list));
}

function addSavedGuild(name) {
  const trimmed = String(name || "").trim();
  if (!trimmed) return;
  const current = loadSavedGuilds();
  const lower = trimmed.toLowerCase();
  if (!current.some((g) => String(g.name || "").toLowerCase() === lower)) {
    current.push({ name: trimmed, updatedAt: null, online: 0, world: "-", members: 0, onlineCharacters: [] });
    saveSavedGuilds(current);
  }
}

function removeSavedGuild(name) {
  const lower = String(name || "").trim().toLowerCase();
  const current = loadSavedGuilds().filter((g) => String(g.name || "").toLowerCase() !== lower);
  saveSavedGuilds(current);
}

function renderSavedGuilds() {
  const current = loadSavedGuilds();
  if (current.length === 0) {
    ui.guildSavedOutput.textContent = "No saved guilds yet.";
    return;
  }
  ui.guildSavedOutput.textContent = current.map((g) => {
    const onlineChars = (g.onlineCharacters || []).slice(0, 12).join(", ");
    return (
      g.name +
      " | world " + String(g.world || "-") +
      " | online " + String(g.online || 0) + "/" + String(g.members || 0) +
      " | updated " + String(g.updatedAt || "never") +
      "\n  " + (onlineChars || "no online characters listed")
    );
  }).join("\n\n");
}

function appendConfidenceHistory(matches) {
  const history = loadConfidenceHistory();
  const today = new Date().toISOString();
  matches.forEach((m) => {
    const key = String(m.name || "Unknown");
    if (!history[key]) history[key] = [];
    history[key].push({ t: today, c: Number(m.confidence || 0) });
    if (history[key].length > 30) history[key] = history[key].slice(-30);
  });
  saveConfidenceHistory(history);
  renderConfidenceHistory();
}

function renderConfidenceHistory() {
  const history = loadConfidenceHistory();
  const lines = Object.keys(history).sort().slice(0, 20).map((name) => {
    const points = history[name].slice(-10).map((p) => p.c).join(" -> ");
    return name + ": " + points;
  });
  ui.historyOutput.textContent = lines.length ? lines.join("\n") : "No history yet.";
}

function downloadCsv(matches) {
  const cols = ["name","confidence","adjacencies","clashes","logins","hiddenLikely","hiddenScore","sessionSimilarity","evidencePassed","recentTradeDates"];
  const rows = [cols.join(",")].concat(matches.map((m) =>
    cols.map((c) => {
      const v = c === "recentTradeDates" ? (m[c] || []).join("|") : m[c];
      return "\"" + String(v ?? "").replaceAll("\"", "\"\"") + "\"";
    }).join(",")
  ));
  const blob = new Blob([rows.join("\n")], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "altfinder_matches.csv";
  a.click();
  URL.revokeObjectURL(url);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;");
}

function fillKpis(data, trackerStatus) {
  ui.kpiLogins.textContent = String(data.totalLogins ?? "-");
  ui.kpiMatches.textContent = String((data.possibleMatches || []).length);
  ui.kpiRange.textContent = data.dateRange || "-";
  ui.kpiSave.textContent = trackerStatus && trackerStatus.latestWorldSave ? trackerStatus.latestWorldSave : "-";
}

function renderRows(matches) {
  if (!matches || matches.length === 0) {
    ui.resultsBody.innerHTML = '<tr><td colspan="8" class="muted">No matches found.</td></tr>';
    return;
  }
  const rows = matches.map((m) => {
    const tradeDates = (m.recentTradeDates || []).length > 0 ? m.recentTradeDates.join(", ") : "none";
    const hidden = m.hiddenLikely ? ("yes (" + m.hiddenScore + ")") : ("no (" + m.hiddenScore + ")");
    const why = m.explanation || (
      "adj=" + String(m.adjacencies ?? "-") +
      ", logins=" + String(m.logins ?? "-") +
      ", sessionSimilarity=" + String(m.sessionSimilarity ?? "-") +
      ", evidence=" + (m.evidencePassed ? "pass" : "low")
    );
    return (
      "<tr>" +
        "<td>" + escapeHtml(m.name || "Unknown") + "</td>" +
        "<td><span class=\"score-pill\">" + escapeHtml(String(m.confidence ?? "-")) + "</span></td>" +
        "<td>" + escapeHtml(String(m.adjacencies ?? "-")) + "</td>" +
        "<td>" + escapeHtml(String(m.clashes ?? "-")) + "</td>" +
        "<td>" + escapeHtml(String(m.logins ?? "-")) + "</td>" +
        "<td>" + escapeHtml(hidden) + "</td>" +
        "<td>" + escapeHtml(tradeDates) + "</td>" +
        "<td>" + escapeHtml(why) + "</td>" +
      "</tr>"
    );
  }).join("");
  ui.resultsBody.innerHTML = rows;
}

async function fetchJson(path) {
  return fetchJsonWithInit(path, {});
}

async function fetchJsonWithInit(path, init) {
  const url = baseUrl() + path;
  const res = await fetch(url, init);
  const contentType = res.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) {
    const text = await res.text();
    const renderHint =
      res.status === 404
        ? " Hint: This usually means the Backend URL is not your Alt Finder API web service. In Render, use the public URL of the service running altfinder (not a worker/static site URL)."
        : "";
    throw new Error(
      "Backend URL is not the Alt Finder API (" +
      res.status +
      "). Response from " +
      url +
      ": " +
      text.slice(0, 140) +
      renderHint
    );
  }
  const data = await res.json();
  if (!res.ok) {
    throw new Error(data.error ? (data.error + " | " + (data.details || []).join("; ")) : ("Request failed (" + res.status + ")"));
  }
  return data;
}

async function loadStatus() {
  try {
    saveApiBase();
    const [health, trackerStatus] = await Promise.all([
      fetchJson("/api/altfinder/health"),
      fetchJson("/api/altfinder/status")
    ]);
    ui.healthBadge.textContent = "API: " + (health.status || "unknown");
    ui.kpiSave.textContent = trackerStatus.latestWorldSave || "-";
    ui.healthOutput.textContent =
      "API status: " + (health.status || "unknown") + "\n" +
      "Online history rows: " + String(trackerStatus.onlineHistoryRows ?? "-") + "\n" +
      "Latest world save: " + String(trackerStatus.latestWorldSave ?? "-") + "\n" +
      "Latest world save age (s): " + String(trackerStatus.latestWorldSaveAgeSeconds ?? "-") + "\n" +
      "Bazaar cooldown (s): " + String(trackerStatus.bazaarCooldownSeconds ?? 0) + "\n" +
      "Cache size: " + String(trackerStatus.queryCacheSize ?? "-") + "\n" +
      "Cache TTL (s): " + String(trackerStatus.queryCacheTtlSeconds ?? "-") + "\n" +
      "Status latency (ms): " + String(trackerStatus.statusLatencyMs ?? "-");
    await loadResearch();
  } catch (err) {
    ui.healthBadge.textContent = "API: unavailable";
    ui.healthOutput.textContent = "Health fetch failed: " + (err.message || String(err));
    setError(err.message || String(err));
  }
}

async function loadResearch() {
  try {
    const data = await fetchJson("/api/altfinder/research?limit=25");
    if (!Array.isArray(data) || data.length === 0) {
      ui.researchOutput.textContent = "No research history yet.";
      return;
    }
    ui.researchOutput.textContent = data.map((r) => {
      const who = (r.searchedCharacters || []).join(", ");
      const against = (r.targetCharacters || []).join(", ");
      const scope = against ? (" | targets: " + against) : "";
      return "[" + (r.createdAt || "-") + "] " + r.runType + " | " + who + scope +
        " | matches " + String(r.matchCount ?? 0) +
        " | distance " + String(r.distanceMinutes ?? 0) + "m";
    }).join("\n");
  } catch (err) {
    ui.researchOutput.textContent = "Research history unavailable: " + (err.message || String(err));
  }
}

async function runSearch() {
  setError("");
  setStatus("Searching...");
  ui.output.textContent = "Loading...";
  saveApiBase();

  const chars = $("characters").value.trim();
  const from = $("from").value.trim();
  const to = $("to").value.trim();
  const distance = $("distance").value.trim();
  const includeClashes = $("clashes").value;

  if (!chars) {
    setError("Characters is required.");
    setStatus("");
    ui.output.textContent = "No search yet.";
    return;
  }

  const q = new URLSearchParams();
  q.set("characters", chars);
  if (from) q.set("from", from);
  if (to) q.set("to", to);
  if (distance) q.set("distance", distance);
  q.set("includeClashes", includeClashes);
  q.set("format", "detailed");

  try {
    const [data, trackerStatus] = await Promise.all([
      fetchJson("/api/altfinder/alts?" + q.toString()),
      fetchJson("/api/altfinder/status")
    ]);
    latestRawMatches = data.possibleMatches || [];
    const filteredMatches = applyAllowIgnore(latestRawMatches);
    latestMatches = filteredMatches;
    fillKpis(data, trackerStatus);
    ui.kpiMatches.textContent = String(filteredMatches.length);
    renderRows(filteredMatches);
    appendConfidenceHistory(filteredMatches);
    ui.output.textContent = data.formattedText || JSON.stringify(data, null, 2);
    setStatus("Done.");
  } catch (err) {
    setError(err.message || String(err));
    ui.output.textContent = "Search failed.";
    ui.resultsBody.innerHTML = '<tr><td colspan="8" class="muted">Search failed.</td></tr>';
    setStatus("");
  }
}

async function runGuildSearch() {
  ui.guildStatus.textContent = "Searching...";
  ui.guildOutput.textContent = "Loading...";
  const name = ui.guildName.value.trim();
  if (!name) {
    ui.guildStatus.textContent = "";
    ui.guildOutput.textContent = "Guild name is required.";
    return;
  }
  try {
    const q = new URLSearchParams();
    q.set("name", name);
    const data = await fetchJson("/api/altfinder/guild?" + q.toString());
    const onlineNames = (data.onlineCharacters || []).join(", ");
    ui.guildOutput.textContent =
      "Guild: " + data.name + "\n" +
      "World: " + data.world + "\n" +
      "Members: " + data.members + "\n" +
      "Online: " + data.online + "\n" +
      "Online characters: " + (onlineNames || "none");
    ui.guildStatus.textContent = "Done.";
  } catch (err) {
    ui.guildStatus.textContent = "";
    ui.guildOutput.textContent = err.message || String(err);
  }
}

async function refreshSavedGuilds() {
  const current = loadSavedGuilds();
  if (current.length === 0) {
    renderSavedGuilds();
    return;
  }
  ui.guildStatus.textContent = "Refreshing saved guilds...";
  for (let i = 0; i < current.length; i += 1) {
    const g = current[i];
    try {
      const q = new URLSearchParams();
      q.set("name", g.name);
      const data = await fetchJson("/api/altfinder/guild?" + q.toString());
      current[i] = {
        name: g.name,
        world: data.world || "-",
        members: Number(data.members || 0),
        online: Number(data.online || 0),
        onlineCharacters: data.onlineCharacters || [],
        updatedAt: new Date().toISOString()
      };
    } catch (_) {
      current[i] = {
        name: g.name,
        world: g.world || "-",
        members: Number(g.members || 0),
        online: Number(g.online || 0),
        onlineCharacters: g.onlineCharacters || [],
        updatedAt: g.updatedAt || null
      };
    }
  }
  saveSavedGuilds(current);
  renderSavedGuilds();
  ui.guildStatus.textContent = "Saved guilds refreshed.";
}

async function listWatchlist() {
  ui.watchStatus.textContent = "Loading...";
  ui.watchOutput.textContent = "Loading...";
  try {
    const q = new URLSearchParams();
    q.set("guildId", WEB_WATCH_GUILD_ID);
    const data = await fetchJson("/api/altfinder/watchlist?" + q.toString());
    const rows = data.watches || [];
    if (rows.length === 0) {
      ui.watchOutput.textContent = "No watchlist entries.";
    } else {
      ui.watchOutput.textContent = rows.map((w) =>
        w.characterName +
        " | threshold " + w.confidenceThreshold +
        " | distance " + w.distance +
        " | window " + w.windowDays + "d" +
        " | include clashes " + w.includeClashes
      ).join("\n");
    }
    ui.watchStatus.textContent = "Done.";
  } catch (err) {
    ui.watchStatus.textContent = "";
    ui.watchOutput.textContent = err.message || String(err);
  }
}

async function addWatch() {
  const characterName = ui.watchCharacter.value.trim();
  if (!characterName) {
    ui.watchOutput.textContent = "Character name is required.";
    return;
  }
  ui.watchStatus.textContent = "Saving...";
  ui.watchOutput.textContent = "Saving...";
  try {
    const q = new URLSearchParams();
    q.set("guildId", WEB_WATCH_GUILD_ID);
    q.set("channelId", WEB_WATCH_CHANNEL_ID);
    q.set("character", characterName);
    await fetchJson("/api/altfinder/watchlist/add?" + q.toString());
    ui.watchStatus.textContent = "Saved.";
    await listWatchlist();
  } catch (err) {
    ui.watchStatus.textContent = "";
    ui.watchOutput.textContent = err.message || String(err);
  }
}

async function removeWatch() {
  const characterName = ui.watchCharacter.value.trim();
  if (!characterName) {
    ui.watchOutput.textContent = "Character name is required.";
    return;
  }
  ui.watchStatus.textContent = "Removing...";
  ui.watchOutput.textContent = "Removing...";
  try {
    const q = new URLSearchParams();
    q.set("guildId", WEB_WATCH_GUILD_ID);
    q.set("character", characterName);
    const data = await fetchJson("/api/altfinder/watchlist/remove?" + q.toString());
    ui.watchStatus.textContent = data.removed ? "Removed." : "Not found.";
    await listWatchlist();
  } catch (err) {
    ui.watchStatus.textContent = "";
    ui.watchOutput.textContent = err.message || String(err);
  }
}

async function runClashes() {
  ui.clashStatus.textContent = "Scanning...";
  ui.clashOutput.textContent = "Loading...";
  const characters = ui.clashCharacters.value.trim();
  const targets = ui.clashTargets.value.trim();
  const from = ui.clashFrom.value.trim();
  const to = ui.clashTo.value.trim();
  const distance = ui.clashDistance.value.trim();
  if (!characters || !targets) {
    ui.clashStatus.textContent = "";
    ui.clashOutput.textContent = "Source and target characters are required.";
    return;
  }
  try {
    const q = new URLSearchParams();
    q.set("characters", characters);
    q.set("targets", targets);
    if (from) q.set("from", from);
    if (to) q.set("to", to);
    if (distance) q.set("distance", distance);
    const data = await fetchJson("/api/altfinder/clashes?" + q.toString());
    ui.clashOutput.textContent = data.formattedText || "No clashes found.";
    ui.clashStatus.textContent = "Done.";
  } catch (err) {
    ui.clashStatus.textContent = "";
    ui.clashOutput.textContent = err.message || String(err);
  }
}

$("runBtn").addEventListener("click", runSearch);
$("guildSearchBtn").addEventListener("click", runGuildSearch);
$("guildSaveBtn").addEventListener("click", () => {
  const name = ui.guildName.value.trim();
  if (!name) return;
  addSavedGuild(name);
  renderSavedGuilds();
  ui.guildStatus.textContent = "Guild saved.";
});
$("guildRefreshAllBtn").addEventListener("click", refreshSavedGuilds);
$("watchAddBtn").addEventListener("click", addWatch);
$("watchRemoveBtn").addEventListener("click", removeWatch);
$("watchListBtn").addEventListener("click", listWatchlist);
$("clashRunBtn").addEventListener("click", runClashes);
$("csvBtn").addEventListener("click", () => downloadCsv(latestMatches));
$("presetSaveBtn").addEventListener("click", () => {
  const name = ui.presetName.value.trim();
  if (!name) return;
  const presets = loadPresets();
  presets[name] = {
    characters: $("characters").value.trim(),
    from: $("from").value.trim(),
    to: $("to").value.trim(),
    distance: $("distance").value.trim(),
    clashes: $("clashes").value,
    allow: ui.allowNames.value.trim(),
    ignore: ui.ignoreNames.value.trim()
  };
  savePresets(presets);
  refreshPresetSelect();
});
$("presetLoadBtn").addEventListener("click", () => {
  const name = ui.presetSelect.value;
  const preset = loadPresets()[name];
  if (!preset) return;
  $("characters").value = preset.characters || "";
  $("from").value = preset.from || "";
  $("to").value = preset.to || "";
  $("distance").value = preset.distance || "0";
  $("clashes").value = preset.clashes || "false";
  ui.allowNames.value = preset.allow || "";
  ui.ignoreNames.value = preset.ignore || "";
});
$("presetDeleteBtn").addEventListener("click", () => {
  const name = ui.presetSelect.value;
  if (!name) return;
  const presets = loadPresets();
  delete presets[name];
  savePresets(presets);
  refreshPresetSelect();
});
$("clearBtn").addEventListener("click", () => {
  $("characters").value = "";
  $("from").value = "";
  $("to").value = "";
  $("distance").value = "0";
  $("clashes").value = "false";
  setStatus("");
  setError("");
  ui.output.textContent = "No search yet.";
  ui.resultsBody.innerHTML = '<tr><td colspan="8" class="muted">No search yet.</td></tr>';
  latestRawMatches = [];
  latestMatches = [];
  ui.kpiLogins.textContent = "-";
  ui.kpiMatches.textContent = "-";
  ui.kpiRange.textContent = "-";
});
ui.allowNames.addEventListener("change", () => {
  latestMatches = applyAllowIgnore(latestRawMatches);
  renderRows(latestMatches);
});
ui.ignoreNames.addEventListener("change", () => {
  latestMatches = applyAllowIgnore(latestRawMatches);
  renderRows(latestMatches);
});

ui.apiBase.addEventListener("change", loadStatus);
$("characters").addEventListener("keydown", (e) => {
  if (e.key === "Enter") runSearch();
});
$("guildName").addEventListener("keydown", (e) => {
  if (e.key === "Enter") runGuildSearch();
});

refreshPresetSelect();
renderConfidenceHistory();
renderSavedGuilds();
loadStatus();
