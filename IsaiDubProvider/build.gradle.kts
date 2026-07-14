// Use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Tamil dubbed movies and web series"
    language = "ta"
    authors = listOf("zoneofmakos")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://github.com/zoneofmakos/Shadow/raw/refs/heads/main/IsaiDubProvider/icon.png"
}
