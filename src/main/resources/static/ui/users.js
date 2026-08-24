import { showToast } from '/ui/ui-helpers.js';

const toastContainer = document.getElementById('toast-container');

const createUserForm = document.getElementById('create-user-form');
const createFirst = document.getElementById('create-first');
const createLast = document.getElementById('create-last');
const createEmail = document.getElementById('create-email');
const createModel = document.getElementById('create-model');
const createPassword = document.getElementById('create-password');
const createPasswordConfirm = document.getElementById('create-password-confirm');
const createUserError = document.getElementById('create-user-error');
const createUserSubmit = document.getElementById('create-user-submit');

const findUserForm = document.getElementById('find-user-form');
const findFirst = document.getElementById('find-first');
const findLast = document.getElementById('find-last');
const findResult = document.getElementById('find-result');
const findResultContent = document.getElementById('find-result-content');

const resetPasswordSection = document.getElementById('reset-password-section');
const resetPasswordForm = document.getElementById('reset-password-form');
const resetPassword = document.getElementById('reset-password');
const resetPasswordConfirm = document.getElementById('reset-password-confirm');
const resetPasswordError = document.getElementById('reset-password-error');
const resetPasswordSubmit = document.getElementById('reset-password-submit');
const usersGridServiceWarning = document.getElementById('users-grid-service-warning');
const usersGridServicePill = document.getElementById('users-grid-service-pill');

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
  const response = await fetch('/api/simulator/grid-service');
  if (!response.ok) {
    throw new Error(`Could not determine grid service availability (${response.status}).`);
  }
  const payload = await response.json();
  return !!payload?.available;
};

const setFormEnabled = (form, enabled) => {
  if (!form) {
    return;
  }
  form.querySelectorAll('input, select, textarea, button').forEach((field) => {
    field.disabled = !enabled;
  });
  form.classList.toggle('opacity-50', !enabled);
};

