-- Wadeh foundation schema. Physical places and commercial claims are separate tables.
-- Every fact records when it was effective (effective_at / asserted_on) and when we learned it (recorded_at).
-- Extensions are created by the bootstrap script as superuser; IF NOT EXISTS keeps this idempotent.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE country (
  code            char(2) PRIMARY KEY,
  name_en         text NOT NULL,
  name_ar         text NOT NULL,
  currency        char(3) NOT NULL,
  timezone        text NOT NULL,
  public_search   boolean NOT NULL DEFAULT false   -- a country stays out of public search until authorised
);

CREATE TABLE organization (
  id              uuid PRIMARY KEY,
  kind            text NOT NULL CHECK (kind IN ('developer','brokerage','owner','platform','test_supplier')),
  name_en         text NOT NULL,
  name_ar         text NOT NULL,
  country_code    char(2) NOT NULL REFERENCES country(code),
  is_test         boolean NOT NULL DEFAULT false,   -- synthetic supplier; release check blocks these in prod
  refresh_target_days int NOT NULL DEFAULT 14,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
  id              uuid PRIMARY KEY,
  email           text NOT NULL UNIQUE,
  display_name    text NOT NULL,
  password_hash   text NOT NULL,
  platform_role   text CHECK (platform_role IN ('platform_admin','verifier','editor','support','analyst')),
  disabled        boolean NOT NULL DEFAULT false,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE membership (
  user_id         uuid NOT NULL REFERENCES app_user(id),
  org_id          uuid NOT NULL REFERENCES organization(id),
  role            text NOT NULL CHECK (role IN ('org_owner','supplier_agent','verifier')),
  PRIMARY KEY (user_id, org_id)
);

CREATE TABLE locality (
  id              uuid PRIMARY KEY,
  country_code    char(2) NOT NULL REFERENCES country(code),
  parent_id       uuid REFERENCES locality(id),
  kind            text NOT NULL CHECK (kind IN ('governorate','city','district','neighbourhood')),
  name_en         text NOT NULL,
  name_ar         text NOT NULL,
  aliases         text[] NOT NULL DEFAULT '{}',
  search_norm     text NOT NULL DEFAULT '',
  centroid        geography(Point,4326),
  boundary        geography(MultiPolygon,4326),
  boundary_source text
);
CREATE INDEX locality_norm_trgm ON locality USING gin (search_norm gin_trgm_ops);

CREATE TABLE project (
  id              uuid PRIMARY KEY,
  developer_org_id uuid NOT NULL REFERENCES organization(id),
  locality_id     uuid NOT NULL REFERENCES locality(id),
  code            text NOT NULL,                     -- stable short code used in templates
  name_en         text NOT NULL,
  name_ar         text NOT NULL,
  aliases         text[] NOT NULL DEFAULT '{}',
  search_norm     text NOT NULL DEFAULT '',
  location        geography(Point,4326),
  location_precision_m int,                          -- disclosed precision; never pretend to be exact
  outline         geography(MultiPolygon,4326),
  outline_kind    text CHECK (outline_kind IN ('marketing','surveyed','approximate')),
  publication_rights text NOT NULL DEFAULT 'directory_only'
                  CHECK (publication_rights IN ('none','directory_only','full')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (developer_org_id, code)
);
CREATE INDEX project_norm_trgm ON project USING gin (search_norm gin_trgm_ops);
CREATE INDEX project_location_gix ON project USING gist (location);

CREATE TABLE phase (
  id              uuid PRIMARY KEY,
  project_id      uuid NOT NULL REFERENCES project(id),
  code            text NOT NULL,
  name_en         text NOT NULL,
  name_ar         text NOT NULL,
  handover_from   text,       -- precise-date string (e.g. 2028-Q3)
  handover_to     text,
  UNIQUE (project_id, code)
);

CREATE TABLE building (
  id              uuid PRIMARY KEY,
  project_id      uuid NOT NULL REFERENCES project(id),
  phase_id        uuid REFERENCES phase(id),
  code            text NOT NULL,
  UNIQUE (project_id, code)
);

-- A physical unit. Its identity is independent of any supplier key.
CREATE TABLE unit (
  id              uuid PRIMARY KEY,
  project_id      uuid NOT NULL REFERENCES project(id),
  phase_id        uuid REFERENCES phase(id),
  building_id     uuid REFERENCES building(id),
  unit_code       text,                              -- developer's code, unique within project when known
  unit_type       text NOT NULL,
  bedrooms        int,
  bathrooms       int,
  area_m2         numeric(10,2),
  area_basis      text NOT NULL DEFAULT 'UNKNOWN',
  floor           text,
  finishing       text NOT NULL DEFAULT 'UNKNOWN',
  aspect          text,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX unit_code_per_project ON unit (project_id, unit_code) WHERE unit_code IS NOT NULL;

-- Ambiguous physical matches wait here for a human decision instead of being merged automatically.
CREATE TABLE unit_match_review (
  id              uuid PRIMARY KEY,
  candidate_unit_id uuid NOT NULL REFERENCES unit(id),
  proposed_unit_id  uuid NOT NULL REFERENCES unit(id),
  reason          text NOT NULL,
  status          text NOT NULL DEFAULT 'open' CHECK (status IN ('open','merged','distinct')),
  decided_by      uuid REFERENCES app_user(id),
  decided_at      timestamptz
);

-- A seller's listing of a unit through one channel. Two brokers = two listings on one unit.
CREATE TABLE listing (
  id              uuid PRIMARY KEY,
  unit_id         uuid NOT NULL REFERENCES unit(id),
  seller_org_id   uuid NOT NULL REFERENCES organization(id),
  supplier_key    text NOT NULL,                     -- the supplier's own identifier, never our identity
  channel         text NOT NULL,
  offer_type      text NOT NULL,
  publication     text NOT NULL DEFAULT 'published' CHECK (publication IN ('published','withdrawn','suspended')),
  last_confirmed_at timestamptz,                     -- last time the source asserted the facts (asserted date, not upload date)
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (seller_org_id, supplier_key)
);
CREATE INDEX listing_unit ON listing(unit_id);

CREATE TABLE import_batch (
  id              uuid PRIMARY KEY,
  org_id          uuid NOT NULL REFERENCES organization(id),
  mode            text NOT NULL CHECK (mode IN ('partial','full_snapshot')),
  source_kind     text NOT NULL CHECK (source_kind IN ('csv','xlsx','api_feed','manual')),
  filename        text,
  file_sha256     text NOT NULL,
  idempotency_key text,
  status          text NOT NULL CHECK (status IN ('staged','published','rejected','reverted','failed')),
  uploaded_by     uuid REFERENCES app_user(id),
  reviewed_by     uuid REFERENCES app_user(id),
  review_note     text,
  summary         jsonb NOT NULL DEFAULT '{}',
  created_at      timestamptz NOT NULL DEFAULT now(),
  published_at    timestamptz,
  reverted_at     timestamptz,
  UNIQUE (org_id, idempotency_key)
);

-- Original text of each supplier row, kept for the permitted scope.
CREATE TABLE source_record (
  id              uuid PRIMARY KEY,
  batch_id        uuid NOT NULL REFERENCES import_batch(id),
  row_no          int NOT NULL,
  raw             jsonb NOT NULL,
  normalized      jsonb,
  errors          jsonb NOT NULL DEFAULT '[]',
  warnings        jsonb NOT NULL DEFAULT '[]',
  action          text NOT NULL,   -- add | revise | confirm | unchanged | withdraw | invalid | stale | conflict
  listing_id      uuid REFERENCES listing(id),
  diff            jsonb NOT NULL DEFAULT '[]',
  UNIQUE (batch_id, row_no)
);

CREATE TABLE payment_plan_version (
  id              uuid PRIMARY KEY,
  listing_id      uuid NOT NULL REFERENCES listing(id),
  plan            jsonb NOT NULL,           -- eg.wadeh.core.PaymentPlan
  content_hash    text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);

-- An immutable version of the commercial offer. Superseded, never updated in place.
CREATE TABLE offer_version (
  id              uuid PRIMARY KEY,
  listing_id      uuid NOT NULL REFERENCES listing(id),
  version_no      int NOT NULL,
  currency        char(3) NOT NULL,
  list_price_minor bigint,
  cash_price_minor bigint,
  monthly_rent_minor bigint,
  plan_version_id uuid REFERENCES payment_plan_version(id),
  handover        text,
  notes_en        text,
  notes_ar        text,
  content_hash    text NOT NULL,
  asserted_on     date,                      -- price-sheet date the supplier stated
  effective_at    timestamptz NOT NULL,
  recorded_at     timestamptz NOT NULL DEFAULT now(),
  status          text NOT NULL CHECK (status IN ('current','superseded','retracted')),
  superseded_at   timestamptz,
  source_record_id uuid REFERENCES source_record(id),
  batch_id        uuid REFERENCES import_batch(id),
  UNIQUE (listing_id, version_no)
);
CREATE UNIQUE INDEX one_current_offer ON offer_version(listing_id) WHERE status = 'current';

CREATE TABLE availability_observation (
  id              uuid PRIMARY KEY,
  listing_id      uuid NOT NULL REFERENCES listing(id),
  unit_id         uuid NOT NULL REFERENCES unit(id),
  state           text NOT NULL,
  effective_at    timestamptz NOT NULL,
  recorded_at     timestamptz NOT NULL DEFAULT now(),
  hold_expires_at timestamptz,
  is_correction   boolean NOT NULL DEFAULT false,
  note            text,
  accepted        boolean NOT NULL,          -- false = recorded for audit but not applied (e.g. late "available" after sold)
  source_record_id uuid REFERENCES source_record(id),
  batch_id        uuid REFERENCES import_batch(id),
  retracted       boolean NOT NULL DEFAULT false
);
CREATE INDEX avail_listing ON availability_observation(listing_id, effective_at DESC);

-- Consumer-visible change log; also the transactional outbox for alerts.
CREATE TABLE change_event (
  id              uuid PRIMARY KEY,
  listing_id      uuid NOT NULL REFERENCES listing(id),
  unit_id         uuid NOT NULL REFERENCES unit(id),
  kind            text NOT NULL,
  field           text,
  old_value       text,
  new_value       text,
  currency        char(3),
  like_for_like   boolean NOT NULL DEFAULT true,
  effective_on    date,
  recorded_at     timestamptz NOT NULL DEFAULT now(),
  batch_id        uuid REFERENCES import_batch(id),
  note            text,
  dedupe_key      text NOT NULL UNIQUE,
  retracted       boolean NOT NULL DEFAULT false,
  seq             bigserial
);
CREATE INDEX change_listing ON change_event(listing_id, recorded_at DESC);

CREATE TABLE audit_log (
  id              bigserial PRIMARY KEY,
  at              timestamptz NOT NULL DEFAULT now(),
  actor_user_id   uuid REFERENCES app_user(id),
  actor_org_id    uuid REFERENCES organization(id),
  action          text NOT NULL,
  entity          text NOT NULL,
  entity_id       text,
  correlation_id  text,
  details         jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX audit_entity ON audit_log(entity, entity_id);

CREATE TABLE session_token (
  token_hash      text PRIMARY KEY,
  user_id         uuid NOT NULL REFERENCES app_user(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL
);

INSERT INTO country(code, name_en, name_ar, currency, timezone, public_search)
VALUES ('EG', 'Egypt', 'مصر', 'EGP', 'Africa/Cairo', true);
