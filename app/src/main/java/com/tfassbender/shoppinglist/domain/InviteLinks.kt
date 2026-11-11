package com.tfassbender.shoppinglist.domain

/**
 * Invite links look like `https://<web app>/invite/<token>`. Users may paste the whole link (or text around
 * it, e.g. from a chat message) or just the token. Returns null if nothing token-like is found.
 */
fun parseInviteToken(input: String): String? {
    val text = input.trim()
    val fromLink = Regex("/invite/([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)
    if (fromLink != null) return fromLink
    return text.takeIf { Regex("[A-Za-z0-9_-]{16,}").matches(it) }
}
