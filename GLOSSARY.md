# myMusic PC Domain Glossary

Core domain language for the myMusic desktop client, shared between product decisions, desktop adaptation, and mobile feature parity.

## Language

### Music & Sources

**Provider**:
An external music service, self-hosted server, or custom script source that supplies catalogs, search results, lyrics, and playback streams.
_Avoid_: Platform, channel, plugin

**Canonical Track**:
A single musical recording deduplicated across one or more **Provider** entries, enabling transparent source fallback during playback.
_Avoid_: Song row, aggregated item

**Playback Stream**:
A resolved audio source with quality metadata and required transport headers, bound to a specific **Provider** instance of a **Canonical Track**.
_Avoid_: Direct URL, audio link

### Desktop Product Scope

**Desktop Shell**:
The PC-native window frame, navigation hierarchy, mouse and keyboard interaction model, and OS integration that hosts the shared music domain.
_Avoid_: Tablet shell, UI skeleton

**Core Full Milestone**:
The release target covering complete listening, multi-source fallback, authentication, playlist management, local library, cloud sync, audio effects, and desktop integration parity.
_Avoid_: MVP, basic version

**Visual Full Milestone**:
The release target extending the **Core Full Milestone** with full GPU-driven lyric stages, infinite poster wall, and lyric share cards.
_Avoid_: Eye candy, extra effects

**Platform-Degraded Capability**:
A mobile feature whose underlying mobile OS mechanism has no Windows equivalent and therefore adopts an explicit desktop alternative or fallback instead of silent omission.
_Avoid_: Unfinished feature, cut feature

### Persistence & Security

**Shared Library Store**:
The persistent local store of favorites, playback history, user playlists, local tracks, sync baselines, and preferences whose model is shared with the mobile client for lossless cross-device sync.
_Avoid_: Desktop cache, in-memory table

**Window View State**:
Desktop-only layout state such as window bounds, maximization, and sidebar or dock sizing that never participates in cross-device sync.
_Avoid_: App settings

**Credential Vault**:
OS-protected local storage for **Provider** login sessions and cloud sync tokens, isolated from plain-text configuration files.
_Avoid_: Cookie text file
