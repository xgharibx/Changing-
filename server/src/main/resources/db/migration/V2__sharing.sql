-- Read-only comparison links. Only public listing ids are stored: never notes, budgets or saved addresses.
-- The owner proves ownership with a secret returned once at creation (stored hashed).
CREATE TABLE share_link (
  token           text PRIMARY KEY,               -- unguessable, URL-safe, 22 chars
  owner_secret_hash text NOT NULL,
  listing_ids     uuid[] NOT NULL CHECK (cardinality(listing_ids) BETWEEN 1 AND 4),
  lang            text NOT NULL DEFAULT 'ar' CHECK (lang IN ('ar','en')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL,
  revoked_at      timestamptz,
  view_count      int NOT NULL DEFAULT 0
);
CREATE INDEX share_link_expiry ON share_link(expires_at);
