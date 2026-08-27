import { fetchWithTimeout, iconSpan, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const approvalsList = document.getElementById('approvals-list');
const approvalsEmpty = document.getElementById('approvals-empty');
const refreshButton = document.getElementById('refresh-approvals');
const toastContainer = document.getElementById('toast-container');

let activeApproveMenu = null;

const closeActiveApproveMenu = () => {
  if (!activeApproveMenu) {
    return;
  }
  activeApproveMenu.menu.classList.add('hidden');
  activeApproveMenu.toggle.setAttribute('aria-expanded', 'false');
  activeApproveMenu = null;
};

const openApproveMenu = (menu, toggle, container) => {
  if (activeApproveMenu && activeApproveMenu.menu !== menu) {
    closeActiveApproveMenu();
  }
  menu.classList.remove('hidden');
  toggle.setAttribute('aria-expanded', 'true');
  activeApproveMenu = { menu, toggle, container };
};

document.addEventListener('pointerdown', (event) => {
  if (!activeApproveMenu) {
    return;
  }
  if (!activeApproveMenu.container.contains(event.target)) {
    closeActiveApproveMenu();
  }
});

document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') {
    closeActiveApproveMenu();
  }
});

const formatRequestedAt = (value) => {
  const epochMillis = Number(value);
  if (!Number.isFinite(epochMillis) || epochMillis <= 0) {
    return '-';
  }
  const timestamp = new Date(epochMillis);
  if (Number.isNaN(timestamp.getTime())) {
    return '-';
  }
  return timestamp.toLocaleString();
};

const callPatchAction = async (first, last, action) => {
  const payload = new URLSearchParams();
  payload.set('action', action);

  const response = await fetchWithTimeout(`/api/approvals/${encodeURIComponent(first)}/${encodeURIComponent(last)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: payload.toString()
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not ${action} approval for ${first} ${last} (${response.status}).`);
  }

  return response.json();
};

const actionButton = (label, className, onClick) => {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = `px-3 py-1.5 text-xs rounded-lg border transition-colors ${className}`;
  button.textContent = label;
  button.addEventListener('click', onClick);
  return button;
};

