import {
  buildConsoleIconLink,
  fetchWithTimeout,
  iconSpan,
  renderContainerStatusRows,
  resolvePreferredConsole,
  showToast,
  withWorkingOverlay
} from '/ui/ui-helpers.js';

const simulatorsGrid = document.getElementById('simulators-grid');
const simulatorsEmpty = document.getElementById('simulators-empty');
const refreshButton = document.getElementById('refresh-simulators');
const openCreateSimulatorButton = document.getElementById('open-create-simulator');
const createSimulatorModal = document.getElementById('create-simulator-modal');
const closeCreateSimulatorButton = document.getElementById('close-create-simulator');
const cancelCreateSimulatorButton = document.getElementById('cancel-create-simulator');
const createSimulatorForm = document.getElementById('create-simulator-form');
const createSimulatorTitle = document.getElementById('create-simulator-title');
const createSimulatorSubtitle = document.getElementById('create-simulator-subtitle');
const createSimulatorInfo = document.getElementById('create-simulator-info');
const createSimulatorError = document.getElementById('create-simulator-error');
const createSimulatorLevelRow = document.getElementById('create-simulator-level-row');
const createSimulatorLevel = document.getElementById('create-sim-level');
const createSimulatorName = document.getElementById('create-sim-name');
const createSimulatorPort = document.getElementById('create-sim-port');
const createOwnerFields = document.getElementById('create-owner-fields');
const createOwnerFirst = document.getElementById('create-owner-first');
const createOwnerLast = document.getElementById('create-owner-last');
const createOwnerEmailRow = document.getElementById('create-owner-email-row');
const createOwnerEmail = document.getElementById('create-owner-email');
const createRegionFields = document.getElementById('create-region-fields');
const createRegionX = document.getElementById('create-region-x');
const createRegionY = document.getElementById('create-region-y');
const createSimulatorOarRow = document.getElementById('create-sim-oar-row');
const createSimulatorOar = document.getElementById('create-sim-oar');
const createSimulatorBotRow = document.getElementById('create-simulator-bot-row');
const createSimulatorBot = document.getElementById('create-simulator-bot');
const submitCreateSimulator = document.getElementById('submit-create-simulator');
const toastContainer = document.getElementById('toast-container');

let cachedSimulatorStatuses = [];
let cachedCreatePolicy = null;
let cachedLevelRules = [];
let cachedOars = [];

const REQUEST_RECOVERY_WINDOW_MS = 180000;
const REQUEST_RECOVERY_POLL_MS = 3000;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const levelIcon = (level) => {
  switch ((level || '').toUpperCase()) {
    case 'ROBUST':
      return 'ROB';
    case 'GRID':
      return 'GRD';
    case 'STANDALONE':
      return 'STN';
    default:
      return 'SIM';
  }
};

const normalizeMapHost = (hostname) => {
  const value = String(hostname || '').trim();
  if (!value) {
    return value;
  }
  // IPv6 literals must be wrapped for host:port URLs.
  return value.includes(':') && !value.startsWith('[') ? `[${value}]` : value;
};

const buildWorldMapUrl = (status) => {
  const port = Number(status?.port);
  const regionX = Number(status?.region?.x);
  const regionY = Number(status?.region?.y);
  const host = normalizeMapHost(window.location.hostname);
  if (!host || !Number.isFinite(port) || port <= 0 || !Number.isFinite(regionX) || !Number.isFinite(regionY)) {
    return null;
  }
  return `http://${host}:${port}/map-1-${regionX}-${regionY}-objects.jpg`;
};

const normalizedLevel = (status) => String(status?.level || '').trim().toUpperCase();
const normalizeOwnerFirstFromName = (name) => String(name || '').replace(/\s+/g, '').trim();
const createBotToggleAllowedForLevel = (levelName) => {
  const normalized = String(levelName || '').trim().toUpperCase();
  return normalized.length > 0 && normalized !== 'ROBUST';
};

const actionVerb = (action) => {
  switch (String(action || '').toLowerCase()) {
    case 'start':
      return 'Starting';
    case 'stop':
      return 'Stopping';
    case 'restart':
      return 'Restarting';
    case 'delete':
      return 'Deleting';
    default:
      return 'Working on';
  }
};

const ownerLastPlaceholder = () => (createSimulatorBot?.checked ? 'Bot' : 'Owner');

