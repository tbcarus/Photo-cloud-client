package ru.tbcarus.photo_cloud_client.transfer

import java.text.Normalizer

// Повторяет правила имён обычной библиотеки Server, чтобы не передавать файл, который будет отклонён.
// Решение остаётся за Server: его ответ обрабатывается и при пройденной проверке.
private val FORBIDDEN_CHARS = Regex("[/\\\\:*?\"<>|\\p{Cc}\\p{Cs}]")
private val WINDOWS_RESERVED_STEM = Regex("(?i)CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[0-9¹²³]|LPT[0-9¹²³]")
internal const val MAX_NAME_BYTES = 255

/** Причина, по которой Server не примет имя файла или папки; null — имя допустимо. Имя не исправляется. */
fun libraryNameProblem(name: String?): String? = when {
    name.isNullOrBlank() -> "Пустое имя"
    FORBIDDEN_CHARS.containsMatchIn(name) -> "Имя содержит / \\ : * ? \" < > | или управляющие символы"
    name == "." || name == ".." -> "Имя не может быть . или .."
    name.endsWith(".") || name.endsWith(" ") -> "Имя не может заканчиваться точкой или пробелом"
    !Normalizer.isNormalized(name, Normalizer.Form.NFC) -> "Имя не в форме Unicode NFC"
    WINDOWS_RESERVED_STEM.matches(name.substringBefore('.').trimEnd()) -> "Имя устройства Windows"
    name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> "Имя длиннее $MAX_NAME_BYTES байт"
    else -> null
}

/** Имя Camera в ROOT зарезервировано Server без учёта регистра. */
fun isReservedRootName(name: String): Boolean = name.equals("Camera", ignoreCase = true)
