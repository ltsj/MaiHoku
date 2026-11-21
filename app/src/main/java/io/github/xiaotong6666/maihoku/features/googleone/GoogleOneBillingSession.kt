package io.github.xiaotong6666.maihoku.features.googleone

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.hook.ModuleResources
import java.lang.ref.WeakReference
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/**
 * One client per foreground Activity. No account switching, payment entry,
 * background purchases, price overrides, or automatic checkout confirmation.
 *
 * The query walks the whole SKU list in bounded batches so the offer inspector
 * covers every Google One / Gemini tier that Play exposes to the account, not
 * only the first few products of a single oversized request.
 */
internal class GoogleOneBillingSession(activity: Activity) {
    private val activityRef = WeakReference(activity)
    private var client: BillingClient? = null

    @Volatile private var requestId = 0

    @Volatile private var closed = false

    private data class AvailableOffer(
        val product: ProductDetails,
        val details: ProductDetails.SubscriptionOfferDetails,
        val phases: List<OfferPhase>,
    ) {
        val isFreeTrial: Boolean get() = OfferPricing.hasFreeFirstPhase(phases)
        fun label(activity: Activity): String {
            val tag = ModuleResources.text(
                activity,
                if (isFreeTrial) R.string.googleone_offer_free else R.string.googleone_offer_paid,
            )
            val offerId = details.offerId ?: details.basePlanId
            return ModuleResources.text(
                activity,
                R.string.googleone_offer_row,
                product.productId,
                offerId,
                tag,
            )
        }
    }