const renderRow = (approval) => {
  const first = String(approval?.first || '').trim();
  const last = String(approval?.last || '').trim();
  const email = String(approval?.email || '').trim();
  const requestedAt = formatRequestedAt(approval?.requestedAtEpochMillis);
  if (!first || !last) {
    return null;
  }

  const row = document.createElement('div');
  row.className = 'grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_minmax(10rem,auto)_auto] gap-4 px-5 py-3 items-center';

  const userCol = document.createElement('div');
  userCol.className = 'min-w-0';
  const userName = document.createElement('div');
  userName.className = 'font-semibold text-gray-100 truncate';
  userName.textContent = `${first} ${last}`;
  userName.title = `${first} ${last}`;
  userCol.appendChild(userName);

  const emailCol = document.createElement('div');
  emailCol.className = 'min-w-0 text-sm text-gray-300 truncate';
  emailCol.textContent = email || '-';
  emailCol.title = email || '';

  const requestedCol = document.createElement('div');
  requestedCol.className = 'text-xs text-gray-300 whitespace-nowrap';
  requestedCol.textContent = requestedAt;
  requestedCol.title = requestedAt;

  const actions = document.createElement('div');
  actions.className = 'flex items-center gap-2 justify-end';

  const setButtonsEnabled = (enabled) => {
    actions.querySelectorAll('button').forEach((button) => {
      button.disabled = !enabled;
    });
  };

  const runApproveAction = async (action, messageVerb) => {
    setButtonsEnabled(false);
    try {
      await withWorkingOverlay(async () => {
        await callPatchAction(first, last, action);
        showToast(toastContainer, `${messageVerb} ${first} ${last}.`, 'success');
        await loadApprovals();
      }, `${messageVerb} ${first} ${last} ...`);
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Approve failed.', 'error');
    } finally {
      setButtonsEnabled(true);
    }
  };

  const approveWrap = document.createElement('div');
  approveWrap.className = 'relative inline-flex';

  const approveBtn = actionButton(
    'Approve',
    'rounded-r-none text-emerald-100 border-emerald-400/40 hover:bg-emerald-600/20',
    async () => {
      await runApproveAction('approve', 'Approved');
    }
  );

  const approveToggleBtn = document.createElement('button');
  approveToggleBtn.type = 'button';
  approveToggleBtn.className = 'px-2 py-1.5 text-xs rounded-r-lg border border-l-0 border-emerald-400/40 text-emerald-100 hover:bg-emerald-600/20';
  approveToggleBtn.innerHTML = iconSpan('chevronDown', 'h-4 w-4 inline-block align-middle shrink-0');
  approveToggleBtn.setAttribute('aria-haspopup', 'menu');
  approveToggleBtn.setAttribute('aria-expanded', 'false');

  const approveMenu = document.createElement('div');
  approveMenu.className = 'hidden absolute right-0 top-full z-20 mt-1 w-52 rounded-lg border border-neon-primary/30 bg-dark-800/95 p-1 shadow-lg';
  approveMenu.setAttribute('role', 'menu');

  const approveAsHandlerBtn = actionButton(
    'Approve as Bot Handler',
    'w-full text-left rounded-md border-transparent text-sky-200 hover:bg-sky-600/20',
    async () => {
      closeActiveApproveMenu();
      await runApproveAction('approve-handler', 'Approved');
    }
  );
  approveAsHandlerBtn.setAttribute('role', 'menuitem');
  approveMenu.appendChild(approveAsHandlerBtn);

  approveToggleBtn.addEventListener('click', (event) => {
    event.stopPropagation();
    if (approveMenu.classList.contains('hidden')) {
      openApproveMenu(approveMenu, approveToggleBtn, approveWrap);
      return;
    }
    closeActiveApproveMenu();
  });

  approveWrap.addEventListener('focusout', (event) => {
    const nextFocused = event.relatedTarget;
    if (nextFocused instanceof Node && approveWrap.contains(nextFocused)) {
      return;
    }
    if (activeApproveMenu?.menu === approveMenu) {
      closeActiveApproveMenu();
    }
  });

  approveWrap.appendChild(approveBtn);
  approveWrap.appendChild(approveToggleBtn);
  approveWrap.appendChild(approveMenu);

  const rejectBtn = actionButton(
    'Reject',
    'text-amber-100 border-amber-400/40 hover:bg-amber-600/20',
    async () => {
      setButtonsEnabled(false);
      try {
        await withWorkingOverlay(async () => {
          await callPatchAction(first, last, 'reject');
          showToast(toastContainer, `Rejected ${first} ${last}.`, 'success');
          await loadApprovals();
        }, `Rejecting ${first} ${last} ...`);
      } catch (err) {
        showToast(toastContainer, err instanceof Error ? err.message : 'Reject failed.', 'error');
      } finally {
        setButtonsEnabled(true);
      }
    }
  );

  actions.appendChild(approveWrap);
  actions.appendChild(rejectBtn);

  row.appendChild(userCol);
  row.appendChild(emailCol);
  row.appendChild(requestedCol);
  row.appendChild(actions);
  return row;
};

const loadApprovals = async () => {
  if (!approvalsList || !approvalsEmpty) {
    return;
  }

  approvalsList.innerHTML = '';
  approvalsEmpty.classList.add('hidden');

  const response = await fetchWithTimeout('/api/approvals');
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Could not load approvals (${response.status}).`);
  }

  const approvals = await response.json();
  if (!Array.isArray(approvals) || approvals.length === 0) {
    approvalsEmpty.classList.remove('hidden');
    return;
  }

  approvals
    .slice()
    .sort((a, b) => Number(b?.requestedAtEpochMillis || 0) - Number(a?.requestedAtEpochMillis || 0))
    .forEach((approval) => {
    const row = renderRow(approval);
    if (row) {
      approvalsList.appendChild(row);
    }
  });

  if (!approvalsList.children.length) {
    approvalsEmpty.classList.remove('hidden');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  refreshButton?.addEventListener('click', async () => {
    try {
      await withWorkingOverlay(async () => {
        await loadApprovals();
      }, 'Refreshing approvals ...');
      showToast(toastContainer, 'Approvals list refreshed.', 'success');
    } catch (err) {
      showToast(toastContainer, err instanceof Error ? err.message : 'Refresh failed.', 'error');
    }
  });

  loadApprovals().catch((err) => {
    showToast(toastContainer, err instanceof Error ? err.message : 'Failed to load approvals.', 'error');
  });
});
