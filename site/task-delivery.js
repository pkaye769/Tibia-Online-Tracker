const MONSTERS_URL = './static/monster-spawns.json';

const map = L.map('monsterMap', { zoomControl: false }).setView([20, 0], 2);
L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
  subdomains: 'abcd',
  maxZoom: 19,
  attribution: '&copy; OpenStreetMap contributors &copy; CARTO'
}).addTo(map);

const monsterListEl = document.getElementById('monsterList');
const monsterSearchInput = document.getElementById('monsterSearch');
const monsterCountText = document.getElementById('monsterCountText');
const mapHud = document.getElementById('mapHud');
const showBestSpotBtn = document.getElementById('showBestSpotBtn');
const showMonsterListBtn = document.getElementById('showMonsterListBtn');
const showTopSpawnsBtn = document.getElementById('showTopSpawnsBtn');

const state = {
  monsters: [],
  current: null,
  markers: []
};

function normalizeName(name) {
  return String(name || '').trim().toLowerCase();
}

function clearMarkers() {
  state.markers.forEach((marker) => map.removeLayer(marker));
  state.markers = [];
}

function bestSpotForMonster(monster) {
  if (!monster || !Array.isArray(monster.spots) || !monster.spots.length) return null;
  return monster.spots.reduce((best, spot) => (
    (spot.amount || 0) > (best.amount || 0) ? spot : best
  ), monster.spots[0]);
}

function highlightMonster(monsterName, options = {}) {
  const monster = state.monsters.find((entry) => normalizeName(entry.name) === normalizeName(monsterName));
  if (!monster) return;

  clearMarkers();
  const best = bestSpotForMonster(monster);
  if (!best) return;

  const spotMarker = L.circleMarker([best.lat || 0, best.lng || 0], {
    radius: 9,
    color: '#f5b942',
    fillColor: '#f5b942',
    fillOpacity: 1,
    weight: 3
  }).addTo(map);

  spotMarker.bindPopup(`
    <div style="color:#fff; font-size:13px; line-height:1.5;">
      <strong>${monster.name}</strong><br>
      ${best.name}<br>
      Amount: <strong>${best.amount}</strong><br>
      Position: ${best.x}, ${best.y}, ${best.z}
    </div>
  `);

  spotMarker.openPopup();
  map.flyTo([best.lat || 0, best.lng || 0], 7, { duration: 0.9 });

  const ranking = [...monster.spots].sort((a, b) => (b.amount || 0) - (a.amount || 0));
  const topThree = ranking.slice(0, 3).map((entry) => `${entry.name}: ${entry.amount}`).join(' · ');

  mapHud.innerHTML = `
    <strong>${monster.name}</strong><br>
    Best: ${best.name}<br>
    Amount: <strong>${best.amount}</strong><br>
    ${best.x} / ${best.y} / ${best.z}
  `;

  state.current = monster;
  state.markers = [spotMarker];

  if (options.showRanking) {
    console.log('Top spots:', topThree);
  }
}

function renderMonsterList(list) {
  monsterListEl.innerHTML = '';
  monsterCountText.textContent = String(list.length);

  for (const monster of list) {
    const item = document.createElement('li');
    item.className = 'monster-item';
    item.innerHTML = `
      <div class="left">
        <span class="monster-icon" aria-hidden="true"></span>
        <span class="monster-name">${monster.name}</span>
      </div>
      <button class="monster-btn" type="button">Show best spot</button>
    `;

    item.querySelector('button').addEventListener('click', () => {
      highlightMonster(monster.name);
    });

    monsterListEl.appendChild(item);
  }
}

function filterMonsters() {
  const query = normalizeName(monsterSearchInput.value);
  const filtered = state.monsters.filter((monster) => normalizeName(monster.name).includes(query));
  renderMonsterList(filtered);
}

function setDefaultMonster() {
  if (!state.monsters.length) return;
  const first = state.monsters[0];
  highlightMonster(first.name);
}

function setupToolbar() {
  monsterSearchInput.addEventListener('input', filterMonsters);

  showBestSpotBtn.addEventListener('click', () => {
    const query = normalizeName(monsterSearchInput.value);
    if (!query) {
      setDefaultMonster();
      return;
    }

    const matched = state.monsters.find((monster) => normalizeName(monster.name).includes(query));
    if (matched) highlightMonster(matched.name);
  });

  showMonsterListBtn.addEventListener('click', () => {
    monsterSearchInput.value = '';
    filterMonsters();
  });

  showTopSpawnsBtn.addEventListener('click', () => {
    if (!state.monsters.length) return;
    const ranked = [...state.monsters]
      .map((monster) => ({
        name: monster.name,
        best: bestSpotForMonster(monster)
      }))
      .filter((entry) => entry.best)
      .sort((a, b) => (b.best.amount || 0) - (a.best.amount || 0));

    const top = ranked[0];
    if (top) highlightMonster(top.name, { showRanking: true });
  });
}

async function loadMonsters() {
  try {
    const response = await fetch(MONSTERS_URL);
    if (!response.ok) throw new Error(`Failed to load ${MONSTERS_URL}`);
    const data = await response.json();
    state.monsters = Array.isArray(data.monsters) ? data.monsters : [];
    renderMonsterList(state.monsters);
    setupToolbar();
    setDefaultMonster();
  } catch (error) {
    console.error(error);
    monsterListEl.innerHTML = '<li class="monster-item"><span class="monster-name">Unable to load monster data</span></li>';
    monsterCountText.textContent = '0';
    mapHud.innerHTML = 'Monster data unavailable';
  }
}

loadMonsters();
