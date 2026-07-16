version = 2

cloudstream {
    description = "Karuppan Protection Plugin"
    authors = listOf("errorcodeQQ","Reflex1337", "zoneofmakos")
    status = 0
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
