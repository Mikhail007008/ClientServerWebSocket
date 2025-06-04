package com.example.clientera.util

/**
 * Расширение для строки, вычисляет MD5 хэш строки.
 * @return MD5 хэш в виде 32-символьной шестнадцатеричной строки.
 */

import java.math.BigInteger
import java.security.MessageDigest

fun String.toMD5(): String {
    val md = MessageDigest.getInstance("MD5")
    val messageDigest = md.digest(this.toByteArray())
    val no = BigInteger(1, messageDigest)
    var hashText = no.toString(16)

    while (hashText.length < 32) {
        hashText = "0$hashText"
    }
    return hashText
}