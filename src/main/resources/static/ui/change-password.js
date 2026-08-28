import { fetchWithTimeout, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const form = document.getElementById('change-password-form');
const oldPassword = document.getElementById('old-password');
const newPassword = document.getElementById('new-password');
const confirmPassword = document.getElementById('confirm-password');
const submitButton = document.getElementById('change-password-submit');
const errorEl = document.getElementById('change-password-error');
const toastContainer = document.getElementById('toast-container');
const gridServicePill = document.getElementById('change-password-grid-service-pill');
const gridServiceWarning = document.getElementById('change-password-grid-service-warning');

let gridServiceAvailable = false;
const gridServiceUnavailableMessage = 'Password changes are disabled until a ROBUST or STANDALONE simulator is active.';

const showInlineError = (message) => {
  if (!errorEl) {
    return;
  }
  errorEl.textContent = message;
  errorEl.classList.remove('hidden');
};

const clearInlineError = () => {
  if (!errorEl) {
    return;
  }
  errorEl.textContent = '';
  errorEl.classList.add('hidden');
};

const setGridServiceBadge = (available) => {
  if (gridServicePill) {
    gridServicePill.className = `mt-2 inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs ${available
      ? 'border-emerald-400/40 bg-emerald-500/10 text-emerald-200'
      : 'border-amber-400/40 bg-amber-500/10 text-amber-200'}`;
    gridServicePill.innerHTML = available
      ? '<span class="w-2 h-2 rounded-full bg-emerald-300"></span><span>Grid Service: Online</span>'
      : '<span class="w-2 h-2 rounded-full bg-amber-300"></span><span>Grid Service: Unavailable</span>';
  }
  if (gridServiceWarning) {
    gridServiceWarning.classList.toggle('hidden', available);
  }
};

const setFormEnabled = (enabled) => {
  if (!form) {
    return;
  }
  form.querySelectorAll('input, button').forEach((field) => {
    field.disabled = !enabled;
  });
  form.classList.toggle('opacity-50', !enabled);
};

const fetchGridServiceAvailability = async () => {
  const response = await fetchWithTimeout('/ui/api/auth/grid-status');
  if (!response.ok) {
    throw new Error(`Could not determine grid service availability (${response.status}).`);
  }
  const payload = await response.json();
  return !!payload?.available;
};

const changePassword = async (oldPass, newPass) => {
  const payload = new URLSearchParams();
  payload.set('oldPassword', oldPass);
  payload.set('newPassword', newPass);

  const response = await fetchWithTimeout('/ui/api/auth/change-password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Password change failed (${response.status}).`);
  }
};

document.addEventListener('DOMContentLoaded', () => {
  fetchGridServiceAvailability()
    .then((available) => {
      gridServiceAvailable = available;
      setGridServiceBadge(available);
      setFormEnabled(available);
      if (!available) {
        showToast(toastContainer, gridServiceUnavailableMessage, 'info');
      }
    })
    .catch((err) => {
      gridServiceAvailable = false;
      setGridServiceBadge(false);
      setFormEnabled(false);
      showToast(toastContainer, err instanceof Error ? err.message : 'Failed to query grid service availability.', 'error');
    });

  form?.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearInlineError();

    if (!gridServiceAvailable) {
      showInlineError(gridServiceUnavailableMessage);
      return;
    }

    const oldPass = String(oldPassword?.value || '');
    const newPass = String(newPassword?.value || '');
    const confirmPass = String(confirmPassword?.value || '');

    if (!oldPass || !newPass || !confirmPass) {
      showInlineError('All fields are required.');
      return;
    }

    if (newPass !== confirmPass) {
      showInlineError('New password and confirm password must match.');
      return;
    }

    if (!submitButton) {
      return;
    }

    submitButton.disabled = true;
    try {
      await withWorkingOverlay(async () => changePassword(oldPass, newPass), 'Changing password ...');
      form.reset();
      showToast(toastContainer, 'Password updated.', 'success');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Password change failed.';
      showInlineError(message);
      showToast(toastContainer, message, 'error');
    } finally {
      submitButton.disabled = false;
    }
  });
});
