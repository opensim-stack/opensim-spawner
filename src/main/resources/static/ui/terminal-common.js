(() => {
  const showToastFactory = (toastContainer) => (message, tone = 'info') => {
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
      setTimeout(() => toast.remove(), 260);
    }, 3200);
  };

  const createTerminal = (terminalHost, options = {}) => {
    if (!terminalHost || !window.Terminal || !window.FitAddon) {
      return null;
    }

    const terminal = new window.Terminal({
      fontFamily: 'JetBrains Mono, Fira Code, monospace',
      fontSize: 14,
      convertEol: true,
      cursorBlink: true,
      theme: {
        background: '#080811',
        foreground: '#e5e7eb',
        cursor: '#8b5cf6'
      },
      ...options
    });

    const fitAddon = new window.FitAddon.FitAddon();
    terminal.loadAddon(fitAddon);
    terminal.open(terminalHost);
    fitAddon.fit();

    return {
      terminal,
      fitAddon,
      fit: () => fitAddon.fit()
    };
  };

  const containerNameFromQuery = () => {
    const query = new URLSearchParams(window.location.search);
    return (query.get('container') || '').trim();
  };

  const websocketUrlForContainer = (path, containerName) => {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    return `${protocol}://${window.location.host}${path}?container=${encodeURIComponent(containerName)}`;
  };

  const toNonEmptyString = (value) => {
    if (value === null || value === undefined) {
      return '';
    }
    const text = String(value);
    return text;
  };

  const parseSocketMessageEvent = (event) => {
    const rawText = toNonEmptyString(event?.data);
    let payload;
    try {
      payload = JSON.parse(rawText);
    } catch (_ignored) {
      return {
        isJson: false,
        type: 'output',
        data: rawText,
        message: ''
      };
    }

    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
      return {
        isJson: false,
        type: 'output',
        data: rawText,
        message: ''
      };
    }

    const normalizedType = toNonEmptyString(payload.type).trim().toLowerCase();
    const data = toNonEmptyString(payload.data);
    const message = toNonEmptyString(payload.message);

    const type = normalizedType
      || (data ? 'output' : '')
      || (message ? 'info' : 'info');

    return {
      isJson: true,
      type,
      data,
      message
    };
  };

  const sanitizeForFileName = (value, fallback = 'container') => {
    const sanitized = String(value || '')
      .trim()
      .replace(/[^a-zA-Z0-9._-]+/g, '-')
      .replace(/^-+|-+$/g, '');
    return sanitized || fallback;
  };

  const downloadTextFile = (fileName, content) => {
    const blob = new Blob([String(content || '')], { type: 'text/plain;charset=utf-8' });
    const downloadUrl = URL.createObjectURL(blob);

    const link = document.createElement('a');
    link.href = downloadUrl;
    link.download = fileName;
    document.body.appendChild(link);
    link.click();
    link.remove();

    URL.revokeObjectURL(downloadUrl);
  };

  window.SpawnerTerminalCommon = {
    showToastFactory,
    createTerminal,
    containerNameFromQuery,
    websocketUrlForContainer,
    parseSocketMessageEvent,
    sanitizeForFileName,
    downloadTextFile
  };
})();
