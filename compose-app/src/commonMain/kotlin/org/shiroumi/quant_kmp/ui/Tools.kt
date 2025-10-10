package org.shiroumi.quant_kmp.ui

val String.suffix: String
    get() {
        if (length != 6) return ".Nothing"
        return when {
            startsWith("60") || startsWith("68") || startsWith("90") -> ".SH"
            startsWith("00") || startsWith("30") -> ".SZ"
            startsWith("43") || startsWith("83") || startsWith("87") -> ".BJ"
            else -> ".Nothing"
        }
    }