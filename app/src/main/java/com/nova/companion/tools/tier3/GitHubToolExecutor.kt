package com.nova.companion.tools.tier3

import android.content.Context
import android.util.Log
import com.nova.companion.BuildConfig
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub tool — Nova can check your repos, PRs, issues, and commits.
 * "What PRs are open on nova-app?" / "Any new issues on Blayzex?" / "Latest commits?"
 */
object GitHubToolExecutor {

    private const val TAG = "GitHubTool"
    private const val API = "https://api.github.com"

    fun register(registry: ToolRegistry) {
        registry.registerTool(NovaTool(
            name = "github",
            description = "Check GitHub repos, pull requests, issues, and commits. " +
                    "Use for questions like 'what PRs are open', 'any new issues', " +
                    "'show me recent commits', 'what repos do I have'.",
            parameters = mapOf(
                "action" to ToolParam(
                    type = "string",
                    description = "What to do: 'repos' (list your repos), 'prs' (open pull requests), " +
                            "'issues' (open issues), 'commits' (recent commits)",
                    required = true
                ),
                "repo" to ToolParam(
                    type = "string",
                    description = "Repo name like 'nova-app' or 'owner/repo'. Required for prs/issues/commits.",
                    required = false
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        ))
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val action = (params["action"] as? String)?.trim() ?: "repos"
        val repo = (params["repo"] as? String)?.trim()

        if (BuildConfig.GITHUB_TOKEN.isBlank()) {
            return ToolResult(false, "GitHub token not configured")
        }

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "repos" -> listRepos()
                    "prs" -> listPRs(repo)
                    "issues" -> listIssues(repo)
                    "commits" -> listCommits(repo)
                    else -> ToolResult(false, "Unknown action: $action. Use repos/prs/issues/commits")
                }
            } catch (e: Exception) {
                Log.e(TAG, "GitHub API error for action=$action", e)
                ToolResult(false, "GitHub error: ${e.message}")
            }
        }
    }

    private fun listRepos(): ToolResult {
        val json = get("$API/user/repos?sort=updated&per_page=10") ?: return ToolResult(false, "Failed to fetch repos")
        val arr = JSONArray(json)
        if (arr.length() == 0) return ToolResult(true, "No repos found")
        val sb = StringBuilder("Your repos:\n")
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val name = r.getString("name")
            val desc = r.optString("description", "").let { if (it.isBlank()) "" else " — $it" }
            val stars = r.getInt("stargazers_count")
            sb.append("• $name$desc ⭐$stars\n")
        }
        return ToolResult(true, sb.toString().trim())
    }

    private fun listPRs(repo: String?): ToolResult {
        val fullRepo = resolveRepo(repo) ?: return ToolResult(false, "Specify a repo name")
        val json = get("$API/repos/$fullRepo/pulls?state=open&per_page=10") ?: return ToolResult(false, "Failed to fetch PRs")
        val arr = JSONArray(json)
        if (arr.length() == 0) return ToolResult(true, "No open PRs on $fullRepo")
        val sb = StringBuilder("Open PRs on $fullRepo:\n")
        for (i in 0 until arr.length()) {
            val pr = arr.getJSONObject(i)
            val num = pr.getInt("number")
            val title = pr.getString("title")
            val author = pr.getJSONObject("user").getString("login")
            sb.append("• #$num: $title (by $author)\n")
        }
        return ToolResult(true, sb.toString().trim())
    }

    private fun listIssues(repo: String?): ToolResult {
        val fullRepo = resolveRepo(repo) ?: return ToolResult(false, "Specify a repo name")
        val json = get("$API/repos/$fullRepo/issues?state=open&per_page=10") ?: return ToolResult(false, "Failed to fetch issues")
        val arr = JSONArray(json)
        if (arr.length() == 0) return ToolResult(true, "No open issues on $fullRepo")
        val sb = StringBuilder("Open issues on $fullRepo:\n")
        for (i in 0 until arr.length()) {
            val issue = arr.getJSONObject(i)
            if (issue.has("pull_request")) continue // skip PRs listed as issues
            val num = issue.getInt("number")
            val title = issue.getString("title")
            sb.append("• #$num: $title\n")
        }
        return ToolResult(true, sb.toString().trim())
    }

    private fun listCommits(repo: String?): ToolResult {
        val fullRepo = resolveRepo(repo) ?: return ToolResult(false, "Specify a repo name")
        val json = get("$API/repos/$fullRepo/commits?per_page=5") ?: return ToolResult(false, "Failed to fetch commits")
        val arr = JSONArray(json)
        if (arr.length() == 0) return ToolResult(true, "No commits found on $fullRepo")
        val sb = StringBuilder("Recent commits on $fullRepo:\n")
        for (i in 0 until arr.length()) {
            val commit = arr.getJSONObject(i)
            val sha = commit.getString("sha").take(7)
            val msg = commit.getJSONObject("commit").getString("message").lines().first().take(60)
            val author = commit.getJSONObject("commit").getJSONObject("author").getString("name")
            sb.append("• [$sha] $msg — $author\n")
        }
        return ToolResult(true, sb.toString().trim())
    }

    /** If repo is just a name (no slash), try to find it in user's repos */
    private fun resolveRepo(repo: String?): String? {
        if (repo.isNullOrBlank()) return null
        return if (repo.contains("/")) repo else {
            // Try fetching user info to prepend username
            val userJson = get("$API/user") ?: return repo
            val login = JSONObject(userJson).optString("login", "")
            if (login.isNotBlank()) "$login/$repo" else repo
        }
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.GITHUB_TOKEN}")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            if (conn.responseCode != 200) {
                Log.w(TAG, "GitHub HTTP ${conn.responseCode} for $url")
                return null
            }
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            Log.e(TAG, "GET $url failed", e)
            null
        } finally {
            conn.disconnect()
        }
    }
}
