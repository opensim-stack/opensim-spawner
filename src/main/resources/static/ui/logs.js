(() => {
  const common = window.SpawnerTerminalCommon;
  const containerNameEl = document.getElementById('logs-container-name');
  const autoScrollCheckbox = document.getElementById('logs-autoscroll');
  const downloadButton = document.getElementById('logs-download');
  const terminalHost = document.getElementById('terminal');
  const toastContainer = document.getElementById('toast-container');

  const LOG_BUFFER_LIMIT = 20000;
  const logBuffer = [];

  if (!common) {
    return;
  }

  const showToast = common.showToastFactory(toastContainer);
  const containerName = common.containerNameFromQuery();
  if (containerNameEl) {
    containerNameEl.textContent = containerName || '<missing>';
  }

  const terminalKit = common.createTerminal(terminalHost, {
    disableStdin: true,
    cursorBlink: false
  });
  if (!containerName || !terminalKit) {
    showToast('Missing required container query parameter or terminal dependencies.', 'error');
    if (downloadButton) {
      downloadButton.disabled = true;
    }
    return;
  }

  const { terminal } = terminalKit;

  const maybeAutoScroll = () => {
    if (autoScrollCheckbox?.checked) {
      terminal.scrollToBottom();
    }
  };

  const appendLogChunk = (data) => {
    if (typeof data !== 'string' || data.length === 0) {
      return;
    }
    terminal.write(data);
    logBuffer.push(data);
    if (logBuffer.length > LOG_BUFFER_LIMIT) {
      logBuffer.splice(0, logBuffer.length - LOG_BUFFER_LIMIT);
    }
    maybeAutoScroll();
  };

  const socket = new WebSocket(common.websocketUrlForContainer('/ui/ws/logs', containerName));

  socket.addEventListener('open', () => {
    terminal.writeln('\r\n[logs] WebSocket connected.');
    maybeAutoScroll();
  });

  socket.addEventListener('message', (event) => {
    const parsed = common.parseSocketMessageEvent(event);

    if (parsed.type === 'output') {
      appendLogChunk(parsed.data);
      return;
    }

    if (parsed.type === 'error') {
      const text = parsed.message || 'Log stream error';
      showToast(text, 'error');
      terminal.writeln(`\r\n[error] ${text}`);
      maybeAutoScroll();
      return;
    }

    if (parsed.message.trim()) {
      terminal.writeln(`\r\n[info] ${parsed.message}`);
      maybeAutoScroll();
    }
  });

  socket.addEventListener('close', () => {
    showToast('Log stream disconnected.', 'error');
    terminal.writeln('\r\n[logs] WebSocket closed.');
    maybeAutoScroll();
  });

  socket.addEventListener('error', () => {
    showToast('Log stream connection failed.', 'error');
  });

  downloadButton?.addEventListener('click', () => {
    if (!logBuffer.length) {
      showToast('No logs available to download yet.', 'error');
      return;
    }

    common.downloadTextFile(`${common.sanitizeForFileName(containerName)}.log`, logBuffer.join(''));
    showToast('Log buffer downloaded.', 'success');
  });

  window.addEventListener('resize', () => {
    terminalKit.fit();
    maybeAutoScroll();
  });
})();
