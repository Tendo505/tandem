package io.github.tendo505.tandem.app.sync

import io.github.tendo505.tandem.core.ErrorResponse
import io.github.tendo505.tandem.core.ProtocolJson
import io.github.tendo505.tandem.core.PullResponse
import io.github.tendo505.tandem.core.PushRequest
import io.github.tendo505.tandem.core.PushResponse
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException

interface TandemService {
    @POST("api/lists/{listId}/ops")
    suspend fun push(@Path("listId") listId: String, @Body body: PushRequest): PushResponse

    @GET("api/lists/{listId}/ops")
    suspend fun pull(@Path("listId") listId: String, @Query("after") after: Long, @Query("limit") limit: Int): PullResponse
}

class RetrofitSyncApi(baseUrl: String) : SyncApi {
    private val service: TandemService = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .addConverterFactory(ProtocolJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create()

    override suspend fun push(listId: String, request: PushRequest) = call { service.push(listId, request) }

    override suspend fun pull(listId: String, after: Long, limit: Int) = call { service.pull(listId, after, limit) }

    /** 4xx means the server looked at the request and refused it; anything else is worth retrying later. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        if (e.code() in 400..499) {
            val body = e.response()?.errorBody()?.string()
            val reason = body?.let { runCatching { ProtocolJson.decodeFromString<ErrorResponse>(it).error }.getOrNull() }
            throw SyncRejectedException(reason ?: "HTTP ${e.code()}")
        }
        throw IOException("server error ${e.code()}", e)
    } catch (e: SerializationException) {
        throw IOException("unexpected response from server", e)
    }
}
