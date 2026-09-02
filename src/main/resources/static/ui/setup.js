import { fetchWithTimeout, iconSpan, showToast, withWorkingOverlay } from '/ui/ui-helpers.js';

const STEPS = [
  { number: 1, title: 'Grid Mode' },
  { number: 2, title: 'Bot Details' },
  { number: 3, title: 'User Details' },
  { number: 4, title: 'Region Details' },
  { number: 5, title: 'Administrator User' },
  { number: 6, title: 'Summary' }
];

const modeDescriptions = {
  STANDALONE: 'Standalone mode is perfect for a small private grid, or if you are just getting started with your journey into the Metaverse, and you dont yet want to join the <strong>Hypergrid</strong>.',
  ROBUST: 'For the more experienced, use Robust for a larger grid. You can have multiple <em>Grid</em> simulators each with multiple regions.'
};

const PERSON_NAME_PATTERN = /^[a-z0-9]+$/i;
const SIMULATOR_NAME_PATTERN = /^[a-z0-9_-]+$/i;
const MIN_PASSWORD_LENGTH = 8;
const REQUEST_RECOVERY_WINDOW_MS = 180000;
const REQUEST_RECOVERY_POLL_MS = 3000;

const stepLabel = document.getElementById('wizard-step-label');
const stepTitle = document.getElementById('wizard-step-title');
const stepError = document.getElementById('wizard-error');
const toastContainer = document.getElementById('toast-container');
const prevButton = document.getElementById('wizard-prev');
const nextButton = document.getElementById('wizard-next');
const nextButtonLabel = document.getElementById('wizard-next-label');
const nextButtonSpinner = document.getElementById('wizard-next-spinner');
const skipLink = document.getElementById('wizard-skip-link');

const modeDescription = document.getElementById('wizard-mode-description');
const gridModeGroup = document.getElementById('wizard-grid-mode-group');
const simulatorName = document.getElementById('wizard-simulator-name');
const gridName = document.getElementById('wizard-grid-name');
const gridNick = document.getElementById('wizard-grid-nick');
const gridWelcomeMessage = document.getElementById('wizard-grid-welcome-message');

const createBotToggle = document.getElementById('wizard-create-bot');
const botFields = document.getElementById('wizard-bot-fields');
const botFirst = document.getElementById('wizard-bot-first');
const botLast = document.getElementById('wizard-bot-last');
const botEmail = document.getElementById('wizard-bot-email');
const botAppearance = document.getElementById('wizard-bot-appearance');
const botGenderGroup = document.getElementById('wizard-bot-gender-group');

const handlerInfo = document.getElementById('wizard-handler-info');
const userFirst = document.getElementById('wizard-user-first');
const userLast = document.getElementById('wizard-user-last');
const userEmail = document.getElementById('wizard-user-email');
const userPassword = document.getElementById('wizard-user-password');
const userPasswordConfirm = document.getElementById('wizard-user-password-confirm');

const regionSimulatorName = document.getElementById('wizard-region-sim-name');
const regionPort = document.getElementById('wizard-region-port');
const regionX = document.getElementById('wizard-region-x');
const regionY = document.getElementById('wizard-region-y');
const regionOar = document.getElementById('wizard-region-oar');

const adminUser = document.getElementById('wizard-admin-user');
const adminPassword = document.getElementById('wizard-admin-password');
const adminPasswordConfirm = document.getElementById('wizard-admin-password-confirm');

const summaryContainer = document.getElementById('wizard-summary');

let currentStep = 1;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const hideError = () => {
  if (!stepError) {
    return;
  }
  stepError.textContent = '';
  stepError.classList.add('hidden');
};

const showError = (message) => {
  if (!stepError) {
    return;
  }
  stepError.textContent = message;
  stepError.classList.remove('hidden');
};

const selectedGridMode = () => {
  const selected = document.querySelector('input[name="wizard-grid-mode"]:checked');
  return selected instanceof HTMLInputElement ? String(selected.value || 'STANDALONE').toUpperCase() : 'STANDALONE';
};

