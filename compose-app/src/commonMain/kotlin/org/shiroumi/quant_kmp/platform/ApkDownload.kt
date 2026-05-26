package org.shiroumi.quant_kmp.platform

/**
 * 触发 APK 文件下载
 * 各平台自行实现下载方式
 */
expect fun downloadApk(url: String)
