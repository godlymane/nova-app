package com.nova.companion.tools.tier3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.nova.companion.accessibility.NovaAccessibilityService
import com.nova.companion.accessibility.UIAutomator
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.delay

object PostInstagramStoryToolExecutor {

    private const val TAG = "PostIGStoryTool"
    private const val IG_PACKAGE = "com.instagram.android"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "postInstagramStory",
            description = "Post an Instagram Story. Can open the story camera for a new photo/video, or share the latest photo from gallery as a story.",
            parameters = mapOf(
                "mode" to ToolParam(
                    type = "string",
                    description = "How to create the story: 'camera' to open story camera, 'latest_photo' to share latest gallery photo as story. Default is 'camera'.",
                    required = false
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val mode = (params["mode"] as? String)?.trim()?.lowercase() ?: "camera"

            // Check if Instagram is installed
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(IG_PACKAGE)
                ?: return ToolResult(false, "Instagram is not installed on this device")

            when (mode) {
                "latest_photo" -> shareLatestPhotoToStory(context)
                else -> openStoryCameraViaUI(context, launchIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post Instagram story", e)
            ToolResult(false, "Failed to post Instagram story: ${e.message}")
        }
    }

    /**
     * Opens Instagram and swipes right to enter the story camera.
     * Falls back to tapping "Your story" / camera icon if swipe fails.
     */
    private suspend fun openStoryCameraViaUI(context: Context, launchIntent: Intent): ToolResult {
        // Step 1: Open Instagram
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        context.startActivity(launchIntent)
        delay(2500) // Wait for Instagram to load

        // Step 2: Check if accessibility service is available
        if (!NovaAccessibilityService.isRunning()) {
            return ToolResult(
                true,
                "Opened Instagram. Swipe right from the home feed to create a story (accessibility service not enabled for auto-navigation)."
            )
        }

        val service = NovaAccessibilityService.instance
            ?: return ToolResult(true, "Opened Instagram. Swipe right to create a story.")

        // Step 3: Try tapping the "+" create button first (bottom nav), then "Your story"
        var tapped = UIAutomator.tapByDescription("Create")
        if (tapped) {
            delay(1000)
            // Instagram shows a bottom sheet: Post, Story, Reel, Live — tap Story
            val storyTapped = UIAutomator.tapByText("Story") || UIAutomator.tapByText("STORY")
            if (storyTapped) {
                delay(1500)
                Log.i(TAG, "Opened Instagram story camera via Create > Story")
                return ToolResult(true, "Opened Instagram story camera. Take a photo or video to post your story!")
            }
        }

        // Step 4: Swipe right from feed to open story camera (classic gesture)
        val displayMetrics = service.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val swiped = service.swipe(
            startX = screenWidth * 0.05f,
            startY = screenHeight * 0.5f,
            endX = screenWidth * 0.85f,
            endY = screenHeight * 0.5f,
            durationMs = 350
        )

        if (swiped) {
            delay(1500)
            Log.i(TAG, "Opened Instagram story camera via swipe right")
            return ToolResult(true, "Opened Instagram story camera. Take a photo or video to post your story!")
        }

        // Step 5: Fallback — try tapping profile picture / "Your story" text
        tapped = UIAutomator.tapByDescription("Your story")
        if (!tapped) tapped = UIAutomator.tapByText("Your story")
        if (!tapped) tapped = UIAutomator.tapByDescription("Camera")
        if (!tapped) tapped = UIAutomator.tapByDescription("New story")

        if (tapped) {
            delay(1500)
            Log.i(TAG, "Opened Instagram story creation via tap fallback")
            return ToolResult(true, "Opened Instagram story creation. Take a photo or video to post!")
        }

        return ToolResult(true, "Opened Instagram. Swipe right from the feed to create a story!")
    }

    /**
     * Finds the latest photo in the device gallery and shares it to Instagram
     * via ACTION_SEND intent. Instagram will show options including Story.
     */
    private fun shareLatestPhotoToStory(context: Context): ToolResult {
        // Query the latest photo from MediaStore
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        val photoUri: Uri? = cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            } else null
        }

        if (photoUri == null) {
            return ToolResult(false, "No photos found on the device to share")
        }

        // Share to Instagram via intent — user picks Story/Feed/Message
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, photoUri)
            setPackage(IG_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            context.startActivity(shareIntent)
            Log.i(TAG, "Shared latest photo to Instagram: $photoUri")
            ToolResult(true, "Sharing your latest photo to Instagram. Select 'Story' to post it as a story!")
        } catch (e: Exception) {
            Log.w(TAG, "Direct share to Instagram failed, trying chooser", e)
            val chooserIntent = Intent.createChooser(shareIntent, "Share to Instagram").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
            ToolResult(true, "Opening share menu with your latest photo. Select Instagram to post!")
        }
    }
}
