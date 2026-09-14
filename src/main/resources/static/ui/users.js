import { fetchWithTimeout, iconSpan, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const toastContainer = document.getElementById('toast-container');

const activeUsersList = document.getElementById('active-users-list');
const activeUsersEmpty = document.getElementById('active-users-empty');
const refreshActiveUsersButton = document.getElementById('refresh-active-users');
const showAllAgentsCheckbox = document.getElementById('show-all-agents');

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
let botNameSet = new Set();
let handlerNameSet = new Set();
const gridServiceUnavailableMessage = 'User management is disabled until a ROBUST or STANDALONE simulator is active.';
const ACTIVE_USERS_COLUMN_COUNT = 7;

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

const setFormEnabled = (form, enabled) => {
  if (!form) {
    return;
  }
  form.querySelectorAll('input, select, textarea, button').forEach((field) => {
    field.disabled = !enabled;
  });
  form.classList.toggle('opacity-50', !enabled);
};

const setElementEnabled = (el, enabled) => {
  if (!el) {
    return;
  }
  el.disabled = !enabled;
  el.classList.toggle('opacity-50', !enabled);
  el.classList.toggle('cursor-not-allowed', !enabled);
};

const applyGridServiceUiState = () => {
  const enabled = gridServiceAvailable;
  setElementEnabled(refreshActiveUsersButton, enabled);
  setElementEnabled(showAllAgentsCheckbox, enabled);
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

const findUser = async (first, last) => {
  const response = await fetchWithTimeout(`/api/user/${encodeURIComponent(first)}/${encodeURIComponent(last)}`);
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Find user failed (${response.status}).`);
  }
  return response.json();
};

const listActiveUsers = async () => {
  const showAllAgents = !!showAllAgentsCheckbox?.checked;
  const response = await fetchWithTimeout(`/api/user/active?showAllAgents=${showAllAgents ? 'true' : 'false'}`);
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Active user query failed (${response.status}).`);
  }
  const payload = await response.json();
  return Array.isArray(payload) ? payload : [];
};

