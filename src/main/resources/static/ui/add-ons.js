import { fetchWithTimeout, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const addOnsList = document.getElementById('add-ons-list');
const addOnsEmpty = document.getElementById('add-ons-empty');
const updateButton = document.getElementById('update-add-ons');
const toastContainer = document.getElementById('toast-container');

const normalizedText = (value, fallback = '') => {
  const text = String(value || '').trim();
  return text || fallback;
};

const callList = async () => {
  const response = await fetchWithTimeout('/api/add-ons');
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not load add-ons (${response.status}).`);
  }
  return response.json();
};

const callReload = async () => {
  const response = await fetchWithTimeout('/api/add-ons/reload', { method: 'POST' });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not update add-ons (${response.status}).`);
  }
  return response.json();
};

const callSetEnabled = async (name, enabled) => {
  const payload = new URLSearchParams();
  payload.set('name', name);
  payload.set('enabled', String(enabled));

  const response = await fetchWithTimeout('/api/add-ons', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not update add-on '${name}' (${response.status}).`);
  }

  return response.json();
};

const createToggle = (name, enabled) => {
  const label = document.createElement('label');
  label.className = 'relative inline-flex items-center cursor-pointer';

  const input = document.createElement('input');
  input.type = 'checkbox';
  input.className = 'peer sr-only';
  input.checked = !!enabled;
  input.setAttribute('aria-label', `Toggle ${name}`);

  const track = document.createElement('span');
  track.className = 'h-6 w-11 rounded-full border border-neon-primary/35 bg-dark-700 transition-colors peer-checked:bg-neon-primary/45 peer-focus:ring-2 peer-focus:ring-neon-primary/40';

  const knob = document.createElement('span');
  knob.className = 'pointer-events-none absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-gray-100 shadow-sm transition-transform peer-checked:translate-x-5';

  label.appendChild(input);
  label.appendChild(track);
  label.appendChild(knob);

  input.addEventListener('change', async () => {
    const nextValue = input.checked;
    input.disabled = true;
    try {
      await withWorkingOverlay(async () => {
        await callSetEnabled(name, nextValue);
      }, `${nextValue ? 'Enabling' : 'Disabling'} add-on ${name} ...`);
      showToast(toastContainer, `${nextValue ? 'Enabled' : 'Disabled'} add-on ${name}.`, 'success');
    } catch (err) {
      input.checked = !nextValue;
      showToast(toastContainer, err instanceof Error ? err.message : 'Could not update add-on.', 'error');
    } finally {
      input.disabled = false;
    }
  });

  return label;
};

const createIcon = (addOnName, title) => {
  const icon = document.createElement('img');
  icon.className = 'h-8 w-8 rounded-md object-cover border border-neon-primary/20 bg-dark-700/70';
  icon.width = 32;
  icon.height = 32;
  icon.alt = `${title} icon`;
  icon.src = `/api/add-ons/icon?name=${encodeURIComponent(addOnName)}`;
  icon.addEventListener('error', () => {
    icon.src = '/ui/opensim-stack.png';
  }, { once: true });
  return icon;
};

const renderRow = (addOn) => {
  const manifest = addOn?.manifest || {};
  const addOnName = normalizedText(manifest.name, '');
  const name = normalizedText(manifest.name, 'Unnamed add-on');

  const row = document.createElement('div');
  row.className = 'grid grid-cols-[minmax(0,1fr)_auto] gap-4 px-5 py-3 items-center';

  const left = document.createElement('div');
  left.className = 'min-w-0 flex items-center gap-3';

  left.appendChild(createIcon(addOnName || name, name));

  const details = document.createElement('div');
  details.className = 'min-w-0';

  const title = document.createElement('div');
  title.className = 'font-medium text-gray-100 truncate';
  title.textContent = name;
  title.title = name;

  const description = normalizedText(manifest.description);
  if (description) {
    const subtitle = document.createElement('div');
    subtitle.className = 'text-sm text-gray-400 truncate';
    subtitle.textContent = description;
    subtitle.title = description;
    details.appendChild(title);
    details.appendChild(subtitle);
  } else {
    details.appendChild(title);
  }

  left.appendChild(details);

  const toggleHost = document.createElement('div');
  toggleHost.className = 'flex items-center';
  toggleHost.appendChild(createToggle(addOnName || name, !!addOn?.enabled));

  row.appendChild(left);
  row.appendChild(toggleHost);

  return row;
};

const loadAddOns = async () => {
  if (!addOnsList || !addOnsEmpty) {
    return;
  }

  addOnsList.innerHTML = '';
  addOnsEmpty.classList.add('hidden');

  const addOns = await callList();
  if (!Array.isArray(addOns) || addOns.length === 0) {
    addOnsEmpty.classList.remove('hidden');
    return;
  }

  addOns.forEach((addOn) => {
    addOnsList.appendChild(renderRow(addOn));
  });

  if (!addOnsList.children.length) {
    addOnsEmpty.classList.remove('hidden');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  updateButton?.addEventListener('click', async () => {
    try {
      await withWorkingOverlay(async () => {
        await callReload();
        await loadAddOns();
      }, 'Updating add-ons ...');
      showToast(toastContainer, 'Add-ons updated.', 'success');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Update failed.', 'error');
    }
  });

  loadAddOns().catch((err) => {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load add-ons.', 'error');
  });
});