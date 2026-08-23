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
const createModel = document.getElementById('create-model');
const submitCreateBot = document.getElementById('submit-create-bot');
const toastContainer = document.getElementById('toast-container');

const childLevelsByParentLevel = {
  GOVERNOR: ['BUILDER', 'ACTOR'],
  BUILDER: ['ACTOR']
};

const showToast = (message, tone = 'info') => {
  if (!toastContainer || !message) {
    return;
  }

  const toneClass = tone === 'error'
    ? 'border-rose-400/60 text-rose-100 bg-rose-900/45'
    : tone === 'success'
      ? 'border-emerald-400/60 text-emerald-100 bg-emerald-900/35'
      : 'border-neon-primary/50 text-gray-100 bg-dark-800/90';

  const toast = document.createElement('div');
  toast.className = `pointer-events-auto border ${toneClass} backdrop-blur rounded-lg px-4 py-3 shadow-lg transition-all duration-300 opacity-0 translate-y-2`;
  toast.textContent = message;

  toastContainer.appendChild(toast);
  requestAnimationFrame(() => {
    toast.classList.remove('opacity-0', 'translate-y-2');
  });

  const removeToast = () => {
    toast.classList.add('opacity-0', 'translate-y-2');
    setTimeout(() => {
      toast.remove();
    }, 260);
  };

  setTimeout(removeToast, 3200);
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

const consoleTargetForContainer = (containerIdOrName) => {
  const raw = String(containerIdOrName || '').trim();
  if (!raw) {
    return 'console-generic';
  }
  const safe = raw.replace(/[^a-zA-Z0-9_-]+/g, '-');
  return `console-${safe}`;
};

const callAction = async (first, last, action) => {
  const payload = new URLSearchParams();
  payload.set('action', action);
  const response = await fetch(`/api/bot/${encodeURIComponent(first)}/${encodeURIComponent(last)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });
  if (!response.ok) {
    throw new Error(`Action '${action}' failed (${response.status}).`);
  }
};

const deleteBot = async (first, last) => {
  const response = await fetch(`/api/bot/${encodeURIComponent(first)}/${encodeURIComponent(last)}`, {
    method: 'DELETE'
  });
  if (!response.ok) {
    throw new Error(`Delete failed (${response.status}).`);
  }
};

const createBot = async ({ first, last, level, parent, email, model }) => {
  const payload = new URLSearchParams();
  payload.set('level', level);
  payload.set('parent', parent || '');
  if (email) {
    payload.set('email', email);
  }
  if (model) {
    payload.set('model', model);
  }

  const response = await fetch(`/api/bot/${encodeURIComponent(first)}/${encodeURIComponent(last)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Create bot failed (${response.status}).`);
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
};

const closeCreateDialog = () => {
  if (createBotModal) {
    createBotModal.classList.add('hidden');
  }
};

const openGovernorDialog = () => {
  if (!createBotModal || !createMode || !createParent || !createBotTitle || !createBotSubtitle || !createBotInfo || !createLevelRow) {
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

const openChildDialog = (parentStatus) => {
  if (!createBotModal || !createMode || !createParent || !createBotTitle || !createBotSubtitle || !createBotInfo || !createLevelRow || !createLevel) {
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
  const parent = status.parent || '-';
  const containers = Array.isArray(status.containerStatus) ? status.containerStatus : [];
  const normalizedLevel = String(level).toUpperCase();
  const canSpawnChild = normalizedLevel === 'GOVERNOR' || normalizedLevel === 'BUILDER';
  const preferredConsoleContainer = containers.find((container) =>
    String(container?.containerName || '').startsWith('opensim-metaverse2mcp-'))
    || containers.find((container) => String(container?.containerName || '').length > 0)
    || null;
  const preferredConsoleName = preferredConsoleContainer
    ? (preferredConsoleContainer.containerName || preferredConsoleContainer.containerId || '')
    : '';
  const preferredConsoleUrl = preferredConsoleName
    ? `/ui/console.html?container=${encodeURIComponent(preferredConsoleName)}`
    : '';
  const preferredConsoleTarget = preferredConsoleContainer
    ? consoleTargetForContainer(preferredConsoleContainer.containerId || preferredConsoleName)
    : 'console-generic';

  const containerRows = containers
    .map((container) => {
      const running = !!container.running;
      const dotColor = running ? 'bg-emerald-400' : 'bg-rose-400';
      const text = running ? 'Running' : (container.status || 'Stopped');
      const containerName = container.containerName || container.containerId;
      const containerParam = encodeURIComponent(containerName || '');
      const containerTarget = consoleTargetForContainer(container.containerId || containerName);
      return `
        <div class="flex items-center justify-between text-sm gap-3">
          <div class="min-w-0">
            <div class="text-gray-300 truncate" title="${containerName}">${containerName}</div>
            <a href="/ui/console.html?container=${containerParam}" target="${containerTarget}" rel="noopener" class="text-xs text-neon-accent hover:text-neon-secondary">Open console</a>
          </div>
          <span class="inline-flex items-center gap-2 text-gray-200 whitespace-nowrap"><span class="w-2 h-2 rounded-full ${dotColor}"></span>${text}</span>
        </div>
      `;
    })
    .join('');

  card.innerHTML = `
    <div class="flex items-start justify-between gap-3">
      <div>
        <h2 class="text-xl font-semibold text-white">${first} ${last}</h2>
        <p class="text-sm text-gray-400">Parent: ${parent}</p>
      </div>
      <div class="flex items-start gap-2">
        ${preferredConsoleUrl
      ? `<a href="${preferredConsoleUrl}" target="${preferredConsoleTarget}" rel="noopener" class="px-2 py-1 rounded-md text-xs border border-neon-primary/40 text-neon-accent hover:text-neon-secondary hover:bg-neon-primary/10">Open Console</a>`
      : ''}
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
      <button data-action="start" class="px-3 py-2 rounded-lg bg-emerald-600/20 border border-emerald-400/40 text-emerald-200 hover:bg-emerald-600/30">Start</button>
      <button data-action="stop" class="px-3 py-2 rounded-lg bg-amber-600/20 border border-amber-400/40 text-amber-200 hover:bg-amber-600/30">Stop</button>
      <button data-action="restart" class="px-3 py-2 rounded-lg bg-sky-600/20 border border-sky-400/40 text-sky-200 hover:bg-sky-600/30">Restart</button>
      <button data-action="delete" class="px-3 py-2 rounded-lg bg-rose-600/20 border border-rose-400/40 text-rose-200 hover:bg-rose-600/30">Delete</button>
    </div>
    ${canSpawnChild ? '<button data-spawn-child class="mt-2 text-sm text-neon-accent hover:text-neon-secondary text-left">Spawn child bot</button>' : ''}
  `;

  card.querySelectorAll('button[data-action]').forEach((button) => {
    button.addEventListener('click', async () => {
      const action = button.getAttribute('data-action');
      if (!action) {
        return;
      }

      button.disabled = true;
      try {
        if (action === 'delete') {
          if (!window.confirm(`Delete bot ${first} ${last}?`)) {
            return;
          }
          await deleteBot(first, last);
          showToast(`Deleted bot ${first} ${last}.`, 'success');
        } else {
          await callAction(first, last, action);
          showToast(`Sent '${action}' for ${first} ${last}.`, 'success');
        }
        await loadBots();
      } catch (err) {
        showToast(err instanceof Error ? err.message : 'Request failed.', 'error');
      } finally {
        button.disabled = false;
      }
    });
  });

  const spawnChildButton = card.querySelector('button[data-spawn-child]');
  if (spawnChildButton) {
    spawnChildButton.addEventListener('click', () => {
      openChildDialog(status);
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

  const listResponse = await fetch('/api/bot');
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
    const response = await fetch(`/api/bot/${encodeURIComponent(parts.first)}/${encodeURIComponent(parts.last)}`);
    if (!response.ok) {
      throw new Error(`Could not load bot '${name}' (${response.status}).`);
    }
    return response.json();
  }));

  statuses.forEach((status) => {
    botsGrid.appendChild(createCard(status));
  });
};

document.addEventListener('DOMContentLoaded', () => {
  openCreateBotButton?.addEventListener('click', () => {
    openGovernorDialog();
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
    if (!createMode || !createParent || !createFirst || !createLast || !createEmail || !createModel || !submitCreateBot || !createBotError) {
      return;
    }

    const first = createFirst.value.trim();
    const last = createLast.value.trim();
    const email = createEmail.value.trim();
    const model = createModel.value.trim();
    const level = createMode.value === 'child' ? String(createLevel?.value || '').toUpperCase() : 'GOVERNOR';

    if (!first || !last || !level) {
      createBotError.textContent = 'First name, last name and level are required.';
      createBotError.classList.remove('hidden');
      return;
    }

    createBotError.classList.add('hidden');
    submitCreateBot.disabled = true;

    try {
      await createBot({
        first,
        last,
        level,
        parent: createParent.value,
        email,
        model
      });
      closeCreateDialog();
      await loadBots();
      showToast(`Created bot ${first} ${last}.`, 'success');
    } catch (err) {
      createBotError.textContent = err instanceof Error ? err.message : 'Failed to create bot.';
      createBotError.classList.remove('hidden');
      showToast(createBotError.textContent, 'error');
    } finally {
      submitCreateBot.disabled = false;
    }
  });

  if (refreshButton) {
    refreshButton.addEventListener('click', async () => {
      try {
        await loadBots();
        showToast('Bot list refreshed.', 'success');
      } catch (err) {
        showToast(err instanceof Error ? err.message : 'Refresh failed.', 'error');
      }
    });
  }

  loadBots().catch((err) => {
    showToast(err instanceof Error ? err.message : 'Failed to load bots.', 'error');
  });
});
