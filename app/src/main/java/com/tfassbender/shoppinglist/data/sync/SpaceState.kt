package com.tfassbender.shoppinglist.data.sync

import com.tfassbender.shoppinglist.domain.Catalog
import com.tfassbender.shoppinglist.domain.MergeWarning
import com.tfassbender.shoppinglist.domain.ShopLayout
import com.tfassbender.shoppinglist.domain.ShoppingList
import com.tfassbender.shoppinglist.domain.SyncOp
import kotlinx.serialization.Serializable

/**
 * Everything the app keeps per space, persisted as `spaces/<spaceId>.json` (like the web app's IndexedDB
 * entry per space, plus catalog, layouts and favorites).
 */
@Serializable
data class SpaceState(
    /** Sequence number of the last successful pull (`since` / `baseSeq` of the next sync). */
    val seq: Long = 0,
    /** Server state of the lists as of [seq]. */
    val confirmed: Map<String, ShoppingList> = emptyMap(),
    /** Sent and accepted by the server, but not yet contained in a pull - still shown on top of [confirmed]. */
    val acked: List<SyncOp> = emptyList(),
    /** Not yet sent (offline or in flight). */
    val queue: List<SyncOp> = emptyList(),
    val warnings: List<MergeWarning> = emptyList(),
    /** null until the first pull. */
    val catalog: Catalog? = null,
    val layouts: List<ShopLayout> = emptyList(),
    /** My favorites per layout id (normalized names). Not part of the pull - loaded per layout. */
    val favorites: Map<String, List<String>> = emptyMap(),
)
