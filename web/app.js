const $ = (id) => document.getElementById(id);

const ui = {
  apiBase: $("apiBase"),
  mode: $("mode"),
  distance: $("distance"),
  includeClashes: $("includeClashes"),
  strictMode: $("strictMode"),
  characters: $("characters"),
  from: $("from"),
  to: $("to"),
  runBtn: $("runBtn"),
  clearBtn: $("clearBtn"),
  status: $("status"),
  error: $("error"),
  health: $("health"),
  summary: $("summary"),
  results: $("results"),
  guildName: $("guildName"),
  guildSearchBtn: $("guildSearchBtn"),
  guildSaveBtn: $("guildSaveBtn"),
  guildStatus: $("guildStatus"),
  guildOutput: $("guildOutput"),
  savedCharacter: $("savedCharacter"),
  savedCharacterSelect: $("savedCharacterSelect"),
  savedCharactersOutput: $("savedCharactersOutput"),
  charAddBtn: $("charAddBtn"),
  charUseBtn: $("charUseBtn"),
  charRemoveBtn: $("charRemoveBtn"),
  savedGuildSelect: $("savedGuildSelect"),
  savedGuildsOutput: $("savedGuildsOutput"),
  guildRefreshBtn: $("guildRefreshBtn"),
  guildUseBtn: $("guildUseBtn"),
  guildRemoveBtn: $("guildRemoveBtn"),
  watchGuildId: $("watchGuildId"),
  watchChannelId: $("watchChannelId"),
  watchCharacter: $("watchCharacter"),
  watchDistance: $("watchDistance"),
  watchIncludeClashes: $("watchIncludeClashes"),
  watchThreshold: $("watchThreshold"),
  watchWindowDays: $("watchWindowDays"),
  watchSelect: $("watchSelect"),
  watchAddBtn: $("watchAddBtn"),
  watchRefreshBtn: $("watchRefreshBtn"),
  watchRunBtn: $("watchRunBtn"),
  watchUseBtn: $("watchUseBtn"),
  watchRemoveBtn: $("watchRemoveBtn"),
  watchStatus: $("watchStatus"),
  watchOutput: $("watchOutput")
};

const SAVED_CHARS_KEY = "altfinder_saved_chars_v1";
const SAVED_GUILDS_KEY = "altfinder_saved_guilds_v2";
const DEFAULT_DISTANCE_KEY = "altfinder_default_distance_v1";
const STRICT_MODE_KEY = "altfinder_strict_mode_v1";
const WATCH_GUILD_ID_KEY = "altfinder_watch_guild_id_v1";
const WATCH_CHANNEL_ID_KEY = "altfinder_watch_channel_id_v1";
const storedApi = localStorage.getItem("altfinder_api_base");
const defaultApiBase = "https://tibia-alt-finder-api.onrender.com";
ui.apiBase.value = storedApi || defaultApiBase;
const storedDistance = localStorage.getItem(DEFAULT_DISTANCE_KEY);
if (storedDistance !== null && storedDistance !== "") {
  ui.distance.value = storedDistance;
} else {
  ui.distance.value = "1";
}
ui.strictMode.checked = localStorage.getItem(STRICT_MODE_KEY) === "true";
ui.watchGuildId.value = localStorage.getItem(WATCH_GUILD_ID_KEY) || "";
ui.watchChannelId.value = localStorage.getItem(WATCH_CHANNEL_ID_KEY) || "";

function syncStrictModeControls() {
  if (ui.strictMode.checked) {
    ui.distance.value = "0";
    ui.includeClashes.value = "false";
    ui.distance.disabled = true;
    ui.includeClashes.disabled = true;
  } else {
    ui.distance.disabled = false;
    ui.includeClashes.disabled = false;
    const stored = localStorage.getItem(DEFAULT_DISTANCE_KEY);
    ui.distance.value = stored !== null && stored !== "" ? stored : "1";
  }
}

syncStrictModeControls();

function baseUrl() {
  return (ui.apiBase.value || "").trim().replace(/\/+$/, "");
}

function setStatus(text) {
  ui.status.textContent = text || "";
}

function setError(text, showDirectBoardLink = false) {
  ui.error.textContent = "";
  if (!text) return;

  const message = document.createElement("div");
  message.textContent = text;
  ui.error.appendChild(message);

  if (showDirectBoardLink) {
    const link = document.createElement("a");
    link.href = `${baseUrl()}/altfinder`;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = "Open backend-hosted board";
    ui.error.appendChild(link);
  }
}

