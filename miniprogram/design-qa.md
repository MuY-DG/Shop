# Product review card design QA

## Evidence

- Source visual truth: `/var/folders/m1/6q35gvwd0bs_z79kshgf7j880000gn/T/codex-clipboard-311be649-cd7c-4b79-879e-34527e81e7f5.png`.
- Browser-rendered implementation: `/private/tmp/shop-review-sheet-rating-final.png`.
- Normalized source viewport: `/private/tmp/review-reference-normalized.png`.
- Normalized implementation viewport: `/private/tmp/review-implementation-normalized.png`.
- Viewport: WeChat DevTools Stable v2.01.2510290, iPhone 12/13 Pro preset at 88% simulator scale.
- Source pixels: 1720 × 1738. Implementation capture: 1250 × 768 full DevTools window.
- Density normalization: the source phone viewport and implementation simulator viewport were cropped and normalized to 393 × 852 pixels before comparison.
- State: steel-screen-protector product detail, buyer-review sheet open, long five-star review collapsed after five lines, and `最新` selected.
- Full-view comparison evidence: the normalized source and implementation were opened together and checked for the same phone viewport, sheet height, filter row, toolbar, review card, review image, and persistent purchase bar.
- Focused comparison evidence: the first long review was checked for rating/spec alignment, the five-line expand affordance, reduced vertical gap, and square media. The expanded state was also opened and collapsed again in DevTools.

## Findings

- No actionable P0, P1, or P2 differences remain for the annotated changes.
- Fonts and typography: the existing PingFang-first mini-program stack is preserved. The rating copy and stars are compact secondary information, while comment text keeps the established review-body size and five-line rhythm.
- Spacing and layout rhythm: `最新` and `规格` are grouped at the right edge. The rating/spec row sits directly under the nickname, and the comment begins with a 4rpx gap instead of the previous large blank area.
- Colors and visual tokens: the existing gray secondary copy, gold rating treatment, white review cards, and selected red filter state remain consistent with the product-detail design.
- Image quality and asset fidelity: review media uses the real uploaded image with `aspectFill` inside equal 170rpx width and height. Existing local Iconify-derived solid star assets are reused.
- Copy and content: ratings 4–5 display `超赞`, rating 3 displays `还不错`, and ratings 1–2 display stars without a label. The right side displays `已购 + 规格`; review time is removed from this sheet.

## Interaction and runtime checks

- A long review is measured after rendering and only receives the expand control when its real content height exceeds five lines.
- The collapsed state shows `…展开`; tapping it reveals the complete review and changes the action to `收起`.
- Toggling `最新` reloads the list while retaining the new card format.
- Historical console entries were cleared before retesting. Toggling latest and expanding the long review produced 0 new errors and 0 new warnings.
- `pnpm check` passed TypeScript checks and all 131 tests.

## Comparison history

- Earlier state: the card displayed `已购｜规格｜时间` as one left-aligned metadata line, left a large gap before the body, rendered the full long comment, and sized gallery images from flexible grid tracks.
- Fixes: replaced metadata with a two-sided rating/spec row, removed time, added star-count copy rules, measured five-line overflow, added expand/collapse, tightened the body gap, and made review images square.
- Post-fix evidence: `/private/tmp/review-implementation-normalized.png` confirms the compact rating/spec row, five-line `…展开` state, and 1:1 review image; the live DevTools interaction confirmed the full expand/collapse behavior.

## Follow-up polish

- None required for this iteration.

final result: passed
