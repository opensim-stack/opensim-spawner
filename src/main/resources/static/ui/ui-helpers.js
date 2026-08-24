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

const iconByAction = {
  start: '<svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true"><path d="M7 5v10l8-5-8-5Z"></path></svg>',
  stop: '<svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true"><rect x="5" y="5" width="10" height="10" rx="1.5"></rect></svg>',
  restart: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M15.5 9a5.5 5.5 0 1 0 1.3 3.6"></path><path d="M15.5 4.5V9h-4.5"></path></svg>',
  delete: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M3.5 5.5h13"></path><path d="M7.5 5.5V4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1.5"></path><path d="M6.5 7.5v8"></path><path d="M10 7.5v8"></path><path d="M13.5 7.5v8"></path><path d="M5 5.5l.7 11a1 1 0 0 0 1 .9h6.6a1 1 0 0 0 1-.9l.7-11"></path></svg>',
  console: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="2.5" y="4" width="15" height="12" rx="1.5"></rect><path d="M6 8.2 8.8 10 6 11.8"></path><path d="M10.3 12h3.7"></path></svg>',
  plus: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M10 4.5v11"></path><path d="M4.5 10h11"></path></svg>'
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
    return `
      <div class="flex items-center justify-between text-sm gap-3">
        <div class="min-w-0">
          <div class="text-gray-300 truncate" title="${containerName}">${containerName}</div>
          ${consoleLink}
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