const applyGridServiceUiState = () => {
  const enabled = gridServiceAvailable;
  setFormEnabled(createUserForm, enabled);
  setFormEnabled(findUserForm, enabled);
  setFormEnabled(resetPasswordForm, enabled);
  if (usersGridServiceWarning) {
    usersGridServiceWarning.classList.toggle('hidden', enabled);
  }
  if (!enabled && findResult) {
    findResult.classList.add('hidden');
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

const createUser = async ({ first, last, email, model, password }) => {
  const payload = new URLSearchParams();
  payload.set('first', first);
  payload.set('last', last);
  payload.set('email', email);
  payload.set('model', model);
  payload.set('password', password);

  const response = await fetch('/api/user', {
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

const findUser = async (first, last) => {
  const response = await fetch(`/api/user/${encodeURIComponent(first)}/${encodeURIComponent(last)}`);
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Find user failed (${response.status}).`);
  }
  return response.json();
};

const resetUserPassword = async (first, last, password) => {
  const payload = new URLSearchParams();
  payload.set('password', password);

  const response = await fetch(`/api/user/${encodeURIComponent(first)}/${encodeURIComponent(last)}/password`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Reset password failed (${response.status}).`);
  }
};

const renderFindResult = (result) => {
  if (!findResult || !findResultContent || !resetPasswordSection) {
    return;
  }

  const account = result && typeof result.account === 'object' ? result.account : {};
  const entries = Object.entries(account);

  findResult.classList.remove('hidden');

  if (!result?.found || entries.length === 0) {
    findResultContent.textContent = `No account found for ${result?.first || ''} ${result?.last || ''}.`.trim();
    resetPasswordSection.classList.add('hidden');
    return;
  }

  findResultContent.innerHTML = '';
  entries.forEach(([key, value]) => {
    const row = document.createElement('div');
    row.className = 'grid grid-cols-[10rem_minmax(0,1fr)] gap-3';

    const keyEl = document.createElement('div');
    keyEl.className = 'text-gray-400';
    keyEl.textContent = `${key}:`;

    const valueEl = document.createElement('div');
    valueEl.className = 'text-gray-100 break-all';
    valueEl.textContent = String(value || '');

    row.appendChild(keyEl);
    row.appendChild(valueEl);
    findResultContent.appendChild(row);
  });

  resetPasswordSection.classList.remove('hidden');
};

const loadFindResultFromQuery = async () => {
  if (!gridServiceAvailable) {
    return;
  }
  const params = new URLSearchParams(window.location.search);
  const first = String(params.get('first') || '').trim();
  const last = String(params.get('last') || '').trim();
  if (!first || !last) {
    return;
  }

  if (findFirst) {
    findFirst.value = first;
  }
  if (findLast) {
    findLast.value = last;
  }

  try {
    const result = await findUser(first, last);
    renderFindResult(result);
    if (!result.found) {
      showToast(toastContainer, `No account found for ${first} ${last}.`, 'info');
    }
  } catch (err) {
    showToast(toastContainer, err instanceof Error ? err.message : 'Find user failed.', 'error');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  fetchGridServiceAvailability()
    .then((available) => {
      gridServiceAvailable = available;
      applyGridServiceUiState();
      if (!available) {
        showToast(toastContainer, gridServiceUnavailableMessage, 'info');
      } else {
        loadFindResultFromQuery();
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

    if (!createFirst || !createLast || !createEmail || !createModel || !createPassword || !createPasswordConfirm || !createUserSubmit) {
      return;
    }

    const first = createFirst.value.trim();
    const last = createLast.value.trim();
    const email = createEmail.value.trim();
    const model = createModel.value.trim();
    const password = createPassword.value;
    const confirm = createPasswordConfirm.value;

    if (!first || !last || !email || !model || !password) {
      showInlineError(createUserError, 'All fields are required.');
      return;
    }

    if (password !== confirm) {
      showInlineError(createUserError, 'Password and confirm password must match.');
      return;
    }

    createUserSubmit.disabled = true;
    try {
      const created = await createUser({ first, last, email, model, password });
      createUserForm.reset();
      showToast(toastContainer, `Created user ${created.first} ${created.last}.`, 'success');
    } catch (err) {
      showInlineError(createUserError, err instanceof Error ? err.message : 'Create user failed.');
      showToast(toastContainer, createUserError?.textContent || 'Create user failed.', 'error');
    } finally {
      createUserSubmit.disabled = false;
    }
  });

  findUserForm?.addEventListener('submit', (event) => {
    event.preventDefault();
    if (!gridServiceAvailable) {
      showToast(toastContainer, gridServiceUnavailableMessage, 'error');
      return;
    }
    const first = String(findFirst?.value || '').trim();
    const last = String(findLast?.value || '').trim();
    if (!first || !last) {
      showToast(toastContainer, 'First name and last name are required to search.', 'error');
      return;
    }

    const next = new URL(window.location.href);
    next.searchParams.set('first', first);
    next.searchParams.set('last', last);
    window.location.assign(next.toString());
  });

  resetPasswordForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearInlineError(resetPasswordError);

    if (!gridServiceAvailable) {
      showInlineError(resetPasswordError, gridServiceUnavailableMessage);
      return;
    }

    if (!resetPassword || !resetPasswordConfirm || !resetPasswordSubmit) {
      return;
    }

    const params = new URLSearchParams(window.location.search);
    const first = String(params.get('first') || '').trim();
    const last = String(params.get('last') || '').trim();
    const password = resetPassword.value;
    const confirm = resetPasswordConfirm.value;

    if (!first || !last) {
      showInlineError(resetPasswordError, 'Find a user first before resetting password.');
      return;
    }

    if (!password) {
      showInlineError(resetPasswordError, 'Password is required.');
      return;
    }

    if (password !== confirm) {
      showInlineError(resetPasswordError, 'Password and confirm password must match.');
      return;
    }

    resetPasswordSubmit.disabled = true;
    try {
      await resetUserPassword(first, last, password);
      resetPasswordForm.reset();
      showToast(toastContainer, `Reset password for ${first} ${last}.`, 'success');
    } catch (err) {
      showInlineError(resetPasswordError, err instanceof Error ? err.message : 'Reset password failed.');
      showToast(toastContainer, resetPasswordError?.textContent || 'Reset password failed.', 'error');
    } finally {
      resetPasswordSubmit.disabled = false;
    }
  });
});
