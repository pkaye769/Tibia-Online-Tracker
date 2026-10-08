"use strict";

const API_KEY = "online-tibia-tracker-api";
const TIBIADATA_API = "https://api.tibiadata.com/v4";
const TIBIASTALKER_API = "https://api.tibiastalker.pl/api/tibia-stalker/v1/characters";
const byId = (id) => document.getElementById(id);
const apiInput = byId("apiBase");
const onlineStatus = byId("onlineStatus");
const transferStatus = byId("transferStatus");
const numberFormat = new Intl.NumberFormat();
const shareFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
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

function expAtLevel(level) {
  return Math.floor((50 * level ** 3) / 3 - 100 * level ** 2 + (850 * level) / 3 - 200);
}

function calculateLeveling() {
  const current = Number(byId("currentLevel").value);
  const target = Number(byId("targetLevel").value);
  const currentExp = Number(byId("currentLevelExp").value);
  const expPerHour = Number(byId("expPerHour").value);
  const error = byId("levelError");

  error.textContent = "";
  byId("expNeeded").textContent = "—";
  byId("timeNeeded").textContent = "—";

  if (!Number.isInteger(current) || !Number.isInteger(target) || current < 1 || target <= current || target > 1000) {
    error.textContent = "Enter a current level and a higher target level (up to 1,000).";
    return;
  }
  if (!Number.isFinite(currentExp) || currentExp < 0 || currentExp >= expAtLevel(current + 1) - expAtLevel(current)) {
    error.textContent = "XP earned this level must be less than the experience needed for your next level.";
    return;
  }
  if (!Number.isFinite(expPerHour) || expPerHour < 0) {
    error.textContent = "XP per hour cannot be negative.";
    return;
  }

  const remaining = Math.max(0, expAtLevel(target) - expAtLevel(current) - currentExp);
  byId("expNeeded").textContent = numberFormat.format(remaining);
  if (expPerHour > 0) {
    const hours = remaining / expPerHour;
    const totalMinutes = Math.round(hours * 60);
    const wholeHours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    byId("timeNeeded").textContent = wholeHours ? `${numberFormat.format(wholeHours)}h ${minutes}m` : `${minutes}m`;
  } else {
    byId("timeNeeded").textContent = "Enter your XP/hour";
  }
}

function calculateHuntProfit() {
  const loot = Number(byId("huntLoot").value);
  const supplies = Number(byId("huntSupplies").value);
  const partySize = Number(byId("partySize").value);
  const error = byId("huntError");
  error.textContent = "";

  if (!Number.isInteger(loot) || !Number.isInteger(supplies) || loot < 0 || supplies < 0 ||
      !Number.isInteger(partySize) || partySize < 1 || partySize > 100) {
    error.textContent = "Enter whole, non-negative loot and supplies and a party size from 1 to 100.";
    byId("huntProfit").textContent = "—";
    byId("profitShare").textContent = "—";
    return;
  }

  const profit = loot - supplies;
  byId("huntProfit").textContent = numberFormat.format(profit);
  byId("profitShare").textContent = shareFormat.format(profit / partySize);
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

for (const id of ["currentLevel", "targetLevel", "currentLevelExp", "expPerHour"]) {
  byId(id).addEventListener("input", calculateLeveling);
}
for (const id of ["huntLoot", "huntSupplies", "partySize"]) {
  byId(id).addEventListener("input", calculateHuntProfit);
}

byId("characterSearchForm").addEventListener("submit", searchCharacter);
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
calculateLeveling();
calculateHuntProfit();
