import { actionIconSvg, consoleTargetForContainer, fetchWithTimeout, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const stackList = document.getElementById('stack-list');
const stackEmpty = document.getElementById('stack-empty');
const refreshButton = document.getElementById('refresh-stack');
const showEntireStackCheckbox = document.getElementById('show-entire-stack');
const toastContainer = document.getElementById('toast-container');
const REQUEST_RECOVERY_WINDOW_MS = 180000;
const REQUEST_RECOVERY_POLL_MS = 3000;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const buttonClassesByAction = {
  start: 'text-emerald-200 border-emerald-400/40 hover:bg-emerald-600/20',
  stop: 'text-amber-200 border-amber-400/40 hover:bg-amber-600/20',
  restart: 'text-sky-200 border-sky-400/40 hover:bg-sky-600/20',
  console: 'text-neon-accent border-neon-primary/40 hover:bg-neon-primary/10'
};

const actionVerb = (action) => {
  switch (String(action || '').toLowerCase()) {
    case 'start':
      return 'Starting';
    case 'stop':
      return 'Stopping';
    case 'restart':
      return 'Restarting';
    default:
      return 'Updating';
  }
};

const splitBotName = (displayName) => {
  const trimmed = String(displayName || '').trim();
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

const fetchManagedContainerNames = async () => {
  const names = new Set();

  const simulatorListResponse = await fetchWithTimeout('/api/simulator');
  if (simulatorListResponse.ok) {
    const simulatorNames = await simulatorListResponse.json();
    if (Array.isArray(simulatorNames)) {
      const statuses = await Promise.all(simulatorNames.map(async (name) => {
        const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(name)}`);
        return response.ok ? response.json() : null;
      }));
      statuses.forEach((status) => {
        const containers = Array.isArray(status?.containerStatus) ? status.containerStatus : [];
        containers.forEach((container) => {
          const containerName = String(container?.containerName || '').trim();
          if (containerName) {
            names.add(containerName);
          }
        });
      });
    }
  }

  const botListResponse = await fetchWithTimeout('/api/bot');
  if (botListResponse.ok) {
    const botNames = await botListResponse.json();
    if (Array.isArray(botNames)) {
      const statuses = await Promise.all(botNames.map(async (displayName) => {
        const { first, last } = splitBotName(displayName);
        const response = await fetchWithTimeout(`/api/bot/${encodeURIComponent(first)}/${encodeURIComponent(last)}`);
        return response.ok ? response.json() : null;
      }));
      statuses.forEach((status) => {
        const containers = Array.isArray(status?.containerStatus) ? status.containerStatus : [];
        containers.forEach((container) => {
          const containerName = String(container?.containerName || '').trim();
          if (containerName) {
            names.add(containerName);
          }
        });
      });
    }
  }

  return names;
};

const callAction = async (containerName, action) => {
  const payload = new URLSearchParams();
  payload.set('container', containerName);
  payload.set('action', action);

  const response = await fetchWithTimeout('/api/stack', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not ${action} '${containerName}' (${response.status}).`);
  }

  return response.json();
};

const actionButton = (action, containerName) => {
  const button = document.createElement(action === 'console' ? 'a' : 'button');
  const className = buttonClassesByAction[action] || '';
  button.className = `inline-flex items-center justify-center h-9 w-9 rounded-lg border transition-colors ${className}`;
  button.dataset.action = action;
  button.setAttribute('title', action.charAt(0).toUpperCase() + action.slice(1));
  button.setAttribute('aria-label', `${action} ${containerName}`);
  button.innerHTML = `<span class="h-4 w-4 shrink-0">${actionIconSvg(action)}</span>`;

  if (action === 'console') {
    button.href = `/ui/console.html?container=${encodeURIComponent(containerName)}`;
    button.target = consoleTargetForContainer(containerName);
    button.rel = 'noopener';
  }

  return button;
};

const stateBadge = (status, running) => {
  const badge = document.createElement('span');
  const active = !!running;
  badge.className = `inline-flex items-center gap-2 text-xs rounded-full border px-2 py-1 ${active
    ? 'text-emerald-200 border-emerald-400/40 bg-emerald-500/10'
    : 'text-rose-200 border-rose-400/40 bg-rose-500/10'}`;
  const dot = document.createElement('span');
  dot.className = `w-2 h-2 rounded-full ${active ? 'bg-emerald-400' : 'bg-rose-400'}`;
  badge.appendChild(dot);
  badge.appendChild(document.createTextNode(status || 'unknown'));
  return badge;
};

const renderRow = (container) => {
  const row = document.createElement('div');
  row.className = 'grid grid-cols-[minmax(0,1fr)_auto] gap-4 px-5 py-3 items-center';

  const left = document.createElement('div');
  left.className = 'min-w-0 flex items-center gap-3';

  const name = document.createElement('div');
  name.className = 'font-mono text-sm text-gray-100 truncate';
  name.textContent = container.containerName;
  name.title = container.containerName;

  left.appendChild(name);
  left.appendChild(stateBadge(container.status, container.running));

  const actions = document.createElement('div');
  actions.className = 'flex items-center gap-2';

  const startButton = actionButton('start', container.containerName);
  const stopButton = actionButton('stop', container.containerName);
  const restartButton = actionButton('restart', container.containerName);
  const consoleButton = actionButton('console', container.containerName);

  [startButton, stopButton, restartButton].forEach((button) => {
    button.addEventListener('click', async () => {
      button.disabled = true;
      try {
        await withWorkingOverlay(async () => {
          const result = await callAction(container.containerName, button.dataset.action || '');
          showToast(toastContainer, `Container ${result.container}: ${result.action} requested (${result.status}).`, 'success');
          await loadStack();
        }, `${actionVerb(button.dataset.action)} container ${container.containerName} ...`);
      } catch (err) {
        const action = button.dataset.action || '';
        const recovered = await withWorkingOverlay(
          async () => waitForStackActionOutcome(container.containerName, action),
          `Verifying ${actionVerb(action).toLowerCase()} result for container ${container.containerName} ...`
        );
        if (recovered) {
          await loadStack();
          showToast(toastContainer, `Completed '${action}' for ${container.containerName}.`, 'success');
          return;
        }
        showToast(toastContainer, err instanceof Error ? err.message : 'Action failed.', 'error');
      } finally {
        button.disabled = false;
      }
    });
  });

  actions.appendChild(startButton);
  actions.appendChild(stopButton);
  actions.appendChild(restartButton);
  actions.appendChild(consoleButton);

  row.appendChild(left);
  row.appendChild(actions);
  return row;
};

const loadStack = async () => {
  if (!stackList || !stackEmpty) {
    return;
  }

  stackList.innerHTML = '';
  stackEmpty.classList.add('hidden');

  const response = await fetchWithTimeout('/api/stack');
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not load stack containers (${response.status}).`);
  }

  const containers = await response.json();
  if (!Array.isArray(containers) || containers.length === 0) {
    stackEmpty.classList.remove('hidden');
    return;
  }

  let visibleContainers = containers;
  if (!showEntireStackCheckbox?.checked) {
    try {
      const managed = await fetchManagedContainerNames();
      visibleContainers = containers.filter((container) => !managed.has(String(container?.containerName || '').trim()));
    } catch (_ignored) {
      // If managed-group discovery fails, keep default list rather than hiding everything.
    }
  }

  visibleContainers.forEach((container) => {
    if (container && typeof container.containerName === 'string' && container.containerName.trim()) {
      stackList.appendChild(renderRow(container));
    }
  });

  if (!stackList.children.length) {
    stackEmpty.classList.remove('hidden');
  }
};

const waitForStackActionOutcome = async (containerName, action, maxWaitMs = REQUEST_RECOVERY_WINDOW_MS) => {
  const normalizedAction = String(action || '').toLowerCase();
  const deadline = Date.now() + maxWaitMs;

  while (Date.now() < deadline) {
    try {
      const response = await fetchWithTimeout('/api/stack');
      if (response.ok) {
        const containers = await response.json();
        const match = Array.isArray(containers)
          ? containers.find((container) => container?.containerName === containerName)
          : null;
        if (match) {
          if ((normalizedAction === 'start' || normalizedAction === 'restart') && !!match.running) {
            return true;
          }
          if (normalizedAction === 'stop' && !match.running) {
            return true;
          }
        }
      }
    } catch (_ignored) {
      // Keep polling through transient failures.
    }

    await sleep(REQUEST_RECOVERY_POLL_MS);
  }

  return false;
};

document.addEventListener('DOMContentLoaded', () => {
  refreshButton?.addEventListener('click', async () => {
    try {
      await withWorkingOverlay(async () => {
        await loadStack();
      }, 'Refreshing stack ...');
      showToast(toastContainer, 'Stack list refreshed.', 'success');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Refresh failed.', 'error');
    }
  });

  showEntireStackCheckbox?.addEventListener('change', async () => {
    try {
      await withWorkingOverlay(async () => {
        await loadStack();
      }, 'Refreshing stack ...');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Refresh failed.', 'error');
    }
  });

  loadStack().catch((err) => {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load stack containers.', 'error');
  });
});
