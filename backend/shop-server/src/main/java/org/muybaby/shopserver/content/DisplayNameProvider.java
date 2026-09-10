package org.muybaby.shopserver.content;

/** 跨业务域只提供当前展示名称，支付等历史记录应自行保存快照。 */
public interface DisplayNameProvider {
    String displayName();
}
