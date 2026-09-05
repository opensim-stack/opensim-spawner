export const showToast = (toastContainer, message, tone = 'info') => {
  if (!toastContainer || !message) {
    return;
  }

  const toneClass = tone === 'error'
    ? 'border-rose-400/60 text-rose-100 bg-rose-900/45'
    : tone === 'success'
      ? 'border-emerald-400/60 text-emerald-100 bg-emerald-900/35'
      : 'border-neon-primary/50 text-gray-100 bg-dark-800/90';

  const toast = document.createElement('div');
  toast.className = `pointer-events-auto border ${toneClass} backdrop-blur rounded-lg px-4 py-3 shadow-lg transition-all duration-300 opacity-0 translate-y-2`;
  toast.textContent = message;

  toastContainer.appendChild(toast);
  requestAnimationFrame(() => {
    toast.classList.remove('opacity-0', 'translate-y-2');
  });

  setTimeout(() => {
    toast.classList.add('opacity-0', 'translate-y-2');
    setTimeout(() => {
      toast.remove();
    }, 260);
  }, 3200);
};

export const REQUEST_TIMEOUT_MS = 180000;

let workingOverlayRefCount = 0;
let workingOverlayElement = null;

const ensureWorkingOverlay = () => {
  if (workingOverlayElement && document.body.contains(workingOverlayElement)) {
    return workingOverlayElement;
  }

  const overlay = document.createElement('div');
  overlay.id = 'global-working-overlay';
  overlay.className = 'fixed inset-0 z-[100] bg-black/50 backdrop-blur-sm flex items-center justify-center';
  overlay.innerHTML = `
    <div class="bg-dark-800/95 border border-neon-primary/30 rounded-2xl p-6 neon-border min-w-[16rem]">
      <div class="flex items-center gap-3 text-gray-100">
        <span class="inline-block h-6 w-6 rounded-full border-2 border-neon-primary/40 border-t-neon-accent animate-spin"></span>
        <span id="global-working-message" class="font-medium">Working ...</span>
      </div>
    </div>
  `;
  document.body.appendChild(overlay);
  workingOverlayElement = overlay;
  return overlay;
};

export const showWorkingOverlay = (message = 'Working ...') => {
  const overlay = ensureWorkingOverlay();
  const messageEl = overlay.querySelector('#global-working-message');
  if (messageEl) {
    messageEl.textContent = message;
  }
  workingOverlayRefCount += 1;
  overlay.classList.remove('hidden');
};

export const hideWorkingOverlay = () => {
  if (workingOverlayRefCount > 0) {
    workingOverlayRefCount -= 1;
  }
  if (workingOverlayRefCount > 0) {
    return;
  }
  if (workingOverlayElement) {
    workingOverlayElement.classList.add('hidden');
  }
};

export const withWorkingOverlay = async (operation, message = 'Working ...') => {
  showWorkingOverlay(message);
  try {
    return await operation();
  } finally {
    hideWorkingOverlay();
  }
};

export const fetchWithTimeout = async (url, options = {}, timeoutMs = REQUEST_TIMEOUT_MS) => {
  const timeoutController = new AbortController();
  const externalSignal = options?.signal;
  const timeoutHandle = setTimeout(() => {
    timeoutController.abort(new DOMException('Timed out', 'AbortError'));
  }, timeoutMs);

  const abortExternal = () => timeoutController.abort(new DOMException('Aborted', 'AbortError'));
  if (externalSignal) {
    if (externalSignal.aborted) {
      abortExternal();
    } else {
      externalSignal.addEventListener('abort', abortExternal, { once: true });
    }
  }

  try {
    return await fetch(url, {
      ...options,
      signal: timeoutController.signal
    });
  } catch (error) {
    if (error?.name === 'AbortError') {
      throw new Error(`Request timed out after ${Math.ceil(timeoutMs / 1000)} seconds.`);
    }
    throw error;
  } finally {
    clearTimeout(timeoutHandle);
    if (externalSignal) {
      externalSignal.removeEventListener('abort', abortExternal);
    }
  }
};

