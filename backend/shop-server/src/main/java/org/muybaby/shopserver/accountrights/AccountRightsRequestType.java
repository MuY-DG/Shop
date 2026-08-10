package org.muybaby.shopserver.accountrights;

public enum AccountRightsRequestType {
    ACCOUNT_CANCELLATION,
    PERSONAL_INFORMATION_DELETION,
    ACCESS_COPY,
    CORRECTION;

    public boolean changesStoredIdentity() {
        return this == ACCOUNT_CANCELLATION || this == PERSONAL_INFORMATION_DELETION;
    }
}
