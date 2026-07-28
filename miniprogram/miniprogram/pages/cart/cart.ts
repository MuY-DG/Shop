import { registerCartPage } from "./cart-page";

registerCartPage({
  loginRedirect: "/pages/cart/cart",
  navigationBack: false,
  syncTabBar: true
});
