package com.aisocial.copilot.core

/** Small result type used across the data/domain layers instead of throwing. */
sealed interface NetResult<out T> {
    data class Success<T>(val value: T) : NetResult<T>
    data class Failure(val message: String, val detail: String? = null) : NetResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun valueOrNull(): T? = (this as? Success)?.value
}

inline fun <T, R> NetResult<T>.map(transform: (T) -> R): NetResult<R> = when (this) {
    is NetResult.Success -> NetResult.Success(transform(value))
    is NetResult.Failure -> this
}
