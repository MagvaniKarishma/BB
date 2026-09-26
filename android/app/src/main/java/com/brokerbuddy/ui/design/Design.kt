package com.brokerbuddy.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brokerbuddy.R
import com.brokerbuddy.core.format.Money
import com.brokerbuddy.core.model.Furnishing
import com.brokerbuddy.core.model.PropertyCategory
import com.brokerbuddy.core.model.Property
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.ui.theme.Brand
import com.brokerbuddy.ui.theme.Tint
import com.brokerbuddy.ui.theme.brand

// ---------- Brand header ----------

/** "BrokerBuddy" in two tones with the tagline underneath. */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.brand.navy)) { append("Broker") }
                withStyle(SpanStyle(color = MaterialTheme.brand.link)) { append("Buddy") }
            },
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
        )
        Text("Your Real Estate Assistant", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.navy)
    }
}

/** Home top bar: menu, wordmark, search, notifications (with count) and the user's initials. */
@Composable
fun HomeTopBar(
    userName: String?,
    notificationCount: Int,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = MaterialTheme.brand.navy) }
        BrandWordmark(Modifier.weight(1f).padding(start = 4.dp))
        IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, contentDescription = "Search clients", tint = MaterialTheme.brand.navy) }
        Box {
            IconButton(onClick = onNotifications) {
                Icon(Icons.Outlined.Notifications, contentDescription = "Follow-ups due", tint = MaterialTheme.brand.navy)
            }
            if (notificationCount > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp).size(18.dp).background(Brand.Red, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (notificationCount > 9) "9+" else "$notificationCount", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Avatar(userName ?: "?", size = 44.dp, modifier = Modifier.clickable(onClick = onProfile))
    }
}

/** Standard top bar for inner screens: back arrow and a title on the brand background. */
@Composable
fun BrandBackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.brand.navy)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.brand.navy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

// ---------- Cards & sections ----------

/** White rounded card with a soft shadow — the basic building block of every screen. */
@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.brand.card)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    val inner: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(contentPadding), content = content) }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, elevation = elevation, content = inner)
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, elevation = elevation, content = inner)
    }
}