function parseNames(raw) {
  return raw.split(",").map((x) => x.trim()).filter(Boolean);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function loadSavedCharacters() {
  try {
    return JSON.parse(localStorage.getItem(SAVED_CHARS_KEY) || "[]");
  } catch (_) {
    return [];
  }
}

function saveSavedCharacters(list) {
  localStorage.setItem(SAVED_CHARS_KEY, JSON.stringify(list));
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

function refreshCharacterSelect() {
  const chars = loadSavedCharacters();
  ui.savedCharacterSelect.innerHTML = chars.map((c) => `<option>${c}</option>`).join("");
  ui.savedCharactersOutput.textContent = chars.length > 0 ? chars.join("\n") : "No saved characters yet.";
}

function refreshGuildSelectAndPanel() {
  const guilds = loadSavedGuilds();
  ui.savedGuildSelect.innerHTML = guilds.map((g) => `<option>${g.name}</option>`).join("");
  if (guilds.length === 0) {
    ui.savedGuildsOutput.textContent = "No saved guilds yet.";
    return;
  }
  ui.savedGuildsOutput.textContent = guilds.map((g) => {
    const online = (g.onlineCharacters || []).join(", ") || "none";
    return `${g.name} | ${g.world || "-"} | online ${g.online || 0}/${g.members || 0}\n  ${online}`;
  }).join("\n\n");
}

async function fetchJson(path) {
  const url = baseUrl() + path;
  const maxAttempts = 5;
  const timeoutMs = path.startsWith("/api/altfinder/alts") ? 60000 : 15000;
  let lastError = null;
  let res = null;

  async function fetchWithTimeout(targetUrl, ms) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), ms);
    try {
      return await fetch(targetUrl, { signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      res = await fetchWithTimeout(url, timeoutMs);
      if (res.status < 500) {
        break;
      }
      lastError = new Error(`HTTP ${res.status}`);
    } catch (err) {
      lastError = err;
    }

    if (attempt < maxAttempts) {
      const delayMs = Math.min(8000, 1000 * (2 ** (attempt - 1)));
      await sleep(delayMs);
    }
  }

  if (!res) {
    const timeoutHint =
      lastError && lastError.name === "AbortError"
        ? "Request timed out waiting for API response."
        : "Browser could not establish a network connection.";
    throw new Error(
      `Could not reach API at ${url} after ${maxAttempts} attempts. ${timeoutHint} Check Backend URL, Render deploy health, VPN/firewall/proxy rules, and CORS/network access.`
    );
  }

  const contentType = res.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) {
    const text = await res.text();
    if (/response timed out/i.test(text)) {
      throw new Error(
        `Backend timed out at ${url}. Narrow date range, reduce characters, or retry from the backend-hosted board.`
      );
    }
    throw new Error(`Expected JSON from ${url} (HTTP ${res.status}): ${text.slice(0, 200)}`);
  }
  const body = await res.json();
  if (!res.ok) {
    const details = Array.isArray(body.details) && body.details.length > 0 ? ` (${body.details.join("; ")})` : "";
    throw new Error(body.message || body.error || `HTTP ${res.status}${details}`);
  }
  return body;
}

function renderAlts(data, names, distance, includeClashes) {
  const matches = Array.isArray(data.possibleMatches) ? data.possibleMatches : [];
  const top = matches.slice().sort((a, b) => Number(b.confidence || 0) - Number(a.confidence || 0));

  ui.summary.textContent = [
    `Mode: alts`,
    `Searched characters: ${names.join(", ") || "-"}`,
    `Total logins: ${data.totalLogins ?? 0}`,
    `Date range: ${data.dateRange || "Max range"}`,
    `Adjacency distance: ${distance} minutes`,
    `Include clashes: ${includeClashes}`,
    `Possible matches: ${top.length}`
  ].join("\n");

  if (top.length === 0) {
    ui.results.textContent = "No matches found.";
    return;
  }

  ui.results.textContent = top
    .map((m) => `${m.name || "Unknown"}: ${m.adjacencies || 0} / ${m.clashes || 0} / ${m.logins || 0}`)
    .join("\n");
}