const listBotNames = async () => {
  const response = await fetchWithTimeout('/api/bot');
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Bot query failed (${response.status}).`);
  }
  const payload = await response.json();
  return Array.isArray(payload) ? payload : [];
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

const normalizeDisplayName = (first, last) => `${String(first || '').trim()} ${String(last || '').trim()}`.trim().toLowerCase();

const isBotUser = (first, last) => botNameSet.has(normalizeDisplayName(first, last));

const splitAgentName = (name) => {
  const parts = String(name || '').trim().split(/\s+/).filter((part) => part.length > 0);
  if (parts.length === 0) {
    return { fullName: '', first: '', last: '' };
  }
  if (parts.length === 1) {
    return { fullName: parts[0], first: parts[0], last: '' };
  }
  return {
    fullName: parts.join(' '),
    first: parts[0],
    last: parts.slice(1).join(' ')
  };
};

const formatVectorNumber = (value) => {
  const number = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(number)) {
    return '';
  }
  return Number.isInteger(number) ? String(number) : number.toFixed(2).replace(/\.0+$|0+$/g, '').replace(/\.$/, '');
};

const formatVector = (...values) => {
  const formatted = values.map(formatVectorNumber);
  return formatted.every((value) => value.length > 0) ? formatted.join(', ') : '';
};

const formatAgentPosition = (agent) => formatVector(agent?.posX, agent?.posY, agent?.posZ);

const formatAgentVelocity = (agent) => formatVector(agent?.velX, agent?.velY, agent?.velZ);

const formatAgentLookAt = (agent) => formatVector(agent?.lookatX, agent?.lookatY, agent?.lookatZ);

const appendIconMarkup = (el, action, className) => {
  if (!el) {
    return;
  }
  el.insertAdjacentHTML('beforeend', iconSpan(action, className));
};

const createAgentStateBadge = (iconName, label, active, activeClasses) => {
  const badge = document.createElement('span');
  badge.className = `inline-flex items-center justify-center rounded-md border p-1.5 transition-colors ${active
    ? activeClasses
    : 'border-gray-700/60 bg-dark-900/60 text-gray-500'}`;
  badge.title = active ? label : `Not ${label.toLowerCase()}`;
  badge.setAttribute('aria-label', badge.title);
  appendIconMarkup(badge, iconName, 'h-4 w-4 inline-block align-middle shrink-0');
  return badge;
};

const buildAgentStateCell = (agent) => {
  const wrap = document.createElement('div');
  wrap.className = 'inline-flex items-center gap-2';
  wrap.appendChild(createAgentStateBadge('flying', 'Flying', !!agent?.isFlying, 'border-sky-400/40 bg-sky-500/15 text-sky-200'));
  wrap.appendChild(createAgentStateBadge('satGround', 'Sat on ground', !!agent?.isSatOnGround, 'border-amber-400/40 bg-amber-500/15 text-amber-200'));
  wrap.appendChild(createAgentStateBadge('satObject', 'Sat on object', !!agent?.isSatOnObject, 'border-violet-400/40 bg-violet-500/15 text-violet-200'));
  return wrap;
};

const createRegionHeaderRow = (region, agentCount) => {
  const row = document.createElement('tr');
  row.className = 'bg-dark-900/60';

  const cell = document.createElement('td');
  cell.colSpan = ACTIVE_USERS_COLUMN_COUNT;
  cell.className = 'px-3 py-3 border-t border-b border-neon-primary/15';

  const wrap = document.createElement('div');
  wrap.className = 'flex flex-wrap items-center justify-between gap-3';

  const titleWrap = document.createElement('div');
  titleWrap.className = 'min-w-0';

  const title = document.createElement('div');
  title.className = 'text-sm font-semibold text-white';
  title.textContent = String(region?.name || 'Unknown region');

  const meta = document.createElement('div');
  meta.className = 'text-xs text-gray-400 font-mono break-all';
  meta.textContent = String(region?.uuid || '');

  titleWrap.appendChild(title);
  if (meta.textContent) {
    titleWrap.appendChild(meta);
  }

  const count = document.createElement('span');
  count.className = 'inline-flex items-center rounded-full border border-neon-primary/25 bg-neon-primary/10 px-2.5 py-1 text-xs text-neon-accent';
  count.textContent = `${agentCount} agent${agentCount === 1 ? '' : 's'}`;

  wrap.appendChild(titleWrap);
  wrap.appendChild(count);
  cell.appendChild(wrap);
  row.appendChild(cell);
  return row;
};

const setHandlerEnabled = async (first, last, enabled) => {
  const payload = new URLSearchParams();
  payload.set('enabled', enabled ? 'true' : 'false');
  const response = await fetchWithTimeout(`/api/user/${encodeURIComponent(first)}/${encodeURIComponent(last)}/handler`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Failed to update bot handler state (${response.status}).`);
  }
};

