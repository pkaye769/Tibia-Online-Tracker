const TASKS_URL = './static/tasks.json';
const CATEGORY_COLORS = {
  delivery: '#4da3ff',
  courier: '#41d39a',
  collection: '#ffb454',
  transport: '#b88dff'
};
const categoryOrder = ['delivery', 'courier', 'collection', 'transport'];

const map = L.map('map').setView([50.0, 10.0], 4);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19,
  attribution: '&copy; OpenStreetMap contributors'
}).addTo(map);

const cluster = L.markerClusterGroup({
  spiderfyDistanceMultiplier: 1.5,
  showCoverageOnHover: false,
  maxClusterRadius: 45
});

const taskList = document.getElementById('taskList');
const statusSelect = document.getElementById('statusFilter');
const categoryButtons = [...document.querySelectorAll('[data-category-filter]')];

const state = {
  status: 'all',
  categories: new Set(categoryOrder),
  tasks: [],
  markers: new Map()
};

function formatLabel(value) {
  return String(value || '—');
}

function getMarkerColor(category) {
  return CATEGORY_COLORS[category] || '#8aa0b6';
}

function getFilteredTasks() {
  return state.tasks.filter((task) => {
    const matchesCategory = state.categories.has(task.category);
    const matchesStatus = state.status === 'all' || task.status === state.status;
    return matchesCategory && matchesStatus;
  });
}

function renderTaskList(tasks) {
  taskList.innerHTML = '';

  if (!tasks.length) {
    taskList.innerHTML = '<li class="task-item empty-state"><strong>No matching deliveries</strong><div class="task-meta">Adjust your filters to see more task routes.</div></li>';
    return;
  }

  for (const task of tasks) {
    const li = document.createElement('li');
    li.className = 'task-item';
    li.innerHTML = `
      <div class="task-head">
        <strong>${task.title}</strong>
        <span class="task-tag task-${task.category}">${task.category}</span>
      </div>
      <div class="task-meta">${task.location} · ${task.status}</div>
      <div class="task-meta">Reward: ${task.reward}</div>
    `;

    li.addEventListener('click', () => {
      const marker = state.markers.get(task.id);
      if (marker) {
        map.flyTo(marker.getLatLng(), 11, { duration: 0.8 });
        marker.openPopup();
      }
      [...document.querySelectorAll('.task-item')].forEach((el) => el.classList.remove('active'));
      li.classList.add('active');
    });

    taskList.appendChild(li);
  }
}

function updateLayerFromFilters() {
  cluster.clearLayers();
  const tasks = getFilteredTasks();

  for (const task of tasks) {
    const marker = state.markers.get(task.id);
    if (marker) cluster.addLayer(marker);
  }

  if (tasks.length) {
    const bounds = L.latLngBounds(tasks.map((task) => state.markers.get(task.id)?.getLatLng()).filter(Boolean));
    if (bounds.isValid()) map.fitBounds(bounds.pad(0.35));
  }

  renderTaskList(tasks);
}

function updateCategoryButtons() {
  for (const button of categoryButtons) {
    const category = button.dataset.categoryFilter;
    const active = state.categories.has(category);
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', String(active));
  }
}

function setupFilterControls() {
  statusSelect.addEventListener('change', (event) => {
    state.status = event.target.value;
    updateLayerFromFilters();
  });

  for (const button of categoryButtons) {
    button.addEventListener('click', () => {
      const category = button.dataset.categoryFilter;
      if (state.categories.has(category)) {
        state.categories.delete(category);
      } else {
        state.categories.add(category);
      }
      updateCategoryButtons();
      updateLayerFromFilters();
    });
  }
}

async function loadTasks() {
  try {
    const response = await fetch(TASKS_URL);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    const features = Array.isArray(data.features) ? data.features : [];

    state.tasks = [];
    state.markers = new Map();

    for (let i = 0; i < features.length; i += 1) {
      const feature = features[i];
      const props = feature.properties || {};
      const coords = feature.geometry?.coordinates || [];
      if (!Array.isArray(coords) || coords.length < 2) continue;

      const [lng, lat] = coords;
      const category = (props.category || 'delivery').toLowerCase();
      const status = props.status || 'Open';
      const id = `${category}-${i}`;
      const color = getMarkerColor(category);

      const marker = L.circleMarker([lat, lng], {
        radius: 9,
        color,
        fillColor: color,
        fillOpacity: 0.92,
        weight: 2
      });

      marker.bindPopup(`
        <strong>${props.title || 'Task delivery'}</strong><br>
        ${props.description || 'Delivery task'}<br>
        <strong>Category:</strong> ${formatLabel(category)}<br>
        <strong>Status:</strong> ${formatLabel(status)}<br>
        <strong>Reward:</strong> ${formatLabel(props.reward)}<br>
        <strong>Location:</strong> ${formatLabel(props.location)}
      `);

      const task = {
        id,
        title: props.title || 'Task delivery',
        description: props.description || 'Delivery task',
        category,
        status,
        location: props.location || 'Unknown',
        reward: props.reward || '—',
        marker
      };

      state.tasks.push(task);
      state.markers.set(id, marker);
    }

    map.addLayer(cluster);
    updateLayerFromFilters();
  } catch (error) {
    taskList.innerHTML = '<li class="task-item empty-state"><strong>Map data unavailable</strong><div class="task-meta">Failed to load the task list.</div></li>';
    console.error(error);
  }
}

setupFilterControls();
updateCategoryButtons();
loadTasks();
