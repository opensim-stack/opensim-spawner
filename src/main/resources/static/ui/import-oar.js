import { fetchWithTimeout, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const form = document.getElementById('import-form');
const fileInput = document.getElementById('import-file');
const urlInput = document.getElementById('import-url');
const mergeInput = document.getElementById('import-merge');
const skipAssetsInput = document.getElementById('import-skip-assets');
const errorMessage = document.getElementById('import-error');
const submitButton = document.getElementById('import-submit');
const subtitle = document.getElementById('import-subtitle');
const backLink = document.getElementById('import-back-link');
const toastContainer = document.getElementById('toast-container');
const dropTarget = document.getElementById('import-drop-target');

const query = new URLSearchParams(window.location.search);
const region = String(query.get('region') || '').trim();
const simulator = String(query.get('simulator') || '').trim();

if (subtitle) {
  subtitle.textContent = region
    ? `Import an OAR archive into region ${region}.`
    : 'Import an OAR archive into a region.';
}
if (backLink) {
  backLink.href = simulator ? `/ui/regions.html?simulator=${encodeURIComponent(simulator)}` : '/ui/simulators.html';
}

function clearMessages() {
  errorMessage?.classList.add('hidden');
}

function hasFileSelection() {
  return Boolean(fileInput?.files?.length);
}

function hasUrlSelection() {
  return Boolean(urlInput?.value?.trim());
}

function syncImportSourceControls() {
  if (!fileInput || !urlInput) {
    return;
  }

  const fileSelected = hasFileSelection();
  const urlSelected = hasUrlSelection();

  urlInput.disabled = fileSelected;
  fileInput.disabled = urlSelected;
}

function setError(text) {
  if (!errorMessage) {
    return;
  }
  errorMessage.textContent = text;
  errorMessage.classList.remove('hidden');
}

function setDropActive(active) {
  const target = dropTarget || form;
  if (!target) {
    return;
  }
  target.classList.toggle('border-neon-accent/60', active);
  target.classList.toggle('bg-neon-accent/5', active);
}

function extractDroppedUrl(dataTransfer) {
  if (!dataTransfer) {
    return '';
  }

  const uriList = String(dataTransfer.getData('text/uri-list') || '');
  if (uriList) {
    const fromUriList = uriList
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find((line) => line && !line.startsWith('#'));
    if (fromUriList) {
      return fromUriList;
    }
  }

  const plainText = String(dataTransfer.getData('text/plain') || '').trim();
  if (!plainText) {
    return '';
  }

  try {
    const maybeUrl = new URL(plainText);
    return maybeUrl.toString();
  } catch (_err) {
    return '';
  }
}

document.addEventListener('DOMContentLoaded', () => {
  if (fileInput) {
    fileInput.addEventListener('change', () => {
      clearMessages();
      if (hasFileSelection() && urlInput) {
        urlInput.value = '';
      }
      syncImportSourceControls();
    });
  }

  if (urlInput) {
    urlInput.addEventListener('input', () => {
      clearMessages();
      if (hasUrlSelection() && fileInput?.files?.length) {
        fileInput.value = '';
      }
      syncImportSourceControls();
    });
  }

  if (form) {
    form.addEventListener('dragover', (event) => {
      event.preventDefault();
      setDropActive(true);
    });

    form.addEventListener('dragleave', () => {
      setDropActive(false);
    });

    form.addEventListener('drop', (event) => {
      event.preventDefault();
      setDropActive(false);
      clearMessages();

      const droppedFiles = event.dataTransfer?.files;
      if (droppedFiles?.length && fileInput && !fileInput.disabled) {
        const transfer = new DataTransfer();
        transfer.items.add(droppedFiles[0]);
        fileInput.files = transfer.files;
        if (urlInput) {
          urlInput.value = '';
        }
        syncImportSourceControls();
        form.requestSubmit();
        return;
      }

      const droppedUrl = extractDroppedUrl(event.dataTransfer);
      if (droppedUrl && urlInput && !urlInput.disabled) {
        urlInput.value = droppedUrl;
        if (fileInput) {
          fileInput.value = '';
        }
        syncImportSourceControls();
        form.requestSubmit();
      }
    });
  }

  syncImportSourceControls();

  form?.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!submitButton) {
      return;
    }

    clearMessages();
    submitButton.disabled = true;

    try {
      if (!region) {
        throw new Error('Missing region parameter.');
      }

      const urlValue = urlInput?.value?.trim() || '';
      const fileSelected = hasFileSelection();
      const urlSelected = Boolean(urlValue);
      const merge = Boolean(mergeInput?.checked);
      const skipAssets = Boolean(skipAssetsInput?.checked);

      if (!fileSelected && !urlSelected) {
        throw new Error('Select an OAR file or provide an OAR URL.');
      }
      if (fileSelected && urlSelected) {
        throw new Error('Choose either file upload or URL import, not both.');
      }

      await withWorkingOverlay(async () => {
        let response;

        if (urlSelected) {
          const params = new URLSearchParams();
          params.set('url', urlValue);
          params.set('merge', merge ? 'true' : 'false');
          params.set('skipAssets', skipAssets ? 'true' : 'false');
          response = await fetchWithTimeout(
            `/api/import/oar-url/${encodeURIComponent(region)}?${params.toString()}`,
            { method: 'GET' }
          );
        } else {
          const formData = new FormData();
          if (fileInput?.files?.length) {
            formData.append('file', fileInput.files[0]);
          }
          formData.append('merge', merge ? 'true' : 'false');
          formData.append('skipAssets', skipAssets ? 'true' : 'false');

          response = await fetchWithTimeout(
            `/api/import/oar/${encodeURIComponent(region)}`,
            { method: 'POST', body: formData }
          );
        }

        if (!response.ok) {
          const text = await response.text();
          throw new Error(text || `Could not import archive (${response.status}).`);
        }
      }, urlSelected ? 'Fetching and importing region archive ...' : 'Uploading and importing region archive ...');

      showToast(toastContainer, 'OAR imported successfully.', 'success');
      if (simulator) {
        window.location.assign(`/ui/regions.html?simulator=${encodeURIComponent(simulator)}`);
      } else {
        window.location.assign('/ui/simulators.html');
      }
    } catch (err) {
      const text = err instanceof Error ? err.message : 'Failed to import OAR.';
      setError(text);
      showToast(toastContainer, text, 'error');
    } finally {
      if (submitButton) {
        submitButton.disabled = false;
      }
    }
  });
});
