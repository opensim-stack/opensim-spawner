import { actionIconSvg, fetchWithTimeout, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const networkList = document.getElementById('network-list');
const networkEmpty = document.getElementById('network-empty');
const locationBadge = document.getElementById('network-location');
const refreshButton = document.getElementById('refresh-network');
const toastContainer = document.getElementById('toast-container');

const locationBadgeClassByType = {
  LOCALHOST: 'border-rose-400/40 bg-rose-500/10 text-rose-200',
  LAN: 'border-amber-400/40 bg-amber-500/10 text-amber-200',
  WAN: 'border-emerald-400/40 bg-emerald-500/10 text-emerald-200'
};

const normalizeProtocol = (protocol) => {
  const value = String(protocol || '').trim().toLowerCase();
  return value === 'udp' ? 'udp' : 'tcp';
};

const collapseConsecutive = (values) => {
  const sorted = [...new Set(values)]
    .map((value) => Number(value))
    .filter((value) => Number.isInteger(value) && value > 0)
    .sort((a, b) => a - b);

  if (!sorted.length) {
    return [];
  }

  const ranges = [];
  let start = sorted[0];
  let end = sorted[0];

  for (let index = 1; index < sorted.length; index += 1) {
    const current = sorted[index];
    if (current === end + 1) {
      end = current;
      continue;
    }
    ranges.push(start === end ? `${start}` : `${start}-${end}`);
    start = current;
    end = current;
  }

  ranges.push(start === end ? `${start}` : `${start}-${end}`);
  return ranges;
};

const groupedPortLabels = (ports) => {
  const all = Array.isArray(ports) ? ports : [];
  const byProtocol = { tcp: [], udp: [] };

  all.forEach((port) => {
    const protocol = normalizeProtocol(port?.protocol);
    byProtocol[protocol].push(port?.port);
  });

  return ['tcp', 'udp'].flatMap((protocol) => collapseConsecutive(byProtocol[protocol])
    .map((range) => ({
      label: `${range}/${protocol}`,
      protocol
    })));
};

const createPortBadge = ({ label, protocol }) => {
  const badge = document.createElement('span');
  const protocolClass = protocol === 'udp'
    ? 'border-sky-400/40 bg-sky-500/10 text-sky-200'
    : 'border-emerald-400/40 bg-emerald-500/10 text-emerald-200';
  badge.className = `inline-flex items-center text-xs rounded-full border px-2 py-1 font-mono ${protocolClass}`;
  badge.textContent = label;
  return badge;
};

const createTestButton = (containerName) => {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'inline-flex items-center justify-center h-9 w-9 rounded-lg border transition-colors text-sky-200 border-sky-400/40 hover:bg-sky-600/20';
  button.setAttribute('title', 'Test');
  button.setAttribute('aria-label', `Test network for ${containerName}`);
  button.innerHTML = `<span class="h-4 w-4 shrink-0">${actionIconSvg('select')}</span>`;

  button.addEventListener('click', async () => {
    button.disabled = true;
    try {
      await withWorkingOverlay(
        async () => runExternalTest(containerName),
        `Testing external network for container ${containerName} ...`
      );
      showToast(toastContainer, `External network test triggered for ${containerName}.`, 'success');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'External network test failed.', 'error');
    } finally {
      button.disabled = false;
    }
  });

  return button;
};

const renderNetworkRow = (container) => {
  const row = document.createElement('div');
  row.className = 'grid grid-cols-[minmax(0,1fr)_minmax(0,2fr)_auto] gap-4 px-5 py-3 items-center';

  const name = document.createElement('div');
  name.className = 'font-mono text-sm text-gray-100 truncate';
  name.textContent = String(container?.containerName || '').trim();
  name.title = name.textContent;

  const badges = document.createElement('div');
  badges.className = 'flex flex-wrap items-center gap-2';

  groupedPortLabels(container?.ports).forEach((portMeta) => {
    badges.appendChild(createPortBadge(portMeta));
  });

  const actions = document.createElement('div');
  actions.className = 'flex items-center gap-2';
  actions.appendChild(createTestButton(name.textContent));

  row.appendChild(name);
  row.appendChild(badges);
  row.appendChild(actions);
  return row;
};

const responseMessage = async (response) => {
  try {
    const payload = await response.json();
    const error = String(payload?.error || payload?.message || '').trim();
    if (error) {
      return error;
    }
  } catch (_ignored) {
    // Fallback to plain text body when response is not JSON.
  }

  const text = String(await response.text()).trim();
  return text || `Request failed (${response.status}).`;
};

const loadNetworkStatus = async () => {
  if (!locationBadge) {
    return;
  }

  const response = await fetchWithTimeout('/api/network/status');
  if (!response.ok) {
    throw new Error(await responseMessage(response));
  }

  const payload = await response.json();
  const type = String(payload?.type || 'LOCALHOST').trim().toUpperCase();
  const ipAddress = String(payload?.ipAddress || '127.0.0.1').trim();
  const badgeClass = locationBadgeClassByType[type] || locationBadgeClassByType.LOCALHOST;

  locationBadge.className = `inline-flex items-center gap-2 text-xs rounded-full border px-3 py-1.5 ${badgeClass}`;
  locationBadge.textContent = `${type}: ${ipAddress}`;
};

const loadNetworkPorts = async () => {
  if (!networkList || !networkEmpty) {
    return;
  }

  networkList.innerHTML = '';
  networkEmpty.classList.add('hidden');

  const response = await fetchWithTimeout('/api/network');
  if (!response.ok) {
    throw new Error(await responseMessage(response));
  }

  const containers = await response.json();
  const all = Array.isArray(containers) ? containers : [];

  all.forEach((container) => {
    const containerName = String(container?.containerName || '').trim();
    if (!containerName) {
      return;
    }
    const labels = groupedPortLabels(container?.ports);
    if (!labels.length) {
      return;
    }
    networkList.appendChild(renderNetworkRow(container));
  });

  if (!networkList.children.length) {
    networkEmpty.classList.remove('hidden');
  }
};

const refreshNetworkPage = async () => {
  await Promise.all([loadNetworkStatus(), loadNetworkPorts()]);
};

const runExternalTest = async (containerName) => {
  const requestContainer = String(containerName || '').trim();
  const endpoint = requestContainer
    ? `/api/network/test?container=${encodeURIComponent(requestContainer)}`
    : '/api/network/test';

  const response = await fetchWithTimeout(endpoint, { method: 'POST' });
  if (!response.ok) {
    throw new Error(await responseMessage(response));
  }
};

document.addEventListener('DOMContentLoaded', () => {
  refreshButton?.addEventListener('click', async () => {
    try {
      await withWorkingOverlay(refreshNetworkPage, 'Refreshing network ...');
      showToast(toastContainer, 'Network list refreshed.', 'success');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Refresh failed.', 'error');
    }
  });

  refreshNetworkPage().catch((err) => {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load network view.', 'error');
  });
});
