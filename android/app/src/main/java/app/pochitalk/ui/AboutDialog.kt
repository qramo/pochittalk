package app.pochitalk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(PochiColors.Card)
                .border(2.dp, PochiColors.Border, RoundedCornerShape(20.dp)),
        ) {
            // ヘッダーは固定、本文だけがスクロールする
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Brand()
                OutlinedButton(onClick = onDismiss) { Text("閉じる") }
            }
            HorizontalDivider(color = PochiColors.Border)
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                aboutSections.forEach { section ->
                    Text(
                        section.title,
                        color = androidx.compose.ui.graphics.Color(0xFF93C5FD),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    HorizontalDivider(color = PochiColors.Border)
                    section.blocks.forEach { block ->
                        when (block) {
                            is AboutBlock.Paragraph ->
                                Text(boldMarkup(block.text), color = PochiColors.Text, fontSize = 13.sp, lineHeight = 20.sp)
                            is AboutBlock.Sub ->
                                Text(
                                    block.title,
                                    color = PochiColors.Amber,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            is AboutBlock.Bullets ->
                                block.items.forEach { item ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("•", color = PochiColors.Muted, fontSize = 13.sp)
                                        Text(boldMarkup(item), color = PochiColors.Text, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                                    }
                                }
                        }
                    }
                }
            }
        }
    }
}
