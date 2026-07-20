Component({
  options: {
    styleIsolation: 'isolated',
  },

  properties: {
    tone: {
      type: String,
      value: 'brand',
    },
    size: {
      type: String,
      value: 'medium',
    },
    outline: {
      type: Boolean,
      value: false,
    },
  },
})
