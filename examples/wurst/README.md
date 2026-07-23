# Wurst 7 example

Wurst is a normal Fabric mod: it declares `net.wurstclient.WurstInitializer` as a `main` entrypoint, so mc-graft needs
no bootstrap override — it just calls `onInitialize()`.

    ./gradlew agentJar \
      -Ppayload=/path/to/Wurst-Client-v7.54-MC26.2.jar \
      -PmixinTargets=/path/to/wurst-mixin-targets.txt \
      -PagentName=wurst-agent.jar

`mixin-targets.txt` is the list of Minecraft classes Wurst's mixins target (one per line, dotted names).
