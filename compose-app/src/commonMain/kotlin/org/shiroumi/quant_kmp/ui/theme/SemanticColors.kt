package org.shiroumi.quant_kmp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 应用级语义色 token，承载 Material 3 ColorScheme 之外的业务专属色：
 * - 股票方向：A 股惯例 红涨 / 绿跌（[bullish] / [bearish]）
 * - 业务状态：弱封 / 炸板 / 天量 / 密码强弱 / 数据更新结果
 *
 * 取值原则：Material ColorScheme 承载品牌与组件层级；此处只承载业务含义。
 * 股票方向保持红涨 / 绿跌，但红色采用克制的低彩度暖红，避免在绿色/蓝色主题下抢占品牌色。
 */
@Immutable
data class QuantSemanticColors(
    val bullish: Color,
    val onBullish: Color,
    val bullishContainer: Color,
    val onBullishContainer: Color,
    val bearish: Color,
    val onBearish: Color,
    val bearishContainer: Color,
    val onBearishContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val success: Color,
    val onSuccess: Color,
    val neutral: Color,
    val onNeutral: Color,
)

// region 暖棕红
private val warmBrownRedLightSemantic = QuantSemanticColors(
    bullish = Color(0xFFB94A3A), onBullish = Color(0xFFFFFFFF),
    bullishContainer = Color(0xFFFFDAD4), onBullishContainer = Color(0xFF7A2013),
    bearish = Color(0xFF2E6F40), onBearish = Color(0xFFFFFFFF),
    bearishContainer = Color(0xFFB9E7C2), onBearishContainer = Color(0xFF103B1C),
    warning = Color(0xFFB86E00), onWarning = Color(0xFFFFFFFF),
    success = Color(0xFF3F6F48), onSuccess = Color(0xFFFFFFFF),
    neutral = Color(0xFF6D6261), onNeutral = Color(0xFFFFFFFF),
)
private val warmBrownRedDarkSemantic = QuantSemanticColors(
    bullish = Color(0xFFFFB4A6), onBullish = Color(0xFF5C160B),
    bullishContainer = Color(0xFF7A2013), onBullishContainer = Color(0xFFFFDAD4),
    bearish = Color(0xFF9BD6A6), onBearish = Color(0xFF003913),
    bearishContainer = Color(0xFF1A5429), onBearishContainer = Color(0xFFB9E7C2),
    warning = Color(0xFFE8BD7A), onWarning = Color(0xFF3F2A00),
    success = Color(0xFFA7D3AE), onSuccess = Color(0xFF123A1B),
    neutral = Color(0xFFB6A8A4), onNeutral = Color(0xFF2A211F),
)
// endregion

// region 靛蓝
private val indigoBlueLightSemantic = QuantSemanticColors(
    bullish = Color(0xFFB3261E), onBullish = Color(0xFFFFFFFF),
    bullishContainer = Color(0xFFFFDAD6), onBullishContainer = Color(0xFF6B130E),
    bearish = Color(0xFF2D6E4F), onBearish = Color(0xFFFFFFFF),
    bearishContainer = Color(0xFFB3E6C8), onBearishContainer = Color(0xFF07341F),
    warning = Color(0xFFB46C00), onWarning = Color(0xFFFFFFFF),
    success = Color(0xFF356D55), onSuccess = Color(0xFFFFFFFF),
    neutral = Color(0xFF5C616E), onNeutral = Color(0xFFFFFFFF),
)
private val indigoBlueDarkSemantic = QuantSemanticColors(
    bullish = Color(0xFFFFB4AB), onBullish = Color(0xFF5C0F0B),
    bullishContainer = Color(0xFF6B130E), onBullishContainer = Color(0xFFFFDAD6),
    bearish = Color(0xFF98D8B2), onBearish = Color(0xFF00391F),
    bearishContainer = Color(0xFF174F35), onBearishContainer = Color(0xFFB3E6C8),
    warning = Color(0xFFE5BD7D), onWarning = Color(0xFF3D2A00),
    success = Color(0xFFA0D3B8), onSuccess = Color(0xFF123A28),
    neutral = Color(0xFFB0B2BE), onNeutral = Color(0xFF24262F),
)
// endregion