function renderClashes(data, names, distance) {
  const clashes = Array.isArray(data.clashes) ? data.clashes : [];
  ui.summary.textContent = [
    `Mode: clashes`,
    `Searched characters: ${names.join(", ") || "-"}`,
    `Adjacency distance: ${distance} minutes`,
    `Clash pairs: ${clashes.length}`
  ].join("\n");

  if (clashes.length === 0) {
    ui.results.textContent = "No clashes found.";
    return;
  }

  ui.results.textContent = clashes
    .slice(0, 200)
    .map((c) => c.formatted || `${c.name || "Unknown"}: ${c.adjacencies || 0} / ${c.clashes || 0} / ${c.logins || 0}`)
    .join("\n");
}

async function checkHealth() {
  try {
    const data = await fetchJson("/api/altfinder/health");
    ui.health.textContent = `API: online (${data.status || "ok"})`;
    ui.health.className = "health ok";
  } catch (_) {
    ui.health.textContent = "API: unavailable";
    ui.health.className = "health bad";
  }
}

async function run() {
  setError("");
  setStatus("Running...");

  const mode = ui.mode.value;
  const strictMode = ui.strictMode.checked;
  const distance = strictMode ? 0 : Number(ui.distance.value || 1);
  const includeClashes = strictMode ? false : ui.includeClashes.value === "true";
  const names = parseNames(ui.characters.value || "");

  if (names.length === 0) {
    setStatus("");
    setError("Enter at least one character.");
    return;
  }

  localStorage.setItem("altfinder_api_base", baseUrl());
  localStorage.setItem(STRICT_MODE_KEY, strictMode ? "true" : "false");
  if (!strictMode) {
    localStorage.setItem(DEFAULT_DISTANCE_KEY, String(distance));
  }

  const params = new URLSearchParams();
  params.set("characters", names.join(","));
  params.set("distance", String(distance));
  const fromInput = ui.from.value.trim();
  const toInput = ui.to.value.trim();
  if (fromInput) params.set("from", fromInput);
  if (toInput) params.set("to", toInput);

  try {
    if (mode === "alts") {
      params.set("includeClashes", includeClashes ? "true" : "false");
      params.set("format", "detailed");
      const data = await fetchJson(`/api/altfinder/alts?${params.toString()}`);
      renderAlts(data, names, distance, includeClashes);
    } else {
      const data = await fetchJson(`/api/altfinder/clashes?${params.toString()}`);
      renderClashes(data, names, distance);
    }
    setStatus("Done.");
  } catch (err) {
    setStatus("");
    const message = err instanceof Error ? err.message : String(err);
    const showDirectBoardLink = message.includes("Could not reach API");
    setError(message, showDirectBoardLink);
  }
}

async function runGuildSearch() {
  ui.guildStatus.textContent = "Searching...";
  const name = ui.guildName.value.trim();
  if (!name) {
    ui.guildStatus.textContent = "";
    ui.guildOutput.textContent = "Enter a guild name.";
    return;
  }
  try {
    const q = new URLSearchParams();
    q.set("name", name);
    const data = await fetchJson(`/api/altfinder/guild?${q.toString()}`);
    ui.guildOutput.textContent = [
      `Guild: ${data.name || name}`,
      `World: ${data.world || "-"}`,
      `Members: ${data.members || 0}`,
      `Online: ${data.online || 0}`,
      `Online characters: ${(data.onlineCharacters || []).join(", ") || "none"}`
    ].join("\n");
    ui.guildStatus.textContent = "Done.";
  } catch (err) {
    ui.guildStatus.textContent = "";
    ui.guildOutput.textContent = err instanceof Error ? err.message : String(err);
  }
}

async function saveCurrentGuild() {
  const name = ui.guildName.value.trim();
  if (!name) return;
  const current = loadSavedGuilds();
  const exists = current.some((g) => String(g.name || "").toLowerCase() === name.toLowerCase());
  if (!exists) {
    current.push({ name, world: "-", members: 0, online: 0, onlineCharacters: [] });
    saveSavedGuilds(current);
  }
  await refreshSavedGuilds();
}

async function refreshSavedGuilds() {
  const current = loadSavedGuilds();
  if (current.length === 0) {
    refreshGuildSelectAndPanel();
    return;
  }

  for (let i = 0; i < current.length; i += 1) {
    const g = current[i];
    try {
      const q = new URLSearchParams();
      q.set("name", g.name);
      const data = await fetchJson(`/api/altfinder/guild?${q.toString()}`);
      current[i] = {
        name: g.name,
        world: data.world || "-",
        members: Number(data.members || 0),
        online: Number(data.online || 0),
        onlineCharacters: Array.isArray(data.onlineCharacters) ? data.onlineCharacters : []
      };
    } catch (_) {
      current[i] = g;
    }
  }
  saveSavedGuilds(current);
  refreshGuildSelectAndPanel();
}

