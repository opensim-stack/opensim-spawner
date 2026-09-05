import { fetchWithTimeout, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const updatesForm = document.getElementById('updates-form');
const automaticUpdates = document.getElementById('automatic-updates');
const updatesTag = document.getElementById('updates-tag');
const dockerHubUsername = document.getElementById('dockerhub-username');
const dockerHubToken = document.getElementById('dockerhub-token');
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
  payload.set('tag', String(updatesTag?.value || '').trim() || 'latest');
  payload.set('dockerHubUsername', String(dockerHubUsername?.value || '').trim());
  payload.set('dockerHubToken', String(dockerHubToken?.value || ''));

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
    updatesTag.value = String(state?.tag || 'latest');
  }
  if (dockerHubUsername) {
    dockerHubUsername.value = String(state?.dockerHubUsername || '');
  }
  if (dockerHubToken) {
    dockerHubToken.value = String(state?.dockerHubToken || '');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  withWorkingOverlay(async () => {
    applyState(await loadUpdatesConfig());
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
});
