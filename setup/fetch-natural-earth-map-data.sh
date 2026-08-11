#!/usr/bin/env bash
set -euo pipefail

source_dir=tmp/map-sources
mkdir -p "$source_dir"

curl -sS -L \
  https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.1/geojson/ne_10m_admin_0_countries.geojson \
  -o "$source_dir/ne_10m_admin_0_countries.geojson"
curl -sS -L \
  https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.1/geojson/ne_10m_admin_1_states_provinces.geojson \
  -o "$source_dir/ne_10m_admin_1_states_provinces.geojson"
curl -sS -L \
  https://naciscdn.org/naturalearth/50m/raster/MSR_50M.zip \
  -o "$source_dir/MSR_50M.zip"

printf '%s  %s\n' \
  '239eec57ac17f100a11e2536cffc56752c318b50ae765b0918ff7aab4ce8f255' \
  "$source_dir/ne_10m_admin_0_countries.geojson" \
  '22d0e3ad85eb3e27f17cabf8ba2d50e554fbc27a87796ff891d958185da62fb5' \
  "$source_dir/ne_10m_admin_1_states_provinces.geojson" \
  'e38f8b256f64eccae250a1e482761d4a7a585e6602c941e6bdb860e160faf609' \
  "$source_dir/MSR_50M.zip" \
  | shasum -a 256 -c -

unzip -oq "$source_dir/MSR_50M.zip" -d "$source_dir"
