Component({
  options: {
    styleIsolation: 'isolated',
  },

  properties: {
    type: {
      type: String,
      value: 'empty',
    },
    title: {
      type: String,
      value: '',
    },
    description: {
      type: String,
      value: '',
    },
    actionText: {
      type: String,
      value: '',
    },
  },

  methods: {
    handleAction() {
      this.triggerEvent('action')
    },
  },
})
