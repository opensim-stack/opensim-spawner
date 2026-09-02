document.addEventListener('DOMContentLoaded', () => {
  const form = document.getElementById('login-form');
  const button = document.getElementById('login-btn');
  const errorEl = document.getElementById('login-error');
  const gridServicePill = document.getElementById('login-grid-service-pill');

  if (!form || !button || !errorEl) {
    return;
  }

  const params = new URLSearchParams(window.location.search);
  const next = params.get('next') || '/ui/index.html';

  const setGridServiceBadge = (available) => {
    if (!gridServicePill) {
      return;
    }
    gridServicePill.className = `mb-6 inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs ${available
      ? 'border-emerald-400/40 bg-emerald-500/10 text-emerald-200'
      : 'border-amber-400/40 bg-amber-500/10 text-amber-200'}`;
    gridServicePill.innerHTML = available
      ? '<span class="w-2 h-2 rounded-full bg-emerald-300"></span><span>Grid Service: Online</span>'
      : '<span class="w-2 h-2 rounded-full bg-amber-300"></span><span>Grid Service: Unavailable</span>';
  };

  const fetchGridServiceStatus = async () => {
    try {
      const response = await fetch('/ui/api/auth/grid-status');
      if (!response.ok) {
        setGridServiceBadge(false);
        return;
      }
      const payload = await response.json();
      setGridServiceBadge(!!payload?.available);
    } catch (_err) {
      setGridServiceBadge(false);
    }
  };

  const fetchSetupStatus = async () => {
    try {
      const response = await fetch('/ui/api/setup/status');
      if (!response.ok) {
        return { guided: false, required: false };
      }
      const payload = await response.json();
      return {
        guided: !!payload?.guided,
        required: !!payload?.required
      };
    } catch (_err) {
      return { guided: false, required: false };
    }
  };

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

  const resolveNextTarget = async (status) => {
    if (status?.admin === false) {
      return '/ui/change-password.html';
    }
    const firstRun = await shouldLaunchSetupWizard();
    if (firstRun && !next.startsWith('/ui/setup.html')) {
      return '/ui/setup.html';
    }
    return next;
  };

  fetchGridServiceStatus();

  fetchSetupStatus()
    .then((setup) => {
      if (setup.guided && setup.required) {
        window.location.href = '/ui/setup.html';
      }
    })
    .catch(() => {
      // Ignore setup status errors and keep login form visible.
    });

  fetch('/ui/api/auth/status')
    .then((response) => response.ok ? response.json() : { authenticated: false })
    .then(async (status) => {
      if (status && status.authenticated === true) {
        window.location.href = await resolveNextTarget(status);
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
    const formPayload = new URLSearchParams();
    formPayload.set('username', String(formData.get('username') || ''));
    formPayload.set('password', String(formData.get('password') || ''));

    try {
      const response = await fetch('/ui/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: formPayload.toString()
      });

      if (!response.ok) {
        const data = await response.json().catch(() => ({ error: 'Login failed.' }));
        throw new Error(data.error || 'Login failed.');
      }

      const authPayload = await response.json().catch(() => ({ admin: true }));
      window.location.href = await resolveNextTarget(authPayload);
    } catch (err) {
      errorEl.textContent = err instanceof Error ? err.message : 'Login failed.';
      errorEl.classList.remove('hidden');
    } finally {
      button.disabled = false;
    }
  });
});
