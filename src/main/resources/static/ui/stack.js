import { actionIconSvg, createContainerActionsMenu, fetchWithTimeout, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const stackList = document.getElementById('stack-list');
const stackEmpty = document.getElementById('stack-empty');
const refreshButton = document.getElementById('refresh-stack');
const updateAllButton = document.getElementById('update-all');
const toastContainer = document.getElementById('toast-container');
const REQUEST_RECOVERY_WINDOW_MS = 180000;
const REQUEST_RECOVERY_POLL_MS = 3000;
const SELF_UPDATE_DOWN_WAIT_MS = 120000;
const SELF_UPDATE_AUTH_WAIT_MS = 300000;
const SELF_UPDATE_POLL_MS = 2000;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const actionVerb = (action) => {
  switch (String(action || '').toLowerCase()) {
    case 'start':
      return 'Starting';
    case 'stop':
      return 'Stopping';
    case 'restart':
      return 'Restarting';
    case 'update':
      return 'Updating';
    default:
      return 'Updating';
  }
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

const callUpdateAll = async () => {
  const response = await fetchWithTimeout('/api/stack/update-all', { method: 'POST' });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not update all stack containers (${response.status}).`);
  }
  return response.json();
};

const isConnectionFailure = (error) => {
  if (!error) {
    return false;
  }
  if (error instanceof TypeError) {
    return true;
  }
  const message = String(error.message || '').toLowerCase();
  return message.includes('failed to fetch') || message.includes('networkerror') || message.includes('network error');
};

const waitForSelfUpdateReauth = async () => {
  const shutdownDeadline = Date.now() + SELF_UPDATE_DOWN_WAIT_MS;
  let sawShutdown = false;

  while (Date.now() < shutdownDeadline) {
    try {
      await fetchWithTimeout('/api/stack', { cache: 'no-store' }, 5000);
    } catch (error) {
      if (isConnectionFailure(error)) {
        sawShutdown = true;
        break;
      }
    }
    await sleep(SELF_UPDATE_POLL_MS);
  }

  if (!sawShutdown) {
    throw new Error('Self-update did not appear to stop the spawner in time.');
  }

  const authDeadline = Date.now() + SELF_UPDATE_AUTH_WAIT_MS;
  while (Date.now() < authDeadline) {
    try {
      const response = await fetchWithTimeout('/api/stack', { cache: 'no-store' }, 5000);
      if (response.status === 401) {
        return;
      }
    } catch (_ignored) {
      // Still offline while the replacement instance is starting.
    }
    await sleep(SELF_UPDATE_POLL_MS);
  }

  throw new Error('Self-update completed but authentication reset was not detected in time.');
};

const renderRow = (container) => {
  const row = document.createElement('div');
  row.className = 'grid grid-cols-[auto_minmax(0,1fr)_auto] gap-4 px-5 py-3 items-center';

  const updates = document.createElement('div');
  updates.className = 'flex w-8 items-center justify-center';

  if (container.updateAvailable) {
    const updateMarker = document.createElement('span');
    updateMarker.className = 'inline-flex items-center justify-center h-6 w-6 rounded-full border border-emerald-400/40 bg-emerald-500/10 text-emerald-200 update-pulse';
    updateMarker.title = 'Update available';
    updateMarker.setAttribute('aria-label', 'Update available');
    updateMarker.innerHTML = `<span class="h-3.5 w-3.5 shrink-0">${actionIconSvg('update')}</span>`;
    updates.appendChild(updateMarker);
  }

  const left = document.createElement('div');
  left.className = 'min-w-0';

  const identity = document.createElement('div');
  identity.className = 'min-w-0';

  const name = document.createElement('div');
  name.className = 'font-mono text-sm text-gray-100 truncate';
  name.textContent = container.containerName;
  name.title = container.containerName;

  const image = document.createElement('div');
  if (container.updateAvailable) {
    image.className = 'font-mono text-xs text-emerald-200 truncate';
    image.textContent = `Update available to ${container.image || 'unknown image'}`;
    image.title = image.textContent;
  } else {
    image.className = 'font-mono text-xs text-gray-400 truncate';
    image.textContent = container.image || 'unknown image';
    image.title = container.image || 'unknown image';
  }

  identity.appendChild(name);
  identity.appendChild(image);
  left.appendChild(identity);

  const actions = document.createElement('div');
  actions.className = 'flex items-center justify-end';

  const menu = createContainerActionsMenu({
    containerName: container.containerName,
    status: container.status,
    running: container.running,
    updateAvailable: container.updateAvailable,
    onAction: async (action, actionButton) => {
      if (action === 'update' && !window.confirm(`Update container '${container.containerName}' now?`)) {
        return;
      }
      actionButton.disabled = true;
      try {
        await withWorkingOverlay(async () => {
          const result = await callAction(container.containerName, action);
          if (result?.selfUpdate) {
            showToast(toastContainer, 'Spawner self-update started. Waiting for restart ...', 'info');
            await waitForSelfUpdateReauth();
            window.location.assign('/ui/stack.html');
            return;
          }
          showToast(toastContainer, `Container ${result.container}: ${result.action} requested (${result.status}).`, 'success');
          await loadStack();
        }, `${actionVerb(action)} container ${container.containerName} ...`);
      } catch (err) {
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
        actionButton.disabled = false;
      }
    }
  });
  actions.appendChild(menu);

  row.appendChild(updates);
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

  containers.forEach((container) => {
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
          if (normalizedAction === 'update' && !match.updateAvailable) {
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

  updateAllButton?.addEventListener('click', async () => {
    if (!window.confirm('Update all containers with available updates? This runs sequentially and can take a while.')) {
      return;
    }
    try {
      await withWorkingOverlay(async () => {
        const response = await callUpdateAll();
        if (response?.selfUpdate) {
          showToast(toastContainer, 'Spawner self-update started. Waiting for restart ...', 'info');
          await waitForSelfUpdateReauth();
          window.location.assign('/ui/stack.html');
          return;
        }
        await loadStack();
        showToast(toastContainer, `Updated ${response.count || 0} container(s).`, 'success');
      }, 'Updating containers sequentially ...');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Update all failed.', 'error');
    }
  });

  loadStack().catch((err) => {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load stack containers.', 'error');
  });
});
