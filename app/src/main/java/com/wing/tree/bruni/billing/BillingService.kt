package com.wing.tree.bruni.billing

import android.app.Activity
import android.content.Context
import arrow.core.Either
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.wing.tree.bruni.billing.model.Product
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import org.json.JSONObject

class BillingService(context: Context, private val products: List<Product>) {
    private val ioDispatcher = Dispatchers.IO
    private val coroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _purchases = Channel<Either<BillingResult, Purchase>> {
        if (it is Either.Right) {
            val purchase = it.value

            coroutineScope.launch {
                purchase.products.forEach { id ->
                    products.find { product ->
                        product.id == id
                    }?.let { product ->
                        if (product.isConsumable) {
                            consumePurchase(purchase)
                        } else {
                            acknowledgePurchase(purchase)
                        }
                    }
                }
            }
        }
    }

    val purchases: ReceiveChannel<Either<BillingResult, Purchase>> get() = _purchases

    private val purchasesUpdatedListener by lazy {
        PurchasesUpdatedListener { billingResult, purchases ->
            purchases?.let {
                when (billingResult.responseCode) {
                    BillingResponseCode.OK -> {
                        coroutineScope.launch {
                            for (purchase in purchases) {
                                _purchases.send(Either.Right(purchase))
                            }
                        }
                    }
                    else -> {
                        coroutineScope.launch {
                            _purchases.send(Either.Left(billingResult))
                        }
                    }
                }
            }
        }
    }

    private val billingClient by lazy {
        BillingClient
            .newBuilder(context.applicationContext)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()
    }

    fun endConnection() {
        billingClient.endConnection()
    }

    fun launchBillingFlow(
        activity: Activity,
        productDetails: ProductDetails,
        offerToken: String? = null
    ): BillingResult {
        val builder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)

        offerToken?.let {
            builder.setOfferToken(it)
        }

        val productDetailsParams = builder.build()
        val productDetailsParamsList = listOf(productDetailsParams)

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        return billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    fun queryPurchases(productType: String) {
        val builder = QueryPurchasesParams.newBuilder()
        val queryPurchasesParams = builder
            .setProductType(productType)
            .build()

        billingClient.queryPurchasesAsync(queryPurchasesParams) { billingResult, purchases ->
            when (billingResult.responseCode) {
                BillingResponseCode.OK -> {
                    coroutineScope.launch {
                        for (purchase in purchases) {
                            _purchases.send(Either.Right(purchase))
                        }
                    }
                }
                else -> {
                    coroutineScope.launch {
                        _purchases.send(Either.Left(billingResult))
                    }
                }
            }
        }
    }

    fun queryPurchases(queryPurchasesParams: QueryPurchasesParams) {
        billingClient.queryPurchasesAsync(queryPurchasesParams) { billingResult, purchases ->
            when (billingResult.responseCode) {
                BillingResponseCode.OK -> {
                    coroutineScope.launch {
                        for (purchase in purchases) {
                            _purchases.send(Either.Right(purchase))
                        }
                    }
                }
                else -> {
                    coroutineScope.launch {
                        _purchases.send(Either.Left(billingResult))
                    }
                }
            }
        }
    }

    fun startConnection(billingClientStateListener: BillingClientStateListener) {
        billingClient.startConnection(billingClientStateListener)
    }

    suspend fun acknowledgePurchase(purchase: Purchase): Either<BillingResult, Purchase> {
        return if (purchase.isAcknowledged) {
            Either.Right(purchase)
        } else {
            val acknowledgePurchaseParams = AcknowledgePurchaseParams
                .newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

            withContext(ioDispatcher) {
                val billingResult = billingClient.acknowledgePurchase(acknowledgePurchaseParams)

                when(billingResult.responseCode) {
                    BillingResponseCode.OK -> Either.Right(purchase)
                    else ->  Either.Left(billingResult)
                }
            }
        }
    }

    suspend fun consumePurchase(purchase: Purchase): Either<BillingResult, Purchase> {
        val consumeParams = ConsumeParams
            .newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        return withContext(ioDispatcher) {
            val consumeResult = billingClient.consumePurchase(consumeParams)
            val billingResult = consumeResult.billingResult

            when(billingResult.responseCode) {
                BillingResponseCode.OK -> Either.Right(purchase)
                else -> Either.Left(billingResult)
            }
        }
    }

    suspend fun queryProductDetails(): Either<BillingResult, List<ProductDetails>?> {
        val productList = products.map {
            QueryProductDetailsParams.Product
                .newBuilder()
                .setProductId(it.id)
                .setProductType(it.productType)
                .build()
        }

        val builder = QueryProductDetailsParams.newBuilder()
        val queryProductDetailsParams = builder
            .setProductList(productList)
            .build()

        return withContext(ioDispatcher) {
            val productDetailsResult = billingClient.queryProductDetails(queryProductDetailsParams)
            val billingResult = productDetailsResult.billingResult

            when(billingResult.responseCode) {
                BillingResponseCode.OK -> Either.Right(productDetailsResult.productDetailsList)
                else -> Either.Left(billingResult)
            }
        }
    }

    companion object {
        val Purchase.jsonObject: JSONObject get() = JSONObject(originalJson)
        val Purchase.purchased: Boolean get() = purchaseState == Purchase.PurchaseState.PURCHASED
    }
}
