class SpawnerSideMenu extends HTMLElement {
  connectedCallback() {
    const active = this.dataset.active || '';

    this.innerHTML = `
      <aside class="w-72 max-w-[85vw] shrink-0 bg-dark-800/90 backdrop-blur border-r border-neon-primary/20 p-4 flex flex-col min-h-screen">
        <a href="/" class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-dark-700 transition-colors">
          <img src="/ui/opensim-stack.png" alt="OpenSim AI Stack" class="h-10 w-auto">
          <span class="font-bold gradient-text">OpenSim AI Stack</span>
        </a>

        <nav class="mt-6 space-y-2">
          ${this.menuItem('/ui/bots.html', 'Bots', active === 'bots')}
          ${this.menuItem('/ui/stack.html', 'Stack', active === 'stack')}
          ${this.menuItem('/ui/simulators.html', 'Simulators', active === 'simulators')}
        </nav>

        <div class="mt-auto pt-6">
          <button id="menu-signout" class="w-full px-4 py-2 rounded-lg border border-rose-400/50 text-rose-200 hover:bg-rose-500/10 transition-colors">
            Sign Out
          </button>
        </div>
      </aside>
    `;

    const signoutBtn = this.querySelector('#menu-signout');
    if (signoutBtn) {
      signoutBtn.addEventListener('click', async () => {
        await fetch('/ui/api/auth/logout', { method: 'POST' });
        window.location.href = '/ui/login.html';
      });
    }
  }

  menuItem(href, label, isActive) {
    const base = 'block px-3 py-2 rounded-lg transition-colors';
    const state = isActive
      ? 'bg-neon-primary/20 border border-neon-primary/40 text-white'
      : 'text-gray-200 hover:bg-dark-700 hover:text-neon-primary border border-transparent';
    return `<a href="${href}" class="${base} ${state}">${label}</a>`;
  }
}

customElements.define('spawner-side-menu', SpawnerSideMenu);
