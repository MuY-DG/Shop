# Shop Product Review Images Implementation Plan

**Date:** 2026-08-03

**Goal:** Add a complete product-review image flow across the backend, mini program, and admin review management while preserving the existing completed-order eligibility and moderation rules.

## Scope

- Allow an app user to upload up to 6 images for one pending order-item review.
- Limit each source image to 5 MB and reuse the existing COS direct-upload/WebP processing path.
- Keep unfinished review uploads for at most 24 hours, then remove them through the storage cleanup job.
- Bind uploaded assets transactionally when the review is created.
- Return ordered image metadata from public review, my-review, and admin-review APIs.
- Show review images in the mini-program review form and product review lists.
- Show review images in the admin review list/detail drawer.
- Remove image usages and schedule assets for cleanup when a user deletes a review.

## Data And Storage Model

1. Add `product_review_image` with `review_id`, `asset_id`, `image_url`, and `sort_order`.
2. Add storage usage values `PRODUCT_REVIEW_IMAGE` and `PRODUCT_REVIEW`.
3. Add a `PRODUCT_REVIEW_IMAGE` upload profile:
   - scope: `LIBRARY`
   - media kind: `IMAGE`
   - visibility: `PUBLIC`
   - uploader: app user
   - upload context: `PRODUCT_REVIEW_ORDER_ITEM` plus the order-item id
   - staging lifetime after completion: 24 hours
4. Extend expiry cleanup to cover expiring public library assets. Bound assets clear `expires_at` and gain an active usage row, so they are retained.

## Backend Contract

- Add upload endpoints under `/app/product/order-items/{orderItemId}/review-images`:
  - legacy multipart upload
  - create/complete/cancel COS direct-upload session
- Upload is allowed only when the caller owns a completed, non-deleted order containing the item and the item has not been reviewed.
- `ProductReviewRequest` accepts `imageFileIds`; the backend normalizes distinct ids, preserves request order, and rejects more than 6.
- Every submitted asset must be active, public, image media, uploaded by the same app user, match the order-item context, remain unexpired, and have no active usage.
- Review responses expose `images: [{ fileId, url, sortOrder }]`.
- Existing update behavior keeps the current image set unchanged.

## Mini Program

- Reuse `uploadFileDirect` with the review-image endpoints.
- Let the user select from album/camera, preview, remove, retry, and see the 6-image limit.
- Keep a local temporary path for immediate preview and submit only bound asset ids.
- Display returned review images in the product-detail preview and full review sheet.

## Admin

- Extend `Api.Product.ProductReview` with ordered images.
- Add thumbnail/preview UI to the review table and detail drawer.
- Keep moderation behavior unchanged; hidden reviews disappear from public API results.

## Verification

- Backend integration tests:
  - upload endpoint authentication and completed-order ownership
  - image binding, ordering, public/my/admin response projection
  - cross-user/context/stale/over-limit rejection
  - review deletion removes usage and schedules assets for cleanup
  - expiring public review-image asset cleanup
- Mini-program tests for image normalization/draft behavior, then full `pnpm check`.
- Admin `pnpm build`.
- Backend focused tests followed by the full Maven test suite.
- `git diff --check` and final worktree review.
