-- Public landmarks a buyer can pick as an origin without granting location permission.
CREATE TABLE landmark (
  id          uuid PRIMARY KEY,
  name_en     text NOT NULL,
  name_ar     text NOT NULL,
  kind        text NOT NULL,
  geom        geometry(Point,4326) NOT NULL,
  source      text NOT NULL,
  search_norm text NOT NULL DEFAULT ''
);
CREATE INDEX landmark_gix ON landmark USING gist (geom);
