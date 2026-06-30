version = 2

cloudstream {
    description = "Michael Protection Plugin"
    authors = listOf("zoneofmakos")
    status = 1
    tvTypes = listOf("Others")
    requiresResources = false
    language = "en"
}

android {
    namespace = "com.shadow"
    lint { abortOnError = false }
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
}