// region 翠青绿
private val tealGreenLightSemantic = QuantSemanticColors(
    bullish = Color(0xFFA84636), onBullish = Color(0xFFFFFFFF),
    bullishContainer = Color(0xFFFFDAD4), onBullishContainer = Color(0xFF6E2014),
    bearish = Color(0xFF2F6F4F), onBearish = Color(0xFFFFFFFF),
    bearishContainer = Color(0xFFB7E7CB), onBearishContainer = Color(0xFF0D3B25),
    warning = Color(0xFFAD6B00), onWarning = Color(0xFFFFFFFF),
    success = Color(0xFF006C63), onSuccess = Color(0xFFFFFFFF),
    neutral = Color(0xFF4F6663), onNeutral = Color(0xFFFFFFFF),
)
private val tealGreenDarkSemantic = QuantSemanticColors(
    bullish = Color(0xFFF3B5AA), onBullish = Color(0xFF56180E),
    bullishContainer = Color(0xFF6E2014), onBullishContainer = Color(0xFFFFDAD4),
    bearish = Color(0xFF9DD8B6), onBearish = Color(0xFF00391F),
    bearishContainer = Color(0xFF195039), onBearishContainer = Color(0xFFB7E7CB),
    warning = Color(0xFFE2BE82), onWarning = Color(0xFF3B2A00),
    success = Color(0xFF83D7CC), onSuccess = Color(0xFF003A34),
    neutral = Color(0xFFA9BAB7), onNeutral = Color(0xFF223230),
)
// endregion

// region 紫罗兰
private val violetPurpleLightSemantic = QuantSemanticColors(
    bullish = Color(0xFFB2385D), onBullish = Color(0xFFFFFFFF),
    bullishContainer = Color(0xFFFFD9E2), onBullishContainer = Color(0xFF6E1737),
    bearish = Color(0xFF2E7D5B), onBearish = Color(0xFFFFFFFF),
    bearishContainer = Color(0xFFB1E5C9), onBearishContainer = Color(0xFF0A3320),
    warning = Color(0xFFB76C00), onWarning = Color(0xFFFFFFFF),
    success = Color(0xFF386E45), onSuccess = Color(0xFFFFFFFF),
    neutral = Color(0xFF615C6F), onNeutral = Color(0xFFFFFFFF),
)
private val violetPurpleDarkSemantic = QuantSemanticColors(
    bullish = Color(0xFFFFB0C4), onBullish = Color(0xFF5B0E29),
    bullishContainer = Color(0xFF6E1737), onBullishContainer = Color(0xFFFFD9E2),
    bearish = Color(0xFF87D8AB), onBearish = Color(0xFF003020),
    bearishContainer = Color(0xFF1B5034), onBearishContainer = Color(0xFFB1E5C9),
    warning = Color(0xFFE6BD7F), onWarning = Color(0xFF3D2A00),
    success = Color(0xFF92D8A8), onSuccess = Color(0xFF093720),
    neutral = Color(0xFFB1AABE), onNeutral = Color(0xFF272431),
)
// endregion

