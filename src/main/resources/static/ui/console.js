(() => {
  const common = window.SpawnerTerminalCommon;
  const containerNameEl = document.getElementById('console-container-name');
  const commandInput = document.getElementById('console-command');
  const executeButton = document.getElementById('console-execute');
  const terminalHost = document.getElementById('terminal');
  const toastContainer = document.getElementById('toast-container');

  if (!common) {
    return;
  }

  const showToast = common.showToastFactory(toastContainer);
  const containerName = common.containerNameFromQuery();
  if (containerNameEl) {
    containerNameEl.textContent = containerName || '<missing>';
  }

  const terminalKit = common.createTerminal(terminalHost, { cursorBlink: true });
  if (!containerName || !terminalKit) {
    showToast('Missing required container query parameter or terminal dependencies.', 'error');
    if (executeButton) {
      executeButton.disabled = true;
    }
    return;
  }

  const { terminal } = terminalKit;

  let socket = null;

  const sendMessage = (payload) => {
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      return false;
    }
    socket.send(JSON.stringify(payload));
    return true;
  };

  const sendResize = () => {
    terminalKit.fit();
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

  socket = new WebSocket(common.websocketUrlForContainer('/ui/ws/console', containerName));

  socket.addEventListener('open', () => {
    terminal.writeln('\r\n[console] WebSocket connected.');
    sendResize();
    startCommand();
  });

  socket.addEventListener('message', (event) => {
    const parsed = common.parseSocketMessageEvent(event);

    if (parsed.type === 'output' && parsed.data) {
      terminal.write(parsed.data);
      return;
    }

    if (parsed.type === 'error') {
      const text = parsed.message || 'Console error';
      showToast(text, 'error');
      terminal.writeln(`\r\n[error] ${text}`);
      return;
    }

    if (parsed.type === 'started') {
      showToast(parsed.message || 'Command started.', 'success');
      return;
    }

    if (parsed.message) {
      terminal.writeln(`\r\n[info] ${parsed.message}`);
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
