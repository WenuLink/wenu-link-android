package org.WenuLink.parameters

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

enum class SemanticType {
    BOOL,
    INT,
    FLOAT,
    ENUM
}

sealed interface ParamValue {
    data class BoolVal(val v: Boolean) : ParamValue
    data class IntVal(val v: Int) : ParamValue
    data class FloatVal(val v: Float) : ParamValue
    data class EnumVal(val v: Int) : ParamValue
}

sealed class ParameterSpec(
    val name: String,
    val type: Int,
    val semantic: SemanticType,
    var index: Int = -1
) {
    init {
        require(name.isNotBlank()) { "Parameter name cannot be blank" }
    }

    abstract fun read(onResult: (ParamValue?) -> Unit)
    abstract fun write(value: ParamValue, onResult: (String?) -> Unit)

    fun toMavlink(value: ParamValue): Int = when (value) {
        is ParamValue.BoolVal if value.v -> 1
        is ParamValue.BoolVal -> 0
        is ParamValue.IntVal -> value.v
        is ParamValue.EnumVal -> value.v
        is ParamValue.FloatVal -> value.v.toInt()
    }

    fun fromMavlink(value: Int): ParamValue = when (semantic) {
        SemanticType.BOOL -> ParamValue.BoolVal(value != 0)
        SemanticType.INT -> ParamValue.IntVal(value)
        SemanticType.FLOAT -> ParamValue.FloatVal(value.toFloat())
        SemanticType.ENUM -> ParamValue.EnumVal(value)
    }

    override fun toString(): String = name
}

data class WenuLinkParameter(val spec: ParameterSpec, var value: ParamValue) {
    override fun toString(): String = "Parameter(${spec.name}=$value)"
}

open class SimpleParameter(
    name: String,
    type: Int,
    semantic: SemanticType,
    private val reader: ((ParamValue?) -> Unit) -> Unit,
    private val writer: (ParamValue, (String?) -> Unit) -> Unit = { _, cb -> cb(null) }
) : ParameterSpec(name, type, semantic) {
    override fun read(onResult: (ParamValue?) -> Unit) {
        reader(onResult)
    }

    override fun write(value: ParamValue, onResult: (String?) -> Unit) {
        writer(value, onResult)
    }
}

interface ParameterProvider {
    fun provide(): List<ParameterSpec>
}

class ParameterRegistry(private val providers: List<ParameterProvider>) {
    private lateinit var params: List<WenuLinkParameter>
    private lateinit var byIndex: Map<Int, WenuLinkParameter>
    private lateinit var byName: Map<String, WenuLinkParameter>

    suspend fun filterAvailable(parameters: List<ParameterSpec>): List<WenuLinkParameter> =
        coroutineScope {
            parameters
                .map { spec ->
                    async {
                        val value = suspendCoroutine { cont ->
                            spec.read { cont.resume(it) }
                        }
                        value?.let { spec to it }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .mapIndexed { i, (spec, value) ->
                    spec.index = i
                    WenuLinkParameter(spec, value)
                }
        }

    fun isLoaded() = ::params.isInitialized && ::byName.isInitialized

    suspend fun load() {
        params = filterAvailable(providers.flatMap { it.provide() })

        require(params.isNotEmpty()) { "ParameterRegistry cannot be empty" }

        byIndex = params.associateBy { it.spec.index }
        byName = params.associateBy { it.spec.name.uppercase() }

        require(byIndex.size == params.size) {
            "Duplicate parameter index detected"
        }
        require(byName.size == params.size) {
            "Duplicate parameter name detected"
        }
    }

    fun getByIndex(index: Int): WenuLinkParameter? = byIndex[index]

    fun getByName(name: String): WenuLinkParameter? = byName[name.uppercase()]

    fun all(): Collection<WenuLinkParameter> = params

    fun size(): Int = params.size
}