    fun query() {
        if (closed) return
        val activity = activityRef.get() ?: return
        requestId += 1
        val currentRequest = requestId
        client?.endConnection()
        client = null
        notice(R.string.googleone_querying)
        try {
            val billing = BillingClient.newBuilder(activity.applicationContext)
                .setListener(
                    PurchasesUpdatedListener { result, _ ->
                        if (isCurrent(currentRequest)) {
                            notice(R.string.googleone_purchase_callback, result.responseCode)
                        }
                    },
                )
                .enablePendingPurchases()
                .build()
            client = billing
            billing.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (!isCurrent(currentRequest)) return
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            queryProducts(billing, currentRequest)
                        } else {
                            failure(currentRequest, R.string.googleone_connection_failed, result.responseCode, result.debugMessage)
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        if (isCurrent(currentRequest)) failure(currentRequest, R.string.googleone_connection_lost)
                    }
                },
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Billing client initialization failed", error)
            failure(currentRequest, R.string.googleone_init_failed, error.javaClass.simpleName)
        }
    }

    private fun queryProducts(billing: BillingClient, id: Int) {
        val chunks = PRODUCT_IDS.chunked(QUERY_BATCH_SIZE)
        val collected = ArrayList<AvailableOffer>(PRODUCT_IDS.size * 2)
        var lastCode = 0
        var lastMessage = ""

        fun finish() {
            if (!isCurrent(id)) return
            val offers = collected.sortedWith(
                compareByDescending<AvailableOffer> { it.isFreeTrial }
                    .thenByDescending { OfferPricing.freeMonths(it.phases) }
                    .thenBy { it.product.productId }
                    .thenBy { it.details.offerId ?: it.details.basePlanId },
            )
            if (offers.isEmpty() && lastCode != 0) {
                failure(id, R.string.googleone_query_failed, lastCode, lastMessage)
                return
            }
            onActivity(id) { showOffers(it, id, offers) }
        }

        fun queryChunk(index: Int) {
            if (!isCurrent(id)) return
            if (index >= chunks.size) {
                finish()
                return
            }
            val products = chunks[index].map { sku ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(sku)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            }
            val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
            try {
                billing.queryProductDetailsAsync(
                    params,
                    ProductDetailsResponseListener { result, details ->
                        if (!isCurrent(id)) return@ProductDetailsResponseListener
                        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                            lastCode = result.responseCode
                            lastMessage = result.debugMessage
                            Log.w(
                                TAG,
                                "Product batch ${index + 1}/${chunks.size} failed: " +
                                    "${result.responseCode} ${result.debugMessage}",
                            )
                        } else {
                            collected += details.orEmpty().flatMap { product ->
                                product.subscriptionOfferDetails.orEmpty().map { offer ->
                                    AvailableOffer(
                                        product = product,
                                        details = offer,
                                        phases = offer.pricingPhases.pricingPhaseList.map { phase ->
                                            OfferPhase(
                                                amountMicros = phase.priceAmountMicros,
                                                formattedPrice = phase.formattedPrice,
                                                billingPeriod = phase.billingPeriod,
                                                billingCycles = phase.billingCycleCount,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        queryChunk(index + 1)
                    },
                )
            } catch (error: Throwable) {
                Log.e(TAG, "Product batch ${index + 1}/${chunks.size} exception", error)
                lastCode = -1
                lastMessage = error.javaClass.simpleName
                queryChunk(index + 1)
            }
        }

        queryChunk(0)
    }

    private fun showOffers(activity: Activity, id: Int, offers: List<AvailableOffer>) {
        if (offers.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(text(activity, R.string.googleone_offers_title))
                .setMessage(text(activity, R.string.googleone_offers_empty))
                .setPositiveButton(text(activity, R.string.googleone_ok), null)
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_offers_count, offers.size))
            .setItems(offers.map { it.label(activity) }.toTypedArray()) { _, index ->
                if (isCurrent(id)) showOfferDetail(activity, id, offers[index])
            }
            .setNegativeButton(text(activity, R.string.googleone_close), null)
            .show()
    }

    private fun showOfferDetail(activity: Activity, id: Int, offer: AvailableOffer) {
        val message = buildString {
            append(text(activity, R.string.googleone_product_line, offer.product.productId))
            append(
                text(
                    activity,
                    R.string.googleone_offer_id_line,
                    offer.details.offerId ?: text(activity, R.string.googleone_base_plan),
                ),
            )
            append(text(activity, R.string.googleone_plan_id_line, offer.details.basePlanId))
            append(text(activity, R.string.googleone_pricing_header))
            append(describePhases(activity, offer.phases))
            append(text(activity, R.string.googleone_eligibility_notice))
        }
        val confirmMessage = if (offer.isFreeTrial) {
            R.string.googleone_confirm_message
        } else {
            R.string.googleone_confirm_message_paid
        }
        AlertDialog.Builder(activity)
            .setTitle(text(activity, R.string.googleone_details_title))
            .setMessage(message)
            .setNegativeButton(text(activity, R.string.googleone_back), null)
            .setPositiveButton(text(activity, R.string.googleone_checkout)) { _, _ ->
                AlertDialog.Builder(activity)
                    .setTitle(text(activity, R.string.googleone_confirm_title))
                    .setMessage(text(activity, confirmMessage))
                    .setNegativeButton(text(activity, R.string.googleone_cancel), null)
                    .setPositiveButton(text(activity, R.string.googleone_open_checkout)) { _, _ ->
                        launchOfficialBilling(activity, id, offer)
                    }
                    .show()
            }
            .show()
    }

    private fun launchOfficialBilling(activity: Activity, id: Int, offer: AvailableOffer) {
        if (!isCurrent(id) || activity.isFinishing || activity.isDestroyed) return
        val billing = client ?: return
        try {
            val entry = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(offer.product)
                .setOfferToken(offer.details.offerToken)
                .build()
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(entry))
                .build()
            val result = billing.launchBillingFlow(activity, params)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                failure(id, R.string.googleone_checkout_failed, result.responseCode, result.debugMessage)
            } else {
                Log.i(TAG, "Official Play checkout launched; user must confirm manually")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Official checkout launch failed", error)
            failure(id, R.string.googleone_checkout_exception, error.javaClass.simpleName)
        }
    }

    private fun isCurrent(id: Int): Boolean = !closed && id == requestId

    private fun onActivity(id: Int, action: (Activity) -> Unit) {
        val activity = activityRef.get() ?: return
        activity.runOnUiThread {
            if (isCurrent(id) && !activity.isFinishing && !activity.isDestroyed) {
                try {
                    action(activity)
                } catch (error: Throwable) {
                    Log.e(TAG, "Google One UI operation failed", error)
                }
            }
        }
    }

    private fun text(activity: Activity, resourceId: Int, vararg arguments: Any): String = ModuleResources.text(activity, resourceId, *arguments)

    private fun describePhases(activity: Activity, phases: List<OfferPhase>): String {
        if (phases.isEmpty()) return text(activity, R.string.googleone_pricing_unavailable)
        return phases.mapIndexed { index, phase ->
            val price = if (phase.amountMicros == 0L) {
                text(activity, R.string.googleone_free_price)
            } else {
                phase.formattedPrice
            }
            val cycleText = if (phase.billingCycles > 0) {
                text(activity, R.string.googleone_phase_cycles, phase.billingCycles)
            } else {
                text(activity, R.string.googleone_phase_ongoing)
            }
            text(activity, R.string.googleone_phase_line, index + 1, price, phase.billingPeriod, cycleText)
        }.joinToString("\n")
    }

    private fun failure(id: Int, resourceId: Int, vararg arguments: Any) {
        onActivity(id) { activity ->
            val message = text(activity, resourceId, *arguments)
            Log.w(TAG, message)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun notice(resourceId: Int, vararg arguments: Any) {
        val activity = activityRef.get() ?: return
        activity.runOnUiThread {
            if (!closed && !activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, text(activity, resourceId, *arguments), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun close() {
        closed = true
        requestId++
        client?.endConnection()
        client = null
        activityRef.clear()
    }

    private companion object {
        const val TAG = "GoogleOneBilling"

        /** queryProductDetailsAsync has a bounded product list; stay well below it. */
        const val QUERY_BATCH_SIZE = 20

        /**
         * Google One / Gemini 订阅 SKU 全集：基础档位 + YouTube 附加项 + 各地区
         * 首期折扣/试用 SKU。Play 只会返回当前账号实际可见的部分，查询侧不再裁剪。
         */
        val PRODUCT_IDS = listOf(
            // 基础档位
            "g1.play.20tb",
            "g1.play.ai.pro",
            "g1.30tb",
            "g1.2tb.ai.subs_variant",
            "g1.play.2tb.ai",
            "g1.2tb.subs_variant",
            "g1.2tb.6months_eft",
            "g1.play.helium",
            // 附加项
            "g1.youtube_premium_individual_add_on",
            "g1.youtube_premium_family_add_on",
            "g1.youtube_premium_lite_add_on",
            // 首期折扣 / 试用
            "g1.helium.3months_ip_75p.acq",
            "g1.2tb.3months_ip_85p.acq",
            "g1.2tb.ai.3months_ip_75p.acq",
            "g1.10tb.1month_ip_90p",
            "g1.10tb.3months_ip_85p",
            "g1.20tb.3months_ip_90p.acq",
            "g1.20tb.3months_ip_80p.acq",
            "g1.20tb.3months_ip_75p.acq",
            "g1.ai.ultra.1month_ip_90p.acq",
            "g1.ai.ultra.3months_ip_90p.acq",
            "g1.ai.ultra.3months_ip_80p.acq",
            "g1.ai.ultra.1month_ip_75p.acq",
            "g1.ai.ultra.3months_ip_75p.acq",
        )
    }
}
