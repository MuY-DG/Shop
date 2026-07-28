import { registerCartPage } from "../cart-page";

registerCartPage({
  loginRedirect: "/pages/cart/standalone/standalone",
  navigationBack: true,
  syncTabBar: false
});
