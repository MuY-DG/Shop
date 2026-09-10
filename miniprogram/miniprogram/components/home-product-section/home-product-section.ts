import type { HomeProductCardView } from "../../features/home";

interface ProductSelectEvent {
  detail: {
    path?: string;
    spuId?: number;
    title?: string;
  };
}

Component({
  options: {
    styleIsolation: "isolated"
  },

  properties: {
    title: {
      type: String,
      value: ""
    },
    subtitle: {
      type: String,
      value: ""
    },
    iconPath: {
      type: String,
      value: ""
    },
    presentation: {
      type: String,
      value: "compact"
    },
    products: {
      type: Array,
      value: [],
      observer(products: HomeProductCardView[]) {
        // 固定左右归属，让标题展开或内容高度变化只影响同列商品。
        this.setData({
          productColumns: [
            { id: "left", products: products.filter((_, index) => index % 2 === 0) },
            { id: "right", products: products.filter((_, index) => index % 2 === 1) }
          ]
        });
      }
    },
    separated: {
      type: Boolean,
      value: false
    },
    addingSpuId: {
      type: Number,
      value: 0
    }
  },

  data: {
    productColumns: [
      { id: "left", products: [] as HomeProductCardView[] },
      { id: "right", products: [] as HomeProductCardView[] }
    ]
  },

  methods: {
    onMoreTap() {
      this.triggerEvent("more");
    },

    onProductSelect(event: ProductSelectEvent) {
      this.triggerEvent("select", event.detail);
    },

    onProductAdd(event: ProductSelectEvent) {
      this.triggerEvent("add", event.detail);
    }
  }
});
