import {
  buildConsoleIconLink,
  fetchWithTimeout,
  iconSpan,
  renderContainerStatusRows,
  resolvePreferredConsole,
  showToast,
  withWorkingOverlay
} from '/ui/ui-helpers.js';

const botsGrid = document.getElementById('bots-grid');
const botsEmpty = document.getElementById('bots-empty');
const refreshButton = document.getElementById('refresh-bots');
const openCreateBotButton = document.getElementById('open-create-bot');
const createBotModal = document.getElementById('create-bot-modal');
const closeCreateBotButton = document.getElementById('close-create-bot');
const cancelCreateBotButton = document.getElementById('cancel-create-bot');
const createBotForm = document.getElementById('create-bot-form');
const createBotTitle = document.getElementById('create-bot-title');
const createBotSubtitle = document.getElementById('create-bot-subtitle');
const createBotInfo = document.getElementById('create-bot-info');
const createBotError = document.getElementById('create-bot-error');
const createMode = document.getElementById('create-mode');
const createParent = document.getElementById('create-parent');
const createLevelRow = document.getElementById('create-bot-level-row');
const createLevel = document.getElementById('create-level');
const createFirst = document.getElementById('create-first');
const createLast = document.getElementById('create-last');
const createEmail = document.getElementById('create-email');
const createAppearance = document.getElementById('create-appearance');
const createGenderGroup = document.getElementById('create-gender-group');
const submitCreateBot = document.getElementById('submit-create-bot');
const toastContainer = document.getElementById('toast-container');
const botsGridServiceWarning = document.getElementById('bots-grid-service-warning');
const botsGridServicePill = document.getElementById('bots-grid-service-pill');

