class SpawnerSideMenu extends HTMLElement {
  connectedCallback() {
    const active = this.dataset.active || '';
    const navMarkup = '';

    this.innerHTML = `
      <aside class="w-72 max-w-[85vw] shrink-0 bg-dark-800/90 backdrop-blur border-r border-neon-primary/20 p-4 flex flex-col min-h-screen">
        <a href="/" class="px-3 py-2 rounded-lg hover:bg-dark-700 transition-colors block">
          <div class="flex items-center gap-3">
            <img src="/ui/opensim-stack.png" alt="OpenSim AI Stack" class="h-10 w-auto">
            <span class="font-bold gradient-text">OpenSim AI Stack</span>
          </div>
          <div class="mt-1 pl-[3.25rem] leading-tight">
            <div id="menu-grid-name" class="text-[11px] text-gray-300/80 truncate"></div>
            <div id="menu-grid-nick" class="text-[10px] text-gray-400/60 truncate"></div>
          </div>
        </a>

        <nav id="menu-nav" class="mt-6 space-y-2">${navMarkup}</nav>

        <div class="mt-auto pt-6">
          <button id="menu-signout" class="w-full px-4 py-2 rounded-lg border border-rose-400/50 text-rose-200 hover:bg-rose-500/10 transition-colors inline-flex items-center gap-2 justify-center">
            <span class="h-4 w-4">${this.icon('signout')}</span>
            <span>Sign Out</span>
          </button>
        </div>
      </aside>
    `;

    this.loadGridLabels();
    this.loadRoleMenu(active);

    const signoutBtn = this.querySelector('#menu-signout');
    if (signoutBtn) {
      signoutBtn.addEventListener('click', async () => {
        await fetch('/ui/api/auth/logout', { method: 'POST' });
        window.location.href = '/ui/login.html';
      });
    }
  }

  adminMenu(active) {
    return `${this.menuItem('/ui/bots.html', 'Bots', 'bots', active === 'bots')}
      ${this.menuItem('/ui/stack.html', 'Stack', 'stack', active === 'stack')}
      ${this.menuItem('/ui/simulators.html', 'Simulators', 'simulators', active === 'simulators')}
      ${this.menuItem('/ui/users.html', 'Users', 'users', active === 'users' || active === 'create-user' || active === 'approvals')}
      ${this.menuItem('/ui/create-user.html', 'Create User', 'create-user', active === 'create-user', true)}
      ${this.menuItem('/ui/approvals.html', 'Approvals', 'approvals', active === 'approvals', true)}`;
  }

  userMenu(active) {
    return this.menuItem('/ui/change-password.html', 'Change Password', 'change-password', active === 'change-password');
  }

  async loadRoleMenu(active) {
    const nav = this.querySelector('#menu-nav');
    if (!nav) {
      return;
    }

    try {
      const response = await fetch('/ui/api/auth/status');
      if (!response.ok) {
        return;
      }
      const status = await response.json();
      nav.innerHTML = status?.admin === true ? this.adminMenu(active) : this.userMenu(active);
    } catch (_err) {
      nav.innerHTML = this.adminMenu(active);
    }
  }

  menuItem(href, label, iconKey, isActive, isSubItem = false) {
    const base = `px-3 py-2 rounded-lg transition-colors flex items-center gap-2 ${isSubItem ? 'ml-6 text-sm' : ''}`;
    const state = isActive
      ? 'bg-neon-primary/20 border border-neon-primary/40 text-white'
      : 'text-gray-200 hover:bg-dark-700 hover:text-neon-primary border border-transparent';
    return `<a href="${href}" class="${base} ${state}"><span class="h-4 w-4">${this.icon(iconKey)}</span><span>${label}</span></a>`;
  }

  async loadGridLabels() {
    const nameEl = this.querySelector('#menu-grid-name');
    const nickEl = this.querySelector('#menu-grid-nick');
    if (!nameEl || !nickEl) {
      return;
    }

    try {
      const response = await fetch('/ui/api/config');
      if (!response.ok) {
        return;
      }
      const config = await response.json();
      const gridName = String(config?.gridName || '').trim();
      const gridNick = String(config?.gridNick || '').trim();
      if (gridName) {
        nameEl.textContent = gridName;
      }
      if (gridNick) {
        nickEl.textContent = gridNick;
      }
    } catch (_err) {
      // Menu metadata is optional; keep default branding when unavailable.
    }
  }

  icon(key) {
    switch (key) {
      case 'bots':
        return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="5" y="7" width="10" height="8" rx="2"></rect><path d="M8 7V5.8a2 2 0 0 1 4 0V7"></path><circle cx="8" cy="11" r="1"></circle><circle cx="12" cy="11" r="1"></circle><path d="M3.5 9.5h1.5"></path><path d="M15 9.5h1.5"></path></svg>';
      case 'stack':
        return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M4 6.2 10 3l6 3.2-6 3.2L4 6.2Z"></path><path d="M4 10l6 3.2 6-3.2"></path><path d="M4 13.8 10 17l6-3.2"></path></svg>';
      case 'simulators':
        return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="10" cy="10" r="6"></circle><path d="M10 4v12"></path><path d="M4 10h12"></path></svg>';
      case 'users':
        return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="10" cy="7" r="3"></circle><path d="M4.5 16a5.5 5.5 0 0 1 11 0"></path></svg>';
      case 'create-user':
        return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="8" cy="8" r="3"></circle><path d="M3.5 16a4.5 4.5 0 0 1 9 0"></path><path d="M14 6v5"></path><path d="M11.5 8.5h5"></path></svg>';
      case 'approvals':
        return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="4" y="3.5" width="12" height="13" rx="1.5"></rect><path d="M7 7.5h6"></path><path d="M7 10h4"></path><path d="M7.5 13l1.4 1.4L12.5 11"></path></svg>';
      case 'change-password':
        return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="4" y="8" width="12" height="8" rx="2"></rect><path d="M7 8V6.8a3 3 0 0 1 6 0V8"></path><circle cx="10" cy="12" r="1.1"></circle></svg>';
      case 'signout':
        return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M8 4.5H5.5A1.5 1.5 0 0 0 4 6v8a1.5 1.5 0 0 0 1.5 1.5H8"></path><path d="M12 6.5 16 10l-4 3.5"></path><path d="M16 10H8"></path></svg>';
      default:
        return '';
    }
  }
}

customElements.define('spawner-side-menu', SpawnerSideMenu);
