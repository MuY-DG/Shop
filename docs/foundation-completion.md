# Foundation Completion

The foundation milestone is complete when these checks pass:

- `cd backend/shop-server && ./mvnw test`
- `cd admin && pnpm build`
- `cd miniprogram && pnpm typecheck`

The next implementation plan should begin with authentication and RBAC:

- Spring Security token model.
- Admin login API.
- Backend-driven Art Design Pro menu API.
- Mini program silent login API.
- Token separation between `/admin/**` and `/app/**`.
