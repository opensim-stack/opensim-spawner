import { fetchWithTimeout, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const createUserForm = document.getElementById('create-user-form');
const createFirst = document.getElementById('create-first');
const createLast = document.getElementById('create-last');
const createEmail = document.getElementById('create-email');
const createPassword = document.getElementById('create-password');
const createPasswordConfirm = document.getElementById('create-password-confirm');
const createHandler = document.getElementById('create-handler');
const createUserError = document.getElementById('create-user-error');
const createUserSubmit = document.getElementById('create-user-submit');
const usersGridServiceWarning = document.getElementById('users-grid-service-warning');
const usersGridServicePill = document.getElementById('users-grid-service-pill');
const toastContainer = document.getElementById('toast-container');

let gridServiceAvailable = false;
const gridServiceUnavailableMessage = 'User management is disabled until a ROBUST or STANDALONE simulator is active.';

const showInlineError = (el, message) => {
  if (!el) {
    return;
  }
  el.textContent = message;
  el.classList.remove('hidden');
};

const clearInlineError = (el) => {
  if (!el) {
    return;
  }
  el.textContent = '';
  el.classList.add('hidden');
};

const fetchGridServiceAvailability = async () => {
  const response = await fetchWithTimeout('/api/simulator/grid-service');
  if (!response.ok) {
    throw new Error(`Could not determine grid service availability (${response.status}).`);
  }
  const payload = await response.json();
  return !!payload?.available;
};

const applyGridServiceUiState = () => {
  const enabled = gridServiceAvailable;
  if (createUserForm) {
    createUserForm.querySelectorAll('input, button').forEach((field) => {
      field.disabled = !enabled;
    });
    createUserForm.classList.toggle('opacity-50', !enabled);
  }
  if (usersGridServiceWarning) {
    usersGridServiceWarning.classList.toggle('hidden', enabled);
  }
  if (usersGridServicePill) {
    usersGridServicePill.className = `mt-2 inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs ${enabled
      ? 'border-emerald-400/40 bg-emerald-500/10 text-emerald-200'
      : 'border-amber-400/40 bg-amber-500/10 text-amber-200'}`;
    usersGridServicePill.innerHTML = enabled
      ? '<span class="w-2 h-2 rounded-full bg-emerald-300"></span><span>Grid Login: Available</span>'
      : '<span class="w-2 h-2 rounded-full bg-amber-300"></span><span>Grid Login: Unavailable</span>';
  }
};

const createUser = async ({ first, last, email, password, botHandler }) => {
  const payload = new URLSearchParams();
  payload.set('first', first);
  payload.set('last', last);
  payload.set('email', email);
  payload.set('password', password);
  if (botHandler) {
    payload.set('botHandler', 'true');
  }

  const response = await fetchWithTimeout('/api/user', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Create user failed (${response.status}).`);
  }

  return response.json();
};

const listHandlers = async () => {
  const response = await fetchWithTimeout('/api/user/handlers');
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Handler query failed (${response.status}).`);
  }
  const payload = await response.json();
  return Array.isArray(payload) ? payload : [];
};

document.addEventListener('DOMContentLoaded', () => {
  fetchGridServiceAvailability()
    .then((available) => {
      gridServiceAvailable = available;
      applyGridServiceUiState();
      if (!available) {
        showToast(toastContainer, gridServiceUnavailableMessage, 'info');
      } else if (createHandler) {
        listHandlers()
          .then((handlers) => {
            createHandler.checked = handlers.length === 0;
          })
          .catch((err) => {
            createHandler.checked = false;
            showToast(toastContainer, err instanceof Error ? err.message : 'Could not load handler defaults.', 'error');
          });
      }
    })
    .catch((err) => {
      gridServiceAvailable = false;
      applyGridServiceUiState();
      showToast(toastContainer, err instanceof Error ? err.message : 'Failed to query grid service availability.', 'error');
    });

  createUserForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearInlineError(createUserError);

    if (!gridServiceAvailable) {
      showInlineError(createUserError, gridServiceUnavailableMessage);
      return;
    }

    if (!createFirst || !createLast || !createEmail || !createPassword || !createPasswordConfirm || !createUserSubmit) {
      return;
    }

    const first = createFirst.value.trim();
    const last = createLast.value.trim();
    const email = createEmail.value.trim();
    const password = createPassword.value;
    const confirm = createPasswordConfirm.value;

    if (!first || !last || !email || !password) {
      showInlineError(createUserError, 'All fields are required.');
      return;
    }

    if (password !== confirm) {
      showInlineError(createUserError, 'Password and confirm password must match.');
      return;
    }

    createUserSubmit.disabled = true;
    try {
      await withWorkingOverlay(
        async () => createUser({ first, last, email, password, botHandler: !!createHandler?.checked }),
        `Creating user ${first} ${last} ...`
      );
      const next = new URL('/ui/users.html', window.location.origin);
      next.searchParams.set('first', first);
      next.searchParams.set('last', last);
      window.location.assign(next.toString());
    } catch (err) {
      showInlineError(createUserError, err instanceof Error ? err.message : 'Create user failed.');
      showToast(toastContainer, createUserError?.textContent || 'Create user failed.', 'error');
    } finally {
      createUserSubmit.disabled = false;
    }
  });
});
