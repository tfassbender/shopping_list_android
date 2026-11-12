package com.tfassbender.shoppinglist.ui.common

import androidx.annotation.StringRes
import com.tfassbender.shoppinglist.R

// Generated from the error_* strings (which come from the web app's i18n) - one entry per server error code.

/** The message for a server error code; null if there is none (the caller shows a generic message). */
@StringRes
fun errorMessageRes(code: String?): Int? = when (code) {
    "HISTORY_ENTRY_NOT_FOUND" -> R.string.error_HISTORY_ENTRY_NOT_FOUND
    "NETWORK_ERROR" -> R.string.error_NETWORK_ERROR
    "INVALID_EMAIL" -> R.string.error_INVALID_EMAIL
    "INVALID_DISPLAY_NAME" -> R.string.error_INVALID_DISPLAY_NAME
    "PASSWORD_TOO_SHORT" -> R.string.error_PASSWORD_TOO_SHORT
    "PASSWORD_TOO_LONG" -> R.string.error_PASSWORD_TOO_LONG
    "EMAIL_TAKEN" -> R.string.error_EMAIL_TAKEN
    "INVALID_CREDENTIALS" -> R.string.error_INVALID_CREDENTIALS
    "EMAIL_NOT_VERIFIED" -> R.string.error_EMAIL_NOT_VERIFIED
    "INVALID_TOKEN" -> R.string.error_INVALID_TOKEN
    "WRONG_PASSWORD" -> R.string.error_WRONG_PASSWORD
    "EMAIL_DISABLED" -> R.string.error_EMAIL_DISABLED
    "INVALID_REQUEST" -> R.string.error_INVALID_REQUEST
    "FORBIDDEN" -> R.string.error_FORBIDDEN
    "NOT_FOUND" -> R.string.error_NOT_FOUND
    "INVALID_NAME" -> R.string.error_INVALID_NAME
    "SPACE_NOT_FOUND" -> R.string.error_SPACE_NOT_FOUND
    "OWNER_ONLY" -> R.string.error_OWNER_ONLY
    "OWNER_CANNOT_LEAVE" -> R.string.error_OWNER_CANNOT_LEAVE
    "INVITE_INVALID" -> R.string.error_INVITE_INVALID
    "ALREADY_MEMBER" -> R.string.error_ALREADY_MEMBER
    "INVALID_EXPIRY" -> R.string.error_INVALID_EXPIRY
    "LIST_NOT_FOUND" -> R.string.error_LIST_NOT_FOUND
    "ITEM_NOT_FOUND" -> R.string.error_ITEM_NOT_FOUND
    "INVALID_ITEM_NAME" -> R.string.error_INVALID_ITEM_NAME
    "INVALID_ITEM_FIELD" -> R.string.error_INVALID_ITEM_FIELD
    "LIST_FULL" -> R.string.error_LIST_FULL
    "INVALID_COLOR" -> R.string.error_INVALID_COLOR
    "CATEGORY_NOT_FOUND" -> R.string.error_CATEGORY_NOT_FOUND
    "CATEGORY_EXISTS" -> R.string.error_CATEGORY_EXISTS
    "INVALID_UNIT" -> R.string.error_INVALID_UNIT
    "INVALID_ORDER" -> R.string.error_INVALID_ORDER
    "INVALID_ID" -> R.string.error_INVALID_ID
    "LAYOUT_NOT_FOUND" -> R.string.error_LAYOUT_NOT_FOUND
    "TOO_MANY_TOKENS" -> R.string.error_TOO_MANY_TOKENS
    "TOKEN_NOT_FOUND" -> R.string.error_TOKEN_NOT_FOUND
    "READ_ONLY_TOKEN" -> R.string.error_READ_ONLY_TOKEN
    "SESSION_REQUIRED" -> R.string.error_SESSION_REQUIRED
    "INVITE_NOT_FOUND" -> R.string.error_INVITE_NOT_FOUND
    "MEMBER_NOT_FOUND" -> R.string.error_MEMBER_NOT_FOUND
    "USER_NOT_FOUND" -> R.string.error_USER_NOT_FOUND
    "SESSION_EXPIRED" -> R.string.error_SESSION_EXPIRED
    "INVALID_LANGUAGE" -> R.string.error_INVALID_LANGUAGE
    "LIST_EXISTS" -> R.string.error_LIST_EXISTS
    "LAYOUT_EXISTS" -> R.string.error_LAYOUT_EXISTS
    "ITEM_EXISTS" -> R.string.error_ITEM_EXISTS
    "TOO_MANY_CATEGORIES" -> R.string.error_TOO_MANY_CATEGORIES
    "TOO_MANY_UNITS" -> R.string.error_TOO_MANY_UNITS
    "INVALID_CHANGE" -> R.string.error_INVALID_CHANGE
    "BATCH_TOO_LARGE" -> R.string.error_BATCH_TOO_LARGE
    "INTERNAL_ERROR" -> R.string.error_INTERNAL_ERROR
    else -> null
}
