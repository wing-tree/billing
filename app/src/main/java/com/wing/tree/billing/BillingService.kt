@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.wing.tree.billing

import android.app.Activity
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import arrow.core.Either
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.wing.tree.billing.extension.consumable
import com.wing.tree.billing.extension.get
import com.wing.tree.billing.model.Product
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class BillingService(context: Context, private val products: List<Product>) {
    private lateinit var billingClientStateListener: BillingClientStateListener

    private val billingClient by lazy {
        BillingClient
            .newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()
    }

    private val ioDispatcher = Dispatchers.IO
    private val coroutineScope = CoroutineScope(SupervisorJob().plus(ioDispatcher))

    private val _productDetailsList = MutableStateFlow<List<ProductDetails>?>(null)
    val productDetailsList: StateFlow<List<ProductDetails>?> get() = _productDetailsList

    private val _purchases = Channel<Either<BillingResult, Purchase>>(Channel.UNLIMITED) {
        if (it is Either.Right) {
            val purchase = it.value

            coroutineScope.launch {
                if (purchase.purchased) {
                    purchase.products.forEach { id ->
                        val product = products[id] ?: return@forEach

                        when (product) {
                            is Product.INAPP -> {
                                if (product.consumable) {
                                    consumePurchase(purchase)
                                } else {
                                    acknowledgePurchase(purchase)
                                }
                            }

                            is Product.SUBS -> acknowledgePurchase(purchase)
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

    fun consumable(purchase: Purchase): Boolean {
        return products.consumable().map { it.id }.containsAll(purchase.products)
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

    fun reconnect() {
        startConnection(billingClientStateListener)
    }

    fun queryPurchases(@ProductType productType: String) {
        val builder = QueryPurchasesParams.newBuilder()
        val queryPurchasesParams = builder
            .setProductType(productType)
            .build()

        queryPurchases(queryPurchasesParams)
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

    fun setup(
        lifecycleOwner: LifecycleOwner,
        onBillingServiceDisconnected: (() -> Unit)? = null,
        onBillingSetupFinished: (billingResult: BillingResult) -> Unit
    ) {
        lifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    super.onCreate(owner)

                    billingClientStateListener = object : BillingClientStateListener {
                        override fun onBillingServiceDisconnected() {
                            onBillingServiceDisconnected?.invoke()
                        }

                        override fun onBillingSetupFinished(billingResult: BillingResult) {
                            onBillingSetupFinished(billingResult)
                        }
                    }

                    startConnection(billingClientStateListener)
                }

                override fun onResume(owner: LifecycleOwner) {
                    super.onResume(owner)
                    queryPurchases(ProductType.INAPP)
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    endConnection()
                    super.onDestroy(owner)
                }
            }
        )
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

                when (billingResult.responseCode) {
                    BillingResponseCode.OK -> Either.Right(purchase)
                    else -> Either.Left(billingResult)
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

            when (billingResult.responseCode) {
                BillingResponseCode.OK -> Either.Right(purchase)
                else -> Either.Left(billingResult)
            }
        }
    }

    suspend fun processPurchase(purchase: Purchase): Either<BillingResult, Purchase> {
        return when {
            consumable(purchase) -> consumePurchase(purchase)
            else -> acknowledgePurchase(purchase)
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

            when (billingResult.responseCode) {
                BillingResponseCode.OK -> with(productDetailsResult.productDetailsList) {
                    _productDetailsList.update {
                        this
                    }

                    Either.Right(this)
                }

                else -> Either.Left(billingResult)
            }
        }
    }

    companion object {
        val Purchase.jsonObject: JSONObject get() = JSONObject(originalJson)
        val Purchase.purchased: Boolean get() = purchaseState == Purchase.PurchaseState.PURCHASED
    }
}
