const $ = (id) => document.getElementById(id);

const ui = {
  apiBase: $("apiBase"),
  mode: $("mode"),
  distance: $("distance"),
  includeClashes: $("includeClashes"),
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
  guildRemoveBtn: $("guildRemoveBtn")
};

const SAVED_CHARS_KEY = "altfinder_saved_chars_v1";
const SAVED_GUILDS_KEY = "altfinder_saved_guilds_v2";
const storedApi = localStorage.getItem("altfinder_api_base");
const defaultApiBase = "https://tibia-alt-finder-api.onrender.com";
ui.apiBase.value = storedApi || defaultApiBase;

function baseUrl() {
  return (ui.apiBase.value || "").trim().replace(/\/+$/, "");
}

function setStatus(text) {
  ui.status.textContent = text || "";
}

function setError(text) {
  ui.error.textContent = text || "";
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
  let res;
  try {
    res = await fetch(url);
  } catch (err) {
    // Retry once for transient Render cold-start/network blips.
    await sleep(1500);
    try {
      res = await fetch(url);
    } catch (_) {
      throw new Error(
        `Could not reach API at ${url}. Check Backend URL, API deploy health, and CORS/network access.`
      );
    }
  }
  const contentType = res.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) {
    const text = await res.text();
    throw new Error(`Expected JSON from ${url} (HTTP ${res.status}): ${text.slice(0, 200)}`);
  }
  const body = await res.json();
  if (!res.ok) {
    throw new Error(body.message || `HTTP ${res.status}`);
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
  const distance = Number(ui.distance.value || 0);
  const includeClashes = ui.includeClashes.value === "true";
  const names = parseNames(ui.characters.value || "");

  if (names.length === 0) {
    setStatus("");
    setError("Enter at least one character.");
    return;
  }

  localStorage.setItem("altfinder_api_base", baseUrl());

  const params = new URLSearchParams();
  params.set("characters", names.join(","));
  params.set("distance", String(distance));
  if (ui.from.value.trim()) params.set("from", ui.from.value.trim());
  if (ui.to.value.trim()) params.set("to", ui.to.value.trim());

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
    setError(err instanceof Error ? err.message : String(err));
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

ui.runBtn.addEventListener("click", run);
ui.clearBtn.addEventListener("click", () => {
  setStatus("");
  setError("");
  ui.summary.textContent = "No search yet.";
  ui.results.textContent = "No search yet.";
});
ui.apiBase.addEventListener("change", checkHealth);

ui.guildSearchBtn.addEventListener("click", runGuildSearch);
ui.guildSaveBtn.addEventListener("click", saveCurrentGuild);
ui.guildRefreshBtn.addEventListener("click", refreshSavedGuilds);
ui.guildUseBtn.addEventListener("click", useSavedGuild);
ui.guildRemoveBtn.addEventListener("click", removeSavedGuild);

ui.charAddBtn.addEventListener("click", addSavedCharacter);
ui.charUseBtn.addEventListener("click", useSavedCharacter);
ui.charRemoveBtn.addEventListener("click", removeSavedCharacter);

checkHealth();
refreshCharacterSelect();
refreshGuildSelectAndPanel();
