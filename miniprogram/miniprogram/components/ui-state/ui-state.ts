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
    icon: {
      type: String,
      value: '/assets/icons/empty-products.svg',
    },
    actionText: {
      type: String,
      value: '',
    },
    skeletonType: {
      type: String,
      value: '',
    },
  },

  data: {
    skeletonGridItems: [0, 1, 2, 3],
    skeletonListItems: [0, 1, 2],
  },

  methods: {
    handleAction() {
      this.triggerEvent('action')
    },
  },
})
