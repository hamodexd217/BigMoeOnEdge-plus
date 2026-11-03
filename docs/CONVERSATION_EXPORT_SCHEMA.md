# Conversation JSON export schema

The JSON export uses `schema_version: 1`. It is a user-facing transcript format, not a database backup.

Top-level fields:

- `schema_version` (integer): export schema version.
- `conversation` (object): `id`, `title`, `created_at`, and `updated_at`. Timestamps are UTC ISO-8601 strings; unavailable values are `null`.
- `messages` (array): saved messages in repository order. Each message contains `role`, `content`, `timestamp`, and an `attachments` array. `thinking` appears only when the user explicitly checks “Include thinking / reasoning” and saved reasoning exists.
- Each attachment reference contains only `name`, `mime_type`, `kind`, and `size_bytes`. Local file paths and attachment database IDs are intentionally excluded.

The format omits model runtime state, tokens, keys, authentication data, performance metrics, and unrelated app records. Unknown fields should be ignored by importers; future incompatible changes should increment `schema_version`.
