const normalize = (value) => String(value ?? '').trim();

const parseBoolean = (value) => {
  const normalized = normalize(value).toLowerCase();
  if (normalized === 'true' || normalized === '1' || normalized === 'on' || normalized === 'yes') {
    return true;
  }
  if (normalized === 'false' || normalized === '0' || normalized === 'off' || normalized === 'no') {
    return false;
  }
  return false;
};

const booleanLabel = (value) => (parseBoolean(value) ? 'true' : 'false');

const buildField = (item) => {
  const wrapper = document.createElement('label');
  wrapper.className = 'block rounded-lg border border-neon-primary/20 bg-dark-900/30 p-4';

  const title = document.createElement('div');
  title.className = 'text-sm font-medium text-gray-100';
  title.textContent = String(item?.name || '');
  wrapper.appendChild(title);

  const hasRequestValue = !!item?.hasRequestValue;
  const requestValue = normalize(item?.requestValue);
  const defaultValue = normalize(item?.defaultValue);
  const type = String(item?.type || 'TEXT').toUpperCase();

  let input;
  if (type === 'BOOLEAN') {
    input = document.createElement('select');
    input.className = 'mt-3 w-full rounded-lg bg-dark-700 border border-neon-primary/30 px-3 py-2 text-gray-100 focus:outline-none focus:ring-2 focus:ring-neon-primary';
    input.dataset.valueType = 'BOOLEAN';
    input.dataset.variableName = String(item?.name || '');

    const useDefaultOption = document.createElement('option');
    useDefaultOption.value = '';
    useDefaultOption.textContent = `Use default (${booleanLabel(defaultValue)})`;
    input.appendChild(useDefaultOption);

    const trueOption = document.createElement('option');
    trueOption.value = 'true';
    trueOption.textContent = 'true';
    input.appendChild(trueOption);

    const falseOption = document.createElement('option');
    falseOption.value = 'false';
    falseOption.textContent = 'false';
    input.appendChild(falseOption);

    if (hasRequestValue) {
      input.value = parseBoolean(requestValue) ? 'true' : 'false';
    }

    wrapper.appendChild(input);
  } else if (type === 'CHOICE') {
    input = document.createElement('select');
    input.className = 'mt-3 w-full rounded-lg bg-dark-700 border border-neon-primary/30 px-3 py-2 text-gray-100 focus:outline-none focus:ring-2 focus:ring-neon-primary';
    input.dataset.valueType = 'CHOICE';
    input.dataset.variableName = String(item?.name || '');

    const emptyOption = document.createElement('option');
    emptyOption.value = '';
    emptyOption.textContent = 'Use default';
    input.appendChild(emptyOption);

    const choices = Array.isArray(item?.choices) ? item.choices : [];
    choices.forEach((choice) => {
      const value = String(choice || '').trim();
      if (!value) {
        return;
      }
      const option = document.createElement('option');
      option.value = value;
      option.textContent = value;
      input.appendChild(option);
    });

    if (hasRequestValue) {
      input.value = requestValue;
    }

    wrapper.appendChild(input);
  } else {
    input = document.createElement('input');
    input.type = type === 'INTEGER' ? 'number' : 'text';
    input.className = 'mt-3 w-full rounded-lg bg-dark-700 border border-neon-primary/30 px-3 py-2 text-gray-100 focus:outline-none focus:ring-2 focus:ring-neon-primary';
    input.dataset.valueType = type;
    input.dataset.variableName = String(item?.name || '');

    if (hasRequestValue) {
      input.value = requestValue;
    } else if (type === 'TEXT') {
      input.placeholder = defaultValue;
      input.value = '';
    } else {
      input.value = defaultValue;
    }

    wrapper.appendChild(input);
  }

  const description = normalize(item?.description);
  if (description) {
    const help = document.createElement('p');
    help.className = 'mt-2 text-xs text-gray-400';
    help.textContent = description;
    wrapper.appendChild(help);
  }

  return wrapper;
};

export const renderVariableEditor = (container, items) => {
  if (!container) {
    return;
  }

  container.innerHTML = '';
  const list = Array.isArray(items) ? items : [];

  if (!list.length) {
    const empty = document.createElement('p');
    empty.className = 'text-sm text-gray-400';
    empty.textContent = 'This component has no configurable variables.';
    container.appendChild(empty);
    return;
  }

  list.forEach((item) => {
    container.appendChild(buildField(item));
  });
};

export const collectVariableValues = (container) => {
  const values = new URLSearchParams();
  if (!container) {
    return values;
  }

  container.querySelectorAll('[data-variable-name]').forEach((input) => {
    const key = String(input.dataset.variableName || '').trim();
    const valueType = String(input.dataset.valueType || 'TEXT').toUpperCase();
    if (!key) {
      return;
    }

    values.set(key, normalize(input.value));
  });

  return values;
};
