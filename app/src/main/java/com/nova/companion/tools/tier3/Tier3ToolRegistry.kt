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
        PostInstagramStoryToolExecutor.register(registry)
        RunLearnedRoutineToolExecutor.register(registry)
        // New API-powered tools
        GetTimeZoneToolExecutor.register(registry)      // fixes timezone → weather bug
        BrowserlessToolExecutor.register(registry)      // web browsing
        GitHubToolExecutor.register(registry)           // GitHub repos/PRs/issues
        YouTubeToolExecutor.register(registry)          // YouTube search with metadata
        GooglePlacesToolExecutor.register(registry)     // nearby places
        // Google device data (no OAuth — uses Android ContentProviders)
        GoogleCalendarToolExecutor.register(registry)   // read/create calendar events
        SearchContactsToolExecutor.register(registry)   // find contacts by name/number
        // Life management tools
        ExpenseToolExecutor.register(registry)           // expense tracking + budget insights
        FitnessToolExecutor.register(registry)           // steps, workouts, water, sleep
        TaskToolExecutor.register(registry)              // task management + to-do lists
        AppUsageToolExecutor.register(registry)          // screen time + app usage analytics
        FocusModeToolExecutor.register(registry)         // DND + focus sessions + pomodoro
        SmartRoutineToolExecutor.register(registry)      // automated routines engine
        MorningBriefingToolExecutor.register(registry)   // daily morning briefing
    }
}
