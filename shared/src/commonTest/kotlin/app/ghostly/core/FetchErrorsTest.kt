package app.ghostly.core

import app.ghostly.core.sub.FetchErrors
import app.ghostly.core.sub.SubscriptionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchErrorsTest {

    @Test
    fun androidDnsFailureIsExplained() {
        val e = Exception("Unable to resolve host \"subscription.mywisp.uk\": No address associated with hostname")
        assertTrue(FetchErrors.describe(e).startsWith("не удаётся найти сервер подписки"))
    }

    @Test
    fun timeoutAndTls() {
        assertTrue("вовремя" in FetchErrors.describe(Exception("Request timeout has expired [url=https://x]")))
        assertTrue("защищённое" in FetchErrors.describe(Exception("wrap", Exception("Trust anchor for certification path not found."))))
    }

    @Test
    fun httpStatusesInRussian() {
        assertEquals(
            "подписка не найдена: ссылка устарела или подписку удалили",
            FetchErrors.describe(SubscriptionException("HTTP 404: Not Found", definitive = true)),
        )
        val limit = FetchErrors.describe(SubscriptionException("HTTP 403: Device limit reached", definitive = true))
        assertTrue(limit.startsWith("доступ к подписке закрыт") && limit.endsWith("Сообщение провайдера: Device limit reached"))
        assertTrue("ошибка 502" in FetchErrors.describe(SubscriptionException("HTTP 502")))
    }

    @Test
    fun russianMessagesPassThrough() {
        assertEquals("в подписке нет поддерживаемых серверов", FetchErrors.describe(SubscriptionException("В подписке нет поддерживаемых серверов")))
    }
}
