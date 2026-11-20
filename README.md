# Qwen Custom Tabs patches

Morphe patches for Qwen (`com.tongyi.intl`). The current patch opens HTTP(S) links from Qwen's embedded browser in Android Custom Tabs and targets Qwen 2.5.1.

## Install

Add this manifest URL as a patch source in Morphe Manager to use the development release channel. Enable prereleases for the source:

`https://raw.githubusercontent.com/soredake/morphe-patches/dev/patches-bundle.json`

## Patches

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0-dev.16](https://github.com/soredake/morphe-patches/releases/tag/v1.0.0-dev.16)**&nbsp;&nbsp;&middot;&nbsp;&nbsp;`dev`&nbsp;&nbsp;&middot;&nbsp;&nbsp;**1 patches** across **1 apps**&nbsp;&nbsp;&middot;&nbsp;&nbsp;[Full details](PATCHES.md)

| # | App | Patches | Version | Package |
|---|---|---|---|---|
| 1 | [**Qwen**](PATCHES.md#qwen-comtongyiintl) | 1 | `2.5.1` | [`com.tongyi.intl`](https://play.google.com/store/apps/details?id=com.tongyi.intl) |
<!-- PATCHES_END -->

## Build locally

Run `./gradlew buildAndroid`. The `.mpp` bundle is written to `patches/build/libs/` and can be applied with [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop).

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
