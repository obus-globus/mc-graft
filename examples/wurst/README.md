# Wurst 7 example

Wurst is a normal Fabric mod: it declares `net.wurstclient.WurstInitializer` as a `main` entrypoint, so mc-graft needs
no bootstrap override — it just calls `onInitialize()`. The mixin target list is auto-derived from `wurst.mixins.json`.

    ./gradlew agentJar -Ppayload=/path/to/Wurst-Client-v7.54-MC26.2.jar -PagentName=wurst-agent.jar

That's the whole command — mixin configs, access-widener, entrypoint, and target classes all come from the jar.
