# Shop RBAC Authorization Experience Implementation Plan

Date: 2026-07-14

## Goal

Make system administration permissions understandable and internally consistent without exposing unsafe free-form CRUD for code-bound permission marks.

## Current-State Constraints

- Keep `admin_permission.auth_mark` as a developer-owned contract shared by frontend button visibility and backend `@PreAuthorize` checks.
- Keep role authorization as the administrator-facing place to assign both page access and action/API permissions.
- Do not create a standalone permission CRUD page; creating a mark without matching backend code would not create real authorization.
- Replace the current menu page's fake write operations with a truthful read-only resource catalog.
- Preserve live RBAC refresh: enabled user, role, and permission changes must continue to affect existing admin access tokens.
- Start backend behavior changes with focused failing tests, then run the broader backend and admin gates.

## Task 1: Permission Catalog Migration And Access Contract

Files:

- Add `V18__rbac_authorization_experience.sql`.
- Modify `AdminMenuController`.
- Modify RBAC schema/menu controller tests.

Steps:

1. Replace the generic `add` mark with `system:menu:read` for the existing menu resource.
2. Remove the unused `system:menu:update` grant/resource mark until a real menu write API exists.
3. Allow users with `system:menu:read` to load the full access catalog while preserving role-management access.
4. Prove the migration and access contract with focused tests.

## Task 2: Role Grant Consistency

Files:

- Modify `AdminManagementService` and `ErrorCode`.
- Modify `AdminManagementControllerTest`.

Steps:

1. Add failing cases for a selected child menu without its parent and a permission without its owning menu.
2. Reject inconsistent grants before deleting or replacing existing role grants.
3. Keep empty grants valid and preserve atomic replacement for valid menu/permission selections.
4. Return a dedicated business error for inconsistent role authorization input.

## Task 3: Clear Role Authorization UI

Files:

- Modify the role list and role authorization dialog.
- Add a focused role-grant selection utility test.

Steps:

1. Rename `菜单权限` to `授权配置`.
2. Explain that page access controls navigation while operation permissions control buttons and backend APIs.
3. Label tree nodes as directory, page, or operation permission.
4. Continue saving menu IDs and permission IDs together so frontend selections satisfy backend consistency checks.
5. Prove that selecting a page or operation adds required parent menus without implicitly adding unrelated operations.

## Task 4: Truthful Read-Only Menu Resource Catalog

Files:

- Modify `admin/src/api/system-manage.ts`.
- Simplify `admin/src/views/system/menu/index.vue`.
- Remove the unused menu edit dialog if no other route imports it.

Steps:

1. Load the full access catalog instead of only the current user's visible menu tree.
2. Add a read-only notice explaining that routes and permission marks are maintained through code and migrations.
3. Show menu hierarchy, route, component, resource type, and attached permission marks.
4. Remove add/edit/delete controls, hard-coded edit time/status, fake success messages, and dead form logic.

## Verification

Backend:

```bash
cd backend/shop-server
./mvnw -Dtest='AdminRbacSchemaTest,AdminManagementControllerTest,AdminMenuControllerTest,AdminLiveRbacPermissionTest' test
./mvnw test
```

Admin:

```bash
cd admin
pnpm exec tsx --test src/views/system/role/modules/role-grant-selection.test.ts
pnpm exec eslint src/api/system-manage.ts src/views/system/menu/index.vue src/views/system/role/index.vue src/views/system/role/modules/role-permission-dialog.vue
pnpm exec stylelint src/views/system/menu/index.vue src/views/system/role/index.vue src/views/system/role/modules/role-permission-dialog.vue
pnpm build
```

Final:

```bash
git diff --check
git status --short --branch
```
