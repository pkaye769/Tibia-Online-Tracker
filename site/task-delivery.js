const MONSTERS_URL = './static/monster-spawns.json';

const mapCanvas = document.getElementById('mapCanvas');
const monsterListEl = document.getElementById('monsterList');
const monsterCountText = document.getElementById('monsterCountText');
const monsterSearchInput = document.getElementById('monsterSearch');
const mapHud = document.getElementById('mapHud');
const showBestSpotBtn = document.getElementById('showBestSpotBtn');
const showMonsterListBtn = document.getElementById('showMonsterListBtn');

const WORLD = {
  minX: 30000,
  maxX: 34000,
  minY: 30000,
  maxY: 34000,
  width: 1200,
  height: 560
};

const state = {
  monsters: [],
  markers: []
};

function normalize(text) {
  return String(text || '').trim().toLowerCase();
}

function tibiaToPixel(x, y) {
  const px = ((x - WORLD.minX) / (WORLD.maxX - WORLD.minX)) * WORLD.width;
  const py = ((WORLD.maxY - y) / (WORLD.maxY - WORLD.minY)) * WORLD.height;
  return { x: px, y: py };
}

function clearMarkers() {
  state.markers.forEach((marker) => marker.remove());
  state.markers = [];
}

function bestSpotForMonster(monster) {
  if (!monster || !Array.isArray(monster.spots) || !monster.spots.length) return null;

  return monster.spots.reduce((best, spot) => {
    return (spot.amount || 0) > (best.amount || 0) ? spot : best;
  }, monster.spots[0]);
}

function showMonster(monsterName, forcedSpot = null) {
  const monster = state.monsters.find((entry) => normalize(entry.name) === normalize(monsterName));
  if (!monster) return;

  clearMarkers();
  const bestSpot = forcedSpot || bestSpotForMonster(monster);
  if (!bestSpot) return;

  const pos = tibiaToPixel(bestSpot.x, bestSpot.y);

  const marker = document.createElement('button');
  marker.type = 'button';
  marker.className = 'tibia-marker';
  marker.setAttribute('title', `${monster.name} - ${bestSpot.name}`);
  marker.style.left = `${pos.x}px`;
  marker.style.top = `${pos.y}px`;
  marker.textContent = String(bestSpot.amount);

  marker.addEventListener('click', () => {
    mapHud.innerHTML = `
      <strong>${monster.name}</strong><br>
      ${bestSpot.name}<br>
      Amount: <strong>${bestSpot.amount}</strong><br>
      X: ${bestSpot.x} · Y: ${bestSpot.y} · Z: ${bestSpot.z}
    `;
  });

  mapCanvas.appendChild(marker);
  state.markers.push(marker);

  mapHud.innerHTML = `
    <strong>${monster.name}</strong><br>
    Best: ${bestSpot.name}<br>
    Amount: <strong>${bestSpot.amount}</strong><br>
    X: ${bestSpot.x} · Y: ${bestSpot.y} · Z: ${bestSpot.z}
  `;
}

function renderMonsterList(monsters) {
  monsterListEl.innerHTML = '';
  monsterCountText.textContent = String(monsters.length);

  monsters.forEach((monster) => {
    const item = document.createElement('li');
    item.className = 'monster-item';

    const left = document.createElement('div');
    left.className = 'left';
    left.innerHTML = `
      <span class="monster-icon" aria-hidden="true"></span>
      <span class="monster-name">${monster.name}</span>
    `;

    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'monster-btn';
    button.textContent = 'Show best spot';
    button.addEventListener('click', () => showMonster(monster.name));

    item.appendChild(left);
    item.appendChild(button);
    monsterListEl.appendChild(item);
  });
}

function filterMonsters() {
  const query = normalize(monsterSearchInput.value);
  const filtered = state.monsters.filter((monster) => normalize(monster.name).includes(query));
  renderMonsterList(filtered);
}

function setDefaultMonster() {
  if (!state.monsters.length) return;
  showMonster(state.monsters[0].name);
}

async function loadMonsters() {
  try {
    const response = await fetch(MONSTERS_URL);
    if (!response.ok) throw new Error(`Failed to load monster data: ${response.status}`);

    const data = await response.json();
    state.monsters = Array.isArray(data.monsters) ? data.monsters : [];
    renderMonsterList(state.monsters);
    setDefaultMonster();
  } catch (error) {
    console.error(error);
    monsterListEl.innerHTML = '<li class="monster-item"><span class="monster-name">Unable to load monster data</span></li>';
    monsterCountText.textContent = '0';
    mapHud.innerHTML = 'Monster data unavailable';
  }
}

monsterSearchInput.addEventListener('input', filterMonsters);

showBestSpotBtn.addEventListener('click', () => {
  const query = normalize(monsterSearchInput.value);
  const match = state.monsters.find((monster) => normalize(monster.name).includes(query));
  if (match) showMonster(match.name);
  else if (!query) setDefaultMonster();
});

showMonsterListBtn.addEventListener('click', () => {
  monsterSearchInput.value = '';
  renderMonsterList(state.monsters);
});

loadMonsters();