const syncOwnerPlaceholders = () => {
  const normalized = normalizeOwnerFirstFromName(createSimulatorName?.value || '');
  if (createOwnerFirst) {
    createOwnerFirst.placeholder = normalized;
  }
  if (createOwnerLast) {
    createOwnerLast.placeholder = ownerLastPlaceholder();
  }
};

const computeCreatePolicy = (statuses) => {
  const all = Array.isArray(statuses) ? statuses : [];
  if (all.length === 0) {
    return {
      allowed: true,
      showSelector: true,
      selectableLevels: ['ROBUST', 'STANDALONE'],
      fixedLevel: null,
      message: 'First simulator can be ROBUST or STANDALONE.'
    };
  }

  const levels = all.map((status) => normalizedLevel(status));
  const hasStandalone = levels.includes('STANDALONE');
  if (hasStandalone) {
    return {
      allowed: false,
      showSelector: false,
      selectableLevels: [],
      fixedLevel: null,
      message: 'A STANDALONE simulator already exists. You cannot create additional simulators.'
    };
  }

  const hasRobust = levels.includes('ROBUST');
  if (hasRobust) {
    return {
      allowed: true,
      showSelector: false,
      selectableLevels: [],
      fixedLevel: 'GRID',
      message: 'ROBUST exists, so new simulators are created as GRID.'
    };
  }

  return {
    allowed: false,
    showSelector: false,
    selectableLevels: [],
    fixedLevel: null,
    message: 'Simulator topology is not valid for creating new simulators.'
  };
};

const callAction = async (name, action) => {
  const payload = new URLSearchParams();
  payload.set('action', action);
  const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(name)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });
  if (!response.ok) {
    throw new Error(`Action '${action}' failed (${response.status}).`);
  }
};

const deleteSimulator = async (name) => {
  const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(name)}`, {
    method: 'DELETE'
  });
  if (!response.ok) {
    throw new Error(`Delete failed (${response.status}).`);
  }
};

const createSimulator = async ({ name, level, port, ownerFirst, ownerLast, ownerEmail, oar, regionX, regionY, createBot }) => {
  const payload = new URLSearchParams();
  payload.set('level', level);
  if (ownerFirst) {
    payload.set('ownerFirst', ownerFirst);
  }
  if (ownerLast) {
    payload.set('ownerLast', ownerLast);
  }
  if (ownerEmail) {
    payload.set('ownerEmail', ownerEmail);
  }
  if (oar) {
    payload.set('oar', oar);
  }
  if (port) {
    payload.set('port', port);
  }
  if (regionX) {
    payload.set('regionX', regionX);
  }
  if (regionY) {
    payload.set('regionY', regionY);
  }
  if (createBot) {
    payload.set('createBot', 'true');
  }

  const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(name)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Create simulator failed (${response.status}).`);
  }
};

const resetCreateDialog = () => {
  createSimulatorForm?.reset();
  if (createSimulatorBot) {
    createSimulatorBot.checked = true;
  }
  if (createRegionX) {
    createRegionX.value = '1000';
  }
  if (createRegionY) {
    createRegionY.value = '1000';
  }
  if (createSimulatorError) {
    createSimulatorError.textContent = '';
    createSimulatorError.classList.add('hidden');
  }
  syncOwnerPlaceholders();
};

const regionRequiredForLevel = (levelName) => {
  const normalized = String(levelName || '').trim().toUpperCase();
  if (!normalized) {
    return false;
  }
  const fromApi = cachedLevelRules.find((rule) => String(rule?.name || '').toUpperCase() === normalized);
  if (fromApi) {
    return !!fromApi.regionRequired;
  }
  return normalized !== 'ROBUST';
};

const activeCreateLevel = () => {
  const policy = cachedCreatePolicy || computeCreatePolicy(cachedSimulatorStatuses);
  if (policy.showSelector) {
    return String(createSimulatorLevel?.value || '').trim().toUpperCase();
  }
  return String(policy.fixedLevel || '').trim().toUpperCase();
};

const setRegionFieldMode = (regionRequired) => {
  createOwnerFields?.classList.toggle('hidden', !regionRequired);
  createOwnerEmailRow?.classList.toggle('hidden', !regionRequired);
  createRegionFields?.classList.toggle('hidden', !regionRequired);
  createSimulatorOarRow?.classList.toggle('hidden', !regionRequired);
  if (!regionRequired) {
    if (createOwnerFirst) {
      createOwnerFirst.value = '';
    }
    if (createOwnerLast) {
      createOwnerLast.value = '';
    }
    if (createOwnerEmail) {
      createOwnerEmail.value = '';
    }
    if (createSimulatorOar) {
      createSimulatorOar.value = '';
    }
    if (createRegionX) {
      createRegionX.value = '1000';
    }
    if (createRegionY) {
      createRegionY.value = '1000';
    }
  }
};

