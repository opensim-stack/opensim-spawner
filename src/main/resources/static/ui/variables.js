import { collectVariableValues, renderVariableEditor } from '/ui/component-variables.js';
import { fetchWithTimeout, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const subtitle = document.getElementById('variables-subtitle');
const form = document.getElementById('variables-form');
const list = document.getElementById('variables-list');
const errorMessage = document.getElementById('variables-error');
const saveButton = document.getElementById('variables-save');
const backLink = document.getElementById('variables-back-link');
const toastContainer = document.getElementById('toast-container');

let currentState = null;

const query = new URLSearchParams(window.location.search);
const currentType = String(query.get('type') || '').trim().toUpperCase();
const currentName = String(query.get('name') || '').trim();

const loadConfiguration = async () => {
  if (!currentType) {
    throw new Error('Missing required query parameter: type.');
  }

  const params = new URLSearchParams();
  params.set('type', currentType);
  if (currentName) {
    params.set('name', currentName);
  }

  const response = await fetchWithTimeout(`/ui/api/variables?${params.toString()}`);
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not load component configuration (${response.status}).`);
  }

  return response.json();
};

const saveConfiguration = async () => {
  if (!currentState) {
    throw new Error('No configuration is loaded.');
  }

  const payload = collectVariableValues(list);
  payload.set('type', currentState.type);
  payload.set('name', currentState.name || '');

  const response = await fetchWithTimeout('/ui/api/variables', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not save configuration (${response.status}).`);
  }

  return response.json();
};

const applyState = (state) => {
  currentState = state;
  if (subtitle) {
    subtitle.textContent = `Configure variables for ${String(state?.displayName || state?.name || '').trim() || 'component'}.`;
  }
  if (backLink) {
    backLink.href = String(state?.redirect || '/ui/bots.html');
  }
  if (errorMessage) {
    errorMessage.textContent = '';
    errorMessage.classList.add('hidden');
  }
  renderVariableEditor(list, state?.configuration);
};

document.addEventListener('DOMContentLoaded', () => {
  withWorkingOverlay(async () => {
    applyState(await loadConfiguration());
  }, 'Loading component configuration ...').catch((err) => {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load component configuration.', 'error');
  });

  form?.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!saveButton) {
      return;
    }

    saveButton.disabled = true;
    if (errorMessage) {
      errorMessage.textContent = '';
      errorMessage.classList.add('hidden');
    }

    try {
      const result = await withWorkingOverlay(async () => saveConfiguration(), 'Saving component configuration ...');
      const redirect = String(result?.redirect || currentState?.redirect || '/ui/bots.html');
      window.location.assign(redirect);
    } catch (err) {
      const text = err instanceof Error ? err.message : 'Failed to save component configuration.';
      if (errorMessage) {
        errorMessage.textContent = text;
        errorMessage.classList.remove('hidden');
      }
      showToast(toastContainer, text, 'error');
    } finally {
      saveButton.disabled = false;
    }
  });
});
