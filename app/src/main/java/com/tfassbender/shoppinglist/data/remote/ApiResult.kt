package com.tfassbender.shoppinglist.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

/** Everything that can go wrong with an API call. */
sealed interface ApiError {

    /**
     * The server answered with an error status. [code] is its stable error code (e.g. `LIST_NOT_FOUND`,
     * see doc/server_api.md) - the UI translates it; [message] is the server's English text (logs only).
     */
    data class Server(val status: Int, val code: String?, val message: String?) : ApiError

    /** No connection / timeout - offline, or the server is down. Online-only actions report this. */
    data class Network(val cause: IOException) : ApiError

    /** 2xx, but the body didn't match the generated model (spec and server out of sync?). */
    data class InvalidResponse(val cause: Exception) : ApiError
}

val ApiError.code: String? get() = (this as? ApiError.Server)?.code
val ApiError.status: Int? get() = (this as? ApiError.Server)?.status
val ApiError.isNetwork: Boolean get() = this is ApiError.Network

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}

inline fun <T> ApiResult<T>.onSuccess(action: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Success) action(value)
    return this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.value

private val errorJson = Json { ignoreUnknownKeys = true }

/** Executes a generated Retrofit call and maps every outcome to an [ApiResult]. */
suspend fun <T : Any> apiCall(call: suspend () -> Response<T>): ApiResult<T> {
    val response = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        return ApiResult.Failure(ApiError.Network(e))
    } catch (e: SerializationException) { // incl. MissingFieldException
        return ApiResult.Failure(ApiError.InvalidResponse(e))
    }

    val body = response.body()
    if (response.isSuccessful && body != null) return ApiResult.Success(body)
    if (response.isSuccessful) {
        return ApiResult.Failure(ApiError.InvalidResponse(IllegalStateException("empty response body")))
    }
    val errorBody = try {
        response.errorBody()?.string()
    } catch (_: IOException) {
        null
    }
    return ApiResult.Failure(parseError(response.code(), errorBody))
}

/** For endpoints that answer `204 No Content` (Retrofit gives a null body there, which [apiCall] would reject). */
suspend fun apiCallNoContent(call: suspend () -> Response<Unit>): ApiResult<Unit> = apiCall {
    val response = call()
    if (response.isSuccessful && response.body() == null) Response.success(Unit, response.raw()) else response
}

/** The server's error body is `{"code": "...", "message": "..."}`; anything else (proxy page) gives nulls. */
internal fun parseError(status: Int, body: String?): ApiError.Server {
    val json = try {
        body?.takeIf { it.isNotBlank() }?.let { errorJson.parseToJsonElement(it).jsonObject }
    } catch (_: IllegalArgumentException) { // also covers SerializationException
        null
    }
    return ApiError.Server(
        status = status,
        code = json?.get("code")?.jsonPrimitive?.contentOrNull,
        message = json?.get("message")?.jsonPrimitive?.contentOrNull,
    )
}
