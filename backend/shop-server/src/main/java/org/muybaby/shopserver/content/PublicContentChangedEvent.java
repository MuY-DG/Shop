package org.muybaby.shopserver.content;

public record PublicContentChangedEvent(Region region) {

    public static PublicContentChangedEvent home() {
        return new PublicContentChangedEvent(Region.HOME);
    }

    public static PublicContentChangedEvent contact() {
        return new PublicContentChangedEvent(Region.CONTACT);
    }

    public enum Region {
        HOME,
        CONTACT
    }
}
