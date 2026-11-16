package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ImageEntity
import com.example.data.local.entity.SectionEntity
import com.example.utils.MediaActions
import com.example.R
import androidx.compose.ui.res.stringResource

@Composable
fun PageSectionCard(
    section: SectionEntity,
    modifier: Modifier = Modifier,
    images: List<ImageEntity> = emptyList(),
    searchQuery: String = "",
    onFigureClick: (ImageEntity) -> Unit = {}
) {
    val context = LocalContext.current

    val (badgeColor, typeIcon, typeLabel) = when (section.sectionType.uppercase()) {
        "ANATOMY" -> Triple(MaterialTheme.colorScheme.tertiary, Icons.Default.Visibility, stringResource(R.string.section_type_anatomy))
        "APPROACH" -> Triple(MaterialTheme.colorScheme.primary, Icons.Default.MedicalServices, stringResource(R.string.section_type_approach))
        "REDUCTION" -> Triple(MaterialTheme.colorScheme.secondary, Icons.Default.PanTool, stringResource(R.string.section_type_reduction))
        "FIXATION" -> Triple(MaterialTheme.colorScheme.primary, Icons.Default.Healing, stringResource(R.string.section_type_fixation))
        "PEARLS" -> Triple(MaterialTheme.colorScheme.tertiary, Icons.Default.Psychology, stringResource(R.string.section_type_pearls))
        "COMPLICATIONS" -> Triple(MaterialTheme.colorScheme.error, Icons.Default.Warning, stringResource(R.string.section_type_complications))
        "REHAB" -> Triple(MaterialTheme.colorScheme.secondary, Icons.Default.Speed, stringResource(R.string.section_type_rehab))
        else -> Triple(MaterialTheme.colorScheme.primary, Icons.Default.MedicalServices, if (section.sectionType.equals("OVERVIEW", true)) stringResource(R.string.section_type_overview) else section.sectionType)
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.22f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("section_card_${section.id}")
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeColor.copy(alpha = 0.14f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = {
                            val copyText = buildString {
                                appendLine(section.title)
                                appendLine()
                                appendLine(section.content)
                                if (section.pearls.isNotBlank()) {
                                    appendLine("\nPearls:\n${section.pearls}")
                                }
                                if (section.warnings.isNotBlank()) {
                                    appendLine("\nDanger Zone:\n${section.warnings}")
                                }
                            }
                            MediaActions.copyToClipboard(context, section.title, copyText)
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("copy_section_${section.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.page_section_card_copy_section),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            MediaActions.shareClinicalTip(context, section.title, section.content)
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("share_section_${section.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.page_section_card_share_section),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            OperativeText(
                content = section.content,
                images = images,
                searchQuery = searchQuery,
                onFigureClick = onFigureClick
            )

            if (section.pearls.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                PearlCard(pearlText = section.pearls)
            }

            if (section.warnings.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                WarningBanner(warningText = section.warnings)
            }
        }
    }
}
