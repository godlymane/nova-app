package com.nova.companion.tools.tier3

import com.nova.companion.tools.ToolRegistry

object Tier3ToolRegistry {

    fun registerAll(registry: ToolRegistry) {
        OrderFoodToolExecutor.register(registry)
        BookRideToolExecutor.register(registry)
        PlaySpotifyToolExecutor.register(registry)
        PlayYouTubeToolExecutor.register(registry)
        SendEmailToolExecutor.register(registry)
        GetWeatherToolExecutor.register(registry)
        GetDirectionsToolExecutor.register(registry)
        ShareContentToolExecutor.register(registry)
        TranslateTextToolExecutor.register(registry)
        QuickNoteToolExecutor.register(registry)
    }
}
