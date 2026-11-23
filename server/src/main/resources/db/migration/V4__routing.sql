CREATE EXTENSION IF NOT EXISTS pgrouting;

-- Versioned layer catalogue: every map layer states where it comes from, when it was observed vs imported, rights and failure state.
CREATE TABLE map_layer (
  code            text PRIMARY KEY,
  name_en         text NOT NULL,
  name_ar         text NOT NULL,
  source          text NOT NULL,
  licence         text NOT NULL,
  attribution     text NOT NULL,
  extent          text NOT NULL,            -- human description of coverage
  observed_at     timestamptz,              -- data snapshot time at the source
  imported_at     timestamptz,
  valid_until     timestamptz,
  offline_allowed boolean NOT NULL DEFAULT false,
  status          text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive','blocked')),
  activation_criterion text
);

CREATE TABLE road_node (
  id              bigint PRIMARY KEY,
  geom            geometry(Point,4326) NOT NULL,
  dataset         text NOT NULL
);
CREATE INDEX road_node_gix ON road_node USING gist (geom);

-- One row per directed-capable segment. Costs are seconds; negative cost = not traversable in that direction for that mode.
CREATE TABLE road_edge (
  id              bigserial PRIMARY KEY,
  source          bigint NOT NULL REFERENCES road_node(id),
  target          bigint NOT NULL REFERENCES road_node(id),
  car_cost        double precision NOT NULL,
  car_reverse_cost double precision NOT NULL,
  foot_cost       double precision NOT NULL,
  foot_reverse_cost double precision NOT NULL,
  length_m        double precision NOT NULL,
  highway         text NOT NULL,
  name_en         text,
  name_ar         text,
  status          text NOT NULL DEFAULT 'existing' CHECK (status IN ('existing','under_construction','planned','proposed')),
  status_source   text,
  geom            geometry(LineString,4326) NOT NULL,
  dataset         text NOT NULL,
  osm_way_id      bigint
);
CREATE INDEX road_edge_gix ON road_edge USING gist (geom);
CREATE INDEX road_edge_src ON road_edge(source);
CREATE INDEX road_edge_tgt ON road_edge(target);

-- Entrances are first-class: a compound can have separate visitor, pedestrian and vehicle gates with conditions.
CREATE TABLE entrance (
  id              uuid PRIMARY KEY,
  project_id      uuid REFERENCES project(id),
  place_key       text,                      -- for non-catalogue places (e.g. a public landmark used in evidence)
  name_en         text NOT NULL,
  name_ar         text NOT NULL,
  kind            text NOT NULL CHECK (kind IN ('visitor','pedestrian','vehicle','service','any')),
  modes           text[] NOT NULL,           -- car, foot
  node_id         bigint REFERENCES road_node(id),
  geom            geometry(Point,4326) NOT NULL,
  conditions_en   text,
  conditions_ar   text,
  locked          boolean NOT NULL DEFAULT false,
  source          text NOT NULL,
  observed_at     timestamptz,
  verified_by     text,
  verified_at     timestamptz
);

CREATE TABLE transit_station (
  id              uuid PRIMARY KEY,
  name_en         text NOT NULL,
  name_ar         text NOT NULL,
  line            text NOT NULL,
  geom            geometry(Point,4326) NOT NULL
);

-- Claims about a station's status. Only an APPROVED claim from an authoritative source changes what we show and route.
CREATE TABLE station_status_claim (
  id              uuid PRIMARY KEY,
  station_id      uuid NOT NULL REFERENCES transit_station(id),
  status          text NOT NULL CHECK (status IN ('proposed','planned','under_construction','operational','closed')),
  source_kind     text NOT NULL CHECK (source_kind IN ('operator','government','developer','press','user')),
  source_ref      text NOT NULL,
  published_at    timestamptz NOT NULL,
  approved_by     uuid REFERENCES app_user(id),
  approved_at     timestamptz
);

-- Kill switches and health for external providers (traffic, AI, geocoding...).
CREATE TABLE provider_switch (
  name            text PRIMARY KEY,
  enabled         boolean NOT NULL,
  reason          text,
  last_ok_at      timestamptz,
  last_error      text,
  changed_at      timestamptz NOT NULL DEFAULT now()
);
INSERT INTO provider_switch(name, enabled, reason) VALUES ('traffic', false, 'No traffic provider contracted (cost register C-05)'), ('ai', false, 'No model key provisioned (founder item F-06)');
