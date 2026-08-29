package me.bmax.apatch.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.bmax.apatch.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

@Composable
fun IconTextButton(
    iconRes: ImageVector,
    textRes: Int,
    showText: Boolean? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val finalShowText = showText ?: true

    IconButton(
        onClick = onClick,
        enabled = enabled,
        minHeight = 35.dp,
        minWidth = 35.dp,
        backgroundColor = colorScheme.secondaryContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = if (finalShowText) 10.dp else 4.dp)
        ) {
            Icon(
                imageVector = iconRes,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            if (finalShowText) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(id = textRes),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
fun ModuleUpdateButton(
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        minHeight = 35.dp,
        minWidth = 35.dp,
        backgroundColor = colorScheme.secondaryContainer
    ) {
        Icon(
            painter = painterResource(id = R.drawable.device_mobile_down),
            contentDescription = stringResource(id = R.string.apm_update),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun ModuleRemoveButton(
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        minHeight = 35.dp,
        minWidth = 35.dp,
        backgroundColor = colorScheme.secondaryContainer
    ) {
        Icon(
            painter = painterResource(id = R.drawable.trash),
            contentDescription = stringResource(id = R.string.apm_remove),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun ModuleUndoRemoveButton(
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        minHeight = 35.dp,
        minWidth = 35.dp,
        backgroundColor = colorScheme.secondaryContainer
    ) {
        Icon(
            painter = painterResource(id = R.drawable.undo),
            contentDescription = stringResource(id = R.string.apm_undo),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun KPModuleRemoveButton(
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        minHeight = 35.dp,
        minWidth = 35.dp,
        backgroundColor = colorScheme.secondaryContainer
    ) {
        Icon(
            painter = painterResource(id = R.drawable.trash),
            contentDescription = stringResource(id = R.string.kpm_unload),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun ModuleStateIndicator(
    @DrawableRes icon: Int,
    color: Color = colorScheme.outline
) {
    Image(
        modifier = Modifier.requiredSize(150.dp),
        painter = painterResource(id = icon),
        contentDescription = null,
        alpha = 0.1f,
        colorFilter = ColorFilter.tint(color)
    )
}
