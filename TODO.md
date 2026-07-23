# mc-graft — TODO

## Extract `vspike` into its own thing
`mixinservice/` (package `vspike`) is the standalone Sponge-Mixin service mc-graft
uses on bare vanilla (its own `MixinService`/`AccessWidener`/bytecode provider,
plus `McefNative`). It's currently vendored inside this repo. Turn it into its own
standalone project/artifact that mc-graft depends on, so:
- it can be versioned + published independently,
- the MCEF native-staging helper (`vspike.McefNative`, an optional payload-native
  stager) lives with it rather than in the injection engine, and
- the engine depends on a clean published coordinate instead of a sibling source set.

## Notes
- The MCEF native staging is currently the one remaining payload-specific helper
  (it targets LiquidBounce's in-game CEF browser). It's opt-in (only fires when the
  native is bundled), but as part of the vspike extraction it should be generalized
  into a payload-declared "native library to stage" hook or dropped from the core.
