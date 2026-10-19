package app.octopus.local.providers

import app.octopus.local.pricing.*
import java.time.ZoneId
import java.time.ZonedDateTime

/** Read-only coupon terms and simultaneous savings evidence on the observed Keeta screens. */
object KeetaPromotionEvidence {
    private fun clean(s:String)=s.replace(Regex("[\\u064b-\\u065f\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"),"")
        .replace('٪','%').replace(Regex("[\\s\\u00a0]+")," ").trim()
    private val number="([0-9٠-٩]+(?:[.٫][0-9٠-٩]{1,2})?)"
    private fun ancestors(frame:CatalogFrame,node:CatalogNode):List<CatalogNode>{
        val result=mutableListOf<CatalogNode>();var current=node
        repeat(12){current=frame.nodes.singleOrNull{it.index==current.parent}?:return result;result+=current};return result
    }
    fun couponSheet(frame:CatalogFrame)=frame.complete && frame.nodes.none { it.label=="شروط الاستخدام" } && frame.nodes.any{it.label=="قسائمي"} &&
        frame.nodes.any{clean(it.label).startsWith("خصم ")} && frame.nodes.any{clean(it.label).contains("وفر حتى")}
    fun terms(frame:CatalogFrame)=frame.complete && frame.nodes.any{it.label=="شروط الاستخدام"} &&
        frame.nodes.any{clean(it.label).contains("طلبات التوصيل فقط")}
    fun couponTarget(frame:CatalogFrame):CatalogNode?{
        if(!KeetaMenuPreview.menu(frame)||couponSheet(frame)||terms(frame))return null
        val title=frame.nodes.singleOrNull{Regex("^خصم $number% من $number (?:﷼|ريال)$").matches(clean(it.label))}?:return null
        return ancestors(frame,title).firstOrNull{it.clickable&&it.enabled&&frame.descendants(it.index).size<=80}
    }
    fun termsTarget(frame:CatalogFrame):CatalogNode?{
        if(!couponSheet(frame)||terms(frame))return null
        val label=frame.nodes.singleOrNull{clean(it.label)=="تنتهي صلاحيتها في"}?:return null
        val row=ancestors(frame,label).firstOrNull{it.clickable&&it.enabled&&frame.descendants(it.index).size<=100}?:return null
        val timer=frame.descendants(row.index).mapNotNull { clean(it.label).takeIf { value -> value.matches(Regex("[0-9٠-٩]{2}")) }?.let(MoneyParser::parse)?.div(100) }
        return row.takeIf { timer.size==3 && timer.any { value -> value>0 } }
    }
    fun closeTarget(frame:CatalogFrame):CatalogNode?{
        if(terms(frame)){
            val label=frame.nodes.singleOrNull{clean(it.label)=="حسنا"}?:return null
            return if(label.clickable&&label.enabled)label else ancestors(frame,label).firstOrNull{it.clickable&&it.enabled&&frame.descendants(it.index).size<=80}
        }
        if(couponSheet(frame)){
            val title=frame.nodes.singleOrNull{it.label=="قسائمي"}?:return null
            return frame.nodes.singleOrNull{it.index<title.index&&it.clickable&&it.enabled&&it.label.isEmpty()&&
                it.className=="android.widget.ImageView"}
        }
        return null
    }