const renderIconChoiceGroup = ({
  container,
  name,
  options,
  defaultValue,
  cardClassName,
  selectedClassName,
  iconClassName,
  labelClassName,
  descriptionClassName,
  iconWrapClassName
}) => {
  if (!container) {
    return;
  }

  container.innerHTML = options.map((option) => {
    const description = option.description
      ? `<span class="${descriptionClassName}">${option.description}</span>`
      : '';
    return `
      <label class="${cardClassName}">
        <input name="${name}" type="radio" value="${option.value}" class="peer sr-only" ${option.value === defaultValue ? 'checked' : ''}>
        <span class="${selectedClassName}">
          <span class="${iconWrapClassName}">
            ${iconSpan(option.icon, iconClassName)}
          </span>
          <span>
            <span class="${labelClassName}">${option.label}</span>
            ${description}
          </span>
        </span>
      </label>
    `;
  }).join('');
};

const renderGridModeOptions = () => {
  renderIconChoiceGroup({
    container: gridModeGroup,
    name: 'wizard-grid-mode',
    defaultValue: 'STANDALONE',
    options: [
    {
      value: 'STANDALONE',
      label: 'Standalone',
      description: 'Single simulator with region and login service.',
      icon: 'standalone'
    },
    {
      value: 'ROBUST',
      label: 'Robust',
      description: 'Larger grid setup with dedicated grid services.',
      icon: 'robust'
    }
    ],
    cardClassName: 'cursor-pointer rounded-xl border border-neon-primary/30 bg-dark-700/70 p-5 transition-colors hover:border-neon-primary/60',
    selectedClassName: 'flex items-center gap-4 rounded-lg border border-transparent p-1 peer-checked:border-neon-accent peer-checked:bg-neon-accent/10',
    iconClassName: 'h-9 w-9 inline-block align-middle shrink-0',
    labelClassName: 'block text-2xl font-semibold text-white',
    descriptionClassName: 'text-sm text-gray-300',
    iconWrapClassName: 'inline-flex h-16 w-16 items-center justify-center rounded-xl border border-neon-primary/40 bg-neon-primary/15 text-neon-primary'
  });
};

const selectedBotGender = () => {
  const selected = document.querySelector('input[name="wizard-bot-gender"]:checked');
  return selected instanceof HTMLInputElement ? selected.value : 'neutral';
};

const renderGenderOptions = () => {
  renderIconChoiceGroup({
    container: botGenderGroup,
    name: 'wizard-bot-gender',
    defaultValue: 'neutral',
    options: [
      { value: 'male', label: 'Male', icon: 'male' },
      { value: 'female', label: 'Female', icon: 'female' },
      { value: 'neutral', label: 'Neutral', icon: 'neutral' }
    ],
    cardClassName: 'cursor-pointer',
    selectedClassName: 'inline-flex w-full items-center justify-center gap-2 rounded-lg border border-neon-primary/30 bg-dark-700 px-3 py-2 text-gray-200 transition-colors peer-checked:border-neon-accent peer-checked:bg-neon-accent/20 peer-checked:text-neon-accent hover:border-neon-primary/60',
    iconClassName: 'h-6 w-6 inline-block align-middle shrink-0',
    labelClassName: 'text-sm',
    descriptionClassName: '',
    iconWrapClassName: ''
  });
};

const normalizeIdentityFirstName = () => String(simulatorName?.value || '').replace(/\s+/g, '').trim();

const suggestedRegionSimulatorName = () => {
  const base = String(simulatorName?.value || '').trim();
  if (!base) {
    return '';
  }
  return selectedGridMode() === 'ROBUST' ? `${base}-grid` : base;
};

const wizardState = () => {
  const mode = selectedGridMode();
  const primaryName = String(simulatorName?.value || '').trim();
  const gridNameValue = String(gridName?.value || '').trim();
  const gridNickValue = String(gridNick?.value || '').trim();
  const gridWelcomeMessageValue = String(gridWelcomeMessage?.value || '').trim();
  const adminUserValue = String(adminUser?.value || '').trim();
  const adminPasswordValue = String(adminPassword?.value || '');
  const createBot = !!createBotToggle?.checked;
  const regionName = String(regionSimulatorName?.value || primaryName).trim();
  const normalizedFirst = normalizeIdentityFirstName();
  const botFirstValue = String(botFirst?.value || '').trim() || normalizedFirst;
  const botLastValue = String(botLast?.value || '').trim() || 'Bot';
  const userFirstValue = String(userFirst?.value || '').trim() || normalizedFirst;
  const userLastValue = String(userLast?.value || '').trim() || 'User';

  return {
    mode,
    grid: {
      name: gridNameValue,
      nick: gridNickValue,
      welcomeMessage: gridWelcomeMessageValue
    },
    admin: {
      username: adminUserValue,
      password: adminPasswordValue
    },
    simulator: {
      primaryName,
      regionName,
      name: regionName,
      level: mode === 'ROBUST' ? 'GRID' : 'STANDALONE',
      port: String(regionPort?.value || '').trim(),
      regionX: String(regionX?.value || '').trim() || '1000',
      regionY: String(regionY?.value || '').trim() || '1000',
      oar: String(regionOar?.value || '').trim()
    },
    bot: {
      create: createBot,
      level: 'ACTOR',
      first: botFirstValue,
      last: botLastValue,
      email: String(botEmail?.value || '').trim(),
      appearance: String(botAppearance?.value || '').trim(),
      gender: selectedBotGender()
    },
    user: {
      first: userFirstValue,
      last: userLastValue,
      email: String(userEmail?.value || '').trim(),
      password: String(userPassword?.value || ''),
      botHandler: createBot
    }
  };
};

