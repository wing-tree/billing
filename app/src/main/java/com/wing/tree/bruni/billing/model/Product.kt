package com.wing.tree.bruni.billing.model

data class Product(
    val id: String,
    val type: Type
) {
    val consumable: Boolean = when(type) {
        is Type.INAPP -> type.consumable
        else -> false
    }

    val productType = type.productType
}
