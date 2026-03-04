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
  results: $("results")
};

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
  return raw
    .split(",")
    .map((x) => x.trim())
    .filter(Boolean);
}

async function fetchJson(path) {
  const url = baseUrl() + path;
  const res = await fetch(url);
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

ui.runBtn.addEventListener("click", run);
ui.clearBtn.addEventListener("click", () => {
  setStatus("");
  setError("");
  ui.summary.textContent = "No search yet.";
  ui.results.textContent = "No search yet.";
});
ui.apiBase.addEventListener("change", checkHealth);

checkHealth();

