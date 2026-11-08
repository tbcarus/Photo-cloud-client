package ru.tbcarus.photo_cloud_client.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Сроки доступа и разбор адресов: чистые функции формы «Поделиться». */
class LibrarySharingTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val now = ZonedDateTime.of(2026, 11, 8, 15, 30, 45, 123_000_000, moscow)

    @Test
    fun neverDaysAndUntilDateGiveMomentsWithDeviceOffset() {
        assertEquals(ExpiryValue.Valid(null), expiryValue(ExpiryChoice.Never, now))
        // N календарных дней к текущему моменту, до секунд.
        assertEquals(ExpiryValue.Valid("2026-11-15T15:30:45+03:00"), expiryValue(ExpiryChoice.Days("7"), now))
        // «До даты» включает весь день: граница — начало следующего дня в поясе устройства.
        assertEquals(ExpiryValue.Valid("2026-11-09T00:00:00+03:00"), expiryValue(ExpiryChoice.Until(LocalDate.of(2026, 11, 8)), now))
    }

    @Test
    fun zeroNegativeAndPastExpiriesAreRejected() {
        assertEquals(ExpiryValue.Invalid("Укажите положительное число дней"), expiryValue(ExpiryChoice.Days("0"), now))
        assertEquals(ExpiryValue.Invalid("Укажите положительное число дней"), expiryValue(ExpiryChoice.Days(""), now))
        assertEquals(ExpiryValue.Invalid("Дата окончания уже прошла"), expiryValue(ExpiryChoice.Until(LocalDate.of(2026, 11, 7)), now))
        assertEquals(ExpiryValue.Invalid("Выберите дату окончания"), expiryValue(ExpiryChoice.Until(), now))
    }

    @Test
    fun emailsAreSplitTrimmedAndDeduplicated() {
        assertEquals(listOf("a@test", "b@test", "c@test"), parseEmails(" a@test,b@test\n\nA@TEST ; c@test ,"))
        // Последний включённый день срока: граница начала 09.11 соответствует дате 08.11.
        assertEquals(ExpiryChoice.Until(LocalDate.of(2026, 11, 8)), expiryChoiceOf("2026-11-08T21:00:00Z", moscow))
    }
}
