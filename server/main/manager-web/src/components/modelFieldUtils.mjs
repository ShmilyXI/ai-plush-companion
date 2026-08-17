function normalizeOption(option) {
  if (option && typeof option === 'object' && !Array.isArray(option)) {
    const value = Object.prototype.hasOwnProperty.call(option, 'value') ? option.value : option.label
    const label = Object.prototype.hasOwnProperty.call(option, 'label') ? option.label : value
    return { label: String(label ?? ''), value }
  }
  return { label: String(option ?? ''), value: option }
}

export function normalizeModelField(field = {}) {
  const options = Array.isArray(field.options) ? field.options.map(normalizeOption) : []
  let control = 'input'
  if (options.length > 0) control = 'select'
  else if (field.type === 'boolean') control = 'switch'
  else if (field.type === 'dict') control = 'json-textarea'

  const numericTypes = new Set(['number', 'integer', 'int', 'float'])
  const inputType = field.type === 'password' ? 'password' : numericTypes.has(field.type) ? 'number' : 'text'
  let defaultValue = Object.prototype.hasOwnProperty.call(field, 'default') ? field.default : ''
  if (control === 'switch') defaultValue = Boolean(defaultValue)
  if (control === 'json-textarea') {
    defaultValue = JSON.stringify(defaultValue && typeof defaultValue === 'object' ? defaultValue : {}, null, 2)
  }

  return {
    prop: field.key,
    label: field.label || field.key,
    control,
    inputType,
    type: control === 'input' ? inputType : control,
    options,
    defaultValue,
    help: field.help || field.description || '',
    placeholder: field.placeholder || `请输入${field.key}`,
  }
}

export function createDefaultModelConfig(fields = []) {
  return fields.reduce((config, field) => {
    config[field.prop] = field.defaultValue !== undefined ? field.defaultValue : ''
    return config
  }, {})
}