const syncCreateDialogFields = () => {
  const level = activeCreateLevel();
  const regionRequired = regionRequiredForLevel(level);
  const showCreateBotToggle = createBotToggleAllowedForLevel(level);
  createSimulatorBotRow?.classList.toggle('hidden', !showCreateBotToggle);
  if (showCreateBotToggle && createSimulatorBot) {
    createSimulatorBot.checked = true;
  }
  setRegionFieldMode(regionRequired);
  syncOwnerPlaceholders();
};

const loadCreateDialogMetadata = async () => {
  const [levelsResponse, oarsResponse] = await Promise.all([
    fetchWithTimeout('/api/simulator/levels'),
    fetchWithTimeout('/api/simulator/oars')
  ]);

  if (!levelsResponse.ok) {
    throw new Error(`Could not load simulator levels (${levelsResponse.status}).`);
  }
  if (!oarsResponse.ok) {
    throw new Error(`Could not load OAR list (${oarsResponse.status}).`);
  }

  const levels = await levelsResponse.json();
  const oars = await oarsResponse.json();
  cachedLevelRules = Array.isArray(levels) ? levels : [];
  cachedOars = Array.isArray(oars) ? oars : [];
};

const populateOarOptions = () => {
  if (!createSimulatorOar) {
    return;
  }

  createSimulatorOar.innerHTML = '';
  const noneOption = document.createElement('option');
  noneOption.value = '';
  noneOption.textContent = 'None';
  createSimulatorOar.appendChild(noneOption);

  cachedOars.forEach((entry) => {
    const key = String(entry?.key || '').trim();
    if (!key) {
      return;
    }

    const option = document.createElement('option');
    option.value = key;
    option.textContent = String(entry?.name || key);
    createSimulatorOar.appendChild(option);
  });
};

const closeCreateDialog = () => {
  createSimulatorModal?.classList.add('hidden');
};

const openCreateDialog = async () => {
  if (!createSimulatorModal || !createSimulatorLevelRow || !createSimulatorLevel || !createSimulatorInfo || !createSimulatorSubtitle || !createSimulatorTitle) {
    return;
  }

  if (!cachedSimulatorStatuses.length) {
    try {
      await loadSimulators();
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Failed to refresh simulators before create.', 'error');
      return;
    }
  }

  const policy = cachedCreatePolicy || computeCreatePolicy(cachedSimulatorStatuses);
  cachedCreatePolicy = policy;
  if (!policy.allowed) {
    showToast(toastContainer, policy.message, 'error');
    return;
  }

  if (!cachedLevelRules.length || !cachedOars.length) {
    try {
      await loadCreateDialogMetadata();
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load dialog metadata.', 'error');
      return;
    }
  }

  resetCreateDialog();
  createSimulatorTitle.textContent = 'Create Simulator';
  createSimulatorSubtitle.textContent = policy.fixedLevel
    ? `New simulators are fixed to ${policy.fixedLevel}.`
    : 'Select the simulator level for the first simulator.';
  createSimulatorInfo.textContent = policy.message;

  if (policy.showSelector) {
    createSimulatorLevel.innerHTML = '';
    policy.selectableLevels.forEach((level) => {
      const option = document.createElement('option');
      option.value = level;
      option.textContent = level;
      createSimulatorLevel.appendChild(option);
    });
    createSimulatorLevelRow.classList.remove('hidden');
  } else {
    createSimulatorLevelRow.classList.add('hidden');
  }

  populateOarOptions();
  syncCreateDialogFields();

  createSimulatorModal.classList.remove('hidden');
  createSimulatorName?.focus();
};

