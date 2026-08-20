<div align="center">

# GameNative

**Play the PC games you already own — from Steam, Epic and GOG — on your Android device, with cloud saves.**

[![Darkaxt Release](https://img.shields.io/github/v/release/Darkaxt/GameNative?include_prereleases&style=flat-square&logo=github&label=Darkaxt%20release)](https://github.com/Darkaxt/GameNative/releases)
[![GitHub stars](https://img.shields.io/github/stars/Darkaxt/GameNative?style=flat-square&logo=github&color=ffd700)](https://github.com/Darkaxt/GameNative/stargazers)
[![Official Discord](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fdiscord.com%2Fapi%2Fv9%2Finvites%2F2hKv4VfZfE%3Fwith_counts%3Dtrue&query=%24.approximate_member_count&style=flat-square&logo=discord&logoColor=white&label=official%20discord&color=5865F2&suffix=%20members)](https://discord.gg/2hKv4VfZfE)
[![License](https://img.shields.io/badge/license-GPL%203.0-blue?style=flat-square)](LICENSE)

[**Download Darkaxt builds**](https://github.com/Darkaxt/GameNative/releases) · [**Official upstream**](https://github.com/utkarshdalal/GameNative) · [**Official Discord**](https://discord.gg/2hKv4VfZfE)

<video src="https://github.com/user-attachments/assets/95b5397b-908a-44ef-a10a-dac7723580b0" autoplay loop muted playsinline width="100%"></video>

</div>

---

> [!IMPORTANT]
> This is the **[Darkaxt/GameNative](https://github.com/Darkaxt/GameNative) fork** of the [official GameNative project](https://github.com/utkarshdalal/GameNative). It follows upstream while developing a Steam-normalized cross-store library and native Steam community experience. Download fork builds from [Darkaxt releases](https://github.com/Darkaxt/GameNative/releases).

## What this fork adds

- **One game, all owned copies:** confidently related Steam, Epic, GOG, Amazon and custom copies share one canonical card and search result while retaining their real source badges.
- **Conservative Steam catalog matching:** normalized title and corroborating metadata drive automatic matches; ambiguous games stay unresolved until you use **Fix Steam match** with a title or AppID.
- **Ownership-safe actions:** a Steam match improves presentation only. Install, play, update, uninstall and save operations still use the selected copy from the store where you actually own it.
- **Steam-rich game pages:** trusted Steam identities can provide descriptions, media, requirements, achievements, DLC information and other catalog details for non-Steam-owned copies.
- **Native Steam community browsing:** fork prereleases include read-only Reviews and Discussions with filters and bounded pagination; authenticated actions continue through Steam.
- **Secure matching setup and fork releases:** Steam Web API keys are validated and protected with Android Keystore. Darkaxt releases use a persistent signing identity and include optional side-by-side builds; master builds are available as [signed GitHub Actions artifacts](https://github.com/Darkaxt/GameNative/actions/workflows/app-release-signed.yml).

Automatic matching is best effort: the fork does not force uncertain matches, and a matched Steam AppID never implies Steam ownership.

## About GameNative

GameNative lets you run the PC games in your Steam, Epic and GOG libraries directly on Android — no streaming required. Your saves sync to the cloud, so you can stop on your PC and keep going on your phone.

It's still early. Not every game runs yet, and some need tweaking to play well, but the community is constantly finding and sharing configs that work — and these get applied automatically. You can see if anyone has tried running your game successfully at https://gamenative.app/compatibility.

## What you get

- Play games you actually own on Steam, Epic, GOG and Amazon
- Cloud saves that carry over between your PC and your phone
- Automatically applied known configs, so many games just work out of the box with no tweaking required
- Controller and touch support, with a custom control editor and on-screen HUD
- Steam DLC, workshop and branch support
- Active support over Discord if you need help getting a game running

## Demo

[TechDweeb](https://www.youtube.com/@TechDweeb) walks through setting up GameNative on an Android handheld in a couple of minutes:

<div align="center">

<a href="https://youtu.be/QqIChmAu2_A?si=Ha6xzTQXZA2H8HUN&t=53" target="_blank"><img src="https://github.com/user-attachments/assets/6957e3a1-34ac-41f5-b558-0f1868dbf3d4" alt="Youtube Video" /></a>

</div>

## How to use

1. Download a signed fork build from [Darkaxt releases](https://github.com/Darkaxt/GameNative/releases)
2. Install the APK on your Android device
3. Log in to your Steam account
4. Install your game
5. Hit play and enjoy

## Support

For help with the official GameNative project and the wider community, visit the [upstream Discord server](https://discord.gg/2hKv4VfZfE). Darkaxt build details, checksums and compatibility variants are documented on each [fork release](https://github.com/Darkaxt/GameNative/releases).

If you'd like to support the original project, its [Ko-fi page](https://ko-fi.com/gamenative) remains the official destination.

## Contributing

To contribute to the official project, ask for the **#development** channel on the [upstream Discord](https://discord.gg/2hKv4VfZfE) or use its [Trello board](https://trello.com/b/vGRkFoAM/open-source-board). Fork-specific development is tracked in this repository.

### Building

Most of the time you don't need this — if you just want to play, grab the release above. This is for contributors.

1. Build it like any normal Android Studio project. Ask on Discord if you get stuck.
2. **SteamGridDB API key (optional):** to pull game artwork for custom games, add your key to `local.properties`:
   ```properties
   STEAMGRIDDB_API_KEY=your_api_key_here
   ```
   You can get one from your [SteamGridDB preferences](https://www.steamgriddb.com/profile/preferences). Without it everything still works — it just won't fetch images.

## Analytics & privacy

GameNative uses [PostHog](https://posthog.com) for anonymous analytics. No personal information is ever collected — no names, emails, IPs or device identifiers.

**Always collected**, to improve game compatibility:
- Game launch, close and exit events (game name, store, session length, average FPS, container config)
- Game install, cancel and uninstall events

This is how we figure out which games work, how well they run, and which configs to apply automatically for the next person. It can't identify you.

**Optional**, and switchable under *Settings → Info → Usage Analytics*:
- Feature usage (on-screen keyboard, controller, HUD, control editor)
- Login success/failure events
- Recommendation interactions
- App lifecycle events (foreground/background)
- Cloud sync events

The full [Privacy Policy](PrivacyPolicy/README.md) has the details.

## Supporters

The official project is supported by its [Ko-fi sponsors](https://ko-fi.com/gamenative) and [GitHub sponsors](https://github.com/sponsors/utkarshdalal?preview=true), including [CodeRabbit](https://coderabbit.link/gnative).

[![Official GameNative Star History Chart](https://star-history.dera.page/svg?repos=utkarshdalal/GameNative&type=Date&theme=dark)](https://star-history.dera.page/#utkarshdalal/GameNative&Date)

## License

[GPL 3.0](LICENSE).

See [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for attributions, copyleft source offers, and notices about third-party and proprietary components bundled with the app.

---

**Disclaimer:** This software is meant for playing games that you legally own. Don't use it for piracy or anything else illegal. The maintainer takes no responsibility for misuse.
