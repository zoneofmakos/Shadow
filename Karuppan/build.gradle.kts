version = 2

cloudstream {
    authors = listOf("errorcodeQQ","Reflex1337", "zoneofmakos")
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
