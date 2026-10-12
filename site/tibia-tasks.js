(() => {
  'use strict';

  const DATA_URL = './static/tasks-data.json';
  const STORAGE_KEY = 'tibia_tasks_completed_v1';

  // State
  const state = {
    monsters: [],
    weeklyTasks: [],
    completedTaskIds: new Set(),
    searchQuery: '',
    searchMode: 'monsters',
    view: 'all', // 'all' | 'delivery' | 'kill' | 'monsters'
    difficulty: 'all', // 'all' | 'Easy' | 'Medium' | 'Hard'
    selected: null,
    markers: []
  };

  // DOM Elements
  const searchInput = document.getElementById('taskSearchInput');
  const clearSearchBtn = document.getElementById('clearSearchBtn');
  const weeklyTasksSection = document.getElementById('weeklyTasksSection');
  const monstersSection = document.getElementById('monstersSection');
  const weeklyTasksList = document.getElementById('weeklyTasksList');
  const monstersList = document.getElementById('monstersList');
  const tasksListCount = document.getElementById('tasksListCount');
  const monstersListCount = document.getElementById('monstersListCount');
  const statTasksCount = document.getElementById('statTasksCount');
  const statMonstersCount = document.getElementById('statMonstersCount');
  const statCompletedCount = document.getElementById('statCompletedCount');
  const resetProgressBtn = document.getElementById('resetProgressBtn');

  // HUD Elements
  const mapHud = document.getElementById('mapHud');
  const hudTitle = document.getElementById('hudTitle');
  const hudCoords = document.getElementById('hudCoords');
  const hudBody = document.getElementById('hudBody');
  const hudActions = document.getElementById('hudActions');
  const hudFocusBtn = document.getElementById('hudFocusBtn');
  const hudCopyBtn = document.getElementById('hudCopyBtn');

  // Load saved completions
  try {
    const rawSaved = localStorage.getItem(STORAGE_KEY);
    if (rawSaved) {
      const parsed = JSON.parse(rawSaved);
      if (Array.isArray(parsed)) {
        state.completedTaskIds = new Set(parsed);
      }
    }
  } catch (e) {
    console.warn('Could not read completed tasks from storage', e);
  }

  function saveCompletedTasks() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify([...state.completedTaskIds]));
    } catch (e) {
      console.warn('Could not save completed tasks', e);
    }
    updateStats();
  }

  // Initialize Map
  let map = null;
  let markerLayerGroup = null;

  function initMap() {
    const mapEl = document.getElementById('map');
    if (!mapEl) return;

    if (typeof window.L === 'undefined') {
      mapEl.innerHTML = `
        <div class="map-fallback-view">
          <h3 style="color:#ffffff; margin-bottom:8px;">Interactive Tibia Map</h3>
          <p style="color:#9aaebd; max-width:400px; font-size:13px;">
            Map library loaded offline. Coords and spot data are available in the list and HUD.
          </p>
        </div>`;
      return;
    }

    try {
      map = window.L.map('map', {
        zoomControl: true,
        attributionControl: true
      }).setView([51.4, 0.1], 6);

      window.L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
        subdomains: 'abcd',
        maxZoom: 18,
        minZoom: 4,
        attribution: '&copy; OpenStreetMap contributors &copy; CARTO'
      }).addTo(map);

      markerLayerGroup = window.L.layerGroup().addTo(map);
    } catch (err) {
      console.error('Failed to initialize Leaflet map:', err);
    }
  }

  function normalize(str) {
    return String(str || '').toLowerCase().trim();
  }

  function getFilteredData() {
    const q = normalize(state.searchQuery);

    const filteredTasks = state.weeklyTasks.filter(task => {
      if (state.view === 'monsters') return false;
      if (state.view === 'delivery' && task.type !== 'delivery') return false;
      if (state.view === 'kill' && task.type !== 'kill') return false;
      if (state.difficulty !== 'all' && task.difficulty !== state.difficulty) return false;

      if (!q) return true;
      return (
        normalize(task.title).includes(q) ||
        normalize(task.monsterName).includes(q) ||
        normalize(task.itemRequired).includes(q) ||
        normalize(task.location).includes(q) ||
        normalize(task.description).includes(q)
      );
    });

    const filteredMonsters = state.monsters.filter(monster => {
      if (state.view === 'delivery' || state.view === 'kill') return false;
      if (state.difficulty !== 'all' && monster.difficulty !== state.difficulty) return false;

      if (!q) return true;
      if (state.searchMode === 'drops') {
        return (monster.dropItems || []).some(item => normalize(item).includes(q));
      }
      const spotsMatch = (monster.spots || []).some(spot => normalize(spot.name).includes(q) || normalize(spot.city).includes(q));
      return normalize(monster.name).includes(q) || normalize(monster.category).includes(q) || spotsMatch;
    });

    return { filteredTasks, filteredMonsters };
  }

  function updateStats() {
    statTasksCount.textContent = String(state.weeklyTasks.length);
    statMonstersCount.textContent = String(state.monsters.length);
    statCompletedCount.textContent = String(state.completedTaskIds.size);
  }

  function selectSpot(spot, title, details, itemPill = null) {
    state.selected = { spot, title, details };

    hudTitle.textContent = title;
    hudCoords.textContent = spot ? `X: ${spot.x} · Y: ${spot.y} · Z: ${spot.z}` : '—';

    let bodyHtml = `<strong>Location:</strong> ${spot.name} (${spot.city || 'Tibia'})<br>`;
    bodyHtml += `<strong>Spawn Density:</strong> ~${spot.amount} creatures<br>`;
    if (details) {
      bodyHtml += `<span style="display:inline-block; margin-top:4px;">${details}</span>`;
    }
    if (itemPill) {
      bodyHtml += `<div style="margin-top:6px;">${itemPill}</div>`;
    }

    hudBody.innerHTML = bodyHtml;
    hudActions.hidden = false;

    if (map && spot.lat && spot.lng) {
      map.flyTo([spot.lat, spot.lng], 9, { duration: 0.75 });
    }
  }

  function renderWeeklyTasks(tasks) {
    weeklyTasksList.innerHTML = '';
    tasksListCount.textContent = String(tasks.length);

    if (state.view === 'monsters') {
      weeklyTasksSection.style.display = 'none';
      return;
    }
    weeklyTasksSection.style.display = 'block';

    if (!tasks.length) {
      weeklyTasksList.innerHTML = `
        <div style="padding:16px; background:#0d1822; border:1px dashed #1e3649; border-radius:6px; color:var(--muted); font-size:12px; text-align:center;">
          No weekly tasks match the current search or filters.
        </div>`;
      return;
    }

    tasks.forEach(task => {
      const isCompleted = state.completedTaskIds.has(task.id);
      const monster = state.monsters.find(m => m.id === task.monsterId || normalize(m.name) === normalize(task.monsterName));
      const bestSpot = monster?.bestSpot;

      const card = document.createElement('article');
      card.className = `task-card ${isCompleted ? 'completed' : ''}`;
      card.id = `card-task-${task.id}`;

      const badgeTypeClass = task.type === 'delivery' ? 'badge-delivery' : 'badge-kill';
      const badgeDiffClass = `badge-diff-${task.difficulty.toLowerCase()}`;

      card.innerHTML = `
        <div class="card-top">
          <h3 class="card-title">${task.title}</h3>
          <div class="badge-group">
            <span class="badge ${badgeTypeClass}">${task.type === 'delivery' ? 'Delivery Task' : 'Kill Task'}</span>
            <span class="badge ${badgeDiffClass}">${task.difficulty}</span>
          </div>
        </div>

        <div class="card-meta-line">
          ${task.itemRequired
            ? `<span class="item-highlight">✦ Deliver: ${task.amount} ${task.itemRequired}</span>`
            : `<span style="color:#8ac5ff; font-weight:600;">⚔ Target: ${task.amount}</span>`}
          <span>·</span>
          <span>Monster: <strong>${task.monsterName}</strong></span>
        </div>

        <p class="card-desc">${task.description}</p>

        <div class="card-meta-line" style="font-size:11px;">
          <span>Reward: <strong style="color:var(--accent-bright);">${task.reward}</strong></span>
          <span>·</span>
          <span>Area: <strong>${task.location}</strong></span>
        </div>

        <div class="card-bottom-actions">
          <label class="check-label" title="Mark this task as completed for this week">
            <input type="checkbox" ${isCompleted ? 'checked' : ''} data-task-id="${task.id}">
            <span>${isCompleted ? 'Completed' : 'Mark Done'}</span>
          </label>
          <button class="card-btn btn-show-map" type="button">
            <span>Show on map</span> <span aria-hidden="true">→</span>
          </button>
        </div>
      `;

      // Checkbox listener
      const checkbox = card.querySelector('input[type="checkbox"]');
      checkbox.addEventListener('change', (e) => {
        e.stopPropagation();
        if (checkbox.checked) {
          state.completedTaskIds.add(task.id);
          card.classList.add('completed');
          checkbox.nextElementSibling.textContent = 'Completed';
        } else {
          state.completedTaskIds.delete(task.id);
          card.classList.remove('completed');
          checkbox.nextElementSibling.textContent = 'Mark Done';
        }
        saveCompletedTasks();
      });

      // Click card or button to focus on map
      const focusAction = () => {
        document.querySelectorAll('.task-card, .monster-card').forEach(c => c.classList.remove('active'));
        card.classList.add('active');

        if (bestSpot) {
          selectSpot(
            bestSpot,
            `${task.title} (${task.monsterName})`,
            `<strong>Task:</strong> ${task.description}<br><strong>Reward:</strong> ${task.reward}`,
            task.itemRequired ? `<span class="drop-pill is-task-item">Required: ${task.amount} ${task.itemRequired}</span>` : null
          );
        } else {
          hudTitle.textContent = task.title;
          hudCoords.textContent = '—';
          hudBody.innerHTML = `<strong>Area:</strong> ${task.location}<br>${task.description}<br><strong>Reward:</strong> ${task.reward}`;
          hudActions.hidden = true;
        }
      };

      card.addEventListener('click', (e) => {
        if (e.target.tagName === 'INPUT' || e.target.closest('label')) return;
        focusAction();
      });

      card.querySelector('.btn-show-map').addEventListener('click', (e) => {
        e.stopPropagation();
        focusAction();
      });

      weeklyTasksList.appendChild(card);
    });
  }

  function renderMonsters(monsters) {
    monstersList.innerHTML = '';
    monstersListCount.textContent = String(monsters.length);

    if (state.view === 'delivery' || state.view === 'kill') {
      monstersSection.style.display = 'none';
      return;
    }
    monstersSection.style.display = 'block';

    if (!monsters.length) {
      monstersList.innerHTML = `
        <div style="padding:16px; background:#0d1822; border:1px dashed #1e3649; border-radius:6px; color:var(--muted); font-size:12px; text-align:center;">
          No monsters match the current search or filters.
        </div>`;
      return;
    }

    // Set of all delivery task items to highlight them
    const deliveryItems = new Set(
      state.weeklyTasks.filter(t => t.itemRequired).map(t => normalize(t.itemRequired))
    );

    monsters.forEach(monster => {
      const best = monster.bestSpot;
      const spots = monster.spots?.length ? monster.spots : (best ? [best] : []);
      const badgeDiffClass = `badge-diff-${(monster.difficulty || 'easy').toLowerCase()}`;
      const searchQuery = normalize(state.searchQuery);

      const card = document.createElement('article');
      card.className = 'monster-card';
      card.id = `card-monster-${monster.id}`;

      // Build drops HTML
      const dropsHtml = (monster.dropItems || []).map(drop => {
        const isTask = deliveryItems.has(normalize(drop));
        const isSearchMatch = state.searchMode === 'drops' && searchQuery && normalize(drop).includes(searchQuery);
        return `<span class="drop-pill ${isTask ? 'is-task-item' : ''} ${isSearchMatch ? 'is-search-match' : ''}" title="${isSearchMatch ? 'Matching drop item' : isTask ? 'Delivery Task Item' : 'Monster Drop'}">${isTask ? '★ ' : ''}${drop}</span>`;
      }).join(' ');
      const spotsHtml = spots.map((spot, index) => `
        <button class="spawn-location-btn" type="button" data-spot-index="${index}">
          <span>${spot.name}${spot.city ? ` · ${spot.city}` : ''}</span>
          <strong>~${spot.amount} creatures</strong>
        </button>
      `).join('');

      card.innerHTML = `
        <div class="card-top">
          <h3 class="card-title">${monster.name}</h3>
          <div class="badge-group">
            <span class="badge ${badgeDiffClass}">${monster.difficulty || 'Easy'}</span>
            <span class="badge" style="background:#132936; border:1px solid #23485c; color:#95c6e8;">${monster.category || 'Creature'}</span>
          </div>
        </div>

        <div class="card-meta-line">
          <span>HP: <strong>${monster.hp}</strong></span>
          <span>·</span>
          <span>EXP: <strong>${monster.exp}</strong></span>
          <span>·</span>
          <span>Best: <strong>${best ? best.name : 'Unknown'}</strong> (~${best?.amount || 0})</span>
        </div>

        <div style="margin: 8px 0 6px;">
          <span style="font-size:11px; color:var(--muted);">Notable drops & delivery items:</span>
          <div class="drops-tag-list">${dropsHtml}</div>
        </div>

        <div class="spawn-location-list">
          <span class="spawn-location-heading">Spawn locations · approximate counts</span>
          ${spotsHtml}
        </div>

        <div class="card-bottom-actions" style="margin-top:10px;">
          <span style="font-size:11px; color:var(--muted);">
            Coords: <strong style="color:var(--accent-bright);">${best ? `${best.x}, ${best.y}` : '—'}</strong>
          </span>
          <button class="card-btn btn-show-best" type="button">
            <span>Show best spot</span> <span aria-hidden="true">→</span>
          </button>
        </div>
      `;

      const focusMonster = (spot = best) => {
        document.querySelectorAll('.task-card, .monster-card').forEach(c => c.classList.remove('active'));
        card.classList.add('active');

        if (spot) {
          selectSpot(
            spot,
            `${monster.name} - ${spot.name}`,
            `<strong>Creature Stats:</strong> ${monster.hp} HP · ${monster.exp} EXP<br><strong>Location:</strong> ${spot.name} (${spot.city || 'Tibia'})<br><strong>Approximate count:</strong> ~${spot.amount} creatures`,
            `<div style="display:flex; gap:4px; flex-wrap:wrap; margin-top:4px;">${dropsHtml}</div>`
          );
        }
      };

      card.addEventListener('click', focusMonster);
      card.querySelectorAll('[data-spot-index]').forEach(button => {
        button.addEventListener('click', (event) => {
          event.stopPropagation();
          focusMonster(spots[Number(button.dataset.spotIndex)]);
        });
      });
      card.querySelector('.btn-show-best').addEventListener('click', (e) => {
        e.stopPropagation();
        focusMonster();
      });

      monstersList.appendChild(card);
    });
  }

  function updateMapMarkers(monsters, tasks) {
    if (!map || !markerLayerGroup) return;

    markerLayerGroup.clearLayers();
    state.markers = [];

    // Map monsterId to task items
    const deliveryTaskByMonster = new Map();
    tasks.forEach(t => {
      if (t.type === 'delivery' && t.itemRequired) {
        deliveryTaskByMonster.set(normalize(t.monsterName), t);
      }
    });

    const killTaskByMonster = new Map();
    tasks.forEach(t => {
      if (t.type === 'kill') {
        killTaskByMonster.set(normalize(t.monsterName), t);
      }
    });

    monsters.forEach(monster => {
      const spots = monster.spots || (monster.bestSpot ? [monster.bestSpot] : []);
      const deliveryTask = deliveryTaskByMonster.get(normalize(monster.name));
      const killTask = killTaskByMonster.get(normalize(monster.name));

      spots.forEach((spot, idx) => {
        if (!spot.lat || !spot.lng) return;

        let markerClass = 'marker-monster';
        if (deliveryTask) markerClass = 'marker-delivery';
        else if (killTask) markerClass = 'marker-kill';

        const iconHtml = `<div class="tibia-map-marker ${markerClass}" title="${monster.name} (${spot.name})">${spot.amount}</div>`;
        const customIcon = window.L.divIcon({
          html: iconHtml,
          className: 'tibia-div-icon',
          iconSize: [28, 28],
          iconAnchor: [14, 14],
          popupAnchor: [0, -14]
        });

        const marker = window.L.marker([spot.lat, spot.lng], { icon: customIcon });

        let popupContent = `
          <div style="min-width:200px;">
            <strong style="font-size:14px; color:#ffffff;">${monster.name}</strong><br>
            <span style="color:#8cf3d0; font-size:12px;">${spot.name} (${spot.city || 'Tibia'})</span><br>
            <div style="margin:6px 0; font-size:12px; color:#a9bed4;">
              Density: <strong>~${spot.amount} creatures</strong><br>
              Coords: <code>${spot.x}, ${spot.y}, ${spot.z}</code>
            </div>`;

        if (deliveryTask) {
          popupContent += `
            <div style="padding:4px 6px; background:rgba(42,226,143,0.15); border:1px solid rgba(42,226,143,0.35); border-radius:4px; font-size:11px; color:#2ae28f; margin-top:6px;">
              ★ Weekly Delivery: Deliver ${deliveryTask.amount} ${deliveryTask.itemRequired}
            </div>`;
        } else if (killTask) {
          popupContent += `
            <div style="padding:4px 6px; background:rgba(60,141,255,0.15); border:1px solid rgba(60,141,255,0.35); border-radius:4px; font-size:11px; color:#8ac5ff; margin-top:6px;">
              ⚔ Weekly Task: Slay ${killTask.amount}
            </div>`;
        }

        popupContent += `</div>`;
        marker.bindPopup(popupContent);

        marker.on('click', () => {
          selectSpot(
            spot,
            `${monster.name} - ${spot.name}`,
            `Density: ~${spot.amount} spawns<br>Creature: ${monster.hp} HP · ${monster.exp} EXP`,
            deliveryTask ? `<span class="drop-pill is-task-item">★ Delivery Task: ${deliveryTask.amount} ${deliveryTask.itemRequired}</span>` : null
          );

          // Scroll sidebar to matching card
          const card = document.getElementById(`card-monster-${monster.id}`) || document.getElementById(`card-task-${deliveryTask?.id}`);
          if (card) {
            document.querySelectorAll('.task-card, .monster-card').forEach(c => c.classList.remove('active'));
            card.classList.add('active');
            card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
          }
        });

        markerLayerGroup.addLayer(marker);
        state.markers.push(marker);
      });
    });
  }

  function applyFilters() {
    const { filteredTasks, filteredMonsters } = getFilteredData();
    renderWeeklyTasks(filteredTasks);
    renderMonsters(filteredMonsters);
    updateMapMarkers(filteredMonsters, filteredTasks);
  }

  // Setup Event Listeners
  function setupEvents() {
    // Search input
    searchInput.addEventListener('input', () => {
      state.searchQuery = searchInput.value;
      applyFilters();
    });

    clearSearchBtn.addEventListener('click', () => {
      searchInput.value = '';
      state.searchQuery = '';
      applyFilters();
    });

    // View filter chips
    document.querySelectorAll('[data-view]').forEach(chip => {
      chip.addEventListener('click', () => {
        document.querySelectorAll('[data-view]').forEach(c => c.classList.remove('active'));
        chip.classList.add('active');
        state.view = chip.getAttribute('data-view');
        applyFilters();
      });
    });

    document.querySelectorAll('[data-search-mode]').forEach(chip => {
      chip.addEventListener('click', () => {
        document.querySelectorAll('[data-search-mode]').forEach(button => {
          button.classList.remove('active');
          button.setAttribute('aria-pressed', 'false');
        });
        chip.classList.add('active');
        chip.setAttribute('aria-pressed', 'true');
        state.searchMode = chip.getAttribute('data-search-mode');
        searchInput.placeholder = state.searchMode === 'drops'
          ? 'Search dropped items (e.g. Gauze Bandage)...'
          : 'Search monster names (e.g. Mummy)...';
        applyFilters();
      });
    });

    // Difficulty filter chips
    document.querySelectorAll('[data-difficulty]').forEach(chip => {
      chip.addEventListener('click', () => {
        document.querySelectorAll('[data-difficulty]').forEach(c => c.classList.remove('active'));
        chip.classList.add('active');
        state.difficulty = chip.getAttribute('data-difficulty');
        applyFilters();
      });
    });

    // Reset progress button
    resetProgressBtn.addEventListener('click', () => {
      if (confirm('Reset your saved weekly tasks progress?')) {
        state.completedTaskIds.clear();
        saveCompletedTasks();
        applyFilters();
      }
    });

    // Quick Zones
    document.querySelectorAll('.zone-chip').forEach(chip => {
      chip.addEventListener('click', () => {
        const lat = parseFloat(chip.getAttribute('data-lat'));
        const lng = parseFloat(chip.getAttribute('data-lng'));
        if (map && !isNaN(lat) && !isNaN(lng)) {
          map.flyTo([lat, lng], 8, { duration: 0.8 });
        }
      });
    });

    // HUD Actions
    hudFocusBtn.addEventListener('click', () => {
      if (map && state.selected?.spot?.lat && state.selected?.spot?.lng) {
        map.flyTo([state.selected.spot.lat, state.selected.spot.lng], 10, { duration: 0.6 });
      }
    });

    hudCopyBtn.addEventListener('click', () => {
      if (!state.selected?.spot) return;
      const s = state.selected.spot;
      const text = `${s.x}, ${s.y}, ${s.z}`;
      if (navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(text).then(() => {
          hudCopyBtn.textContent = 'Copied!';
          setTimeout(() => { hudCopyBtn.textContent = 'Copy Coords'; }, 1600);
        });
      }
    });
  }

  // Load Data
  async function loadData() {
    try {
      const resp = await fetch(DATA_URL);
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const data = await resp.json();

      state.monsters = Array.isArray(data.monsters) ? data.monsters : [];
      state.weeklyTasks = Array.isArray(data.weeklyTasks) ? data.weeklyTasks : [];

      updateStats();
      applyFilters();

      // Default highlight first monster best spot
      if (state.monsters.length > 0 && state.monsters[0].bestSpot) {
        const m = state.monsters[0];
        selectSpot(m.bestSpot, `${m.name} - Best Spot`, `HP: ${m.hp} · EXP: ${m.exp}<br>Location: ${m.bestSpot.name}`);
      }
    } catch (err) {
      console.error('Failed to load tasks data:', err);
      weeklyTasksList.innerHTML = `<div style="padding:16px; color:#ff7b72;">Failed to load task dataset (${err.message}).</div>`;
      monstersList.innerHTML = `<div style="padding:16px; color:#ff7b72;">Failed to load monster dataset.</div>`;
    }
  }

  // Startup
  initMap();
  setupEvents();
  loadData();
})();
