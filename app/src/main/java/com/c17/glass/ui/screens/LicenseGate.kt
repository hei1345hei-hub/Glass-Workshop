package com.c17.glass.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c17.glass.ui.GlassBackground
import com.c17.glass.ui.components.GlassCard
import com.c17.glass.ui.components.GlassDivider
import com.c17.glass.ui.components.LiquidButton
import com.c17.glass.ui.components.StatusDot
import com.c17.glass.ui.theme.Glass
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.min

/** 必须手动输入的声明原文。 */
const val LICENSE_STATEMENT = "禁止倒卖，后果自负。"

/**
 * 首次启动门禁：输入 [LICENSE_STATEMENT] 后方可进入主界面。
 *
 * 粘贴拦截策略：
 *  - 仅提取本次新增片段 [extractInsertedSlice] 进行判断，避免正常中文输入法词组上屏（如「禁止」「倒卖」
 *    或候选词提交时 `composition` 清空）被误判为复制粘贴；
 *  - 仅当新增片段命中剪贴板内容（>=2 字），或无组合态下一次性突增 >=8 字（整句粘贴兜底）时才拦截。
 */
@Composable
fun LicenseGate(onAccept: () -> Unit) {
    val colors = Glass.colors
    val context = LocalContext.current
    val backdrop = rememberLayerBackdrop()

    var value by remember { mutableStateOf(TextFieldValue("")) }
    var blocked by remember { mutableStateOf(false) }

    val trimmed = value.text.trim()
    val matched = trimmed == LICENSE_STATEMENT

    val borderColor by animateColorAsState(
        targetValue = when {
            matched -> colors.success
            blocked -> colors.danger
            value.text.isNotEmpty() -> colors.accent.copy(alpha = 0.75f)
            else -> colors.separator
        },
        label = "license-input-border"
    )

    Box(Modifier.fillMaxSize()) {
        // 静态液态玻璃底层背景，供底部进入按钮折射采样
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            GlassBackground()
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colors.accent.copy(alpha = if (colors.isDark) 0.14f else 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.22f, h * 0.18f),
                        radius = w * 0.72f
                    ),
                    radius = w * 0.72f,
                    center = Offset(w * 0.22f, h * 0.18f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colors.danger.copy(alpha = if (colors.isDark) 0.10f else 0.06f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.85f, h * 0.78f),
                        radius = w * 0.65f
                    ),
                    radius = w * 0.65f,
                    center = Offset(w * 0.85f, h * 0.78f)
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            // 顶部品牌与声明徽章
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.accent.copy(alpha = if (colors.isDark) 0.20f else 0.12f))
                        .border(
                            1.dp,
                            colors.accent.copy(alpha = 0.30f),
                            RoundedCornerShape(999.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(colors.accent, 6)
                        Spacer(Modifier.width(6.dp))
                        BasicText(
                            "C17 玻璃工坊",
                            style = TextStyle(
                                color = colors.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.danger.copy(alpha = if (colors.isDark) 0.18f else 0.10f))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    BasicText(
                        "开源免费",
                        style = TextStyle(
                            color = colors.danger,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            BasicText(
                "使用前声明",
                style = TextStyle(
                    color = colors.ink,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                "本工具永久开源免费，禁止一切形式的倒卖与二次打包收费。",
                style = TextStyle(
                    color = colors.inkMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            )

            Spacer(Modifier.height(20.dp))

            // 主声明卡片
            GlassCard(
                backdrop = backdrop,
                corner = 26.dp,
                contentPadding = PaddingValues(18.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        "请手动输入以下声明：",
                        style = TextStyle(
                            color = colors.inkMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (matched) {
                                    colors.success.copy(alpha = 0.14f)
                                } else {
                                    colors.separator.copy(alpha = if (colors.isDark) 0.55f else 0.45f)
                                }
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        BasicText(
                            "${trimmed.length} / ${LICENSE_STATEMENT.length}",
                            style = TextStyle(
                                color = if (matched) colors.success else colors.inkMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 声明展示盒：逐字高亮已正确匹配的前缀，方便核对标点
                val statementAnnotated = remember(trimmed, colors) {
                    val matchPrefixLen = longestMatchingPrefix(trimmed, LICENSE_STATEMENT)
                    buildAnnotatedString {
                        if (matchPrefixLen > 0) {
                            withStyle(
                                SpanStyle(
                                    color = if (matched) colors.success else colors.accent,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append(LICENSE_STATEMENT.substring(0, matchPrefixLen))
                            }
                        }
                        if (matchPrefixLen < LICENSE_STATEMENT.length) {
                            withStyle(
                                SpanStyle(
                                    color = colors.danger,
                                    fontWeight = FontWeight.SemiBold
                                )
                            ) {
                                append(LICENSE_STATEMENT.substring(matchPrefixLen))
                            }
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            colors.danger.copy(alpha = if (colors.isDark) 0.10f else 0.06f)
                        )
                        .border(
                            1.dp,
                            colors.danger.copy(alpha = if (colors.isDark) 0.26f else 0.18f),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    BasicText(
                        text = statementAnnotated,
                        style = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                }

                GlassDivider(verticalPadding = 14.dp)

                // 输入框
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (colors.isDark) Color(0xFF1A1A1C) else Color(0xFFF3F4F6)
                        )
                        .border(
                            width = if (matched || blocked || value.text.isNotEmpty()) 1.4.dp else 1.dp,
                            color = borderColor,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        BasicTextField(
                            value = value,
                            onValueChange = { next ->
                                if (isLikelyPaste(context, value.text, value.composition, next)) {
                                    blocked = true
                                    return@BasicTextField
                                }
                                blocked = false
                                value = next
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                color = colors.ink,
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            ),
                            cursorBrush = SolidColor(colors.accent),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (value.text.isEmpty()) {
                                    BasicText(
                                        "在此输入上方声明（支持词组上屏）",
                                        style = TextStyle(
                                            color = colors.inkDim,
                                            fontSize = 14.sp
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                    if (value.text.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(colors.separator)
                                .clickable {
                                    value = TextFieldValue("")
                                    blocked = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                "×",
                                style = TextStyle(
                                    color = colors.inkMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        when {
                            blocked -> colors.danger
                            matched -> colors.success
                            else -> colors.inkDim
                        },
                        6
                    )
                    Spacer(Modifier.width(7.dp))
                    BasicText(
                        when {
                            blocked -> "检测到粘贴操作已拦截，请手动输入文字。"
                            matched -> "声明核验通过，点击下方按钮进入。"
                            else -> "禁止复制粘贴，请留意中文逗号「，」与句号「。」"
                        },
                        style = TextStyle(
                            color = when {
                                blocked -> colors.danger
                                matched -> colors.success
                                else -> colors.inkMuted
                            },
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            if (matched) {
                LiquidButton(
                    onClick = onAccept,
                    backdrop = backdrop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    tint = colors.accent
                ) {
                    BasicText(
                        "进入 C17 玻璃工坊",
                        style = TextStyle(
                            color = colors.onAccent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.cardSurface.copy(alpha = if (colors.isDark) 0.65f else 0.85f))
                        .border(1.dp, colors.separator, RoundedCornerShape(999.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        "输入完整声明后继续",
                        style = TextStyle(
                            color = colors.inkDim,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    "仅在本地保存确认状态，不收集或上传任何个人信息。",
                    style = TextStyle(
                        color = colors.inkDim,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                )
            }
        }
    }
}

private fun longestMatchingPrefix(input: String, target: String): Int {
    val limit = min(input.length, target.length)
    var i = 0
    while (i < limit && input[i] == target[i]) {
        i++
    }
    return i
}

/** 提取本次编辑实际新插入的文本切片。 */
private fun extractInsertedSlice(oldText: String, newText: String): String {
    if (newText.length <= oldText.length) return ""
    var prefix = 0
    val minLen = min(oldText.length, newText.length)
    while (prefix < minLen && oldText[prefix] == newText[prefix]) {
        prefix++
    }
    var suffix = 0
    val maxSuffix = min(oldText.length - prefix, newText.length - prefix)
    while (
        suffix < maxSuffix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
        suffix++
    }
    val end = newText.length - suffix
    return if (end > prefix) newText.substring(prefix, end) else ""
}

/**
 * 仅拦截真实粘贴，放行拼音/五笔/手写等中文输入法的整词或词组提交：
 *  1. 优先比对实际新增切片与系统剪贴板文本（避免剪贴板残留单字或标点误伤）；
 *  2. 若无组合态且单次无预览直接注入 >=8 个字符（几乎一整句），作为整句粘贴兜底拦截。
 */
private fun isLikelyPaste(
    context: Context,
    oldText: String,
    oldComposition: TextRange?,
    next: TextFieldValue
): Boolean {
    val inserted = extractInsertedSlice(oldText, next.text)
    if (inserted.length <= 1) return false

    val clip = runCatching {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
    }.getOrNull().orEmpty()

    if (clip.length >= 2) {
        val hitClipboard = inserted == clip ||
            inserted.contains(clip) ||
            (clip.contains(inserted) && inserted.length >= 4)
        if (hitClipboard) return true
    }

    return inserted.length >= 8 && oldComposition == null && next.composition == null
}