const createCard = (status) => {
  const card = document.createElement('section');
  card.className = 'feature-card bg-dark-800/80 backdrop-blur rounded-xl p-5 flex flex-col gap-4 sm:col-span-2 xl:col-span-2';

  const name = String(status.name || '').trim() || 'Unnamed Simulator';
  const level = status.level || 'UNKNOWN';
  const ownerFirst = status.ownerFirst || '';
  const ownerLast = status.ownerLast || '';
  const ownerDisplay = `${ownerFirst} ${ownerLast}`.trim();
  const ownerLine = ownerDisplay
    ? `<p class="text-sm text-gray-400">Owner: ${ownerDisplay}</p>`
    : '';
  const port = Number(status?.port);
  const portBadge = Number.isFinite(port) && port > 0
    ? `<div class="mt-2"><span class="inline-flex items-center rounded-md border border-neon-accent/40 bg-neon-accent/10 px-2.5 py-1 text-xs font-medium text-neon-accent">Port ${port}</span></div>`
    : '';
  const containers = Array.isArray(status.containerStatus) ? status.containerStatus : [];
  const worldMapUrl = buildWorldMapUrl(status);
  const worldMapPane = worldMapUrl
    ? `<div class="relative h-full w-full">
        <img src="${worldMapUrl}" alt="World map for ${name}" loading="lazy" class="h-full w-full object-cover rounded-lg border border-neon-accent/30 bg-dark-900/50" />
        <a href="${worldMapUrl}" target="_blank" rel="noopener noreferrer" class="absolute right-2 top-2 rounded-md border border-neon-accent/50 bg-dark-900/80 px-2 py-1 text-xs font-medium text-neon-accent hover:bg-dark-800">Open map</a>
      </div>`
    : `<div class="h-full w-full rounded-lg border border-neon-accent/20 bg-dark-900/40 text-sm text-gray-400 flex items-center justify-center text-center px-4">World map unavailable</div>`;

  const preferredConsole = resolvePreferredConsole(containers, 'opensim-simulator-');
  const preferredConsoleLink = preferredConsole
    ? buildConsoleIconLink(preferredConsole.name, preferredConsole.target, 'Open preferred console', 'text-neon-accent hover:text-neon-secondary')
    : '';
  const containerRows = renderContainerStatusRows(containers);

  card.innerHTML = `
    <div class="flex flex-col lg:flex-row gap-4">
      <div class="w-full lg:w-1/2 flex flex-col gap-4 min-w-0">
        <div class="flex items-start justify-between gap-3">
          <div>
            <h2 class="text-xl font-semibold text-white">${name}</h2>
            ${ownerLine}
          </div>
          <div class="flex items-start gap-2">
            ${preferredConsoleLink}
            <div class="w-14 h-14 rounded-xl bg-neon-primary/20 border border-neon-primary/40 flex items-center justify-center text-neon-primary font-bold">
              ${levelIcon(level)}
            </div>
          </div>
        </div>

        <div class="text-xs uppercase tracking-wide text-neon-accent">${level}</div>

        <div class="space-y-2 bg-dark-900/50 rounded-lg p-3 border border-neon-primary/20">
          ${containerRows || '<div class="text-sm text-gray-400">No tracked containers.</div>'}
          ${portBadge}
        </div>

        <div class="grid grid-cols-2 gap-2 mt-auto">
          <button data-action="start" class="px-3 py-2 rounded-lg bg-emerald-600/20 border border-emerald-400/40 text-emerald-200 hover:bg-emerald-600/30 inline-flex items-center justify-center gap-2">${iconSpan('start', 'h-4 w-4 inline-block align-middle shrink-0')}<span>Start</span></button>
          <button data-action="stop" class="px-3 py-2 rounded-lg bg-amber-600/20 border border-amber-400/40 text-amber-200 hover:bg-amber-600/30 inline-flex items-center justify-center gap-2">${iconSpan('stop', 'h-4 w-4 inline-block align-middle shrink-0')}<span>Stop</span></button>
          <button data-action="restart" class="px-3 py-2 rounded-lg bg-sky-600/20 border border-sky-400/40 text-sky-200 hover:bg-sky-600/30 inline-flex items-center justify-center gap-2">${iconSpan('restart', 'h-4 w-4 inline-block align-middle shrink-0')}<span>Restart</span></button>
          <button data-action="delete" class="px-3 py-2 rounded-lg bg-rose-600/20 border border-rose-400/40 text-rose-200 hover:bg-rose-600/30 inline-flex items-center justify-center gap-2">${iconSpan('delete', 'h-4 w-4 inline-block align-middle shrink-0')}<span>Delete</span></button>
        </div>
      </div>

      <div class="w-full lg:w-1/2 min-h-[220px] lg:min-h-full">
        ${worldMapPane}
      </div>
    </div>
  `;

  card.querySelectorAll('button[data-action]').forEach((button) => {
    button.addEventListener('click', async () => {
      const action = button.getAttribute('data-action');
      if (!action) {
        return;
      }

      button.disabled = true;
      try {
        await withWorkingOverlay(async () => {
          if (action === 'delete') {
            if (!window.confirm(`Delete simulator ${name}?`)) {
              return;
            }
            await deleteSimulator(name);
            showToast(toastContainer, `Deleted simulator ${name}.`, 'success');
          } else {
            await callAction(name, action);
            showToast(toastContainer, `Sent '${action}' for ${name}.`, 'success');
          }
          await loadSimulators();
        }, `${actionVerb(action)} simulator ${name} ...`);
      } catch (err) {
        const recovered = await withWorkingOverlay(
          async () => waitForSimulatorActionOutcome(name, action),
          `Verifying ${actionVerb(action).toLowerCase()} result for simulator ${name} ...`
        );
        if (recovered) {
          await loadSimulators();
          if (action === 'delete') {
            showToast(toastContainer, `Deleted simulator ${name}.`, 'success');
          } else {
            showToast(toastContainer, `Completed '${action}' for ${name}.`, 'success');
          }
          return;
        }
        showToast(toastContainer, err instanceof Error ? err.message : 'Request failed.', 'error');
      } finally {
        button.disabled = false;
      }
    });
  });

  return card;
};

