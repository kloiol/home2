# House Viewer 3D (Android)

A lightweight, low-poly 3D house viewer and model library for Android built with **Kotlin** and **OpenGL ES 2.0**, configured to compile and publish persistently signed APKs directly to **GitHub Releases**.

## Built-In Low-Poly Collection (80 Standalone Buildings)

All 80 models are standalone low-poly flat-shaded buildings with **no ground pads** and **no trees**, spanning 25 architectural archetypes across four regional traditions with curated color palettes:

- **German Architecture (36 models)** — German Village Cottage, Alpine A-Frame Chalet, Quedlinburg Fachwerk, Stade Brick Merchant, Hanseatic Stepped-Gable, Bavarian Timber Chalet, Black Forest Farmhouse, Mosel Slate-Roof House, North Frisian Thatched House, Baroque Mansard House, Franconian Half-Hipped Inn, and Lubeck Brick Gothic House.
- **Kaliningrad Architecture (16 models)** — Fishing Village Twin/Triple-Gable Fachwerk, Brick Townhouse with Curved Baroque Gable & Balcony, Konigsberg Steep-Roof Suburban House, Amalienau Historic Villa, and Leninsky Prospekt Baroque Townhouse.
- **Polish Residential Architecture (14 models)** — Country Karczma Cottage with eyebrow dormers, Dworek Manor Villa with pillared porch, Bay-Window Family Villa, and Classic Balcony Gable House.
- **Lithuanian Traditional Wood Architecture (14 models)** — Curonian Spit (Nida) Fisherman's House with *zirgeliai* finials, Aukstaitija Traditional Log *Troba* with front porch, Samogitian Half-Hipped Wood House, and Vilnius Zverynas Wooden Villa with glazed veranda.

## Seamless In-Place APK Updates

This repository includes a persistent signing keystore (`app/keystore/app-signing.jks`) and automatically increments `versionCode` using `GITHUB_RUN_NUMBER` on every GitHub Actions build. If you previously installed an older build signed by an ephemeral runner debug key, uninstall it once before installing this release — every future release will install directly over the previous version.
