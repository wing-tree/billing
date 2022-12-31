package com.wing.tree.bruni.billing.model

import com.android.billingclient.api.BillingClient

sealed class Type(val productType: String) {
    sealed class Inapp : Type(BillingClient.ProductType.INAPP) {
        object Consumable : Inapp()
        object NonConsumable : Inapp()
    }

    object Subs : Type(BillingClient.ProductType.SUBS)
}
