"use strict";

const API_KEY = "online-tibia-tracker-api";
const TIBIADATA_API = "https://api.tibiadata.com/v4";
const TIBIASTALKER_API = "https://api.tibiastalker.pl/api/tibia-stalker/v1/characters";
const byId = (id) => document.getElementById(id);
const apiInput = byId("apiBase");
const onlineStatus = byId("onlineStatus");
const transferStatus = byId("transferStatus");
const numberFormat = new Intl.NumberFormat();
const vocationCategories = [
  { id: "all", label: "All", matches: () => true },
  { id: "knight", label: "Knight", matches: (vocation) => vocation.includes("knight") },
  { id: "paladin", label: "Paladin", matches: (vocation) => vocation.includes("paladin") },
  { id: "monk", label: "Monk", matches: (vocation) => vocation.includes("monk") },
  { id: "druid", label: "Druid", matches: (vocation) => vocation.includes("druid") },
  { id: "sorcerer", label: "Sorcerer", matches: (vocation) => vocation.includes("sorcerer") }
];
let onlinePlayers = [];
let activeVocationCategory = "all";
let onlineWorldName = "";
let checkedCharacterName = "";

// Saved lists keys (reuse names used elsewhere for compatibility)
const SAVED_CHARS_KEY = "altfinder_saved_chars_v1";
const SAVED_GUILDS_KEY = "altfinder_saved_guilds_v2";

function getVocationCategory(vocation) {
  const normalized = (vocation || "").toLowerCase();
  return vocationCategories.find((category) => category.id !== "all" && category.matches(normalized));
}

function renderOnlinePlayers() {
  const list = byId("onlineList");
  const filters = byId("onlineFilters");
  list.replaceChildren();

  const category = vocationCategories.find((item) => item.id === activeVocationCategory) || vocationCategories[0];
  const filteredPlayers = onlinePlayers.filter((player) => {
    const vocation = (player.vocation || "").toLowerCase();
    return category.id === "all" || category.matches(vocation);
  });

  filters.replaceChildren();
  for (const item of vocationCategories) {
    const count = item.id === "all"
      ? onlinePlayers.length
      : onlinePlayers.filter((player) => item.matches((player.vocation || "").toLowerCase())).length;
    const button = document.createElement("button");
    button.type = "button";
    button.className = `vocation-filter${item.id === activeVocationCategory ? " active" : ""}`;
    button.dataset.category = item.id;
    button.setAttribute("aria-pressed", String(item.id === activeVocationCategory));
    button.textContent = `${item.label} · ${numberFormat.format(count)}`;
    filters.append(button);
  }
  filters.hidden = onlinePlayers.length === 0;

  if (checkedCharacterName) {
    const onlineMatch = onlinePlayers.find((player) => player.name?.toLowerCase() === checkedCharacterName.toLowerCase());
    const status = onlineMatch
      ? `${onlineMatch.name} is online in ${onlineWorldName}.`
      : `${checkedCharacterName} is not listed among the ${numberFormat.format(onlinePlayers.length)} players online in ${onlineWorldName}.`;
    onlineStatus.textContent = `${status} Showing ${category.label.toLowerCase()} players.`;
  } else {
    onlineStatus.textContent = `${numberFormat.format(filteredPlayers.length)} ${category.id === "all" ? "" : `${category.label} `}players online in ${onlineWorldName}. Showing up to 50.`;
  }

  if (!filteredPlayers.length) {
    const empty = document.createElement("li");
    empty.textContent = `No ${category.id === "all" ? "" : `${category.label.toLowerCase()} `}players online in this world.`;
    list.append(empty);
    return;
  }

  for (const player of filteredPlayers.slice(0, 50)) {
    const item = document.createElement("li");
    item.textContent = `${player.name} · ${numberFormat.format(player.level)} · ${player.vocation}`;
    list.append(item);
  }
}

function readApiBase() {
  return apiInput.value.trim().replace(/\/+$/, "");
}