const syncNamePlaceholders = () => {
  const normalizedFirst = normalizeIdentityFirstName();
  if (botFirst) {
    botFirst.placeholder = normalizedFirst;
  }
  if (botLast) {
    botLast.placeholder = 'Bot';
  }
  if (userFirst) {
    userFirst.placeholder = normalizedFirst;
  }
  if (userLast) {
    userLast.placeholder = 'User';
  }
};

const isValidEmail = (value) => {
  const email = String(value || '').trim();
  if (!email) {
    return false;
  }
  const validator = document.createElement('input');
  validator.type = 'email';
  validator.value = email;
  return validator.checkValidity();
};

const isIntegerString = (value) => /^-?\d+$/.test(String(value || '').trim());

const setBusy = (busy) => {
  if (!nextButton || !nextButtonLabel || !nextButtonSpinner) {
    return;
  }
  nextButton.disabled = !!busy;
  prevButton.disabled = !!busy;
  nextButton.classList.toggle('opacity-60', !!busy);
  nextButton.classList.toggle('cursor-not-allowed', !!busy);
  nextButtonSpinner.classList.toggle('hidden', !busy);
  nextButtonLabel.textContent = currentStep === STEPS.length ? 'Finishing' : 'Next';
};

const applyModeDescription = () => {
  if (!modeDescription) {
    return;
  }
  modeDescription.innerHTML = modeDescriptions[selectedGridMode()] || '';
};

const syncBotFieldState = () => {
  const enabled = !!createBotToggle?.checked;
  botFields?.classList.toggle('opacity-60', !enabled);
  botFields?.querySelectorAll('input, select').forEach((field) => {
    field.disabled = !enabled;
  });
  handlerInfo?.classList.toggle('hidden', !enabled);
};

const updateRegionSimulatorName = () => {
  const suggested = suggestedRegionSimulatorName();
  if (!regionSimulatorName || !suggested || String(regionSimulatorName.value || '').trim()) {
    return;
  }
  regionSimulatorName.value = suggested;
};

const formatMaybe = (value) => {
  const normalized = String(value || '').trim();
  return normalized || 'n/a';
};

