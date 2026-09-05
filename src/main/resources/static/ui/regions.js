import {
  fetchWithTimeout,
  iconSpan,
  showToast,
  withWorkingOverlay
} from '/ui/ui-helpers.js';

const regionsSubtitle = document.getElementById('regions-subtitle');
const gridSizeXInput = document.getElementById('grid-size-x');
const gridSizeYInput = document.getElementById('grid-size-y');
const refreshButton = document.getElementById('refresh-regions');
const regionsGrid = document.getElementById('regions-grid');
const detailsEmpty = document.getElementById('region-details-empty');
const detailsPanel = document.getElementById('region-details');
const detailName = document.getElementById('detail-name');
const detailId = document.getElementById('detail-id');
const detailPosition = document.getElementById('detail-position');
const detailSize = document.getElementById('detail-size');
const detailPort = document.getElementById('detail-port');
const detailFlags = document.getElementById('detail-flags');
const regionOptionsForm = document.getElementById('region-options-form');
const detailOptionPublic = document.getElementById('detail-option-public');
const detailOptionVoice = document.getElementById('detail-option-voice');
const saveRegionOptionsButton = document.getElementById('save-region-options');
const toastContainer = document.getElementById('toast-container');

const addRegionModal = document.getElementById('add-region-modal');
const addRegionForm = document.getElementById('add-region-form');
const closeAddRegionButton = document.getElementById('close-add-region');
const cancelAddRegionButton = document.getElementById('cancel-add-region');
const addRegionNameInput = document.getElementById('add-region-name');
const addRegionXInput = document.getElementById('add-region-x');
const addRegionYInput = document.getElementById('add-region-y');
const addRegionPublicInput = document.getElementById('add-region-public');
const addRegionVoiceInput = document.getElementById('add-region-voice');
const addRegionNextFreePortInput = document.getElementById('add-region-next-free-port');
const addRegionPortInput = document.getElementById('add-region-port');
const addRegionOarSelect = document.getElementById('add-region-oar');
const addRegionEstateSelect = document.getElementById('add-region-estate');
const addEstateOwnerFields = document.getElementById('add-estate-owner-fields');
const addEstateOwnerFirstInput = document.getElementById('add-estate-owner-first');
const addEstateOwnerLastInput = document.getElementById('add-estate-owner-last');
const addRegionError = document.getElementById('add-region-error');
const submitAddRegionButton = document.getElementById('submit-add-region');

const GRID_SIZE_MIN = 1;
const GRID_SIZE_MAX = 10;
const GRID_SIZE_DEFAULT = 4;
const REQUEST_RECOVERY_WINDOW_MS = 180000;
const REQUEST_RECOVERY_POLL_MS = 3000;
const NEW_ESTATE_VALUE = '__new_estate__';

let simulatorName = '';
let simulatorStatus = null;
let regions = [];
let estates = [];
let oars = [];
let selectedRegionId = '';
let refreshInFlight = null;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const parseSimulatorName = () => {
  const params = new URLSearchParams(window.location.search);
  return String(params.get('simulator') || '').trim();
};

const normalizedLevel = () => String(simulatorStatus?.level || '').trim().toUpperCase();
const regionEditorSupported = () => normalizedLevel() === 'GRID' || normalizedLevel() === 'STANDALONE';

const normalizeMapHost = (hostname) => {
  const value = String(hostname || '').trim();
  if (!value) {
    return value;
  }
  return value.includes(':') && !value.startsWith('[') ? `[${value}]` : value;
};

const buildWorldMapUrl = (region) => {
  const host = normalizeMapHost(window.location.hostname);
  const port = Number(simulatorStatus?.port);
  const x = Number(region?.x);
  const y = Number(region?.y);
  if (!host || !Number.isFinite(port) || port <= 0 || !Number.isFinite(x) || !Number.isFinite(y)) {
    return null;
  }
  return `http://${host}:${port}/map-1-${x}-${y}-objects.jpg`;
};

