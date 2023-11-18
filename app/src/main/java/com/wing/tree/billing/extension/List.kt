@file:Suppress("unused")

package com.wing.tree.billing.extension

import com.android.billingclient.api.Purchase
import com.wing.tree.billing.model.Product

operator fun List<Product>.get(id: String): Product? = find { it.id == id }
operator fun List<Product>.get(purchase: Purchase): Product? = find {
    purchase.products.contains(it.id)
}

fun List<Product>.consumable() = filterIsINAPP().filter { it.consumable }
fun List<Product>.contains(purchase: Purchase) = map { it.id }.containsAll(purchase.products)
fun List<Product>.filterIsINAPP() = filterIsInstance<Product.INAPP>()
fun List<Product>.filterIsSUBS() = filterIsInstance<Product.SUBS>()
