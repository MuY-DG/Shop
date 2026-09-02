package org.muybaby.shopserver.product;

public enum ProductParameterValueType {
    TEXT,
    NUMBER,
    SINGLE_SELECT,
    MULTI_SELECT,
    BOOLEAN;

    public boolean supportsFiltering() {
        return this == SINGLE_SELECT || this == MULTI_SELECT;
    }
}