// region 新增主题语义色
private val forestGreenLightSemantic = QuantSemanticColors(
    bullish = Color(0xFFA84636), onBullish = Color(0xFFFFFFFF),
    bullishContainer = Color(0xFFFFDAD4), onBullishContainer = Color(0xFF6E2014),
    bearish = Color(0xFF356F45), onBearish = Color(0xFFFFFFFF),
    bearishContainer = Color(0xFFBEE6C2), onBearishContainer = Color(0xFF133B1D),
    warning = Color(0xFFAC6B00), onWarning = Color(0xFFFFFFFF),
    success = Color(0xFF3D7045), onSuccess = Color(0xFFFFFFFF),
    neutral = Color(0xFF5F665A), onNeutral = Color(0xFFFFFFFF),
)
private val forestGreenDarkSemantic = QuantSemanticColors(
    bullish = Color(0xFFF3B5AA), onBullish = Color(0xFF56180E),
    bullishContainer = Color(0xFF6E2014), onBullishContainer = Color(0xFFFFDAD4),
    bearish = Color(0xFFA2D5A8), onBearish = Color(0xFF083914),
    bearishContainer = Color(0xFF1E5229), onBearishContainer = Color(0xFFBEE6C2),
    warning = Color(0xFFE2BE82), onWarning = Color(0xFF3B2A00),
    success = Color(0xFFA8D3AD), onSuccess = Color(0xFF123A1A),
    neutral = Color(0xFFB4BAAE), onNeutral = Color(0xFF2A3026),
)

private val oceanBlueLightSemantic = QuantSemanticColors(
    bullish = Color(0xFFB3261E), onBullish = Color(0xFFFFFFFF),
    bullishContainer = Color(0xFFFFDAD6), onBullishContainer = Color(0xFF6B130E),
    bearish = Color(0xFF2C705D), onBearish = Color(0xFFFFFFFF),
    bearishContainer = Color(0xFFB4E6D6), onBearishContainer = Color(0xFF07372C),
    warning = Color(0xFFB36B00), onWarning = Color(0xFFFFFFFF),
    success = Color(0xFF326F5E), onSuccess = Color(0xFFFFFFFF),
    neutral = Color(0xFF5A646B), onNeutral = Color(0xFFFFFFFF),
)
private val oceanBlueDarkSemantic = QuantSemanticColors(
    bullish = Color(0xFFFFB4AB), onBullish = Color(0xFF5C0F0B),
    bullishContainer = Color(0xFF6B130E), onBullishContainer = Color(0xFFFFDAD6),
    bearish = Color(0xFF98D8C4), onBearish = Color(0xFF00392C),
    bearishContainer = Color(0xFF164F40), onBearishContainer = Color(0xFFB4E6D6),
    warning = Color(0xFFE5BD7D), onWarning = Color(0xFF3D2A00),
    success = Color(0xFF9DD5C5), onSuccess = Color(0xFF0D392C),
    neutral = Color(0xFFAFB5BA), onNeutral = Color(0xFF252B30),
)

private val amberGoldLightSemantic = QuantSemanticColors(
    bullish = Color(0xFFB24B39), onBullish = Color(0xFFFFFFFF),
    bullishContainer = Color(0xFFFFDAD4), onBullishContainer = Color(0xFF732316),
    bearish = Color(0xFF3C7042), onBearish = Color(0xFFFFFFFF),
    bearishContainer = Color(0xFFBFE6C2), onBearishContainer = Color(0xFF153B1C),
    warning = Color(0xFF9A6A00), onWarning = Color(0xFFFFFFFF),
    success = Color(0xFF4B6F42), onSuccess = Color(0xFFFFFFFF),
    neutral = Color(0xFF696153), onNeutral = Color(0xFFFFFFFF),
)
private val amberGoldDarkSemantic = QuantSemanticColors(
    bullish = Color(0xFFFFB4A6), onBullish = Color(0xFF5B160B),
    bullishContainer = Color(0xFF732316), onBullishContainer = Color(0xFFFFDAD4),
    bearish = Color(0xFFA7D5A8), onBearish = Color(0xFF0C3914),
    bearishContainer = Color(0xFF25522B), onBearishContainer = Color(0xFFBFE6C2),
    warning = Color(0xFFD7C07F), onWarning = Color(0xFF352C00),
    success = Color(0xFFB1D1A8), onSuccess = Color(0xFF1B3915),
    neutral = Color(0xFFB8B0A3), onNeutral = Color(0xFF302B22),
)