const clampGridSize = (value) => {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return GRID_SIZE_DEFAULT;
  }
  return Math.max(GRID_SIZE_MIN, Math.min(GRID_SIZE_MAX, Math.floor(parsed)));
};

const activeGridSize = () => {
  const x = clampGridSize(gridSizeXInput?.value);
  const y = clampGridSize(gridSizeYInput?.value);
  if (gridSizeXInput) {
    gridSizeXInput.value = String(x);
  }
  if (gridSizeYInput) {
    gridSizeYInput.value = String(y);
  }
  return { x, y, count: x * y };
};

const setSelectedRegion = (region) => {
  selectedRegionId = String(region?.id || '');
  renderDetails(region || null);
  renderGrid();
};

const hasRegionFlag = (region, flagName) => {
  const needle = String(flagName || '').trim().toLowerCase();
  if (!needle) {
    return false;
  }
  const flags = Array.isArray(region?.flags) ? region.flags : [];
  return flags.some((flag) => String(flag || '').trim().toLowerCase() === needle);
};

const inferRegionPublic = (region) => {
  if (hasRegionFlag(region, 'private')) {
    return false;
  }
  if (hasRegionFlag(region, 'public')) {
    return true;
  }
  return true;
};

const inferRegionVoice = (region) => {
  if (hasRegionFlag(region, 'novoice') || hasRegionFlag(region, 'novoicechat') || hasRegionFlag(region, 'voiceoff')) {
    return false;
  }
  if (hasRegionFlag(region, 'voice') || hasRegionFlag(region, 'enablevoice')) {
    return true;
  }
  return true;
};

const renderDetails = (region) => {
  if (!detailsPanel || !detailsEmpty || !detailName || !detailId || !detailPosition || !detailSize || !detailPort || !detailFlags || !detailOptionPublic || !detailOptionVoice) {
    return;
  }

  if (!region) {
    detailsPanel.classList.add('hidden');
    detailsEmpty.classList.remove('hidden');
    return;
  }

  detailsEmpty.classList.add('hidden');
  detailsPanel.classList.remove('hidden');
  detailName.textContent = String(region.name || '');
  detailId.textContent = String(region.id || '');
  detailPosition.textContent = String(region.position || `${region.x},${region.y}`);
  detailSize.textContent = String(region.size || 'Unknown');
  const regionPort = Number(region?.port);
  detailPort.textContent = Number.isFinite(regionPort) && regionPort > 0 ? String(regionPort) : 'Unknown';
  detailOptionPublic.checked = inferRegionPublic(region);
  detailOptionVoice.checked = inferRegionVoice(region);

  const flags = Array.isArray(region.flags) ? region.flags : [];
  detailFlags.innerHTML = '';
  if (flags.length === 0) {
    const empty = document.createElement('span');
    empty.className = 'text-gray-400';
    empty.textContent = 'None';
    detailFlags.appendChild(empty);
    return;
  }

  flags.forEach((flag) => {
    const badge = document.createElement('span');
    badge.className = 'inline-flex items-center rounded-md border border-neon-accent/40 bg-neon-accent/10 px-2 py-1 text-xs font-medium text-neon-accent';
    badge.textContent = String(flag || '');
    detailFlags.appendChild(badge);
  });
};