const iconByAction = {
  start: '<svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true"><path d="M7 5v10l8-5-8-5Z"></path></svg>',
  stop: '<svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true"><rect x="5" y="5" width="10" height="10" rx="1.5"></rect></svg>',
  restart: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M15.5 9a5.5 5.5 0 1 0 1.3 3.6"></path><path d="M15.5 4.5V9h-4.5"></path></svg>',
  update: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M10 15.5v-8"></path><path d="m6.8 10.5 3.2-3.2 3.2 3.2"></path><rect x="4" y="4" width="12" height="12" rx="2"></rect></svg>',
  delete: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M3.5 5.5h13"></path><path d="M7.5 5.5V4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1.5"></path><path d="M6.5 7.5v8"></path><path d="M10 7.5v8"></path><path d="M13.5 7.5v8"></path><path d="M5 5.5l.7 11a1 1 0 0 0 1 .9h6.6a1 1 0 0 0 1-.9l.7-11"></path></svg>',
  console: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="2.5" y="4" width="15" height="12" rx="1.5"></rect><path d="M6 8.2 8.8 10 6 11.8"></path><path d="M10.3 12h3.7"></path></svg>',
  logs: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="4" y="3.5" width="12" height="13" rx="1.5"></rect><path d="M7 7.5h6"></path><path d="M7 10h6"></path><path d="M7 12.5h4"></path></svg>',
  plus: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M10 4.5v11"></path><path d="M4.5 10h11"></path></svg>',
  select: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M5 10.5 8.2 13.7 15 7"></path><path d="M16 10a6 6 0 1 1-2.2-4.6"></path></svg>',
  robot: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="5" y="6.5" width="10" height="8" rx="2"></rect><path d="M8.5 6.5V5a1.5 1.5 0 0 1 3 0v1.5"></path><circle cx="8" cy="10.5" r="0.8"></circle><circle cx="12" cy="10.5" r="0.8"></circle><path d="M3.8 9.8h1.2"></path><path d="M15 9.8h1.2"></path></svg>',
  person: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="10" cy="7" r="3"></circle><path d="M4.5 16a5.5 5.5 0 0 1 11 0"></path></svg>',
  handler: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="10" cy="10" r="6"></circle><path d="M10 6.2v7.6"></path><path d="M6.8 9.5h6.4"></path><path d="M7.6 13.8 10 11.4l2.4 2.4"></path></svg>',
  chevronDown: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m5.5 7.5 4.5 5 4.5-5"></path></svg>',
  male: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="8" cy="12" r="4"></circle><path d="M11 9l5-5"></path><path d="M12.8 4H16v3.2"></path></svg>',
  female: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="10" cy="7" r="4"></circle><path d="M10 11v6"></path><path d="M7 14h6"></path></svg>',
  neutral: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="10" cy="10" r="5"></circle><path d="M10 3.8v2.2"></path><path d="M10 14v2.2"></path></svg>',
  standalone: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="10" cy="10" r="6"></circle><path d="M10 4v12"></path><path d="M4 10h12"></path></svg>',
  robust: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M4 6.2 10 3l6 3.2-6 3.2L4 6.2Z"></path><path d="M4 10l6 3.2 6-3.2"></path><path d="M4 13.8 10 17l6-3.2"></path></svg>'
};

export const actionIconSvg = (action) => iconByAction[action] || '';

export const iconSpan = (action, className = 'h-4 w-4 inline-block align-middle shrink-0') => {
  const icon = actionIconSvg(action);
  return icon ? `<span class="${className}">${icon}</span>` : '';
};

export const consoleTargetForContainer = (containerIdOrName) => {
  const raw = String(containerIdOrName || '').trim();
  if (!raw) {
    return 'console-generic';
  }
  const safe = raw.replace(/[^a-zA-Z0-9_-]+/g, '-');
  return `console-${safe}`;
};

export const logsTargetForContainer = (containerIdOrName) => {
  const raw = String(containerIdOrName || '').trim();
  if (!raw) {
    return 'logs-generic';
  }
  const safe = raw.replace(/[^a-zA-Z0-9_-]+/g, '-');
  return `logs-${safe}`;
};

export const resolvePreferredConsole = (containers, preferredPrefix = '') => {
  const all = Array.isArray(containers) ? containers : [];
  const preferred = all.find((container) =>
    String(container?.containerName || '').startsWith(preferredPrefix));
  const fallback = all.find((container) => String(container?.containerName || '').length > 0);
  const resolved = preferred || fallback || null;
  if (!resolved) {
    return null;
  }

  const name = resolved.containerName || resolved.containerId || '';
  if (!name) {
    return null;
  }

  return {
    name,
    url: `/ui/console.html?container=${encodeURIComponent(name)}`,
    target: consoleTargetForContainer(resolved.containerId || name)
  };
};

export const renderContainerStatusRows = (containers) => {
  const all = Array.isArray(containers) ? containers : [];
  return all.map((container) => {
    const running = !!container.running;
    const dotColor = running ? 'bg-emerald-400' : 'bg-rose-400';
    const text = running ? 'Running' : (container.status || 'Stopped');
    const containerName = container.containerName || container.containerId;
    const consoleLink = buildConsoleIconLink(
      containerName,
      container.containerId || containerName,
      'Open console for container',
      'text-neon-accent hover:text-neon-secondary'
    );
    const logsLink = buildLogsIconLink(
      containerName,
      container.containerId || containerName,
      'Open logs for container',
      'text-sky-300 hover:text-sky-200'
    );
    return `
      <div class="flex items-center justify-between text-sm gap-3">
        <div class="min-w-0">
          <div class="text-gray-300 truncate" title="${containerName}">${containerName}</div>
          <div class="inline-flex items-center gap-2">${consoleLink}${logsLink}</div>
        </div>
        <span class="inline-flex items-center gap-2 text-gray-200 whitespace-nowrap"><span class="w-2 h-2 rounded-full ${dotColor}"></span>${text}</span>
      </div>
    `;
  }).join('');
};

export const buildConsoleIconLink = (
  containerName,
  targetSeed,
  label = 'Open console',
  className = 'text-neon-accent hover:text-neon-secondary'
) => {
  const resolvedName = String(containerName || '').trim();
  if (!resolvedName) {
    return '';
  }
  const target = consoleTargetForContainer(targetSeed || resolvedName);
  return `<a href="/ui/console.html?container=${encodeURIComponent(resolvedName)}" target="${target}" rel="noopener" aria-label="${label}" title="${label}" class="inline-flex items-center justify-center h-5 w-5 ${className}">${iconSpan('console')}</a>`;
};

export const buildLogsIconLink = (
  containerName,
  targetSeed,
  label = 'Open logs',
  className = 'text-sky-300 hover:text-sky-200'
) => {
  const resolvedName = String(containerName || '').trim();
  if (!resolvedName) {
    return '';
  }
  const target = logsTargetForContainer(targetSeed || resolvedName);
  return `<a href="/ui/logs.html?container=${encodeURIComponent(resolvedName)}" target="${target}" rel="noopener" aria-label="${label}" title="${label}" class="inline-flex items-center justify-center h-5 w-5 ${className}">${iconSpan('logs')}</a>`;
};
