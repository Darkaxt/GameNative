# Darkaxt Fork README Design

## Goal

Make `Darkaxt/GameNative` immediately recognizable as a maintained fork of the official `utkarshdalal/GameNative`, direct fork users to Darkaxt releases, and summarize the stable user-visible differences currently present on `fork/master`.

## Audience and placement

The primary audience is a user landing on the fork repository to decide what to download and how it differs from upstream. A concise **About this fork** callout will appear near the top of the README, before the inherited upstream introduction. Official GameNative attribution and links remain prominent.

## Content structure

1. Keep the existing GameNative title and core description.
2. Add a fork-first callout that:
   - identifies this repository as `Darkaxt/GameNative`;
   - links to the official upstream project;
   - links to Darkaxt releases;
   - explains the persistent Darkaxt signing identity and optional side-by-side package.
3. Add **What this fork adds** with six concise themes:
   - Steam-normalized canonical cards and search across owned stores;
   - conservative automatic Steam matching and manual correction;
   - ownership-safe copy selection and source-native actions;
   - Steam-rich details, media, and native read-only Reviews and Discussions;
   - a validated, Android-Keystore-protected user-supplied Steam Web API key;
   - persistently signed Darkaxt release and master artifact channels.
4. Retain the inherited upstream capability list and community information, labeling official upstream destinations where needed.

## Accuracy boundaries

The README describes capabilities, not implementation internals or a commit-by-commit changelog. It must not claim that every game resolves to Steam or that a Steam presentation identity proves Steam ownership.

Epic CMS presentation fallback and PCGamingWiki current-availability corroboration remain excluded because they are on `feat/steam-catalog-resolution`, not current `fork/master`. They can be documented after integration into master.

Capabilities already present upstream—store integrations, cloud saves, compatibility configs, controls, DLC, Workshop, and branch support—remain credited as inherited GameNative capabilities rather than fork additions.

## Link policy

- Darkaxt release badges and primary download actions point to `Darkaxt/GameNative` releases.
- The license links to the repository-local `LICENSE`.
- Upstream Trendshift, star history, Discord, Ko-fi, compatibility, Trello, and sponsor links remain only when clearly identified as official/upstream destinations.
- No unverified stable nightly download URL is advertised; master artifacts remain described as GitHub Actions artifacts.

## Verification

Before publishing:

- compare each fork-difference claim against `origin/master..fork/master` and current code;
- confirm branch-only Epic/PCGamingWiki claims are absent;
- validate all changed Markdown links and repository paths;
- run `git diff --check`;
- preserve the upstream merge and unrelated paused feature-branch work.