const createRegionCell = (region) => {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'relative h-36 rounded-lg overflow-hidden border transition-all text-left';

  const selected = selectedRegionId && String(region?.id || '') === selectedRegionId;
  if (selected) {
    button.classList.add('border-neon-accent', 'ring-1', 'ring-neon-accent/60');
  } else {
    button.classList.add('border-neon-primary/25', 'hover:border-neon-primary/60');
  }

  const worldMapUrl = buildWorldMapUrl(region);
  const port = Number(region?.port);
  const shortPort = Number.isFinite(port) && port > 0 ? String(port) : 'n/a';
  button.innerHTML = worldMapUrl
    ? `<img src="${worldMapUrl}" alt="World map for ${region.name}" loading="lazy" class="h-full w-full object-cover bg-dark-900/50">
       <div class="absolute top-0 left-0 right-0 bg-black/55 text-xs px-2 py-1 text-gray-100 truncate">${region.name}</div>
       <div class="absolute bottom-0 left-0 right-0 bg-black/55 text-xs px-2 py-1 text-gray-100 truncate">${region.position || `${region.x},${region.y}`}</div>
       <div class="absolute bottom-1 right-1 rounded bg-neon-accent/90 px-1.5 py-0.5 text-[10px] font-semibold text-dark-900">:${shortPort}</div>`
    : `<div class="h-full w-full bg-dark-900/70 text-gray-300 flex items-center justify-center text-sm text-center px-3">Map unavailable</div>
       <div class="absolute top-0 left-0 right-0 bg-black/55 text-xs px-2 py-1 text-gray-100 truncate">${region.name}</div>
       <div class="absolute bottom-0 left-0 right-0 bg-black/55 text-xs px-2 py-1 text-gray-100 truncate">${region.position || `${region.x},${region.y}`}</div>
       <div class="absolute bottom-1 right-1 rounded bg-neon-accent/90 px-1.5 py-0.5 text-[10px] font-semibold text-dark-900">:${shortPort}</div>`;

  button.addEventListener('click', () => setSelectedRegion(region));
  return button;
};

const openAddRegionDialog = () => {
  if (!addRegionModal || !addRegionForm || !addRegionNameInput || !addRegionXInput || !addRegionYInput || !addRegionPublicInput || !addRegionVoiceInput || !addRegionNextFreePortInput || !addRegionPortInput || !addRegionError) {
    return;
  }

  addRegionForm.reset();
  addRegionPublicInput.checked = true;
  addRegionVoiceInput.checked = true;
  addRegionNextFreePortInput.checked = true;
  addRegionPortInput.value = '';
  addRegionPortInput.disabled = true;
  addRegionError.classList.add('hidden');
  addRegionError.textContent = '';

  const suggested = suggestNextCoordinates();
  addRegionXInput.value = String(suggested.x);
  addRegionYInput.value = String(suggested.y);

  populateEstateOptions();
  populateOarOptions();
  syncEstateOwnerFields();

  addRegionModal.classList.remove('hidden');
  addRegionNameInput.focus();
};

const syncRegionPortField = () => {
  if (!addRegionPortInput || !addRegionNextFreePortInput) {
    return;
  }
  const useNext = !!addRegionNextFreePortInput.checked;
  addRegionPortInput.disabled = useNext;
  if (useNext) {
    addRegionPortInput.value = '';
  }
};

const closeAddRegionDialog = () => {
  addRegionModal?.classList.add('hidden');
};

const createEmptyCell = () => {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'h-36 rounded-lg border border-dashed border-neon-primary/35 bg-dark-900/40 hover:bg-dark-700/50 transition-colors flex items-center justify-center text-neon-accent';
  button.innerHTML = `<span class="h-10 w-10">${iconSpan('plus', 'h-10 w-10 inline-block')}</span>`;
  button.addEventListener('click', () => openAddRegionDialog());
  return button;
};

const renderGrid = () => {
  if (!regionsGrid) {
    return;
  }

  const size = activeGridSize();
  regionsGrid.innerHTML = '';
  regionsGrid.style.gridTemplateColumns = `repeat(${size.x}, minmax(0, 1fr))`;

  const visibleRegions = regions.slice(0, size.count);
  visibleRegions.forEach((region) => {
    regionsGrid.appendChild(createRegionCell(region));
  });

  for (let i = visibleRegions.length; i < size.count; i += 1) {
    regionsGrid.appendChild(createEmptyCell());
  }
};

const resolveSelectedRegion = () => {
  if (!regions.length) {
    selectedRegionId = '';
    renderDetails(null);
    return;
  }
  const selected = regions.find((region) => String(region.id || '') === selectedRegionId);
  if (selected) {
    renderDetails(selected);
    return;
  }
  setSelectedRegion(regions[0]);
};

