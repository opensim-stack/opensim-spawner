import { fetchWithTimeout, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';
import { collectVariableValues, renderVariableEditor } from '/ui/component-variables.js';

const updatesForm = document.getElementById('updates-form');
const automaticUpdates = document.getElementById('automatic-updates');
const updatesTag = document.getElementById('updates-tag');
const dockerHubUsername = document.getElementById('dockerhub-username');
const dockerHubToken = document.getElementById('dockerhub-token');
const addOnsRepository = document.getElementById('addons-repository');
const addOnsBranch = document.getElementById('addons-branch');
const globalVariablesForm = document.getElementById('global-variables-form');
const globalVariablesList = document.getElementById('global-variables-list');
const globalVariablesError = document.getElementById('global-variables-error');
const saveGlobalVariablesButton = document.getElementById('save-global-variables');
const toastContainer = document.getElementById('toast-container');


const loadUpdatesConfig = async () => {
  const response = await fetchWithTimeout('/ui/api/updates');
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not load updates configuration (${response.status}).`);
  }
  return response.json();
};

const saveUpdatesConfig = async () => {
  const payload = new URLSearchParams();
  payload.set('automaticUpdates', automaticUpdates?.checked ? 'true' : 'false');
  payload.set('tag', String(updatesTag?.value || '').trim() || '');
  payload.set('dockerHubUsername', String(dockerHubUsername?.value || '').trim());
  payload.set('dockerHubToken', String(dockerHubToken?.value || ''));
  payload.set('addOnsRepository', String(addOnsRepository?.value || '').trim());
  payload.set('addOnsBranch', String(addOnsBranch?.value || '').trim());

  const response = await fetchWithTimeout('/ui/api/updates', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not save updates configuration (${response.status}).`);
  }

  return response.json();
};

const applyState = (state) => {
  if (automaticUpdates) {
    automaticUpdates.checked = !!state?.automaticUpdates;
  }
  if (updatesTag) {
    updatesTag.value = String(state?.tag);
  }
  if (dockerHubUsername) {
    dockerHubUsername.value = String(state?.dockerHubUsername || '');
  }
  if (dockerHubToken) {
    dockerHubToken.value = String(state?.dockerHubToken || '');
  }
  if (addOnsRepository) {
    addOnsRepository.value = String(state?.addOnsRepository || '');
  }
  if (addOnsBranch) {
    addOnsBranch.value = String(state?.addOnsBranch || '');
  }
};

const loadGlobalVariables = async () => {
  const response = await fetchWithTimeout('/ui/api/variables?type=STACK');
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not load global container configuration (${response.status}).`);
  }
  return response.json();
};

const saveGlobalVariables = async () => {
  const payload = collectVariableValues(globalVariablesList);
  payload.set('type', 'STACK');

  const response = await fetchWithTimeout('/ui/api/variables', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not save global container configuration (${response.status}).`);
  }

  return response.json();
};

const applyGlobalVariablesState = (state) => {
  renderVariableEditor(globalVariablesList, state?.configuration);
  if (globalVariablesError) {
    globalVariablesError.textContent = '';
    globalVariablesError.classList.add('hidden');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  withWorkingOverlay(async () => {
    applyState(await loadUpdatesConfig());
    applyGlobalVariablesState(await loadGlobalVariables());
  }, 'Loading configuration ...').catch((err) => {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load configuration.', 'error');
  });

  updatesForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      await withWorkingOverlay(async () => {
        const updated = await saveUpdatesConfig();
        applyState(updated);
      }, 'Saving updates configuration ...');
      showToast(toastContainer, 'Updates configuration saved.', 'success');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Save failed.', 'error');
    }
  });

  globalVariablesForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!saveGlobalVariablesButton) {
      return;
    }

    saveGlobalVariablesButton.disabled = true;
    if (globalVariablesError) {
      globalVariablesError.textContent = '';
      globalVariablesError.classList.add('hidden');
    }

    try {
      await withWorkingOverlay(async () => {
        await saveGlobalVariables();
        applyGlobalVariablesState(await loadGlobalVariables());
      }, 'Saving global container configuration ...');
      showToast(toastContainer, 'Global container configuration saved.', 'success');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save global container configuration.';
      if (globalVariablesError) {
        globalVariablesError.textContent = message;
        globalVariablesError.classList.remove('hidden');
      }
      showToast(toastContainer, message, 'error');
    } finally {
      saveGlobalVariablesButton.disabled = false;
    }
  });
});
