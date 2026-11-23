-- Viewing requests. Recipient, channel and consent are recorded atomically with the request.
-- Personal data here (name, phone) is the most sensitive in the system: retention_until is mandatory (privacy register PR-01).
CREATE TABLE inquiry (
  id                uuid PRIMARY KEY,
  idempotency_key   text NOT NULL UNIQUE,
  buyer_token_hash  text NOT NULL,                 -- lets the buyer read/cancel without an account
  listing_id        uuid NOT NULL REFERENCES listing(id),
  offer_version_id  uuid NOT NULL REFERENCES offer_version(id),   -- the version the buyer saw and accepted
  recipient_org_id  uuid NOT NULL REFERENCES organization(id),
  channel           text NOT NULL CHECK (channel IN ('in_app','phone_call','whatsapp')),
  contact_name      text NOT NULL,
  contact_phone     text NOT NULL,
  consent_share_contact boolean NOT NULL,
  consent_text_version text NOT NULL,
  preferred_lang    text NOT NULL DEFAULT 'ar',
  slot_start        timestamptz NOT NULL,
  holds_slot        boolean NOT NULL DEFAULT false,
  status            text NOT NULL CHECK (status IN ('requested','acknowledged','confirmed','alternative_proposed','waitlisted','declined',
                                                    'cancelled','no_response','attended','no_show')),
  proposed_slot     timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  retention_until   timestamptz NOT NULL
);
-- Slot policy: one held/confirmed viewing per listing per slot start.
CREATE UNIQUE INDEX inquiry_slot_hold ON inquiry(listing_id, slot_start) WHERE holds_slot;
CREATE INDEX inquiry_recipient ON inquiry(recipient_org_id, status);

CREATE TABLE inquiry_event (
  id          bigserial PRIMARY KEY,
  inquiry_id  uuid NOT NULL REFERENCES inquiry(id),
  from_status text,
  to_status   text NOT NULL,
  actor       text NOT NULL,          -- buyer | partner:<user id> | system
  note        text,
  at          timestamptz NOT NULL DEFAULT now()
);

-- Outbox for anything that must reach a person (partner inbox email, buyer push). Delivery is idempotent by dedupe_key.
CREATE TABLE outbox (
  id          bigserial PRIMARY KEY,
  dedupe_key  text NOT NULL UNIQUE,
  topic       text NOT NULL,
  payload     jsonb NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  state       text NOT NULL DEFAULT 'queued' CHECK (state IN ('queued','accepted_by_provider','failed','cancelled')),
  attempts    int NOT NULL DEFAULT 0,
  last_error  text
);