let gridServiceAvailable = false;
let cachedAppearanceNames = [];
const gridServiceUnavailableMessage = 'Bot management is disabled until a ROBUST or STANDALONE simulator is active.';
const REQUEST_RECOVERY_WINDOW_MS = 180000;
const REQUEST_RECOVERY_POLL_MS = 3000;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const childLevelsByParentLevel = {
  GOVERNOR: ['BUILDER', 'ACTOR'],
  BUILDER: ['ACTOR']
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

const levelIcon = (level) => {
  switch ((level || '').toUpperCase()) {
    case 'GOVERNOR':
      return 'GOV';
    case 'BUILDER':
      return 'BLD';
    case 'ACTOR':
      return 'ACT';
    default:
      return 'BOT';
  }
};

const splitBotName = (displayName) => {
  const trimmed = (displayName || '').trim();
  if (!trimmed) {
    return { first: '', last: '' };
  }
  const firstSpace = trimmed.indexOf(' ');
  if (firstSpace < 0) {
    return { first: trimmed, last: '' };
  }
  return {
    first: trimmed.slice(0, firstSpace),
    last: trimmed.slice(firstSpace + 1)
  };
};

const callAction = async (first, last, action) => {
  const payload = new URLSearchParams();
  payload.set('action', action);
  const response = await fetchWithTimeout(`/api/bot/${encodeURIComponent(first)}/${encodeURIComponent(last)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });
  if (!response.ok) {
    throw new Error(`Action '${action}' failed (${response.status}).`);
  }
};

const deleteBot = async (first, last) => {
  const response = await fetchWithTimeout(`/api/bot/${encodeURIComponent(first)}/${encodeURIComponent(last)}`, {
    method: 'DELETE'
  });
  if (!response.ok) {
    throw new Error(`Delete failed (${response.status}).`);
  }
};

const createBot = async ({ first, last, level, parent, email, appearance, gender }) => {
  const payload = new URLSearchParams();
  payload.set('level', level);
  payload.set('parent', parent || '');
  if (email) {
    payload.set('email', email);
  }
  if (appearance) {
    payload.set('appearance', appearance);
  }
  if (gender) {
    payload.set('gender', gender);
  }

  const response = await fetchWithTimeout(`/api/bot/${encodeURIComponent(first)}/${encodeURIComponent(last)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Create bot failed (${response.status}).`);
  }
};

const fetchGridServiceAvailability = async () => {
  const response = await fetchWithTimeout('/api/simulator/grid-service');
  if (!response.ok) {
    throw new Error(`Could not determine grid service availability (${response.status}).`);
  }
  const payload = await response.json();
  return !!payload?.available;
};

const fetchAppearanceNames = async () => {
  const response = await fetchWithTimeout('/api/bot/appearances');
  if (!response.ok) {
    throw new Error(`Could not load appearances (${response.status}).`);
  }
  const payload = await response.json();
  return Array.isArray(payload) ? payload : [];
};

const populateAppearanceOptions = () => {
  if (!createAppearance) {
    return;
  }

  createAppearance.innerHTML = '';
  const defaultOption = document.createElement('option');
  defaultOption.value = '';
  defaultOption.textContent = 'Default';
  createAppearance.appendChild(defaultOption);

  cachedAppearanceNames.forEach((name) => {
    const value = String(name || '').trim();
    if (!value) {
      return;
    }
    const option = document.createElement('option');
    option.value = value;
    option.textContent = value;
    createAppearance.appendChild(option);
  });
};

const ensureAppearanceOptionsLoaded = async () => {
  if (cachedAppearanceNames.length > 0) {
    populateAppearanceOptions();
    return;
  }
  cachedAppearanceNames = await fetchAppearanceNames();
  populateAppearanceOptions();
};

const renderGenderOptions = () => {
  if (!createGenderGroup) {
    return;
  }

  const options = [
    { value: 'male', label: 'Male', icon: 'male' },
    { value: 'female', label: 'Female', icon: 'female' },
    { value: 'neutral', label: 'Neutral', icon: 'neutral' }
  ];

  createGenderGroup.innerHTML = options.map((option) => `
    <label class="cursor-pointer">
      <input class="peer sr-only" type="radio" name="create-gender" value="${option.value}" ${option.value === 'neutral' ? 'checked' : ''}>
      <span class="inline-flex w-full items-center justify-center gap-2 rounded-lg border border-neon-primary/30 bg-dark-700 px-3 py-2 text-gray-200 transition-colors peer-checked:border-neon-accent peer-checked:bg-neon-accent/20 peer-checked:text-neon-accent hover:border-neon-primary/60">
        ${iconSpan(option.icon, 'h-6 w-6 inline-block align-middle shrink-0')}
        <span class="text-sm">${option.label}</span>
      </span>
    </label>
  `).join('');
};

const toggleButtonDisabledVisual = (button, disabled) => {
  if (!button) {
    return;
  }
  button.disabled = !!disabled;
  button.classList.toggle('opacity-50', !!disabled);
  button.classList.toggle('cursor-not-allowed', !!disabled);
};

const applyGridServiceUiState = () => {
  toggleButtonDisabledVisual(openCreateBotButton, !gridServiceAvailable);
  if (botsGridServiceWarning) {
    botsGridServiceWarning.classList.toggle('hidden', gridServiceAvailable);
  }
  if (botsGridServicePill) {
    botsGridServicePill.className = `mt-2 inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs ${gridServiceAvailable
      ? 'border-emerald-400/40 bg-emerald-500/10 text-emerald-200'
      : 'border-amber-400/40 bg-amber-500/10 text-amber-200'}`;
    botsGridServicePill.innerHTML = gridServiceAvailable
      ? '<span class="w-2 h-2 rounded-full bg-emerald-300"></span><span>Grid Login: Available</span>'
      : '<span class="w-2 h-2 rounded-full bg-amber-300"></span><span>Grid Login: Unavailable</span>';
  }
};

const resetCreateDialog = () => {
  if (!createBotForm) {
    return;
  }
  createBotForm.reset();
  if (createBotError) {
    createBotError.textContent = '';
    createBotError.classList.add('hidden');
  }
  const defaultGender = createGenderGroup?.querySelector('input[name="create-gender"][value="neutral"]');
  if (defaultGender instanceof HTMLInputElement) {
    defaultGender.checked = true;
  }
};

const closeCreateDialog = () => {
  if (createBotModal) {
    createBotModal.classList.add('hidden');
  }
};

const openGovernorDialog = async () => {
  if (!gridServiceAvailable) {
    showToast(toastContainer, gridServiceUnavailableMessage, 'error');
    return;
  }
  if (!createBotModal || !createMode || !createParent || !createBotTitle || !createBotSubtitle || !createBotInfo || !createLevelRow) {
    return;
  }

  try {
    await ensureAppearanceOptionsLoaded();
  } catch (err) {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load appearance options.', 'error');
    return;
  }

  resetCreateDialog();
  createMode.value = 'governor';
  createParent.value = '';
  createBotTitle.textContent = 'Create Governor Bot';
  createBotSubtitle.textContent = 'This creates a top-level dialog bot (no parent).';
  createBotInfo.textContent = 'A governor bot is powerful. For delegation patterns, consider spawning a child bot from an existing governor or builder card.';
  createLevelRow.classList.add('hidden');
  createBotModal.classList.remove('hidden');
  createFirst?.focus();
};

const openChildDialog = async (parentStatus) => {
  if (!gridServiceAvailable) {
    showToast(toastContainer, gridServiceUnavailableMessage, 'error');
    return;
  }
  if (!createBotModal || !createMode || !createParent || !createBotTitle || !createBotSubtitle || !createBotInfo || !createLevelRow || !createLevel) {
    return;
  }

  try {
    await ensureAppearanceOptionsLoaded();
  } catch (err) {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load appearance options.', 'error');
    return;
  }

  const parentLevel = String(parentStatus.level || '').toUpperCase();
  const options = childLevelsByParentLevel[parentLevel] || [];
  if (options.length === 0) {
    return;
  }

  resetCreateDialog();
  createMode.value = 'child';
  createParent.value = `${parentStatus.first} ${parentStatus.last}`.trim();
  createBotTitle.textContent = 'Spawn Child Bot';
  createBotSubtitle.textContent = `Parent: ${createParent.value} (${parentLevel})`;
  createBotInfo.textContent = 'Child bot types are constrained by parent level policy.';

  createLevel.innerHTML = '';
  options.forEach((optionLevel) => {
    const option = document.createElement('option');
    option.value = optionLevel;
    option.textContent = optionLevel;
    createLevel.appendChild(option);
  });
  createLevelRow.classList.remove('hidden');

  createBotModal.classList.remove('hidden');
  createFirst?.focus();
};

const createCard = (status) => {
  const card = document.createElement('section');
  card.className = 'feature-card bg-dark-800/80 backdrop-blur rounded-xl p-5 flex flex-col gap-4';

  const first = status.first || '';
  const last = status.last || '';
  const level = status.level || 'UNKNOWN';
  const parent = String(status.parent || '').trim();
  const containers = Array.isArray(status.containerStatus) ? status.containerStatus : [];
  const normalizedLevel = String(level).toUpperCase();
  const canSpawnChild = normalizedLevel === 'GOVERNOR' || normalizedLevel === 'BUILDER';
  const preferredConsole = resolvePreferredConsole(containers, 'opensim-metaverse2mcp-');
  const preferredConsoleLink = preferredConsole
    ? buildConsoleIconLink(preferredConsole.name, preferredConsole.target, 'Open preferred console', 'text-neon-accent hover:text-neon-secondary')
    : '';
  const containerRows = renderContainerStatusRows(containers);

  card.innerHTML = `
    <div class="flex items-start justify-between gap-3">
      <div>
        <h2 class="text-xl font-semibold text-white">${first} ${last}</h2>
        ${parent ? `<p class="text-sm text-gray-400">Parent: ${parent}</p>` : ''}
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
    </div>

    <div class="grid grid-cols-2 gap-2 mt-auto">
      <button data-action="start" class="px-3 py-2 rounded-lg bg-emerald-600/20 border border-emerald-400/40 text-emerald-200 hover:bg-emerald-600/30 inline-flex items-center justify-center gap-2">${iconSpan('start', 'h-4 w-4 inline-block align-middle shrink-0')}<span>Start</span></button>
      <button data-action="stop" class="px-3 py-2 rounded-lg bg-amber-600/20 border border-amber-400/40 text-amber-200 hover:bg-amber-600/30 inline-flex items-center justify-center gap-2">${iconSpan('stop', 'h-4 w-4 inline-block align-middle shrink-0')}<span>Stop</span></button>
      <button data-action="restart" class="px-3 py-2 rounded-lg bg-sky-600/20 border border-sky-400/40 text-sky-200 hover:bg-sky-600/30 inline-flex items-center justify-center gap-2">${iconSpan('restart', 'h-4 w-4 inline-block align-middle shrink-0')}<span>Restart</span></button>
      <button data-action="delete" class="px-3 py-2 rounded-lg bg-rose-600/20 border border-rose-400/40 text-rose-200 hover:bg-rose-600/30 inline-flex items-center justify-center gap-2">${iconSpan('delete', 'h-4 w-4 inline-block align-middle shrink-0')}<span>Delete</span></button>
    </div>
    ${canSpawnChild ? `<button data-spawn-child class="mt-2 text-sm text-neon-accent hover:text-neon-secondary text-left inline-flex items-center gap-1">${iconSpan('plus', 'h-4 w-4 inline-block align-middle shrink-0')}<span>Spawn child bot</span></button>` : ''}
  `;

  card.querySelectorAll('button[data-action]').forEach((button) => {
    const action = button.getAttribute('data-action');
    const shouldDisable = !gridServiceAvailable && (action === 'start' || action === 'stop' || action === 'restart');
    toggleButtonDisabledVisual(button, shouldDisable);
    button.addEventListener('click', async () => {
      const action = button.getAttribute('data-action');
      if (!action) {
        return;
      }

      if (!gridServiceAvailable && (action === 'start' || action === 'stop' || action === 'restart')) {
        showToast(toastContainer, gridServiceUnavailableMessage, 'error');
        return;
      }

      button.disabled = true;
      try {
        await withWorkingOverlay(async () => {
          if (action === 'delete') {
            if (!window.confirm(`Delete bot ${first} ${last}?`)) {
              return;
            }
            await deleteBot(first, last);
            showToast(toastContainer, `Deleted bot ${first} ${last}.`, 'success');
          } else {
            await callAction(first, last, action);
            showToast(toastContainer, `Sent '${action}' for ${first} ${last}.`, 'success');
          }
          await loadBots();
        }, `${actionVerb(action)} bot ${first} ${last} ...`);
      } catch (err) {
        const recovered = await withWorkingOverlay(
          async () => waitForBotActionOutcome(first, last, action),
          `Verifying ${actionVerb(action).toLowerCase()} result for bot ${first} ${last} ...`
        );
        if (recovered) {
          await loadBots();
          if (action === 'delete') {
            showToast(toastContainer, `Deleted bot ${first} ${last}.`, 'success');
          } else {
            showToast(toastContainer, `Completed '${action}' for ${first} ${last}.`, 'success');
          }
          return;
        }
        showToast(toastContainer, err instanceof Error ? err.message : 'Request failed.', 'error');
      } finally {
        button.disabled = false;
      }
    });
  });

  const spawnChildButton = card.querySelector('button[data-spawn-child]');
  if (spawnChildButton) {
    toggleButtonDisabledVisual(spawnChildButton, !gridServiceAvailable);
    spawnChildButton.addEventListener('click', async () => {
      await openChildDialog(status);
    });
  }

  return card;
};

const loadBots = async () => {
  if (!botsGrid || !botsEmpty) {
    return;
  }

  botsGrid.innerHTML = '';
  botsEmpty.classList.add('hidden');

  try {
    gridServiceAvailable = await fetchGridServiceAvailability();
  } catch (err) {
    gridServiceAvailable = false;
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to query grid service availability.', 'error');
  }
  applyGridServiceUiState();

  const listResponse = await fetchWithTimeout('/api/bot');
  if (!listResponse.ok) {
    throw new Error(`Could not list bots (${listResponse.status}).`);
  }

  const botNames = await listResponse.json();
  if (!Array.isArray(botNames) || botNames.length === 0) {
    botsEmpty.classList.remove('hidden');
    return;
  }

  const statuses = await Promise.all(botNames.map(async (name) => {
    const parts = splitBotName(name);
    const response = await fetchWithTimeout(`/api/bot/${encodeURIComponent(parts.first)}/${encodeURIComponent(parts.last)}`);
    if (!response.ok) {
      throw new Error(`Could not load bot '${name}' (${response.status}).`);
    }
    return response.json();
  }));

  statuses.forEach((status) => {
    botsGrid.appendChild(createCard(status));
  });
};

const waitForBotVisible = async (first, last, maxWaitMs = REQUEST_RECOVERY_WINDOW_MS) => {
  const deadline = Date.now() + maxWaitMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetchWithTimeout(`/api/bot/${encodeURIComponent(first)}/${encodeURIComponent(last)}`);
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

const fetchBotStatusOrNull = async (first, last) => {
  try {
    const response = await fetchWithTimeout(`/api/bot/${encodeURIComponent(first)}/${encodeURIComponent(last)}`);
    if (!response.ok) {
      return null;
    }
    return response.json();
  } catch (_ignored) {
    return null;
  }
};

const waitForBotActionOutcome = async (first, last, action, maxWaitMs = REQUEST_RECOVERY_WINDOW_MS) => {
  const normalizedAction = String(action || '').toLowerCase();
  const deadline = Date.now() + maxWaitMs;
  const expectedName = `${first} ${last}`.trim();

  while (Date.now() < deadline) {
    if (normalizedAction === 'delete') {
      try {
        const listResponse = await fetchWithTimeout('/api/bot');
        if (listResponse.ok) {
          const names = await listResponse.json();
          if (Array.isArray(names) && !names.includes(expectedName)) {
            return true;
          }
        }
      } catch (_ignored) {
        // Keep polling through transient failures.
      }
      await sleep(REQUEST_RECOVERY_POLL_MS);
      continue;
    }

    const status = await fetchBotStatusOrNull(first, last);
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
  renderGenderOptions();

  openCreateBotButton?.addEventListener('click', async () => {
    await openGovernorDialog();
  });

  closeCreateBotButton?.addEventListener('click', () => {
    closeCreateDialog();
  });

  cancelCreateBotButton?.addEventListener('click', () => {
    closeCreateDialog();
  });

  createBotModal?.addEventListener('click', (event) => {
    if (event.target === createBotModal) {
      closeCreateDialog();
    }
  });

  createBotForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!createMode || !createParent || !createFirst || !createLast || !createEmail || !createAppearance || !submitCreateBot || !createBotError) {
      return;
    }

    if (!gridServiceAvailable) {
      createBotError.textContent = gridServiceUnavailableMessage;
      createBotError.classList.remove('hidden');
      return;
    }

    const first = createFirst.value.trim();
    const last = createLast.value.trim();
    const email = createEmail.value.trim();
    const appearance = createAppearance.value.trim();
    const selectedGender = createGenderGroup?.querySelector('input[name="create-gender"]:checked');
    const gender = selectedGender instanceof HTMLInputElement ? selectedGender.value : 'neutral';
    const level = createMode.value === 'child' ? String(createLevel?.value || '').toUpperCase() : 'GOVERNOR';

    if (!first || !last || !level) {
      createBotError.textContent = 'First name, last name and level are required.';
      createBotError.classList.remove('hidden');
      return;
    }

    createBotError.classList.add('hidden');
    submitCreateBot.disabled = true;

    try {
      await withWorkingOverlay(async () => {
        await createBot({
          first,
          last,
          level,
          parent: createParent.value,
          email,
          appearance,
          gender
        });
        closeCreateDialog();
        await loadBots();
        showToast(toastContainer, `Created bot ${first} ${last}.`, 'success');
      }, `Creating bot ${first} ${last} ...`);
    } catch (err) {
      const recovered = await withWorkingOverlay(
        async () => waitForBotVisible(first, last),
        `Verifying bot ${first} ${last} ...`
      );
      if (recovered) {
        closeCreateDialog();
        await loadBots();
        showToast(toastContainer, `Created bot ${first} ${last}.`, 'success');
        return;
      }

      createBotError.textContent = err instanceof Error ? err.message : 'Failed to create bot.';
      createBotError.classList.remove('hidden');
      showToast(toastContainer, createBotError.textContent, 'error');
    } finally {
      submitCreateBot.disabled = false;
    }
  });

  if (refreshButton) {
    refreshButton.addEventListener('click', async () => {
      try {
        await withWorkingOverlay(async () => {
          await loadBots();
        }, 'Refreshing bots ...');
        showToast(toastContainer, 'Bot list refreshed.', 'success');
      } catch (err) {
        showToast(toastContainer, err instanceof Error ? err.message : 'Refresh failed.', 'error');
      }
    });
  }

  loadBots().catch((err) => {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load bots.', 'error');
  });
});