    fun ownedRule(frame:CatalogFrame,now:Long,ownershipVerified:Boolean=false):OfferRule?{
        if(!terms(frame)||(!ownershipVerified&&frame.nodes.none{it.label=="قسائمي"}))return null
        val labels=frame.nodes.map{clean(it.label)}
        val percents=labels.mapNotNull{Regex("^خصم $number%$").matchEntire(it)?.groupValues?.get(1)?.let(MoneyParser::parse)}.distinct()
        val minima=labels.flatMap{Regex("الحد الأدنى(?: للطلب)?[:：،]? $number (?:﷼|ريال)").findAll(it).map{m->m.groupValues[1]}.toList()}
            .mapNotNull(MoneyParser::parse).distinct()
        val caps=labels.flatMap{Regex("وفر حتى $number (?:﷼|ريال)").findAll(it).map{m->m.groupValues[1]}.toList()}
            .mapNotNull(MoneyParser::parse).distinct()
        val percent=percents.singleOrNull()?.toInt()?.takeIf{it in 1..10000}?:return null
        val minimum=minima.singleOrNull()?:return null
        val cap=caps.singleOrNull()?:return null
        val today=labels.any{it.contains("تنتهي اليوم")}
        val heading=frame.nodes.singleOrNull { it.label=="شروط الاستخدام" } ?: return null
        val body=ancestors(frame,heading).firstOrNull { ancestor ->
            val children=frame.descendants(ancestor.index)
            children.any { clean(it.label)=="حسنا" } && children.none { it.label=="قسائمي" }
        } ?: return null
        val missingText=frame.descendants(body.index).any { it.omittedText }
        val unknown=labels.any{Regex("أول طلب|الطلب الأول|عملاء جدد|بطاقة|محفظة|اشتراك|مشترك|عضوية").containsMatchIn(it)}
        val expires=if(today)ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now),ZoneId.of("Asia/Riyadh"))
            .toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Riyadh")).toInstant().toEpochMilli() else null
        return OfferRule("keeta-owned-$percent-$minimum-$cap","خصم ${java.math.BigDecimal.valueOf(percent.toLong(),2).stripTrailingZeros().toPlainString()}% • الحد الأدنى ${java.math.BigDecimal.valueOf(minimum,2).stripTrailingZeros().toPlainString()} • سقف ${java.math.BigDecimal.valueOf(cap,2).stripTrailingZeros().toPlainString()} ريال",
            OfferTarget.ITEMS,minimum,percentBasisPoints=percent,maximumDiscount=cap,
            termsComplete=!unknown&&!missingText&&expires!=null,requiresCoupon=true,couponVerified=true,validUntilMillis=expires)
    }
    fun simultaneous(frame:CatalogFrame,rule:OfferRule):Boolean{
        if(!KeetaMenuPreview.menu(frame)||rule.percentBasisPoints==null||rule.maximumDiscount==null)return false
        val card=KeetaMenuPreview.productTarget(frame)?:return false
        val row=frame.descendants(card.index)
        val quantities=row.filter{clean(it.label).matches(Regex("[0-9٠-٩]{1,2}"))}.mapNotNull{MoneyParser.parse(it.label)?.div(100)}.distinct()
        val quantity=quantities.singleOrNull()?.takeIf{it in 1..99}?:return false
        val unit=row.mapNotNull{it.label.takeIf{v->v.contains('﷼')}?.let(MoneyParser::parse)}.singleOrNull()?:return false
        val subtotal=unit*quantity
        if(subtotal<rule.minimum)return false
        val discount=minOf(subtotal*rule.percentBasisPoints/10000,rule.maximumDiscount)
        val checkout=frame.nodes.singleOrNull{it.label=="إتمام الدفع"}?:return false
        for(parent in ancestors(frame,checkout)){
            val children=frame.descendants(parent.index)
            if(children.size>130)return false
            val savings=children.mapNotNull{Regex("^وفرت $number (?:﷼|ريال)$").matchEntire(clean(it.label))?.groupValues?.get(1)?.let(MoneyParser::parse)}.distinct()
            if(savings.singleOrNull()!=discount)continue
            val money=children.mapNotNull{it.label.takeIf{v->v.contains('﷼')}?.let(MoneyParser::parse)}.toSet()
            val badges=children.filter{!it.clickable&&it.label.matches(Regex("[0-9٠-٩]{1,2}"))}.mapNotNull{MoneyParser.parse(it.label)?.div(100)}.distinct()
            if(subtotal in money && subtotal-discount in money && badges.singleOrNull()==quantity &&
                children.any{clean(it.label)=="توصيل مجاني"})return true
        }
        return false
    }
}
