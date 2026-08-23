document.addEventListener('DOMContentLoaded', () => {
  const form = document.getElementById('login-form');
  const button = document.getElementById('login-btn');
  const errorEl = document.getElementById('login-error');

  if (!form || !button || !errorEl) {
    return;
  }

  const params = new URLSearchParams(window.location.search);
  const next = params.get('next') || '/ui/index.html';

  fetch('/ui/api/auth/status')
    .then((response) => response.ok ? response.json() : { authenticated: false })
    .then((status) => {
      if (status && status.authenticated === true) {
        window.location.href = next;
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

      window.location.href = next;
    } catch (err) {
      errorEl.textContent = err instanceof Error ? err.message : 'Login failed.';
      errorEl.classList.remove('hidden');
    } finally {
      button.disabled = false;
    }
  });
});