const loadSimulatorStatus = async () => {
  const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(simulatorName)}`);
  if (!response.ok) {
    throw new Error(`Could not load simulator '${simulatorName}' (${response.status}).`);
  }
  simulatorStatus = await response.json();
};

const loadRegionData = async () => {
  const regionsResponse = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(simulatorName)}/regions`);
  const estatesResponse = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(simulatorName)}/estates`);
  const oarsResponse = await fetchWithTimeout('/api/simulator/oars');

  if (!regionsResponse.ok) {
    const body = await regionsResponse.text();
    throw new Error(body || `Could not load regions (${regionsResponse.status}).`);
  }
  if (!estatesResponse.ok) {
    const body = await estatesResponse.text();
    throw new Error(body || `Could not load estates (${estatesResponse.status}).`);
  }
  if (!oarsResponse.ok) {
    const body = await oarsResponse.text();
    throw new Error(body || `Could not load appearances (${oarsResponse.status}).`);
  }

  const loadedRegions = await regionsResponse.json();
  const loadedEstates = await estatesResponse.json();
  const loadedOars = await oarsResponse.json();

  regions = Array.isArray(loadedRegions)
    ? loadedRegions.slice().sort((a, b) => Number(a?.x || 0) - Number(b?.x || 0) || Number(a?.y || 0) - Number(b?.y || 0))
    : [];
  estates = Array.isArray(loadedEstates) ? loadedEstates : [];
  oars = Array.isArray(loadedOars) ? loadedOars : [];

  renderGrid();
  resolveSelectedRegion();
};

const populateOarOptions = () => {
  if (!addRegionOarSelect) {
    return;
  }
  addRegionOarSelect.innerHTML = '';
  const none = document.createElement('option');
  none.value = '';
  none.textContent = 'None';
  addRegionOarSelect.appendChild(none);

  oars.forEach((entry) => {
    const key = String(entry?.key || '').trim();
    if (!key) {
      return;
    }
    const option = document.createElement('option');
    option.value = key;
    option.textContent = String(entry?.name || key);
    addRegionOarSelect.appendChild(option);
  });
};

const populateEstateOptions = () => {
  if (!addRegionEstateSelect) {
    return;
  }

  addRegionEstateSelect.innerHTML = '';
  estates.forEach((estate) => {
    const name = String(estate?.name || '').trim();
    if (!name) {
      return;
    }
    const option = document.createElement('option');
    option.value = name;
    option.textContent = name;
    addRegionEstateSelect.appendChild(option);
  });

  const newEstateOption = document.createElement('option');
  newEstateOption.value = NEW_ESTATE_VALUE;
  newEstateOption.textContent = 'New Estate';
  addRegionEstateSelect.appendChild(newEstateOption);

  if (addRegionEstateSelect.options.length === 1) {
    addRegionEstateSelect.value = NEW_ESTATE_VALUE;
  }
};

const syncEstateOwnerFields = () => {
  if (!addEstateOwnerFields || !addRegionEstateSelect || !addEstateOwnerFirstInput || !addEstateOwnerLastInput) {
    return;
  }

  const isNewEstate = addRegionEstateSelect.value === NEW_ESTATE_VALUE;
  addEstateOwnerFields.classList.toggle('hidden', !isNewEstate);
  addEstateOwnerFirstInput.required = isNewEstate;
  addEstateOwnerLastInput.required = isNewEstate;
  if (!isNewEstate) {
    addEstateOwnerFirstInput.value = '';
    addEstateOwnerLastInput.value = '';
  }
};

const suggestNextCoordinates = () => {
  if (!regions.length) {
    return { x: 1000, y: 1000 };
  }

  const maxX = regions.reduce((acc, region) => Math.max(acc, Number(region?.x || 0)), 1000);
  const matchingY = regions.find((region) => Number(region?.x || 0) === maxX);
  const y = Number(matchingY?.y || 1000);
  return { x: maxX + 1, y };
};

const waitForRegionVisible = async (name, x, y, maxWaitMs = REQUEST_RECOVERY_WINDOW_MS) => {
  const targetName = String(name || '').trim().toLowerCase();
  const targetX = Number(x);
  const targetY = Number(y);
  const deadline = Date.now() + maxWaitMs;

  while (Date.now() < deadline) {
    try {
      const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(simulatorName)}/regions`);
      if (response.ok) {
        const list = await response.json();
        if (Array.isArray(list) && list.some((region) => {
          const regionNameMatches = String(region?.name || '').trim().toLowerCase() === targetName;
          const coordsMatch = Number(region?.x) === targetX && Number(region?.y) === targetY;
          return regionNameMatches || coordsMatch;
        })) {
          return true;
        }
      }
    } catch (_ignored) {
      // Ignore transient errors while waiting for eventual consistency.
    }
    await sleep(REQUEST_RECOVERY_POLL_MS);
  }

  return false;
};