/** Card with a bold title and an optional "View All" link, as on the home screen. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    onViewAll: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BrandCard(modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (onViewAll != null) {
                TextButton(onClick = onViewAll) { Text("View All", color = MaterialTheme.brand.link) }
            }
        }
        Column(Modifier.padding(bottom = 8.dp), content = content)
    }
}

@Composable
fun RowDivider() {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(MaterialTheme.brand.divider))
}

/** Stat tile: coloured icon square, big number, label and a trend line. */
@Composable
fun StatTile(
    icon: ImageVector,
    tint: Tint,
    value: String,
    label: String,
    trend: String?,
    trendColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    BrandCard(modifier, onClick = onClick, contentPadding = 8.dp) {
        Box(Modifier.size(38.dp).background(tint.container, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint.content, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.brand.navy)
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, letterSpacing = 0.sp), color = MaterialTheme.brand.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trend != null) {
            Spacer(Modifier.height(6.dp))
            Text(trend, style = MaterialTheme.typography.labelMedium, color = trendColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- Small pieces ----------

private val avatarPalette = listOf(
    Color(0xFF60A5FA), Color(0xFF34D399), Color(0xFFF59E0B), Color(0xFFF472B6),
    Color(0xFFA78BFA), Color(0xFF2DD4BF), Color(0xFFFB7185), Color(0xFF818CF8),
)

fun initials(name: String): String = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    .let { parts -> (parts.firstOrNull()?.take(1).orEmpty() + (if (parts.size > 1) parts.last().take(1) else "")).uppercase() }
    .ifEmpty { "?" }

/** Circle with the person's initials in a colour derived from their name. */
@Composable
fun Avatar(name: String, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val color = avatarPalette[(name.hashCode() and 0x7fffffff) % avatarPalette.size]
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(initials(name), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
    }
}

/** Rounded status chip ("Call", "New Lead", "Rent", "Semi Furnished"). */
@Composable
fun Pill(text: String, tint: Tint, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.background(tint.container, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
        color = tint.content,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
    )
}

/** Round tinted icon button (call, WhatsApp). */
@Composable
fun RoundIconButton(icon: ImageVector, description: String, tint: Tint, onClick: () -> Unit, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(tint.container).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint.content, modifier = Modifier.size(size * 0.5f))
    }
}

/** Chat-bubble-with-phone glyph for "message on WhatsApp" buttons. */
@Composable
fun whatsAppIcon(): ImageVector = ImageVector.vectorResource(R.drawable.ic_whatsapp)

/** Badge for where a lead came from; text only (no third-party logos). */
@Composable
fun SourceBadge(source: String, size: Dp = 52.dp) {
    val (label, bg) = when (source) {
        "ACRES_99" -> "99" to Color(0xFFE53935)
        "HOUSING_COM" -> "H" to Color(0xFF7C3AED)
        "MAGICBRICKS" -> "MB" to Color(0xFFD32F2F)
        "WHATSAPP" -> "WA" to Color(0xFF16A34A)
        "REFERRAL" -> "R" to Color(0xFF2563EB)
        "WALK_IN" -> "W" to Color(0xFF0EA5E9)
        "PHONE_CALL" -> "☎" to Color(0xFF0F766E)
        "AI_CALL_ASSISTANT" -> "AI" to Color(0xFF7C3AED)
        else -> "•" to Color(0xFF64748B)
    }
    Box(
        Modifier.size(size + 8.dp).background(bg.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
        }
    }
}

/** Pastel colour for each property category (dashboard chips). */
@Composable
fun categoryTint(category: PropertyCategory): Tint {
    val b = MaterialTheme.brand
    return when (category) {
        PropertyCategory.STUDIO -> b.info
        PropertyCategory.BHK_1 -> b.success
        PropertyCategory.BHK_2 -> b.amber
        PropertyCategory.BHK_3 -> b.danger
        PropertyCategory.BHK_4 -> b.purple
        PropertyCategory.BHK_5_PLUS -> b.teal
        PropertyCategory.COMMERCIAL -> b.neutral
        PropertyCategory.OTHER -> b.neutral
    }
}

val PropertyCategory.shortLabel: String
    get() = when (this) {
        PropertyCategory.COMMERCIAL -> "Comm."
        else -> label
    }

@Composable
fun CategoryChip(category: PropertyCategory, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = categoryTint(category)
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(tint.container).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(category.shortLabel, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = tint.content, maxLines = 1, softWrap = false)
        Text("$count", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp), color = tint.content, maxLines = 1)
    }
}

/** A list tab: label plus an optional count, e.g. "Follow Up (8)". */
data class TabItem<T>(val key: T, val label: String, val count: Int? = null)

/** Scrollable row of pill tabs (Clients: All / New / Active …; Follow Ups: Today / Upcoming / Overdue). */
@Composable
fun <T> FilterTabs(tabs: List<TabItem<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val b = MaterialTheme.brand
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { t ->
            val on = t.key == selected
            Text(
                t.label + (t.count?.let { " ($it)" } ?: ""),
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) b.info.container else Color.Transparent)
                    .clickable { onSelect(t.key) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) b.link else b.muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * Two-option pill switch (Rent | Buy) with a navy selected half. [selected] may be null
 * (rent/buy not stated yet); then neither half is highlighted.
 */
@Composable
fun <T : Any> SegmentedPill(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFE6EBF5)),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (on) Brand.Navy else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (on) Color.White else Brand.Navy,
                )
            }
        }
    }
}

