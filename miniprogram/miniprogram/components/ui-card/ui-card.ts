Component({
  options: {
    styleIsolation: 'isolated',
  },

  properties: {
    variant: {
      type: String,
      value: 'default',
    },
    padding: {
      type: String,
      value: 'medium',
    },
    interactive: {
      type: Boolean,
      value: false,
    },
    ariaLabel: {
      type: String,
      value: '',
    },
  },

  methods: {
    handleTap() {
      if (!this.data.interactive) {
        return
      }

      this.triggerEvent('tap')
    },
  },
})
