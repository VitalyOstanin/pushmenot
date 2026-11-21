package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vitalyostanin.pushmenot.R

private val cardShape = RoundedCornerShape(14.dp)
internal val buttonShape = RoundedCornerShape(12.dp)
internal val screenPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)

@Composable
internal fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column {
            if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    Modifier.weight(1f).padding(vertical = 20.dp),
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                action?.invoke()
            }
        }
    }
}

@Composable
internal fun TogglePanel(title: Int, description: Int, checked: Boolean, tag: String, toggle: (Boolean) -> Unit) {
    val label = stringResource(title)
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Title(label)
                Help(stringResource(description))
            }
            Switch(
                checked = checked,
                onCheckedChange = toggle,
                modifier = Modifier.testTag(tag).semantics { contentDescription = label },
            )
        }
    }
}

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    warning: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(),
        shape = cardShape,
        color = if (warning) colors.tertiaryContainer else containerColor,
        contentColor = if (warning) colors.onTertiaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun Notice(title: Int, warning: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(),
        shape = cardShape,
        color = if (warning) colors.tertiaryContainer else colors.secondaryContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Title(stringResource(title))
            content()
        }
    }
}

@Composable
internal fun ActionButton(
    label: Int,
    onClick: () -> Unit,
    primary: Boolean = true,
    enabled: Boolean = true,
    tag: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val size = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val modifier = if (tag == null) size else size.testTag(tag)
    val colors = MaterialTheme.colorScheme
    val focusColor = if (primary) colors.onPrimary else colors.primary
    val text: @Composable () -> Unit = { Text(stringResource(label), fontWeight = FontWeight.SemiBold) }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.onFocusChanged { focused = it.isFocused },
            enabled = enabled,
            shape = buttonShape,
            border = if (focused) BorderStroke(2.dp, focusColor) else null,
            content = { text() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.onFocusChanged { focused = it.isFocused },
            enabled = enabled,
            shape = buttonShape,
            border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) focusColor else colors.outline),
            content = { text() },
        )
    }
}

@Composable
internal fun Confirmation(title: Int, message: Int, confirmLabel: Int, confirm: () -> Unit, cancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(confirmLabel)) } },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun Title(label: String) {
    Text(label, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Help(label: String) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
}
