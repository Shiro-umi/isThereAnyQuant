package org.shiroumi.quant_kmp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import org.shiroumi.compose_app.generated.resources.NotoSansSC_Regular
import org.shiroumi.compose_app.generated.resources.Res

// 在代码中引用
@Composable
fun montserratFontFamily(): FontFamily {
    return FontFamily(
        Font(
            resource = Res.font.NotoSansSC_Regular,
            weight = FontWeight.Normal,
            style = FontStyle.Normal
        ),
//        Font(
//            resource = Res.font.NotoSansSC_Bold,
//            weight = FontWeight.Bold,
//            style = FontStyle.Normal
//        ),
//        Font(
//            resource = Res.font.NotoSansSC_Light,
//            weight = FontWeight.Light,
//            style = FontStyle.Normal
//        ),
    )
}

@Composable
fun AppTypography(): Typography {
    // 1. 先获取你定义的字体家族
    val montserrat = montserratFontFamily()

    // 2. 获取 Material 3 默认的排版样式
    val defaultTypography = Typography()

    // 3. 返回一个新的 Typography 对象，通过 copy() 方法为每个样式设置你的字体
    return Typography(
        displayLarge = defaultTypography.displayLarge.copy(fontFamily = montserrat),
        displayMedium = defaultTypography.displayMedium.copy(fontFamily = montserrat),
        displaySmall = defaultTypography.displaySmall.copy(fontFamily = montserrat),

        headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = montserrat),
        headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = montserrat),
        headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = montserrat),

        titleLarge = defaultTypography.titleLarge.copy(fontFamily = montserrat),
        titleMedium = defaultTypography.titleMedium.copy(fontFamily = montserrat),
        titleSmall = defaultTypography.titleSmall.copy(fontFamily = montserrat),

        bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = montserrat),
        bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = montserrat),
        bodySmall = defaultTypography.bodySmall.copy(fontFamily = montserrat),

        labelLarge = defaultTypography.labelLarge.copy(fontFamily = montserrat),
        labelMedium = defaultTypography.labelMedium.copy(fontFamily = montserrat),
        labelSmall = defaultTypography.labelSmall.copy(fontFamily = montserrat)
    )
}