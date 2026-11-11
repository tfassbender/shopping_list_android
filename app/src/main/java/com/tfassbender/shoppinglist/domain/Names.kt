package com.tfassbender.shoppinglist.domain

/** Same normalization as the server (`SpaceCatalog.normalize`): trimmed, lowercase, single spaces. */
fun normalizeName(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * Input limits of the server (see doc/server_api.md, "Limits"). Changes queued offline are only checked by
 * the server at the next sync - validating them here gives the user immediate feedback instead of a
 * rejected change later. Each function returns the server's error code, or null if the input is valid.
 */
object Limits {
    const val NAME_LENGTH = 60 // space, list, layout
    const val ITEM_NAME_LENGTH = 100
    const val QUANTITY_LENGTH = 20
    const val UNIT_LENGTH = 20
    const val NOTE_LENGTH = 200
    const val CATEGORY_NAME_LENGTH = 40
    const val ITEMS_PER_LIST = 1000

    /** Space, list and layout names. */
    fun checkName(name: String): String? = if (name.isBlank() || name.trim().length > NAME_LENGTH) "INVALID_NAME" else null

    fun checkItemName(name: String): String? =
        if (name.isBlank() || name.trim().length > ITEM_NAME_LENGTH) "INVALID_ITEM_NAME" else null

    fun checkItemField(field: ItemField, value: String?): String? = when {
        field == ItemField.NAME -> checkItemName(value.orEmpty())
        (value?.trim()?.length ?: 0) > field.maxLength -> "INVALID_ITEM_FIELD"
        else -> null
    }
}

/** The editable fields of an item (server `ItemField`). */
enum class ItemField(val maxLength: Int) {
    NAME(Limits.ITEM_NAME_LENGTH),
    QUANTITY(Limits.QUANTITY_LENGTH),
    UNIT(Limits.UNIT_LENGTH),
    NOTE(Limits.NOTE_LENGTH),
    CATEGORY(64),
}
