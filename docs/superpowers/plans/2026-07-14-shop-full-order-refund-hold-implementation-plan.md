# Shop Full-Order Refund And Fulfillment Hold Implementation Plan

**Date:** 2026-07-14

## Goal

Make the current order-level after-sale flow safe and internally consistent by supporting only full-order refund-only requests. An active after-sale request must be visible on admin orders and must block shipment and receipt confirmation until it is rejected or otherwise resolved.

## Scope

- The only newly accepted after-sale type is `REFUND_ONLY`.
- The requested and approved refund amount must equal the order's full paid amount.
- Active after-sale statuses are `REQUESTED`, `APPROVED`, `REFUNDING`, and `REFUND_FAILED`.
- An active after-sale blocks `PAID -> SHIPPED` and `SHIPPED -> COMPLETED` transitions.
- Admin order list/detail responses expose the latest active after-sale summary and a derived `canShip` value.
- Admin order UI shows the hold and does not offer shipment while the hold is active.
- Mini-program after-sale application shows a fixed full-refund amount and no return-refund selector.

## Explicit Non-Goals

- Return logistics and return-receipt confirmation.
- Partial refunds or item-level after-sales.
- A new after-sale time limit for completed orders.
- Database schema changes or new order status enum values.

## Backend Work

1. Add focused regression tests proving that:
   - partial amount applications are rejected;
   - `RETURN_REFUND` applications are rejected;
   - admin approval cannot change the full refund amount;
   - a blocking after-sale prevents shipment;
   - a blocking after-sale prevents receipt confirmation;
   - rejected after-sales release fulfillment actions;
   - admin order list/detail return the active after-sale summary and `canShip=false`.
2. Centralize the blocking after-sale status query so shipment, receipt, and order projection use one definition.
3. Validate full paid amount and `REFUND_ONLY` in both app application and admin approval paths.
4. Add the fulfillment guard after locking the order row, preserving transaction ordering against concurrent after-sale application.
5. Extend admin order DTOs and SQL projections without adding persistent columns.

## Frontend Work

1. Admin order list/detail:
   - show `仅退款待审核` / `退款中` / `退款失败` as a second status dimension;
   - replace the shipment action with a clear hold explanation when `canShip=false`;
   - link operators to the related after-sale workspace.
2. Admin after-sale audit:
   - display the requested full refund amount as read-only;
   - submit that exact amount.
3. Mini-program:
   - remove the after-sale type selector;
   - show `整单仅退款` and a read-only full paid amount;
   - always submit `REFUND_ONLY` with the full paid amount.

## Verification

- Run focused Spring tests for after-sale, shipment, receipt, and admin order projections.
- Run the backend full test suite.
- Run admin typecheck/build and relevant Node tests.
- Run mini-program typecheck/tests.
- Review the final diff and confirm the worktree contains only intentional changes.
