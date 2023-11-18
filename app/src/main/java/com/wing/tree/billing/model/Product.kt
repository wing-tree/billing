package com.wing.tree.billing.model

import com.android.billingclient.api.BillingClient

sealed class Product {
    abstract val id: String
    abstract val productType: String

    data class INAPP(
        override val id: String,
        val consumable: Boolean
    ) : Product() {
        override val productType: String
            get() = BillingClient.ProductType.INAPP
    }

    data class SUBS(
        override val id: String
    ) : Product() {
        override val productType: String
            get() = BillingClient.ProductType.SUBS
    }
}
