package io.github.snowiris57.reconnect.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.snowiris57.reconnect.Event
import io.github.snowiris57.reconnect.EventType
import io.github.snowiris57.reconnect.Health
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 状態を示すピル（監視中はドットがゆっくり脈打つ） */
@Composable
fun StatusPill(health: Health, modifier: Modifier = Modifier) {
    val color = healthColor(health)
    Surface(modifier = modifier, shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(color, animate = health != Health.STOPPED)
            Spacer(Modifier.width(8.dp))
            Text(healthLabel(health), style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
fun PulsingDot(color: Color, animate: Boolean, modifier: Modifier = Modifier) {
    val alpha = if (animate) {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(
            initialValue = 1f, targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulseAlpha",
        )
        a
    } else 1f
    Box(
        modifier
            .size(8.dp)
            .alpha(alpha)
            .background(color, CircleShape)
    )
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = valueStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                if (unit.isNotEmpty()) {
                    Text(
                        " $unit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) { content() }
}

data class EventStyle(val icon: ImageVector, val color: Color)

@Composable
fun eventStyle(type: EventType): EventStyle = when (type) {
    EventType.START -> EventStyle(Icons.Filled.PlayArrow, MaterialTheme.colorScheme.primary)
    EventType.STOP -> EventStyle(Icons.Filled.Close, MaterialTheme.colorScheme.outline)
    EventType.DETECT -> EventStyle(Icons.Filled.Warning, SignalColors.warn)
    EventType.RECOVER_START -> EventStyle(Icons.Filled.Refresh, SignalColors.info)
    EventType.STEP -> EventStyle(Icons.AutoMirrored.Filled.KeyboardArrowRight, MaterialTheme.colorScheme.outline)
    EventType.RECOVER_OK -> EventStyle(Icons.Filled.CheckCircle, SignalColors.good)
    EventType.RECOVER_FAIL, EventType.ERROR -> EventStyle(Icons.Filled.Warning, SignalColors.bad)
    EventType.INFO -> EventStyle(Icons.Filled.Info, MaterialTheme.colorScheme.outline)
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)

@Composable
fun EventRow(e: Event, modifier: Modifier = Modifier) {
    val st = eventStyle(e.type)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(36.dp).background(st.color.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(st.icon, contentDescription = null, tint = st.color, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(e.message, style = MaterialTheme.typography.bodyMedium)
            Text(
                timeFmt.format(Date(e.time)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