const renderSummary = () => {
  if (!summaryContainer) {
    return;
  }
  const state = wizardState();

  const botBlock = state.bot.create
    ? `<div class="rounded-lg border border-neon-primary/20 bg-dark-700/30 p-4">
        <h3 class="text-lg font-semibold text-white">Bot</h3>
        <p class="mt-2 text-sm text-gray-300">${state.bot.first} ${state.bot.last}</p>
        <p class="text-sm text-gray-400">Level: ${state.bot.level}</p>
        <p class="text-sm text-gray-400">Appearance: ${formatMaybe(state.bot.appearance)}</p>
        <p class="text-sm text-gray-400">Gender: ${formatMaybe(state.bot.gender)}</p>
      </div>`
    : `<div class="rounded-lg border border-neon-primary/20 bg-dark-700/30 p-4 text-sm text-gray-300">Bot creation is disabled.</div>`;

  summaryContainer.innerHTML = `
    <div class="rounded-lg border border-neon-primary/20 bg-dark-700/30 p-4">
      <h3 class="text-lg font-semibold text-white">Grid</h3>
      <p class="mt-2 text-sm text-gray-300">Mode: ${state.mode}</p>
      <p class="text-sm text-gray-400">Grid Name: ${formatMaybe(state.grid.name)}</p>
      <p class="text-sm text-gray-400">Grid Nick: ${formatMaybe(state.grid.nick)}</p>
      <p class="text-sm text-gray-400">Welcome Message: ${formatMaybe(state.grid.welcomeMessage)}</p>
      <p class="text-sm text-gray-400">Simulator Level: ${state.simulator.level}</p>
      <p class="text-sm text-gray-400">Primary Simulator: ${formatMaybe(state.simulator.primaryName)}</p>
      <p class="text-sm text-gray-400">Region Simulator: ${formatMaybe(state.simulator.regionName)}</p>
    </div>

    ${botBlock}

    <div class="rounded-lg border border-neon-primary/20 bg-dark-700/30 p-4">
      <h3 class="text-lg font-semibold text-white">User</h3>
      <p class="mt-2 text-sm text-gray-300">${state.user.first} ${state.user.last}</p>
      <p class="text-sm text-gray-400">Email: ${formatMaybe(state.user.email)}</p>
      <p class="text-sm text-gray-400">Bot Handler: ${state.user.botHandler ? 'Yes' : 'No'}</p>
    </div>

    <div class="rounded-lg border border-neon-primary/20 bg-dark-700/30 p-4">
      <h3 class="text-lg font-semibold text-white">Administrator</h3>
      <p class="mt-2 text-sm text-gray-300">User: ${formatMaybe(state.admin.username)}</p>
      <p class="text-sm text-gray-400">Password: (hidden)</p>
    </div>

    <div class="rounded-lg border border-neon-primary/20 bg-dark-700/30 p-4">
      <h3 class="text-lg font-semibold text-white">Region</h3>
      <p class="mt-2 text-sm text-gray-400">Port: ${formatMaybe(state.simulator.port)}</p>
      <p class="text-sm text-gray-400">Region X/Y: ${state.simulator.regionX}, ${state.simulator.regionY}</p>
      <p class="text-sm text-gray-400">Appearance: ${formatMaybe(state.simulator.oar)}</p>
    </div>
  `;
};

const renderStep = () => {
  STEPS.forEach((step) => {
    const el = document.getElementById(`wizard-step-${step.number}`);
    if (!el) {
      return;
    }
    el.classList.toggle('hidden', step.number !== currentStep);
  });

  const step = STEPS[currentStep - 1];
  if (stepLabel) {
    stepLabel.textContent = `Step ${step.number} of ${STEPS.length}`;
  }
  if (stepTitle) {
    stepTitle.textContent = step.title;
  }

  if (prevButton) {
    prevButton.classList.toggle('hidden', currentStep === 1);
  }
  if (skipLink) {
    skipLink.classList.toggle('hidden', currentStep !== 1);
  }
  if (nextButtonLabel) {
    nextButtonLabel.textContent = currentStep === STEPS.length ? 'Finish' : 'Next';
  }

  if (currentStep === 4) {
    updateRegionSimulatorName();
  }
  if (currentStep === 5) {
    renderSummary();
  }
};

