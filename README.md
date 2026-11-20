# Qwen Custom Tabs patches

Morphe patches for Qwen (`com.tongyi.intl`). The current patch opens HTTP(S) links from Qwen's embedded browser in Android Custom Tabs and targets Qwen 2.5.1.

## Install

Add this manifest URL as a patch source in Morphe Manager to use the development release channel. Enable prereleases for the source:

`https://raw.githubusercontent.com/soredake/morphe-patches/dev/patches-bundle.json`

## Patches

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0-dev.1](https://github.com/soredake/morphe-patches/releases/tag/v1.0.0-dev.1)** · `dev` · **1 patch** across **1 app**

| App | Patch | Qwen version |
|---|---|---|
| Qwen | Open links in Custom Tabs | `2.5.1` |
<!-- PATCHES_END -->

## Build locally

Run `./gradlew buildAndroid`. The `.mpp` bundle is written to `patches/build/libs/` and can be applied with [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop).

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
