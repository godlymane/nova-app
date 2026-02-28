package com.nova.companion.tools

import android.content.Context
import com.nova.companion.cloud.ToolDefinition
import com.nova.companion.cloud.ToolParamDef
import org.json.JSONArray
import org.json.JSONObject

data class NovaTool(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParam>,
    val executor: suspend (Context, Map<String, Any>) -> ToolResult
)

data class ToolParam(
    val type: String,  // "string", "number", "boolean"
    val description: String,
    val required: Boolean = true
)

data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: Any? = null
)

object ToolRegistry {
    private val tools = mutableMapOf<String, NovaTool>()

    fun registerTool(tool: NovaTool) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): NovaTool? {
        return tools[name]
    }

    fun getAllTools(): Map<String, NovaTool> {
        return tools.toMap()
    }

    fun getToolDefinitionsForLLM(): List<ToolDefinition> {
        return tools.values.map { tool ->
            ToolDefinition(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters.mapValues { (_, param) ->
                    ToolParamDef(
                        type = param.type,
                        description = param.description,
                        required = param.required
                    )
                }
            )
        }
    }

    fun getToolDefinitionsAsJson(): JSONArray {
        val functions = JSONArray()

        tools.values.forEach { tool ->
            val function = JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)

                val parameters = JSONObject().apply {
                    put("type", "object")

                    val properties = JSONObject()
                    tool.parameters.forEach { (paramName, paramDef) ->
                        properties.put(paramName, JSONObject().apply {
                            put("type", paramDef.type)
                            put("description", paramDef.description)
                        })
                    }
                    put("properties", properties)

                    val required = JSONArray()
                    tool.parameters.forEach { (paramName, paramDef) ->
                        if (paramDef.required) {
                            required.put(paramName)
                        }
                    }
                    put("required", required)
                }
                put("parameters", parameters)
            }

            functions.put(JSONObject().apply {
                put("type", "function")
                put("function", function)
            })
        }

        return functions
    }

    private var initialized = false

    fun initialize(context: Context) {
        if (!initialized) {
            initializeTools(context)
            initialized = true
        }
    }

    fun initializeTools(context: Context) {
        OpenAppTool.register(this, context)
        SetAlarmTool.register(this, context)
        SetReminderTool.register(this, context)
        SendMessageTool.register(this, context)
        PhoneSettingsTool.register(this, context)
        WebSearchTool.register(this, context)
        NavigateTool.register(this, context)
        MediaControlTool.register(this, context)
    }
}