private val roseRedLightSemantic = QuantSemanticColors(
    bullish = Color(0xFFB53B45), onBullish = Color(0xFFFFFFFF),
    bullishContainer = Color(0xFFFFDADD), onBullishContainer = Color(0xFF721B24),
    bearish = Color(0xFF2F704B), onBearish = Color(0xFFFFFFFF),
    bearishContainer = Color(0xFFB8E7C8), onBearishContainer = Color(0xFF0D3B22),
    warning = Color(0xFFB76C00), onWarning = Color(0xFFFFFFFF),
    success = Color(0xFF3D6F49), onSuccess = Color(0xFFFFFFFF),
    neutral = Color(0xFF6B5F62), onNeutral = Color(0xFFFFFFFF),
)
private val roseRedDarkSemantic = QuantSemanticColors(
    bullish = Color(0xFFFFB3BA), onBullish = Color(0xFF5D1018),
    bullishContainer = Color(0xFF721B24), onBullishContainer = Color(0xFFFFDADD),
    bearish = Color(0xFF9BD8AF), onBearish = Color(0xFF00391E),
    bearishContainer = Color(0xFF19502F), onBearishContainer = Color(0xFFB8E7C8),
    warning = Color(0xFFE6BD7F), onWarning = Color(0xFF3D2A00),
    success = Color(0xFFA6D3AF), onSuccess = Color(0xFF123A1C),
    neutral = Color(0xFFB9ADB0), onNeutral = Color(0xFF30272A),
)
// endregion

fun resolveSemanticColors(theme: AppColorTheme, useDarkColors: Boolean): QuantSemanticColors =
    when (theme) {
        AppColorTheme.WarmBrownRed -> if (useDarkColors) warmBrownRedDarkSemantic else warmBrownRedLightSemantic
        AppColorTheme.IndigoBlue -> if (useDarkColors) indigoBlueDarkSemantic else indigoBlueLightSemantic
        AppColorTheme.TealGreen -> if (useDarkColors) tealGreenDarkSemantic else tealGreenLightSemantic
        AppColorTheme.VioletPurple -> if (useDarkColors) violetPurpleDarkSemantic else violetPurpleLightSemantic
        AppColorTheme.ForestGreen -> if (useDarkColors) forestGreenDarkSemantic else forestGreenLightSemantic
        AppColorTheme.OceanBlue -> if (useDarkColors) oceanBlueDarkSemantic else oceanBlueLightSemantic
        AppColorTheme.AmberGold -> if (useDarkColors) amberGoldDarkSemantic else amberGoldLightSemantic
        AppColorTheme.RoseRed -> if (useDarkColors) roseRedDarkSemantic else roseRedLightSemantic
        AppColorTheme.GraphiteGray -> if (useDarkColors) indigoBlueDarkSemantic else indigoBlueLightSemantic
        AppColorTheme.SkyBlue -> if (useDarkColors) oceanBlueDarkSemantic else oceanBlueLightSemantic
        AppColorTheme.MintCyan -> if (useDarkColors) tealGreenDarkSemantic else tealGreenLightSemantic
        AppColorTheme.CoralOrange -> if (useDarkColors) warmBrownRedDarkSemantic else warmBrownRedLightSemantic
        AppColorTheme.PlumPurple -> if (useDarkColors) violetPurpleDarkSemantic else violetPurpleLightSemantic
        AppColorTheme.SteelBlueGray -> if (useDarkColors) indigoBlueDarkSemantic else indigoBlueLightSemantic
    }

val LocalQuantColors = staticCompositionLocalOf<QuantSemanticColors> {
    error("QuantSemanticColors not provided — wrap content in AppTheme")
}

/** 在 Composable 中以 `MaterialTheme.quantColors` 形式访问语义色，与 `colorScheme` 风格一致。 */
val MaterialTheme.quantColors: QuantSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalQuantColors.current