const validateStep = () => {
  const state = wizardState();

  if (currentStep === 1) {
    if (!state.simulator.primaryName) {
      return 'Primary simulator name is required.';
    }
    if (!SIMULATOR_NAME_PATTERN.test(state.simulator.primaryName)) {
      return 'Primary simulator name must use letters, numbers, underscores or dashes only.';
    }
    if (!state.grid.name) {
      return 'Grid name is required.';
    }
    if (!state.grid.nick) {
      return 'Grid nick is required.';
    }
  }

  if (currentStep === 2 && state.bot.create) {
    const enteredBotFirst = String(botFirst?.value || '').trim();
    const enteredBotLast = String(botLast?.value || '').trim();
    if ((enteredBotFirst && !PERSON_NAME_PATTERN.test(enteredBotFirst)) || (enteredBotLast && !PERSON_NAME_PATTERN.test(enteredBotLast))) {
      return 'Bot first and last name must be alphanumeric only.';
    }
    if (state.bot.email && !isValidEmail(state.bot.email)) {
      return 'Bot email must be a valid email address.';
    }
  }

  if (currentStep === 3) {
    if (!state.user.email || !state.user.password) {
      return 'Email and password are required.';
    }
    const enteredUserFirst = String(userFirst?.value || '').trim();
    const enteredUserLast = String(userLast?.value || '').trim();
    if ((enteredUserFirst && !PERSON_NAME_PATTERN.test(enteredUserFirst)) || (enteredUserLast && !PERSON_NAME_PATTERN.test(enteredUserLast))) {
      return 'User first and last name must be alphanumeric only.';
    }
    if (!isValidEmail(state.user.email)) {
      return 'User email must be a valid email address.';
    }
    if (state.user.password.length < MIN_PASSWORD_LENGTH) {
      return `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
    }
    if (state.user.password !== String(userPasswordConfirm?.value || '')) {
      return 'Password and confirm password must match.';
    }
  }

  if (currentStep === 4) {
    if (!state.simulator.regionName) {
      return 'Region simulator name is required.';
    }
    if (!SIMULATOR_NAME_PATTERN.test(state.simulator.regionName)) {
      return 'Region simulator name must use letters, numbers, underscores or dashes only.';
    }
    if (state.mode === 'ROBUST' && state.simulator.regionName.toLowerCase() === state.simulator.primaryName.toLowerCase()) {
      return 'For ROBUST mode, region simulator name must differ from the primary simulator name.';
    }
    if (state.simulator.port) {
      if (!isIntegerString(state.simulator.port)) {
        return 'Port must be a whole number when provided.';
      }
      const port = Number(state.simulator.port);
      if (port < 1 || port > 65535) {
        return 'Port must be between 1 and 65535.';
      }
    }
    if (!isIntegerString(state.simulator.regionX) || !isIntegerString(state.simulator.regionY)) {
      return 'Region X and Region Y must be whole numbers.';
    }
    const regionXValue = Number(state.simulator.regionX);
    const regionYValue = Number(state.simulator.regionY);
    if (regionXValue < 0 || regionYValue < 0) {
      return 'Region X and Region Y must be zero or greater.';
    }
  }

  if (currentStep === 5) {
    if (!state.admin.username) {
      return 'Administrator user is required.';
    }
    if (!state.admin.password) {
      return 'Administrator password is required.';
    }
    if (state.admin.password.length < MIN_PASSWORD_LENGTH) {
      return `Administrator password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
    }
    if (state.admin.password !== String(adminPasswordConfirm?.value || '')) {
      return 'Administrator password and confirm password must match.';
    }
  }

  return '';
};

const loadAppearances = async () => {
  if (!botAppearance) {
    return;
  }
  try {
    const response = await fetchWithTimeout('/api/bot/appearances');
    if (!response.ok) {
      throw new Error(`Could not load bot appearances (${response.status}).`);
    }
    const items = await response.json();
    if (!Array.isArray(items)) {
      return;
    }
    items.forEach((appearanceName) => {
      const value = String(appearanceName || '').trim();
      if (!value) {
        return;
      }
      const option = document.createElement('option');
      option.value = value;
      option.textContent = value;
      botAppearance.appendChild(option);
    });
  } catch (err) {
    showToast(toastContainer, err instanceof Error ? err.message : 'Could not load bot appearances.', 'error');
  }
};

const loadOars = async () => {
  if (!regionOar) {
    return;
  }
  try {
    const response = await fetchWithTimeout('/api/simulator/oars');
    if (!response.ok) {
      throw new Error(`Could not load region appearances (${response.status}).`);
    }
    const items = await response.json();
    if (!Array.isArray(items)) {
      return;
    }
    items.forEach((entry) => {
      const key = String(entry?.key || '').trim();
      if (!key) {
        return;
      }
      const option = document.createElement('option');
      option.value = key;
      option.textContent = String(entry?.name || key);
      regionOar.appendChild(option);
    });
  } catch (err) {
    showToast(toastContainer, err instanceof Error ? err.message : 'Could not load region appearances.', 'error');
  }
};

const loadGridDefaults = async () => {
  try {
    const response = await fetchWithTimeout('/ui/api/config');
    if (!response.ok) {
      throw new Error(`Could not load grid defaults (${response.status}).`);
    }

    const config = await response.json();
    if (gridName && !String(gridName.value || '').trim()) {
      gridName.value = String(config?.gridName || '').trim();
    }
    if (gridNick && !String(gridNick.value || '').trim()) {
      gridNick.value = String(config?.gridNick || '').trim();
    }
    if (gridWelcomeMessage && !String(gridWelcomeMessage.value || '').trim()) {
      gridWelcomeMessage.value = String(config?.welcomeMessage || '').trim();
    }
    if (adminUser && !String(adminUser.value || '').trim()) {
      adminUser.value = String(config?.consoleUser || '').trim() || 'Administrator';
    }
  } catch (err) {
    showToast(toastContainer, err instanceof Error ? err.message : 'Could not load grid defaults.', 'error');
  }
};

