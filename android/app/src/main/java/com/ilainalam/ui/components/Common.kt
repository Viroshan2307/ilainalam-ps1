package com.ilainalam.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ilainalam.i18n.LocalStrings
import com.ilainalam.ml.QualityGrade
import com.ilainalam.ml.SeverityLevel
import com.ilainalam.ui.theme.LeafDark
import com.ilainalam.ui.theme.SevMild
import com.ilainalam.ui.theme.SevModerate
import com.ilainalam.ui.theme.SevSevere

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.back)
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = LeafDark, titleContentColor = Color.White,
            navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
        ),
    )
}

/** Big, full-width primary action (≥ 64 dp tall) with icon + text. */
@Composable
fun BigButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun BigOutlinedButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

data class Badge(val color: Color, val icon: ImageVector, val text: String)

@Composable
fun severityBadge(level: SeverityLevel): Badge {
    val s = LocalStrings.current
    return when (level) {
        SeverityLevel.MILD -> Badge(SevMild, Icons.Filled.CheckCircle, s.mild)
        SeverityLevel.MODERATE -> Badge(SevModerate, Icons.Filled.Warning, s.moderate)
        SeverityLevel.SEVERE -> Badge(SevSevere, Icons.Filled.Error, s.severe)
    }
}

@Composable
fun qualityBadge(grade: QualityGrade): Badge {
    val s = LocalStrings.current
    return when (grade) {
        QualityGrade.GOOD -> Badge(SevMild, Icons.Filled.CheckCircle, s.qualityGood)
        QualityGrade.FAIR -> Badge(SevModerate, Icons.Filled.Warning, s.qualityFair)
        QualityGrade.POOR -> Badge(SevSevere, Icons.Filled.Error, s.qualityPoor)
    }
}

/** Colour + icon + word together, so it works for colour-blind users and in bright sun. */
@Composable
fun BadgeRow(label: String, badge: Badge, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "$label: ${badge.text}${detail?.let { ", $it" } ?: ""}" }) {
        Icon(badge.icon, contentDescription = null, tint = badge.color, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(badge.text, style = MaterialTheme.typography.titleLarge, color = badge.color)
                if (detail != null) { Spacer(Modifier.width(8.dp)); Text(detail, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
fun BulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { Row { Text("•  ", style = MaterialTheme.typography.bodyLarge); Text(it, style = MaterialTheme.typography.bodyLarge) } }
    }
}
