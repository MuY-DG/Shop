// Avoid a percentage-height cycle when the list request returns no rows before
// the surrounding card has established its own content height.
export const ASSET_LIBRARY_EMPTY_TABLE_HEIGHT = 'clamp(360px, 48vh, 460px)'
