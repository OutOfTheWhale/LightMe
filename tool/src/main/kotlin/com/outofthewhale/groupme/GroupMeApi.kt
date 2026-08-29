package com.outofthewhale.groupme

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.UUID

private const val API_BASE = "https://api.groupme.com/v3"
private const val IMAGE_SERVICE = "https://image.groupme.com/pictures"

internal class GroupMeApi(val token: String) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun getMe(): Result<GroupMeUser> = runCatching {
        val response = client.get("$API_BASE/users/me") { auth() }
        response.requireSuccess()
        response.body<Envelope<GroupMeUser>>().response
            ?: throw IllegalStateException("Empty response from GroupMe.")
    }

    suspend fun getGroups(): Result<List<GroupMeGroup>> = runCatching {
        val response = client.get("$API_BASE/groups?per_page=100&omit=memberships") { auth() }
        response.requireSuccess()
        response.body<Envelope<List<GroupMeGroup>>>().response.orEmpty()
    }

    /** Newest first. [beforeId] pages backwards through history. */
    suspend fun getMessages(
        groupId: String,
        beforeId: String? = null,
        limit: Int = 50,
    ): Result<List<GroupMeMessage>> = runCatching {
        val before = if (beforeId != null) "&before_id=$beforeId" else ""
        val response = client.get("$API_BASE/groups/$groupId/messages?limit=$limit$before") { auth() }
        // GroupMe returns 304 with an empty body when there are no (more) messages.
        if (response.status.value == 304) return@runCatching emptyList()
        response.requireSuccess()
        response.body<Envelope<MessagesPage>>().response?.messages.orEmpty()
    }

    suspend fun sendMessage(
        groupId: String,
        text: String,
        attachments: List<GroupMeAttachment> = emptyList(),
    ): Result<GroupMeMessage> = runCatching {
        val response = client.post("$API_BASE/groups/$groupId/messages") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(
                OutgoingMessageRequest(
                    OutgoingMessage(
                        sourceGuid = UUID.randomUUID().toString(),
                        text = text,
                        attachments = attachments,
                    )
                )
            )
        }
        response.requireSuccess()
        response.body<Envelope<SentMessageResponse>>().response?.message
            ?: throw IllegalStateException("GroupMe did not return the sent message.")
    }

    /** Uploads image bytes to the GroupMe image service and returns the attachment URL. */
    suspend fun uploadImage(bytes: ByteArray, contentType: String = "image/jpeg"): Result<String> =
        runCatching {
            val response = client.post(IMAGE_SERVICE) {
                header("X-Access-Token", token)
                header("Content-Type", contentType)
                setBody(bytes)
            }
            response.requireSuccess()
            response.body<ImageUploadResponse>().payload?.pictureUrl
                ?: throw IllegalStateException("Image service did not return a URL.")
        }

    fun close() {
        client.close()
    }

    private fun HttpRequestBuilder.auth() {
        header("X-Access-Token", token)
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (!status.isSuccess()) {
            val body = bodyAsText().take(300)
            throw GroupMeApiException(status.value, "GroupMe HTTP ${status.value}: $body")
        }
    }
}

internal class GroupMeApiException(val statusCode: Int, message: String) : Exception(message) {
    val isUnauthorized: Boolean get() = statusCode == 401
}
