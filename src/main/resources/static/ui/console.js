(() => {
  const containerNameEl = document.getElementById('console-container-name');
  const commandInput = document.getElementById('console-command');
  const executeButton = document.getElementById('console-execute');
  const terminalHost = document.getElementById('terminal');
  const toastContainer = document.getElementById('toast-container');

  const showToast = (message, tone = 'info') => {
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

  const query = new URLSearchParams(window.location.search);
  const containerName = (query.get('container') || '').trim();
  if (containerNameEl) {
    containerNameEl.textContent = containerName || '<missing>';
  }

  if (!containerName || !terminalHost || !window.Terminal || !window.FitAddon) {
    showToast('Missing required container query parameter or terminal dependencies.', 'error');
    if (executeButton) {
      executeButton.disabled = true;
    }
    return;
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
    }
  });
  const fitAddon = new window.FitAddon.FitAddon();
  terminal.loadAddon(fitAddon);
  terminal.open(terminalHost);
  fitAddon.fit();

  let socket = null;

  const sendMessage = (payload) => {
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      return false;
    }
    socket.send(JSON.stringify(payload));
    return true;
  };

  const sendResize = () => {
    fitAddon.fit();
    sendMessage({
      type: 'resize',
      cols: terminal.cols,
      rows: terminal.rows
    });
  };

  const startCommand = () => {
    const command = (commandInput?.value || '/bin/bash').trim() || '/bin/bash';
    const ok = sendMessage({
      type: 'start',
      command,
      cols: terminal.cols,
      rows: terminal.rows
    });

    if (!ok) {
      showToast('Console socket is not connected.', 'error');
    }
  };

  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new WebSocket(`${protocol}://${window.location.host}/ui/ws/console?container=${encodeURIComponent(containerName)}`);

  socket.addEventListener('open', () => {
    terminal.writeln('\r\n[console] WebSocket connected.');
    sendResize();
    startCommand();
  });

  socket.addEventListener('message', (event) => {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch (e) {
      terminal.write(String(event.data || ''));
      return;
    }

    if (message.type === 'output' && typeof message.data === 'string') {
      terminal.write(message.data);
      return;
    }

    if (message.type === 'error') {
      showToast(String(message.message || 'Console error'), 'error');
      terminal.writeln(`\r\n[error] ${String(message.message || 'Console error')}`);
      return;
    }

    if (message.type === 'started') {
      showToast(String(message.message || 'Command started.'), 'success');
      return;
    }

    if (typeof message.message === 'string') {
      terminal.writeln(`\r\n[info] ${message.message}`);
    }
  });

  socket.addEventListener('close', () => {
    showToast('Console disconnected.', 'error');
    terminal.writeln('\r\n[console] WebSocket closed.');
  });

  socket.addEventListener('error', () => {
    showToast('Console connection failed.', 'error');
  });

  terminal.onData((data) => {
    sendMessage({ type: 'input', data });
  });

  window.addEventListener('resize', () => {
    sendResize();
  });

  executeButton?.addEventListener('click', () => {
    startCommand();
  });

  commandInput?.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      startCommand();
    }
  });
})();
