package io.github.xiaotong6666.maihoku.features.googleone

/**
 * 展示用目录：把 Play 返回的 sku + offerId 翻成可读的档位、首期价格和区域提示。
 *
 * 只影响列表文字，不参与购买流程，也不会伪造 Play 没有返回的优惠。
 * regionLocked 表示该条属于地区性促销（*_ip_*p 首期价、g1.play.* 预装档），
 * 多数地区账号查不到，需要美区等对应区域的账号才会返回。
 */
internal object PlanCatalog {
    data class Entry(
        val title: String,
        val regionLocked: Boolean,
        val order: Int,
    )

    private val BY_SKU_OFFER: Map<String, Entry> = mapOf(
        // Plus 400GB（helium）
        key("g1.play.helium", "eft-12mo", "Plus 400GB · 免12个月（后 $4.99/月）", true, 110),
        key("g1.play.helium", "eft-6mo", "Plus 400GB · 免6个月（后 $4.99/月）", true, 111),
        key("g1.play.helium", "eft-3mo", "Plus 400GB · 免3个月（后 $4.99/月）", true, 112),
        key("g1.play.helium", "eft-1mo", "Plus 400GB · 免1个月（后 $4.99/月）", true, 113),
        key("g1.helium.3months_ip_75p.acq", "introprice", "Plus 400GB · $1.29 用3个月（后 $4.99/月）", true, 114),

        // Plus 2TB
        key("g1.2tb.subs_variant", "6months-eft", "Plus 2TB · 免6个月（后 $9.99/月）", false, 120),
        key("g1.2tb.subs_variant", "3months-eft", "Plus 2TB · 免3个月（后 $9.99/月）", false, 121),
        key("g1.2tb.6months_eft", "freetrial", "Plus 2TB · 免6个月（后 $9.99/月）", false, 122),
        key("g1.2tb.3months_ip_85p.acq", "introprice", "Plus 2TB · $1.49 用3个月（后 $9.99/月）", true, 123),

        // Pro 5TB / 10TB
        key("g1.2tb.ai.subs_variant", "12months-eft", "Pro 5TB · 免12个月（后 $19.99/月）", false, 210),
        key("g1.2tb.ai.subs_variant", "6months-eft", "Pro 5TB · 免6个月（后 $19.99/月）", false, 211),
        key("g1.2tb.ai.subs_variant", "6months-eft-2", "Pro 5TB · 免6个月（备用档2）", false, 212),
        key("g1.2tb.ai.subs_variant", "6months-eft-ss-2026", "Pro 5TB · 免6个月（SS 2026）", false, 213),
        key("g1.play.2tb.ai", "eft-3mo", "Pro 5TB · 免3个月（后 $19.99/月）", true, 214),
        key("g1.2tb.ai.3months_ip_75p.acq", "introprice", "Pro 5TB · $4.99 用3个月（后 $19.99/月）", true, 215),
        key("g1.10tb.1month_ip_90p", "introprice", "Pro 10TB · $4.99 用1个月（后 $49.99/月）", true, 216),
        key("g1.10tb.3months_ip_85p", "introprice", "Pro 10TB · $7.49 用3个月（后 $49.99/月）", true, 217),

        // Ultra 5x（20TB）
        key("g1.20tb.3months_ip_90p.acq", "introprice", "Ultra 5x 20TB · $9.99 用3个月（后 $99.99/月）", true, 310),
        key("g1.20tb.3months_ip_80p.acq", "introprice", "Ultra 5x 20TB · $19.99 用3个月（后 $99.99/月）", true, 311),
        key("g1.20tb.3months_ip_75p.acq", "introprice", "Ultra 5x 20TB · $24.99 用3个月（后 $99.99/月）", true, 312),

        // Ultra 20x（30TB）
        key("g1.ai.ultra.1month_ip_90p.acq", "introprice", "Ultra 20x 30TB · $19.99 用1个月（后 $199.99/月）", true, 410),
        key("g1.ai.ultra.3months_ip_90p.acq", "introprice", "Ultra 20x 30TB · $19.99 用3个月（后 $199.99/月）", true, 411),
        key("g1.ai.ultra.3months_ip_80p.acq", "introprice", "Ultra 20x 30TB · $39.99 用3个月（后 $199.99/月）", true, 412),
        key("g1.ai.ultra.1month_ip_75p.acq", "introprice", "Ultra 20x 30TB · $49.99 用1个月（后 $199.99/月）", true, 413),
        key("g1.ai.ultra.3months_ip_75p.acq", "introprice", "Ultra 20x 30TB · $49.99 用3个月（后 $199.99/月）", true, 414),

        // YouTube 附加项
        key("g1.youtube_premium_individual_add_on", "eft-1mo", "YouTube Premium 个人版 · 免1个月（附加）", true, 510),
        key("g1.youtube_premium_family_add_on", "eft-1mo", "YouTube Premium 家庭版 · 免1个月（附加）", true, 511),
        key("g1.youtube_premium_lite_add_on", "eft-1mo", "YouTube Premium Lite · 免1个月（附加）", true, 512),
    )

    /** 只知道 SKU、不知道具体 offer 时的兜底说明。 */
    private val BY_SKU: Map<String, Entry> = mapOf(
        "g1.play.20tb" to Entry("Ultra 5x 20TB · Play 基础 SKU（无促销）", true, 390),
        "g1.play.ai.pro" to Entry("Pro · Play 基础 SKU（无促销）", true, 290),
        "g1.30tb" to Entry("Ultra 20x 30TB · 基础 SKU（无促销）", false, 490),
    )

    private fun key(sku: String, offerId: String, title: String, regionLocked: Boolean, order: Int) =
        "$sku|$offerId" to Entry(title, regionLocked, order)

    fun find(sku: String, offerId: String?): Entry? =
        BY_SKU_OFFER["$sku|${offerId.orEmpty()}"] ?: BY_SKU[sku]

    fun orderOf(sku: String, offerId: String?): Int = find(sku, offerId)?.order ?: 999
}
