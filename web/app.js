const $ = (id) => document.getElementById(id);

const ui = {
  apiBase: $("apiBase"),
  status: $("status"),
  error: $("error"),
  output: $("output"),
  guildName: $("guildName"),
  guildStatus: $("guildStatus"),
  guildOutput: $("guildOutput"),
  watchGuildId: $("watchGuildId"),
  watchChannelId: $("watchChannelId"),
  watchCharacter: $("watchCharacter"),
  watchStatus: $("watchStatus"),
  watchOutput: $("watchOutput"),
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

function saveWatchDefaults() {
  localStorage.setItem("altfinder_watch_guild_id", ui.watchGuildId.value.trim());
  localStorage.setItem("altfinder_watch_channel_id", ui.watchChannelId.value.trim());
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
    ui.resultsBody.innerHTML = '<tr><td colspan="7" class="muted">No matches found.</td></tr>';
    return;
  }
  const rows = matches.map((m) => {
    const tradeDates = (m.recentTradeDates || []).length > 0 ? m.recentTradeDates.join(", ") : "none";
    const hidden = m.hiddenLikely ? ("yes (" + m.hiddenScore + ")") : ("no (" + m.hiddenScore + ")");
    return (
      "<tr>" +
        "<td>" + escapeHtml(m.name || "Unknown") + "</td>" +
        "<td><span class=\"score-pill\">" + escapeHtml(String(m.confidence ?? "-")) + "</span></td>" +
        "<td>" + escapeHtml(String(m.adjacencies ?? "-")) + "</td>" +
        "<td>" + escapeHtml(String(m.clashes ?? "-")) + "</td>" +
        "<td>" + escapeHtml(String(m.logins ?? "-")) + "</td>" +
        "<td>" + escapeHtml(hidden) + "</td>" +
        "<td>" + escapeHtml(tradeDates) + "</td>" +
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

function loadWatchDefaults() {
  const guild = localStorage.getItem("altfinder_watch_guild_id");
  const channel = localStorage.getItem("altfinder_watch_channel_id");
  if (guild) ui.watchGuildId.value = guild;
  if (channel) ui.watchChannelId.value = channel;
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
  } catch (err) {
    ui.healthBadge.textContent = "API: unavailable";
    setError(err.message || String(err));
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
    fillKpis(data, trackerStatus);
    renderRows(data.possibleMatches || []);
    ui.output.textContent = data.formattedText || JSON.stringify(data, null, 2);
    setStatus("Done.");
  } catch (err) {
    setError(err.message || String(err));
    ui.output.textContent = "Search failed.";
    ui.resultsBody.innerHTML = '<tr><td colspan="7" class="muted">Search failed.</td></tr>';
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
    ui.guildOutput.textContent =
      "Guild: " + data.name + "\n" +
      "World: " + data.world + "\n" +
      "Members: " + data.members + "\n" +
      "Online: " + data.online;
    ui.guildStatus.textContent = "Done.";
  } catch (err) {
    ui.guildStatus.textContent = "";
    ui.guildOutput.textContent = err.message || String(err);
  }
}

async function listWatchlist() {
  saveWatchDefaults();
  const guildId = ui.watchGuildId.value.trim();
  if (!guildId) {
    ui.watchOutput.textContent = "Discord Guild ID is required.";
    return;
  }
  ui.watchStatus.textContent = "Loading...";
  ui.watchOutput.textContent = "Loading...";
  try {
    const q = new URLSearchParams();
    q.set("guildId", guildId);
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
  saveWatchDefaults();
  const guildId = ui.watchGuildId.value.trim();
  const channelId = ui.watchChannelId.value.trim();
  const characterName = ui.watchCharacter.value.trim();
  if (!guildId || !channelId || !characterName) {
    ui.watchOutput.textContent = "Guild ID, Channel ID, and Character name are required.";
    return;
  }
  ui.watchStatus.textContent = "Saving...";
  ui.watchOutput.textContent = "Saving...";
  try {
    await fetchJsonWithInit("/api/altfinder/watchlist", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        guildId: guildId,
        channelId: channelId,
        characterName: characterName
      })
    });
    ui.watchStatus.textContent = "Saved.";
    await listWatchlist();
  } catch (err) {
    ui.watchStatus.textContent = "";
    ui.watchOutput.textContent = err.message || String(err);
  }
}

async function removeWatch() {
  saveWatchDefaults();
  const guildId = ui.watchGuildId.value.trim();
  const characterName = ui.watchCharacter.value.trim();
  if (!guildId || !characterName) {
    ui.watchOutput.textContent = "Guild ID and Character name are required.";
    return;
  }
  ui.watchStatus.textContent = "Removing...";
  ui.watchOutput.textContent = "Removing...";
  try {
    const q = new URLSearchParams();
    q.set("guildId", guildId);
    q.set("character", characterName);
    const data = await fetchJsonWithInit("/api/altfinder/watchlist?" + q.toString(), {
      method: "DELETE"
    });
    ui.watchStatus.textContent = data.removed ? "Removed." : "Not found.";
    await listWatchlist();
  } catch (err) {
    ui.watchStatus.textContent = "";
    ui.watchOutput.textContent = err.message || String(err);
  }
}

$("runBtn").addEventListener("click", runSearch);
$("guildSearchBtn").addEventListener("click", runGuildSearch);
$("watchAddBtn").addEventListener("click", addWatch);
$("watchRemoveBtn").addEventListener("click", removeWatch);
$("watchListBtn").addEventListener("click", listWatchlist);
$("clearBtn").addEventListener("click", () => {
  $("characters").value = "";
  $("from").value = "";
  $("to").value = "";
  $("distance").value = "0";
  $("clashes").value = "false";
  setStatus("");
  setError("");
  ui.output.textContent = "No search yet.";
  ui.resultsBody.innerHTML = '<tr><td colspan="7" class="muted">No search yet.</td></tr>';
  ui.kpiLogins.textContent = "-";
  ui.kpiMatches.textContent = "-";
  ui.kpiRange.textContent = "-";
});

ui.apiBase.addEventListener("change", loadStatus);
ui.watchGuildId.addEventListener("change", saveWatchDefaults);
ui.watchChannelId.addEventListener("change", saveWatchDefaults);
$("characters").addEventListener("keydown", (e) => {
  if (e.key === "Enter") runSearch();
});

loadWatchDefaults();
loadStatus();
