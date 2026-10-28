package com.clma.ai.provider

import kotlinx.serialization.Serializable

@Serializable
data class BalanceOption(
    val enabled: Boolean = false,
    val apiPath: String = "/credits",
    val resultPath: String = "data.total_usage",
    val method: BalanceMethod = BalanceMethod.GET,
    val requestBody: String = "{}",
    val headers: List<CustomHeader> = emptyList(),
    val decimals: Int = 2,
    val prefix: String = "",
    val suffix: String = "",
    val animate: Boolean = true,
)

@Serializable
enum class BalanceMethod { GET, POST }