/** Greeting card with the Mumbai skyline illustration. */
@Composable
fun GreetingHero(greeting: String, name: String, subtitle: String, badge: String?, modifier: Modifier = Modifier) {
    val b = MaterialTheme.brand
    Box(
        modifier.fillMaxWidth().height(190.dp).shadow(2.dp, MaterialTheme.shapes.large).clip(MaterialTheme.shapes.large)
            .background(Brush.verticalGradient(listOf(b.heroTop, b.heroBottom))),
    ) {
        // Artwork on the right; its left edge fades into the card so the text stays clear.
        Image(
            painterResource(R.drawable.hero_skyline),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.62f)
                // Alpha mask: the left edge fades to transparent, so the card's gradient shows through without a seam.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.4f to Color.Black), blendMode = BlendMode.DstIn)
                },
        )
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.52f).padding(start = 18.dp)) {
            Text("$greeting,", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.brand.navy)
            Text("$name! 👋", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.brand.navy)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.brand.navy.copy(alpha = 0.85f))
        }
        if (badge != null) {
            Surface(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.brand.card,
                shadowElevation = 2.dp,
            ) {
                Text(badge, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.brand.navy)
            }
        }
    }
}

// ---------- Property card ----------

fun priceText(p: Property): String =
    Money.full(p.price) + if (p.transactionType == TransactionType.RENT) " / month" else ""

fun Furnishing.chipLabel() = when (this) {
    Furnishing.UNFURNISHED -> "Unfurnished"
    Furnishing.SEMI_FURNISHED -> "Semi Furnished"
    Furnishing.FULLY_FURNISHED -> "Furnished"
}

/** Listing card: cover photo (stock artwork when the listing has none), title, area, price, chips. */
@Composable
fun PropertyCard(
    property: Property,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    matchCount: Int? = null,
    imageHeight: Dp = 120.dp,
) {
    val b = MaterialTheme.brand
    BrandCard(modifier, onClick = onClick, contentPadding = 0.dp) {
        Box(Modifier.fillMaxWidth().height(imageHeight).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))) {
            PropertyPhoto(property.id, property.photoIds.firstOrNull(), Modifier.fillMaxSize())
            if (matchCount != null && matchCount > 0) {
                PhotoBadge(if (matchCount == 1) "1 client match" else "$matchCount client matches")
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(property.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocationOn, null, tint = b.muted, modifier = Modifier.size(14.dp))
                Text(property.locality, style = MaterialTheme.typography.bodySmall, color = b.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(priceText(property), style = MaterialTheme.typography.titleSmall, color = b.link, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(property.transactionType.label, if (property.transactionType == TransactionType.RENT) b.success else b.info)
                property.furnishing?.let { Pill(it.chipLabel(), b.neutral) }
            }
        }
    }
}

@Composable
private fun BoxScope.PhotoBadge(text: String) {
    Text(
        text,
        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Brand.Navy,
    )
}

/** Compact listing row (Properties list): photo on the left, facts on the right. */
@Composable
fun PropertyRow(property: Property, onClick: () -> Unit, modifier: Modifier = Modifier, badge: String? = null) {
    val b = MaterialTheme.brand
    val p = property
    BrandCard(modifier.fillMaxWidth(), onClick = onClick, contentPadding = 10.dp) {
        Row {
            Box(Modifier.size(width = 104.dp, height = 96.dp).clip(RoundedCornerShape(14.dp))) {
                PropertyPhoto(p.id, p.photoIds.firstOrNull(), Modifier.fillMaxSize(), maxPx = 400)
                if (p.photoIds.size > 1) {
                    Text(
                        "${p.photoIds.size}",
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        color = Color.White, style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (badge != null) Pill(badge, b.danger)
                }
                Text(p.locality, style = MaterialTheme.typography.bodySmall, color = b.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(priceText(p), style = MaterialTheme.typography.titleSmall, color = b.link, maxLines = 1)
                val facts = listOfNotNull(p.furnishing?.chipLabel(), p.bathrooms?.let { if (it == 1) "1 Bath" else "$it Baths" }).joinToString(" • ")
                if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodySmall, color = b.muted, maxLines = 1)
                p.carpetAreaSqft?.let { Text("$it sq ft", style = MaterialTheme.typography.bodySmall, color = b.muted) }
            }
        }
    }
}
