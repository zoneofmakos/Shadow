version = 3

cloudstream {
    authors = listOf("zoneofmakos")
    status = 1
    tvTypes = listOf("Others")
    requiresResources = false
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/zoneofmakos/Shadow/main/Michael/icon.png"
}

android {
    namespace = "com.shadow"
    lint { abortOnError = false }
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
}
