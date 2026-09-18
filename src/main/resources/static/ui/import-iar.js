import { fetchWithTimeout, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const form = document.getElementById('import-form');
const pathInput = document.getElementById('import-path');
const fileInput = document.getElementById('import-file');
const errorMessage = document.getElementById('import-error');
const successMessage = document.getElementById('import-success');
const submitButton = document.getElementById('import-submit');
const subtitle = document.getElementById('import-subtitle');
const backLink = document.getElementById('import-back-link');
const toastContainer = document.getElementById('toast-container');

const query = new URLSearchParams(window.location.search);
const first = String(query.get('first') || '').trim();
const last = String(query.get('last') || '').trim();

if (subtitle) {
  const label = `${first} ${last}`.trim();
  subtitle.textContent = label
    ? `Import an IAR file into ${label}'s inventory.`
    : 'Import an IAR file into a bot\'s inventory.';
}
if (backLink) {
  backLink.href = '/ui/bots.html';
}

// Show a subtle hint clearing on re-selection.
if (fileInput) {
  fileInput.addEventListener('change', () => {
    successMessage.classList.add('hidden');
    errorMessage.classList.add('hidden');
  });
}

document.addEventListener('DOMContentLoaded', () => {
  form?.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!submitButton) {
      return;
    }

    errorMessage.classList.add('hidden');
    successMessage.classList.add('hidden');
    submitButton.disabled = true;

    try {
      const firstValue = pathInput?.value?.trim() || '';

      await withWorkingOverlay(async () => {
        const formData = new FormData();
        formData.append('inventoryPath', firstValue);
        if (fileInput?.files?.length) {
          formData.append('file', fileInput.files[0]);
        }

        const response = await fetchWithTimeout(
          `/ui/api/import/iar/${encodeURIComponent(first)}/${encodeURIComponent(last)}`,
          { method: 'POST', body: formData }
        );

        if (!response.ok) {
          const text = await response.text();
          throw new Error(text || `Could not import inventory (${response.status}).`);
        }
      }, 'Uploading and importing inventory ...');

      showToast(toastContainer, 'Inventory imported successfully.', 'success');
      window.location.assign('/ui/bots.html');
    } catch (err) {
      const text = err instanceof Error ? err.message : 'Failed to import inventory.';
      if (errorMessage) {
        errorMessage.textContent = text;
        errorMessage.classList.remove('hidden');
      }
      showToast(toastContainer, text, 'error');
    } finally {
      if (submitButton) {
        submitButton.disabled = false;
      }
    }
  });
});
