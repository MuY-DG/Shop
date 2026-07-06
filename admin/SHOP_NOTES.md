# Shop Admin Notes

This directory is based on Art Design Pro.

## Required Backend Contracts

All JSON APIs use:

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

Admin table pages expect page data inside `data`:

```json
{
  "records": [],
  "total": 0,
  "current": 1,
  "size": 10
}
```

Access mode is configured in `.env`:

```env
VITE_ACCESS_MODE = backend
```

Backend menu APIs must return route records with `name`, `path`, `component`, `meta`, and optional `children`.
