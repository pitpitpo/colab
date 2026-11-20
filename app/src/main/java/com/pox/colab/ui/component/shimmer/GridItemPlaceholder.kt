package com.pox.colab.ui.component.shimmer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pox.colab.ui.component.expressive.ExpressiveShapes

@Composable
fun GridItemPlaceholder(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .width(88.dp)
                            .height(24.dp)
                            .clip(CircleShape)
                            .shimmer()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .height(22.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .shimmer()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.42f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .shimmer()
                    )
                }

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .shimmer()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .width(92.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .shimmer()
                )
                Box(
                    modifier = Modifier
                        .width(108.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .shimmer()
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(ExpressiveShapes.connectedStart)
                        .shimmer()
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(ExpressiveShapes.connectedEnd)
                        .shimmer()
                )
            }
        }
    }
}
