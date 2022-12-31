package com.wing.tree.bruni.billing.model

data class Product(
    val id: String,
    val type: Type
) {
    val isConsumable: Boolean get() = type is Type.Inapp.Consumable
    val productType = type.productType
}