const loadPage = async () => {
  simulatorName = parseSimulatorName();
  if (!simulatorName) {
    throw new Error('Missing simulator query parameter.');
  }

  await loadSimulatorStatus();
  if (!regionEditorSupported()) {
    throw new Error('Regions are only available for GRID and STANDALONE simulators.');
  }

  if (regionsSubtitle) {
    regionsSubtitle.textContent = `${simulatorName} (${normalizedLevel()})`;
  }

  await loadRegionData();
};

const submitCreateRegion = async (event) => {
  event.preventDefault();
  if (!addRegionNameInput || !addRegionXInput || !addRegionYInput || !addRegionEstateSelect || !addEstateOwnerFirstInput || !addEstateOwnerLastInput || !addRegionPublicInput || !addRegionVoiceInput || !addRegionNextFreePortInput || !addRegionPortInput || !addRegionOarSelect || !addRegionError || !submitAddRegionButton) {
    return;
  }

  const regionName = addRegionNameInput.value.trim();
  const regionX = addRegionXInput.value.trim();
  const regionY = addRegionYInput.value.trim();
  const estateSelection = addRegionEstateSelect.value;
  const regionPort = addRegionPortInput.value.trim();
  const regionOar = addRegionOarSelect.value.trim();
  const ownerFirst = addEstateOwnerFirstInput.value.trim();
  const ownerLast = addEstateOwnerLastInput.value.trim();

  if (!regionName) {
    addRegionError.textContent = 'Region name is required.';
    addRegionError.classList.remove('hidden');
    return;
  }
  if (Number.isNaN(Number(regionX)) || Number.isNaN(Number(regionY))) {
    addRegionError.textContent = 'X and Y must be valid numbers.';
    addRegionError.classList.remove('hidden');
    return;
  }
  if (!addRegionNextFreePortInput.checked) {
    if (!regionPort || !Number.isInteger(Number(regionPort))) {
      addRegionError.textContent = 'Port must be a whole number when Next Free Port is disabled.';
      addRegionError.classList.remove('hidden');
      return;
    }
    const numericPort = Number(regionPort);
    if (numericPort < 1 || numericPort > 65535) {
      addRegionError.textContent = 'Port must be between 1 and 65535.';
      addRegionError.classList.remove('hidden');
      return;
    }
  }
  if (!estateSelection) {
    addRegionError.textContent = 'Estate name is required.';
    addRegionError.classList.remove('hidden');
    return;
  }

  const isNewEstate = estateSelection === NEW_ESTATE_VALUE;
  if (isNewEstate && (!ownerFirst || !ownerLast)) {
    addRegionError.textContent = 'Estate owner first and last names are required for a new estate.';
    addRegionError.classList.remove('hidden');
    return;
  }

  const selectedExistingEstate = estates.find((estate) => String(estate?.name || '') === estateSelection);
  const estateName = isNewEstate ? `${regionName} Estate` : estateSelection;

  const payload = new URLSearchParams();
  payload.set('regionName', regionName);
  payload.set('regionX', regionX);
  payload.set('regionY', regionY);
  payload.set('estateName', estateName);
  payload.set('isPublic', addRegionPublicInput.checked ? 'true' : 'false');
  payload.set('enableVoice', addRegionVoiceInput.checked ? 'true' : 'false');
  if (!addRegionNextFreePortInput.checked && regionPort) {
    payload.set('regionPort', regionPort);
  }
  if (regionOar) {
    payload.set('regionOar', regionOar);
  }
  if (isNewEstate) {
    payload.set('estateOwnerFirst', ownerFirst);
    payload.set('estateOwnerLast', ownerLast);
  } else if (selectedExistingEstate) {
    payload.set('estateOwnerFirst', String(selectedExistingEstate.ownerFirst || ''));
    payload.set('estateOwnerLast', String(selectedExistingEstate.ownerLast || ''));
  }

  addRegionError.classList.add('hidden');
  addRegionError.textContent = '';
  submitAddRegionButton.disabled = true;

  try {
    await withWorkingOverlay(async () => {
      const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(simulatorName)}/regions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: payload.toString()
      });
      if (!response.ok) {
        const body = await response.text();
        throw new Error(body || `Create region failed (${response.status}).`);
      }
      await waitForRegionVisible(regionName, Number(regionX), Number(regionY));
      await loadRegionData();
      const created = regions.find((region) => String(region.name || '').toLowerCase() === regionName.toLowerCase());
      if (created) {
        setSelectedRegion(created);
      }
      closeAddRegionDialog();
      showToast(toastContainer, `Created region ${regionName}.`, 'success');
    }, `Creating region ${regionName} ...`);
  } catch (err) {
    const recovered = await withWorkingOverlay(
      async () => waitForRegionVisible(regionName, Number(regionX), Number(regionY)),
      `Verifying region ${regionName} ...`
    );
    if (recovered) {
      await loadRegionData();
      const created = regions.find((region) => String(region.name || '').toLowerCase() === regionName.toLowerCase());
      if (created) {
        setSelectedRegion(created);
      }
      closeAddRegionDialog();
      showToast(toastContainer, `Created region ${regionName}.`, 'success');
      submitAddRegionButton.disabled = false;
      return;
    }

    addRegionError.textContent = err instanceof Error ? err.message : 'Failed to create region.';
    addRegionError.classList.remove('hidden');
    showToast(toastContainer, addRegionError.textContent, 'error');
  } finally {
    submitAddRegionButton.disabled = false;
  }
};

