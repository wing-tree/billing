package com.wing.tree.bruni.billing.extension

import com.wing.tree.bruni.billing.model.Product

operator fun List<Product>.get(id: String) = find { it.id == id }

fun List<Product>.consumable() = filterIsINAPP().filter { it.consumable }
fun List<Product>.filterIsINAPP() = filterIsInstance<Product.INAPP>()
fun List<Product>.filterIsSUBS() = filterIsInstance<Product.SUBS>()