const resetUserPassword = async (first, last, password) => {
  const payload = new URLSearchParams();
  payload.set('password', password);

  const response = await fetchWithTimeout(`/api/user/${encodeURIComponent(first)}/${encodeURIComponent(last)}/password`, {
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
  const resultFirst = String(result?.first || '').trim();
  const resultLast = String(result?.last || '').trim();
  const isBot = isBotUser(resultFirst, resultLast);

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

  if (isBot) {
    const note = document.createElement('p');
    note.className = 'mt-4 text-sm text-amber-300';
    note.textContent = 'Password reset is unavailable for bot users.';
    findResultContent.appendChild(note);
    resetPasswordSection.classList.add('hidden');
    return;
  }

  resetPasswordSection.classList.remove('hidden');
};

const findAndRenderUser = async (first, last) => {
  const result = await withWorkingOverlay(async () => findUser(first, last), `Finding user ${first} ${last} ...`);
  renderFindResult(result);
  if (!result.found) {
    showToast(toastContainer, `No account found for ${first} ${last}.`, 'info');
  }
};

const renderActiveUsers = (regions) => {
  if (!activeUsersList || !activeUsersEmpty) {
    return;
  }

  activeUsersList.innerHTML = '';

  if (!Array.isArray(regions) || regions.length === 0) {
    activeUsersEmpty.classList.remove('hidden');
    return;
  }

  activeUsersEmpty.classList.add('hidden');
  let renderedAgentCount = 0;

  regions.forEach((region) => {
    const agents = Array.isArray(region?.agents) ? region.agents.filter((agent) => agent && typeof agent === 'object') : [];
    if (!agents.length) {
      return;
    }
    activeUsersList.appendChild(createRegionHeaderRow(region, agents.length));

    agents.forEach((agent) => {
      const { first, last, fullName } = splitAgentName(agent?.name);
      const displayKey = normalizeDisplayName(first || fullName, last);
      const isBot = botNameSet.has(displayKey);
      const isHandler = !isBot && handlerNameSet.has(displayKey);
      const normalizedAgentType = String(agent?.type || '').trim().toLowerCase();
      const isActionableAgent = normalizedAgentType === 'root' || normalizedAgentType === 'user';
      const hasSearchableName = !!first && !!last;

      const tr = document.createElement('tr');

      const firstTd = document.createElement('td');
      firstTd.className = 'py-2 pr-3 text-gray-100';
      const firstWrap = document.createElement('span');
      firstWrap.className = 'inline-flex items-center gap-2';
      const iconWrap = document.createElement('span');
      iconWrap.className = isBot ? 'text-amber-300' : (isHandler ? 'text-sky-300' : 'text-emerald-300');
      appendIconMarkup(iconWrap, isBot ? 'robot' : (isHandler ? 'handler' : 'person'), 'h-4 w-4 inline-block align-middle shrink-0');
      const firstText = document.createElement('span');
      firstText.textContent = first || fullName || 'Unknown';
      firstWrap.appendChild(iconWrap);
      firstWrap.appendChild(firstText);
      firstTd.appendChild(firstWrap);

      const lastTd = document.createElement('td');
      lastTd.className = 'py-2 pr-3 text-gray-100';
      lastTd.textContent = last;

      const idTd = document.createElement('td');
      idTd.className = 'py-2 pr-3 text-gray-300 font-mono text-xs break-all';
      idTd.textContent = String(agent?.uuid || '');

      const typeTd = document.createElement('td');
      typeTd.className = 'py-2 pr-3 text-gray-300';
      typeTd.textContent = String(agent?.type || '');

      const stateTd = document.createElement('td');
      stateTd.className = 'py-2 pr-3 text-gray-300';
      stateTd.appendChild(buildAgentStateCell(agent));

      const posTd = document.createElement('td');
      posTd.className = 'py-2 pr-3 text-gray-300 font-mono text-xs';
      const position = formatAgentPosition(agent);
      const velocity = formatAgentVelocity(agent);
      const lookAt = formatAgentLookAt(agent);
      const positionPrimary = document.createElement('div');
      positionPrimary.textContent = position || '—';
      posTd.appendChild(positionPrimary);
      if (velocity) {
        const velocityEl = document.createElement('div');
        velocityEl.className = 'mt-1 text-[11px] text-gray-500';
        velocityEl.textContent = `Vel ${velocity}`;
        posTd.appendChild(velocityEl);
      }
      const details = [
        agent?.currentParcelUuid ? `Parcel: ${agent.currentParcelUuid}` : '',
        lookAt ? `Look At: ${lookAt}` : ''
      ].filter((value) => value.length > 0);
      if (details.length > 0) {
        posTd.title = details.join('\n');
      }

      const actionTd = document.createElement('td');
      actionTd.className = 'py-2 text-right';
      const actionsWrap = document.createElement('div');
      actionsWrap.className = 'inline-flex items-center gap-2';

      if (isActionableAgent && hasSearchableName) {
        const selectButton = document.createElement('button');
        selectButton.type = 'button';
        selectButton.className = 'inline-flex items-center gap-2 rounded-lg border border-neon-primary/40 px-3 py-1.5 text-xs text-neon-accent hover:bg-neon-primary/10';
        selectButton.innerHTML = `${iconSpan('select', 'h-4 w-4 inline-block align-middle shrink-0')}<span>Select</span>`;
        selectButton.addEventListener('click', async () => {
          if (!gridServiceAvailable) {
            showToast(toastContainer, gridServiceUnavailableMessage, 'error');
            return;
          }
          if (findFirst) {
            findFirst.value = first;
          }
          if (findLast) {
            findLast.value = last;
          }

          const next = new URL(window.location.href);
          next.searchParams.set('first', first);
          next.searchParams.set('last', last);
          window.history.replaceState({}, '', next.toString());

          try {
            await findAndRenderUser(first, last);
          } catch (err) {
            showToast(toastContainer, err instanceof Error ? err.message : 'Find user failed.', 'error');
          }
        });

        actionsWrap.appendChild(selectButton);

        if (!isBot) {
          const handlerButton = document.createElement('button');
          handlerButton.type = 'button';
          const syncHandlerButton = (enabled) => {
            handlerButton.className = enabled
              ? 'inline-flex items-center gap-2 rounded-lg border border-sky-400/40 px-3 py-1.5 text-xs text-sky-200 bg-sky-600/20 hover:bg-sky-600/30'
              : 'inline-flex items-center gap-2 rounded-lg border border-gray-500/40 px-3 py-1.5 text-xs text-gray-200 hover:bg-dark-700';
            handlerButton.innerHTML = `${iconSpan('handler', 'h-4 w-4 inline-block align-middle shrink-0')}<span>${enabled ? 'Handler On' : 'Handler Off'}</span>`;
          };
          syncHandlerButton(isHandler);

          handlerButton.addEventListener('click', async () => {
            if (!gridServiceAvailable) {
              showToast(toastContainer, gridServiceUnavailableMessage, 'error');
              return;
            }
            const currentlyEnabled = handlerNameSet.has(displayKey);
            selectButton.disabled = true;
            handlerButton.disabled = true;
            try {
              await withWorkingOverlay(
                async () => setHandlerEnabled(first, last, !currentlyEnabled),
                `${currentlyEnabled ? 'Removing' : 'Assigning'} bot handler ${first} ${last} ...`
              );
              if (currentlyEnabled) {
                handlerNameSet.delete(displayKey);
              } else {
                handlerNameSet.add(displayKey);
              }
              renderActiveUsers(regions);
              showToast(toastContainer, `${first} ${last} ${currentlyEnabled ? 'is no longer' : 'is now'} a bot handler.`, 'success');
            } catch (err) {
              showToast(toastContainer, err instanceof Error ? err.message : 'Failed to update bot handler state.', 'error');
            } finally {
              selectButton.disabled = false;
              handlerButton.disabled = false;
            }
          });

          actionsWrap.appendChild(handlerButton);
        }
      }

      actionTd.appendChild(actionsWrap);

      tr.appendChild(firstTd);
      tr.appendChild(lastTd);
      tr.appendChild(idTd);
      tr.appendChild(typeTd);
      tr.appendChild(stateTd);
      tr.appendChild(posTd);
      tr.appendChild(actionTd);
      activeUsersList.appendChild(tr);
      renderedAgentCount += 1;
    });
  });

  if (renderedAgentCount === 0) {
    activeUsersEmpty.classList.remove('hidden');
  }
};

const loadActiveUsers = async () => {
  const [users, botNames, handlers] = await withWorkingOverlay(
    async () => Promise.all([listActiveUsers(), listBotNames(), listHandlers()]),
    'Loading active users ...'
  );
  botNameSet = new Set(botNames.map((name) => String(name || '').trim().toLowerCase()).filter((name) => name.length > 0));
  handlerNameSet = new Set(handlers
    .map((item) => normalizeDisplayName(item?.handlerFirst, item?.handlerLast))
    .filter((name) => name.length > 0));
  renderActiveUsers(users);
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

  await findAndRenderUser(first, last);
};

document.addEventListener('DOMContentLoaded', () => {
  fetchGridServiceAvailability()
    .then(async (available) => {
      gridServiceAvailable = available;
      applyGridServiceUiState();
      if (!available) {
        showToast(toastContainer, gridServiceUnavailableMessage, 'info');
      } else {
        try {
          await loadActiveUsers();
          await loadFindResultFromQuery();
        } catch (err) {
          showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load users data.', 'error');
        }
      }
    })
    .catch((err) => {
      gridServiceAvailable = false;
      applyGridServiceUiState();
      showToast(toastContainer, err instanceof Error ? err.message : 'Failed to query grid service availability.', 'error');
    });

  refreshActiveUsersButton?.addEventListener('click', async () => {
    if (!gridServiceAvailable) {
      showToast(toastContainer, gridServiceUnavailableMessage, 'error');
      return;
    }
    try {
      await loadActiveUsers();
      showToast(toastContainer, 'Active users refreshed.', 'success');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Active users refresh failed.', 'error');
    }
  });

  showAllAgentsCheckbox?.addEventListener('change', async () => {
    if (!gridServiceAvailable) {
      return;
    }
    try {
      await loadActiveUsers();
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Active users refresh failed.', 'error');
    }
  });

  findUserForm?.addEventListener('submit', async (event) => {
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
    window.history.replaceState({}, '', next.toString());

    try {
      await findAndRenderUser(first, last);
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Find user failed.', 'error');
    }
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

    if (isBotUser(first, last)) {
      showInlineError(resetPasswordError, 'Password reset is unavailable for bot users.');
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
      await withWorkingOverlay(async () => resetUserPassword(first, last, password), `Resetting password for ${first} ${last} ...`);
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