function addSavedCharacter() {
  const name = ui.savedCharacter.value.trim();
  if (!name) return;
  const chars = loadSavedCharacters();
  if (!chars.some((c) => c.toLowerCase() === name.toLowerCase())) {
    chars.push(name);
    saveSavedCharacters(chars);
  }
  ui.savedCharacter.value = "";
  refreshCharacterSelect();
}

function removeSavedCharacter() {
  const selected = ui.savedCharacterSelect.value;
  if (!selected) return;
  const chars = loadSavedCharacters().filter((c) => c !== selected);
  saveSavedCharacters(chars);
  refreshCharacterSelect();
}

function useSavedCharacter() {
  const selected = ui.savedCharacterSelect.value;
  if (!selected) return;
  const current = parseNames(ui.characters.value || "");
  if (!current.some((n) => n.toLowerCase() === selected.toLowerCase())) {
    current.push(selected);
  }
  ui.characters.value = current.join(", ");
}

function removeSavedGuild() {
  const selected = ui.savedGuildSelect.value;
  if (!selected) return;
  const guilds = loadSavedGuilds().filter((g) => g.name !== selected);
  saveSavedGuilds(guilds);
  refreshGuildSelectAndPanel();
}

function useSavedGuild() {
  const selected = ui.savedGuildSelect.value;
  if (!selected) return;
  ui.guildName.value = selected;
}

function setWatchStatus(text) {
  ui.watchStatus.textContent = text || "";
}

function saveWatchSettings() {
  localStorage.setItem(WATCH_GUILD_ID_KEY, ui.watchGuildId.value.trim());
  localStorage.setItem(WATCH_CHANNEL_ID_KEY, ui.watchChannelId.value.trim());
}

function getWatchGuildId() {
  return (ui.watchGuildId.value || "").trim();
}

function renderWatchList(data) {
  const watches = Array.isArray(data.watches) ? data.watches : [];
  ui.watchSelect.innerHTML = watches.map((w) => `<option value="${w.characterName}">${w.characterName}</option>`).join("");
  if (watches.length === 0) {
    ui.watchOutput.textContent = "No watched characters yet.";
    return;
  }
  ui.watchOutput.textContent = watches.map((w) => {
    const lastChecked = w.lastCheckedAt || "-";
    const lastAlert = w.lastAlertAt || "-";
    return `${w.characterName} | distance ${w.distance} | clashes ${w.includeClashes} | threshold ${w.confidenceThreshold} | window ${w.windowDays}d\n  checked: ${lastChecked}\n  alert: ${lastAlert}`;
  }).join("\n\n");
}

async function refreshWatchList() {
  saveWatchSettings();
  const guildId = getWatchGuildId();
  if (!guildId) {
    ui.watchSelect.innerHTML = "";
    ui.watchOutput.textContent = "Enter Guild ID to load watch list.";
    return;
  }
  setWatchStatus("Refreshing...");
  try {
    const q = new URLSearchParams();
    q.set("guildId", guildId);
    const data = await fetchJson(`/api/altfinder/watchlist?${q.toString()}`);
    renderWatchList(data);
    setWatchStatus("Done.");
  } catch (err) {
    ui.watchOutput.textContent = err instanceof Error ? err.message : String(err);
    setWatchStatus("");
  }
}

async function addWatch() {
  saveWatchSettings();
  const guildId = getWatchGuildId();
  const channelId = (ui.watchChannelId.value || "").trim();
  const character = (ui.watchCharacter.value || "").trim();
  const distance = Math.max(0, Number(ui.watchDistance.value || 0));
  const includeClashes = ui.watchIncludeClashes.value === "true";
  const threshold = Math.max(0, Math.min(100, Number(ui.watchThreshold.value || 80)));
  const windowDays = Math.max(1, Math.min(365, Number(ui.watchWindowDays.value || 30)));

  if (!guildId || !channelId || !character) {
    ui.watchOutput.textContent = "Guild ID, Channel ID, and Character are required.";
    return;
  }

  setWatchStatus("Adding...");
  try {
    const q = new URLSearchParams();
    q.set("guildId", guildId);
    q.set("channelId", channelId);
    q.set("character", character);
    q.set("distance", String(distance));
    q.set("includeClashes", includeClashes ? "true" : "false");
    q.set("threshold", String(threshold));
    q.set("windowDays", String(windowDays));
    await fetchJson(`/api/altfinder/watchlist/add?${q.toString()}`);
    ui.watchCharacter.value = "";
    await refreshWatchList();
    setWatchStatus("Done.");
  } catch (err) {
    ui.watchOutput.textContent = err instanceof Error ? err.message : String(err);
    setWatchStatus("");
  }
}