async function fetchTibiaData(path) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch(`${TIBIADATA_API}${path}`, { signal: controller.signal });
    const data = await response.json();
    const apiStatus = data.information?.status?.http_code;
    if (!response.ok || (apiStatus != null && apiStatus >= 400)) {
      throw new Error(data.message || `TibiaData returned HTTP ${apiStatus || response.status}.`);
    }
    return data;
  } catch (error) {
    if (error.name === "AbortError") {
      throw new Error("The TibiaData request timed out. Please try again.");
    }
    if (error instanceof TypeError) {
      throw new Error("Could not connect to TibiaData. Check your connection and try again.");
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

async function fetchTibiaStalker(characterName) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch(`${TIBIASTALKER_API}/${encodeURIComponent(characterName)}`, { signal: controller.signal });
    const data = await response.json();
    if (!response.ok) {
      throw new Error(data.message || `Tibia Stalker returned HTTP ${response.status}.`);
    }
    if (!Array.isArray(data.possibleInvisibleCharacters)) {
      throw new Error("Tibia Stalker returned an unexpected character response.");
    }
    return data;
  } catch (error) {
    if (error.name === "AbortError") {
      throw new Error("The Tibia Stalker request timed out. Please try again.");
    }
    if (error instanceof TypeError) {
      throw new Error("Could not connect to Tibia Stalker. Check your connection and try again.");
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function setHiddenCharacterResults(data) {
  const container = byId("hiddenCharacters");
  container.replaceChildren();
  const hiddenCharacters = data?.possibleInvisibleCharacters || [];
  if (!hiddenCharacters.length) {
    const empty = document.createElement("p");
    empty.className = "empty-result";
    empty.textContent = "Tibia Stalker did not return any possible hidden characters.";
    container.append(empty);
    return;
  }

  const table = document.createElement("table");
  const head = document.createElement("thead");
  const header = document.createElement("tr");
  for (const label of ["Character", "Matches", "First match", "Last match"]) {
    const cell = document.createElement("th");
    cell.scope = "col";
    cell.textContent = label;
    header.append(cell);
  }
  head.append(header);
  table.append(head);

  const body = document.createElement("tbody");
  for (const match of hiddenCharacters) {
    const row = document.createElement("tr");
    const values = [
      match.otherCharacterName || "Unknown",
      match.numberOfMatches == null ? "—" : numberFormat.format(match.numberOfMatches),
      match.firstMatchDateOnly || "—",
      match.lastMatchDateOnly || "—"
    ];
    for (const value of values) {
      const cell = document.createElement("td");
      cell.textContent = value;
      row.append(cell);
    }
    body.append(row);
  }
  table.append(body);
  container.append(table);
}

function setCharacterResults(data, stalkerData, searchedName) {
  const results = byId("characterResults");
  const character = data?.character?.character;
  if (!character && !stalkerData) {
    throw new Error(data.message || `Character "${searchedName}" was not found.`);
  }

  const name = character?.name || stalkerData?.name || searchedName;
  const level = character?.level ?? stalkerData?.level;
  const world = character?.world || stalkerData?.world || "World unknown";
  byId("characterSummary").textContent = `${name}${level == null ? "" : ` · Level ${numberFormat.format(level)}`} · ${world}`;
  const details = byId("characterDetails");
  details.replaceChildren();
  const guildName = character?.guild?.name
    ? `${character.guild.name}${character.guild.rank ? ` · ${character.guild.rank}` : ""}`
    : "No guild listed";
  const fields = [
    ["Vocation", character?.vocation || stalkerData?.vocation || "—"],
    ["Residence", character?.residence || "—"],
    ["Guild", guildName],
    ["Account", character?.account_status || "—"],
    ["Achievement points", character?.achievement_points == null ? "—" : numberFormat.format(character.achievement_points)],
    ["Last login", (character?.last_login || stalkerData?.lastLogin) ? new Date(character?.last_login || stalkerData.lastLogin).toLocaleString() : "—"]
  ];
  for (const [label, value] of fields) {
    const item = document.createElement("div");
    const term = document.createElement("span");
    term.textContent = label;
    const description = document.createElement("strong");
    description.textContent = value;
    item.append(term, description);
    details.append(item);
  }

  setHiddenCharacterResults(stalkerData);

  const linkedCharacters = byId("accountCharacters");
  linkedCharacters.replaceChildren();
  const others = Array.isArray(data?.character?.other_characters)
    ? data.character.other_characters.filter((other) => other.name?.toLowerCase() !== name.toLowerCase())
    : [];
  if (others.length) {
    const table = document.createElement("table");
    const head = document.createElement("thead");
    const header = document.createElement("tr");
    for (const label of ["Character", "World", "Status", "Notes"]) {
      const cell = document.createElement("th");
      cell.scope = "col";
      cell.textContent = label;
      header.append(cell);
    }
    head.append(header);
    table.append(head);
    const body = document.createElement("tbody");
    for (const other of others) {
      const row = document.createElement("tr");
      const values = [
        other.name || "Unknown",
        other.world || "—",
        other.status || "—",
        [other.main ? "Main" : "", other.traded ? "Traded" : "", other.deleted ? "Deleted" : ""].filter(Boolean).join(" · ") || "—"
      ];
      for (const value of values) {
        const cell = document.createElement("td");
        cell.textContent = value;
        row.append(cell);
      }
      body.append(row);
    }
    table.append(body);
    linkedCharacters.append(table);
  } else {
    const empty = document.createElement("p");
    empty.className = "empty-result";
    empty.textContent = data
      ? "TibiaData does not list any other characters on this account."
      : "Public account characters are unavailable without a TibiaData profile.";
    linkedCharacters.append(empty);
  }

  const deaths = byId("characterDeaths");
  deaths.replaceChildren();
  const deathList = Array.isArray(data?.character?.deaths) ? data.character.deaths.slice(0, 5) : [];
  if (deathList.length) {
    const list = document.createElement("ul");
    for (const death of deathList) {
      const item = document.createElement("li");
      const date = death.time ? new Date(death.time).toLocaleString() : "Unknown date";
      item.textContent = `${date} · Level ${death.level ?? "?"} · ${death.reason || "Details unavailable"}`;
      list.append(item);
    }
    deaths.append(list);
  } else {
    const empty = document.createElement("p");
    empty.className = "empty-result";
    empty.textContent = data ? "No recent deaths listed." : "Recent deaths are unavailable without a TibiaData profile.";
    deaths.append(empty);
  }
  results.hidden = false;
}

function toMemberList(guild) {
  if (Array.isArray(guild?.members)) return guild.members;
  const nested = guild?.members?.members;
  return Array.isArray(nested) ? nested : [];
}

function setGuildResults(data, searchedName) {
  const results = byId("guildResults");
  const guild = data?.guild;
  if (!guild) {
    throw new Error(data.message || `Guild "${searchedName}" was not found.`);
  }

  byId("guildSummary").textContent = `${guild.name || searchedName} · ${guild.world || "World unknown"}`;

  const memberList = toMemberList(guild);
  const memberTotal = Number(guild.members_total ?? guild.membersTotal ?? memberList.length ?? 0);
  const onlineTotal = Number(guild.members_online ?? guild.membersOnline ?? 0);
  const onlineNames = memberList
    .filter((member) => {
      const status = String(member.status || member.memberStatus || "").toLowerCase();
      const isOnline = member.online === true || status.includes("online");
      return isOnline;
    })
    .map((member) => member.name || member.character_name || member.player_name)
    .filter(Boolean);

  const details = byId("guildDetails");
  details.replaceChildren();
  const fields = [
    ["World", guild.world || "—"],
    ["Members", memberTotal ? numberFormat.format(memberTotal) : "—"],
    ["Online", onlineTotal ? numberFormat.format(onlineTotal) : (onlineNames.length ? numberFormat.format(onlineNames.length) : "0")],
    ["Founded", guild.founded || "—"],
    ["Homepage", guild.homepage || "—"]
  ];
  for (const [label, value] of fields) {
    const item = document.createElement("div");
    const term = document.createElement("span");
    term.textContent = label;
    const description = document.createElement("strong");
    description.textContent = value;
    item.append(term, description);
    details.append(item);
  }

  const onlineContainer = byId("guildOnlineMembers");
  onlineContainer.replaceChildren();
  if (!onlineNames.length) {
    const empty = document.createElement("p");
    empty.className = "empty-result";
    empty.textContent = "No guild members are currently online.";
    onlineContainer.append(empty);
  } else {
    const list = document.createElement("ul");
    for (const name of onlineNames.slice(0, 50)) {
      const item = document.createElement("li");
      item.textContent = name;
      list.append(item);
    }
    onlineContainer.append(list);
  }

  results.hidden = false;
}

// Saved characters (watchlist) helpers
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

function refreshSavedPlayersUI() {
  const list = loadSavedCharacters();
  const select = byId("savedPlayerList");
  const display = byId("savedPlayersDisplay");
  select.innerHTML = list.map((c) => `<option>${c}</option>`).join("");
  display.textContent = list.length > 0 ? list.join("\n") : "No saved players yet.";
}

function addSavedPlayer() {
  const input = byId("savedPlayerInput");
  const name = (input.value || "").trim();
  if (!name) return;
  const list = loadSavedCharacters();
  if (!list.some((c) => c.toLowerCase() === name.toLowerCase())) {
    list.push(name);
    saveSavedCharacters(list);
  }
  input.value = "";
  refreshSavedPlayersUI();
}

function removeSavedPlayer() {
  const select = byId("savedPlayerList");
  const selected = select.value;
  if (!selected) return;
  const list = loadSavedCharacters().filter((c) => c !== selected);
  saveSavedCharacters(list);
  refreshSavedPlayersUI();
}

// Saved guilds helpers
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

function refreshSavedGuildsUI() {
  const guilds = loadSavedGuilds();
  const select = byId("savedGuildList");
  const display = byId("savedGuildsDisplay");
  select.innerHTML = guilds.map((g) => `<option>${g.name}</option>`).join("");
  if (guilds.length === 0) {
    display.textContent = "No saved guilds yet.";
    return;
  }
  display.textContent = guilds.map((g) => {
    const online = (g.onlineCharacters || []).join(", ") || "none";
    return `${g.name} | ${g.world || "-"} | online ${g.online || 0}/${g.members || 0}\n  ${online}`;
  }).join("\n\n");
}

async function addSavedGuild() {
  const input = byId("savedGuildInput");
  const name = (input.value || "").trim();
  if (!name) return;
  const guilds = loadSavedGuilds();
  if (!guilds.some((g) => String(g.name || "").toLowerCase() === name.toLowerCase())) {
    guilds.push({ name, world: "-", members: 0, online: 0, onlineCharacters: [] });
    saveSavedGuilds(guilds);
  }
  input.value = "";
  refreshSavedGuildsUI();
}

async function removeSavedGuild() {
  const select = byId("savedGuildList");
  const selected = select.value;
  if (!selected) return;
  const guilds = loadSavedGuilds().filter((g) => g.name !== selected);
  saveSavedGuilds(guilds);
  refreshSavedGuildsUI();
}

async function refreshSavedGuilds() {
  const guilds = loadSavedGuilds();
  if (guilds.length === 0) {
    refreshSavedGuildsUI();
    return;
  }
  for (let i = 0; i < guilds.length; i += 1) {
    const g = guilds[i];
    try {
      const data = await fetchTibiaData(`/guild/${encodeURIComponent(g.name)}`);
      const guild = data.guild;
      const members = Array.isArray(guild?.members) ? guild.members : (Array.isArray(guild?.members?.members) ? guild.members.members : []);
      const onlineNames = members.filter((m) => {
        const status = String(m.status || m.memberStatus || "").toLowerCase();
        return m.online === true || status.includes("online");
      }).map((m) => m.name || m.character_name || m.player_name).filter(Boolean);
      guilds[i] = {
        name: guild.name || g.name,
        world: guild.world || "-",
        members: Number(guild.members_total ?? members.length ?? 0),
        online: Number(guild.members_online ?? onlineNames.length ?? 0),
        onlineCharacters: onlineNames
      };
    } catch (_) {
      // leave unchanged on error
    }
  }
  saveSavedGuilds(guilds);
  refreshSavedGuildsUI();
}

async function searchCharacter(event) {
  event.preventDefault();
  const name = byId("characterNames").value.trim();
  const status = byId("characterSearchStatus");
  const output = byId("characterResults");
  output.hidden = true;

  if (!name) {
    status.textContent = "Enter a character name.";
    return;
  }

  const button = byId("characterSearchButton");
  button.disabled = true;
  status.textContent = "Searching Tibia Stalker and loading TibiaData profile…";

  try {
    const [profileResult, hiddenResult] = await Promise.allSettled([
      fetchTibiaData(`/character/${encodeURIComponent(name)}`),
      fetchTibiaStalker(name)
    ]);
    const profileData = profileResult.status === "fulfilled" ? profileResult.value : null;
    const hiddenData = hiddenResult.status === "fulfilled" ? hiddenResult.value : null;
    if (!profileData && !hiddenData) {
      throw new Error(`Tibia Stalker: ${hiddenResult.reason.message} TibiaData: ${profileResult.reason.message}`);
    }
    setCharacterResults(profileData, hiddenData, name);

    const messages = [];
    if (hiddenData) {
      messages.push(`${hiddenData.possibleInvisibleCharacters.length} possible hidden characters from Tibia Stalker`);
    } else {
      messages.push(`Hidden-character search unavailable: ${hiddenResult.reason.message}`);
    }
    if (profileData) {
      messages.push("profile details from TibiaData");
    } else {
      messages.push(`TibiaData profile unavailable: ${profileResult.reason.message}`);
    }
    status.textContent = `${messages.join("; ")}.`;
  } catch (error) {
    status.textContent = `Could not look up ${name}: ${error.message}`;
  } finally {
    button.disabled = false;
  }
}

async function searchGuild(event) {
  event.preventDefault();
  const name = byId("guildName").value.trim();
  const status = byId("guildSearchStatus");
  const output = byId("guildResults");
  output.hidden = true;

  if (!name) {
    status.textContent = "Enter a guild name.";
    return;
  }

  const button = byId("guildSearchButton");
  button.disabled = true;
  status.textContent = `Looking up guild ${name}…`;

  try {
    const data = await fetchTibiaData(`/guild/${encodeURIComponent(name)}`);
    setGuildResults(data, name);
    status.textContent = `Guild lookup completed for ${name}.`;
  } catch (error) {
    status.textContent = `Could not look up guild ${name}: ${error.message}`;
  } finally {
    button.disabled = false;
  }
}

function setRows(container, events) {
  container.replaceChildren();
  if (!events.length) {
    const empty = document.createElement("p");
    empty.className = "empty-result";
    empty.textContent = "No transfers found.";
    container.append(empty);
    return;
  }

  const table = document.createElement("table");
  const head = document.createElement("thead");
  const header = document.createElement("tr");
  for (const label of ["Character", "Transfer", "Date"]) {
    const cell = document.createElement("th");
    cell.scope = "col";
    cell.textContent = label;
    header.append(cell);
  }
  head.append(header);
  table.append(head);

  const body = document.createElement("tbody");
  for (const event of events) {
    const row = document.createElement("tr");
    const name = document.createElement("td");
    name.textContent = event.characterName || "Unknown";
    const route = document.createElement("td");
    route.textContent = `${event.fromWorld || "?"} → ${event.toWorld || "?"}`;
    const date = document.createElement("td");
    const parsedDate = new Date(event.transferTime);
    date.textContent = Number.isNaN(parsedDate.getTime()) ? event.transferTime || "—" : parsedDate.toLocaleDateString();
    row.append(name, route, date);
    body.append(row);
  }
  table.append(body);
  container.append(table);
}

async function loadTransfers(event) {
  event.preventDefault();
  const base = readApiBase();
  const world = byId("transferWorld").value.trim();
  const days = Number(byId("transferDays").value);
  const output = byId("transferResults");
  output.hidden = true;

  if (!base) {
    byId("apiSettings").open = true;
    transferStatus.textContent = "Set a compatible tracker API URL above to load transfer history. This GitHub Pages site does not host an API.";
    return;
  }
  if (!Number.isInteger(days) || days < 1 || days > 365) {
    transferStatus.textContent = "Choose a look-back period from 1 to 365 days.";
    return;
  }

  const button = byId("transferSearch");
  button.disabled = true;
  transferStatus.textContent = "Loading transfers…";
  const params = new URLSearchParams({ world, lookbackDays: String(days) });

  try {
    const response = await fetch(`${base}/api/altfinder/transfers?${params.toString()}`);
    const data = await response.json();
    if (!response.ok) {
      throw new Error(data.error || data.message || `Request failed (${response.status}).`);
    }
    if (!Array.isArray(data.transfersTo) || !Array.isArray(data.transfersFrom)) {
      throw new Error("The API returned an unexpected transfer response.");
    }
    byId("toWorldName").textContent = data.world || world;
    byId("fromWorldName").textContent = data.world || world;
    setRows(byId("transfersTo"), data.transfersTo);
    setRows(byId("transfersFrom"), data.transfersFrom);
    transferStatus.textContent = `Showing transfer activity for ${data.world || world} over the last ${data.lookbackDays || days} days.`;
    output.hidden = false;
  } catch (error) {
    transferStatus.textContent = `Could not load transfers: ${error.message} Check the API URL and its CORS settings.`;
  } finally {
    button.disabled = false;
  }
}

async function loadOnlineCharacters(characterName) {
  const list = byId("onlineList");
  list.replaceChildren();
  let worldName = byId("onlineCharacter").value.trim();
  const searchedName = (characterName || "").trim();
  checkedCharacterName = searchedName;
  onlinePlayers = [];
  byId("onlineFilters").hidden = true;
  activeVocationCategory = "all";
  const worldButton = byId("onlineCheck");
  const characterCheckButton = byId("onlineCharacterCheck");
  worldButton.disabled = true;
  characterCheckButton.disabled = true;
  onlineStatus.textContent = searchedName ? `Finding ${searchedName}…` : `Loading online players in ${worldName || "world"}…`;
  try {
    if (searchedName) {
      const characterData = await fetchTibiaData(`/character/${encodeURIComponent(searchedName)}`);
      const character = characterData.character?.character;
      if (!character?.world) {
        throw new Error(characterData.message || `Could not find the world for ${searchedName}.`);
      }
      worldName = character.world;
      byId("onlineCharacter").value = worldName;
    }
    if (!worldName) {
      onlineStatus.textContent = "Enter a world name.";
      return;
    }
    const data = await fetchTibiaData(`/world/${encodeURIComponent(worldName)}`);
    const world = data.world;
    if (!world || !Array.isArray(world.online_players)) {
      throw new Error(data.message || "TibiaData did not return an online-player list for that world.");
    }
    onlineWorldName = world.name;
    onlinePlayers = world.online_players;
    renderOnlinePlayers();
  } catch (error) {
    onlineStatus.textContent = `Could not load live world data: ${error.message}`;
  } finally {
    worldButton.disabled = false;
    characterCheckButton.disabled = false;
  }
}

for (const id of ["huntLoot", "huntSupplies", "partySize"]) {
  // removed hunt profit listeners
}

byId("characterSearchForm").addEventListener("submit", searchCharacter);
byId("guildSearchForm").addEventListener("submit", searchGuild);
byId("transferForm").addEventListener("submit", loadTransfers);
byId("onlineSearchForm").addEventListener("submit", (event) => {
  event.preventDefault();
  loadOnlineCharacters();
});
byId("onlineCharacterForm").addEventListener("submit", (event) => {
  event.preventDefault();
  loadOnlineCharacters(byId("onlineCharacterName").value);
});
byId("onlineFilters").addEventListener("click", (event) => {
  const button = event.target.closest("button[data-category]");
  if (!button || !vocationCategories.some((category) => category.id === button.dataset.category)) {
    return;
  }
  activeVocationCategory = button.dataset.category;
  renderOnlinePlayers();
});

// Saved players UI events
byId("addSavedPlayerBtn").addEventListener("click", addSavedPlayer);
byId("removeSavedPlayerBtn").addEventListener("click", removeSavedPlayer);
byId("useSavedPlayerBtn").addEventListener("click", () => {
  const select = byId("savedPlayerList");
  const selected = select.value;
  if (!selected) return;
  byId("characterNames").value = selected;
});
byId("savedPlayerInput").addEventListener("keydown", (e) => {
  if (e.key === "Enter") addSavedPlayer();
});

// Saved guilds UI events
byId("addSavedGuildBtn").addEventListener("click", addSavedGuild);
byId("removeSavedGuildBtn").addEventListener("click", removeSavedGuild);
byId("refreshSavedGuildsBtn").addEventListener("click", refreshSavedGuilds);
byId("savedGuildInput").addEventListener("keydown", (e) => {
  if (e.key === "Enter") addSavedGuild();
});

// API save button
byId("saveApi").addEventListener("click", () => {
  const base = readApiBase();
  if (base) {
    try {
      const parsed = new URL(base);
      if (parsed.protocol !== "https:" && parsed.protocol !== "http:") {
        throw new Error("Use an HTTP or HTTPS URL.");
      }
      localStorage.setItem(API_KEY, parsed.origin + parsed.pathname.replace(/\/+$/, ""));
      apiInput.value = localStorage.getItem(API_KEY);
      onlineStatus.textContent = "API URL saved in this browser.";
      transferStatus.textContent = "API URL saved in this browser.";
    } catch (error) {
      onlineStatus.textContent = error.message || "Enter a valid API URL.";
    }
  } else {
    try {
      localStorage.removeItem(API_KEY);
      onlineStatus.textContent = "API URL cleared.";
      transferStatus.textContent = "API URL cleared.";
    } catch (error) {
      onlineStatus.textContent = `Could not clear the saved API URL: ${error.message}`;
    }
  }
});

try {
  apiInput.value = localStorage.getItem(API_KEY) || "";
} catch (error) {
  onlineStatus.textContent = "Browser storage is unavailable. Enter an API URL to load live data.";
}

// Initialize saved lists UI
refreshSavedPlayersUI();
refreshSavedGuildsUI();