const callRegionOptionsUpdate = async (regionId, isPublic, enableVoice) => {
  const payload = new URLSearchParams();
  payload.set('isPublic', isPublic ? 'true' : 'false');
  payload.set('enableVoice', enableVoice ? 'true' : 'false');
  const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(simulatorName)}/regions/${encodeURIComponent(regionId)}/options`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Failed to save options (${response.status}).`);
  }
};

const callRegionAction = async (regionId, action) => {
  const payload = new URLSearchParams();
  payload.set('action', action);
  const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(simulatorName)}/regions/${encodeURIComponent(regionId)}/actions`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Action '${action}' failed (${response.status}).`);
  }
};

const actionLabel = (action) => {
  switch (action) {
    case 'restart':
      return 'Restart';
    case 'shutdown':
      return 'Shutdown';
    case 'close':
      return 'Close';
    case 'delete':
      return 'Delete';
    default:
      return action;
  }
};

const actionIcon = (action) => {
  switch (action) {
    case 'close':
      return 'stop';
    default:
      return action;
  }
};

const syncActionButtonIcons = () => {
  document.querySelectorAll('button[data-region-action]').forEach((button) => {
    const action = String(button.getAttribute('data-region-action') || '').toLowerCase();
    if (!action) {
      return;
    }
    button.innerHTML = `${iconSpan(actionIcon(action), 'h-4 w-4 inline-block align-middle shrink-0')}<span>${actionLabel(action)}</span>`;
  });
};