async function removeWatch() {
  saveWatchSettings();
  const guildId = getWatchGuildId();
  const character = ui.watchSelect.value;
  if (!guildId || !character) return;

  setWatchStatus("Removing...");
  try {
    const q = new URLSearchParams();
    q.set("guildId", guildId);
    q.set("character", character);
    await fetchJson(`/api/altfinder/watchlist/remove?${q.toString()}`);
    await refreshWatchList();
    setWatchStatus("Done.");
  } catch (err) {
    ui.watchOutput.textContent = err instanceof Error ? err.message : String(err);
    setWatchStatus("");
  }
}

function useWatchedCharacter() {
  const selected = ui.watchSelect.value;
  if (!selected) return;
  const current = parseNames(ui.characters.value || "");
  if (!current.some((n) => n.toLowerCase() === selected.toLowerCase())) {
    current.push(selected);
  }
  ui.characters.value = current.join(", ");
}

async function runWatchBatch() {
  saveWatchSettings();
  const guildId = getWatchGuildId();
  if (!guildId) {
    ui.watchOutput.textContent = "Guild ID is required.";
    return;
  }

  setWatchStatus("Running...");
  try {
    const q = new URLSearchParams();
    q.set("guildId", guildId);
    q.set("limit", "10");
    const data = await fetchJson(`/api/altfinder/watchlist/run?${q.toString()}`);
    const items = Array.isArray(data.items) ? data.items : [];
    if (items.length === 0) {
      ui.watchOutput.textContent = "No watch results.";
    } else {
      ui.watchOutput.textContent = items.map((item) => {
        const lines = Array.isArray(item.lines) && item.lines.length > 0 ? item.lines.join("\n") : "No matches above threshold.";
        return `${item.characterName} (${item.matches} matches)\n${lines}`;
      }).join("\n\n");
    }
    setWatchStatus("Done.");
  } catch (err) {
    ui.watchOutput.textContent = err instanceof Error ? err.message : String(err);
    setWatchStatus("");
  }
}

ui.runBtn.addEventListener("click", run);
ui.clearBtn.addEventListener("click", () => {
  setStatus("");
  setError("");
  ui.summary.textContent = "No search yet.";
  ui.results.textContent = "No search yet.";
});
ui.apiBase.addEventListener("change", checkHealth);
ui.distance.addEventListener("change", () => {
  const distance = Number(ui.distance.value || 0);
  localStorage.setItem(DEFAULT_DISTANCE_KEY, String(distance));
});
ui.strictMode.addEventListener("change", () => {
  localStorage.setItem(STRICT_MODE_KEY, ui.strictMode.checked ? "true" : "false");
  syncStrictModeControls();
});

ui.guildSearchBtn.addEventListener("click", runGuildSearch);
ui.guildSaveBtn.addEventListener("click", saveCurrentGuild);
ui.guildRefreshBtn.addEventListener("click", refreshSavedGuilds);
ui.guildUseBtn.addEventListener("click", useSavedGuild);
ui.guildRemoveBtn.addEventListener("click", removeSavedGuild);

ui.charAddBtn.addEventListener("click", addSavedCharacter);
ui.charUseBtn.addEventListener("click", useSavedCharacter);
ui.charRemoveBtn.addEventListener("click", removeSavedCharacter);

ui.watchGuildId.addEventListener("change", saveWatchSettings);
ui.watchChannelId.addEventListener("change", saveWatchSettings);
ui.watchAddBtn.addEventListener("click", addWatch);
ui.watchRefreshBtn.addEventListener("click", refreshWatchList);
ui.watchRunBtn.addEventListener("click", runWatchBatch);
ui.watchUseBtn.addEventListener("click", useWatchedCharacter);
ui.watchRemoveBtn.addEventListener("click", removeWatch);

checkHealth();
refreshCharacterSelect();
refreshGuildSelectAndPanel();
refreshWatchList();