const loadSimulators = async () => {
  if (!simulatorsGrid || !simulatorsEmpty) {
    return;
  }

  simulatorsGrid.innerHTML = '';
  simulatorsEmpty.classList.add('hidden');

  const listResponse = await fetchWithTimeout('/api/simulator');
  if (!listResponse.ok) {
    throw new Error(`Could not list simulators (${listResponse.status}).`);
  }

  const simulatorNames = await listResponse.json();
  if (!Array.isArray(simulatorNames) || simulatorNames.length === 0) {
    cachedSimulatorStatuses = [];
    cachedCreatePolicy = computeCreatePolicy(cachedSimulatorStatuses);
    simulatorsEmpty.classList.remove('hidden');
    return;
  }

  const statuses = await Promise.all(simulatorNames.map(async (name) => {
    const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(name)}`);
    if (!response.ok) {
      throw new Error(`Could not load simulator '${name}' (${response.status}).`);
    }
    return response.json();
  }));

  cachedSimulatorStatuses = statuses;
  cachedCreatePolicy = computeCreatePolicy(statuses);

  statuses.forEach((status) => {
    simulatorsGrid.appendChild(createCard(status));
  });
};

const waitForSimulatorVisible = async (name, maxWaitMs = REQUEST_RECOVERY_WINDOW_MS) => {
  const deadline = Date.now() + maxWaitMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(name)}`);
      if (response.ok) {
        return true;
      }
    } catch (_ignored) {
      // Ignore transient errors while waiting for eventual consistency after create.
    }
    await new Promise((resolve) => setTimeout(resolve, REQUEST_RECOVERY_POLL_MS));
  }
  return false;
};

