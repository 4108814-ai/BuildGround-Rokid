# Official AIUI capability fixtures

These files are copied from the 2026-08-02 local official capability corpus:

- `layout`: `samples/capabilities/pages/layout`; its three imported WXSS files are flattened into `layout.wxss` because this engine has no asset/import resolver.
- `position`: `samples/capabilities/pages/position`.
- `grid`: `samples/capabilities/pages/grid/index.ink`; the `script setup` block is removed and the page/style blocks are split.

All three fixtures are compiled with an empty host-data object because their templates are static. Tests assert the exact v1 rejection/warning features for declarations outside the Ink Surface matrix.

`chart-rich.wxml` and `chart-out-of-matrix.wxml` are reduced from the official
capabilities chart page. They retain its bound multi-series line, area, bar,
pie, radar, funnel, and scatter authoring shapes while removing unrelated
dashboard chrome and script-only behavior.
