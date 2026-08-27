document.addEventListener('DOMContentLoaded', () => {
  const form = document.getElementById('register-form');
  const button = document.getElementById('register-btn');
  const errorEl = document.getElementById('register-error');
  const successEl = document.getElementById('register-success');

  if (!form || !button || !errorEl || !successEl) {
    return;
  }

  const namePattern = /^[a-z0-9]+$/i;

  const showError = (message) => {
    successEl.classList.add('hidden');
    successEl.textContent = '';
    errorEl.textContent = message;
    errorEl.classList.remove('hidden');
  };

  const showSuccess = (message) => {
    errorEl.classList.add('hidden');
    errorEl.textContent = '';
    successEl.textContent = message;
    successEl.classList.remove('hidden');
  };

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    errorEl.classList.add('hidden');
    successEl.classList.add('hidden');
    button.disabled = true;

    const first = String(document.getElementById('register-first')?.value || '').trim();
    const last = String(document.getElementById('register-last')?.value || '').trim();
    const email = String(document.getElementById('register-email')?.value || '').trim();
    const password = String(document.getElementById('register-password')?.value || '');
    const confirm = String(document.getElementById('register-password-confirm')?.value || '');

    if (!first || !last || !email || !password) {
      showError('All fields are required.');
      button.disabled = false;
      return;
    }

    if (!namePattern.test(first) || !namePattern.test(last)) {
      showError('First and last name must be alphanumeric only.');
      button.disabled = false;
      return;
    }

    if (password !== confirm) {
      showError('Password and confirm password must match.');
      button.disabled = false;
      return;
    }

    const payload = new URLSearchParams();
    payload.set('first', first);
    payload.set('last', last);
    payload.set('email', email);
    payload.set('password', password);

    try {
      const response = await fetch('/ui/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: payload.toString()
      });

      if (!response.ok) {
        const data = await response.json().catch(() => ({ error: 'Registration failed.' }));
        throw new Error(data.error || 'Registration failed.');
      }

      form.reset();
      showSuccess('Approval submitted. An administrator can now approve your account request.');
    } catch (err) {
      showError(err instanceof Error ? err.message : 'Registration failed.');
    } finally {
      button.disabled = false;
    }
  });
});
