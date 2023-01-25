package com.wing.tree.bruni.billing.model

import com.android.billingclient.api.BillingClient

sealed class Type(val productType: String) {
    data class INAPP(val consumable: Boolean) : Type(BillingClient.ProductType.INAPP)
    object SUBS : Type(BillingClient.ProductType.SUBS)
}
