@file:Suppress("UnstableApiUsage")

version = 7

android {
    defaultConfig {
        android.buildFeatures.buildConfig = true
    }
}

cloudstream {
    language = "ta"
    requiresResources = true
    authors = listOf("zoneofmakos")

    status = 1
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://raw.githubusercontent.com/zoneofmakos/Shadow/main/ShadowTV/television.png"
}
