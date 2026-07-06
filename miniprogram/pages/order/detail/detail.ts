Page({
  data: {
    transactionId: "",
    merchantTradeNo: ""
  },
  onLoad(query: Record<string, string | undefined>) {
    this.setData({
      transactionId: query.transaction_id ? query.transaction_id : "",
      merchantTradeNo: query.merchant_trade_no ? query.merchant_trade_no : ""
    });
  }
});
