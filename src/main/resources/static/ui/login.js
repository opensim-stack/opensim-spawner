document.addEventListener('DOMContentLoaded', () => {
  const form = document.getElementById('login-form');
  const button = document.getElementById('login-btn');
  const errorEl = document.getElementById('login-error');

  if (!form || !button || !errorEl) {
    return;
  }

  const params = new URLSearchParams(window.location.search);
  const next = params.get('next') || '/ui/index.html';

  const shouldLaunchSetupWizard = async () => {
    try {
      const [simResponse, botResponse] = await Promise.all([
        fetch('/api/simulator'),
        fetch('/api/bot')
      ]);

      if (!simResponse.ok || !botResponse.ok) {
        return false;
      }

      const [simulators, bots] = await Promise.all([simResponse.json(), botResponse.json()]);
      const hasSimulators = Array.isArray(simulators) && simulators.length > 0;
      const hasBots = Array.isArray(bots) && bots.length > 0;
      return !hasSimulators && !hasBots;
    } catch (_err) {
      return false;
    }
  };

  const resolveNextTarget = async () => {
    const firstRun = await shouldLaunchSetupWizard();
    if (firstRun && !next.startsWith('/ui/setup.html')) {
      return '/ui/setup.html';
    }
    return next;
  };

  fetch('/ui/api/auth/status')
    .then((response) => response.ok ? response.json() : { authenticated: false })
    .then(async (status) => {
      if (status && status.authenticated === true) {
        window.location.href = await resolveNextTarget();
      }
    })
    .catch(() => {
      // Ignore status check errors and keep login form visible.
    });

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    errorEl.classList.add('hidden');
    button.disabled = true;

    const formData = new FormData(form);
    const payload = new URLSearchParams();
    payload.set('username', String(formData.get('username') || ''));
    payload.set('password', String(formData.get('password') || ''));

    try {
      const response = await fetch('/ui/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: payload.toString()
      });

      if (!response.ok) {
        const data = await response.json().catch(() => ({ error: 'Login failed.' }));
        throw new Error(data.error || 'Login failed.');
      }

      window.location.href = await resolveNextTarget();
    } catch (err) {
      errorEl.textContent = err instanceof Error ? err.message : 'Login failed.';
      errorEl.classList.remove('hidden');
    } finally {
      button.disabled = false;
    }
  });
});