const submitRegionOptions = async (event) => {
  event.preventDefault();
  const selected = regions.find((region) => String(region?.id || '') === selectedRegionId);
  if (!selected || !detailOptionPublic || !detailOptionVoice || !saveRegionOptionsButton) {
    return;
  }

  saveRegionOptionsButton.disabled = true;
  try {
    await withWorkingOverlay(async () => {
      await callRegionOptionsUpdate(selected.id, detailOptionPublic.checked, detailOptionVoice.checked);
      await loadRegionData();
      const refreshed = regions.find((region) => String(region?.id || '') === String(selected.id));
      if (refreshed) {
        setSelectedRegion(refreshed);
      }
      showToast(toastContainer, `Saved options for ${selected.name}.`, 'success');
    }, `Saving options for ${selected.name} ...`);
  } catch (err) {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to save region options.', 'error');
  } finally {
    saveRegionOptionsButton.disabled = false;
  }
};

const confirmRegionAction = (action, regionName) => {
  if (action === 'close') {
    return window.confirm(`Close region ${regionName}?`);
  }
  if (action === 'delete') {
    return window.confirm(`Delete region ${regionName}? This cannot be undone.`);
  }
  return true;
};

const handleRegionActionClick = async (button) => {
  const action = String(button.getAttribute('data-region-action') || '').toLowerCase();
  const selected = regions.find((region) => String(region?.id || '') === selectedRegionId);
  if (!action || !selected) {
    return;
  }

  if (!confirmRegionAction(action, selected.name)) {
    return;
  }

  button.disabled = true;
  try {
    await withWorkingOverlay(async () => {
      await callRegionAction(selected.id, action);
      await loadRegionData();
      const refreshed = regions.find((region) => String(region?.id || '') === String(selected.id));
      if (refreshed) {
        setSelectedRegion(refreshed);
      } else {
        resolveSelectedRegion();
      }
      showToast(toastContainer, `Sent '${action}' for ${selected.name}.`, 'success');
    }, `${actionLabel(action)} region ${selected.name} ...`);
  } catch (err) {
    showToast(toastContainer, err instanceof Error ? err.message : `Failed to ${action} region.`, 'error');
  } finally {
    button.disabled = false;
  }
};

document.addEventListener('DOMContentLoaded', () => {
  syncActionButtonIcons();

  gridSizeXInput?.addEventListener('change', () => renderGrid());
  gridSizeYInput?.addEventListener('change', () => renderGrid());

  refreshButton?.addEventListener('click', async () => {
    if (refreshInFlight) {
      return;
    }
    try {
      refreshButton.disabled = true;
      refreshInFlight = withWorkingOverlay(async () => {
        await loadRegionData();
      }, 'Refreshing regions ...');
      await refreshInFlight;
      showToast(toastContainer, 'Regions refreshed.', 'success');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Failed to refresh regions.', 'error');
    } finally {
      refreshInFlight = null;
      refreshButton.disabled = false;
    }
  });

  closeAddRegionButton?.addEventListener('click', () => closeAddRegionDialog());
  cancelAddRegionButton?.addEventListener('click', () => closeAddRegionDialog());
  addRegionModal?.addEventListener('click', (event) => {
    if (event.target === addRegionModal) {
      closeAddRegionDialog();
    }
  });

  addRegionEstateSelect?.addEventListener('change', () => syncEstateOwnerFields());
  addRegionNextFreePortInput?.addEventListener('change', () => syncRegionPortField());
  addRegionForm?.addEventListener('submit', submitCreateRegion);
  regionOptionsForm?.addEventListener('submit', submitRegionOptions);
  document.querySelectorAll('button[data-region-action]').forEach((button) => {
    button.addEventListener('click', async () => {
      await handleRegionActionClick(button);
    });
  });

  loadPage().catch((err) => {
    if (regionsSubtitle) {
      regionsSubtitle.textContent = simulatorName ? simulatorName : 'Unknown simulator';
    }
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load regions page.', 'error');
  });
});
