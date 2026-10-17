package io.github.wg1337.regtrail.shared.model

import kotlinx.serialization.Serializable

/**
 * How hard the model is allowed to think before it answers.
 *
 * ## Why this is a shared model
 * Three places need the same four values and the same wire mapping:
 *
 *  - the settings app's 设置 → Agent selector, which persists the choice;
 *  - the OpenAI-compatible client, which turns it into request parameters;
 *  - the injected module, which reads the *published* value so the floating
 *    window's Agent thinks exactly as hard as the user configured — the window
 *    cannot read SharedPreferences of the settings app, so the level has to
 *    travel through `config.json`.
 *
 * ## What the API actually accepts
 * Per the DeepSeek 思考模式 documentation (https://api-docs.deepseek.com/zh-cn/guides/thinking_mode)
 * the OpenAI-format request takes two independent controls:
 *
 * ```
 * 思考模式开关: {"thinking": {"type": "enabled/disabled"}}
 * 思考强度控制: {"reasoning_effort": "low/high/max"}
 * ```
 *
 * and the documented effort mapping is `minimal/low → low`, `medium/high/xhigh
 * → high`, `max/ultra → max`. Only the three *effective* levels are offered
 * here: asking for `xhigh` and getting `high` while the UI shows "xhigh" would
 * be a lie about what was sent.
 *
 * [Off] omits both fields' enabled state — it sends `{"thinking":{"type":
 * "disabled"}}` and no `reasoning_effort`, which is the documented way to turn
 * thinking off.
 */
@Serializable
enum class ThinkingLevel(
    /** Value sent as `reasoning_effort`; `null` means "do not send it". */
    val effort: String?,
    /** Short label for the settings chips. */
    val label: String,
    /** One sentence shown under the selector. */
    val description: String,
) {
    /** Thinking off: fastest and cheapest, no `reasoning_content` comes back. */
    Off(
        effort = null,
        label = "关闭",
        description = "不思考直接回答，最快最省；复杂分析容易漏线索",
    ),

    /** Maps to the documented `low` effort. */
    Low(
        effort = "low",
        label = "低",
        description = "少量思考，适合明确的单步问题",
    ),

    /**
     * The API default (`思考模式默认打开，且 effort 默认为 high`).
     *
     * Used as this app's default too, so an install that never touches the
     * selector behaves exactly like a plain DeepSeek request.
     */
    High(
        effort = "high",
        label = "高",
        description = "默认档：先思考再回答，适合定位类/字段与分析 Hook 结果",
    ),

    /** Maps to the documented `max` effort. */
    Max(
        effort = "max",
        label = "最高",
        description = "思考最充分，最慢也最贵；适合多步排查",
    ),
    ;

    /** `thinking.type` value for this level. */
    val thinkingType: String get() = if (effort == null) "disabled" else "enabled"

    /** Stable text stored in preferences and in the published config. */
    val wire: String get() = name.lowercase()

    companion object {
        /** What the API itself defaults to, and what an untouched install uses. */
        val DEFAULT = High

        /**
         * Parses [wire] leniently.
         *
         * A `config.json` written by a newer build, a hand-edited preference, or
         * the word `"enabled"` all have to decode to *something*: refusing would
         * break the Agent entirely for a cosmetic mismatch, so anything
         * unrecognised falls back to [DEFAULT].
         */
        fun fromWire(raw: String?): ThinkingLevel {
            val text = raw?.trim()?.lowercase().orEmpty()
            return when (text) {
                "off", "disabled", "none" -> Off
                "low", "minimal" -> Low
                "max", "ultra", "xhigh" -> Max
                "high", "medium", "enabled" -> High
                "" -> DEFAULT
                else -> entries.firstOrNull { it.wire == text } ?: DEFAULT
            }
        }
    }
}

/**
 * Whether the request body carries the thinking fields **at all**.
 *
 * ## Why this is a separate choice from [ThinkingLevel]
 * `{"thinking": …}` and `reasoning_effort` are documented for DeepSeek's
 * OpenAI-format endpoint. Other OpenAI-compatible services differ:
 * `api.openai.com` answers **HTTP 400 `Unrecognized request argument`** for a
 * top-level `thinking`, so a client that always sends it would work on DeepSeek
 * and fail on OpenAI — the exact opposite of "OpenAI-compatible".
 *
 * Three states rather than a boolean, because the honest default is neither on
 * nor off: it is "decide from the endpoint". [Auto] keeps an untouched install
 * behaving exactly as it did before this feature existed, while a user pointing
 * at a self-hosted DeepSeek gateway can say [Always] and a user on OpenAI can
 * say [Never] without editing anything else.
 */
@Serializable
enum class ThinkingWire(
    val label: String,
    val description: String,
) {
    /** Send only when the Base URL looks like DeepSeek. */
    Auto(
        label = "自动",
        description = "Base URL 含 deepseek 时发送；其它接口按原样（不带这两个字段）请求",
    ),

    /** Always send, for a gateway whose hostname is not `deepseek`. */
    Always(
        label = "始终发送",
        description = "总是发送 thinking / reasoning_effort；用于自建的 DeepSeek 兼容网关",
    ),

    /** Never send, for endpoints that reject unknown fields. */
    Never(
        label = "从不发送",
        description = "不发送这两个字段；官方 OpenAI 等严格校验的接口需要这样",
    ),
    ;

    /** Stable text stored in preferences and in the published config. */
    val wire: String get() = name.lowercase()

    /**
     * Resolves the actual decision for a given endpoint.
     *
     * [Auto] keys off the hostname rather than a provider list because the list
     * cannot be kept complete: any proxy of `api.deepseek.com` keeps the name,
     * and every genuinely different provider does not.
     */
    fun sendsFor(baseUrl: String): Boolean = when (this) {
        Auto -> baseUrl.contains("deepseek", ignoreCase = true)
        Always -> true
        Never -> false
    }

    companion object {
        /**
         * Parses [wire] leniently, defaulting to [Auto].
         *
         * `null`, `""` and anything unrecognised all mean "an older config /
         * an untouched preference", which must behave like [Auto].
         */
        fun fromWire(raw: String?): ThinkingWire {
            val text = raw?.trim()?.lowercase().orEmpty()
            return when (text) {
                "always", "on", "true" -> Always
                "never", "off", "false" -> Never
                else -> Auto
            }
        }
    }
}
