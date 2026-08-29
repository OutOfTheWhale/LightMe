package com.outofthewhale.groupme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class Envelope<T>(
    val response: T? = null,
    val meta: Meta? = null,
)

@Serializable
internal data class Meta(
    val code: Int = 0,
    val errors: List<String> = emptyList(),
)

@Serializable
internal data class GroupMeUser(
    val id: String = "",
    val name: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
internal data class GroupMeGroup(
    val id: String,
    val name: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("children_count") val childrenCount: Int = 0,
    val messages: GroupMessagesInfo? = null,
)

/**
 * GroupMe models a topic as a "subgroup": a child of a parent group carrying a
 * [topic] title instead of a name. Its messages come from the ordinary
 * /groups/{id}/messages endpoint, so a topic reads exactly like a group.
 */
@Serializable
internal data class GroupMeTopic(
    // Unlike groups, subgroups return numeric ids.
    val id: Long,
    val topic: String = "",
    @SerialName("parent_id") val parentId: Long? = null,
    @SerialName("updated_at") val updatedAt: Long = 0,
    val messages: GroupMessagesInfo? = null,
)

@Serializable
internal data class GroupMessagesInfo(
    val count: Int = 0,
    @SerialName("last_message_id") val lastMessageId: String? = null,
    @SerialName("last_message_created_at") val lastMessageCreatedAt: Long? = null,
    val preview: MessagePreview? = null,
)

@Serializable
internal data class MessagePreview(
    val nickname: String? = null,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val attachments: List<GroupMeAttachment> = emptyList(),
)

@Serializable
internal data class GroupMeMessage(
    val id: String,
    @SerialName("source_guid") val sourceGuid: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("group_id") val groupId: String? = null,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val text: String? = null,
    val system: Boolean = false,
    @SerialName("favorited_by") val favoritedBy: List<String> = emptyList(),
    val attachments: List<GroupMeAttachment> = emptyList(),
)

@Serializable
internal data class GroupMeAttachment(
    val type: String = "",
    val url: String? = null,
)

@Serializable
internal data class MessagesPage(
    val count: Int = 0,
    val messages: List<GroupMeMessage> = emptyList(),
)

@Serializable
internal data class SentMessageResponse(
    val message: GroupMeMessage? = null,
)

@Serializable
internal data class OutgoingMessage(
    @SerialName("source_guid") val sourceGuid: String,
    val text: String? = null,
    val attachments: List<GroupMeAttachment> = emptyList(),
)

@Serializable
internal data class OutgoingMessageRequest(
    val message: OutgoingMessage,
)

@Serializable
internal data class ImageUploadResponse(
    val payload: ImageUploadPayload? = null,
)

@Serializable
internal data class ImageUploadPayload(
    val url: String? = null,
    @SerialName("picture_url") val pictureUrl: String? = null,
)

internal data class GroupMeCredentials(
    val token: String,
    val userId: String,
    val userName: String,
)