const shouldLaunchWizard = async () => {
  const response = await fetchWithTimeout('/ui/api/setup/status');
  if (!response.ok) {
    throw new Error('Failed to check setup status.');
  }
  const payload = await response.json();
  return !!payload?.guided && !!payload?.required;
};

const runSetupWizardStub = async (details) => {
  const response = await fetchWithTimeout('/ui/api/setup/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(details)
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Setup request failed (${response.status}).`);
  }

  return response.json();
};

const simulatorExists = async (name) => {
  const response = await fetchWithTimeout(`/api/simulator/${encodeURIComponent(name)}`);
  return response.ok;
};

const botExists = async (first, last) => {
  const response = await fetchWithTimeout(`/api/bot/${encodeURIComponent(first)}/${encodeURIComponent(last)}`);
  return response.ok;
};

const waitForSetupOutcome = async (state, maxWaitMs = REQUEST_RECOVERY_WINDOW_MS) => {
  const deadline = Date.now() + maxWaitMs;
  const requiredSimulatorNames = new Set([state.simulator.primaryName]);
  if (state.mode === 'ROBUST') {
    requiredSimulatorNames.add(state.simulator.regionName);
  }

  while (Date.now() < deadline) {
    try {
      const simulatorChecks = await Promise.all(
        Array.from(requiredSimulatorNames).map((name) => simulatorExists(name))
      );
      const simulatorsReady = simulatorChecks.every((exists) => exists);
      if (!simulatorsReady) {
        await sleep(REQUEST_RECOVERY_POLL_MS);
        continue;
      }

      if (!state.bot.create) {
        return true;
      }

      const botReady = await botExists(state.bot.first, state.bot.last);
      if (botReady) {
        return true;
      }
    } catch (_ignored) {
      // Ignore transient polling failures while checking eventual setup outcome.
    }

    await sleep(REQUEST_RECOVERY_POLL_MS);
  }

  return false;
};

const finishWizard = async () => {
  const state = wizardState();
  sessionStorage.setItem('opensim-spawner:setup-wizard:last-payload', JSON.stringify(state));

  setBusy(true);
  try {
    await withWorkingOverlay(async () => runSetupWizardStub(state), 'Running setup wizard ...');
    showToast(toastContainer, 'Setup completed.', 'success');
    window.setTimeout(() => {
      window.location.assign('/ui/index.html');
    }, 800);
  } catch (err) {
    const recovered = await withWorkingOverlay(
      async () => waitForSetupOutcome(state),
      'Verifying setup result ...'
    );
    if (recovered) {
      showToast(toastContainer, 'Setup completed.', 'success');
      window.setTimeout(() => {
        window.location.assign('/ui/index.html');
      }, 800);
      return;
    }
    showError(err instanceof Error ? err.message : 'Setup failed.');
  } finally {
    setBusy(false);
  }
};

document.addEventListener('DOMContentLoaded', async () => {
  hideError();
  renderGridModeOptions();
  renderGenderOptions();
  await loadGridDefaults();
  applyModeDescription();
  syncBotFieldState();
  syncNamePlaceholders();
  renderStep();

  gridModeGroup?.addEventListener('change', () => {
    applyModeDescription();
    updateRegionSimulatorName();
  });

  createBotToggle?.addEventListener('change', () => {
    syncBotFieldState();
  });

  simulatorName?.addEventListener('input', () => {
    syncNamePlaceholders();
    if (!regionSimulatorName || document.activeElement === regionSimulatorName) {
      return;
    }
    const suggested = suggestedRegionSimulatorName();
    if (suggested) {
      regionSimulatorName.value = suggested;
    }
  });

  prevButton?.addEventListener('click', () => {
    hideError();
    currentStep = Math.max(1, currentStep - 1);
    renderStep();
  });

  nextButton?.addEventListener('click', async () => {
    hideError();
    const error = validateStep();
    if (error) {
      showError(error);
      return;
    }

    if (currentStep === STEPS.length) {
      await finishWizard();
      return;
    }

    currentStep = Math.min(STEPS.length, currentStep + 1);
    renderStep();
  });

  try {
    const launchWizard = await shouldLaunchWizard();
    if (!launchWizard) {
      window.location.assign('/ui/index.html');
      return;
    }
  } catch (err) {
    showToast(toastContainer, err instanceof Error ? err.message : 'Could not check setup state.', 'error');
  }

  await Promise.all([loadAppearances(), loadOars()]);
});