const fetchSimulatorStatusOrNull = async (name) => {
  try {
    const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(name)}`);
    if (!response.ok) {
      return null;
    }
    return response.json();
  } catch (_ignored) {
    return null;
  }
};

const waitForSimulatorActionOutcome = async (name, action, maxWaitMs = REQUEST_RECOVERY_WINDOW_MS) => {
  const normalizedAction = String(action || '').toLowerCase();
  const deadline = Date.now() + maxWaitMs;

  while (Date.now() < deadline) {
    if (normalizedAction === 'delete') {
      try {
        const listResponse = await fetchWithTimeout('/api/simulator');
        if (listResponse.ok) {
          const names = await listResponse.json();
          if (Array.isArray(names) && !names.includes(name)) {
            return true;
          }
        }
      } catch (_ignored) {
        // Keep polling through transient failures.
      }
      await sleep(REQUEST_RECOVERY_POLL_MS);
      continue;
    }

    const status = await fetchSimulatorStatusOrNull(name);
    const containers = Array.isArray(status?.containerStatus) ? status.containerStatus : [];
    if (containers.length > 0) {
      if ((normalizedAction === 'start' || normalizedAction === 'restart')
        && containers.every((container) => !!container.running)) {
        return true;
      }
      if (normalizedAction === 'stop' && containers.every((container) => !container.running)) {
        return true;
      }
    }

    await sleep(REQUEST_RECOVERY_POLL_MS);
  }

  return false;
};

document.addEventListener('DOMContentLoaded', () => {
  openCreateSimulatorButton?.addEventListener('click', async () => {
    await openCreateDialog();
  });

  closeCreateSimulatorButton?.addEventListener('click', () => {
    closeCreateDialog();
  });

  cancelCreateSimulatorButton?.addEventListener('click', () => {
    closeCreateDialog();
  });

  createSimulatorModal?.addEventListener('click', (event) => {
    if (event.target === createSimulatorModal) {
      closeCreateDialog();
    }
  });

  createSimulatorForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!createSimulatorName || !createSimulatorPort || !createOwnerFirst || !createOwnerLast || !createOwnerEmail || !createSimulatorOar || !createRegionX || !createRegionY || !submitCreateSimulator || !createSimulatorError) {
      return;
    }

    const policy = cachedCreatePolicy || computeCreatePolicy(cachedSimulatorStatuses);
    if (!policy.allowed) {
      createSimulatorError.textContent = policy.message;
      createSimulatorError.classList.remove('hidden');
      return;
    }

    const name = createSimulatorName.value.trim();
    const port = createSimulatorPort.value.trim();
    const ownerFirstInput = createOwnerFirst.value.trim();
    const ownerLastInput = createOwnerLast.value.trim();
    const ownerFirst = ownerFirstInput || normalizeOwnerFirstFromName(name);
    const ownerLast = ownerLastInput || ownerLastPlaceholder();
    const ownerEmail = createOwnerEmail.value.trim();
    const oar = createSimulatorOar.value.trim();
    const regionX = createRegionX.value.trim() || '1000';
    const regionY = createRegionY.value.trim() || '1000';
    const level = policy.showSelector
      ? String(createSimulatorLevel?.value || '').toUpperCase()
      : String(policy.fixedLevel || '').toUpperCase();
    const regionRequired = regionRequiredForLevel(level);
    const createBot = createBotToggleAllowedForLevel(level) && !!createSimulatorBot?.checked;

    if (!name || !level) {
      createSimulatorError.textContent = 'Name and level are required.';
      createSimulatorError.classList.remove('hidden');
      return;
    }

    if (port && Number.isNaN(Number(port))) {
      createSimulatorError.textContent = 'Port must be a number when provided.';
      createSimulatorError.classList.remove('hidden');
      return;
    }

    if (regionRequired && (!ownerFirst || !ownerLast)) {
      createSimulatorError.textContent = 'Owner first and last name are required for this simulator level.';
      createSimulatorError.classList.remove('hidden');
      return;
    }

    if (regionRequired && (Number.isNaN(Number(regionX)) || Number.isNaN(Number(regionY)))) {
      createSimulatorError.textContent = 'Region X and Y must be valid numbers.';
      createSimulatorError.classList.remove('hidden');
      return;
    }

    createSimulatorError.classList.add('hidden');
    submitCreateSimulator.disabled = true;

    try {
      await withWorkingOverlay(async () => {
        await createSimulator({
          name,
          level,
          port,
          ownerFirst: regionRequired ? ownerFirst : '',
          ownerLast: regionRequired ? ownerLast : '',
          ownerEmail: regionRequired ? ownerEmail : '',
          oar: regionRequired ? oar : '',
          regionX: regionRequired ? regionX : '',
          regionY: regionRequired ? regionY : '',
          createBot
        });
        closeCreateDialog();
        await loadSimulators();
        showToast(toastContainer, `Created simulator ${name}.`, 'success');
      }, `Creating simulator ${name} ...`);
    } catch (err) {
      const recovered = await withWorkingOverlay(
        async () => waitForSimulatorVisible(name),
        `Verifying simulator ${name} ...`
      );
      if (recovered) {
        closeCreateDialog();
        await loadSimulators();
        showToast(toastContainer, `Created simulator ${name}.`, 'success');
        return;
      }

      createSimulatorError.textContent = err instanceof Error ? err.message : 'Failed to create simulator.';
      createSimulatorError.classList.remove('hidden');
      showToast(toastContainer, createSimulatorError.textContent, 'error');
    } finally {
      submitCreateSimulator.disabled = false;
    }
  });

  refreshButton?.addEventListener('click', async () => {
    try {
      await withWorkingOverlay(async () => {
        await loadSimulators();
      }, 'Refreshing simulators ...');
      showToast(toastContainer, 'Simulator list refreshed.', 'success');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Refresh failed.', 'error');
    }
  });

  createSimulatorLevel?.addEventListener('change', () => {
    syncCreateDialogFields();
  });

  createSimulatorName?.addEventListener('input', () => {
    syncOwnerPlaceholders();
  });

  createSimulatorBot?.addEventListener('change', () => {
    syncOwnerPlaceholders();
  });

  loadSimulators().catch((err) => {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load simulators.', 'error');
  });
});
